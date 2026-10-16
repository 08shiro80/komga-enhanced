package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.URI
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

@Component
class MangaScrobblerPlugin(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val syncStateRepository: SyncStateRepository,
  private val userTrackerRepository: UserTrackerRepository,
  private val trackerIdResolver: TrackerIdResolver,
  private val trackerTokenProvider: TrackerTokenProvider,
  private val objectMapper: ObjectMapper,
) {
  private val pluginId = "manga-scrobbler"

  private val timeoutClient =
    JdkClientHttpRequestFactory(
      java.net.http.HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
    ).apply { setReadTimeout(Duration.ofSeconds(30)) }
  private val anilistClient =
    RestClient
      .builder()
      .baseUrl("https://graphql.anilist.co")
      .requestFactory(timeoutClient)
      .build()
  private val malClient =
    RestClient
      .builder()
      .baseUrl("https://api.myanimelist.net")
      .requestFactory(timeoutClient)
      .build()
  private val kitsuClient =
    RestClient
      .builder()
      .baseUrl("https://kitsu.app/api/edge")
      .requestFactory(timeoutClient)
      .build()
  private val mangadexClient =
    RestClient
      .builder()
      .baseUrl("https://api.mangadex.org")
      .requestFactory(timeoutClient)
      .build()

  // Fire-and-forget so we don't block the read-progress save path.
  private val executor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "scrobbler-worker").apply { isDaemon = true }
    }

  // In-memory dedupe: "userId:seriesId:tracker" -> highest chapter number synced to that tracker.
  // Cleared on JVM restart; AniList/MAL updates are idempotent so this is purely an optimization.
  private val lastSynced = ConcurrentHashMap<String, Int>()

  @EventListener(ApplicationReadyEvent::class)
  fun init() {
    val plugin = pluginRepository.findByIdOrNull(pluginId)
    if (plugin == null) {
      logger.debug { "Scrobbler plugin not yet installed" }
      return
    }
    logger.info { "Scrobbler plugin loaded (enabled=${plugin.enabled})" }
    if (plugin.enabled) {
      hydrateLastSyncedFromSyncState()
    }
  }

  /** After restart, avoid redundant API calls: max chapter already recorded per Komga series. */
  private fun hydrateLastSyncedFromSyncState() {
    for (t in UserTracker.mangaTrackers) {
      try {
        for (state in syncStateRepository.findByTracker(t)) {
          lastSynced.merge(dedupeKey(state.userId, state.seriesId, t), state.progress, ::maxOf)
        }
      } catch (e: Exception) {
        logger.warn(e) { "Manga scrobbler: failed to hydrate cache from sync_state (tracker=$t)" }
      }
    }
    if (lastSynced.isNotEmpty()) {
      logger.info { "Manga scrobbler: restored chapter cache for ${lastSynced.size} series from sync_state" }
    }
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

  private fun handle(
    bookId: String,
    userId: String,
  ) {
    val config = loadConfig()

    val accounts =
      userTrackerRepository
        .findByUserId(userId)
        .filter { it.tracker in UserTracker.mangaTrackers && it.active }
        .associateBy { it.tracker }
    if (accounts.isEmpty()) {
      logger.debug { "Skipping scrobble for user $userId: no linked manga tracker" }
      return
    }

    val book = bookRepository.findByIdOrNull(bookId)
    if (book == null) {
      log(LogLevel.WARN, "Book $bookId not found")
      return
    }

    val excludedLibs = parseExcludedLibraryIds(config)
    if (book.libraryId in excludedLibs) {
      logger.debug { "Skipping manga scrobble: library ${book.libraryId} is in exclude_library_ids" }
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

    // Dedupe per tracker: only update a tracker if this chapter is higher than what it last got.
    val pendingTrackers =
      accounts.keys.filter { tracker ->
        chapterNumber > (lastSynced[dedupeKey(userId, book.seriesId, tracker)] ?: 0)
      }
    if (pendingTrackers.isEmpty()) {
      logger.debug { "Already synced chapter $chapterNumber or higher for '${seriesMeta.title}' (user $userId)" }
      return
    }

    val ids = trackerIdResolver.resolve(seriesMeta.title, seriesMeta.links, config)
    if (ids.anilistId == null && ids.malId == null && ids.kitsuId == null && ids.mangadexId == null) {
      log(LogLevel.INFO, "No tracker mapping found for '${seriesMeta.title}'")
      return
    }

    val anilist = accounts[UserTracker.ANILIST]
    if (anilist != null && ids.anilistId != null && UserTracker.ANILIST in pendingTrackers) {
      if (updateAnilist(ids.anilistId, chapterNumber, anilist[UserTracker.KEY_TOKEN] ?: "", seriesMeta.title)) {
        recordSync(userId, book.seriesId, UserTracker.ANILIST, chapterNumber, bookId)
        lastSynced[dedupeKey(userId, book.seriesId, UserTracker.ANILIST)] = chapterNumber
      }
    }

    val mal = accounts[UserTracker.MAL]
    if (mal != null && ids.malId != null && UserTracker.MAL in pendingTrackers) {
      if (updateMal(ids.malId, chapterNumber, mal, seriesMeta.title)) {
        recordSync(userId, book.seriesId, UserTracker.MAL, chapterNumber, bookId)
        lastSynced[dedupeKey(userId, book.seriesId, UserTracker.MAL)] = chapterNumber
      }
    }

    val kitsu = accounts[UserTracker.KITSU]
    if (kitsu != null && ids.kitsuId != null && UserTracker.KITSU in pendingTrackers) {
      if (updateKitsu(ids.kitsuId, chapterNumber, kitsu, seriesMeta.title)) {
        recordSync(userId, book.seriesId, UserTracker.KITSU, chapterNumber, bookId)
        lastSynced[dedupeKey(userId, book.seriesId, UserTracker.KITSU)] = chapterNumber
      }
    }

    val mangadex = accounts[UserTracker.MANGADEX]
    if (mangadex != null && ids.mangadexId != null && UserTracker.MANGADEX in pendingTrackers) {
      if (updateMangaDex(ids.mangadexId, chapterNumber, mangadex, seriesMeta.title)) {
        recordSync(userId, book.seriesId, UserTracker.MANGADEX, chapterNumber, bookId)
        lastSynced[dedupeKey(userId, book.seriesId, UserTracker.MANGADEX)] = chapterNumber
      }
    }
  }

  private fun dedupeKey(
    userId: String,
    seriesId: String,
    tracker: String,
  ) = "$userId:$seriesId:$tracker"

  private fun updateAnilist(
    mediaId: Int,
    progress: Int,
    token: String,
    title: String,
  ): Boolean {
    val mutation =
      """
      mutation (${"$"}id: Int, ${"$"}progress: Int) {
        SaveMediaListEntry(mediaId: ${"$"}id, progress: ${"$"}progress) {
          id
          progress
          status
        }
      }
      """.trimIndent()

    val body =
      mapOf(
        "query" to mutation,
        "variables" to mapOf("id" to mediaId, "progress" to progress),
      )

    return try {
      val response =
        anilistClient
          .post()
          .header("Authorization", "Bearer $token")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(String::class.java)

      val json: JsonNode? = response?.let { objectMapper.readTree(it) }
      val errors = json?.get("errors")
      if (errors != null && errors.isArray && errors.size() > 0) {
        log(LogLevel.ERROR, "AniList error for '$title' (id=$mediaId): $errors")
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

  private fun updateMal(
    mediaId: Int,
    progress: Int,
    account: UserTracker,
    title: String,
  ): Boolean {
    val token = trackerTokenProvider.malToken(account) ?: return false
    return try {
      malClient
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
      if (isUnauthorized(e)) {
        log(LogLevel.WARN, "MAL token expired, attempting refresh for '$title'")
        val refreshed = trackerTokenProvider.refreshMal(account)
        if (refreshed != null) {
          try {
            malClient
              .patch()
              .uri("/v2/manga/$mediaId/my_list_status")
              .header("Authorization", "Bearer $refreshed")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body("num_chapters_read=$progress")
              .retrieve()
              .body(String::class.java)
            log(LogLevel.INFO, "MAL: '$title' → chapter $progress (after refresh)")
            return true
          } catch (e2: RestClientException) {
            log(LogLevel.ERROR, "MAL request failed after token refresh for '$title': ${e2.message}", e2)
          }
        } else {
          log(LogLevel.ERROR, "MAL token refresh failed for '$title' — user must re-authorize")
        }
      } else {
        log(LogLevel.ERROR, "MAL request failed for '$title' (id=$mediaId): ${e.message}", e)
      }
      false
    }
  }

  private fun updateKitsu(
    mediaId: Int,
    progress: Int,
    account: UserTracker,
    title: String,
  ): Boolean {
    val token = trackerTokenProvider.kitsuToken(account) ?: return false
    return try {
      kitsuScrobble(account, mediaId, progress, token, title)
    } catch (e: RestClientException) {
      if (isUnauthorized(e)) {
        log(LogLevel.WARN, "Kitsu token expired, attempting refresh for '$title'")
        val refreshed = trackerTokenProvider.refreshKitsu(account)
        if (refreshed != null) {
          try {
            return kitsuScrobble(account, mediaId, progress, refreshed, title)
          } catch (e2: RestClientException) {
            log(LogLevel.ERROR, "Kitsu request failed after token refresh for '$title': ${e2.message}", e2)
          }
        } else {
          log(LogLevel.ERROR, "Kitsu token refresh failed for '$title' — user must re-authorize")
        }
      } else {
        log(LogLevel.ERROR, "Kitsu request failed for '$title' (id=$mediaId): ${e.message}", e)
      }
      false
    }
  }

  private fun kitsuScrobble(
    account: UserTracker,
    mediaId: Int,
    progress: Int,
    token: String,
    title: String,
  ): Boolean {
    val kitsuUserId = trackerTokenProvider.kitsuUserId(account, token)
    if (kitsuUserId == null) {
      log(LogLevel.ERROR, "Kitsu: could not resolve own user id for '$title' — skipping scrobble")
      return false
    }
    val searchResponse =
      kitsuClient
        .get()
        .uri("/library-entries?filter[userId]=$kitsuUserId&filter[mangaId]=$mediaId&page[limit]=1")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.api+json")
        .retrieve()
        .body(String::class.java)

    val searchJson = searchResponse?.let { objectMapper.readTree(it) }
    val data = searchJson?.get("data")
    val entryId = data?.firstOrNull()?.get("id")?.asText()

    if (entryId != null) {
      val patchBody =
        mapOf(
          "data" to
            mapOf(
              "id" to entryId,
              "type" to "libraryEntries",
              "attributes" to mapOf("progress" to progress),
            ),
        )
      kitsuClient
        .patch()
        .uri("/library-entries/$entryId")
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.parseMediaType("application/vnd.api+json"))
        .body(patchBody)
        .retrieve()
        .body(String::class.java)

      log(LogLevel.INFO, "Kitsu: '$title' → chapter $progress")
    } else {
      val postBody =
        mapOf(
          "data" to
            mapOf(
              "type" to "libraryEntries",
              "attributes" to
                mapOf(
                  "progress" to progress,
                  "status" to "current",
                ),
              "relationships" to
                mapOf(
                  "manga" to
                    mapOf(
                      "data" to
                        mapOf(
                          "id" to mediaId.toString(),
                          "type" to "manga",
                        ),
                    ),
                ),
            ),
        )
      kitsuClient
        .post()
        .uri("/library-entries")
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.parseMediaType("application/vnd.api+json"))
        .body(postBody)
        .retrieve()
        .body(String::class.java)

      log(LogLevel.INFO, "Kitsu: '$title' → chapter $progress (created)")
    }
    return true
  }

  // --- MangaDex ---

  private fun updateMangaDex(
    mangaId: String,
    progress: Int,
    account: UserTracker,
    title: String,
  ): Boolean {
    val token = trackerTokenProvider.mangadexToken(account)
    if (token == null) {
      log(LogLevel.WARN, "MangaDex auth failed for '$title'")
      return false
    }

    // MangaDex has no "read up to chapter N" concept: POST /manga/{id}/read takes chapterIdsRead (UUID
    // arrays). Resolve the chapter UUIDs up to the read chapter number from the manga feed, then mark them.
    // The feed is filtered by the configured max content rating, so higher-rated manga return no chapters.
    val chapterIds =
      try {
        fetchMangaDexReadChapterIds(mangaId, progress, allowedContentRatings(account))
      } catch (e: Exception) {
        log(LogLevel.ERROR, "MangaDex feed request failed for '$title' (id=$mangaId): ${e.message}", e)
        return false
      }
    if (chapterIds.isEmpty()) {
      log(LogLevel.WARN, "MangaDex: no EN chapters up to $progress within content rating for '$title' (id=$mangaId) — nothing marked read")
      return false
    }

    return try {
      postMangaDexRead(mangaId, chapterIds, token)
      log(LogLevel.INFO, "MangaDex: '$title' → ${chapterIds.size} chapters up to $progress marked read")
      true
    } catch (e: RestClientException) {
      if (isUnauthorized(e)) {
        log(LogLevel.WARN, "MangaDex token expired, re-authenticating for '$title'")
        trackerTokenProvider.invalidate(account)
        val retryToken = trackerTokenProvider.mangadexToken(account)
        if (retryToken != null) {
          try {
            postMangaDexRead(mangaId, chapterIds, retryToken)
            log(LogLevel.INFO, "MangaDex: '$title' → ${chapterIds.size} chapters up to $progress marked read (after re-auth)")
            return true
          } catch (e2: RestClientException) {
            log(LogLevel.ERROR, "MangaDex request failed after re-auth for '$title': ${e2.message}", e2)
          }
        }
      } else {
        log(LogLevel.ERROR, "MangaDex request failed for '$title' (id=$mangaId): ${e.message}", e)
      }
      false
    }
  }

  private fun postMangaDexRead(
    mangaId: String,
    chapterIds: List<String>,
    token: String,
  ) {
    mangadexClient
      .post()
      .uri("/manga/$mangaId/read")
      .header("Authorization", "Bearer $token")
      .contentType(MediaType.APPLICATION_JSON)
      .body(mapOf("chapterIdsRead" to chapterIds))
      .retrieve()
      .body(String::class.java)
  }

  private fun allowedContentRatings(account: UserTracker): List<String> {
    val order = listOf("safe", "suggestive", "erotica", "pornographic")
    val max = account[UserTracker.KEY_MAX_CONTENT_RATING] ?: "pornographic"
    val maxIndex = order.indexOf(max).takeIf { it >= 0 } ?: order.lastIndex
    return order.take(maxIndex + 1)
  }

  private fun fetchMangaDexReadChapterIds(
    mangaId: String,
    upToChapter: Int,
    contentRatings: List<String>,
  ): List<String> {
    val ids = mutableListOf<String>()
    val limit = 500
    var offset = 0
    val contentRatingQuery = contentRatings.joinToString("") { "&contentRating[]=$it" }
    while (true) {
      val url =
        "https://api.mangadex.org/manga/$mangaId/feed" +
          "?translatedLanguage[]=en" +
          contentRatingQuery +
          "&order[chapter]=asc" +
          "&limit=$limit&offset=$offset"
      val body =
        mangadexClient
          .get()
          .uri(URI.create(url))
          .retrieve()
          .body(String::class.java)
          ?: break
      val root = objectMapper.readTree(body)
      val data = root.path("data")
      data.forEach { chapter ->
        val chapterNum =
          chapter
            .path("attributes")
            .path("chapter")
            .asText("")
            .toDoubleOrNull()
        if (chapterNum != null && chapterNum <= upToChapter) {
          ids.add(chapter.path("id").asText())
        }
      }
      offset += limit
      if (data.isEmpty || offset >= root.path("total").asInt(0)) break
    }
    return ids
  }

  // --- Sync state recording ---

  private fun recordSync(
    userId: String,
    seriesId: String,
    tracker: String,
    progress: Int,
    bookId: String,
  ) {
    try {
      val existing = syncStateRepository.findByUserIdAndSeriesIdAndTracker(userId, seriesId, tracker)
      val now = LocalDateTime.now()
      if (existing != null) {
        syncStateRepository.update(
          existing.copy(
            bookId = bookId,
            progress = progress,
            lastSyncTimestamp = now,
            lastModifiedDate = now,
          ),
        )
      } else {
        try {
          syncStateRepository.insert(
            SyncState(
              id = TsidCreator.getTsid256().toString(),
              userId = userId,
              bookId = bookId,
              seriesId = seriesId,
              tracker = tracker,
              progress = progress,
              lastSyncTimestamp = now,
            ),
          )
        } catch (e: Exception) {
          // A concurrent insert won the unique(user_id, series_id, tracker) race — update the existing row
          // instead of losing this sync. Preserves pull-side fields (status/score/lastUpdate) via copy.
          val raced =
            syncStateRepository.findByUserIdAndSeriesIdAndTracker(userId, seriesId, tracker)
              ?: throw e
          syncStateRepository.update(
            raced.copy(
              bookId = bookId,
              progress = progress,
              lastSyncTimestamp = now,
              lastModifiedDate = now,
            ),
          )
        }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to record sync state for user $userId series $seriesId tracker $tracker" }
    }
  }

  private fun isUnauthorized(e: RestClientException): Boolean {
    if (e is RestClientResponseException) {
      return e.statusCode.value() == 401 || e.statusCode.value() == 403
    }
    return false
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
