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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

@Service
class GalleryDlWrapper(
  private val pluginConfigRepository: org.gotson.komga.domain.persistence.PluginConfigRepository,
  private val pluginLogRepository: org.gotson.komga.domain.persistence.PluginLogRepository,
  private val blacklistedChapterRepository: org.gotson.komga.domain.persistence.BlacklistedChapterRepository,
  private val chapterUrlRepository: org.gotson.komga.domain.persistence.ChapterUrlRepository,
  private val mangaDexApiClient: MangaDexApiClient,
  private val comicInfoGenerator: ComicInfoGenerator,
  private val galleryDlProcess: GalleryDlProcess,
  private val chapterMatcher: ChapterMatcher,
) {
  private val objectMapper: ObjectMapper = jacksonObjectMapper()
  private val pluginId = "gallery-dl-downloader"

  @Volatile
  private var pluginConfigCache: Pair<Map<String, String?>, Long>? = null
  private val configCacheTtlMs = 60_000L

  companion object {
    private const val MAX_OUTPUT_SIZE = 512 * 1024

    // JSON output (`gallery-dl -j`) must stay a complete, valid document, so it is
    // never front-dropped like progress/error streams. Bounded only to avoid OOM on
    // a runaway feed; a manga with thousands of chapters is well under this.
    private const val MAX_JSON_OUTPUT_SIZE = 32 * 1024 * 1024
    private val progressRegex = """(\d+)%\s+[\d.]+\s*[KMG]?B\s+[\d.]+\s*[KMG]?B/s""".toRegex()

    fun extractMangaDexId(url: String): String? = MangaDexApiClient.extractMangaDexId(url)
  }

  private fun getPluginConfig(): Map<String, String?> {
    val cached = pluginConfigCache
    if (cached != null && System.currentTimeMillis() - cached.second < configCacheTtlMs) {
      return cached.first
    }
    val config =
      pluginConfigRepository
        .findByPluginId(pluginId)
        .associate { it.configKey to it.configValue }
    pluginConfigCache = config to System.currentTimeMillis()
    return config
  }

  private fun getDefaultLanguage(): String =
    try {
      getPluginConfig()["default_language"] ?: "en"
    } catch (e: Exception) {
      logger.debug(e) { "Failed to load default language" }
      "en"
    }

  private fun getGalleryDlPath(): String? = getPluginConfig()["gallery_dl_path"]?.takeIf { it.isNotBlank() }

  private fun deleteQuietly(file: File) {
    if (!file.delete()) {
      logger.debug { "Failed to delete: ${file.absolutePath}" }
    }
  }

  private fun appendBounded(
    sb: StringBuilder,
    line: String,
  ) {
    if (sb.length > MAX_OUTPUT_SIZE) {
      val dropIndex = sb.indexOf("\n", sb.length / 2)
      if (dropIndex > 0) sb.delete(0, dropIndex + 1)
    }
    sb.appendLine(line)
  }

  private fun appendJson(
    sb: StringBuilder,
    line: String,
  ) {
    if (sb.length < MAX_JSON_OUTPUT_SIZE) sb.appendLine(line)
  }

  fun getChaptersForManga(
    mangaId: String,
    language: String? = null,
  ): List<ChapterDownloadInfo> {
    val lang = language ?: getDefaultLanguage()
    return mangaDexApiClient.getChaptersForManga(mangaId, lang)
  }

  fun getMangaMetadata(mangaId: String): MangaInfo? = mangaDexApiClient.getMangaMetadata(mangaId)

  fun isInstalled(): Boolean = galleryDlProcess.isInstalled(getGalleryDlPath())

  fun getChapterInfo(url: String): MangaInfo {
    val mangadexId = extractMangaDexId(url)
    if (mangadexId != null) {
      logger.debug { "Detected MangaDex URL, fetching metadata from API for manga ID: $mangadexId" }
      val apiMetadata = mangaDexApiClient.fetchMangaDexMetadata(mangadexId)
      if (apiMetadata != null) {
        logger.debug { "Using MangaDex API metadata: ${apiMetadata.title}" }
        return apiMetadata.copy(sourceUrl = url)
      }
      logger.warn { "MangaDex API fetch failed, falling back to gallery-dl metadata" }
    }

    val output = StringBuilder()
    val errorOutput = StringBuilder()
    val pluginConfig = getPluginConfig()
    val configFile =
      galleryDlProcess.createInfoConfigFile(
        pluginConfig["mangadex_username"],
        pluginConfig["mangadex_password"],
        pluginConfig["default_language"] ?: "en",
        pluginConfig["flaresolverr_url"],
      )

    try {
      val command =
        galleryDlProcess.getCommand(getGalleryDlPath()).toMutableList().apply {
          add(url)
          add("-j")
          add("--simulate")
          add("--config")
          add(configFile.absolutePath)
        }

      logger.debug { "Executing getChapterInfo: ${command.joinToString(" ")}" }

      val process =
        galleryDlProcess
          .applyEnv(ProcessBuilder(), getGalleryDlPath())
          .command(command)
          .start()

      BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        reader.lines().forEach { line ->
          appendJson(output, line)
          logger.debug { "gallery-dl info: $line" }
        }
      }

      BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
        reader.lines().forEach { line ->
          appendBounded(errorOutput, line)
          logger.debug { "gallery-dl error: $line" }
        }
      }

      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        deleteQuietly(configFile)
        throw GalleryDlException("Timeout getting chapter info for $url")
      }

      val exitValue = process.exitValue()
      deleteQuietly(configFile)

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
          logger.debug { "No title in metadata, using URL-derived title: $fallback" }
          return mangaInfo.copy(title = fallback)
        }
        val errorMsg = "Failed to extract manga title from URL: $url"
        logToDatabase(org.gotson.komga.domain.model.LogLevel.ERROR, errorMsg)
        logger.error { errorMsg }
        throw GalleryDlException(errorMsg)
      }

      logger.debug { "Successfully extracted title: ${mangaInfo.title}" }
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
    val pluginConfig = getPluginConfig()
    val configFile =
      galleryDlProcess.createInfoConfigFile(
        pluginConfig["mangadex_username"],
        pluginConfig["mangadex_password"],
        pluginConfig["default_language"] ?: "en",
        pluginConfig["flaresolverr_url"],
      )

    try {
      val command =
        galleryDlProcess.getCommand(getGalleryDlPath()).toMutableList().apply {
          add(url)
          add("-j")
          add("--simulate")
          add("--config")
          add(configFile.absolutePath)
        }

      val process =
        galleryDlProcess
          .applyEnv(ProcessBuilder(), getGalleryDlPath())
          .command(command)
          .start()

      BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        reader.lines().forEach { line -> appendJson(output, line) }
      }
      BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
        reader.lines().forEach { _ -> }
      }

      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        deleteQuietly(configFile)
        return emptyMap()
      }
      deleteQuietly(configFile)
      if (process.exitValue() != 0) return emptyMap()

      val mapping = mutableMapOf<String, ChapterDownloadInfo>()
      val entries = objectMapper.readValue(output.toString(), List::class.java) as? List<*> ?: return emptyMap()

      entries.forEach { entry ->
        if (entry !is List<*> || entry.size < 3) return@forEach
        val messageType = entry[0] as? Int ?: return@forEach
        if (messageType != 6) return@forEach

        val chapterUrl = entry[1] as? String ?: return@forEach
        val metadata = entry[2] as? Map<*, *> ?: return@forEach

        val chapterId = metadata["chapter_id"]?.toString()
        val chapterNum = metadata["chapter"]
        val chapterMinor = metadata["chapter-minor"] as? String ?: metadata["chapter_minor"] as? String ?: ""
        val chapterNumber =
          if (chapterNum != null) {
            val numStr = chapterNum.toString()
            if (chapterMinor.isNotBlank() && !chapterMinor.startsWith(".")) "$numStr.$chapterMinor" else "$numStr$chapterMinor"
          } else {
            null
          }

        mapping[chapterUrl] =
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

      logger.debug { "Parsed ${mapping.size} chapters from gallery-dl simulate output" }
      return mapping
    } catch (e: Exception) {
      logger.warn(e) { "Failed to parse gallery-dl chapter mapping" }
      deleteQuietly(configFile)
      return emptyMap()
    }
  }

  fun download(
    url: String,
    destinationPath: Path,
    libraryPath: Path? = null,
    komgaSeriesId: String? = null,
    chapterRange: String? = null,
    isCancelled: () -> Boolean = { false },
    onProcessStarted: (Process) -> Unit = {},
    onProgress: (DownloadProgress) -> Unit = {},
  ): DownloadResult {
    val chapterFilterExpr = chapterRange?.let { parseChapterRangeToFilter(it) }
    val output = StringBuilder()
    val errorOutput = StringBuilder()
    var configFileForCleanup: File? = null
    val existingCbzCountAtStart =
      destinationPath
        .toFile()
        .listFiles()
        ?.count { it.isFile && it.extension.lowercase() == "cbz" }
        ?: 0

    try {
      val mangaDexId = extractMangaDexId(url)
      var mangaInfo =
        if (mangaDexId != null) {
          getMangaMetadata(mangaDexId) ?: getChapterInfo(url)
        } else {
          getChapterInfo(url)
        }

      logger.debug { "Using title: ${mangaInfo.title}" }

      val destDir = destinationPath.toFile()
      if (!destDir.exists()) {
        destDir.mkdirs()
      }

      logger.debug { "Creating series.json" }
      try {
        createSeriesJson(mangaInfo, destinationPath)
      } catch (e: Exception) {
        logger.warn(e) { "Failed to create series.json, continuing anyway" }
      }

      val seriesJson = readSeriesJson(destinationPath)
      val pluginConfig = getPluginConfig().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
      val defaultLanguage = pluginConfig["default_language"] ?: "en"
      val configFile = galleryDlProcess.createTempConfigFile(pluginConfig, defaultLanguage)
      configFileForCleanup = configFile

      val gdlPath = getGalleryDlPath()
      val command =
        galleryDlProcess.getCommand(gdlPath).toMutableList().apply {
          add(url)
          add("-d")
          add(destinationPath.toString())
          add("--config")
          add(configFile.absolutePath)
          if (mangaDexId != null) {
            add("-o")
            add("lang=$defaultLanguage")
          }
          if (mangaDexId == null) {
            add("--download-archive")
            add(destinationPath.resolve(".gallery-dl-archive.txt").toString())
          }
          if (chapterFilterExpr != null) {
            add("--chapter-filter")
            add(chapterFilterExpr)
          }
        }

      try {
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
            logger.debug { "Downloading cover image" }
            mangaDexApiClient.downloadMangaCover(mangaDexId, mangaInfo.coverFilename!!, destinationPath)
          }
        } else {
          logger.warn { "Cannot download cover: mangaDexId=$mangaDexId, coverFilename=${mangaInfo.coverFilename}" }
        }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to download cover image (non-fatal)" }
      }

      val allChapters =
        if (mangaDexId != null) {
          logger.debug { "Fetching chapter list for $mangaDexId" }
          getChaptersForManga(mangaDexId)
        } else {
          logger.warn { "Not a MangaDex URL, falling back to single download" }
          emptyList()
        }

      chapterMatcher.normalizeDoubleBracketFilenames(destDir)

      val dbUrls =
        if (komgaSeriesId != null) {
          chapterUrlRepository.findUrlsBySeriesId(komgaSeriesId).toSet()
        } else {
          emptySet()
        }
      val cbzUrls =
        if (dbUrls.isEmpty()) {
          chapterMatcher.extractChapterUrlsFromCbzFiles(destDir)
        } else {
          emptySet()
        }
      val knownUrls = dbUrls + cbzUrls
      logger.debug { "Known chapter URLs: ${knownUrls.size} (db: ${dbUrls.size}, cbz: ${cbzUrls.size})" }

      val blacklistedUrls =
        if (komgaSeriesId != null) {
          blacklistedChapterRepository.findUrlsBySeriesId(komgaSeriesId)
        } else {
          blacklistedChapterRepository.findAll().map { it.chapterUrl }.toSet()
        }

      val chapters =
        allChapters.filter { chapter ->
          if (chapter.chapterUrl in blacklistedUrls) {
            logger.debug { "Skipping blacklisted chapter ${chapter.chapterNumber}" }
            return@filter false
          }

          if (chapter.chapterUrl in knownUrls) {
            return@filter false
          }

          true
        }

      val sameGroupDuplicates = chapterMatcher.findSameGroupDuplicates(allChapters)
      val sameGroupDuplicateUrls = sameGroupDuplicates.map { it.chapterUrl }.toSet()
      if (sameGroupDuplicates.isNotEmpty() && komgaSeriesId != null) {
        for (old in sameGroupDuplicates) {
          if (old.chapterUrl !in knownUrls &&
            old.chapterUrl !in blacklistedUrls &&
            !blacklistedChapterRepository.existsByChapterUrl(old.chapterUrl)
          ) {
            try {
              blacklistedChapterRepository.insert(
                org.gotson.komga.domain.model.BlacklistedChapter(
                  id =
                    java.util.UUID
                      .randomUUID()
                      .toString(),
                  seriesId = komgaSeriesId,
                  chapterUrl = old.chapterUrl,
                  chapterNumber = old.chapterNumber,
                  chapterTitle = old.chapterTitle,
                ),
              )
              logger.warn { "Auto-blacklisted same-group duplicate: ch.${old.chapterNumber} [${old.scanlationGroup}] ${old.chapterUrl}" }
            } catch (e: Exception) {
              logger.debug(e) { "Blacklist insert failed (likely duplicate): ${old.chapterUrl}" }
            }
          }
        }
      }
      val filteredChapters =
        chapters
          .filter { it.chapterUrl !in sameGroupDuplicateUrls }
          .let { list ->
            if (chapterRange != null) {
              list.filter { ch ->
                ch.chapterNumber?.toDoubleOrNull()?.let { chapterMatchesRange(it, chapterRange) } == true
              }
            } else {
              list
            }
          }

      val skippedByUrl = allChapters.count { it.chapterUrl in knownUrls }
      val skippedByBlacklist = allChapters.count { it.chapterUrl in blacklistedUrls }
      val skippedCount = allChapters.size - filteredChapters.size
      if (skippedCount > 0) {
        logger.debug {
          "Resuming download: $skippedCount/${allChapters.size} chapters already done, ${filteredChapters.size} remaining " +
            "(by URL: $skippedByUrl, by blacklist: $skippedByBlacklist, same-group duplicates: ${sameGroupDuplicates.size})"
        }
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.INFO,
          "Resuming: $skippedCount/${allChapters.size} chapters already downloaded, ${filteredChapters.size} remaining",
        )
      } else {
        logger.debug { "No existing chapters found, downloading all ${allChapters.size} chapters" }
      }

      val filesDownloaded = AtomicInteger(0)
      var totalChapters = filteredChapters.size

      if (allChapters.isEmpty() && mangaDexId != null) {
        logger.warn { "MangaDex chapter API returned empty for $mangaDexId — skipping bulk download to prevent re-downloads" }
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.WARN,
          "MangaDex chapter API returned empty for $mangaDexId, skipping download",
        )
        deleteQuietly(configFile)

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
          newlyDownloaded = (downloadedFiles.size - existingCbzCountAtStart).coerceAtLeast(0),
        )
      } else if (allChapters.isEmpty()) {
        val galleryDlChapterMap =
          if (mangaDexId == null) {
            fetchGalleryDlChapterMapping(url)
          } else {
            emptyMap()
          }

        if (galleryDlChapterMap.isNotEmpty()) totalChapters = galleryDlChapterMap.size

        val effectiveCommand = command

        logger.debug { "Starting bulk download: $url ($totalChapters chapters in source listing, $existingCbzCountAtStart CBZ already in destDir)" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Starting download: $url")

        val process =
          galleryDlProcess
            .applyEnv(ProcessBuilder(), gdlPath)
            .command(effectiveCommand)
            .directory(File(System.getProperty("user.home")))
            .start()

        onProcessStarted(process)
        var lastProgress = 0
        val chaptersCompleted = AtomicInteger(0)
        if (totalChapters > 0) {
          onProgress(DownloadProgress(0, totalChapters, 0, "Resuming download"))
        }

        val stdoutThread =
          Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
              reader.lines().forEach { line ->
                appendBounded(output, line)
                logger.debug { "gallery-dl: $line" }

                // The gallery-dl `komga` postprocessor prints this marker once per
                // completed chapter directory (see komga.py `_emit_chapter_progress`) —
                // count those for real chapter progress instead of guessing from file paths.
                if (line.trim() == "[komga] chapter-complete") {
                  val count = chaptersCompleted.incrementAndGet()
                  val total = maxOf(totalChapters, count)
                  onProgress(
                    DownloadProgress(
                      currentChapter = count,
                      totalChapters = total,
                      percent = count * 100 / total,
                      message = "Downloading chapter $count/$total",
                    ),
                  )
                }
              }
            }
          }
        stdoutThread.start()

        val stderrThread =
          Thread {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
              reader.lines().forEach { line ->
                appendBounded(errorOutput, line)
                logger.debug { "gallery-dl stderr: $line" }

                val progress = parseGalleryDlProgress(line, chaptersCompleted.get())
                if (progress != null && progress.percent > lastProgress) {
                  lastProgress = progress.percent
                  onProgress(progress)
                }
              }
            }
          }
        stderrThread.start()

        if (!process.waitFor(2, TimeUnit.HOURS)) {
          process.destroyForcibly()
          stdoutThread.join(5000)
          if (stdoutThread.isAlive) stdoutThread.interrupt()
          stderrThread.join(5000)
          if (stderrThread.isAlive) stderrThread.interrupt()
          deleteQuietly(configFile)
          throw GalleryDlException("Timeout downloading $url")
        }
        stdoutThread.join(5000)
        if (stdoutThread.isAlive) stdoutThread.interrupt()
        stderrThread.join(5000)
        if (stderrThread.isAlive) stderrThread.interrupt()

        val exitCode = process.exitValue()
        deleteQuietly(configFile)

        if (exitCode != 0) {
          val stderr = errorOutput.toString().trim()
          val stdout = output.toString().trim()
          // gallery-dl prints most output (incl. some errors) to stdout and only part to stderr.
          // Surface whichever carries the failure so the Komga log shows the real cause.
          val detail =
            when {
              stderr.isNotEmpty() -> stderr.takeLast(4000)
              stdout.isNotEmpty() -> stdout.takeLast(4000)
              else -> "(gallery-dl produced no output)"
            }
          throw GalleryDlException("Download failed with exit code $exitCode: $detail")
        }

        val allFiles = destDir.walkTopDown().toList()
        val cbzFilesInSubdirs = allFiles.filter { it.isFile && it.extension.lowercase() == "cbz" && it.parentFile != destDir }
        if (cbzFilesInSubdirs.isNotEmpty()) {
          logger.debug { "Found ${cbzFilesInSubdirs.size} CBZ files in subdirectories, moving to root" }
          cbzFilesInSubdirs.forEach { cbzFile ->
            val target = File(destDir, cbzFile.name)
            if (!target.exists()) {
              Files.move(cbzFile.toPath(), target.toPath())
              logger.debug { "Moved ${cbzFile.relativeTo(destDir).path} -> ${target.name}" }
            }
          }
          allFiles.filter { it.isDirectory && it != destDir && it.listFiles()?.isEmpty() == true }.forEach { deleteQuietly(it) }
        }

        // The gallery-dl `komga` postprocessor already wrote a correct, source-aware
        // ComicInfo.xml into each CBZ during download. Komga must NOT re-inject it here:
        // doing so overwrote the good file and, for non-MangaDex sources, produced a bogus
        // `mangadex.org/chapter/…` URL. Trust the postprocessor's output.
        chapterMatcher.normalizeDoubleBracketFilenames(destDir)
      } else if (filteredChapters.isEmpty()) {
        logger.debug { "All ${allChapters.size} chapters already downloaded, nothing to do" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "All chapters already downloaded, skipping: $url")
        deleteQuietly(configFile)
      } else {
        val failuresFile = File(destDir, ".chapter-failures.json")
        val chapterFailures = loadChapterFailures(failuresFile)
        val currentMangaDexId = extractMangaDexId(url)

        val externalRedirects = filteredChapters.count { it.pages == 0 && it.externalUrl != null }
        totalChapters = filteredChapters.count { (it.pages > 0 || it.externalUrl == null) && (chapterFailures[it.chapterUrl] ?: 0) < 3 }
        val autoBlacklisted = filteredChapters.size - totalChapters - externalRedirects
        val resumeInfo = if (skippedCount > 0) " (resuming, $skippedCount already done)" else ""
        val externalInfo = if (externalRedirects > 0) " ($externalRedirects external redirects)" else ""
        logger.debug { "Downloading $totalChapters chapters$resumeInfo${if (autoBlacklisted > 0) " ($autoBlacklisted auto-blacklisted)" else ""}$externalInfo" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Downloading $totalChapters chapters$resumeInfo: $url")

        var downloadIndex = 0
        filteredChapters.forEachIndexed { index, chapter ->
          if (isCancelled()) {
            saveChapterFailures(failuresFile, chapterFailures)
            logger.debug { "Download cancelled before chapter ${downloadIndex + 1}/$totalChapters, stopping" }
            deleteQuietly(configFile)
            return DownloadResult(
              success = true,
              filesDownloaded = filesDownloaded.get(),
              downloadedFiles = emptyList(),
              totalChapters = totalChapters,
              errorMessage = null,
              mangaTitle = mangaInfo.title,
            )
          }

          val chapterNum = chapter.chapterNumber ?: "${index + 1}"

          if (chapter.pages == 0 && chapter.externalUrl != null) {
            if (komgaSeriesId != null && !blacklistedChapterRepository.existsByChapterUrl(chapter.chapterUrl)) {
              try {
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
                logger.warn { "Auto-blacklisted external redirect chapter $chapterNum (pages=0): ${chapter.chapterUrl}" }
              } catch (e: Exception) {
                logger.debug(e) { "Blacklist insert failed (likely duplicate): ${chapter.chapterUrl}" }
              }
            } else if (komgaSeriesId == null) {
              logger.warn { "Cannot blacklist external chapter $chapterNum: series not yet in database" }
            }
            return@forEachIndexed
          }

          val failCount = chapterFailures[chapter.chapterUrl] ?: 0
          if (failCount >= 3) {
            if (komgaSeriesId != null && !blacklistedChapterRepository.existsByChapterUrl(chapter.chapterUrl)) {
              try {
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
                logger.warn { "Auto-blacklisted chapter $chapterNum after $failCount failed attempts: ${chapter.chapterUrl}" }
              } catch (e: Exception) {
                logger.debug(e) { "Blacklist insert failed (likely duplicate): ${chapter.chapterUrl}" }
              }
            } else if (komgaSeriesId == null) {
              logger.warn { "Cannot blacklist chapter $chapterNum: series not yet in database" }
            }
            return@forEachIndexed
          }

          downloadIndex++
          logger.debug { "Downloading chapter $chapterNum ($downloadIndex/$totalChapters): ${chapter.chapterUrl}" }

          val chapterCommand =
            galleryDlProcess.getCommand(gdlPath).toMutableList().apply {
              add(chapter.chapterUrl)
              add("-d")
              add(destinationPath.toString())
              add("--config")
              add(configFile.absolutePath)
            }

          try {
            val chapterProcess =
              galleryDlProcess
                .applyEnv(ProcessBuilder(), gdlPath)
                .command(chapterCommand)
                .directory(File(System.getProperty("user.home")))
                .start()

            onProcessStarted(chapterProcess)

            val chapterOutput = StringBuilder()
            val chapterError = StringBuilder()

            val chStdoutThread =
              Thread {
                BufferedReader(InputStreamReader(chapterProcess.inputStream)).use { reader ->
                  reader.lines().forEach { line ->
                    appendBounded(chapterOutput, line)
                    appendBounded(output, line)
                    logger.debug { "gallery-dl [ch$chapterNum]: $line" }
                  }
                }
              }
            chStdoutThread.start()

            val chStderrThread =
              Thread {
                BufferedReader(InputStreamReader(chapterProcess.errorStream)).use { reader ->
                  reader.lines().forEach { line ->
                    appendBounded(chapterError, line)
                    appendBounded(errorOutput, line)
                    logger.debug { "gallery-dl [ch$chapterNum] stderr: $line" }
                  }
                }
              }
            chStderrThread.start()

            val completed = chapterProcess.waitFor(10, TimeUnit.MINUTES)
            chStdoutThread.join(5000)
            if (chStdoutThread.isAlive) chStdoutThread.interrupt()
            chStderrThread.join(5000)
            if (chStderrThread.isAlive) chStderrThread.interrupt()
            if (!completed) {
              chapterProcess.destroyForcibly()
              logger.warn { "Chapter $chapterNum download timed out" }
            } else if (chapterProcess.exitValue() == 0) {
              filesDownloaded.incrementAndGet()

              val chapterStr = chapter.chapterNumber ?: "${index + 1}"
              val paddedChapter = chapterMatcher.padChapterNumber(chapterStr)
              val cbzFiles =
                destDir
                  .listFiles()
                  ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
                  ?: emptyList()

              val recentCbzFiles =
                cbzFiles
                  .filter { System.currentTimeMillis() - it.lastModified() < 120_000 }
                  .sortedByDescending { it.lastModified() }

              val groupName = chapter.scanlationGroup?.lowercase()

              val targetCbz =
                recentCbzFiles.find { chapterMatcher.matchesChapterAndGroup(it.nameWithoutExtension.lowercase(), paddedChapter, chapterStr, groupName) }
                  ?: cbzFiles.find { chapterMatcher.matchesChapterAndGroup(it.nameWithoutExtension.lowercase(), paddedChapter, chapterStr, groupName) }
                  ?: recentCbzFiles.find { chapterMatcher.matchesChapterNumber(it.nameWithoutExtension.lowercase(), paddedChapter, chapterStr) }
                  ?: cbzFiles.find { chapterMatcher.matchesChapterNumber(it.nameWithoutExtension.lowercase(), paddedChapter, chapterStr) }

              if (targetCbz == null) {
                val expected =
                  if (paddedChapter == chapterStr) "c$paddedChapter" else "c$paddedChapter or c$chapterStr"
                logger.warn { "Could not find CBZ file for chapter $chapterNum (expected $expected)" }
              } else {
                try {
                  // ComicInfo.xml is written by gallery-dl's `komga` postprocessor during the
                  // download. Komga no longer injects it here: this fallback only ever fired for
                  // files gallery-dl had not produced (pre-existing chapters) and, due to number-only
                  // file matching, could rewrite an unrelated older chapter of a different scanlation
                  // group with the wrong metadata. The manual "Re-inject ComicInfo" maintenance
                  // action remains for deliberate backfills.
                  if (komgaSeriesId != null && !chapterUrlRepository.existsByUrl(chapter.chapterUrl)) {
                    val chapterNumVal =
                      chapter.chapterNumber
                        ?.toDoubleOrNull()
                        ?: (index + 1).toDouble()
                    chapterUrlRepository.insert(
                      org.gotson.komga.domain.model.ChapterUrl(
                        id =
                          java.util.UUID
                            .randomUUID()
                            .toString(),
                        seriesId = komgaSeriesId,
                        url = chapter.chapterUrl,
                        chapter = chapterNumVal,
                        volume = chapter.volume?.toIntOrNull(),
                        title = chapter.chapterTitle,
                        lang = chapter.language ?: "en",
                        downloadedAt = java.time.LocalDateTime.now(),
                        chapterId = chapter.chapterId,
                        scanlationGroup = chapter.scanlationGroup,
                      ),
                    )
                    logger.debug { "Registered chapter URL in DB: ch.$chapterNumVal ${chapter.chapterUrl}" }
                  }
                  logger.debug { "Processed ${targetCbz.name}" }
                } catch (e: Exception) {
                  logger.warn(e) { "Failed to process CBZ ${targetCbz.name}" }
                }
              }

              val progressPercent = if (totalChapters > 0) (downloadIndex * 100) / totalChapters else 100
              onProgress(
                DownloadProgress(
                  currentChapter = filesDownloaded.get(),
                  totalChapters = totalChapters,
                  percent = progressPercent,
                  message = "Downloaded chapter $chapterNum",
                  chapterTitle = chapter.chapterTitle,
                ),
              )
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
        chapterMatcher.normalizeDoubleBracketFilenames(destDir)
        deleteQuietly(configFile)
      }

      val downloadedFiles =
        destDir
          .listFiles()
          ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
          ?.map { it.absolutePath }
          ?: emptyList()

      logger.debug { "Found ${downloadedFiles.size} CBZ files in ${destDir.absolutePath}" }

      logger.debug { "Download completed: ${downloadedFiles.size} files (manga: ${mangaInfo.title})" }
      logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Download completed successfully: ${downloadedFiles.size} files downloaded from $url")

      return DownloadResult(
        success = true,
        filesDownloaded = downloadedFiles.size,
        downloadedFiles = downloadedFiles,
        totalChapters = downloadedFiles.size,
        errorMessage = null,
        mangaTitle = mangaInfo.title,
        newlyDownloaded = (downloadedFiles.size - existingCbzCountAtStart).coerceAtLeast(0),
      )
    } catch (e: GalleryDlException) {
      configFileForCleanup?.let { deleteQuietly(it) }
      if (isCancelled()) {
        logger.info { "Download cancelled: $url" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Download cancelled: $url")
      } else {
        logger.error(e) { "Download failed: $url" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.ERROR, "Download failed for $url: ${e.message}", e.stackTraceToString())
      }
      return DownloadResult(
        success = false,
        filesDownloaded = 0,
        downloadedFiles = emptyList(),
        totalChapters = 0,
        errorMessage = if (isCancelled()) "Cancelled" else (e.message ?: "Unknown error"),
      )
    } catch (e: Exception) {
      configFileForCleanup?.let { deleteQuietly(it) }
      if (isCancelled()) {
        logger.info { "Download cancelled: $url" }
        logToDatabase(org.gotson.komga.domain.model.LogLevel.INFO, "Download cancelled: $url")
        return DownloadResult(
          success = false,
          filesDownloaded = 0,
          downloadedFiles = emptyList(),
          totalChapters = 0,
          errorMessage = "Cancelled",
        )
      }
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

  private fun addComicInfoToCbz(
    cbzPath: Path,
    mangaInfo: MangaInfo,
  ) {
    try {
      val chapterId = if (mangaInfo.mangaDexId != null) chapterMatcher.extractChapterId(cbzPath) else null
      val chapterInfo =
        if (chapterId != null) {
          logger.debug { "Fetching chapter metadata for ${cbzPath.fileName} (chapter ID: $chapterId)" }
          mangaDexApiClient.fetchChapterMetadata(chapterId)
        } else {
          null
        }

      val chapterUrl = if (chapterId != null) "https://mangadex.org/chapter/$chapterId" else null
      val comicInfoXml = comicInfoGenerator.generateComicInfoXml(mangaInfo, chapterInfo, chapterUrl)
      val zipComment = comicInfoGenerator.generateZipComment(mangaInfo, chapterInfo, chapterId)

      comicInfoGenerator.injectComicInfo(cbzPath, comicInfoXml, zipComment)

      logger.debug {
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
    try {
      val chapterId =
        chapterMatcher.extractChapterId(cbzPath)
          ?: chapterUrl
            ?.substringAfterLast("/chapter/", "")
            ?.takeIf { it.isNotEmpty() }
      val comicInfoXml = comicInfoGenerator.generateComicInfoXml(mangaInfo, chapterInfo, chapterUrl)
      val zipComment = comicInfoGenerator.generateZipComment(mangaInfo, chapterInfo, chapterId)

      comicInfoGenerator.injectComicInfoWithRetry(cbzPath, comicInfoXml, zipComment)

      logger.debug {
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

  fun repairMissingComicInfo(
    mangaDexId: String,
    directories: List<File>,
    forceReinject: Boolean = false,
  ): RepairResult {
    val mangaInfo =
      try {
        getMangaMetadata(mangaDexId) ?: return RepairResult(0, 0, "Could not fetch metadata for $mangaDexId")
      } catch (e: Exception) {
        return RepairResult(0, 0, "Failed to fetch metadata: ${e.message}")
      }

    val allChapters =
      try {
        getChaptersForManga(mangaDexId)
      } catch (e: Exception) {
        return RepairResult(0, 0, "Failed to fetch chapters: ${e.message}")
      }

    val chapterMap = mutableMapOf<String, MutableList<ChapterDownloadInfo>>()
    val chapterUrlMap = mutableMapOf<String, ChapterDownloadInfo>()
    for (ch in allChapters) {
      chapterUrlMap[ch.chapterUrl] = ch
      val num = ch.chapterNumber ?: continue
      val padded = chapterMatcher.padChapterNumber(num)
      val plain =
        try {
          val n = num.toDouble()
          if (n == n.toLong().toDouble()) n.toLong().toString() else num
        } catch (_: NumberFormatException) {
          num
        }
      chapterMap.getOrPut(padded) { mutableListOf() }.add(ch)
      if (plain != padded) chapterMap.getOrPut(plain) { mutableListOf() }.add(ch)
    }

    var repaired = 0
    var skipped = 0

    for (dir in directories) {
      val cbzFiles =
        dir
          .listFiles()
          ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
          ?: continue

      for (cbzFile in cbzFiles) {
        try {
          // Refuse to overwrite CBZs that came from a non-MangaDex source — the UUID
          // folder may contain manually-copied chapters (mangabuddy, cubari, ...) whose
          // original ComicInfo carries source-specific <Series>/<Number>/<Web>. Re-injecting
          // MangaDex metadata destroys that. The check runs even under forceReinject.
          val existingWeb = comicInfoGenerator.readWebUrl(cbzFile)
          if (existingWeb != null && !existingWeb.contains("mangadex.org", ignoreCase = true)) {
            logger.warn {
              "repair-comicinfo: skip ${cbzFile.name} — existing ComicInfo Web is non-MangaDex ($existingWeb)"
            }
            skipped++
            continue
          }

          if (!forceReinject) {
            val hasComment =
              java.util.zip.ZipFile(cbzFile).use { zf ->
                !zf.comment.isNullOrBlank()
              }
            if (hasComment) {
              skipped++
              continue
            }
          }

          val chapterNum =
            chapterMatcher.extractChapterNumFromFilename(cbzFile.nameWithoutExtension.lowercase())
          val fileGroup =
            chapterMatcher.extractScanlationGroup(cbzFile.nameWithoutExtension)

          val candidates = if (chapterNum != null) chapterMap[chapterNum] else null
          val chapterByNum =
            if (candidates != null && fileGroup != null) {
              candidates.find { it.scanlationGroup?.equals(fileGroup, ignoreCase = true) == true }
                ?: candidates.firstOrNull()
            } else {
              candidates?.firstOrNull()
            }

          val chapter =
            chapterByNum ?: run {
              val chapterId = chapterMatcher.extractChapterId(cbzFile.toPath())
              if (chapterId != null) chapterUrlMap["https://mangadex.org/chapter/$chapterId"] else null
            }

          if (chapter != null) {
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
            repaired++
            logger.debug { "Repaired: ${cbzFile.name}" }
          } else {
            addComicInfoToCbz(cbzFile.toPath(), mangaInfo)
            repaired++
            logger.debug { "Repaired (series-only): ${cbzFile.name}" }
          }
        } catch (e: Exception) {
          logger.warn(e) { "Failed to repair ${cbzFile.name}" }
        }
      }
    }

    return RepairResult(repaired, skipped, null)
  }

  data class RepairResult(
    val repaired: Int,
    val skipped: Int,
    val error: String?,
  )

  private fun deriveTitleFromUrl(url: String): String? =
    try {
      val uri = URI(url)
      val pathSegments =
        uri.path
          ?.split("/")
          ?.filter { it.isNotBlank() }
          ?: emptyList()
      val genericSegments =
        setOf("list", "home", "index", "read", "chapter", "chapters", "all", "manga", "comic", "episode", "episodes", "viewer")
      val meaningfulSegment =
        pathSegments
          .dropLastWhile { it.lowercase() in genericSegments || it.all { c -> c.isDigit() } }
          .lastOrNull()
          ?: pathSegments.lastOrNull()
          ?: return null
      meaningfulSegment
        .replace("-", " ")
        .replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    } catch (e: Exception) {
      logger.debug(e) { "Failed to derive title from URL" }
      null
    }

  private fun logToDatabase(
    level: org.gotson.komga.domain.model.LogLevel,
    message: String,
    exceptionTrace: String? = null,
  ) {
    val line = "[$pluginId] $message"
    when (level) {
      org.gotson.komga.domain.model.LogLevel.ERROR -> logger.error { exceptionTrace?.let { "$line\n$it" } ?: line }
      org.gotson.komga.domain.model.LogLevel.WARN -> logger.warn { line }
      org.gotson.komga.domain.model.LogLevel.DEBUG -> logger.debug { line }
      else -> logger.info { line }
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
      if (seriesJsonFile.exists()) {
        logger.debug { "series.json already exists, leaving it untouched (preserves Komga AutoMatch enrichment)" }
        return
      }
      val newContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata)

      logger.debug { "Writing series.json to: ${seriesJsonFile.absolutePath}" }
      val tempFile = File(seriesJsonFile.parent, ".series.json.tmp")
      tempFile.writeText(newContent)
      try {
        Files.move(tempFile.toPath(), seriesJsonFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
      } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
        logger.debug(e) { "Atomic move not supported, falling back to regular move for series.json" }
        Files.move(tempFile.toPath(), seriesJsonFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }

      if (!seriesJsonFile.exists()) {
        throw java.io.IOException("series.json file was not created")
      }

      val fileSize = seriesJsonFile.length()
      if (fileSize == 0L) {
        throw java.io.IOException("series.json file is empty")
      }

      val fileSizeKb = fileSize / 1024.0
      if (fileSize < 5120) {
        logger.debug { "series.json is only $fileSizeKb KB (expected >5 KB). May lack proper metadata." }
        logToDatabase(
          org.gotson.komga.domain.model.LogLevel.INFO,
          "series.json is only $fileSizeKb KB (expected >5 KB). May lack proper metadata.",
        )
      }

      logToDatabase(
        org.gotson.komga.domain.model.LogLevel.INFO,
        "Created series.json with ${alternateTitles.size} alternative titles ($fileSizeKb KB)",
      )
      logger.debug { "series.json created successfully: ${seriesJsonFile.absolutePath} ($fileSizeKb KB)" }
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
    val genresSet = mutableSetOf<String>()
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
              onGenres = { genresSet.addAll(it) },
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
                onGenres = { genresSet.addAll(it) },
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
              onGenres = { genresSet.addAll(it) },
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
      genres = genresSet.toList(),
    )
  }

  private inline fun extractMetadataFields(
    metadata: Map<*, *>,
    onTitle: (String?, String?) -> Unit,
    onDescription: (String) -> Unit,
    onAltTitles: (String, String) -> Unit,
    onAuthor: (String) -> Unit,
    onGroup: (String) -> Unit,
    onGenres: (List<String>) -> Unit,
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

    @Suppress("UNCHECKED_CAST")
    val genres = metadata["genres"] as? List<*>
    if (genres != null) {
      val stringGenres = genres.filterIsInstance<String>()
      if (stringGenres.isNotEmpty()) onGenres(stringGenres)
    }

    @Suppress("UNCHECKED_CAST")
    val tags = metadata["tags"] as? List<*>
    if (tags != null) {
      val stringTags = tags.filterIsInstance<String>()
      if (stringTags.isNotEmpty()) onGenres(stringTags)
    }
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
    val match = progressRegex.find(line) ?: return null
    val percent = match.groupValues[1].toIntOrNull() ?: return null
    return DownloadProgress(
      currentChapter = currentFile,
      totalChapters = 0,
      percent = percent,
      message = line,
    )
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
    } catch (e: Exception) {
      logger.debug(e) { "Failed to load chapter failures from ${file.name}" }
      mutableMapOf()
    }

  private fun saveChapterFailures(
    file: File,
    failures: Map<String, Int>,
  ) {
    try {
      if (failures.isEmpty()) {
        if (file.exists()) deleteQuietly(file)
      } else {
        objectMapper.writeValue(file, failures)
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to save chapter failures" }
    }
  }

  private fun parseChapterRangeToFilter(range: String): String? {
    val parts = range.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val exprs =
      parts.mapNotNull { part ->
        if (part.count { it == '-' } == 1 && !part.startsWith("-") && !part.endsWith("-")) {
          val (a, b) = part.split("-").map { it.trim() }
          val af = a.toDoubleOrNull() ?: return@mapNotNull null
          val bf = b.toDoubleOrNull() ?: return@mapNotNull null
          "(chapter >= $af and chapter <= $bf)"
        } else {
          val n = part.toDoubleOrNull() ?: return@mapNotNull null
          "chapter == $n"
        }
      }
    return if (exprs.isEmpty()) null else exprs.joinToString(" or ")
  }

  private fun chapterMatchesRange(
    chapterNumber: Double,
    range: String,
  ): Boolean {
    val parts = range.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    for (part in parts) {
      if (part.count { it == '-' } == 1 && !part.startsWith("-") && !part.endsWith("-")) {
        val (a, b) = part.split("-").map { it.trim() }
        val af = a.toDoubleOrNull() ?: continue
        val bf = b.toDoubleOrNull() ?: continue
        if (chapterNumber in af..bf) return true
      } else {
        val n = part.toDoubleOrNull() ?: continue
        if (chapterNumber == n) return true
      }
    }
    return false
  }
}

data class ChapterDownloadInfo(
  val chapterId: String?,
  val chapterNumber: String?,
  val chapterTitle: String?,
  val volume: String?,
  val pages: Int,
  val scanlationGroup: String?,
  val publishDate: String?,
  val language: String?,
  val chapterUrl: String,
  val externalUrl: String? = null,
)

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
      } catch (e: Exception) {
        logger.debug(e) { "Failed to derive publisher from URL: $url" }
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
  val chapterTitle: String? = null,
)

data class DownloadResult(
  val success: Boolean,
  val filesDownloaded: Int,
  val downloadedFiles: List<String>,
  val totalChapters: Int,
  val errorMessage: String?,
  val mangaTitle: String? = null,
  val newlyDownloaded: Int = filesDownloaded,
)

class GalleryDlException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
