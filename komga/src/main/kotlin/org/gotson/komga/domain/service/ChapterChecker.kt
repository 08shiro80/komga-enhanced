package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DownloadStatus
import org.gotson.komga.domain.persistence.BlacklistedChapterRepository
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.DownloadQueueRepository
import org.gotson.komga.domain.persistence.FollowConfigRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

data class ChapterCheckResult(
  val url: String,
  val mangaId: String?,
  val title: String?,
  val apiChapterCount: Int,
  val downloadedChapterCount: Int,
  val filesystemChapterCount: Int,
  val newChaptersEstimate: Int,
  val needsDownload: Boolean,
  val error: String? = null,
)

data class ChapterCheckSummary(
  val totalManga: Int,
  val checkedCount: Int,
  val needsDownloadCount: Int,
  val upToDateCount: Int,
  val errorCount: Int,
  val results: List<ChapterCheckResult>,
  val durationMs: Long,
)

@Service
class ChapterChecker(
  private val followConfigRepository: FollowConfigRepository,
  private val chapterUrlRepository: ChapterUrlRepository,
  private val blacklistedChapterRepository: BlacklistedChapterRepository,
  private val downloadQueueRepository: DownloadQueueRepository,
  private val downloadExecutor: DownloadExecutor,
  private val libraryRepository: LibraryRepository,
  private val seriesRepository: org.gotson.komga.domain.persistence.SeriesRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val objectMapper: ObjectMapper,
) {
  private val httpClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build()

  private val concurrencyLimit = Semaphore(5)

  fun checkAll(): ChapterCheckSummary {
    val config = followConfigRepository.findDefault()
    if (config == null || config.urls.isEmpty()) {
      logger.info { "No follow config URLs to check" }
      return ChapterCheckSummary(0, 0, 0, 0, 0, emptyList(), 0)
    }

    return checkUrls(config.urls)
  }

  fun checkUrls(urls: List<String>): ChapterCheckSummary {
    val startTime = System.currentTimeMillis()
    logger.info { "Starting chapter check for ${urls.size} manga URLs" }

    val executor = Executors.newFixedThreadPool(5)
    val futures =
      urls.map { url ->
        CompletableFuture.supplyAsync(
          {
            try {
              concurrencyLimit.acquire()
              try {
                checkSingleUrl(url)
              } finally {
                concurrencyLimit.release()
              }
            } catch (e: InterruptedException) {
              Thread.currentThread().interrupt()
              ChapterCheckResult(
                url = url,
                mangaId = null,
                title = null,
                apiChapterCount = 0,
                downloadedChapterCount = 0,
                filesystemChapterCount = 0,
                newChaptersEstimate = 0,
                needsDownload = false,
                error = "Interrupted",
              )
            }
          },
          executor,
        )
      }

    val results = futures.map { it.join() }
    executor.shutdown()
    executor.awaitTermination(10, TimeUnit.MINUTES)

    val durationMs = System.currentTimeMillis() - startTime
    val needsDownload = results.filter { it.needsDownload }
    val errors = results.filter { it.error != null }
    val upToDate = results.filter { !it.needsDownload && it.error == null }

    logger.info {
      "Chapter check completed in ${durationMs}ms: " +
        "${results.size} checked, ${needsDownload.size} need download, " +
        "${upToDate.size} up to date, ${errors.size} errors"
    }

    return ChapterCheckSummary(
      totalManga = urls.size,
      checkedCount = results.size,
      needsDownloadCount = needsDownload.size,
      upToDateCount = upToDate.size,
      errorCount = errors.size,
      results = results,
      durationMs = durationMs,
    )
  }

  fun checkAndQueueNewChapters(): ChapterCheckSummary {
    val summary = checkAll()

    summary.results
      .filter { it.needsDownload }
      .forEach { result ->
        val alreadyQueued =
          downloadQueueRepository.existsBySourceUrlAndStatusIn(
            result.url,
            listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING),
          )
        if (!alreadyQueued) {
          try {
            downloadExecutor.createDownload(
              sourceUrl = result.url,
              libraryId = null,
              title = result.title,
              createdBy = "chapter-checker",
              priority = 5,
            )
            logger.info { "Queued download for ${result.title ?: result.url}: ~${result.newChaptersEstimate} new chapters" }
          } catch (e: Exception) {
            logger.warn(e) { "Failed to queue download for ${result.url}" }
          }
        } else {
          logger.debug { "Skipping already-queued URL: ${result.url}" }
        }
      }

    followConfigRepository.findDefault()?.let { config ->
      followConfigRepository.save(config.copy(lastCheckTime = LocalDateTime.now()))
    }

    return summary
  }

  private fun checkSingleUrl(url: String): ChapterCheckResult {
    val mangaId = GalleryDlWrapper.extractMangaDexId(url)
    if (mangaId == null) {
      return ChapterCheckResult(
        url = url,
        mangaId = null,
        title = null,
        apiChapterCount = 0,
        downloadedChapterCount = 0,
        filesystemChapterCount = 0,
        newChaptersEstimate = 0,
        needsDownload = false,
        error = "Not a MangaDex URL",
      )
    }

    try {
      val feedResult = fetchMangaDexFeedTotal(mangaId)
      val apiChapterCount = feedResult.chapterCount
      val title = feedResult.title

      val downloadedCount = countDownloadedChapters(url, mangaId)
      val filesystemCount = countFilesystemChapters(url)

      val blacklistedCount = countBlacklistedChapters(mangaId)
      val knownCount = filesystemCount + blacklistedCount

      logger.info {
        "Chapter check for ${title ?: mangaId}: api=$apiChapterCount, " +
          "db=$downloadedCount, fs=$filesystemCount, blacklisted=$blacklistedCount, " +
          "known=$knownCount"
      }

      val newChaptersEstimate = maxOf(0, apiChapterCount - knownCount)
      val needsDownload = newChaptersEstimate > 0

      if (needsDownload) {
        logger.info { "New chapters detected for ${title ?: mangaId}: $apiChapterCount available, $knownCount known ($newChaptersEstimate new)" }
      } else {
        logger.info { "Up to date: ${title ?: mangaId} ($knownCount/$apiChapterCount)" }
      }

      return ChapterCheckResult(
        url = url,
        mangaId = mangaId,
        title = title,
        apiChapterCount = apiChapterCount,
        downloadedChapterCount = downloadedCount,
        filesystemChapterCount = filesystemCount,
        newChaptersEstimate = newChaptersEstimate,
        needsDownload = needsDownload,
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to check $url" }
      return ChapterCheckResult(
        url = url,
        mangaId = mangaId,
        title = null,
        apiChapterCount = 0,
        downloadedChapterCount = 0,
        filesystemChapterCount = 0,
        newChaptersEstimate = 0,
        needsDownload = false,
        error = e.message,
      )
    }
  }

  private data class FeedResult(
    val chapterCount: Int,
    val title: String?,
  )

  private fun getDownloadLanguage(): String =
    try {
      pluginConfigRepository
        .findByPluginIdAndKey("gallery-dl-downloader", "default_language")
        ?.configValue ?: "en"
    } catch (_: Exception) {
      "en"
    }

  private fun fetchMangaDexFeedTotal(mangaId: String): FeedResult {
    val language = getDownloadLanguage()
    val feedUrl =
      "https://api.mangadex.org/manga/$mangaId/feed?translatedLanguage[]=$language&limit=0"

    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(feedUrl))
        .timeout(Duration.ofSeconds(15))
        .GET()
        .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
      throw GalleryDlAggregateFetchException(
        "MangaDex feed API returned ${response.statusCode()} for manga $mangaId",
      )
    }

    val json = objectMapper.readValue<Map<String, Any?>>(response.body())
    val chapterCount = (json["total"] as? Number)?.toInt() ?: 0
    val title = fetchMangaTitle(mangaId)

    return FeedResult(chapterCount = chapterCount, title = title)
  }

  private fun fetchMangaTitle(mangaId: String): String? {
    try {
      val request =
        HttpRequest
          .newBuilder()
          .uri(URI.create("https://api.mangadex.org/manga/$mangaId"))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() != 200) return null

      val json = objectMapper.readValue<Map<String, Any?>>(response.body())
      val data = json["data"] as? Map<*, *> ?: return null
      val attributes = data["attributes"] as? Map<*, *> ?: return null
      val titleMap = attributes["title"] as? Map<*, *> ?: return null

      val altTitles = attributes["altTitles"] as? List<*> ?: emptyList<Any>()
      var altEnglishTitle: String? = null
      altTitles.forEach { entry ->
        if (entry is Map<*, *>) {
          val enTitle = entry["en"] as? String
          if (enTitle != null && altEnglishTitle == null) {
            altEnglishTitle = enTitle
          }
        }
      }

      return altEnglishTitle ?: titleMap["en"] as? String ?: titleMap.values.firstOrNull() as? String
    } catch (e: Exception) {
      logger.debug { "Failed to fetch title for manga $mangaId: ${e.message}" }
      return null
    }
  }

  private fun countDownloadedChapters(
    url: String,
    mangaId: String,
  ): Int =
    try {
      chapterUrlRepository
        .findAll()
        .count { it.url.contains(mangaId) }
    } catch (e: Exception) {
      logger.warn(e) { "Error counting downloaded chapters for $mangaId, falling back to 0" }
      0
    }

  private fun countFilesystemChapters(url: String): Int {
    val mangaId = GalleryDlWrapper.extractMangaDexId(url) ?: return 0
    val folder = findMangaFolder(mangaId) ?: return 0
    return folder
      .listFiles()
      ?.count { it.isFile && it.extension.lowercase() == "cbz" }
      ?: 0
  }

  private fun findMangaFolder(mangaId: String): java.io.File? {
    libraryRepository.findAll().forEach { library ->
      try {
        val libraryDir = library.path.toFile()
        if (!libraryDir.exists()) return@forEach

        val uuidFolder = java.io.File(libraryDir, mangaId)
        if (uuidFolder.exists() && uuidFolder.isDirectory) {
          return uuidFolder
        }

        libraryDir.listFiles()?.filter { it.isDirectory }?.forEach { mangaDir ->
          val seriesJson = mangaDir.resolve("series.json")
          if (seriesJson.exists()) {
            try {
              if (seriesJson.readText().contains(mangaId)) {
                return mangaDir
              }
            } catch (_: Exception) {
            }
          }
        }
      } catch (_: Exception) {
      }
    }
    return null
  }

  private fun countBlacklistedChapters(mangaId: String): Int {
    try {
      val folder = findMangaFolder(mangaId) ?: return 0
      libraryRepository.findAll().forEach { library ->
        if (folder.absolutePath.startsWith(library.path.toFile().absolutePath)) {
          val folderUrl = folder.toURI().toURL()
          val series = seriesRepository.findNotDeletedByLibraryIdAndUrlOrNull(library.id, folderUrl)
          if (series != null) {
            val count = blacklistedChapterRepository.findBySeriesId(series.id).size
            logger.debug { "Blacklist for $mangaId: folder=${folder.name}, series=${series.id}, count=$count" }
            return count
          }
          logger.debug { "No series found for $mangaId at URL $folderUrl in library ${library.id}" }
        }
      }
    } catch (e: Exception) {
      logger.debug { "Error counting blacklisted chapters for $mangaId: ${e.message}" }
    }
    return 0
  }
}

class GalleryDlAggregateFetchException(
  message: String,
) : IllegalStateException(message)
