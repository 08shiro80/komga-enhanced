package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DownloadStatus
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.DownloadQueueRepository
import org.gotson.komga.domain.persistence.FollowConfigRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
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
  private val downloadQueueRepository: DownloadQueueRepository,
  private val downloadExecutor: DownloadExecutor,
  private val libraryRepository: LibraryRepository,
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
      val aggregateResult = fetchMangaDexAggregate(mangaId)
      val apiChapterCount = aggregateResult.chapterCount
      val title = aggregateResult.title

      val downloadedCount = countDownloadedChapters(url, mangaId)
      val filesystemCount = countFilesystemChapters(url)

      val knownCount = maxOf(downloadedCount, filesystemCount)
      val newChaptersEstimate = maxOf(0, apiChapterCount - knownCount)
      val needsDownload = newChaptersEstimate > 0

      if (needsDownload) {
        logger.info { "New chapters detected for ${title ?: mangaId}: $apiChapterCount available, $knownCount downloaded ($newChaptersEstimate new)" }
      } else {
        logger.debug { "Up to date: ${title ?: mangaId} ($knownCount/$apiChapterCount)" }
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

  private data class AggregateResult(
    val chapterCount: Int,
    val title: String?,
  )

  private fun fetchMangaDexAggregate(mangaId: String): AggregateResult {
    val aggregateUrl =
      "https://api.mangadex.org/manga/$mangaId/aggregate?translatedLanguage[]=en"

    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(aggregateUrl))
        .timeout(Duration.ofSeconds(15))
        .GET()
        .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
      throw GalleryDlAggregateFetchException(
        "MangaDex aggregate API returned ${response.statusCode()} for manga $mangaId",
      )
    }

    val json = objectMapper.readValue<Map<String, Any?>>(response.body())
    var chapterCount = 0

    @Suppress("UNCHECKED_CAST")
    val volumes = json["volumes"] as? Map<String, Any?> ?: emptyMap()
    volumes.values.forEach { volumeData ->
      if (volumeData is Map<*, *>) {
        @Suppress("UNCHECKED_CAST")
        val chapters = volumeData["chapters"] as? Map<String, Any?> ?: emptyMap()
        chapterCount += chapters.size
      }
    }

    val title = fetchMangaTitle(mangaId)

    return AggregateResult(chapterCount = chapterCount, title = title)
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
    val libraries = libraryRepository.findAll()
    var totalCbzCount = 0

    val mangaId = GalleryDlWrapper.extractMangaDexId(url) ?: return 0

    libraries.forEach { library ->
      try {
        val libraryDir = library.path.toFile()
        if (!libraryDir.exists()) return@forEach

        libraryDir.listFiles()?.filter { it.isDirectory }?.forEach { mangaDir ->
          val seriesJson = mangaDir.resolve("series.json")
          if (seriesJson.exists()) {
            try {
              val content = seriesJson.readText()
              if (content.contains(mangaId)) {
                val cbzCount =
                  mangaDir
                    .listFiles()
                    ?.count { it.isFile && it.extension.lowercase() == "cbz" }
                    ?: 0
                totalCbzCount += cbzCount
              }
            } catch (_: Exception) {
            }
          }
        }
      } catch (_: Exception) {
      }
    }

    return totalCbzCount
  }
}

class GalleryDlAggregateFetchException(
  message: String,
) : IllegalStateException(message)
