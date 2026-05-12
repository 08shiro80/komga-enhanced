package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.PluginLog
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginLogRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Periodically pulls remote read progress from AniList / MAL / Kitsu and applies it
 * to Komga when the remote value is ahead of what we last synced.
 *
 * [SyncState.seriesId] is always the **Komga** series id; external tracker ids are
 * resolved from [SeriesMetadata.links] (same rules as [MangaScrobblerPlugin]).
 */
@Component
class MangaSyncPullerPlugin(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val pluginLogRepository: PluginLogRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesRepository: SeriesRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val syncStateRepository: SyncStateRepository,
  private val trackerIdResolver: TrackerIdResolver,
  private val objectMapper: ObjectMapper,
) {
  private val pluginId = "manga-scrobbler"
  private val timeoutFactory = JdkClientHttpRequestFactory(
    java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
  ).apply { setReadTimeout(Duration.ofSeconds(15)) }
  private val anilistClient = RestClient.builder().baseUrl("https://graphql.anilist.co").requestFactory(timeoutFactory).build()
  private val malClient = RestClient.builder().baseUrl("https://api.myanimelist.net").requestFactory(timeoutFactory).build()
  private val kitsuClient = RestClient.builder().baseUrl("https://kitsu.app/api/edge").requestFactory(timeoutFactory).build()

  @Scheduled(fixedDelay = 3600_000, initialDelay = 120_000)
  fun pullAllTrackers() {
    if (!isEnabled()) return
    val config = loadConfig()
    val syncUserId = config["sync_user_id"]?.takeIf { it.isNotBlank() } ?: return
    val excludedLibs = parseExcludedLibraryIds(config)
    logger.debug { "Sync puller: checking tracker progress updates" }

    pullAnilist(config, syncUserId, excludedLibs)
    pullMal(config, syncUserId, excludedLibs)
    pullKitsu(config, syncUserId, excludedLibs)
  }

  private fun pullAnilist(
    config: Map<String, String?>,
    userId: String,
    excludedLibs: Set<String>,
  ) {
    val token = config["anilist_token"] ?: return
    val states = syncStateRepository.findByTracker("anilist")
    for (state in states) {
      if (shouldSkipKomgaSeries(state.seriesId, excludedLibs)) continue
      val anilistId = trackerIdResolver.resolveFromKomgaSeriesId(state.seriesId, config).anilistId ?: continue
      val query =
        """
        query (${'$'}id: Int) {
          MediaList(mediaId: ${'$'}id) {
            progress
            status
            updatedAt
          }
        }
        """.trimIndent()
      try {
        val response =
          anilistClient
            .post()
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("query" to query, "variables" to mapOf("id" to anilistId)))
            .retrieve()
            .body(String::class.java)

        val json = response?.let { objectMapper.readTree(it) }
        val mediaList = json?.get("data")?.get("MediaList")
        if (mediaList == null || mediaList.isNull) continue

        val remoteProgress = mediaList.get("progress")?.asInt(0) ?: 0
        val remoteUpdatedAt = mediaList.get("updatedAt")?.asLong(0) ?: 0
        if (remoteProgress > state.progress && remoteUpdatedAt > 0) {
          applyRemoteProgress(state, remoteProgress, remoteUpdatedAt, userId, "AniList", excludedLibs)
        }
      } catch (e: Exception) {
        logger.debug(e) { "AniList pull failed for series ${state.seriesId}" }
      }
    }
  }

  private fun pullMal(
    config: Map<String, String?>,
    userId: String,
    excludedLibs: Set<String>,
  ) {
    val token = config["mal_access_token"] ?: return
    val states = syncStateRepository.findByTracker("mal")
    for (state in states) {
      if (shouldSkipKomgaSeries(state.seriesId, excludedLibs)) continue
      val malId = trackerIdResolver.resolveFromKomgaSeriesId(state.seriesId, config).malId ?: continue
      try {
        val response =
          malClient
            .get()
            .uri("/v2/manga/$malId/my_list_status")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(String::class.java)

        val json = response?.let { objectMapper.readTree(it) }
        val remoteProgress = json?.get("num_chapters_read")?.asInt(0) ?: continue

        if (remoteProgress > state.progress) {
          applyRemoteProgress(state, remoteProgress, Instant.now().epochSecond, userId, "MAL", excludedLibs)
        }
      } catch (e: RestClientException) {
        if (e.message?.contains("404") != true) {
          logger.debug(e) { "MAL pull failed for series ${state.seriesId}" }
        }
      }
    }
  }

  private fun pullKitsu(
    config: Map<String, String?>,
    userId: String,
    excludedLibs: Set<String>,
  ) {
    val token = config["kitsu_token"] ?: return
    val states = syncStateRepository.findByTracker("kitsu")
    for (state in states) {
      if (shouldSkipKomgaSeries(state.seriesId, excludedLibs)) continue
      val kitsuId = trackerIdResolver.resolveFromKomgaSeriesId(state.seriesId, config).kitsuId ?: continue
      try {
        val response =
          kitsuClient
            .get()
            .uri("/library-entries?filter[mangaId]=$kitsuId&page[limit]=1")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.api+json")
            .retrieve()
            .body(String::class.java)

        val json = response?.let { objectMapper.readTree(it) }
        val attrs = json?.get("data")?.firstOrNull()?.get("attributes")
        if (attrs == null) continue

        val remoteProgress = attrs.get("progress")?.asInt(0) ?: 0
        if (remoteProgress > state.progress) {
          applyRemoteProgress(state, remoteProgress, Instant.now().epochSecond, userId, "Kitsu", excludedLibs)
        }
      } catch (e: Exception) {
        logger.debug(e) { "Kitsu pull failed for series ${state.seriesId}" }
      }
    }
  }

  private fun parseExcludedLibraryIds(config: Map<String, String?>): Set<String> =
    config["exclude_library_ids"]
      ?.split(',')
      ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
      ?.toSet()
      ?: emptySet()

  private fun shouldSkipKomgaSeries(
    komgaSeriesId: String,
    excludedLibs: Set<String>,
  ): Boolean {
    if (excludedLibs.isEmpty()) return false
    val libraryId = seriesRepository.findByIdOrNull(komgaSeriesId)?.libraryId ?: return false
    return libraryId in excludedLibs
  }

  private fun applyRemoteProgress(
    state: SyncState,
    remoteProgress: Int,
    remoteUpdatedAt: Long,
    userId: String,
    trackerName: String,
    excludedLibs: Set<String>,
  ) {
    try {
      if (shouldSkipKomgaSeries(state.seriesId, excludedLibs)) return

      val books = bookRepository.findAllBySeriesId(state.seriesId)
      val bookMetaMap = books.associate { it.id to bookMetadataRepository.findByIdOrNull(it.id) }
      val targetBook =
        bookMetaMap
          .filterValues { it != null }
          .entries
          .firstOrNull { (_, meta) -> meta!!.numberSort.toInt() == remoteProgress }
          ?.let { (bookId, _) -> bookRepository.findByIdOrNull(bookId) }
          ?: return

      readProgressRepository.save(
        ReadProgress(
          bookId = targetBook.id,
          userId = userId,
          page = 1,
          completed = true,
          readDate = LocalDateTime.now(),
          deviceId = "manga-sync-puller",
          deviceName = "Manga Sync Puller ($trackerName)",
        ),
      )

      syncStateRepository.update(
        state.copy(
          progress = remoteProgress,
          bookId = targetBook.id,
          lastSyncTimestamp = LocalDateTime.now(ZoneId.of("Z")),
          lastUpdateTimestamp = LocalDateTime.now(ZoneId.of("Z")),
          lastModifiedDate = LocalDateTime.now(ZoneId.of("Z")),
        ),
      )
      log(LogLevel.INFO, "$trackerName pull: komga series ${state.seriesId} → chapter $remoteProgress")
    } catch (e: Exception) {
      logger.warn(e) { "Failed to apply remote progress for series ${state.seriesId}" }
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
