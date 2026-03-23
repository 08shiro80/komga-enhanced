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

    val libraries = libraryRepository.findAll()
    val folderIndex = buildFolderIndex(libraries)

    val executor = Executors.newFixedThreadPool(5)
    val results: List<ChapterCheckResult>
    try {
      val futures =
        urls.map { url ->
          CompletableFuture.supplyAsync(
            {
              try {
                concurrencyLimit.acquire()
                try {
                  checkSingleUrl(url, folderIndex, libraries)
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
      results = futures.map { it.join() }
    } finally {
      executor.shutdown()
      if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
        logger.warn { "Chapter check executor timed out, forcing shutdown" }
        executor.shutdownNow()
      }
    }

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

  private fun checkSingleUrl(
    url: String,
    folderIndex: Map<String, java.io.File>,
    libraries: Collection<org.gotson.komga.domain.model.Library>,
  ): ChapterCheckResult {
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

      val mangaFolder = folderIndex[mangaId]
      val series = findSeriesForManga(mangaId, mangaFolder, libraries)
      val knownChapterIds = getKnownChapterIds(series)
      val blacklistedChapterIds = getBlacklistedChapterIds(series)
      val allKnownIds = knownChapterIds + blacklistedChapterIds
      val missingIds = apiChapterIds - allKnownIds
      val filesystemCount = countFilesystemChapters(mangaFolder)

      val needsDownload = missingIds.isNotEmpty()

      if (needsDownload) {
        logger.info {
          "Chapter check for ${title ?: mangaId}: api=${apiChapterIds.size}, " +
            "db=${knownChapterIds.size}, blacklisted=${blacklistedChapterIds.size}, " +
            "fs=$filesystemCount, missing=${missingIds.size}"
        }
      } else {
        logger.debug {
          "Up to date: ${title ?: mangaId} (${allKnownIds.size}/${apiChapterIds.size})"
        }
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

  private fun findSeriesForManga(
    mangaId: String,
    folder: java.io.File?,
    libraries: Collection<org.gotson.komga.domain.model.Library>,
  ): org.gotson.komga.domain.model.Series? {
    val byUuid = seriesRepository.findByMangaDexUuid(mangaId)
    if (byUuid != null) return byUuid

    if (folder == null) return null
    libraries.forEach { library ->
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

  private fun countFilesystemChapters(folder: java.io.File?): Int {
    if (folder == null) return 0
    return folder
      .listFiles()
      ?.count { it.isFile && it.extension.lowercase() == "cbz" }
      ?: 0
  }

  private fun buildFolderIndex(libraries: Collection<org.gotson.komga.domain.model.Library>): Map<String, java.io.File> {
    val index = mutableMapOf<String, java.io.File>()
    val uuidRegex = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    libraries.forEach { library ->
      val libraryDir = library.path.toFile()
      if (!libraryDir.exists()) return@forEach
      libraryDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        if (uuidRegex.matches(dir.name)) {
          index[dir.name] = dir
        }
        val seriesJson = dir.resolve("series.json")
        if (seriesJson.exists()) {
          try {
            val content = seriesJson.readText()
            uuidRegex.find(content)?.value?.let { uuid -> index.putIfAbsent(uuid, dir) }
          } catch (e: Exception) {
            logger.debug(e) { "Failed to read series.json in ${dir.name}" }
          }
        }
      }
    }
    logger.debug { "Built folder index with ${index.size} entries" }
    return index
  }

  companion object {
    private val CHAPTER_ID_REGEX = Regex("mangadex\\.org/chapter/([0-9a-f-]+)")
  }
}

class GalleryDlAggregateFetchException(
  message: String,
) : IllegalStateException(message)
