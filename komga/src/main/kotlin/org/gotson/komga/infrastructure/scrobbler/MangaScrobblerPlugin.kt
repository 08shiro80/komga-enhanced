package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.PluginLog
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginLogRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

private val logger = KotlinLogging.logger {}

@Component
class MangaScrobblerPlugin(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val pluginLogRepository: PluginLogRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val objectMapper: ObjectMapper,
) {
  private val pluginId = "manga-scrobbler"

  private val anilistClient = RestClient.create("https://graphql.anilist.co")
  private val malClient = RestClient.create("https://api.myanimelist.net")

  // Fire-and-forget so we don't block the read-progress save path.
  private val executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "scrobbler-worker").apply { isDaemon = true }
  }

  // In-memory dedupe: seriesId -> highest synced chapter number.
  // Cleared on JVM restart; AniList/MAL updates are idempotent so this is purely an optimization.
  private val lastSynced = ConcurrentHashMap<String, Int>()

  private val anilistIdRegex = Regex("""anilist\.co/manga/(\d+)""", RegexOption.IGNORE_CASE)
  private val malIdRegex = Regex("""myanimelist\.net/manga/(\d+)""", RegexOption.IGNORE_CASE)

  @EventListener(ApplicationReadyEvent::class)
  fun init() {
    val plugin = pluginRepository.findByIdOrNull(pluginId)
    if (plugin == null) {
      logger.debug { "Scrobbler plugin not yet installed" }
      return
    }
    logger.info { "Scrobbler plugin loaded (enabled=${plugin.enabled})" }
  }

  @EventListener
  fun onReadProgressChanged(event: DomainEvent.ReadProgressChanged) {
    if (!isEnabled()) return
    val progress = event.progress
    if (!progress.completed) return

    executor.submit {
      try {
        handle(progress.bookId, progress.userId)
      } catch (e: Exception) {
        logger.error(e) { "Scrobbler failed for book ${progress.bookId}" }
        log(LogLevel.ERROR, "Unexpected error for book ${progress.bookId}: ${e.message}", e)
      }
    }
  }

  private fun handle(bookId: String, userId: String) {
    val config = loadConfig()

    val filterUserId = config["sync_user_id"]?.takeIf { it.isNotBlank() }
    if (filterUserId != null && filterUserId != userId) {
      logger.debug { "Skipping scrobble for user $userId (filter=$filterUserId)" }
      return
    }

    val book = bookRepository.findByIdOrNull(bookId)
    if (book == null) {
      log(LogLevel.WARN, "Book $bookId not found")
      return
    }

    val bookMeta = bookMetadataRepository.findByIdOrNull(bookId)
    if (bookMeta == null) {
      log(LogLevel.WARN, "BookMetadata for $bookId not found")
      return
    }

    val seriesMeta = seriesMetadataRepository.findByIdOrNull(book.seriesId)
    if (seriesMeta == null) {
      log(LogLevel.WARN, "SeriesMetadata for ${book.seriesId} not found")
      return
    }

    val chapterNumber = bookMeta.numberSort.toInt()
    if (chapterNumber <= 0) {
      log(LogLevel.DEBUG, "Skipping '${seriesMeta.title}' — non-positive chapter number (${bookMeta.numberSort})")
      return
    }

    // Dedupe: only update if this chapter is higher than what we last synced.
    val previous = lastSynced[book.seriesId] ?: 0
    if (chapterNumber <= previous) {
      logger.debug { "Already synced chapter $chapterNumber or higher for '${seriesMeta.title}'" }
      return
    }

    val ids = resolveTrackerIds(seriesMeta.title, seriesMeta.links, config)
    if (ids.anilistId == null && ids.malId == null) {
      log(LogLevel.INFO, "No tracker mapping found for '${seriesMeta.title}'")
      return
    }

    val tracker = config["tracker"] ?: "both"
    var anySuccess = false

    if (tracker in listOf("anilist", "both") && ids.anilistId != null) {
      val token = config["anilist_token"]
      if (token.isNullOrBlank()) {
        log(LogLevel.WARN, "AniList token not configured")
      } else if (updateAnilist(ids.anilistId, chapterNumber, token, seriesMeta.title)) {
        anySuccess = true
      }
    }

    if (tracker in listOf("mal", "both") && ids.malId != null) {
      val token = config["mal_access_token"]
      if (token.isNullOrBlank()) {
        log(LogLevel.WARN, "MAL access token not configured")
      } else if (updateMal(ids.malId, chapterNumber, token, seriesMeta.title)) {
        anySuccess = true
      }
    }

    if (anySuccess) {
      lastSynced[book.seriesId] = max(previous, chapterNumber)
    }
  }

  private data class TrackerIds(val anilistId: Int?, val malId: Int?)

  private fun resolveTrackerIds(
    seriesTitle: String,
    links: List<org.gotson.komga.domain.model.WebLink>,
    config: Map<String, String?>,
  ): TrackerIds {
    // 1. Auto-detect from SeriesMetadata.links if enabled
    var anilistId: Int? = null
    var malId: Int? = null

    if ((config["auto_detect_links"] ?: "true").toBoolean()) {
      for (link in links) {
        val url = link.url.toString()
        if (anilistId == null) anilistIdRegex.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { anilistId = it }
        if (malId == null) malIdRegex.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { malId = it }
      }
    }

    // 2. Manual mappings JSON override (always wins if present)
    val mappingsJson = config["mappings"]
    if (!mappingsJson.isNullOrBlank()) {
      try {
        val tree = objectMapper.readTree(mappingsJson)
        // Match by exact title, case-insensitive
        val match = tree.fields().asSequence().firstOrNull { it.key.equals(seriesTitle, ignoreCase = true) }
        match?.value?.let { node ->
          node.get("anilist_id")?.asInt(0)?.takeIf { it > 0 }?.let { anilistId = it }
          node.get("mal_id")?.asInt(0)?.takeIf { it > 0 }?.let { malId = it }
        }
      } catch (e: Exception) {
        log(LogLevel.ERROR, "Invalid 'mappings' JSON: ${e.message}")
      }
    }

    return TrackerIds(anilistId, malId)
  }

  private fun updateAnilist(mediaId: Int, progress: Int, token: String, title: String): Boolean {
    val mutation = """
      mutation (${"$"}id: Int, ${"$"}progress: Int) {
        SaveMediaListEntry(mediaId: ${"$"}id, progress: ${"$"}progress) {
          id
          progress
          status
        }
      }
    """.trimIndent()

    val body = mapOf(
      "query" to mutation,
      "variables" to mapOf("id" to mediaId, "progress" to progress),
    )

    return try {
      val response = anilistClient
        .post()
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(String::class.java)

      val json: JsonNode? = response?.let { objectMapper.readTree(it) }
      val errors = json?.get("errors")
      if (errors != null && errors.isArray && errors.size() > 0) {
        log(LogLevel.ERROR, "AniList error for '$title' (id=$mediaId): ${errors.toString()}")
        false
      } else {
        log(LogLevel.INFO, "AniList: '$title' → chapter $progress")
        true
      }
    } catch (e: RestClientException) {
      log(LogLevel.ERROR, "AniList request failed for '$title': ${e.message}", e)
      false
    }
  }

  private fun updateMal(mediaId: Int, progress: Int, token: String, title: String): Boolean {
    return try {
      val response = malClient
        .patch()
        .uri("/v2/manga/$mediaId/my_list_status")
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body("num_chapters_read=$progress")
        .retrieve()
        .body(String::class.java)

      log(LogLevel.INFO, "MAL: '$title' → chapter $progress")
      true
    } catch (e: RestClientException) {
      log(LogLevel.ERROR, "MAL request failed for '$title' (id=$mediaId): ${e.message}", e)
      false
    }
  }

  private fun isEnabled(): Boolean =
    try {
      pluginRepository.findByIdOrNull(pluginId)?.enabled == true
    } catch (_: Exception) {
      false
    }

  private fun loadConfig(): Map<String, String?> =
    pluginConfigRepository
      .findByPluginId(pluginId)
      .associate { it.configKey to it.configValue }

  private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
    try {
      pluginLogRepository.insert(
        PluginLog(
          id = TsidCreator.getTsid256().toString(),
          pluginId = pluginId,
          logLevel = level,
          message = message,
          exceptionTrace = throwable?.stackTraceToString(),
        ),
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to write plugin log: $message" }
    }
  }
}
