package org.gotson.komga.infrastructure.comicscrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.metadata.metron.MetronHttp
import org.gotson.komga.infrastructure.rate.MetronRateLimiter
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import java.net.URLEncoder
import java.time.LocalDateTime
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

@Component
class ComicScrobblerPlugin(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val syncStateRepository: SyncStateRepository,
  private val userTrackerRepository: UserTrackerRepository,
  private val objectMapper: ObjectMapper,
  private val rateLimiter: MetronRateLimiter,
) {
  private val pluginId = "comic-scrobbler"

  private val metronClient = MetronHttp.restClient()

  private val executor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "comic-scrobbler-worker").apply { isDaemon = true }
    }

  private val scrobbledIssues = ConcurrentHashMap<String, MutableSet<Int>>()

  private val metronSeriesRegex = Regex("""metron\.cloud/series/(\d+)""", RegexOption.IGNORE_CASE)

  @EventListener(ApplicationReadyEvent::class)
  fun init() {
    val plugin = pluginRepository.findByIdOrNull(pluginId)
    if (plugin == null) {
      logger.debug { "ComicScrobbler plugin not yet installed" }
      return
    }
    syncStateRepository.findByTracker(UserTracker.METRON).forEach {
      scrobbledIssues.computeIfAbsent(dedupeKey(it.userId, it.seriesId)) { ConcurrentHashMap.newKeySet() }.addAll(1..it.progress)
    }
    logger.info { "ComicScrobbler plugin loaded (enabled=${plugin.enabled}), hydrated ${scrobbledIssues.size} synced series" }
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
        logger.error(e) { "ComicScrobbler failed for book ${progress.bookId}" }
        log(LogLevel.ERROR, "Unexpected error: ${e.message}", e)
      }
    }
  }

  private fun handle(
    bookId: String,
    userId: String,
  ) {
    val config = loadConfig()
    val account = userTrackerRepository.findByUserIdAndTracker(userId, UserTracker.METRON)
    if (account == null || !account.active) {
      logger.debug { "Skipping comic scrobble for user $userId: no linked Metron account" }
      return
    }
    val mUser = account[UserTracker.KEY_USERNAME] ?: return
    val mPass = account[UserTracker.KEY_PASSWORD] ?: return

    val book =
      bookRepository.findByIdOrNull(bookId) ?: run {
        log(LogLevel.WARN, "Book $bookId not found")
        return
      }
    val excludedLibs = parseExcludedLibraryIds(config)
    if (book.libraryId in excludedLibs) {
      logger.debug { "Skipping comic scrobble: library ${book.libraryId} is in exclude_library_ids" }
      return
    }
    val bookMeta =
      bookMetadataRepository.findByIdOrNull(bookId) ?: run {
        log(LogLevel.WARN, "BookMetadata not found")
        return
      }
    val seriesMeta =
      seriesMetadataRepository.findByIdOrNull(book.seriesId) ?: run {
        log(LogLevel.WARN, "SeriesMetadata not found")
        return
      }

    val numberSort = bookMeta.numberSort
    if (numberSort % 1f != 0f) {
      log(LogLevel.DEBUG, "Skipping fractional issue number ($numberSort)")
      return
    }
    val issueNumber = numberSort.toInt()
    if (issueNumber <= 0) {
      log(LogLevel.DEBUG, "Skipping non-positive issue number ($numberSort)")
      return
    }

    val key = dedupeKey(userId, book.seriesId)
    val synced = scrobbledIssues.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }
    if (issueNumber in synced) return

    val issueId = resolveIssueId(seriesMeta.title, issueNumber, seriesMeta.links, config, mUser, mPass)
    if (issueId == null) {
      log(LogLevel.INFO, "No Metron issue found for '${seriesMeta.title}' #$issueNumber")
      return
    }

    if (scrobble(issueId, mUser, mPass, seriesMeta.title)) {
      synced.add(issueNumber)
      recordMetronSync(userId, book.seriesId, issueNumber, book.id)
    }
  }

  private fun dedupeKey(
    userId: String,
    seriesId: String,
  ) = "$userId:$seriesId"

  private fun recordMetronSync(
    userId: String,
    seriesId: String,
    issue: Int,
    bookId: String,
  ) {
    try {
      val now = LocalDateTime.now()
      val existing = syncStateRepository.findByUserIdAndSeriesIdAndTracker(userId, seriesId, UserTracker.METRON)
      if (existing != null) {
        syncStateRepository.update(
          existing.copy(
            bookId = bookId,
            progress = maxOf(existing.progress, issue),
            lastSyncTimestamp = now,
            lastModifiedDate = now,
          ),
        )
      } else {
        syncStateRepository.insert(
          SyncState(
            id = TsidCreator.getTsid256().toString(),
            userId = userId,
            bookId = bookId,
            seriesId = seriesId,
            tracker = UserTracker.METRON,
            progress = issue,
            lastSyncTimestamp = now,
          ),
        )
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to record Metron sync state for user $userId series $seriesId" }
    }
  }

  private fun resolveIssueId(
    seriesTitle: String,
    issueNumber: Int,
    links: List<WebLink>,
    config: Map<String, String?>,
    mUser: String,
    mPass: String,
  ): Int? {
    val mappingsJson = config["mappings"]
    if (!mappingsJson.isNullOrBlank()) {
      try {
        val tree = objectMapper.readTree(mappingsJson)
        val match = tree.fields().asSequence().firstOrNull { it.key.equals(seriesTitle, ignoreCase = true) }
        match?.value?.let { node ->
          node
            .get("metron_issue_id")
            ?.asInt(0)
            ?.takeIf { it > 0 }
            ?.let { return it }
        }
      } catch (e: Exception) {
        log(LogLevel.ERROR, "Invalid mappings JSON: ${e.message}")
      }
    }

    if ((config["auto_detect_links"] ?: "true").toBoolean()) {
      for (link in links) {
        val url = link.url.toString()
        metronSeriesRegex.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { seriesId ->
          searchMetronIssueBySeriesId(seriesId, issueNumber, mUser, mPass)?.let { return it }
        }
      }
    }

    searchMetronIssue(seriesTitle, issueNumber, mUser, mPass)?.let { return it }
    return null
  }

  private fun searchMetronIssue(
    seriesTitle: String,
    issueNumber: Int,
    username: String,
    password: String,
  ): Int? {
    return try {
      waitForMetronSlot()
      val encodedName = URLEncoder.encode(seriesTitle, "UTF-8")
      val seriesResponse =
        metronClient
          .get()
          .uri("/api/series/?name=$encodedName")
          .header("Authorization", basicAuth(username, password))
          .retrieve()
          .body(String::class.java)

      val seriesJson = seriesResponse?.let { objectMapper.readTree(it) }
      val results = seriesJson?.get("results")
      if (results == null || !results.isArray || results.size() == 0) return null

      val seriesId = results[0].get("id").asInt()
      searchMetronIssueBySeriesId(seriesId, issueNumber, username, password)
    } catch (e: Exception) {
      log(LogLevel.WARN, "Metron search failed: ${e.message}", e)
      null
    }
  }

  private fun searchMetronIssueBySeriesId(
    seriesId: Int,
    issueNumber: Int,
    username: String,
    password: String,
  ): Int? {
    return try {
      waitForMetronSlot()
      val encodedNumber = URLEncoder.encode(issueNumber.toString(), "UTF-8")
      val issueResponse =
        metronClient
          .get()
          .uri("/api/issue/?series_id=$seriesId&number=$encodedNumber")
          .header("Authorization", basicAuth(username, password))
          .retrieve()
          .body(String::class.java)

      val issueJson = issueResponse?.let { objectMapper.readTree(it) }
      val issueResults = issueJson?.get("results")
      if (issueResults == null || !issueResults.isArray || issueResults.size() == 0) {
        log(LogLevel.DEBUG, "No issue #$issueNumber for series $seriesId on Metron")
        return null
      }

      val issueId = issueResults[0].get("id").asInt()
      log(LogLevel.INFO, "Metron match: series $seriesId #$issueNumber → issue $issueId")
      issueId
    } catch (e: Exception) {
      log(LogLevel.WARN, "Metron issue search failed: ${e.message}", e)
      null
    }
  }

  private fun scrobble(
    issueId: Int,
    username: String,
    password: String,
    seriesTitle: String,
  ): Boolean =
    try {
      waitForMetronSlot()
      val today = LocalDateTime.now().toLocalDate().toString()
      metronClient
        .post()
        .uri("/api/collection/scrobble/")
        .header("Authorization", basicAuth(username, password))
        .contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("issue_id" to issueId, "date_read" to today))
        .retrieve()
        .body(String::class.java)
      log(LogLevel.INFO, "Metron: scrobbled '$seriesTitle' issue #$issueId")
      true
    } catch (e: RestClientException) {
      log(LogLevel.ERROR, "Metron scrobble failed for '$seriesTitle' (issue=$issueId): ${e.message}", e)
      false
    }

  private fun waitForMetronSlot() {
    while (!rateLimiter.tryAcquire()) {
      val backoff = rateLimiter.suggestedBackoffMs().coerceAtLeast(50)
      logger.debug { "Metron rate limit: waiting ${backoff}ms" }
      Thread.sleep(backoff)
    }
  }

  private fun basicAuth(
    username: String,
    password: String,
  ): String {
    val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    return "Basic $encoded"
  }

  private fun isEnabled(): Boolean =
    try {
      pluginRepository.findByIdOrNull(pluginId)?.enabled == true
    } catch (_: Exception) {
      false
    }

  private fun parseExcludedLibraryIds(config: Map<String, String?>): Set<String> =
    config["exclude_library_ids"]
      ?.split(',')
      ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
      ?.toSet()
      ?: emptySet()

  private fun loadConfig(): Map<String, String?> = pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue }

  private fun log(
    level: LogLevel,
    message: String,
    throwable: Throwable? = null,
  ) {
    val line = "[$pluginId] $message"
    when (level) {
      LogLevel.ERROR -> logger.error(throwable) { line }
      LogLevel.WARN -> logger.warn(throwable) { line }
      LogLevel.DEBUG -> logger.debug(throwable) { line }
      else -> logger.info(throwable) { line }
    }
  }
}
