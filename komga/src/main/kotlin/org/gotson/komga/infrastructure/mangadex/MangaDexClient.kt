package org.gotson.komga.infrastructure.mangadex

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Client for interacting with the MangaDex API.
 *
 * Provides methods for:
 * - Fetching manga metadata
 * - Getting chapter feeds
 * - Searching for manga
 * - Rate limiting to respect API limits
 *
 * MangaDex API documentation: https://api.mangadex.org/docs/
 */
@Service
class MangaDexClient(
  private val objectMapper: ObjectMapper,
) {
  companion object {
    const val BASE_URL = "https://api.mangadex.org"
    const val COVERS_URL = "https://uploads.mangadex.org/covers"

    // Rate limiting: 5 requests per second
    private const val RATE_LIMIT_REQUESTS = 5
    private const val RATE_LIMIT_WINDOW_MS = 1000L
  }

  private val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .build()

  // Rate limiting
  private val requestTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

  /**
   * Extract MangaDex manga ID from URL.
   * Supports: https://mangadex.org/title/UUID or https://mangadex.org/title/UUID/title-slug
   */
  fun extractMangaId(url: String): String? {
    val regex = """mangadex\.org/title/([a-f0-9-]{36})""".toRegex()
    return regex.find(url)?.groupValues?.get(1)
  }

  /**
   * Extract MangaDex chapter ID from URL.
   * Supports: https://mangadex.org/chapter/UUID
   */
  fun extractChapterId(url: String): String? {
    val regex = """mangadex\.org/chapter/([a-f0-9-]{36})""".toRegex()
    return regex.find(url)?.groupValues?.get(1)
  }

  /**
   * Fetch manga metadata by ID.
   */
  fun getManga(mangaId: String): MangaDexManga? {
    rateLimit()

    val url = "$BASE_URL/manga/$mangaId?includes[]=author&includes[]=artist&includes[]=cover_art"
    logger.debug { "Fetching manga: $url" }

    return try {
      val response = executeGet(url)
      if (response.statusCode() != 200) {
        logger.warn { "MangaDex API returned ${response.statusCode()} for manga $mangaId" }
        return null
      }

      val jsonResponse = objectMapper.readValue<Map<String, Any?>>(response.body())
      parseMangaResponse(jsonResponse)
    } catch (e: Exception) {
      logger.error(e) { "Failed to fetch manga $mangaId" }
      null
    }
  }

  /**
   * Fetch manga metadata by URL.
   */
  fun getMangaByUrl(mangaUrl: String): MangaDexManga? {
    val mangaId = extractMangaId(mangaUrl) ?: return null
    return getManga(mangaId)
  }

  /**
   * Get chapter feed for a manga.
   *
   * @param mangaId The manga UUID
   * @param lang Language filter (default: "en")
   * @param limit Maximum chapters to return (default: 500)
   * @param offset Offset for pagination
   * @return List of chapters
   */
  fun getChapterFeed(
    mangaId: String,
    lang: String = "en",
    limit: Int = 500,
    offset: Int = 0,
  ): List<MangaDexChapter> {
    rateLimit()

    val url =
      "$BASE_URL/manga/$mangaId/feed?" +
        "translatedLanguage[]=$lang&" +
        "limit=$limit&" +
        "offset=$offset&" +
        "order[chapter]=asc&" +
        "includes[]=scanlation_group"

    logger.debug { "Fetching chapter feed: $url" }

    return try {
      val response = executeGet(url)
      if (response.statusCode() != 200) {
        logger.warn { "MangaDex API returned ${response.statusCode()} for chapter feed of $mangaId" }
        return emptyList()
      }

      val jsonResponse = objectMapper.readValue<Map<String, Any?>>(response.body())
      parseChapterFeedResponse(jsonResponse)
    } catch (e: Exception) {
      logger.error(e) { "Failed to fetch chapter feed for $mangaId" }
      emptyList()
    }
  }

  /**
   * Get all chapters for a manga (handles pagination).
   */
  fun getAllChapters(
    mangaId: String,
    lang: String = "en",
  ): List<MangaDexChapter> {
    val allChapters = mutableListOf<MangaDexChapter>()
    var offset = 0
    val limit = 500

    do {
      val chapters = getChapterFeed(mangaId, lang, limit, offset)
      allChapters.addAll(chapters)
      offset += limit
    } while (chapters.size == limit)

    return allChapters
  }

  /**
   * Search for manga by title.
   */
  fun searchManga(
    query: String,
    limit: Int = 10,
  ): List<MangaDexManga> {
    rateLimit()

    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
    val url = "$BASE_URL/manga?title=$encodedQuery&limit=$limit&includes[]=cover_art"

    logger.debug { "Searching manga: $url" }

    return try {
      val response = executeGet(url)
      if (response.statusCode() != 200) {
        logger.warn { "MangaDex search returned ${response.statusCode()}" }
        return emptyList()
      }

      val jsonResponse = objectMapper.readValue<Map<String, Any?>>(response.body())

      @Suppress("UNCHECKED_CAST")
      val data = jsonResponse["data"] as? List<Map<String, Any?>> ?: return emptyList()

      data.mapNotNull { parseMangaData(it) }
    } catch (e: Exception) {
      logger.error(e) { "Failed to search manga: $query" }
      emptyList()
    }
  }

  /**
   * Get chapter metadata by ID.
   */
  fun getChapter(chapterId: String): MangaDexChapter? {
    rateLimit()

    val url = "$BASE_URL/chapter/$chapterId?includes[]=scanlation_group"
    logger.debug { "Fetching chapter: $url" }

    return try {
      val response = executeGet(url)
      if (response.statusCode() != 200) {
        logger.warn { "MangaDex API returned ${response.statusCode()} for chapter $chapterId" }
        return null
      }

      val jsonResponse = objectMapper.readValue<Map<String, Any?>>(response.body())

      @Suppress("UNCHECKED_CAST")
      val data = jsonResponse["data"] as? Map<String, Any?> ?: return null
      parseChapterData(data)
    } catch (e: Exception) {
      logger.error(e) { "Failed to fetch chapter $chapterId" }
      null
    }
  }

  /**
   * Build cover image URL.
   */
  fun getCoverUrl(
    mangaId: String,
    coverFilename: String,
    quality: CoverQuality = CoverQuality.ORIGINAL,
  ): String {
    val suffix =
      when (quality) {
        CoverQuality.ORIGINAL -> ""
        CoverQuality.MEDIUM -> ".512.jpg"
        CoverQuality.THUMBNAIL -> ".256.jpg"
      }
    return "$COVERS_URL/$mangaId/$coverFilename$suffix"
  }

  /**
   * Download cover image bytes.
   */
  fun downloadCover(
    mangaId: String,
    coverFilename: String,
    quality: CoverQuality = CoverQuality.ORIGINAL,
  ): ByteArray? {
    rateLimit()

    val url = getCoverUrl(mangaId, coverFilename, quality)
    logger.debug { "Downloading cover: $url" }

    return try {
      val request =
        HttpRequest
          .newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(30))
          .GET()
          .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

      if (response.statusCode() != 200) {
        logger.warn { "Cover download failed with status ${response.statusCode()}" }
        return null
      }

      response.body()
    } catch (e: Exception) {
      logger.error(e) { "Failed to download cover for $mangaId" }
      null
    }
  }

  // --- Private helper methods ---

  private fun executeGet(url: String): HttpResponse<String> {
    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", "Komga/1.0 (https://komga.org)")
        .GET()
        .build()

    return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun rateLimit() {
    val key = "global"
    val now = System.currentTimeMillis()
    val timestamps = requestTimestamps.computeIfAbsent(key) { mutableListOf() }

    synchronized(timestamps) {
      // Remove old timestamps
      timestamps.removeIf { it < now - RATE_LIMIT_WINDOW_MS }

      // If at limit, wait
      if (timestamps.size >= RATE_LIMIT_REQUESTS) {
        val oldestTimestamp = timestamps.minOrNull() ?: now
        val waitTime = RATE_LIMIT_WINDOW_MS - (now - oldestTimestamp)
        if (waitTime > 0) {
          logger.debug { "Rate limiting: waiting ${waitTime}ms" }
          Thread.sleep(waitTime)
        }
        timestamps.removeIf { it < System.currentTimeMillis() - RATE_LIMIT_WINDOW_MS }
      }

      timestamps.add(System.currentTimeMillis())
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun parseMangaResponse(response: Map<String, Any?>): MangaDexManga? {
    val data = response["data"] as? Map<String, Any?> ?: return null
    return parseMangaData(data)
  }

  @Suppress("UNCHECKED_CAST")
  private fun parseMangaData(data: Map<String, Any?>): MangaDexManga? {
    val id = data["id"] as? String ?: return null
    val attributes = data["attributes"] as? Map<String, Any?> ?: return null
    val relationships = data["relationships"] as? List<Map<String, Any?>> ?: emptyList()

    // Parse title (prefer English from altTitles, then title.en, then first available)
    val titleMap = attributes["title"] as? Map<String, String> ?: emptyMap()
    val altTitles = attributes["altTitles"] as? List<Map<String, String>> ?: emptyList()

    // Priority: altTitles[en] -> title.en -> title.ja-ro -> first title
    val englishTitle =
      altTitles.firstNotNullOfOrNull { it["en"] }
        ?: titleMap["en"]
        ?: titleMap["ja-ro"]
        ?: titleMap.values.firstOrNull()
        ?: "Unknown"

    // Parse description
    val descriptionMap = attributes["description"] as? Map<String, String> ?: emptyMap()
    val description = descriptionMap["en"] ?: descriptionMap.values.firstOrNull()

    // Parse tags
    val tags = attributes["tags"] as? List<Map<String, Any?>> ?: emptyList()
    val genres =
      tags.mapNotNull { tag ->
        val tagAttrs = tag["attributes"] as? Map<String, Any?>
        val tagNames = tagAttrs?.get("name") as? Map<String, String>
        tagNames?.get("en")
      }

    // Parse authors/artists from relationships
    var author: String? = null
    var artist: String? = null
    var coverFilename: String? = null

    relationships.forEach { rel ->
      when (rel["type"]) {
        "author" -> {
          val relAttrs = rel["attributes"] as? Map<String, Any?>
          author = author ?: relAttrs?.get("name") as? String
        }
        "artist" -> {
          val relAttrs = rel["attributes"] as? Map<String, Any?>
          artist = artist ?: relAttrs?.get("name") as? String
        }
        "cover_art" -> {
          val relAttrs = rel["attributes"] as? Map<String, Any?>
          coverFilename = coverFilename ?: relAttrs?.get("fileName") as? String
        }
      }
    }

    // Parse alternative titles with languages
    val alternativeTitles = mutableListOf<AlternativeTitle>()
    altTitles.forEach { altTitle ->
      altTitle.forEach { (lang, title) ->
        alternativeTitles.add(AlternativeTitle(title, lang))
      }
    }

    return MangaDexManga(
      id = id,
      title = englishTitle,
      description = description,
      author = author,
      artist = artist,
      status = attributes["status"] as? String,
      year = (attributes["year"] as? Number)?.toInt(),
      contentRating = attributes["contentRating"] as? String,
      publicationDemographic = attributes["publicationDemographic"] as? String,
      genres = genres,
      alternativeTitles = alternativeTitles,
      coverFilename = coverFilename,
      lastChapter = attributes["lastChapter"] as? String,
      lastVolume = attributes["lastVolume"] as? String,
    )
  }

  @Suppress("UNCHECKED_CAST")
  private fun parseChapterFeedResponse(response: Map<String, Any?>): List<MangaDexChapter> {
    val data = response["data"] as? List<Map<String, Any?>> ?: return emptyList()
    return data.mapNotNull { parseChapterData(it) }
  }

  @Suppress("UNCHECKED_CAST")
  private fun parseChapterData(data: Map<String, Any?>): MangaDexChapter? {
    val id = data["id"] as? String ?: return null
    val attributes = data["attributes"] as? Map<String, Any?> ?: return null
    val relationships = data["relationships"] as? List<Map<String, Any?>> ?: emptyList()

    // Parse scanlation group
    var scanlationGroup: String? = null
    relationships.forEach { rel ->
      if (rel["type"] == "scanlation_group") {
        val relAttrs = rel["attributes"] as? Map<String, Any?>
        scanlationGroup = relAttrs?.get("name") as? String
      }
    }

    val chapterStr = attributes["chapter"] as? String
    val chapter =
      chapterStr?.toDoubleOrNull()
        ?: 0.0

    val volumeStr = attributes["volume"] as? String
    val volume = volumeStr?.toIntOrNull()

    return MangaDexChapter(
      id = id,
      url = "https://mangadex.org/chapter/$id",
      chapter = chapter,
      volume = volume,
      title = attributes["title"] as? String,
      lang = attributes["translatedLanguage"] as? String ?: "en",
      pages = (attributes["pages"] as? Number)?.toInt() ?: 0,
      publishAt = attributes["publishAt"] as? String,
      scanlationGroup = scanlationGroup,
      externalUrl = attributes["externalUrl"] as? String,
    )
  }
}

/**
 * MangaDex manga data.
 */
data class MangaDexManga(
  val id: String,
  val title: String,
  val description: String?,
  val author: String?,
  val artist: String?,
  val status: String?,
  val year: Int?,
  val contentRating: String?,
  val publicationDemographic: String?,
  val genres: List<String>,
  val alternativeTitles: List<AlternativeTitle>,
  val coverFilename: String?,
  val lastChapter: String?,
  val lastVolume: String?,
)

/**
 * MangaDex chapter data.
 */
data class MangaDexChapter(
  val id: String,
  val url: String,
  val chapter: Double,
  val volume: Int?,
  val title: String?,
  val lang: String,
  val pages: Int,
  val publishAt: String?,
  val scanlationGroup: String?,
  val externalUrl: String?,
)

/**
 * Alternative title with language.
 */
data class AlternativeTitle(
  val title: String,
  val language: String,
)

/**
 * Cover image quality options.
 */
enum class CoverQuality {
  ORIGINAL,
  MEDIUM, // 512px
  THUMBNAIL, // 256px
}
