package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

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
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesRepository: SeriesRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val syncStateRepository: SyncStateRepository,
  private val userTrackerRepository: UserTrackerRepository,
  private val trackerIdResolver: TrackerIdResolver,
  private val trackerTokenProvider: TrackerTokenProvider,
  private val objectMapper: ObjectMapper,
) {
  private val pluginId = "manga-scrobbler"
  private val timeoutFactory =
    JdkClientHttpRequestFactory(
      java.net.http.HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    ).apply { setReadTimeout(Duration.ofSeconds(15)) }
  private val anilistClient =
    RestClient
      .builder()
      .baseUrl("https://graphql.anilist.co")
      .requestFactory(timeoutFactory)
      .build()
  private val malClient =
    RestClient
      .builder()
      .baseUrl("https://api.myanimelist.net")
      .requestFactory(timeoutFactory)
      .build()
  private val kitsuClient =
    RestClient
      .builder()
      .baseUrl("https://kitsu.app/api/edge")
      .requestFactory(timeoutFactory)
      .build()

  @Scheduled(fixedDelay = 3600_000, initialDelay = 120_000)
  fun pullAllTrackers() {
    if (!isEnabled()) return
    val config = loadConfig()
    val excludedLibs = parseExcludedLibraryIds(config)
    logger.debug { "Sync puller: checking tracker progress updates" }

    activeAccounts(UserTracker.ANILIST).forEach { pullAnilist(it, config, excludedLibs) }
    activeAccounts(UserTracker.MAL).forEach { pullMal(it, config, excludedLibs) }
    activeAccounts(UserTracker.KITSU).forEach { pullKitsu(it, config, excludedLibs) }
  }

  private fun activeAccounts(tracker: String): List<UserTracker> = userTrackerRepository.findByTracker(tracker).filter { it.active }

  private fun pullAnilist(
    account: UserTracker,
    config: Map<String, String?>,
    excludedLibs: Set<String>,
  ) {
    val userId = account.userId
    val token = account[UserTracker.KEY_TOKEN] ?: return
    val states = syncStateRepository.findByUserIdAndTracker(userId, UserTracker.ANILIST)
    for (state in states) {
      if (shouldSkipKomgaSeries(state.seriesId, excludedLibs)) continue
      val anilistId = trackerIdResolver.resolveFromKomgaSeriesId(state.seriesId, config).anilistId ?: continue
      val query =
        """
        query (${'$'}id: Int) {
          Media(id: ${'$'}id) {
            mediaListEntry {
              progress
              status
              updatedAt
            }
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
        val mediaList = json?.get("data")?.get("Media")?.get("mediaListEntry")
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
    account: UserTracker,
    config: Map<String, String?>,
    excludedLibs: Set<String>,
  ) {
    val userId = account.userId
    var token = trackerTokenProvider.malToken(account) ?: return
    val states = syncStateRepository.findByUserIdAndTracker(userId, UserTracker.MAL)
    for (state in states) {
      if (shouldSkipKomgaSeries(state.seriesId, excludedLibs)) continue
      val malId = trackerIdResolver.resolveFromKomgaSeriesId(state.seriesId, config).malId ?: continue
      try {
        val response =
          try {
            requestMal(malId, token)
          } catch (e: RestClientResponseException) {
            if (!isUnauthorized(e)) throw e
            token = trackerTokenProvider.refreshMal(account) ?: return
            requestMal(malId, token)
          }

        val json = response?.let { objectMapper.readTree(it) }
        val listStatus = json?.get("my_list_status") ?: continue
        val remoteProgress = listStatus.get("num_chapters_read")?.asInt(0) ?: continue

        if (remoteProgress > state.progress) {
          applyRemoteProgress(state, remoteProgress, Instant.now().epochSecond, userId, "MAL", excludedLibs)
        }
      } catch (e: RestClientException) {
        val is404 = e is RestClientResponseException && e.statusCode.value() == 404
        if (!is404) {
          logger.debug(e) { "MAL pull failed for series ${state.seriesId}" }
        }
      } catch (e: Exception) {
        logger.debug(e) { "MAL pull failed for series ${state.seriesId}" }
      }
    }
  }

  private fun pullKitsu(
    account: UserTracker,
    config: Map<String, String?>,
    excludedLibs: Set<String>,
  ) {
    val userId = account.userId
    var token = trackerTokenProvider.kitsuToken(account) ?: return
    val kitsuUserId = trackerTokenProvider.kitsuUserId(account, token) ?: return
    val states = syncStateRepository.findByUserIdAndTracker(userId, UserTracker.KITSU)
    for (state in states) {
      if (shouldSkipKomgaSeries(state.seriesId, excludedLibs)) continue
      val kitsuId = trackerIdResolver.resolveFromKomgaSeriesId(state.seriesId, config).kitsuId ?: continue
      try {
        val response =
          try {
            requestKitsu(kitsuId, kitsuUserId, token)
          } catch (e: RestClientResponseException) {
            if (!isUnauthorized(e)) throw e
            token = trackerTokenProvider.refreshKitsu(account) ?: return
            requestKitsu(kitsuId, kitsuUserId, token)
          }

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

  private fun requestMal(
    malId: Int,
    token: String,
  ): String? =
    malClient
      .get()
      .uri("/v2/manga/$malId?fields=my_list_status")
      .header("Authorization", "Bearer $token")
      .retrieve()
      .body(String::class.java)

  private fun requestKitsu(
    kitsuId: Int,
    kitsuUserId: String,
    token: String,
  ): String? =
    kitsuClient
      .get()
      .uri("/library-entries?filter[userId]=$kitsuUserId&filter[mangaId]=$kitsuId&page[limit]=1")
      .header("Authorization", "Bearer $token")
      .header("Accept", "application/vnd.api+json")
      .retrieve()
      .body(String::class.java)

  private fun isUnauthorized(e: RestClientResponseException): Boolean = e.statusCode.value() == 401 || e.statusCode.value() == 403

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
      val metaById =
        bookMetadataRepository
          .findAllByIds(books.map { it.id })
          .associateBy { it.bookId }

      // Mark every chapter up to the remote progress as read, not only the exact match — decimal or gapped
      // numbering would otherwise never resolve and retry hourly without progress.
      val targetBooks =
        books.filter { book ->
          val number = metaById[book.id]?.numberSort
          number != null && number <= remoteProgress.toFloat()
        }

      val alreadyRead =
        readProgressRepository
          .findAllByBookIdsAndUserId(targetBooks.map { it.id }, userId)
          .filter { it.completed }
          .map { it.bookId }
          .toSet()

      val newProgress =
        targetBooks
          .filterNot { it.id in alreadyRead }
          .map { book ->
            ReadProgress(
              bookId = book.id,
              userId = userId,
              page = 1,
              completed = true,
              readDate = LocalDateTime.now(),
              deviceId = "manga-sync-puller",
              deviceName = "Manga Sync Puller ($trackerName)",
            )
          }
      if (newProgress.isNotEmpty()) readProgressRepository.save(newProgress)

      // Always advance syncState (even when no book matches the exact number) to avoid an hourly retry loop.
      val latestBook = targetBooks.maxByOrNull { metaById[it.id]?.numberSort ?: 0f }
      syncStateRepository.update(
        state.copy(
          progress = remoteProgress,
          bookId = latestBook?.id ?: state.bookId,
          lastSyncTimestamp = LocalDateTime.now(),
          lastUpdateTimestamp = LocalDateTime.now(),
          lastModifiedDate = LocalDateTime.now(),
        ),
      )
      log(LogLevel.INFO, "$trackerName pull: komga series ${state.seriesId} → chapter $remoteProgress (${newProgress.size} newly marked)")
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
