package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.PluginConfig
import org.gotson.komga.domain.model.PluginLog
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginLogRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import org.springframework.http.client.JdkClientHttpRequestFactory

private val logger = KotlinLogging.logger {}

@Component
class MangaScrobblerPlugin(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val pluginLogRepository: PluginLogRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val syncStateRepository: SyncStateRepository,
  private val trackerIdResolver: TrackerIdResolver,
  private val objectMapper: ObjectMapper,
) {
  private val pluginId = "manga-scrobbler"

  private val timeoutClient = JdkClientHttpRequestFactory(
    java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
  ).apply { setReadTimeout(Duration.ofSeconds(30)) }
  private val anilistClient = RestClient.builder().baseUrl("https://graphql.anilist.co").requestFactory(timeoutClient).build()
  private val malClient = RestClient.builder().baseUrl("https://api.myanimelist.net").requestFactory(timeoutClient).build()
  private val kitsuClient = RestClient.builder().baseUrl("https://kitsu.app/api/edge").requestFactory(timeoutClient).build()
  private val mangadexClient = RestClient.builder().baseUrl("https://api.mangadex.org").requestFactory(timeoutClient).build()

  // Fire-and-forget so we don't block the read-progress save path.
  private val executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "scrobbler-worker").apply { isDaemon = true }
  }

  // In-memory dedupe: seriesId -> highest synced chapter number.
  // Cleared on JVM restart; AniList/MAL updates are idempotent so this is purely an optimization.
  private val lastSynced = ConcurrentHashMap<String, Int>()

  // In-memory token cache with expiry (avoids unnecessary refresh calls)
  private val refreshClients by lazy {
    RestClient.builder().requestFactory(timeoutClient).build()
  }
  private var malAccessToken: String? = null
  private var malTokenExpiry: Instant? = null
  private var kitsuAccessToken: String? = null
  private var kitsuTokenExpiry: Instant? = null
  private var mangadexAccessToken: String? = null
  private var mangadexRefreshToken: String? = null
  private var mangadexTokenExpiry: Instant? = null

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
    val trackers = listOf("anilist", "mal", "kitsu", "mangadex")
    for (t in trackers) {
      try {
        for (state in syncStateRepository.findByTracker(t)) {
          lastSynced.merge(state.seriesId, state.progress, ::maxOf)
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

    // Dedupe: only update if this chapter is higher than what we last synced.
    val previous = lastSynced[book.seriesId] ?: 0
    if (chapterNumber <= previous) {
      logger.debug { "Already synced chapter $chapterNumber or higher for '${seriesMeta.title}'" }
      return
    }

    val ids = trackerIdResolver.resolve(seriesMeta.title, seriesMeta.links, config)
    if (ids.anilistId == null && ids.malId == null && ids.kitsuId == null && ids.mangadexId == null) {
      log(LogLevel.INFO, "No tracker mapping found for '${seriesMeta.title}'")
      return
    }

    val tracker = config["tracker"] ?: "both"
    var anySuccess = false

    if (tracker in listOf("anilist", "both", "both_kitsu", "all") && ids.anilistId != null) {
      if (updateAnilist(ids.anilistId, chapterNumber, config["anilist_token"] ?: "", seriesMeta.title)) {
        recordSync(book.seriesId, "anilist", chapterNumber, bookId)
        anySuccess = true
      }
    }

    if (tracker in listOf("mal", "both", "both_kitsu", "all") && ids.malId != null) {
      if (updateMal(ids.malId, chapterNumber, config, seriesMeta.title)) {
        recordSync(book.seriesId, "mal", chapterNumber, bookId)
        anySuccess = true
      }
    }

    if (tracker in listOf("kitsu", "both_kitsu", "all") && ids.kitsuId != null) {
      if (updateKitsu(ids.kitsuId, chapterNumber, config, seriesMeta.title)) {
        recordSync(book.seriesId, "kitsu", chapterNumber, bookId)
        anySuccess = true
      }
    }

    if (tracker in listOf("mangadex", "all") && ids.mangadexId != null) {
      if (updateMangaDex(ids.mangadexId, chapterNumber, config, seriesMeta.title)) {
        recordSync(book.seriesId, "mangadex", chapterNumber, bookId)
        anySuccess = true
      }
    }

    if (anySuccess) {
      lastSynced[book.seriesId] = max(previous, chapterNumber)
    }
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

  private fun updateMal(mediaId: Int, progress: Int, config: Map<String, String?>, title: String): Boolean {
    val token = getValidMalToken(config) ?: return false
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
        val refreshed = refreshMalToken(config)
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

  private fun updateKitsu(mediaId: Int, progress: Int, config: Map<String, String?>, title: String): Boolean {
    val token = getValidKitsuToken(config) ?: return false
    return try {
      kitsuScrobble(mediaId, progress, token, title)
    } catch (e: RestClientException) {
      if (isUnauthorized(e)) {
        log(LogLevel.WARN, "Kitsu token expired, attempting refresh for '$title'")
        val refreshed = refreshKitsuToken(config)
        if (refreshed != null) {
          try {
            kitsuScrobble(mediaId, progress, refreshed, title)
            return true
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

  private fun kitsuScrobble(mediaId: Int, progress: Int, token: String, title: String): Boolean {
    val searchResponse = kitsuClient.get()
      .uri("/library-entries?filter[mangaId]=$mediaId&page[limit]=1")
      .header("Authorization", "Bearer $token")
      .header("Accept", "application/vnd.api+json")
      .retrieve()
      .body(String::class.java)

    val searchJson = searchResponse?.let { objectMapper.readTree(it) }
    val data = searchJson?.get("data")
    val entryId = data?.firstOrNull()?.get("id")?.asText()

    if (entryId != null) {
      val patchBody = mapOf(
        "data" to mapOf(
          "id" to entryId,
          "type" to "libraryEntries",
          "attributes" to mapOf("progress" to progress)
        )
      )
      kitsuClient.patch()
        .uri("/library-entries/$entryId")
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.parseMediaType("application/vnd.api+json"))
        .body(patchBody)
        .retrieve()
        .body(String::class.java)

      log(LogLevel.INFO, "Kitsu: '$title' → chapter $progress")
    } else {
      val postBody = mapOf(
        "data" to mapOf(
          "type" to "libraryEntries",
          "attributes" to mapOf(
            "progress" to progress,
            "status" to "current"
          ),
          "relationships" to mapOf(
            "manga" to mapOf(
              "data" to mapOf(
                "id" to mediaId.toString(),
                "type" to "manga"
              )
            )
          )
        )
      )
      kitsuClient.post()
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

  private fun updateMangaDex(mangaId: String, progress: Int, config: Map<String, String?>, title: String): Boolean {
    val token = getValidMangaDexToken(config) ?: return false
    return try {
      mangadexClient.post()
        .uri("/manga/$mangaId/read")
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("lastChapterRead" to progress.toString()))
        .retrieve()
        .body(String::class.java)

      log(LogLevel.INFO, "MangaDex: '$title' → chapter $progress")
      true
    } catch (e: RestClientException) {
      if (isUnauthorized(e)) {
        log(LogLevel.WARN, "MangaDex token expired, re-authenticating for '$title'")
        mangadexAccessToken = null
        mangadexTokenExpiry = null
        val retryToken = getValidMangaDexToken(config)
        if (retryToken != null) {
          try {
            mangadexClient.post()
              .uri("/manga/$mangaId/read")
              .header("Authorization", "Bearer $retryToken")
              .contentType(MediaType.APPLICATION_JSON)
              .body(mapOf("lastChapterRead" to progress.toString()))
              .retrieve()
              .body(String::class.java)
            log(LogLevel.INFO, "MangaDex: '$title' → chapter $progress (after re-auth)")
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

  private fun getValidMangaDexToken(config: Map<String, String?>): String? {
    if (mangadexAccessToken != null && mangadexTokenExpiry != null && Instant.now().isBefore(mangadexTokenExpiry)) {
      return mangadexAccessToken
    }
    val username = config["mangadex_username"]
    val password = config["mangadex_password"]
    val clientId = config["mangadex_client_id"]
    val clientSecret = config["mangadex_client_secret"]
    if (username.isNullOrBlank() || password.isNullOrBlank() || clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
      log(LogLevel.WARN, "MangaDex credentials not fully configured (need username, password, client_id, client_secret)")
      return null
    }
    return authenticateMangaDex(username, password, clientId, clientSecret)
  }

  private fun authenticateMangaDex(username: String, password: String, clientId: String, clientSecret: String): String? {
    // Try refreshing first if we have a refresh token
    val refreshTkn = mangadexRefreshToken
    if (refreshTkn != null) {
      val refreshed = refreshMangaDexToken(clientId, clientSecret, refreshTkn)
      if (refreshed != null) return refreshed
    }
    // Password grant
    return try {
      val body =
        "grant_type=password" +
          "&username=${encode(username)}" +
          "&password=${encode(password)}" +
          "&client_id=${encode(clientId)}" +
          "&client_secret=${encode(clientSecret)}"

      val response = refreshClients.post()
        .uri("https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .retrieve()
        .body(String::class.java)

      val json = response?.let { objectMapper.readTree(it) }
      val accessTkn = json?.get("access_token")?.asText()
      val newRefresh = json?.get("refresh_token")?.asText()
      val expiresIn = json?.get("expires_in")?.asInt() ?: 0

      if (accessTkn != null) {
        mangadexAccessToken = accessTkn
        mangadexRefreshToken = newRefresh
        mangadexTokenExpiry = Instant.now().plusSeconds(expiresIn.toLong() - 60)
        log(LogLevel.INFO, "MangaDex authentication successful (expires in ${expiresIn}s)")
        accessTkn
      } else {
        log(LogLevel.ERROR, "MangaDex auth response missing access_token")
        null
      }
    } catch (e: Exception) {
      log(LogLevel.ERROR, "MangaDex authentication failed: ${e.message}", e)
      null
    }
  }

  private fun refreshMangaDexToken(clientId: String, clientSecret: String, refreshTkn: String): String? {
    return try {
      val body =
        "grant_type=refresh_token" +
          "&refresh_token=${encode(refreshTkn)}" +
          "&client_id=${encode(clientId)}" +
          "&client_secret=${encode(clientSecret)}"

      val response = refreshClients.post()
        .uri("https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .retrieve()
        .body(String::class.java)

      val json = response?.let { objectMapper.readTree(it) }
      val newAccess = json?.get("access_token")?.asText()
      val newRefresh = json?.get("refresh_token")?.asText()
      val expiresIn = json?.get("expires_in")?.asInt() ?: 0

      if (newAccess != null) {
        mangadexAccessToken = newAccess
        mangadexRefreshToken = newRefresh
        mangadexTokenExpiry = Instant.now().plusSeconds(expiresIn.toLong() - 60)
        log(LogLevel.INFO, "MangaDex token refreshed (expires in ${expiresIn}s)")
        newAccess
      } else {
        log(LogLevel.WARN, "MangaDex token refresh failed, will fall back to password grant")
        null
      }
    } catch (e: Exception) {
      log(LogLevel.WARN, "MangaDex token refresh failed, will fall back to password grant: ${e.message}")
      null
    }
  }

  // --- Sync state recording ---

  private fun recordSync(seriesId: String, tracker: String, progress: Int, bookId: String) {
    try {
      val existing = syncStateRepository.findBySeriesIdAndTracker(seriesId, tracker)
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
        syncStateRepository.insert(
          SyncState(
            id = TsidCreator.getTsid256().toString(),
            bookId = bookId,
            seriesId = seriesId,
            tracker = tracker,
            progress = progress,
            lastSyncTimestamp = now,
          ),
        )
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to record sync state for series $seriesId tracker $tracker" }
    }
  }

  // --- Token management ---

  private fun getValidMalToken(config: Map<String, String?>): String? {
    if (malAccessToken != null && malTokenExpiry != null && Instant.now().isBefore(malTokenExpiry)) {
      return malAccessToken
    }
    val stored = config["mal_access_token"]
    if (stored.isNullOrBlank()) return null

    val refreshTkn = config["mal_refresh_token"]
    val clientId = config["mal_client_id"]
    val clientSecret = config["mal_client_secret"]
    if (!refreshTkn.isNullOrBlank() && !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()) {
      val refreshed = refreshMalToken(config)
      if (refreshed != null) return refreshed
    }
    // No refresh configured or refresh failed — use stored token (may 401 later)
    malAccessToken = stored
    return stored
  }

  private fun getValidKitsuToken(config: Map<String, String?>): String? {
    if (kitsuAccessToken != null && kitsuTokenExpiry != null && Instant.now().isBefore(kitsuTokenExpiry)) {
      return kitsuAccessToken
    }
    val stored = config["kitsu_token"]
    if (stored.isNullOrBlank()) return null

    val refreshTkn = config["kitsu_refresh_token"]
    val clientId = config["kitsu_client_id"]
    val clientSecret = config["kitsu_client_secret"]
    if (!refreshTkn.isNullOrBlank() && !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()) {
      val refreshed = refreshKitsuToken(config)
      if (refreshed != null) return refreshed
    }
    kitsuAccessToken = stored
    return stored
  }

  private fun refreshMalToken(config: Map<String, String?>): String? {
    val refreshTkn = config["mal_refresh_token"]?.takeIf { it.isNotBlank() } ?: return null
    val clientId = config["mal_client_id"]?.takeIf { it.isNotBlank() } ?: return null
    val clientSecret = config["mal_client_secret"]?.takeIf { it.isNotBlank() } ?: return null
    return try {
      val body =
        "grant_type=refresh_token" +
          "&refresh_token=${encode(refreshTkn)}" +
          "&client_id=${encode(clientId)}" +
          "&client_secret=${encode(clientSecret)}"

      val response = refreshClients.post()
        .uri("https://myanimelist.net/v1/oauth2/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .retrieve()
        .body(String::class.java)

      val json = response?.let { objectMapper.readTree(it) }
      val newAccess = json?.get("access_token")?.asText()
      val newRefresh = json?.get("refresh_token")?.asText()
      val expiresIn = json?.get("expires_in")?.asInt() ?: 0

      if (newAccess != null) {
        malAccessToken = newAccess
        malTokenExpiry = Instant.now().plusSeconds(expiresIn.toLong() - 60)
        saveConfigValue("mal_access_token", newAccess)
        if (newRefresh != null) saveConfigValue("mal_refresh_token", newRefresh)
        log(LogLevel.INFO, "MAL token refreshed (expires in ${expiresIn}s)")
        newAccess
      } else {
        log(LogLevel.ERROR, "MAL token refresh response missing access_token")
        null
      }
    } catch (e: Exception) {
      log(LogLevel.ERROR, "MAL token refresh failed: ${e.message}", e)
      null
    }
  }

  private fun refreshKitsuToken(config: Map<String, String?>): String? {
    val refreshTkn = config["kitsu_refresh_token"]?.takeIf { it.isNotBlank() } ?: return null
    val clientId = config["kitsu_client_id"]?.takeIf { it.isNotBlank() } ?: return null
    val clientSecret = config["kitsu_client_secret"]?.takeIf { it.isNotBlank() } ?: return null
    return try {
      val body =
        "grant_type=refresh_token" +
          "&refresh_token=${encode(refreshTkn)}" +
          "&client_id=${encode(clientId)}" +
          "&client_secret=${encode(clientSecret)}"

      val response = refreshClients.post()
        .uri("https://kitsu.app/api/oauth/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .retrieve()
        .body(String::class.java)

      val json = response?.let { objectMapper.readTree(it) }
      val newAccess = json?.get("access_token")?.asText()
      val newRefresh = json?.get("refresh_token")?.asText()
      val expiresIn = json?.get("expires_in")?.asInt() ?: 0

      if (newAccess != null) {
        kitsuAccessToken = newAccess
        kitsuTokenExpiry = Instant.now().plusSeconds(expiresIn.toLong() - 60)
        saveConfigValue("kitsu_token", newAccess)
        if (newRefresh != null) saveConfigValue("kitsu_refresh_token", newRefresh)
        log(LogLevel.INFO, "Kitsu token refreshed (expires in ${expiresIn}s)")
        newAccess
      } else {
        log(LogLevel.ERROR, "Kitsu token refresh response missing access_token")
        null
      }
    } catch (e: Exception) {
      log(LogLevel.ERROR, "Kitsu token refresh failed: ${e.message}", e)
      null
    }
  }

  private fun isUnauthorized(e: RestClientException): Boolean {
    val message = e.message ?: ""
    return message.contains("401") || message.contains("403")
  }

  private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

  private fun saveConfigValue(key: String, value: String) {
    val existing = pluginConfigRepository.findByPluginIdAndKey(pluginId, key)
    if (existing != null) {
      pluginConfigRepository.update(existing.copy(configValue = value))
    } else {
      pluginConfigRepository.insert(
        PluginConfig(
          id = UUID.randomUUID().toString(),
          pluginId = pluginId,
          configKey = key,
          configValue = value,
        ),
      )
    }
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
