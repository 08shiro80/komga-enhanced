package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DownloadStatus
import org.gotson.komga.domain.persistence.BlacklistedChapterRepository
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.DownloadQueueRepository
import org.gotson.komga.domain.persistence.FollowConfigRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.springframework.stereotype.Service
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
  private val galleryDlWrapper: GalleryDlWrapper,
) {
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
      val chapters = galleryDlWrapper.getChaptersForManga(mangaId)
      val apiChapterIds = chapters.map { it.chapterId }.toSet()
      val mangaInfo = galleryDlWrapper.getMangaMetadata(mangaId)
      val title = mangaInfo?.title

      val series = findSeriesForManga(mangaId)
      val knownChapterIds = getKnownChapterIds(series)
      val blacklistedChapterIds = getBlacklistedChapterIds(series)
      val allKnownIds = knownChapterIds + blacklistedChapterIds
      val missingIds = apiChapterIds - allKnownIds
      val filesystemCount = countFilesystemChapters(url)

      logger.info {
        "Chapter check for ${title ?: mangaId}: api=${apiChapterIds.size}, " +
          "db=${knownChapterIds.size}, blacklisted=${blacklistedChapterIds.size}, " +
          "fs=$filesystemCount, missing=${missingIds.size}"
      }

      val needsDownload = missingIds.isNotEmpty()

      if (needsDownload) {
        logger.info { "New chapters detected for ${title ?: mangaId}: ${missingIds.size} unknown chapter IDs" }
      } else {
        logger.info { "Up to date: ${title ?: mangaId} (${allKnownIds.size}/${apiChapterIds.size})" }
      }

      return ChapterCheckResult(
        url = url,
        mangaId = mangaId,
        title = title,
        apiChapterCount = apiChapterIds.size,
        downloadedChapterCount = knownChapterIds.size,
        filesystemChapterCount = filesystemCount,
        newChaptersEstimate = missingIds.size,
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

  private fun findSeriesForManga(mangaId: String): org.gotson.komga.domain.model.Series? {
    val byUuid = seriesRepository.findByMangaDexUuid(mangaId)
    if (byUuid != null) return byUuid

    val folder = findMangaFolder(mangaId) ?: return null
    libraryRepository.findAll().forEach { library ->
      if (folder.absolutePath.startsWith(library.path.toFile().absolutePath)) {
        val folderUrl = folder.toURI().toURL()
        return seriesRepository.findNotDeletedByLibraryIdAndUrlOrNull(library.id, folderUrl)
      }
    }
    return null
  }

  private fun extractChapterIdFromUrl(url: String): String? = CHAPTER_ID_REGEX.find(url)?.groupValues?.get(1)

  private fun getKnownChapterIds(series: org.gotson.komga.domain.model.Series?): Set<String> {
    if (series == null) return emptySet()
    val chapterUrls = chapterUrlRepository.findBySeriesId(series.id)
    return chapterUrls
      .mapNotNull { it.chapterId ?: extractChapterIdFromUrl(it.url) }
      .toSet()
  }

  private fun getBlacklistedChapterIds(series: org.gotson.komga.domain.model.Series?): Set<String> {
    if (series == null) return emptySet()
    val blacklisted = blacklistedChapterRepository.findUrlsBySeriesId(series.id)
    return blacklisted
      .mapNotNull { extractChapterIdFromUrl(it) }
      .toSet()
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

  companion object {
    private val CHAPTER_ID_REGEX = Regex("mangadex\\.org/chapter/([0-9a-f-]+)")
  }
}

class GalleryDlAggregateFetchException(
  message: String,
) : IllegalStateException(message)
