package org.gotson.komga.infrastructure.download

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.gotson.komga.domain.model.PluginConfig
import org.gotson.komga.domain.persistence.BlacklistedChapterRepository
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.DownloadExecutor
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

@Component
class MangaDexSubscriptionSyncer(
  private val pluginConfigRepository: PluginConfigRepository,
  private val pluginRepository: PluginRepository,
  private val downloadExecutor: DownloadExecutor,
  private val libraryRepository: LibraryRepository,
  private val seriesRepository: SeriesRepository,
  private val chapterUrlRepository: ChapterUrlRepository,
  private val blacklistedChapterRepository: BlacklistedChapterRepository,
  private val taskScheduler: TaskScheduler,
) {
  private val pluginId = "mangadex-subscription"
  private val objectMapper: ObjectMapper = jacksonObjectMapper()
  private val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .build()

  private val tokenEndpoint = "https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token"
  private val apiBase = "https://api.mangadex.org"
  private val chapterIdRegex = Regex("mangadex\\.org/chapter/([0-9a-f-]+)")
  private val mangaDexDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

  private var accessToken: String? = null
  private var refreshToken: String? = null
  private var expiresAt: Instant? = null

  @Volatile
  private var scheduledTask: ScheduledFuture<*>? = null

  @EventListener(ApplicationReadyEvent::class)
  fun startIfEnabled() {
    val plugin = pluginRepository.findByIdOrNull(pluginId)
    if (plugin == null || !plugin.enabled) {
      logger.debug { "MangaDex Subscription plugin is disabled or not found" }
      return
    }

    val config = loadConfig()
    if (!hasRequiredCredentials(config)) {
      logger.warn { "MangaDex Subscription plugin enabled but credentials not configured" }
      return
    }

    val intervalMinutes = config["sync_interval_minutes"]?.toLongOrNull() ?: 30L
    logger.info { "Starting MangaDex Subscription syncer (interval: ${intervalMinutes}min)" }

    try {
      authenticate(config)
    } catch (e: MangaDexApiException) {
      logger.error(e) { "MangaDex authentication failed, will retry on next feed check" }
    }

    scheduledTask =
      taskScheduler.scheduleAtFixedRate(
        { runFeedCheck() },
        Instant.now().plusSeconds(60),
        Duration.ofMinutes(intervalMinutes),
      )
    logger.info { "MangaDex Subscription feed check scheduled every $intervalMinutes minutes" }
  }

  fun restart() {
    logger.info { "Restarting MangaDex Subscription syncer" }
    stopScheduler()
    accessToken = null
    refreshToken = null
    expiresAt = null
    startIfEnabled()
  }

  fun syncFollowsToMangaDex(libraryId: String): SyncResult {
    val library =
      libraryRepository.findByIdOrNull(libraryId)
        ?: return SyncResult(0, 0, "Library not found")

    val followFile = library.path.resolve("follow.txt").toFile()
    if (!followFile.exists()) {
      return SyncResult(0, 0, "No follow.txt found")
    }

    val mangaDexIds =
      followFile
        .readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .mapNotNull { GalleryDlWrapper.extractMangaDexId(it.trim()) }
        .distinct()

    if (mangaDexIds.isEmpty()) {
      return SyncResult(0, 0, "No MangaDex URLs found in follow.txt")
    }

    val config = loadConfig()
    if (!hasRequiredCredentials(config)) {
      return SyncResult(0, mangaDexIds.size, "MangaDex credentials not configured")
    }

    val token = getValidToken(config)
    var followed = 0

    for (mangaId in mangaDexIds) {
      try {
        val response = apiPost("$apiBase/manga/$mangaId/follow", token, null)
        if (response.statusCode() in 200..204) {
          followed++
          logger.info { "Followed manga on MangaDex: $mangaId" }
        } else {
          logger.warn { "Failed to follow manga $mangaId (HTTP ${response.statusCode()})" }
        }
      } catch (e: Exception) {
        logger.warn { "Error following manga $mangaId: ${e.message}" }
      }
    }

    logger.info { "Synced follow.txt to MangaDex: $followed/${mangaDexIds.size} followed" }
    return SyncResult(followed, mangaDexIds.size, null)
  }

  data class SyncResult(
    val followed: Int,
    val total: Int,
    val error: String?,
  )

  @PreDestroy
  fun stopScheduler() {
    scheduledTask?.cancel(false)
    scheduledTask = null
    logger.info { "MangaDex Subscription syncer stopped" }
  }

  private fun loadConfig(): Map<String, String?> = pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue }

  private fun hasRequiredCredentials(config: Map<String, String?>): Boolean =
    !config["client_id"].isNullOrBlank() &&
      !config["client_secret"].isNullOrBlank() &&
      !config["username"].isNullOrBlank() &&
      !config["password"].isNullOrBlank()

  private fun authenticate(config: Map<String, String?>) {
    val body =
      "grant_type=password" +
        "&client_id=${encode(config["client_id"]!!)}" +
        "&client_secret=${encode(config["client_secret"]!!)}" +
        "&username=${encode(config["username"]!!)}" +
        "&password=${encode(config["password"]!!)}"

    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(tokenEndpoint))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(Duration.ofSeconds(30))
        .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
      throw MangaDexApiException("Authentication failed (HTTP ${response.statusCode()}): ${response.body()}")
    }

    val tokenData: Map<String, Any> = objectMapper.readValue(response.body())
    accessToken = tokenData["access_token"] as? String
    refreshToken = tokenData["refresh_token"] as? String
    val expiresIn = (tokenData["expires_in"] as? Number)?.toLong() ?: 900
    expiresAt = Instant.now().plusSeconds(expiresIn - 60)

    logger.info { "MangaDex authentication successful (expires in ${expiresIn}s)" }
  }

  private fun refreshAccessToken(config: Map<String, String?>) {
    val currentRefreshToken = refreshToken
    if (currentRefreshToken == null) {
      authenticate(config)
      return
    }

    val body =
      "grant_type=refresh_token" +
        "&client_id=${encode(config["client_id"]!!)}" +
        "&client_secret=${encode(config["client_secret"]!!)}" +
        "&refresh_token=${encode(currentRefreshToken)}"

    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(tokenEndpoint))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(Duration.ofSeconds(30))
        .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
      logger.warn { "Token refresh failed, re-authenticating" }
      authenticate(config)
      return
    }

    val tokenData: Map<String, Any> = objectMapper.readValue(response.body())
    accessToken = tokenData["access_token"] as? String
    refreshToken = tokenData["refresh_token"] as? String
    val expiresIn = (tokenData["expires_in"] as? Number)?.toLong() ?: 900
    expiresAt = Instant.now().plusSeconds(expiresIn - 60)

    logger.debug { "MangaDex token refreshed (expires in ${expiresIn}s)" }
  }

  @Synchronized
  private fun getValidToken(config: Map<String, String?>): String {
    val token = accessToken
    val expiry = expiresAt
    if (token != null && expiry != null && Instant.now().isBefore(expiry)) {
      return token
    }
    refreshAccessToken(config)
    return accessToken ?: throw MangaDexApiException("No valid access token after refresh")
  }

  private fun runFeedCheck() {
    try {
      val plugin = pluginRepository.findByIdOrNull(pluginId)
      if (plugin == null || !plugin.enabled) {
        logger.debug { "MangaDex Subscription plugin disabled, skipping feed check" }
        return
      }

      val config = loadConfig()

      if (!hasRequiredCredentials(config)) return

      val targetLibraryName = config["target_library"]?.takeIf { it.isNotBlank() }
      val library =
        if (targetLibraryName != null) {
          libraryRepository.findAll().firstOrNull { it.name.equals(targetLibraryName, ignoreCase = true) }
            ?: run {
              logger.warn { "Target library '$targetLibraryName' not found, falling back to first library" }
              libraryRepository.findAll().firstOrNull()
            }
        } else {
          libraryRepository.findAll().firstOrNull()
        }
      if (library == null) {
        logger.warn { "No library found — MangaDex subscription sync skipped" }
        return
      }
      checkForNewManga(config, library)
      checkFeed(config, library)
    } catch (e: MangaDexApiException) {
      logger.error(e) { "MangaDex feed check failed: ${e.message}" }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun checkForNewManga(
    config: Map<String, String?>,
    library: org.gotson.komga.domain.model.Library?,
  ) {
    val token = getValidToken(config)

    var offset = 0
    val limit = 100
    var queued = 0

    while (true) {
      val url = "$apiBase/user/follows/manga?limit=$limit&offset=$offset"
      val response = apiGet(url, token)
      if (response.statusCode() != 200) {
        logger.warn { "Followed manga request failed (HTTP ${response.statusCode()})" }
        break
      }

      val data: Map<String, Any> = objectMapper.readValue(response.body())
      val mangaList = data["data"] as? List<Map<String, Any>> ?: emptyList()
      val total = (data["total"] as? Number)?.toInt() ?: 0

      for (manga in mangaList) {
        val mangaId = manga["id"] as? String ?: continue

        if (seriesRepository.findByMangaDexUuid(mangaId) != null) continue

        val mangaUrl = "https://mangadex.org/title/$mangaId"
        if (downloadExecutor.isUrlAlreadyQueued(mangaUrl)) continue

        try {
          downloadExecutor.createDownload(
            sourceUrl = mangaUrl,
            libraryId = library?.id,
            title = null,
            createdBy = "mangadex-subscription",
            priority = 5,
          )
          queued++
          logger.info { "New followed manga, queued full download: $mangaId" }
        } catch (e: Exception) {
          logger.warn { "Failed to queue new manga $mangaId: ${e.message}" }
        }
      }

      offset += mangaList.size
      if (offset >= total || mangaList.isEmpty()) break
    }

    if (queued > 0) {
      logger.info { "Followed manga check: queued $queued new manga for full download" }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun checkFeed(
    config: Map<String, String?>,
    library: org.gotson.komga.domain.model.Library?,
  ) {
    val token = getValidToken(config)
    val language =
      pluginConfigRepository
        .findByPluginIdAndKey("gallery-dl-downloader", "default_language")
        ?.configValue ?: "en"

    val lastCheck =
      (config["last_check_time"]?.take(19))
        ?: Instant
          .now()
          .minusSeconds(86400)
          .atOffset(ZoneOffset.UTC)
          .format(mangaDexDateFormat)

    logger.info { "Checking subscription feed since $lastCheck" }

    val seriesCache = mutableMapOf<String, org.gotson.komga.domain.model.Series?>()
    val knownIdsByManga = mutableMapOf<String, Set<String>>()
    val blacklistedIdsByManga = mutableMapOf<String, Set<String>>()

    var offset = 0
    val limit = 100
    val newChaptersByManga = mutableMapOf<String, MutableList<FeedChapter>>()

    while (true) {
      val url =
        "$apiBase/user/follows/manga/feed" +
          "?publishAtSince=${encode(lastCheck)}" +
          "&translatedLanguage[]=$language" +
          "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&contentRating[]=pornographic" +
          "&order[publishAt]=asc" +
          "&limit=$limit&offset=$offset"

      val response = apiGet(url, token)
      if (response.statusCode() != 200) {
        logger.warn { "Subscription feed request failed (HTTP ${response.statusCode()}): ${response.body()}" }
        break
      }

      val feedData: Map<String, Any> = objectMapper.readValue(response.body())
      val chapters = feedData["data"] as? List<Map<String, Any>> ?: emptyList()
      val total = (feedData["total"] as? Number)?.toInt() ?: 0

      logger.debug { "Feed page: ${chapters.size} chapters (offset=$offset, total=$total)" }

      for (chapter in chapters) {
        val chapterId = chapter["id"] as? String ?: continue
        val attributes = chapter["attributes"] as? Map<String, Any> ?: continue
        val relationships = chapter["relationships"] as? List<Map<String, Any>> ?: continue

        val mangaId =
          relationships
            .firstOrNull { it["type"] == "manga" }
            ?.get("id") as? String
            ?: continue

        val chapterUrl = "https://mangadex.org/chapter/$chapterId"

        if (isChapterKnown(mangaId, chapterId, chapterUrl, seriesCache, knownIdsByManga, blacklistedIdsByManga)) continue

        val mangaUrl = "https://mangadex.org/title/$mangaId"
        if (downloadExecutor.isUrlAlreadyQueued(mangaUrl)) continue

        newChaptersByManga
          .getOrPut(mangaId) { mutableListOf() }
          .add(
            FeedChapter(
              chapterId = chapterId,
              mangaId = mangaId,
              chapter = attributes["chapter"] as? String,
              volume = attributes["volume"] as? String,
              title = attributes["title"] as? String,
            ),
          )
      }

      offset += chapters.size
      if (offset >= total || chapters.isEmpty()) break
    }

    if (newChaptersByManga.isEmpty()) {
      logger.info { "Subscription feed: no new chapters" }
    } else {
      var queued = 0

      for ((mangaId, chapters) in newChaptersByManga) {
        val mangaUrl = "https://mangadex.org/title/$mangaId"
        try {
          downloadExecutor.createDownload(
            sourceUrl = mangaUrl,
            libraryId = library?.id,
            title = null,
            createdBy = "mangadex-subscription",
            priority = 5,
          )
          queued++
          logger.info { "Queued $mangaId: ${chapters.size} new chapters (${chapters.map { it.chapter }.joinToString(", ")})" }
        } catch (e: Exception) {
          logger.warn { "Failed to queue $mangaId: ${e.message}" }
        }
      }

      logger.info { "Subscription feed: queued $queued manga with new chapters" }
    }

    saveConfigValue(
      "last_check_time",
      Instant.now().atOffset(ZoneOffset.UTC).format(mangaDexDateFormat),
    )
  }

  private fun isChapterKnown(
    mangaId: String,
    chapterId: String,
    chapterUrl: String,
    seriesCache: MutableMap<String, org.gotson.komga.domain.model.Series?>,
    knownIdsByManga: MutableMap<String, Set<String>>,
    blacklistedIdsByManga: MutableMap<String, Set<String>>,
  ): Boolean {
    if (mangaId !in seriesCache) {
      val series = seriesRepository.findByMangaDexUuid(mangaId)
      seriesCache[mangaId] = series
      if (series != null) {
        knownIdsByManga[mangaId] =
          chapterUrlRepository
            .findUrlsBySeriesId(series.id)
            .mapNotNull { url -> chapterIdRegex.find(url)?.groupValues?.get(1) }
            .toSet()
        blacklistedIdsByManga[mangaId] =
          blacklistedChapterRepository
            .findUrlsBySeriesId(series.id)
            .mapNotNull { url -> chapterIdRegex.find(url)?.groupValues?.get(1) }
            .toSet()
      }
    }
    if (chapterId in (knownIdsByManga[mangaId] ?: emptySet())) return true
    if (chapterId in (blacklistedIdsByManga[mangaId] ?: emptySet())) return true
    if (chapterUrlRepository.existsByUrl(chapterUrl)) return true
    return false
  }

  private data class FeedChapter(
    val chapterId: String,
    val mangaId: String,
    val chapter: String?,
    val volume: String?,
    val title: String?,
  )

  private fun apiGet(
    url: String,
    token: String,
  ): HttpResponse<String> {
    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .header("Authorization", "Bearer $token")
        .GET()
        .timeout(Duration.ofSeconds(30))
        .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun apiPost(
    url: String,
    token: String,
    body: String?,
  ): HttpResponse<String> {
    val builder =
      HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .header("Authorization", "Bearer $token")
        .timeout(Duration.ofSeconds(30))
    if (body != null) {
      builder.header("Content-Type", "application/json")
      builder.POST(HttpRequest.BodyPublishers.ofString(body))
    } else {
      builder.POST(HttpRequest.BodyPublishers.noBody())
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  }

  private fun saveConfigValue(
    key: String,
    value: String,
  ) {
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

  private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

class MangaDexApiException(
  message: String,
) : Exception(message)
