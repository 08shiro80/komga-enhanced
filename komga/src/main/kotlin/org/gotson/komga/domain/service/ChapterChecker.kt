package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.persistence.BlacklistedChapterRepository
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.infrastructure.download.ChapterMatcher
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.gotson.komga.infrastructure.download.MangaDexApiClient
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

data class DeletedChapterScanResult(
  val seriesScanned: Int,
  val entriesRemoved: Int,
  val totalSeries: Int,
  val details: List<DeletedChapterDetail> = emptyList(),
)

data class DeletedChapterDetail(
  val seriesName: String,
  val removedCount: Int,
  val remainingCount: Int,
  val cbzFileCount: Int,
)

data class ChapterCheckResult(
  val url: String,
  val mangaId: String?,
  val title: String?,
  val libraryId: String? = null,
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
  private val chapterUrlRepository: ChapterUrlRepository,
  private val blacklistedChapterRepository: BlacklistedChapterRepository,
  private val libraryRepository: LibraryRepository,
  private val seriesRepository: org.gotson.komga.domain.persistence.SeriesRepository,
  private val galleryDlWrapper: GalleryDlWrapper,
  private val chapterMatcher: ChapterMatcher,
  private val mangaDexApiClient: MangaDexApiClient,
) {
  // Shared across calls: the 5-thread pool itself bounds concurrency to 5 in-flight checks
  // (no separate semaphore needed). Reused instead of created-per-call to avoid thread churn.
  private val executor = Executors.newFixedThreadPool(5)

  @PreDestroy
  fun shutdown() {
    executor.shutdown()
    if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
      logger.warn { "Chapter check executor timed out, forcing shutdown" }
      executor.shutdownNow()
    }
  }

  fun checkUrls(urls: List<String>): ChapterCheckSummary {
    val startTime = System.currentTimeMillis()
    logger.info { "Starting chapter check for ${urls.size} manga URLs" }

    val libraries = libraryRepository.findAll()
    val folderIndex = buildFolderIndex(libraries)

    val futures =
      urls.map { url ->
        CompletableFuture.supplyAsync(
          { checkSingleUrl(url, folderIndex, libraries) },
          executor,
        )
      }
    val results = futures.map { it.join() }

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

  private fun checkSingleUrl(
    url: String,
    folderIndex: Map<String, java.io.File>,
    libraries: Collection<org.gotson.komga.domain.model.Library>,
  ): ChapterCheckResult {
    val mangaId = GalleryDlWrapper.extractMangaDexId(url)
    if (mangaId == null) return checkNonMangaDexUrl(url)

    try {
      val chapters = galleryDlWrapper.getChaptersForManga(mangaId)
      val apiChapterIds = chapters.mapNotNull { it.chapterId }.toSet()
      val mangaInfo = galleryDlWrapper.getMangaMetadata(mangaId)
      val title = mangaInfo?.title

      val mangaFolder = folderIndex[mangaId]
      val series = findSeriesForManga(mangaId, mangaFolder, libraries)
      val libraryId =
        series?.libraryId
          ?: mangaFolder?.let { folder ->
            libraries
              .firstOrNull { lib ->
                folder.absolutePath.startsWith(lib.path.toFile().absolutePath)
              }?.id
          }
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
        libraryId = libraryId,
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

  // For non-MangaDex sources, run gallery-dl --simulate (fetchGalleryDlChapterMapping) to enumerate
  // the available chapter URLs, then check which are missing from CHAPTER_URL (populated by the
  // download flow). Only genuinely new chapters trigger a download. Falls back to unconditional
  // queuing when the simulate returns nothing or errors, so network issues never silently drop chapters.
  private fun checkNonMangaDexUrl(url: String): ChapterCheckResult {
    return try {
      val chapters = galleryDlWrapper.fetchGalleryDlChapterMapping(url)
      if (chapters.isEmpty()) {
        return ChapterCheckResult(
          url = url,
          mangaId = null,
          title = null,
          apiChapterCount = 0,
          downloadedChapterCount = 0,
          filesystemChapterCount = 0,
          newChaptersEstimate = 0,
          needsDownload = true,
        )
      }
      val existence = chapterUrlRepository.existsByUrls(chapters.keys)
      val downloadedCount = existence.values.count { it }
      val missingCount = existence.values.count { !it }
      logger.debug { "Non-MangaDex check for $url: total=${chapters.size}, downloaded=$downloadedCount, missing=$missingCount" }
      ChapterCheckResult(
        url = url,
        mangaId = null,
        title = null,
        apiChapterCount = chapters.size,
        downloadedChapterCount = downloadedCount,
        filesystemChapterCount = 0,
        newChaptersEstimate = missingCount,
        needsDownload = missingCount > 0,
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to check non-MangaDex URL $url, will attempt download" }
      ChapterCheckResult(
        url = url,
        mangaId = null,
        title = null,
        apiChapterCount = 0,
        downloadedChapterCount = 0,
        filesystemChapterCount = 0,
        newChaptersEstimate = 0,
        needsDownload = true,
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
            logger.warn(e) { "Failed to read series.json in ${dir.name}" }
          }
        }
      }
    }
    logger.debug { "Built folder index with ${index.size} entries" }
    return index
  }

  fun scanDeletedChaptersForLibrary(
    libraryId: String,
    dryRun: Boolean = false,
    limit: Int? = null,
    offset: Int = 0,
  ): DeletedChapterScanResult {
    val allSeries = seriesRepository.findAllByLibraryId(libraryId)

    // Availability guard: if the library root is missing or unreadable (detached mount, IO/permission
    // error), abort the scan instead of letting every series folder look "deleted" — a transient unmount
    // would otherwise wipe all CHAPTER_URL tracking rows and trigger mass false re-downloads.
    if (isLibraryRootUnavailable(libraryId)) {
      logger.warn { "Deleted chapters scan aborted: library $libraryId root missing or unreadable — nothing removed" }
      return DeletedChapterScanResult(0, 0, allSeries.size, emptyList())
    }

    val window =
      if (limit != null) {
        allSeries.drop(offset).take(limit)
      } else {
        allSeries
      }
    var seriesScanned = 0
    var totalRemoved = 0
    val details = mutableListOf<DeletedChapterDetail>()

    window.forEach { series ->
      val outcome = reconcileSeries(series, dryRun)
      if (outcome.scanned) seriesScanned++
      totalRemoved += outcome.removed
      outcome.detail?.let { details.add(it) }
    }

    val verb = if (dryRun) "would remove" else "removed"
    logger.info {
      "Deleted chapters scan complete${if (dryRun) " (dry-run)" else ""}: scanned $seriesScanned series " +
        "(window offset=$offset limit=${limit ?: "all"} of ${allSeries.size}), $verb $totalRemoved entries"
    }
    return DeletedChapterScanResult(seriesScanned, totalRemoved, allSeries.size, details)
  }

  fun scanDeletedChaptersForSeries(
    seriesId: String,
    dryRun: Boolean = false,
  ): DeletedChapterScanResult {
    val series =
      seriesRepository.findByIdOrNull(seriesId)
        ?: return DeletedChapterScanResult(0, 0, 0, emptyList())

    if (isLibraryRootUnavailable(series.libraryId)) {
      logger.warn { "Deleted chapters scan aborted: library ${series.libraryId} root missing or unreadable — nothing removed" }
      return DeletedChapterScanResult(0, 0, 1, emptyList())
    }

    val outcome = reconcileSeries(series, dryRun)
    return DeletedChapterScanResult(
      seriesScanned = if (outcome.scanned) 1 else 0,
      entriesRemoved = outcome.removed,
      totalSeries = 1,
      details = listOfNotNull(outcome.detail),
    )
  }

  private fun isLibraryRootUnavailable(libraryId: String): Boolean {
    val libraryDir = libraryRepository.findByIdOrNull(libraryId)?.path?.toFile()
    return libraryDir == null || !libraryDir.exists() || libraryDir.listFiles() == null
  }

  private data class ReconcileOutcome(
    val scanned: Boolean,
    val removed: Int,
    val detail: DeletedChapterDetail?,
  )

  private fun reconcileSeries(
    series: Series,
    dryRun: Boolean,
  ): ReconcileOutcome {
    val chapterUrls = chapterUrlRepository.findBySeriesId(series.id)
    if (chapterUrls.isEmpty()) return ReconcileOutcome(false, 0, null)

    val verb = if (dryRun) "would remove" else "removed"
    val seriesDir = series.path.toFile()

    if (!seriesDir.exists()) {
      val count = chapterUrls.size
      if (!dryRun) chapterUrlRepository.deleteBySeriesId(series.id)
      logger.info { "Deleted chapters scan: folder missing for '${series.name}', $verb $count entries" }
      return ReconcileOutcome(true, count, DeletedChapterDetail(series.name, count, 0, 0))
    }

    val files = seriesDir.listFiles()
    if (files == null) {
      logger.info { "Deleted chapters scan: cannot list files for '${series.name}' (IO error/permission) — skipping (unverifiable)" }
      return ReconcileOutcome(true, 0, null)
    }
    val cbzCount = files.count { it.isFile && it.extension.lowercase() == "cbz" }

    if (cbzCount == 0) {
      val count = chapterUrls.size
      if (!dryRun) chapterUrlRepository.deleteBySeriesId(series.id)
      logger.info { "Deleted chapters scan: no CBZ files in '${series.name}', $verb $count entries" }
      return ReconcileOutcome(true, count, DeletedChapterDetail(series.name, count, 0, 0))
    }

    val perFileUrls = chapterMatcher.extractChapterUrlsPerCbzFile(seriesDir)
    // Safety guard against false positives: only trust the "stale" verdict when EVERY CBZ carries at least
    // one identifiable chapter URL. If any CBZ embeds no recognizable per-chapter URL (e.g. imported/non-
    // MangaDex files whose ComicInfo carries only a manga-level <Web>), we CANNOT conclude the tracked URLs
    // are orphaned, and deleting them would trigger mass false re-downloads. Skip such series (unverifiable).
    // A per-file check is required: an aggregate URL-count vs file-count comparison lets one CBZ with two
    // URLs mask another with none.
    if (perFileUrls.size < cbzCount || perFileUrls.any { it.isEmpty() }) {
      val identifiable = perFileUrls.count { it.isNotEmpty() }
      logger.info { "Deleted chapters scan: skipping '${series.name}' — only $identifiable of $cbzCount CBZs carry identifiable chapter URLs (cannot verify)" }
      return ReconcileOutcome(true, 0, null)
    }
    val existingUrls = perFileUrls.flatMapTo(mutableSetOf()) { it }
    val staleEntries = chapterUrls.filter { it.url !in existingUrls }
    if (staleEntries.isEmpty()) return ReconcileOutcome(true, 0, null)

    // Failsafe: only remove a stale entry when its source chapter STILL EXISTS and can actually be
    // re-downloaded. If the MangaDex chapter is gone (404), unverifiable (network error) or not a MangaDex
    // URL, KEEP the tracking row — deleting it would trigger no successful re-download and only lose the
    // "we had this" record. A chapter that still resolves but has no hosted pages (pages==0 && externalUrl
    // != null, external redirect) is likewise NOT re-downloadable → keep it (consistent with the auto-
    // blacklist rule). On a MangaDex outage every check returns null → nothing is deleted (safe by default).
    val deletable =
      staleEntries.filter { entry ->
        val chapterId = entry.url.substringAfterLast("/chapter/", "")
        val metadata = if (chapterId.isNotBlank()) mangaDexApiClient.fetchChapterMetadata(chapterId) else null
        metadata != null && !(metadata.pages == 0 && metadata.externalUrl != null)
      }
    val kept = staleEntries.size - deletable.size
    if (deletable.isEmpty()) {
      if (kept > 0) {
        logger.info { "Deleted chapters scan: '${series.name}' — $kept stale entries KEPT (source gone/unverifiable, not re-downloadable)" }
      }
      return ReconcileOutcome(true, 0, null)
    }

    if (!dryRun) deletable.forEach { chapterUrlRepository.delete(it.id) }
    val remaining = chapterUrls.size - deletable.size
    logger.info {
      "Deleted chapters scan: '${series.name}' had ${chapterUrls.size} DB entries, $cbzCount files, $verb ${deletable.size} stale entries" +
        if (kept > 0) " ($kept KEPT — source gone/unverifiable)" else ""
    }
    return ReconcileOutcome(true, deletable.size, DeletedChapterDetail(series.name, deletable.size, remaining, cbzCount))
  }

  companion object {
    private val CHAPTER_ID_REGEX = Regex("mangadex\\.org/chapter/([0-9a-f-]+)")
  }
}

class GalleryDlAggregateFetchException(
  message: String,
) : IllegalStateException(message)
