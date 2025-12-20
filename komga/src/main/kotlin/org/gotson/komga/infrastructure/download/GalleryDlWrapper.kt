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
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class GalleryDlWrapper(
  private val pluginConfigRepository: org.gotson.komga.domain.persistence.PluginConfigRepository,
  private val pluginLogRepository: org.gotson.komga.domain.persistence.PluginLogRepository,
) {
  private val objectMapper: ObjectMapper = jacksonObjectMapper()
  private val pluginId = "gallery-dl-downloader"


  /**
   * Check if gallery-dl is installed and available
   */
  fun isInstalled(): Boolean =
    try {
      val command = getGalleryDlCommand() + "--version"
      val process =
        ProcessBuilder()
          .command(command)
          .start()

      process.waitFor(5, TimeUnit.SECONDS)
      process.exitValue() == 0
    } catch (e: Exception) {
      logger.debug { "gallery-dl not found: ${e.message}" }
      false
    }

  /**
   * Get English title from manga URL for use as folder name
   * Sanitizes the title by replacing filesystem-invalid characters with spaces
   */
  fun getEnglishTitleForFolderName(url: String): String {
    try {
      val mangaInfo = getChapterInfo(url) // This now throws with detailed errors
      val title = mangaInfo.title

      // Sanitize title for filesystem use
      // Replace invalid characters (\/:*?"<>|) with spaces
      val sanitized =
        title
          .replace(Regex("""[\\/:*?"<>|]"""), " ")
          .replace(Regex("""\s+"""), " ") // Collapse multiple spaces
          .trim()

      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.INFO,
        "Extracted English title for folder: '$sanitized' from URL: $url",
      )

      return sanitized
    } catch (e: Exception) {
      // Log the actual error instead of just "using fallback"
      val errorMsg = "Failed to extract English title from $url: ${e.message}"
      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.ERROR,
        errorMsg,
        e.stackTraceToString(),
      )
      logger.error(e) { errorMsg }

      // Still return "Unknown" but with proper error logging
      return "Unknown"
    }
  }

  /**
   * Fetch metadata from MangaDex API directly (more comprehensive than gallery-dl metadata)
   */
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

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() != 200) {
        logger.warn { "MangaDex API returned ${response.statusCode()} for manga $mangaId" }
        return null
      }

      val jsonResponse = objectMapper.readValue(response.body(), Map::class.java) as Map<*, *>
      val data = jsonResponse["data"] as? Map<*, *> ?: return null
      val attributes = data["attributes"] as? Map<*, *> ?: return null

      // Extract title (prefer English from altTitles over main title)
      val titleMap = attributes["title"] as? Map<*, *> ?: emptyMap<String, String>()
      val mainEnglishTitle = titleMap["en"] as? String

      // Extract alternative titles FIRST - they have better English translations
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
              // Capture the FIRST English alternative title (usually the best translation)
              if (altEnglishTitle == null && lang == "en") {
                altEnglishTitle = title
                logger.info { "Found English title in altTitles: $title" }
              }
            }
          }
        }
      }

      // PRIORITY: altTitles English > main title English > any title
      // altTitles usually have proper translations while main title.en is often just romaji
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

      // Extract description (prefer English)
      val descriptionMap = attributes["description"] as? Map<*, *> ?: emptyMap<String, String>()
      val description = descriptionMap["en"] as? String

      // Extract author/artist from relationships (now included via includes[]=author&includes[]=artist)
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

      // Combine author and artist if different
      val authorArtist = when {
        author != null && artist != null && author != artist -> "$author, $artist"
        author != null -> author
        artist != null -> artist
        else -> null
      }

      // Extract tags
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

      // Extract other metadata
      val year = attributes["year"] as? Int
      val status = attributes["status"] as? String
      val publicationDemographic = attributes["publicationDemographic"] as? String

      // Extract cover filename from relationships
      var coverFilename: String? = null
      relationships.forEach { rel ->
        if (rel is Map<*, *> && rel["type"] == "cover_art") {
          val coverAttributes = rel["attributes"] as? Map<*, *>
          coverFilename = coverAttributes?.get("fileName") as? String
        }
      }

      logger.info { "Successfully fetched MangaDex metadata for $mangaId: title='$englishTitle', author='$authorArtist', cover='$coverFilename'" }

      return MangaInfo(
        title = englishTitle ?: "Unknown",
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
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to fetch MangaDex API metadata for $mangaId" }
      return null
    }
  }

  /**
   * Extract MangaDex manga ID from URL
   * Supports: https://mangadex.org/title/UUID or https://mangadex.org/title/UUID/title-slug
   */
  private fun extractMangaDexId(url: String): String? {
    val regex = """mangadex\.org/title/([a-f0-9-]{36})""".toRegex()
    return regex.find(url)?.groupValues?.get(1)
  }

  /**
   * Download manga cover image from MangaDex uploads CDN
   * URL format: https://uploads.mangadex.org/covers/{manga-id}/{cover-filename}
   */
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

      // Save cover to destination path
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

  /**
   * Extract MangaDex chapter ID from CBZ filename or chapter URL
   * MangaDex chapter IDs are UUIDs in the filename like: "v01 c001 - Chapter Title [818a8b6f-7748-41a5-b3a1-01c3accf564c].cbz"
   */
  private fun extractChapterId(cbzPath: Path): String? {
    val filename = cbzPath.fileName.toString()
    // Look for UUID pattern in square brackets or parentheses
    val regex = """[\[\(]([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})[\]\)]""".toRegex()
    return regex.find(filename)?.groupValues?.get(1)
  }

  /**
   * Fetch chapter-specific metadata from MangaDex API
   */
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

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() != 200) {
        logger.warn { "MangaDex chapter API returned ${response.statusCode()} for chapter $chapterId" }
        return null
      }

      val jsonResponse = objectMapper.readValue(response.body(), Map::class.java) as Map<*, *>
      val data = jsonResponse["data"] as? Map<*, *> ?: return null
      val attributes = data["attributes"] as? Map<*, *> ?: return null

      // Extract chapter metadata
      val chapterNumber = attributes["chapter"] as? String
      val chapterTitle = attributes["title"] as? String
      val volume = attributes["volume"] as? String
      val pages = attributes["pages"] as? Int ?: 0
      val publishDate = attributes["publishAt"] as? String
      val language = attributes["translatedLanguage"] as? String

      // Extract scanlation group from relationships
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

  /**
   * Data class for chapter download info
   */
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

  /**
   * Fetch all chapters from MangaDex API for a manga
   * Returns list of chapter info sorted by chapter number
   */
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

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

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

  /**
   * Get chapter information from a manga URL
   * For MangaDex, tries API first, then falls back to gallery-dl metadata
   */
  fun getChapterInfo(url: String): MangaInfo {
    // Try MangaDex API first for better metadata
    val mangadexId = extractMangaDexId(url)
    if (mangadexId != null) {
      logger.info { "Detected MangaDex URL, fetching metadata from API for manga ID: $mangadexId" }
      val apiMetadata = fetchMangaDexMetadata(mangadexId)
      if (apiMetadata != null) {
        logger.info { "Using MangaDex API metadata: ${apiMetadata.title}" }
        return apiMetadata
      }
      logger.warn { "MangaDex API fetch failed, falling back to gallery-dl metadata" }
    }

    // Fallback to gallery-dl metadata extraction
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
        ProcessBuilder()
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

      val mangaInfo = parseGalleryDlJson(output.toString())

      // Verify we got a valid title
      if (mangaInfo.title.isBlank() || mangaInfo.title == "Unknown") {
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

  /**
   * Create config file for getChapterInfo (simpler than download config, no postprocessors)
   */
  private fun createInfoConfigFile(): File {
    val tempFile = File.createTempFile("gallery-dl-info-", ".json")

    // Read credentials from database
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

  /**
   * Download manga chapters to destination directory
   *
   * Sequence:
   * 1. Fetch metadata from API (or website fallback) - cached in RAM
   * 2. Determine English title from cached metadata
   * 3. Create proper destination folder using English title
   * 4. Create series.json from cached metadata
   * 5. Download chapters with gallery-dl
   * 6. Inject ComicInfo.xml into CBZ files from cached metadata
   */
  fun download(
    url: String,
    destinationPath: Path,
    libraryPath: Path? = null,
    onProgress: (DownloadProgress) -> Unit = {},
  ): DownloadResult {
    val output = StringBuilder()
    val errorOutput = StringBuilder()

    try {
      // STEP 1: Fetch metadata FIRST (API or website fallback) - cache in RAM
      logger.info { "Step 1: Fetching metadata for $url" }
      val mangaInfo = getChapterInfo(url)
      logger.info { "Metadata cached in RAM: title='${mangaInfo.title}'" }

      // STEP 2: English title already determined in mangaInfo.title
      logger.info { "Step 2: Using English title: ${mangaInfo.title}" }

      // STEP 3: Create destination folder using the proper title
      val destDir = destinationPath.toFile()
      if (!destDir.exists()) {
        logger.info { "Step 3: Creating folder: ${destDir.absolutePath}" }
        destDir.mkdirs()
      }

      // STEP 4: Create series.json BEFORE downloading
      logger.info { "Step 4: Creating series.json from cached metadata" }
      try {
        createSeriesJson(mangaInfo, destinationPath)
      } catch (e: Exception) {
        logger.warn(e) { "Failed to create series.json, continuing anyway" }
      }

      // Check for existing series.json
      val seriesJson = readSeriesJson(destinationPath)

      // Create temporary config file
      val configFile = createTempConfigFile()

      // NOTE: We do NOT use --download-archive because Komga manages duplicates via ChapterUrl tracking
      // This ensures gallery-dl downloads ALL chapters every time, and Komga filters duplicates

      val command =
        getGalleryDlCommand().toMutableList().apply {
          add(url)
          add("-d")
          add(destinationPath.toString())
          add("--config")
          add(configFile.absolutePath)
          add("-o")
          add("lang=en")
          // REMOVED: --download-archive - Komga handles duplicate tracking via ChapterUrl table
        }

      // STEP 5: Download cover image FIRST (before chapters)
      logger.info { "Step 5a: Downloading cover image" }
      try {
        val mangaDexId = extractMangaDexId(url)
        if (mangaDexId != null && mangaInfo.coverFilename != null) {
          downloadMangaCover(mangaDexId, mangaInfo.coverFilename!!, destinationPath)
        } else {
          logger.warn { "Cannot download cover: mangaDexId=$mangaDexId, coverFilename=${mangaInfo.coverFilename}" }
        }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to download cover image (non-fatal)" }
        // Non-fatal, continue
      }

      // STEP 5b: Fetch all chapters from MangaDex API
      val mangaDexId = extractMangaDexId(url)
      val chapters =
        if (mangaDexId != null) {
          logger.info { "Step 5b: Fetching chapter list from MangaDex API" }
          fetchAllChaptersFromMangaDex(mangaDexId)
        } else {
          logger.warn { "Not a MangaDex URL, falling back to single download" }
          emptyList()
        }

      var filesDownloaded = 0
      val totalChapters = chapters.size

      if (chapters.isNotEmpty()) {
        // CHAPTER-BY-CHAPTER DOWNLOAD
        logger.info { "Step 5c: Downloading $totalChapters chapters one by one" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Starting download of $totalChapters chapters: $url")

        chapters.forEachIndexed { index, chapter ->
          val chapterNum = chapter.chapterNumber ?: "${index + 1}"
          logger.info { "Downloading chapter $chapterNum (${index + 1}/$totalChapters): ${chapter.chapterUrl}" }

          // Create chapter-specific command
          val chapterCommand =
            getGalleryDlCommand().toMutableList().apply {
              add(chapter.chapterUrl)
              add("-d")
              add(destinationPath.toString())
              add("--config")
              add(configFile.absolutePath)
            }

          try {
            // Download this single chapter
            val chapterProcess =
              ProcessBuilder()
                .command(chapterCommand)
                .directory(File(System.getProperty("user.home")))
                .start()

            // Read output in background threads
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

              // Find the CBZ file that was just created for this chapter
              val cbzFiles =
                destDir
                  .listFiles()
                  ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
                  ?.sortedByDescending { it.lastModified() }
                  ?: emptyList()

              // The most recently modified CBZ is likely the one just downloaded
              val latestCbz = cbzFiles.firstOrNull()
              if (latestCbz != null) {
                logger.info { "Injecting ComicInfo.xml into ${latestCbz.name}" }
                try {
                  // Create ChapterInfo from our pre-fetched data
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
                  addComicInfoToCbzWithChapterInfo(latestCbz.toPath(), mangaInfo, chapterInfo)
                  logger.info { "ComicInfo.xml added to ${latestCbz.name}" }
                } catch (e: Exception) {
                  logger.warn(e) { "Failed to inject ComicInfo.xml into ${latestCbz.name}" }
                }
              }

              // Report progress
              val progressPercent = ((index + 1) * 100) / totalChapters
              onProgress(DownloadProgress(filesDownloaded, totalChapters, progressPercent, "Downloaded chapter $chapterNum"))
            } else {
              logger.warn { "Chapter $chapterNum download failed with exit code ${chapterProcess.exitValue()}" }
            }
          } catch (e: Exception) {
            logger.warn(e) { "Error downloading chapter $chapterNum" }
          }
        }

        configFile.delete()
      } else {
        // FALLBACK: Single download for non-MangaDex URLs or if chapter fetch failed
        logger.info { "Step 5b: Starting single download: ${command.joinToString(" ")}" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Starting download: $url")

        val process =
          ProcessBuilder()
            .command(command)
            .directory(File(System.getProperty("user.home")))
            .start()

        var lastProgress = 0

        // Read stdout in real-time
        Thread {
          BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lines().forEach { line ->
              output.appendLine(line)
              logger.debug { "gallery-dl: $line" }

              if (line.contains("✔") || line.contains("*")) {
                filesDownloaded++
              }
            }
          }
        }.start()

        // Read stderr for progress
        Thread {
          BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            reader.lines().forEach { line ->
              errorOutput.appendLine(line)
              logger.debug { "gallery-dl stderr: $line" }

              val progress = parseGalleryDlProgress(line, filesDownloaded)
              if (progress != null && progress.percent > lastProgress) {
                lastProgress = progress.percent
                onProgress(progress)
              }
            }
          }
        }.start()

        // Wait for completion (max 2 hours)
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

        // Inject ComicInfo.xml into all CBZ files after download
        val downloadedCbzFiles =
          destDir
            .listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
            ?: emptyList()

        logger.info { "Found ${downloadedCbzFiles.size} CBZ files, injecting ComicInfo.xml" }
        downloadedCbzFiles.forEach { cbzFile ->
          try {
            addComicInfoToCbz(cbzFile.toPath(), mangaInfo)
          } catch (e: Exception) {
            logger.warn(e) { "Failed to inject ComicInfo.xml into ${cbzFile.name}" }
          }
        }
      }

      // Find all downloaded CBZ files
      val downloadedFiles =
        destDir
          .listFiles()
          ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
          ?.map { it.absolutePath }
          ?: emptyList()

      logger.info { "Found ${downloadedFiles.size} CBZ files in ${destDir.absolutePath}" }

      // Clean up empty subdirectories and chapter folders
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
        // Non-fatal: just log the warning and continue
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

  private fun getGalleryDlCommand(): List<String> {
    // First try direct gallery-dl command
    return try {
      val process = ProcessBuilder().command("gallery-dl", "--version").start()
      process.waitFor(2, TimeUnit.SECONDS)
      if (process.exitValue() == 0) {
        listOf("gallery-dl")
      } else {
        // Fall back to python module
        getPythonGalleryDlCommand()
      }
    } catch (e: Exception) {
      // Fall back to python module
      getPythonGalleryDlCommand()
    }
  }

  private fun getPythonGalleryDlCommand(): List<String> {
    // Try python3 first, then python
    val pythonCmds = listOf("python3", "python")

    return pythonCmds
      .firstOrNull { python ->
        try {
          val process = ProcessBuilder().command(python, "-m", "gallery_dl", "--version").start()
          process.waitFor(2, TimeUnit.SECONDS)
          process.exitValue() == 0
        } catch (e: Exception) {
          false
        }
      }?.let { listOf(it, "-m", "gallery_dl") } ?: listOf("gallery-dl")
  }

  /**
   * Get website configs - uses internal defaults (built into JAR)
   * Like gallery-dl extractors, these configs are internal and "just work"
   */
  private fun getWebsiteConfigs(defaultLanguage: String): Map<String, Map<String, Any>> =
    getDefaultWebsiteConfigs(defaultLanguage)

  /**
   * Built-in default website configurations
   * IMPORTANT: directory must be a FLAT list with only chapter folder - NO category/manga folders!
   * gallery-dl -d destination already sets the manga folder, so we only need chapter subfolders
   */
  private fun getDefaultWebsiteConfigs(defaultLanguage: String): Map<String, Map<String, Any>> =
    mapOf(
      // === MANGADEX ===
      "mangadex" to
        mapOf(
          "lang" to defaultLanguage,
          "api" to "api",
          "directory" to listOf("c{chapter:>03}{chapter_minor}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === MANGAHERE ===
      "mangahere" to
        mapOf(
          "directory" to listOf("c{chapter:>03}{chapter_minor}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === COMICK (comick.io / comick.cc) ===
      "comick" to
        mapOf(
          "lang" to defaultLanguage,
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === BATOTO ===
      "batoto" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === MANGASEE / MANGALIFE ===
      "mangasee" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === MANGAKAKALOT / MANGANATO ===
      "mangakakalot" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === MANGANATO ===
      "manganato" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === WEBTOONS ===
      "webtoons" to
        mapOf(
          "directory" to listOf("e{episode:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === ASURA SCANS ===
      "asurascans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === FLAME SCANS ===
      "flamescans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === REAPER SCANS ===
      "reaperscans" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === MANGA PLUS ===
      "mangaplus" to
        mapOf(
          "directory" to listOf("c{chapter:>03}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === IMGUR (for manga hosted on imgur) ===
      "imgur" to
        mapOf(
          "directory" to listOf("{album[title]}"),
          "filename" to "{num:>03}.{extension}",
        ),
      // === NHENTAI ===
      "nhentai" to
        mapOf(
          "directory" to listOf("{gallery_id}"),
          "filename" to "{page:>03}.{extension}",
        ),
      // === E-HENTAI / EX-HENTAI ===
      "exhentai" to
        mapOf(
          "directory" to listOf("{gallery_id}"),
          "filename" to "{page:>03}.{extension}",
        ),
    )

  private fun createTempConfigFile(): File {
    val tempFile = File.createTempFile("gallery-dl-", ".json")

    // Read credentials from database
    val pluginConfig = pluginConfigRepository.findByPluginId(pluginId).associate { it.configKey to it.configValue }
    val mangadexUsername = pluginConfig["mangadex_username"]
    val mangadexPassword = pluginConfig["mangadex_password"]
    val defaultLanguage = pluginConfig["default_language"] ?: "en"

    // Get website configs
    val websiteConfigs = getWebsiteConfigs(defaultLanguage).toMutableMap()

    // Add MangaDex credentials if configured
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
            // CRITICAL: base-directory empty means use -d destination directly
            "base-directory" to "",
            // CRITICAL: Global directory template - only chapter folder, NO category/manga!
            // This applies to ALL sites as fallback. Site-specific configs can override.
            // Using just chapter folder ensures files go directly into destination/chapter/
            "directory" to listOf("c{chapter:>03}"),
            // Fallback filename pattern
            "filename" to "{page:>03}.{extension}",
          ).apply {
            // Add all website configs (these override the global settings)
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

    // Read credentials from database
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
        // No postprocessors - just download the cover images as-is
      )

    tempFile.writeText(objectMapper.writeValueAsString(config))
    return tempFile
  }

  /**
   * Download manga cover image (first/latest only)
   * This is non-fatal - errors are logged but don't stop the download
   */
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
          add("1") // Download only first/latest cover
        }

      logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Downloading cover from: $coverUrl")
      logger.info { "Starting cover download: ${command.joinToString(" ")}" }

      val process =
        ProcessBuilder()
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
      // Non-fatal: log warning and continue
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

  /**
   * Create series.json in Komga's MylarMetadata format
   * Format: {"metadata": {"type": "comicSeries", "name": "...", "alternate_titles": [{"title": "...", "language": "en"}]}}
   */
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
              // Add all optional fields if available
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

      logger.info { "Writing series.json to: ${seriesJsonFile.absolutePath}" }

      seriesJsonFile.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata))

      // Verify file was actually created
      if (!seriesJsonFile.exists()) {
        throw java.io.IOException("series.json file was not created")
      }

      val fileSize = seriesJsonFile.length()
      if (fileSize == 0L) {
        throw java.io.IOException("series.json file is empty")
      }

      // User requirement: file should be >5 KB for proper metadata
      val fileSizeKb = fileSize / 1024.0
      if (fileSize < 5120) { // 5 KB = 5120 bytes
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
      // Non-fatal but log with full detail
      val errorMsg = "Failed to create series.json: ${e.message}"
      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.ERROR,
        errorMsg,
        e.stackTraceToString(),
      )
      logger.error(e) { errorMsg }
    }
  }

  /**
   * Generate ComicInfo.xml content from manga and chapter metadata
   */
  private fun generateComicInfoXml(
    mangaInfo: MangaInfo,
    chapterInfo: ChapterInfo?,
  ): String {
    val seriesTitle = mangaInfo.title.escapeXml()
    val author = mangaInfo.author?.escapeXml() ?: ""
    val description = mangaInfo.description?.escapeXml() ?: ""
    val genres = mangaInfo.genres.joinToString(", ") { it.escapeXml() }

    // Use chapter-specific data if available
    val chapterTitle = chapterInfo?.chapterTitle?.escapeXml() ?: ""
    val chapterNumber = chapterInfo?.chapterNumber
    val volume = chapterInfo?.volume
    val scanlationGroup = chapterInfo?.scanlationGroup?.escapeXml() ?: mangaInfo.scanlationGroup?.escapeXml() ?: ""
    val pageCount = chapterInfo?.pages ?: 0
    val publishDate = chapterInfo?.publishDate
    val language = chapterInfo?.language ?: "en"

    // Build manga type (Yes for manga, YesAndRightToLeft for Japanese manga)
    val mangaType = if (language == "ja") "YesAndRightToLeft" else "Yes"

    return """<?xml version="1.0"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <Title>$chapterTitle</Title>
  <Series>$seriesTitle</Series>
  ${if (chapterNumber != null) "<Number>$chapterNumber</Number>" else ""}
  ${if (volume != null) "<Volume>$volume</Volume>" else ""}
  ${if (description.isNotBlank()) "<Summary>$description</Summary>" else ""}
  ${
      if (mangaInfo.year != null) {
        "<Year>${mangaInfo.year}</Year>"
      } else if (publishDate != null) {
        "<Year>${publishDate.substring(0, 4)}</Year>"
      } else {
        ""
      }
    }
  ${if (publishDate != null) "<Month>${publishDate.substring(5, 7)}</Month>" else ""}
  ${if (publishDate != null) "<Day>${publishDate.substring(8, 10)}</Day>" else ""}
  <Writer>$author</Writer>
  <Translator>$scanlationGroup</Translator>
  <Publisher>MangaDex</Publisher>
  ${if (genres.isNotBlank()) "<Genre>$genres</Genre>" else ""}
  <Web>https://mangadex.org/</Web>
  ${if (pageCount > 0) "<PageCount>$pageCount</PageCount>" else ""}
  <LanguageISO>$language</LanguageISO>
  <Manga>$mangaType</Manga>
  ${if (mangaInfo.publicationDemographic != null) "<AgeRating>${mapDemographicToAgeRating(mangaInfo.publicationDemographic)}</AgeRating>" else ""}
</ComicInfo>"""
  }

  /**
   * Map MangaDex publication demographic to ComicInfo AgeRating
   */
  private fun mapDemographicToAgeRating(demographic: String): String =
    when (demographic.lowercase()) {
      "shounen" -> "Teen"
      "shoujo" -> "Everyone 10+"
      "seinen" -> "Mature 17+"
      "josei" -> "Mature 17+"
      else -> "Unknown"
    }

  /**
   * Escape special XML characters
   */
  private fun String.escapeXml() =
    this
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")

  /**
   * Inject ComicInfo.xml into CBZ file after creation
   * Fetches chapter-specific metadata from MangaDex API
   */
  private fun addComicInfoToCbz(
    cbzPath: Path,
    mangaInfo: MangaInfo,
  ) {
    try {
      // Extract chapter ID from CBZ filename
      val chapterId = extractChapterId(cbzPath)

      // Fetch chapter-specific metadata from API
      val chapterInfo =
        if (chapterId != null) {
          logger.info { "Fetching chapter metadata for ${cbzPath.fileName} (chapter ID: $chapterId)" }
          fetchChapterMetadata(chapterId)
        } else {
          logger.warn { "Could not extract chapter ID from ${cbzPath.fileName}, using series metadata only" }
          null
        }

      // Generate ComicInfo.xml with manga + chapter metadata
      val comicInfoXml = generateComicInfoXml(mangaInfo, chapterInfo)

      // Create a temp file for the new CBZ
      val tempFile = java.nio.file.Files.createTempFile("cbz_temp_", ".cbz")

      try {
        // Read existing CBZ and write to temp with ComicInfo.xml added
        java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(cbzPath)).use { zipIn ->
          java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(tempFile)).use { zipOut ->
            // First add ComicInfo.xml
            val comicInfoEntry = java.util.zip.ZipEntry("ComicInfo.xml")
            zipOut.putNextEntry(comicInfoEntry)
            zipOut.write(comicInfoXml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // Copy all existing entries
            var entry = zipIn.nextEntry
            while (entry != null) {
              if (entry.name != "ComicInfo.xml") {
                // Skip existing ComicInfo.xml if present
                zipOut.putNextEntry(java.util.zip.ZipEntry(entry.name))
                zipIn.copyTo(zipOut)
                zipOut.closeEntry()
              }
              entry = zipIn.nextEntry
            }
          }
        }

        // Replace original with temp
        java.nio.file.Files.move(tempFile, cbzPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      } finally {
        // Clean up temp file if it still exists
        java.nio.file.Files.deleteIfExists(tempFile)
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

  /**
   * Inject ComicInfo.xml into CBZ file with pre-fetched chapter metadata
   * Used for chapter-by-chapter download where we already have the metadata
   */
  private fun addComicInfoToCbzWithChapterInfo(
    cbzPath: Path,
    mangaInfo: MangaInfo,
    chapterInfo: ChapterInfo?,
  ) {
    try {
      // Generate ComicInfo.xml with manga + chapter metadata
      val comicInfoXml = generateComicInfoXml(mangaInfo, chapterInfo)

      // Create a temp file for the new CBZ
      val tempFile = java.nio.file.Files.createTempFile("cbz_temp_", ".cbz")

      try {
        // Read existing CBZ and write to temp with ComicInfo.xml added
        java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(cbzPath)).use { zipIn ->
          java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(tempFile)).use { zipOut ->
            // First add ComicInfo.xml
            val comicInfoEntry = java.util.zip.ZipEntry("ComicInfo.xml")
            zipOut.putNextEntry(comicInfoEntry)
            zipOut.write(comicInfoXml.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // Copy all existing entries
            var entry = zipIn.nextEntry
            while (entry != null) {
              if (entry.name != "ComicInfo.xml") {
                // Skip existing ComicInfo.xml if present
                zipOut.putNextEntry(java.util.zip.ZipEntry(entry.name))
                zipIn.copyTo(zipOut)
                zipOut.closeEntry()
              }
              entry = zipIn.nextEntry
            }
          }
        }

        // Replace original with temp
        java.nio.file.Files.move(tempFile, cbzPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      } finally {
        // Clean up temp file if it still exists
        java.nio.file.Files.deleteIfExists(tempFile)
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
    // Parse JSON array of [type, url, metadata] tuples
    // Example: [[6, "https://mangadex.org/chapter/...", {"manga": "Title", "chapter": 1, ...}], ...]

    var title: String? = null
    var englishTitle: String? = null
    var author: String? = null
    var scanlationGroup: String? = null
    val alternativeTitlesSet = mutableSetOf<String>()
    val alternativeTitlesWithLangMap = mutableMapOf<String, String>()
    var totalChapters = 0

    var description: String? = null

    try {
      // Parse as proper JSON using Jackson
      val entries = objectMapper.readValue(json, List::class.java) as List<*>

      entries.forEach { entry ->
        if (entry is List<*> && entry.size >= 3 && entry[0] == 6) {
          totalChapters++

          val metadata = entry[2] as? Map<*, *> ?: return@forEach

          // Extract manga title
          val mangaTitle = metadata["manga"] as? String
          val lang = metadata["lang"] as? String

          // Prefer English title
          if (lang == "en" && mangaTitle != null) {
            englishTitle = mangaTitle
          }

          // Store first title as fallback
          if (title == null && mangaTitle != null) {
            title = mangaTitle
          }

          // Extract description (from first chapter)
          if (description == null) {
            description = metadata["description"] as? String
          }

          // Extract alternative titles
          val mangaAlt = metadata["manga_alt"] as? List<*>
          if (mangaAlt != null) {
            mangaAlt.forEach { alt ->
              if (alt is String) {
                alternativeTitlesSet.add(alt)

                // Try to detect language from the title text
                val detectedLang = detectLanguageFromTitle(alt)
                alternativeTitlesWithLangMap[alt] = detectedLang
              }
            }
          }

          // Also add the main manga title if it's not English (since English is the primary)
          if (mangaTitle != null && lang != null && lang != "en") {
            alternativeTitlesWithLangMap[mangaTitle] = lang
          }

          // Extract author (first one from array)
          if (author == null) {
            val authors = metadata["author"] as? List<*>
            author = authors?.firstOrNull() as? String
          }

          // Extract scanlation group (first one from array)
          if (scanlationGroup == null) {
            val groups = metadata["group"] as? List<*>
            scanlationGroup = groups?.firstOrNull() as? String
          }
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "Failed to parse gallery-dl JSON: ${json.take(500)}" }
    }

    val finalTitle = englishTitle ?: title ?: "Unknown"

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

  /**
   * Detect language from title text using simple heuristics
   * Returns language code (ja, ko, zh, etc.) or "unknown"
   */
  private fun detectLanguageFromTitle(title: String): String {
    // Japanese: Hiragana (3040-309F), Katakana (30A0-30FF), Kanji (4E00-9FAF)
    if (title.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' || it in '\u4E00'..'\u9FAF' }) {
      return "ja"
    }

    // Korean: Hangul (AC00-D7AF)
    if (title.any { it in '\uAC00'..'\uD7AF' }) {
      return "ko"
    }

    // Chinese: CJK characters (we'll assume Chinese if no Japanese kana detected)
    if (title.any { it in '\u4E00'..'\u9FAF' }) {
      return "zh"
    }

    // Default to unknown for titles we can't detect
    return "unknown"
  }

  private fun parseGalleryDlProgress(
    line: String,
    currentFile: Int,
  ): DownloadProgress? {
    // Parse lines like "50% 2.3 MB 1.5 MB/s"
    val progressRegex = """(\d+)%\s+[\d.]+\s*[KMG]?B\s+[\d.]+\s*[KMG]?B/s""".toRegex()
    val match = progressRegex.find(line) ?: return null

    val percent = match.groupValues[1].toIntOrNull() ?: return null

    return DownloadProgress(
      currentChapter = currentFile,
      totalChapters = 0, // Unknown until completion
      percent = percent,
      message = line,
    )
  }
}

data class MangaInfo(
  val title: String,
  val author: String?,
  val totalChapters: Int,
  val description: String?,
  val alternativeTitles: List<String> = emptyList(),
  val alternativeTitlesWithLanguage: Map<String, String> = emptyMap(), // Map of title -> language code (e.g., "タイトル" -> "ja")
  val scanlationGroup: String? = null,
  val year: Int? = null,
  val status: String? = null,
  val publicationDemographic: String? = null,
  val genres: List<String> = emptyList(),
  val coverFilename: String? = null, // Cover image filename from MangaDex API
)

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
