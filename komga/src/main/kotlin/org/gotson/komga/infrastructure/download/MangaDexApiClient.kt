package org.gotson.komga.infrastructure.download

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class MangaDexApiClient(
  private val rateLimiter: MangaDexRateLimiter,
) {
  private val objectMapper: ObjectMapper = jacksonObjectMapper()
  private val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()
  private val chapterCache = ConcurrentHashMap<String, Pair<List<ChapterDownloadInfo>, Long>>()
  private val mangaInfoCache = ConcurrentHashMap<String, Pair<MangaInfo, Long>>()

  companion object {
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    const val CONTENT_RATING_PARAMS =
      "contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&contentRating[]=pornographic"
    private val mangaDexIdRegex = """mangadex\.org/title/([a-f0-9-]{36})""".toRegex()

    fun extractMangaDexId(url: String): String? = mangaDexIdRegex.find(url)?.groupValues?.get(1)
  }

  @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
  fun evictExpiredCacheEntries() {
    val now = System.currentTimeMillis()
    val chaptersBefore = chapterCache.size
    val infoBefore = mangaInfoCache.size
    chapterCache.entries.removeIf { now - it.value.second > CACHE_TTL_MS }
    mangaInfoCache.entries.removeIf { now - it.value.second > CACHE_TTL_MS }
    val evicted = (chaptersBefore - chapterCache.size) + (infoBefore - mangaInfoCache.size)
    if (evicted > 0) logger.debug { "Evicted $evicted expired cache entries" }
  }

  fun getChaptersForManga(
    mangaId: String,
    language: String,
  ): List<ChapterDownloadInfo> {
    evictExpiredCacheEntries()
    val cacheKey = "$mangaId:$language"
    val cached = chapterCache[cacheKey]
    if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
      logger.debug { "Using cached chapter data for $mangaId" }
      return cached.first
    }
    val chapters = fetchAllChaptersFromMangaDex(mangaId, language)
    chapterCache[cacheKey] = Pair(chapters, System.currentTimeMillis())
    return chapters
  }

  fun getMangaMetadata(mangaId: String): MangaInfo? {
    evictExpiredCacheEntries()
    val cached = mangaInfoCache[mangaId]
    if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
      logger.debug { "Using cached manga metadata for $mangaId" }
      return cached.first
    }
    val info = fetchMangaDexMetadata(mangaId)
    if (info != null) mangaInfoCache[mangaId] = Pair(info, System.currentTimeMillis())
    return info
  }

  fun getMangaDexChapterCount(
    mangaDexId: String,
    language: String,
  ): Int? =
    try {
      rateLimiter.waitIfNeeded()
      val feedUrl =
        "https://api.mangadex.org/manga/$mangaDexId/feed?" +
          "translatedLanguage[]=$language&" +
          "$CONTENT_RATING_PARAMS&" +
          "limit=0"
      val request =
        HttpRequest
          .newBuilder()
          .uri(URI.create(feedUrl))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() == 200) {
        val json = objectMapper.readValue<Map<String, Any?>>(response.body())
        (json["total"] as? Number)?.toInt()
      } else {
        null
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to get MangaDex chapter count for $mangaDexId" }
      null
    }

  fun fetchMangaDexMetadata(mangaId: String): MangaInfo? {
    try {
      val request =
        HttpRequest
          .newBuilder()
          .uri(URI.create("https://api.mangadex.org/manga/$mangaId?includes[]=author&includes[]=artist&includes[]=cover_art"))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()

      rateLimiter.waitIfNeeded()
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() == 429) {
        logger.warn { "MangaDex rate limited (429) for manga $mangaId, waiting 2s and retrying" }
        Thread.sleep(2000)
        rateLimiter.waitIfNeeded()
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      }

      if (response.statusCode() != 200) {
        logger.warn { "MangaDex API returned ${response.statusCode()} for manga $mangaId" }
        return null
      }

      val jsonResponse = objectMapper.readValue(response.body(), Map::class.java) as Map<*, *>
      val data = jsonResponse["data"] as? Map<*, *> ?: return null
      val attributes = data["attributes"] as? Map<*, *> ?: return null

      val titleMap = attributes["title"] as? Map<*, *> ?: emptyMap<String, String>()
      val mainEnglishTitle = titleMap["en"] as? String

      val altTitles = attributes["altTitles"] as? List<*> ?: emptyList<Map<String, String>>()
      val alternativeTitlesWithLang = mutableMapOf<String, String>()
      val alternativeTitlesList = mutableListOf<String>()
      var altEnglishTitle: String? = null

      altTitles.forEach { altTitleEntry ->
        if (altTitleEntry is Map<*, *>) {
          altTitleEntry.entries.forEach { (lang, title) ->
            if (lang is String && title is String) {
              alternativeTitlesWithLang[title] = lang
              alternativeTitlesList.add(title)
              if (altEnglishTitle == null && lang == "en") {
                altEnglishTitle = title
                logger.debug { "Found English title in altTitles: $title" }
              }
            }
          }
        }
      }

      titleMap.forEach { (lang, title) ->
        if (lang is String && title is String && title !in alternativeTitlesList) {
          alternativeTitlesWithLang[title] = lang
          alternativeTitlesList.add(title)
        }
      }

      val englishTitle =
        when {
          altEnglishTitle != null -> altEnglishTitle
          mainEnglishTitle != null -> {
            logger.debug { "Using main title.en (may be romaji): $mainEnglishTitle" }
            mainEnglishTitle
          }
          else -> {
            val fallback = titleMap.values.firstOrNull() as? String
            logger.debug { "No English title found, using first available: $fallback" }
            fallback
          }
        }

      val finalTitle =
        if (englishTitle != null && englishTitle.length > 80) {
          val allEnglishTitles = mutableListOf<String>()
          if (mainEnglishTitle != null) allEnglishTitles.add(mainEnglishTitle)
          altTitles.forEach { entry ->
            if (entry is Map<*, *>) {
              entry.entries.forEach { (lang, title) ->
                if (lang == "en" && title is String) allEnglishTitles.add(title)
              }
            }
          }
          val shortest = allEnglishTitles.minByOrNull { it.length }
          if (shortest != null && shortest.length < englishTitle.length) {
            logger.debug { "Title too long (${englishTitle.length} chars), using shortest EN title: $shortest" }
            shortest
          } else {
            englishTitle
          }
        } else {
          englishTitle
        }

      val descriptionMap = attributes["description"] as? Map<*, *> ?: emptyMap<String, String>()
      val description = descriptionMap["en"] as? String

      val relationships = data["relationships"] as? List<*> ?: emptyList<Map<String, Any>>()
      var author: String? = null
      var artist: String? = null

      relationships.forEach { rel ->
        if (rel is Map<*, *>) {
          val relType = rel["type"] as? String
          val relAttributes = rel["attributes"] as? Map<*, *>
          val name = relAttributes?.get("name") as? String
          when (relType) {
            "author" -> if (author == null && name != null) author = name
            "artist" -> if (artist == null && name != null) artist = name
          }
        }
      }

      val authorArtist =
        when {
          author != null && artist != null && author != artist -> "$author, $artist"
          author != null -> author
          artist != null -> artist
          else -> null
        }

      val tags = attributes["tags"] as? List<*> ?: emptyList<Map<String, Any>>()
      val genres = mutableListOf<String>()

      tags.forEach { tag ->
        if (tag is Map<*, *>) {
          val tagAttributes = tag["attributes"] as? Map<*, *>
          val tagName = tagAttributes?.get("name") as? Map<*, *>
          val englishTagName = tagName?.get("en") as? String
          if (englishTagName != null) {
            genres.add(englishTagName)
          }
        }
      }

      val year = attributes["year"] as? Int
      val status = attributes["status"] as? String
      val publicationDemographic = attributes["publicationDemographic"] as? String

      var coverFilename: String? = null
      relationships.forEach { rel ->
        if (rel is Map<*, *> && rel["type"] == "cover_art") {
          val coverAttributes = rel["attributes"] as? Map<*, *>
          coverFilename = coverAttributes?.get("fileName") as? String
        }
      }

      logger.debug { "Successfully fetched MangaDex metadata for $mangaId: title='$finalTitle', author='$authorArtist', cover='$coverFilename'" }

      return MangaInfo(
        title = finalTitle ?: "Unknown",
        author = authorArtist,
        totalChapters = 0,
        description = description,
        alternativeTitles = alternativeTitlesList,
        alternativeTitlesWithLanguage = alternativeTitlesWithLang,
        scanlationGroup = null,
        year = year,
        status = status,
        publicationDemographic = publicationDemographic,
        genres = genres,
        coverFilename = coverFilename,
        mangaDexId = mangaId,
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to fetch MangaDex API metadata for $mangaId" }
      return null
    }
  }

  fun fetchChapterMetadata(chapterId: String): ChapterInfo? {
    try {
      val request =
        HttpRequest
          .newBuilder()
          .uri(URI.create("https://api.mangadex.org/chapter/$chapterId"))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()

      rateLimiter.waitIfNeeded()
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() == 429) {
        logger.warn { "MangaDex rate limited (429) for chapter $chapterId, waiting 2s" }
        Thread.sleep(2000)
        rateLimiter.waitIfNeeded()
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      }

      if (response.statusCode() != 200) {
        logger.warn { "MangaDex chapter API returned ${response.statusCode()} for chapter $chapterId" }
        return null
      }

      val jsonResponse = objectMapper.readValue(response.body(), Map::class.java) as Map<*, *>
      val data = jsonResponse["data"] as? Map<*, *> ?: return null
      val attributes = data["attributes"] as? Map<*, *> ?: return null

      val chapterNumber = attributes["chapter"] as? String
      val chapterTitle = attributes["title"] as? String
      val volume = attributes["volume"] as? String
      val pages = attributes["pages"] as? Int ?: 0
      val publishDate = attributes["publishAt"] as? String
      val language = attributes["translatedLanguage"] as? String

      val relationships = data["relationships"] as? List<*> ?: emptyList<Map<String, Any>>()
      var scanlationGroup: String? = null

      relationships.forEach { relationship ->
        if (relationship is Map<*, *>) {
          val type = relationship["type"] as? String
          if (type == "scanlation_group") {
            val groupAttributes = relationship["attributes"] as? Map<*, *>
            scanlationGroup = groupAttributes?.get("name") as? String
          }
        }
      }

      logger.debug { "Fetched chapter metadata: chapter=$chapterNumber, title='$chapterTitle', volume=$volume, pages=$pages" }

      return ChapterInfo(
        chapterNumber = chapterNumber,
        chapterTitle = chapterTitle,
        volume = volume,
        pages = pages,
        scanlationGroup = scanlationGroup,
        publishDate = publishDate,
        language = language,
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to fetch chapter metadata for $chapterId" }
      return null
    }
  }

  fun fetchAllChaptersFromMangaDex(
    mangaId: String,
    language: String = "en",
  ): List<ChapterDownloadInfo> {
    val chapters = mutableListOf<ChapterDownloadInfo>()
    var offset = 0
    val limit = 500

    try {
      while (true) {
        val apiUrl =
          "https://api.mangadex.org/manga/$mangaId/feed?" +
            "translatedLanguage[]=$language&" +
            "$CONTENT_RATING_PARAMS&" +
            "includes[]=scanlation_group&" +
            "order[chapter]=asc&" +
            "limit=$limit&" +
            "offset=$offset"

        val request =
          HttpRequest
            .newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        rateLimiter.waitIfNeeded()
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 429) {
          logger.warn { "MangaDex rate limited (429) on feed, waiting 2s" }
          Thread.sleep(2000)
          rateLimiter.waitIfNeeded()
          response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() != 200) {
          logger.warn { "MangaDex feed API returned ${response.statusCode()}" }
          break
        }

        val jsonResponse = objectMapper.readValue(response.body(), Map::class.java) as Map<*, *>
        val data = jsonResponse["data"] as? List<*> ?: break

        if (data.isEmpty()) break

        data.forEach { item ->
          if (item is Map<*, *>) {
            val id = item["id"] as? String ?: return@forEach
            val attributes = item["attributes"] as? Map<*, *> ?: return@forEach

            val chapterNumber = attributes["chapter"] as? String
            val chapterTitle = attributes["title"] as? String
            val volume = attributes["volume"] as? String
            val pages = attributes["pages"] as? Int ?: 0
            val publishDate = attributes["publishAt"] as? String
            val chapterLanguage = attributes["translatedLanguage"] as? String

            val relationships = item["relationships"] as? List<*> ?: emptyList<Map<String, Any>>()
            var scanlationGroup: String? = null

            relationships.forEach { relationship ->
              if (relationship is Map<*, *>) {
                val type = relationship["type"] as? String
                if (type == "scanlation_group") {
                  val groupAttributes = relationship["attributes"] as? Map<*, *>
                  scanlationGroup = groupAttributes?.get("name") as? String
                }
              }
            }

            chapters.add(
              ChapterDownloadInfo(
                chapterId = id,
                chapterNumber = chapterNumber,
                chapterTitle = chapterTitle,
                volume = volume,
                pages = pages,
                scanlationGroup = scanlationGroup,
                publishDate = publishDate,
                language = chapterLanguage,
                chapterUrl = "https://mangadex.org/chapter/$id",
              ),
            )
          }
        }

        val total = jsonResponse["total"] as? Int ?: 0
        offset += limit
        if (offset >= total) break
      }

      logger.debug { "Fetched ${chapters.size} chapters from MangaDex for manga $mangaId (language=$language)" }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to fetch chapter list from MangaDex" }
    }

    return chapters
  }

  fun downloadMangaCover(
    mangaId: String,
    coverFilename: String,
    destinationPath: Path,
  ) {
    try {
      val coverUrl = "https://uploads.mangadex.org/covers/$mangaId/$coverFilename"
      logger.info { "Downloading cover from: $coverUrl" }

      val request =
        HttpRequest
          .newBuilder()
          .uri(URI.create(coverUrl))
          .timeout(Duration.ofSeconds(30))
          .GET()
          .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

      if (response.statusCode() != 200) {
        logger.warn { "Cover download failed with status ${response.statusCode()}" }
        return
      }

      val extension = coverFilename.substringAfterLast('.', "jpg")
      val coverFile = destinationPath.resolve("cover.$extension").toFile()
      coverFile.writeBytes(response.body())

      logger.debug { "Cover downloaded successfully: ${coverFile.absolutePath} (${response.body().size} bytes)" }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to download cover for manga $mangaId" }
    }
  }
}
