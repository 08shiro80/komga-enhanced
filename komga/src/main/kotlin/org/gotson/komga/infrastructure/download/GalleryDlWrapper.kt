package org.gotson.komga.infrastructure.download

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val logger = KotlinLogging.logger {}

@Service
class GalleryDlWrapper(
  private val pluginConfigRepository: org.gotson.komga.domain.persistence.PluginConfigRepository,
  private val pluginLogRepository: org.gotson.komga.domain.persistence.PluginLogRepository,
  private val blacklistedChapterRepository: org.gotson.komga.domain.persistence.BlacklistedChapterRepository,
) {
  private val objectMapper: ObjectMapper = jacksonObjectMapper()
  private val pluginId = "gallery-dl-downloader"
  private var lastMangaDexRequestTime = 0L
  private val mangaDexMinIntervalMs = 450L

  private fun throttleMangaDexApi() {
    val now = System.currentTimeMillis()
    val elapsed = now - lastMangaDexRequestTime
    if (elapsed < mangaDexMinIntervalMs) {
      Thread.sleep(mangaDexMinIntervalMs - elapsed)
    }
    lastMangaDexRequestTime = System.currentTimeMillis()
  }

  fun getMangaDexChapterCount(mangaDexId: String): Int? =
    try {
      throttleMangaDexApi()
      val pluginConfig =
        pluginConfigRepository
          .findByPluginId(pluginId)
          .associate { it.configKey to it.configValue }
      val language = pluginConfig["default_language"] ?: "en"
      val feedUrl =
        "https://api.mangadex.org/manga/$mangaDexId/feed?translatedLanguage[]=$language&limit=0"
      val httpClient =
        HttpClient
          .newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build()
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
      logger.debug { "Failed to get MangaDex chapter count for $mangaDexId: ${e.message}" }
      null
    }

  fun isInstalled(): Boolean =
    try {
      val command = getGalleryDlCommand() + "--version"
      val process =
        applyGalleryDlEnv(ProcessBuilder())
          .command(command)
          .start()

      process.waitFor(5, TimeUnit.SECONDS)
      process.exitValue() == 0
    } catch (e: Exception) {
      logger.debug { "gallery-dl not found: ${e.message}" }
      false
    }

  private fun fetchMangaDexMetadata(mangaId: String): MangaInfo? {
    try {
      val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
      val request =
        HttpRequest
          .newBuilder()
          .uri(URI.create("https://api.mangadex.org/manga/$mangaId?includes[]=cover_art&includes[]=author&includes[]=artist"))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()

      throttleMangaDexApi()
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() == 429) {
        logger.warn { "MangaDex rate limited (429) for manga $mangaId, waiting 2s and retrying" }
        Thread.sleep(2000)
        throttleMangaDexApi()
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
                logger.info { "Found English title in altTitles: $title" }
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

      // Priority: altTitles English > main title English > any title
      val englishTitle =
        when {
          altEnglishTitle != null -> altEnglishTitle
          mainEnglishTitle != null -> {
            logger.warn { "Using main title.en (may be romaji): $mainEnglishTitle" }
            mainEnglishTitle
          }
          else -> {
            val fallback = titleMap.values.firstOrNull() as? String
            logger.warn { "No English title found, using first available: $fallback" }
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
            logger.info { "Title too long (${englishTitle.length} chars), using shortest EN title: $shortest" }
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

      logger.info { "Successfully fetched MangaDex metadata for $mangaId: title='$finalTitle', author='$authorArtist', cover='$coverFilename'" }

      return MangaInfo(
        title = finalTitle ?: "Unknown",
        author = authorArtist,
        totalChapters = 0, // Will be updated during download
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

  companion object {
    private val MANGADEX_ID_REGEX = """mangadex\.org/title/([a-f0-9-]{36})""".toRegex()

    fun extractMangaDexId(url: String): String? = MANGADEX_ID_REGEX.find(url)?.groupValues?.get(1)
  }

  private fun downloadMangaCover(
    mangaId: String,
    coverFilename: String,
    destinationPath: Path,
  ) {
    try {
      val coverUrl = "https://uploads.mangadex.org/covers/$mangaId/$coverFilename"
      logger.info { "Downloading cover from: $coverUrl" }

      val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
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
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.WARN,
          "Cover download failed with status ${response.statusCode()}",
        )
        return
      }

      val extension = coverFilename.substringAfterLast('.', "jpg")
      val coverFile = destinationPath.resolve("cover.$extension").toFile()
      coverFile.writeBytes(response.body())

      logger.info { "Cover downloaded successfully: ${coverFile.absolutePath} (${response.body().size} bytes)" }
      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.INFO,
        "Downloaded cover image: ${coverFile.name} (${response.body().size} bytes)",
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to download cover for manga $mangaId" }
      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.WARN,
        "Failed to download cover: ${e.message}",
      )
    }
  }

  private fun extractChapterId(cbzPath: Path): String? {
    val filename = cbzPath.fileName.toString()
    val regex = """[\[\(]([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})[\]\)]""".toRegex()
    return regex.find(filename)?.groupValues?.get(1)
  }

  private fun extractChapterNumberFromFilename(filename: String): String? {
    val name = filename.substringBeforeLast('.').lowercase()
    val match =
      Regex("""^c(\d+(?:\.\d+)?)""").find(name)
        ?: Regex("""^ch\.?\s*(\d+(?:\.\d+)?)""").find(name)
    val raw = match?.groupValues?.get(1) ?: return null
    return try {
      val num = raw.toDouble()
      if (num == num.toLong().toDouble()) num.toLong().toString() else raw
    } catch (_: NumberFormatException) {
      raw
    }
  }

  private fun fetchChapterMetadata(chapterId: String): ChapterInfo? {
    try {
      val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
      val request =
        HttpRequest
          .newBuilder()
          .uri(URI.create("https://api.mangadex.org/chapter/$chapterId"))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()

      throttleMangaDexApi()
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() == 429) {
        logger.warn { "MangaDex rate limited (429) for chapter $chapterId, waiting 2s" }
        Thread.sleep(2000)
        throttleMangaDexApi()
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

      logger.info { "Fetched chapter metadata: chapter=$chapterNumber, title='$chapterTitle', volume=$volume, pages=$pages" }

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

  data class ChapterDownloadInfo(
    val chapterId: String,
    val chapterNumber: String?,
    val chapterTitle: String?,
    val volume: String?,
    val pages: Int,
    val scanlationGroup: String?,
    val publishDate: String?,
    val language: String?,
    val chapterUrl: String,
  )

  private fun fetchAllChaptersFromMangaDex(
    mangaId: String,
    language: String = "en",
  ): List<ChapterDownloadInfo> {
    val chapters = mutableListOf<ChapterDownloadInfo>()
    var offset = 0
    val limit = 100

    try {
      val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

      while (true) {
        val apiUrl =
          "https://api.mangadex.org/manga/$mangaId/feed?" +
            "translatedLanguage[]=$language&" +
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

        throttleMangaDexApi()
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 429) {
          logger.warn { "MangaDex rate limited (429) on feed, waiting 2s" }
          Thread.sleep(2000)
          throttleMangaDexApi()
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

            // Extract scanlation group from relationships
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

      logger.info { "Fetched ${chapters.size} chapters from MangaDex for manga $mangaId" }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to fetch chapter list from MangaDex" }
    }

    return chapters
  }

  fun getChapterInfo(url: String): MangaInfo {
    val mangadexId = extractMangaDexId(url)
    if (mangadexId != null) {
      logger.info { "Detected MangaDex URL, fetching metadata from API for manga ID: $mangadexId" }
      val apiMetadata = fetchMangaDexMetadata(mangadexId)
      if (apiMetadata != null) {
        logger.info { "Using MangaDex API metadata: ${apiMetadata.title}" }
        return apiMetadata.copy(sourceUrl = url)
      }
      logger.warn { "MangaDex API fetch failed, falling back to gallery-dl metadata" }
    }

    val output = StringBuilder()
    val errorOutput = StringBuilder()
    val configFile = createInfoConfigFile()

    try {
      val command =
        getGalleryDlCommand().toMutableList().apply {
          add(url)
          add("-j")
          add("--simulate")
          add("--config")
          add(configFile.absolutePath)
        }

      logger.info { "Executing getChapterInfo: ${command.joinToString(" ")}" }

      val process =
        applyGalleryDlEnv(ProcessBuilder())
          .command(command)
          .start()

      // Read stdout
      BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        reader.lines().forEach { line ->
          output.appendLine(line)
          logger.debug { "gallery-dl info: $line" }
        }
      }

      // Read stderr
      BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
        reader.lines().forEach { line ->
          errorOutput.appendLine(line)
          logger.debug { "gallery-dl error: $line" }
        }
      }

      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        configFile.delete()
        throw GalleryDlException("Timeout getting chapter info for $url")
      }

      val exitValue = process.exitValue()
      configFile.delete()

      if (exitValue != 0) {
        val errorMsg = "gallery-dl failed with exit code $exitValue: ${errorOutput.toString().trim()}"
        logToDatabase(org.gotson.komga.domain.model.LogLevel.ERROR, errorMsg)
        logger.error { errorMsg }
        throw GalleryDlException(errorMsg)
      }

      val mangaInfo =
        parseGalleryDlJson(output.toString()).copy(sourceUrl = url)

      if (mangaInfo.title.isBlank() || mangaInfo.title == "Unknown") {
        val fallback = deriveTitleFromUrl(url)
        if (fallback != null) {
          logger.info { "No title in metadata, using URL-derived title: $fallback" }
          return mangaInfo.copy(title = fallback)
        }
        val errorMsg = "Failed to extract manga title from URL: $url"
        logToDatabase(org.gotson.komga.domain.model.LogLevel.ERROR, errorMsg)
        logger.error { errorMsg }
        throw GalleryDlException(errorMsg)
      }

      logger.info { "Successfully extracted title: ${mangaInfo.title}" }
      return mangaInfo
    } catch (e: GalleryDlException) {
      throw e
    } catch (e: Exception) {
      val errorMsg = "Error getting chapter info from $url: ${e.message}"
      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.ERROR,
        errorMsg,
        e.stackTraceToString(),
      )
      logger.error(e) { errorMsg }
      throw GalleryDlException(errorMsg, e)
    }
  }

  fun fetchGalleryDlChapterMapping(url: String): Map<String, ChapterDownloadInfo> {
    val output = StringBuilder()
    val configFile = createInfoConfigFile()

    try {
      val command =
        getGalleryDlCommand().toMutableList().apply {
          add(url)
          add("-j")
          add("--simulate")
          add("--config")
          add(configFile.absolutePath)
        }

      val process =
        applyGalleryDlEnv(ProcessBuilder())
          .command(command)
          .start()

      BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        reader.lines().forEach { line -> output.appendLine(line) }
      }
      BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
        reader.lines().forEach { _ -> }
      }

      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        configFile.delete()
        return emptyMap()
      }
      configFile.delete()
      if (process.exitValue() != 0) return emptyMap()

      val mapping = mutableMapOf<String, ChapterDownloadInfo>()
      val entries = objectMapper.readValue(output.toString(), List::class.java) as? List<*> ?: return emptyMap()

      entries.forEach { entry ->
        if (entry !is List<*> || entry.size < 3) return@forEach
        val messageType = entry[0] as? Int ?: return@forEach
        if (messageType != 6) return@forEach

        val chapterUrl = entry[1] as? String ?: return@forEach
        val metadata = entry[2] as? Map<*, *> ?: return@forEach

        val chapterIdNum = metadata["chapter_id"]
        val chapterId = chapterIdNum?.toString() ?: return@forEach
        val chapterNum = metadata["chapter"]
        val chapterMinor = metadata["chapter-minor"] as? String ?: metadata["chapter_minor"] as? String ?: ""
        val chapterNumber =
          if (chapterNum != null) {
            val numStr = chapterNum.toString()
            if (chapterMinor.isNotBlank() && !chapterMinor.startsWith(".")) "$numStr.$chapterMinor" else "$numStr$chapterMinor"
          } else {
            null
          }

        mapping[chapterId] =
          ChapterDownloadInfo(
            chapterId = chapterId,
            chapterNumber = chapterNumber,
            chapterTitle = null,
            volume = null,
            pages = 0,
            scanlationGroup = null,
            publishDate = metadata["date"] as? String,
            language = metadata["language"] as? String ?: metadata["lang"] as? String,
            chapterUrl = chapterUrl,
          )
      }

      logger.info { "Parsed ${mapping.size} chapters from gallery-dl simulate output" }
      return mapping
    } catch (e: Exception) {
      logger.warn(e) { "Failed to parse gallery-dl chapter mapping" }
      configFile.delete()
      return emptyMap()
    }
  }

  private fun createInfoConfigFile(): File {
    val tempFile = File.createTempFile("gallery-dl-info-", ".json")

    val pluginConfig = pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue }
    val mangadexUsername = pluginConfig["mangadex_username"]
    val mangadexPassword = pluginConfig["mangadex_password"]
    val defaultLanguage = pluginConfig["default_language"] ?: "en"

    val config =
      mutableMapOf<String, Any>(
        "extractor" to
          mutableMapOf<String, Any>(
            "base-directory" to "",
            "mangadex" to
              mutableMapOf<String, Any>("lang" to defaultLanguage).apply {
                if (!mangadexUsername.isNullOrBlank()) {
                  this["username"] = mangadexUsername
                }
                if (!mangadexPassword.isNullOrBlank()) {
                  this["password"] = mangadexPassword
                }
              },
          ),
      )

    tempFile.writeText(objectMapper.writeValueAsString(config))
    return tempFile
  }

  fun download(
    url: String,
    destinationPath: Path,
    libraryPath: Path? = null,
    komgaSeriesId: String? = null,
    isCancelled: () -> Boolean = { false },
    onProcessStarted: (Process) -> Unit = {},
    onProgress: (DownloadProgress) -> Unit = {},
  ): DownloadResult {
    val output = StringBuilder()
    val errorOutput = StringBuilder()

    try {
      logger.info { "Fetching metadata for $url" }
      var mangaInfo = getChapterInfo(url)

      logger.info { "Using title: ${mangaInfo.title}" }

      val destDir = destinationPath.toFile()
      if (!destDir.exists()) {
        destDir.mkdirs()
      }

      logger.info { "Creating series.json" }
      try {
        createSeriesJson(mangaInfo, destinationPath)
      } catch (e: Exception) {
        logger.warn(e) { "Failed to create series.json, continuing anyway" }
      }

      val seriesJson = readSeriesJson(destinationPath)
      val configFile = createTempConfigFile()

      val pluginConfig = pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue }
      val defaultLanguage = pluginConfig["default_language"] ?: "en"

      val command =
        getGalleryDlCommand().toMutableList().apply {
          add(url)
          add("-d")
          add(destinationPath.toString())
          add("--config")
          add(configFile.absolutePath)
          if (extractMangaDexId(url) != null) {
            add("-o")
            add("lang=$defaultLanguage")
          }
        }

      try {
        val mangaDexId = extractMangaDexId(url)
        if (mangaDexId != null && mangaInfo.coverFilename != null) {
          val existingCoverFile =
            destDir.listFiles()?.find {
              it.name.startsWith("cover.") && it.isFile
            }

          @Suppress("UNCHECKED_CAST")
          val storedCoverFilename =
            (seriesJson?.get("metadata") as? Map<String, Any?>)?.get("cover_filename") as? String
          val coverChanged = storedCoverFilename != mangaInfo.coverFilename

          if (existingCoverFile != null && !coverChanged) {
            logger.debug { "Cover already exists and unchanged, skipping download" }
          } else {
            logger.info { "Downloading cover image" }
            downloadMangaCover(mangaDexId, mangaInfo.coverFilename!!, destinationPath)
          }
        } else {
          logger.warn { "Cannot download cover: mangaDexId=$mangaDexId, coverFilename=${mangaInfo.coverFilename}" }
        }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to download cover image (non-fatal)" }
      }

      val mangaDexId = extractMangaDexId(url)
      val allChapters =
        if (mangaDexId != null) {
          logger.info { "Fetching chapter list from MangaDex API" }
          fetchAllChaptersFromMangaDex(mangaDexId)
        } else {
          logger.warn { "Not a MangaDex URL, falling back to single download" }
          emptyList()
        }

      normalizeDoubleBracketFilenames(destDir)

      val urlsFromCbz = extractChapterUrlsFromCbzFiles(destDir)
      logger.info { "CBZ ComicInfo check: Found ${urlsFromCbz.size} chapter URLs in existing CBZ files" }

      val existingCbzFiles =
        destDir
          .listFiles()
          ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
          ?.map { it.nameWithoutExtension.lowercase() }
          ?.toSet()
          ?: emptySet()
      logger.info { "File system check: Found ${existingCbzFiles.size} existing CBZ files" }

      val chaptersByNumber = allChapters.groupBy { it.chapterNumber }
      val multiGroupChapterNumbers =
        chaptersByNumber
          .filter { (_, chaps) -> chaps.map { it.scanlationGroup }.distinct().size > 1 }
          .keys
          .toSet()
      if (multiGroupChapterNumbers.isNotEmpty()) {
        logger.info { "Multi-group chapters detected: ${multiGroupChapterNumbers.size} chapter numbers with multiple groups" }
      }

      val blacklistedUrls = blacklistedChapterRepository.findAll().map { it.chapterUrl }.toSet()

      val chapters =
        allChapters.filter { chapter ->
          if (chapter.chapterUrl in blacklistedUrls) {
            logger.debug { "Skipping blacklisted chapter ${chapter.chapterNumber}" }
            return@filter false
          }

          if (chapter.chapterUrl in urlsFromCbz) {
            logger.debug { "Skipping chapter ${chapter.chapterNumber} - URL found in existing CBZ" }
            return@filter false
          }

          val chapterNumStr = chapter.chapterNumber ?: return@filter true
          val chapterStr =
            try {
              val num = chapterNumStr.toDouble()
              if (num == num.toLong().toDouble()) {
                num.toLong().toString()
              } else {
                chapterNumStr
              }
            } catch (_: NumberFormatException) {
              chapterNumStr
            }

          val paddedNum = padChapterNumber(chapterNumStr)

          val groupTag = chapter.scanlationGroup?.lowercase()

          val alreadyExists =
            existingCbzFiles.any { name ->
              val matchesChapterNum =
                name == "c$chapterStr" ||
                  name == "c$chapterNumStr" ||
                  name.startsWith("c$chapterStr ") ||
                  name.startsWith("c$chapterNumStr ") ||
                  name == "ch. $paddedNum" ||
                  name.startsWith("ch. $paddedNum -") ||
                  name.startsWith("ch. $paddedNum ") ||
                  name == "chapter$chapterStr" ||
                  name == "chapter $chapterStr" ||
                  name == "ch$chapterStr" ||
                  name == "ch $chapterStr"

              if (!matchesChapterNum) return@any false
              if (groupTag == null) return@any true
              name.contains("[$groupTag]")
            }

          if (alreadyExists) {
            logger.debug { "Skipping chapter $chapterStr - CBZ file already exists" }
          }
          !alreadyExists
        }

      val skippedCount = allChapters.size - chapters.size
      if (skippedCount > 0) {
        logger.info { "Skipping $skippedCount already downloaded chapters, ${chapters.size} remaining to download" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Skipping $skippedCount already downloaded chapters")

        updateExistingCbzChapterUrls(destDir, allChapters, urlsFromCbz, mangaInfo)
      } else {
        logger.info { "No existing chapters found, downloading all ${allChapters.size} chapters" }
      }

      var filesDownloaded = 0
      val totalChapters = chapters.size

      if (allChapters.isEmpty() && mangaDexId != null) {
        logger.warn { "MangaDex chapter API returned empty for $mangaDexId — skipping bulk download to prevent re-downloads" }
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.WARN,
          "MangaDex chapter API returned empty for $mangaDexId, skipping download",
        )
        configFile.delete()

        val downloadedFiles =
          destDir
            .listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
            ?.map { it.absolutePath }
            ?: emptyList()

        return DownloadResult(
          success = true,
          filesDownloaded = downloadedFiles.size,
          downloadedFiles = downloadedFiles,
          totalChapters = downloadedFiles.size,
          errorMessage = null,
          mangaTitle = mangaInfo.title,
        )
      } else if (allChapters.isEmpty()) {
        val galleryDlChapterMap =
          if (mangaDexId == null) {
            fetchGalleryDlChapterMapping(url)
          } else {
            emptyMap()
          }

        logger.info { "Starting bulk download: $url (chapter mapping: ${galleryDlChapterMap.size} chapters)" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Starting download: $url")

        val process =
          applyGalleryDlEnv(ProcessBuilder())
            .command(command)
            .directory(File(System.getProperty("user.home")))
            .start()

        onProcessStarted(process)
        var lastProgress = 0

        Thread {
          BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lines().forEach { line ->
              output.appendLine(line)
              logger.info { "gallery-dl: $line" }

              if (line.contains("✔") || line.contains("*")) {
                filesDownloaded++
              }
            }
          }
        }.start()

        Thread {
          BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            reader.lines().forEach { line ->
              errorOutput.appendLine(line)
              logger.info { "gallery-dl stderr: $line" }

              val progress = parseGalleryDlProgress(line, filesDownloaded)
              if (progress != null && progress.percent > lastProgress) {
                lastProgress = progress.percent
                onProgress(progress)
              }
            }
          }
        }.start()

        if (!process.waitFor(2, TimeUnit.HOURS)) {
          process.destroyForcibly()
          configFile.delete()
          throw GalleryDlException("Timeout downloading $url")
        }

        val exitCode = process.exitValue()
        configFile.delete()

        if (exitCode != 0) {
          throw GalleryDlException("Download failed with exit code $exitCode: ${errorOutput.toString().trim()}")
        }

        val cbzFilesInSubdirs =
          destDir
            .walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "cbz" && it.parentFile != destDir }
            .toList()
        if (cbzFilesInSubdirs.isNotEmpty()) {
          logger.info { "Found ${cbzFilesInSubdirs.size} CBZ files in subdirectories, moving to root" }
          cbzFilesInSubdirs.forEach { cbzFile ->
            val target = File(destDir, cbzFile.name)
            if (!target.exists()) {
              cbzFile.renameTo(target)
              logger.info { "Moved ${cbzFile.relativeTo(destDir).path} -> ${target.name}" }
            }
          }
          destDir.walkTopDown().filter { it.isDirectory && it != destDir && it.listFiles()?.isEmpty() == true }.forEach { it.delete() }
        }

        val downloadedCbzFiles =
          destDir
            .listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
            ?: emptyList()

        logger.info { "Found ${downloadedCbzFiles.size} CBZ files, processing metadata" }

        val retryChapters =
          if (mangaDexId != null) {
            try {
              fetchAllChaptersFromMangaDex(mangaDexId).also {
                if (it.isNotEmpty()) logger.info { "Fetched ${it.size} chapters from MangaDex API" }
              }
            } catch (_: Exception) {
              emptyList()
            }
          } else {
            emptyList()
          }
        val chapterMap = retryChapters.associateBy { it.chapterNumber }

        downloadedCbzFiles.forEach { cbzFile ->
          try {
            val chapterNum = extractChapterNumberFromFilename(cbzFile.name)
            val matched = chapterMap[chapterNum]
            if (matched != null) {
              val chapterInfo =
                ChapterInfo(
                  chapterNumber = matched.chapterNumber,
                  chapterTitle = matched.chapterTitle,
                  volume = matched.volume,
                  pages = matched.pages,
                  scanlationGroup = matched.scanlationGroup,
                  publishDate = matched.publishDate,
                  language = matched.language,
                )
              addComicInfoToCbzWithChapterInfo(cbzFile.toPath(), mangaInfo, chapterInfo, matched.chapterUrl)
            } else {
              val cbzNameNoExt = cbzFile.nameWithoutExtension
              val galleryDlMatch = galleryDlChapterMap[cbzNameNoExt]
              if (galleryDlMatch != null) {
                val chapterInfo =
                  ChapterInfo(
                    chapterNumber = galleryDlMatch.chapterNumber,
                    chapterTitle = galleryDlMatch.chapterTitle,
                    volume = galleryDlMatch.volume,
                    pages = galleryDlMatch.pages,
                    scanlationGroup = galleryDlMatch.scanlationGroup,
                    publishDate = galleryDlMatch.publishDate,
                    language = galleryDlMatch.language,
                  )
                addComicInfoToCbzWithChapterInfo(cbzFile.toPath(), mangaInfo, chapterInfo, galleryDlMatch.chapterUrl)
              } else {
                addComicInfoToCbz(cbzFile.toPath(), mangaInfo)
              }
            }
          } catch (e: Exception) {
            logger.warn(e) { "Failed to inject ComicInfo.xml into ${cbzFile.name}" }
          }
        }

        normalizeDoubleBracketFilenames(destDir)
      } else if (chapters.isEmpty()) {
        // All chapters already downloaded - skip
        logger.info { "All ${allChapters.size} chapters already downloaded, nothing to do" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "All chapters already downloaded, skipping: $url")
        configFile.delete()
      } else {
        logger.info { "Downloading $totalChapters chapters one by one" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Starting download of $totalChapters chapters: $url")

        val failuresFile = File(destDir, ".chapter-failures.json")
        val chapterFailures = loadChapterFailures(failuresFile)
        val mangaDexId = extractMangaDexId(url)

        chapters.forEachIndexed { index, chapter ->
          if (isCancelled()) {
            saveChapterFailures(failuresFile, chapterFailures)
            logger.info { "Download cancelled before chapter ${index + 1}/$totalChapters, stopping" }
            configFile.delete()
            return DownloadResult(
              success = true,
              filesDownloaded = filesDownloaded,
              downloadedFiles = emptyList(),
              totalChapters = totalChapters,
              errorMessage = null,
              mangaTitle = mangaInfo.title,
            )
          }

          val chapterNum = chapter.chapterNumber ?: "${index + 1}"
          val failCount = chapterFailures[chapter.chapterUrl] ?: 0
          if (failCount >= 3) {
            if (komgaSeriesId != null && !blacklistedChapterRepository.existsByChapterUrl(chapter.chapterUrl)) {
              blacklistedChapterRepository.insert(
                org.gotson.komga.domain.model.BlacklistedChapter(
                  id =
                    java.util.UUID
                      .randomUUID()
                      .toString(),
                  seriesId = komgaSeriesId,
                  chapterUrl = chapter.chapterUrl,
                  chapterNumber = chapter.chapterNumber,
                  chapterTitle = chapter.chapterTitle,
                ),
              )
              logger.info { "Auto-blacklisted chapter $chapterNum after $failCount failed attempts: ${chapter.chapterUrl}" }
            } else if (komgaSeriesId == null) {
              logger.warn { "Cannot blacklist chapter $chapterNum: series not yet in database" }
            }
            return@forEachIndexed
          }

          logger.info { "Downloading chapter $chapterNum (${index + 1}/$totalChapters): ${chapter.chapterUrl}" }

          val chapterCommand =
            getGalleryDlCommand().toMutableList().apply {
              add(chapter.chapterUrl)
              add("-d")
              add(destinationPath.toString())
              add("--config")
              add(configFile.absolutePath)
            }

          try {
            val chapterProcess =
              applyGalleryDlEnv(ProcessBuilder())
                .command(chapterCommand)
                .directory(File(System.getProperty("user.home")))
                .start()

            onProcessStarted(chapterProcess)

            val chapterOutput = StringBuilder()
            val chapterError = StringBuilder()

            Thread {
              BufferedReader(InputStreamReader(chapterProcess.inputStream)).use { reader ->
                reader.lines().forEach { line ->
                  chapterOutput.appendLine(line)
                  output.appendLine(line)
                  logger.debug { "gallery-dl [ch$chapterNum]: $line" }
                }
              }
            }.start()

            Thread {
              BufferedReader(InputStreamReader(chapterProcess.errorStream)).use { reader ->
                reader.lines().forEach { line ->
                  chapterError.appendLine(line)
                  errorOutput.appendLine(line)
                  logger.debug { "gallery-dl [ch$chapterNum] stderr: $line" }
                }
              }
            }.start()

            // Wait for this chapter to complete (max 10 minutes per chapter)
            val completed = chapterProcess.waitFor(10, TimeUnit.MINUTES)
            if (!completed) {
              chapterProcess.destroyForcibly()
              logger.warn { "Chapter $chapterNum download timed out" }
            } else if (chapterProcess.exitValue() == 0) {
              filesDownloaded++

              val chapterStr = chapter.chapterNumber ?: "${index + 1}"
              val paddedChapter = padChapterNumber(chapterStr)
              val cbzFiles =
                destDir
                  .listFiles()
                  ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
                  ?: emptyList()

              val recentCbzFiles =
                cbzFiles
                  .filter { System.currentTimeMillis() - it.lastModified() < 120_000 }
                  .sortedByDescending { it.lastModified() }

              val targetCbz =
                recentCbzFiles.find { file ->
                  val name = file.nameWithoutExtension.lowercase()
                  name.startsWith("c$paddedChapter ") || name == "c$paddedChapter" ||
                    name.startsWith("c$chapterStr ") || name == "c$chapterStr" ||
                    name.startsWith("ch. $paddedChapter ") || name.startsWith("ch. $paddedChapter-") || name == "ch. $paddedChapter"
                }
                  ?: cbzFiles.find { file ->
                    val name = file.nameWithoutExtension.lowercase()
                    name.startsWith("c$paddedChapter ") || name == "c$paddedChapter" ||
                      name.startsWith("c$chapterStr ") || name == "c$chapterStr" ||
                      name.startsWith("ch. $paddedChapter ") || name.startsWith("ch. $paddedChapter-") || name == "ch. $paddedChapter"
                  }

              if (targetCbz == null) {
                logger.warn { "Could not find CBZ file for chapter $chapterNum (expected c$paddedChapter or c$chapterStr)" }
              } else {
                try {
                  val chapterInfo =
                    ChapterInfo(
                      chapterNumber = chapter.chapterNumber,
                      chapterTitle = chapter.chapterTitle,
                      volume = chapter.volume,
                      pages = chapter.pages,
                      scanlationGroup = chapter.scanlationGroup,
                      publishDate = chapter.publishDate,
                      language = chapter.language,
                    )
                  addComicInfoToCbzWithChapterInfo(targetCbz.toPath(), mangaInfo, chapterInfo, chapter.chapterUrl)
                  logger.info { "Processed ${targetCbz.name}" }
                } catch (e: Exception) {
                  logger.warn(e) { "Failed to process CBZ ${targetCbz.name}" }
                }
              }

              val progressPercent = ((index + 1) * 100) / totalChapters
              onProgress(DownloadProgress(filesDownloaded, totalChapters, progressPercent, "Downloaded chapter $chapterNum"))
            } else {
              val exitCode = chapterProcess.exitValue()
              chapterFailures[chapter.chapterUrl] = failCount + 1
              logger.warn { "Chapter $chapterNum download failed with exit code $exitCode (attempt ${failCount + 1}/3)" }
            }
          } catch (e: Exception) {
            chapterFailures[chapter.chapterUrl] = failCount + 1
            logger.warn(e) { "Error downloading chapter $chapterNum" }
          }
        }

        saveChapterFailures(failuresFile, chapterFailures)
        normalizeDoubleBracketFilenames(destDir)
        configFile.delete()
      }

      val downloadedFiles =
        destDir
          .listFiles()
          ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
          ?.map { it.absolutePath }
          ?: emptyList()

      logger.info { "Found ${downloadedFiles.size} CBZ files in ${destDir.absolutePath}" }

      try {
        destDir
          .listFiles()
          ?.filter { it.isDirectory }
          ?.forEach { folder ->
            val deleted = folder.deleteRecursively()
            if (deleted) {
              logger.debug { "Deleted subdirectory: ${folder.name}" }
            } else {
              logger.warn { "Failed to delete subdirectory: ${folder.name}" }
            }
          }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to clean up chapter folders" }
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.WARN,
          "Failed to clean up chapter folders: ${e.message}",
        )
      }

      logger.info { "Download completed: ${downloadedFiles.size} files (manga: ${mangaInfo.title})" }
      logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Download completed successfully: ${downloadedFiles.size} files downloaded from $url")

      return DownloadResult(
        success = true,
        filesDownloaded = downloadedFiles.size,
        downloadedFiles = downloadedFiles,
        totalChapters = downloadedFiles.size,
        errorMessage = null,
        mangaTitle = mangaInfo.title,
      )
    } catch (e: GalleryDlException) {
      logger.error(e) { "Download failed: $url" }
      logToDatabase(org.gotson.komga.domain.model.LogLevel.ERROR, "Download failed for $url: ${e.message}", e.stackTraceToString())
      return DownloadResult(
        success = false,
        filesDownloaded = 0,
        downloadedFiles = emptyList(),
        totalChapters = 0,
        errorMessage = e.message ?: "Unknown error",
      )
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error downloading: $url" }
      logToDatabase(org.gotson.komga.domain.model.LogLevel.ERROR, "Unexpected error downloading from $url: ${e.message}", e.stackTraceToString())
      return DownloadResult(
        success = false,
        filesDownloaded = 0,
        downloadedFiles = emptyList(),
        totalChapters = 0,
        errorMessage = "Unexpected error: ${e.message}",
      )
    }
  }

  private fun deriveTitleFromUrl(url: String): String? =
    try {
      val uri = URI(url)
      val host = uri.host?.removePrefix("www.") ?: return null
      val siteName = host.substringBeforeLast(".")
      val pathSegments =
        uri.path
          ?.split("/")
          ?.filter { it.isNotBlank() }
          ?: emptyList()
      val lastSegment = pathSegments.lastOrNull() ?: return null
      val decoded =
        lastSegment
          .replace("-", " ")
          .replace("_", " ")
          .trim()
      "$siteName - $decoded"
    } catch (_: Exception) {
      null
    }

  private fun getGalleryDlPath(): String? {
    val pluginConfig =
      pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue }
    return pluginConfig["gallery_dl_path"]?.takeIf { it.isNotBlank() }
  }

  private fun applyGalleryDlEnv(processBuilder: ProcessBuilder): ProcessBuilder {
    val galleryDlPath = getGalleryDlPath()
    if (galleryDlPath != null) {
      val env = processBuilder.environment()
      val existing = env["PYTHONPATH"]
      env["PYTHONPATH"] =
        if (existing.isNullOrBlank()) galleryDlPath else "$galleryDlPath${File.pathSeparator}$existing"
      logger.debug { "Set PYTHONPATH=$galleryDlPath for gallery-dl" }
    }
    return processBuilder
  }

  private fun getGalleryDlCommand(): List<String> =
    try {
      val process =
        applyGalleryDlEnv(ProcessBuilder())
          .command("gallery-dl", "--version")
          .start()
      process.waitFor(2, TimeUnit.SECONDS)
      if (process.exitValue() == 0) {
        listOf("gallery-dl")
      } else {
        getPythonGalleryDlCommand()
      }
    } catch (e: Exception) {
      getPythonGalleryDlCommand()
    }

  private fun getPythonGalleryDlCommand(): List<String> {
    val pythonCmds = listOf("python3", "python")

    return pythonCmds
      .firstOrNull { python ->
        try {
          val process =
            applyGalleryDlEnv(ProcessBuilder())
              .command(python, "-m", "gallery_dl", "--version")
              .start()
          process.waitFor(2, TimeUnit.SECONDS)
          process.exitValue() == 0
        } catch (e: Exception) {
          false
        }
      }?.let { listOf(it, "-m", "gallery_dl") } ?: listOf("gallery-dl")
  }

  private fun getWebsiteConfigs(defaultLanguage: String): Map<String, Map<String, Any>> = getDefaultWebsiteConfigs(defaultLanguage)

  private fun getDefaultWebsiteConfigs(defaultLanguage: String): Map<String, Map<String, Any>> =
    mapOf(
      "mangadex" to
        mapOf(
          "lang" to defaultLanguage,
          "api" to "api",
          "data-saver" to false,
          "directory" to listOf("c{chapter:>03}{chapter_minor} [{group:J, }]"),
          "filename" to "{page:>03}.{extension}",
        ),
      "mangahere" to
        mapOf(
          "directory" to listOf("c{chapter:>03}{chapter_minor} [{group:J, }]"),
          "filename" to "{page:>03}.{extension}",
        ),
      "comick" to
        mapOf(
          "lang" to defaultLanguage,
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "batoto" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "mangasee" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "mangakakalot" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "manganato" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "webtoons" to
        mapOf(
          "directory" to listOf("e{episode:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "asurascans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "flamescans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "reaperscans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "mangaplus" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "imgur" to
        mapOf(
          "directory" to listOf("{album[title]}"),
          "filename" to "{num:>03}.{extension}",
        ),
      "nhentai" to
        mapOf(
          "directory" to listOf("{gallery_id}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "exhentai" to
        mapOf(
          "directory" to listOf("{gallery_id}"),
          "filename" to "{page:>03}.{extension}",
        ),
      "rawkuma" to
        mapOf(
          "directory" to listOf("{chapter_id}"),
          "filename" to "{page:>03}.{extension}",
        ),
    )

  private fun createTempConfigFile(): File {
    val tempFile = File.createTempFile("gallery-dl-", ".json")

    val pluginConfig = pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue }
    val mangadexUsername = pluginConfig["mangadex_username"]
    val mangadexPassword = pluginConfig["mangadex_password"]
    val defaultLanguage = pluginConfig["default_language"] ?: "en"

    val websiteConfigs = getWebsiteConfigs(defaultLanguage).toMutableMap()

    if (!mangadexUsername.isNullOrBlank() || !mangadexPassword.isNullOrBlank()) {
      val mangadexConfig = websiteConfigs["mangadex"]?.toMutableMap() ?: mutableMapOf()
      if (!mangadexUsername.isNullOrBlank()) {
        mangadexConfig["username"] = mangadexUsername
      }
      if (!mangadexPassword.isNullOrBlank()) {
        mangadexConfig["password"] = mangadexPassword
      }
      websiteConfigs["mangadex"] = mangadexConfig
    }

    val config =
      mutableMapOf<String, Any>(
        "extractor" to
          mutableMapOf<String, Any>(
            "base-directory" to "",
          ).apply {
            putAll(websiteConfigs)
          },
        "postprocessors" to
          listOf(
            mapOf(
              "name" to "zip",
              "extension" to "cbz",
              "compression" to "store",
              "keep-files" to false,
            ),
          ),
      )

    tempFile.writeText(objectMapper.writeValueAsString(config))
    logger.debug { "Created config file with ${websiteConfigs.size} website configs" }
    return tempFile
  }

  private fun createCoverConfigFile(): File {
    val tempFile = File.createTempFile("gallery-dl-cover-", ".json")

    val pluginConfig = pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue }
    val mangadexUsername = pluginConfig["mangadex_username"]
    val mangadexPassword = pluginConfig["mangadex_password"]

    val config =
      mutableMapOf<String, Any>(
        "extractor" to
          mutableMapOf<String, Any>(
            "base-directory" to "",
            "mangadex" to
              mutableMapOf<String, Any>("lang" to "en").apply {
                if (!mangadexUsername.isNullOrBlank()) {
                  this["username"] = mangadexUsername
                }
                if (!mangadexPassword.isNullOrBlank()) {
                  this["password"] = mangadexPassword
                }
              },
          ),
      )

    tempFile.writeText(objectMapper.writeValueAsString(config))
    return tempFile
  }

  private fun downloadCover(
    mangaUrl: String,
    destinationPath: Path,
  ) {
    try {
      val coverUrl = "${mangaUrl.trimEnd('/')}?tab=art"
      val configFile = createCoverConfigFile()

      val command =
        getGalleryDlCommand().toMutableList().apply {
          add(coverUrl)
          add("-d")
          add(destinationPath.toString())
          add("--config")
          add(configFile.absolutePath)
          add("--range")
          add("1")
        }

      logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Downloading cover from: $coverUrl")
      logger.info { "Starting cover download: ${command.joinToString(" ")}" }

      val process =
        applyGalleryDlEnv(ProcessBuilder())
          .command(command)
          .start()

      if (!process.waitFor(2, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        configFile.delete()
        throw java.util.concurrent.TimeoutException("Cover download timeout")
      }

      val exitCode = process.exitValue()
      configFile.delete()

      if (exitCode == 0) {
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Cover download completed")
        logger.info { "Cover download completed successfully" }
      } else {
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.WARN,
          "Cover download failed with exit code: $exitCode",
        )
        logger.warn { "Cover download failed with exit code: $exitCode" }
      }
    } catch (e: Exception) {
      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.WARN,
        "Cover download failed: ${e.message}",
      )
      logger.warn(e) { "Cover download failed for $mangaUrl" }
    }
  }

  private fun logToDatabase(
    level: org.gotson.komga.domain.model.LogLevel,
    message: String,
    exceptionTrace: String? = null,
  ) {
    try {
      val log =
        org.gotson.komga.domain.model.PluginLog(
          id =
            com.github.f4b6a3.tsid.TsidCreator
              .getTsid256()
              .toString(),
          pluginId = pluginId,
          logLevel = level,
          message = message,
          exceptionTrace = exceptionTrace,
        )
      pluginLogRepository.insert(log)
    } catch (e: Exception) {
      logger.error(e) { "Failed to write plugin log to database: $message" }
    }
  }

  private fun createSeriesJson(
    mangaInfo: MangaInfo,
    destinationPath: Path,
  ) {
    try {
      val alternateTitles =
        mangaInfo.alternativeTitlesWithLanguage.map { (title, lang) ->
          mapOf(
            "title" to title,
            "language" to lang,
          )
        }

      val metadata =
        mapOf(
          "metadata" to
            mutableMapOf<String, Any>(
              "type" to "comicSeries",
              "name" to mangaInfo.title,
              "alternate_titles" to alternateTitles,
            ).apply {
              this["publisher"] = mangaInfo.publisher
              mangaInfo.mangaDexId?.let { this["comicid"] = it }
              mangaInfo.coverFilename?.let { this["cover_filename"] = it }
              mangaInfo.author?.let { this["author"] = it }
              mangaInfo.description?.let { this["description"] = it }
              mangaInfo.year?.let { this["year"] = it }
              mangaInfo.status?.let { this["status"] = it }
              mangaInfo.publicationDemographic?.let { this["publication_demographic"] = it }
              if (mangaInfo.genres.isNotEmpty()) {
                this["genres"] = mangaInfo.genres
              }
            },
        )

      val seriesJsonFile = destinationPath.resolve("series.json").toFile()
      val newContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata)

      if (seriesJsonFile.exists()) {
        val existingContent = seriesJsonFile.readText()
        if (existingContent == newContent) {
          logger.debug { "series.json unchanged, skipping rewrite" }
          return
        }
      }

      logger.info { "Writing series.json to: ${seriesJsonFile.absolutePath}" }
      seriesJsonFile.writeText(newContent)

      if (!seriesJsonFile.exists()) {
        throw java.io.IOException("series.json file was not created")
      }

      val fileSize = seriesJsonFile.length()
      if (fileSize == 0L) {
        throw java.io.IOException("series.json file is empty")
      }

      val fileSizeKb = fileSize / 1024.0
      if (fileSize < 5120) {
        logger.warn { "series.json is only $fileSizeKb KB (expected >5 KB). May lack proper metadata." }
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.WARN,
          "series.json is only $fileSizeKb KB (expected >5 KB). May lack proper metadata.",
        )
      }

      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.INFO,
        "Created series.json with ${alternateTitles.size} alternative titles ($fileSizeKb KB)",
      )
      logger.info { "series.json created successfully: ${seriesJsonFile.absolutePath} ($fileSizeKb KB)" }
    } catch (e: Exception) {
      val errorMsg = "Failed to create series.json: ${e.message}"
      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.ERROR,
        errorMsg,
        e.stackTraceToString(),
      )
      logger.error(e) { errorMsg }
    }
  }

  private fun generateComicInfoXml(
    mangaInfo: MangaInfo,
    chapterInfo: ChapterInfo?,
    chapterUrl: String? = null,
  ): String {
    val seriesTitle = mangaInfo.title.escapeXml()
    val author = mangaInfo.author?.escapeXml() ?: ""
    val description = mangaInfo.description?.escapeXml() ?: ""
    val genres = mangaInfo.genres.joinToString(", ") { it.escapeXml() }

    val chapterTitle = chapterInfo?.chapterTitle?.escapeXml() ?: ""
    val chapterNumber = chapterInfo?.chapterNumber
    val volume = chapterInfo?.volume
    val scanlationGroup = chapterInfo?.scanlationGroup?.escapeXml() ?: mangaInfo.scanlationGroup?.escapeXml() ?: ""
    val pageCount = chapterInfo?.pages ?: 0
    val publishDate = chapterInfo?.publishDate
    val language = chapterInfo?.language ?: "en"

    val mangaType = if (language == "ja") "YesAndRightToLeft" else "Yes"

    return """<?xml version="1.0"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <Title>$chapterTitle</Title>
  <Series>$seriesTitle</Series>
  ${if (chapterNumber != null) "<Number>$chapterNumber</Number>" else ""}
  ${if (volume != null) "<Volume>$volume</Volume>" else ""}
  ${if (description.isNotBlank()) "<Summary>$description</Summary>" else ""}
  ${
      if (publishDate != null) {
        "<Year>${publishDate.substring(0, 4)}</Year>"
      } else if (mangaInfo.year != null) {
        "<Year>${mangaInfo.year}</Year>"
      } else {
        ""
      }
    }
  ${if (publishDate != null) "<Month>${publishDate.substring(5, 7)}</Month>" else ""}
  ${if (publishDate != null) "<Day>${publishDate.substring(8, 10)}</Day>" else ""}
  <Writer>$author</Writer>
  <Translator>$scanlationGroup</Translator>
  <Publisher>${mangaInfo.publisher.escapeXml()}</Publisher>
  ${if (genres.isNotBlank()) "<Genre>$genres</Genre>" else ""}
  <Web>${(chapterUrl ?: mangaInfo.sourceUrl ?: "").escapeXml()}</Web>
  ${if (pageCount > 0) "<PageCount>$pageCount</PageCount>" else ""}
  <LanguageISO>$language</LanguageISO>
  <Manga>$mangaType</Manga>
  ${if (mangaInfo.publicationDemographic != null) "<AgeRating>${mapDemographicToAgeRating(mangaInfo.publicationDemographic)}</AgeRating>" else ""}
</ComicInfo>"""
  }

  private fun mapDemographicToAgeRating(demographic: String): String =
    when (demographic.lowercase()) {
      "shounen" -> "Teen"
      "shoujo" -> "Everyone 10+"
      "seinen" -> "Mature 17+"
      "josei" -> "Mature 17+"
      else -> "Unknown"
    }

  private fun String.escapeXml() =
    this
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")

  private fun addComicInfoToCbz(
    cbzPath: Path,
    mangaInfo: MangaInfo,
  ) {
    try {
      val chapterId = extractChapterId(cbzPath)
      val chapterInfo =
        if (chapterId != null) {
          logger.info { "Fetching chapter metadata for ${cbzPath.fileName} (chapter ID: $chapterId)" }
          fetchChapterMetadata(chapterId)
        } else {
          logger.warn { "Could not extract chapter ID from ${cbzPath.fileName}, using series metadata only" }
          null
        }

      val chapterUrl = if (chapterId != null) "https://mangadex.org/chapter/$chapterId" else null
      val comicInfoXml = generateComicInfoXml(mangaInfo, chapterInfo, chapterUrl)
      val tempFile =
        Files
          .createTempFile("cbz_temp_", ".cbz")

      try {
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
        val allEntries = mutableListOf<Pair<ZipEntry, ByteArray>>()

        ZipInputStream(Files.newInputStream(cbzPath)).use { zipIn ->
          var entry = zipIn.nextEntry
          while (entry != null) {
            if (entry.name != "ComicInfo.xml") {
              allEntries.add(entry to zipIn.readBytes())
            }
            entry = zipIn.nextEntry
          }
        }

        val duplicatePages = mutableSetOf<String>()
        val imagesByPage =
          allEntries
            .filter { (e, _) ->
              val ext = e.name.substringAfterLast('.', "").lowercase()
              ext in imageExtensions
            }.groupBy { (e, _) ->
              e.name.substringBeforeLast('.').substringAfterLast('/')
            }

        for ((_, variants) in imagesByPage) {
          if (variants.size > 1) {
            val keep = variants.maxByOrNull { (_, data) -> data.size }
            variants.filter { it != keep }.forEach { (e, _) -> duplicatePages.add(e.name) }
          }
        }

        if (duplicatePages.isNotEmpty()) {
          logger.info { "Removing ${duplicatePages.size} duplicate images from ${cbzPath.fileName}" }
        }

        ZipOutputStream(Files.newOutputStream(tempFile)).use { zipOut ->
          zipOut.putNextEntry(ZipEntry("ComicInfo.xml"))
          zipOut.write(comicInfoXml.toByteArray(Charsets.UTF_8))
          zipOut.closeEntry()

          for ((entry, data) in allEntries) {
            if (entry.name !in duplicatePages) {
              zipOut.putNextEntry(ZipEntry(entry.name))
              zipOut.write(data)
              zipOut.closeEntry()
            }
          }
        }

        Files.move(tempFile, cbzPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      } finally {
        Files.deleteIfExists(tempFile)
      }

      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.INFO,
        "Injected ComicInfo.xml into ${cbzPath.fileName}" +
          if (chapterInfo != null) " (chapter ${chapterInfo.chapterNumber})" else "",
      )
      logger.info {
        "Added ComicInfo.xml to ${cbzPath.fileName}" +
          if (chapterInfo != null) " with chapter metadata (ch. ${chapterInfo.chapterNumber})" else ""
      }
    } catch (e: Exception) {
      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.WARN,
        "Failed to inject ComicInfo.xml into ${cbzPath.fileName}: ${e.message}",
      )
      logger.warn(e) { "Failed to add ComicInfo.xml to ${cbzPath.fileName}" }
    }
  }

  private fun addComicInfoToCbzWithChapterInfo(
    cbzPath: Path,
    mangaInfo: MangaInfo,
    chapterInfo: ChapterInfo?,
    chapterUrl: String? = null,
  ) {
    val maxRetries = 5
    val retryDelayMs = 1000L

    for (attempt in 1..maxRetries) {
      try {
        val comicInfoXml = generateComicInfoXml(mangaInfo, chapterInfo, chapterUrl)
        val tempFile = cbzPath.resolveSibling("${cbzPath.fileName}.tmp")

        try {
          val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
          val allEntries = mutableListOf<Pair<ZipEntry, ByteArray>>()

          ZipInputStream(Files.newInputStream(cbzPath)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
              if (entry.name != "ComicInfo.xml") {
                allEntries.add(entry to zipIn.readBytes())
              }
              entry = zipIn.nextEntry
            }
          }

          val duplicatePages = mutableSetOf<String>()
          val imagesByPage =
            allEntries
              .filter { (e, _) ->
                val ext = e.name.substringAfterLast('.', "").lowercase()
                ext in imageExtensions
              }.groupBy { (e, _) ->
                e.name.substringBeforeLast('.').substringAfterLast('/')
              }

          for ((_, variants) in imagesByPage) {
            if (variants.size > 1) {
              val keep = variants.maxByOrNull { (_, data) -> data.size }
              variants.filter { it != keep }.forEach { (e, _) -> duplicatePages.add(e.name) }
            }
          }

          if (duplicatePages.isNotEmpty()) {
            logger.info { "Removing ${duplicatePages.size} duplicate images from ${cbzPath.fileName}" }
          }

          ZipOutputStream(Files.newOutputStream(tempFile)).use { zipOut ->
            val writtenEntries = mutableSetOf<String>()

            zipOut.putNextEntry(ZipEntry("ComicInfo.xml"))
            zipOut.write(comicInfoXml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
            writtenEntries.add("ComicInfo.xml")

            for ((entry, data) in allEntries) {
              if (entry.name !in writtenEntries && entry.name !in duplicatePages) {
                writtenEntries.add(entry.name)
                zipOut.putNextEntry(ZipEntry(entry.name))
                zipOut.write(data)
                zipOut.closeEntry()
              }
            }
          }

          Files
            .move(tempFile, cbzPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

          logToDatabase(
            org.gotson.komga.domain.model.LogLevel.INFO,
            "Injected ComicInfo.xml into ${cbzPath.fileName}" +
              if (chapterInfo != null) " (chapter ${chapterInfo.chapterNumber})" else "",
          )
          logger.info {
            "Added ComicInfo.xml to ${cbzPath.fileName}" +
              if (chapterInfo != null) " with chapter metadata (ch. ${chapterInfo.chapterNumber})" else ""
          }
          return
        } finally {
          Files
            .deleteIfExists(tempFile)
        }
      } catch (e: java.nio.file.FileSystemException) {
        if (attempt < maxRetries) {
          logger.debug { "File locked, retrying in ${retryDelayMs}ms (attempt $attempt/$maxRetries): ${cbzPath.fileName}" }
          Thread.sleep(retryDelayMs * attempt)
        } else {
          logToDatabase(
            org.gotson.komga.domain.model.LogLevel.WARN,
            "Failed to inject ComicInfo.xml into ${cbzPath.fileName} after $maxRetries retries: ${e.message}",
          )
          logger.warn(e) { "Failed to add ComicInfo.xml to ${cbzPath.fileName} after $maxRetries retries" }
        }
      } catch (e: Exception) {
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.WARN,
          "Failed to inject ComicInfo.xml into ${cbzPath.fileName}: ${e.message}",
        )
        logger.warn(e) { "Failed to add ComicInfo.xml to ${cbzPath.fileName}" }
        return
      }
    }
  }

  private fun extractChapterUrlsFromCbzFiles(destDir: File): Set<String> {
    val urls = mutableSetOf<String>()
    val cbzFiles =
      destDir
        .listFiles()
        ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
        ?: return urls

    for (cbzFile in cbzFiles) {
      try {
        ZipInputStream(cbzFile.inputStream().buffered()).use { zipIn ->
          var entry = zipIn.nextEntry
          while (entry != null) {
            if (entry.name == "ComicInfo.xml") {
              val xml = zipIn.readBytes().toString(Charsets.UTF_8)
              val match = Regex("<Web>(.+?)</Web>").find(xml)
              if (match != null) {
                val url =
                  match.groupValues[1]
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                if (url.contains("mangadex.org/chapter/")) {
                  urls.add(url)
                }
              }
              break
            }
            entry = zipIn.nextEntry
          }
        }
      } catch (e: Exception) {
        logger.debug { "Failed to read ComicInfo.xml from ${cbzFile.name}: ${e.message}" }
      }
    }
    return urls
  }

  private fun updateExistingCbzChapterUrls(
    destDir: File,
    allChapters: List<ChapterDownloadInfo>,
    existingUrls: Set<String>,
    mangaInfo: MangaInfo,
  ) {
    val cbzFiles =
      destDir
        .listFiles()
        ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
        ?: return

    var updated = 0
    val alreadyUpdated = mutableSetOf<String>()
    val groupRegex = """\[([^\]]+)\]\s*$""".toRegex()

    for (cbzFile in cbzFiles) {
      if (cbzFile.absolutePath in alreadyUpdated) continue
      if (hasComicInfoXml(cbzFile)) continue

      val fileName = cbzFile.nameWithoutExtension
      val nameLower = fileName.lowercase()
      val chapterNum = extractChapterNumFromFilename(nameLower) ?: continue
      val fileGroup =
        groupRegex
          .find(fileName)
          ?.groupValues
          ?.get(1)
          ?.trim()

      val chapter =
        allChapters.find { ch ->
          val chNum = ch.chapterNumber ?: return@find false
          val padded = padChapterNumber(chNum)
          val plain =
            try {
              val n = chNum.toDouble()
              if (n == n.toLong().toDouble()) n.toLong().toString() else chNum
            } catch (_: NumberFormatException) {
              chNum
            }
          val numMatch = chapterNum == padded || chapterNum == plain
          if (!numMatch) return@find false
          if (fileGroup != null) {
            ch.scanlationGroup != null && fileGroup.equals(ch.scanlationGroup, ignoreCase = true)
          } else {
            true
          }
        } ?: continue

      try {
        val chapterInfo =
          ChapterInfo(
            chapterNumber = chapter.chapterNumber,
            chapterTitle = chapter.chapterTitle,
            volume = chapter.volume,
            pages = chapter.pages,
            scanlationGroup = chapter.scanlationGroup,
            publishDate = chapter.publishDate,
            language = chapter.language,
          )
        addComicInfoToCbzWithChapterInfo(cbzFile.toPath(), mangaInfo, chapterInfo, chapter.chapterUrl)
        alreadyUpdated.add(cbzFile.absolutePath)
        updated++
        logger.info { "Injected missing ComicInfo.xml into ${cbzFile.name}" }
      } catch (e: Exception) {
        logger.debug { "Failed to update ComicInfo.xml in ${cbzFile.name}: ${e.message}" }
      }
    }

    if (updated > 0) {
      logger.info { "Existing CBZ update: $updated ComicInfo injected" }
    }
  }

  private fun hasComicInfoXml(cbzFile: File): Boolean {
    try {
      ZipInputStream(cbzFile.inputStream().buffered()).use { zipIn ->
        var entry = zipIn.nextEntry
        while (entry != null) {
          if (entry.name == "ComicInfo.xml") return true
          entry = zipIn.nextEntry
        }
      }
    } catch (_: Exception) {
      return false
    }
    return false
  }

  private fun readSeriesJson(destinationPath: Path): Map<String, Any?>? {
    val seriesJsonFile = destinationPath.resolve("series.json").toFile()
    return if (seriesJsonFile.exists()) {
      try {
        objectMapper.readValue<Map<String, Any?>>(seriesJsonFile)
      } catch (e: Exception) {
        logger.warn(e) { "Failed to read series.json" }
        null
      }
    } else {
      null
    }
  }

  private fun parseGalleryDlJson(json: String): MangaInfo {
    var title: String? = null
    var englishTitle: String? = null
    var author: String? = null
    var scanlationGroup: String? = null
    val alternativeTitlesSet = mutableSetOf<String>()
    val alternativeTitlesWithLangMap = mutableMapOf<String, String>()
    var totalChapters = 0
    var category: String? = null

    var description: String? = null

    try {
      val entries = objectMapper.readValue(json, List::class.java) as List<*>

      entries.forEach { entry ->
        if (entry !is List<*> || entry.size < 2) return@forEach
        val messageType = entry[0] as? Int ?: return@forEach

        when (messageType) {
          2 -> {
            val metadata = entry[1] as? Map<*, *> ?: return@forEach
            extractMetadataFields(
              metadata,
              onTitle = { mangaTitle, lang ->
                if (lang == "en" && mangaTitle != null) englishTitle = mangaTitle
                if (title == null && mangaTitle != null) title = mangaTitle
              },
              onDescription = { if (description == null) description = it },
              onAltTitles = { alt, lang ->
                alternativeTitlesSet.add(alt)
                alternativeTitlesWithLangMap[alt] = lang
              },
              onAuthor = { if (author == null) author = it },
              onGroup = { if (scanlationGroup == null) scanlationGroup = it },
            )
            if (category == null) category = metadata["category"] as? String
            if (title == null) {
              title = metadata["title"] as? String
            }
          }
          3 -> {
            totalChapters++
            if (entry.size >= 3) {
              val metadata = entry[2] as? Map<*, *> ?: return@forEach
              extractMetadataFields(
                metadata,
                onTitle = { mangaTitle, lang ->
                  if (lang == "en" && mangaTitle != null) englishTitle = mangaTitle
                  if (title == null && mangaTitle != null) title = mangaTitle
                },
                onDescription = { if (description == null) description = it },
                onAltTitles = { alt, lang ->
                  alternativeTitlesSet.add(alt)
                  alternativeTitlesWithLangMap[alt] = lang
                },
                onAuthor = { if (author == null) author = it },
                onGroup = { if (scanlationGroup == null) scanlationGroup = it },
              )
              if (category == null) category = metadata["category"] as? String
              if (title == null) {
                title = metadata["title"] as? String
              }
            }
          }
          6 -> {
            totalChapters++
            val metadata = entry[2] as? Map<*, *> ?: return@forEach
            extractMetadataFields(
              metadata,
              onTitle = { mangaTitle, lang ->
                if (lang == "en" && mangaTitle != null) englishTitle = mangaTitle
                if (title == null && mangaTitle != null) title = mangaTitle
              },
              onDescription = { if (description == null) description = it },
              onAltTitles = { alt, lang ->
                alternativeTitlesSet.add(alt)
                alternativeTitlesWithLangMap[alt] = lang
              },
              onAuthor = { if (author == null) author = it },
              onGroup = { if (scanlationGroup == null) scanlationGroup = it },
            )
          }
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "Failed to parse gallery-dl JSON: ${json.take(500)}" }
    }

    val finalTitle = englishTitle ?: title ?: category ?: "Unknown"

    return MangaInfo(
      title = finalTitle,
      author = author,
      totalChapters = totalChapters,
      description = description,
      alternativeTitles = alternativeTitlesSet.toList(),
      alternativeTitlesWithLanguage = alternativeTitlesWithLangMap,
      scanlationGroup = scanlationGroup,
    )
  }

  private inline fun extractMetadataFields(
    metadata: Map<*, *>,
    onTitle: (String?, String?) -> Unit,
    onDescription: (String) -> Unit,
    onAltTitles: (String, String) -> Unit,
    onAuthor: (String) -> Unit,
    onGroup: (String) -> Unit,
  ) {
    val mangaTitle = metadata["manga"] as? String
    val lang = metadata["lang"] as? String
    onTitle(mangaTitle, lang)

    val desc = metadata["description"] as? String
    if (desc != null) onDescription(desc)

    val mangaAlt = metadata["manga_alt"] as? List<*>
    if (mangaAlt != null) {
      mangaAlt.forEach { alt ->
        if (alt is String) {
          onAltTitles(alt, detectLanguageFromTitle(alt))
        }
      }
    }

    if (mangaTitle != null && lang != null && lang != "en") {
      onAltTitles(mangaTitle, lang)
    }

    val authors = metadata["author"] as? List<*>
    val firstAuthor = authors?.firstOrNull() as? String
    if (firstAuthor != null) onAuthor(firstAuthor)
    if (firstAuthor == null) {
      val authorStr = metadata["author"] as? String
      if (authorStr != null) onAuthor(authorStr)
    }

    val groups = metadata["group"] as? List<*>
    val firstGroup = groups?.firstOrNull() as? String
    if (firstGroup != null) onGroup(firstGroup)
  }

  private fun detectLanguageFromTitle(title: String): String {
    if (title.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' || it in '\u4E00'..'\u9FAF' }) {
      return "ja"
    }

    if (title.any { it in '\uAC00'..'\uD7AF' }) {
      return "ko"
    }

    if (title.any { it in '\u4E00'..'\u9FAF' }) {
      return "zh"
    }

    return "unknown"
  }

  private fun parseGalleryDlProgress(
    line: String,
    currentFile: Int,
  ): DownloadProgress? {
    val progressRegex = """(\d+)%\s+[\d.]+\s*[KMG]?B\s+[\d.]+\s*[KMG]?B/s""".toRegex()
    val match = progressRegex.find(line) ?: return null

    val percent = match.groupValues[1].toIntOrNull() ?: return null

    return DownloadProgress(
      currentChapter = currentFile,
      totalChapters = 0,
      percent = percent,
      message = line,
    )
  }

  private fun padChapterNumber(chapterNumStr: String): String =
    try {
      val num = chapterNumStr.toDouble()
      if (num == num.toLong().toDouble()) {
        String.format("%03d", num.toLong())
      } else {
        val intPart = num.toLong()
        val decimalPart = chapterNumStr.substringAfter(".", "")
        String.format("%03d.%s", intPart, decimalPart)
      }
    } catch (_: NumberFormatException) {
      chapterNumStr
    }

  @Suppress("UNCHECKED_CAST")
  private fun loadChapterFailures(file: File): MutableMap<String, Int> =
    try {
      if (file.exists()) {
        val map = objectMapper.readValue(file, Map::class.java) as Map<String, Any>
        map.mapValues { (it.value as Number).toInt() }.toMutableMap()
      } else {
        mutableMapOf()
      }
    } catch (_: Exception) {
      mutableMapOf()
    }

  private fun saveChapterFailures(
    file: File,
    failures: Map<String, Int>,
  ) {
    try {
      if (failures.isEmpty()) {
        if (file.exists()) file.delete()
      } else {
        objectMapper.writeValue(file, failures)
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to save chapter failures" }
    }
  }

  private fun normalizeDoubleBracketFilenames(dir: File) {
    val cbzFiles =
      dir
        .listFiles()
        ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
        ?: return

    for (file in cbzFiles) {
      val name = file.nameWithoutExtension
      if (name.contains("[[") || name.contains("]]")) {
        val normalized =
          name
            .replace(Regex("""\[\['?"""), "[")
            .replace(Regex("""'?\]\]"""), "]")
        val newFile = File(dir, "$normalized.cbz")
        if (!newFile.exists() && normalized != name) {
          file.renameTo(newFile)
          logger.info { "Normalized filename: ${file.name} -> $normalized.cbz" }
        }
      }
    }
  }

  private fun extractChapterNumFromFilename(nameLower: String): String? {
    val cMatch = Regex("""^c(\d+(?:\.\d+)?)""").find(nameLower)
    if (cMatch != null) return cMatch.groupValues[1]
    val chMatch = Regex("""^ch\.\s*(\d+(?:\.\d+)?)""").find(nameLower)
    if (chMatch != null) return chMatch.groupValues[1]
    return null
  }
}

data class MangaInfo(
  val title: String,
  val author: String?,
  val totalChapters: Int,
  val description: String?,
  val alternativeTitles: List<String> = emptyList(),
  val alternativeTitlesWithLanguage: Map<String, String> = emptyMap(),
  val scanlationGroup: String? = null,
  val year: Int? = null,
  val status: String? = null,
  val publicationDemographic: String? = null,
  val genres: List<String> = emptyList(),
  val coverFilename: String? = null,
  val mangaDexId: String? = null,
  val sourceUrl: String? = null,
) {
  val publisher: String
    get() {
      if (mangaDexId != null) return "MangaDex"
      val url = sourceUrl ?: return "Unknown"
      return try {
        val host = URI(url).host?.removePrefix("www.") ?: return "Unknown"
        host
          .substringBeforeLast(".")
          .replaceFirstChar { it.uppercaseChar() }
      } catch (_: Exception) {
        "Unknown"
      }
    }
}

data class ChapterInfo(
  val chapterNumber: String?,
  val chapterTitle: String?,
  val volume: String?,
  val pages: Int,
  val scanlationGroup: String?,
  val publishDate: String?,
  val language: String?,
)

data class DownloadProgress(
  val currentChapter: Int,
  val totalChapters: Int,
  val percent: Int,
  val message: String,
)

data class DownloadResult(
  val success: Boolean,
  val filesDownloaded: Int,
  val downloadedFiles: List<String>,
  val totalChapters: Int,
  val errorMessage: String?,
  val mangaTitle: String? = null,
)

class GalleryDlException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
