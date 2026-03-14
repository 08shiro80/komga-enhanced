package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.DownloadQueue
import org.gotson.komga.domain.model.DownloadStatus
import org.gotson.komga.domain.model.SourceType
import org.gotson.komga.domain.persistence.DownloadQueueRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.gotson.komga.interfaces.api.websocket.DownloadProgressDto
import org.gotson.komga.interfaces.api.websocket.DownloadProgressHandler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

private data class ActiveDownload(
  val download: DownloadQueue,
  var process: Process? = null,
)

@Service
class DownloadExecutor(
  private val downloadQueueRepository: DownloadQueueRepository,
  private val libraryRepository: LibraryRepository,
  private val seriesRepository: org.gotson.komga.domain.persistence.SeriesRepository,
  private val seriesMetadataRepository: org.gotson.komga.domain.persistence.SeriesMetadataRepository,
  private val galleryDlWrapper: GalleryDlWrapper,
  private val libraryLifecycle: LibraryLifecycle,
  private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
  private val downloadProgressHandler: DownloadProgressHandler,
  private val eventPublisher: ApplicationEventPublisher,
) {
  private val processing = AtomicBoolean(false)
  private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()
  private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
  private val downloadThread =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "download-worker").apply { isDaemon = true }
    }

  @Scheduled(fixedDelay = 10000, initialDelay = 10000)
  fun processQueue() {
    if (!processing.compareAndSet(false, true)) {
      logger.debug { "Download processing already in progress, skipping" }
      return
    }

    try {
      if (!galleryDlWrapper.isInstalled()) {
        logger.warn { "gallery-dl is not installed, skipping download processing" }
        processing.set(false)
        return
      }

      val pending = downloadQueueRepository.findPendingOrdered()

      if (pending.isEmpty()) {
        logger.debug { "No pending downloads" }
        processing.set(false)
        return
      }

      val download = pending.first()

      if (activeDownloads.containsKey(download.id)) {
        logger.debug { "Download ${download.id} already being processed" }
        processing.set(false)
        return
      }

      logger.info { "Processing download: ${download.id} - ${download.sourceUrl}" }
      downloadThread.submit {
        try {
          processDownload(download)
        } catch (e: Exception) {
          logger.error(e) { "Error processing download queue" }
        } finally {
          processing.set(false)
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "Error processing download queue" }
      processing.set(false)
    }
  }

  @Scheduled(fixedDelay = 300000, initialDelay = 60000)
  fun autoRetryFailedDownloads() {
    try {
      val failed = downloadQueueRepository.findByStatus(DownloadStatus.FAILED)
      val retriable = failed.filter { it.canRetry() }

      if (retriable.isEmpty()) {
        logger.debug { "No failed downloads to auto-retry" }
        return
      }

      val now = LocalDateTime.now()
      val toRetry =
        retriable.filter { download ->
          val waitMinutes = (download.retryCount + 1) * 5L
          val lastModified = download.lastModifiedDate
          java.time.Duration
            .between(lastModified, now)
            .toMinutes() >= waitMinutes
        }

      if (toRetry.isEmpty()) {
        logger.debug { "No failed downloads ready for retry (in backoff period)" }
        return
      }

      logger.info { "Auto-retrying ${toRetry.size} failed downloads" }

      toRetry.forEach { download ->
        try {
          retryDownload(download.id)
          logger.info { "Auto-retry queued: ${download.id} - ${download.title} (attempt ${download.retryCount + 1})" }

          downloadProgressHandler.broadcastProgress(
            DownloadProgressDto(
              type = "retry",
              downloadId = download.id,
              mangaTitle = download.title,
              url = download.sourceUrl,
              status = "PENDING",
              currentChapter = null,
              totalChapters = download.totalChapters,
              completedChapters = null,
              filesDownloaded = 0,
              percentage = 0,
              error = "Auto-retrying (attempt ${download.retryCount + 1}/${download.maxRetries})",
            ),
          )
        } catch (e: Exception) {
          logger.warn(e) { "Failed to auto-retry download ${download.id}" }
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "Error in auto-retry job" }
    }
  }

  fun createDownload(
    sourceUrl: String,
    libraryId: String?,
    title: String?,
    createdBy: String,
    priority: Int = 5,
  ): DownloadQueue {
    if (libraryId != null) {
      libraryRepository.findByIdOrNull(libraryId)
        ?: throw IllegalArgumentException("Library not found: $libraryId")
    }

    val mangaInfo =
      try {
        galleryDlWrapper.getChapterInfo(sourceUrl)
      } catch (e: Exception) {
        logger.warn(e) { "Could not fetch manga info for $sourceUrl" }
        null
      }

    val download =
      DownloadQueue(
        id = UUID.randomUUID().toString(),
        sourceUrl = sourceUrl,
        sourceType = SourceType.MANGA_SITE,
        title = title ?: mangaInfo?.title ?: "Unknown",
        author = mangaInfo?.author,
        status = DownloadStatus.PENDING,
        progressPercent = 0,
        currentChapter = null,
        totalChapters = mangaInfo?.totalChapters,
        libraryId = libraryId,
        destinationPath = null,
        errorMessage = null,
        pluginId = "gallery-dl-downloader",
        metadataJson = null,
        createdBy = createdBy,
        startedDate = null,
        completedDate = null,
        priority = priority,
        retryCount = 0,
        maxRetries = 3,
        createdDate = LocalDateTime.now(),
        lastModifiedDate = LocalDateTime.now(),
      )

    downloadQueueRepository.insert(download)
    logger.info { "Created download: ${download.id} - ${download.title}" }

    return download
  }

  fun cancelDownload(downloadId: String) {
    val download =
      downloadQueueRepository.findByIdOrNull(downloadId)
        ?: throw IllegalArgumentException("Download not found: $downloadId")

    downloadQueueRepository.update(
      download.copy(
        status = DownloadStatus.CANCELLED,
        lastModifiedDate = LocalDateTime.now(),
      ),
    )

    cancelledIds.add(downloadId)
    activeDownloads.remove(downloadId)?.let { active ->
      active.process?.let { proc ->
        logger.info { "Killing gallery-dl subprocess for download $downloadId (pid=${proc.pid()})" }
        proc.destroyForcibly()
      }
    }
    logger.info { "Cancelled download: $downloadId" }
  }

  fun retryDownload(downloadId: String) {
    val download =
      downloadQueueRepository.findByIdOrNull(downloadId)
        ?: throw IllegalArgumentException("Download not found: $downloadId")

    if (!download.canRetry()) {
      throw IllegalStateException("Download cannot be retried (max retries reached)")
    }

    downloadQueueRepository.update(
      download.copy(
        status = DownloadStatus.PENDING,
        retryCount = download.retryCount + 1,
        errorMessage = null,
        lastModifiedDate = LocalDateTime.now(),
      ),
    )

    logger.info { "Retrying download: $downloadId (attempt ${download.retryCount + 1})" }
  }

  fun resumeDownload(downloadId: String) {
    val download =
      downloadQueueRepository.findByIdOrNull(downloadId)
        ?: throw IllegalArgumentException("Download not found: $downloadId")

    if (download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.PENDING) {
      throw IllegalStateException("Download is already ${download.status.name.lowercase()}")
    }

    cancelledIds.remove(downloadId)

    downloadQueueRepository.update(
      download.copy(
        status = DownloadStatus.PENDING,
        errorMessage = null,
        lastModifiedDate = LocalDateTime.now(),
      ),
    )

    logger.info { "Resumed download: $downloadId - ${download.title}" }
  }

  fun deleteDownload(downloadId: String) {
    downloadQueueRepository.delete(downloadId)
    cancelledIds.add(downloadId)
    activeDownloads.remove(downloadId)?.let { active ->
      active.process?.let { proc ->
        logger.info { "Killing gallery-dl subprocess for deleted download $downloadId (pid=${proc.pid()})" }
        proc.destroyForcibly()
      }
    }
    logger.info { "Deleted download: $downloadId" }
  }

  fun clearDownloadsByStatus(status: DownloadStatus): Int {
    val count = downloadQueueRepository.deleteByStatus(status)
    logger.info { "Cleared $count downloads with status: $status" }
    return count
  }

  fun clearCompletedDownloads(): Int = clearDownloadsByStatus(DownloadStatus.COMPLETED)

  fun clearFailedDownloads(): Int = clearDownloadsByStatus(DownloadStatus.FAILED)

  fun clearCancelledDownloads(): Int = clearDownloadsByStatus(DownloadStatus.CANCELLED)

  fun clearPendingDownloads(): Int = clearDownloadsByStatus(DownloadStatus.PENDING)

  fun processFollowList(
    followListPath: java.nio.file.Path,
    libraryId: String?,
  ) {
    val file = followListPath.toFile()
    if (!file.exists()) {
      logger.debug { "Follow list not found: $followListPath" }
      return
    }

    try {
      val urls =
        file
          .readLines()
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") }

      logger.info { "Processing follow list: ${urls.size} URLs" }

      val activeStatuses = listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED)
      urls.forEach { url ->
        try {
          if (downloadQueueRepository.existsBySourceUrlAndStatusIn(url, activeStatuses)) {
            logger.debug { "Skipping duplicate URL already in queue: $url" }
            return@forEach
          }
          createDownload(
            sourceUrl = url,
            libraryId = libraryId,
            title = null,
            createdBy = "follow-list",
            priority = 5,
          )
          logger.info { "Added to queue from follow list: $url" }
        } catch (e: Exception) {
          logger.warn(e) { "Failed to add URL from follow list: $url" }
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "Error processing follow list: $followListPath" }
    }
  }

  fun isUrlAlreadyQueued(url: String): Boolean =
    downloadQueueRepository.existsBySourceUrlAndStatusIn(
      url,
      listOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED),
    )

  fun setActiveProcess(
    downloadId: String,
    process: Process,
  ) {
    activeDownloads[downloadId]?.process = process
  }

  private fun processDownload(download: DownloadQueue) {
    if (cancelledIds.remove(download.id)) {
      logger.info { "Download ${download.id} was cancelled before processing started, skipping" }
      return
    }
    activeDownloads[download.id] = ActiveDownload(download)

    try {
      updateDownloadStatus(download, DownloadStatus.DOWNLOADING, startedDate = LocalDateTime.now())

      downloadProgressHandler.broadcastProgress(
        DownloadProgressDto(
          type = "started",
          downloadId = download.id,
          mangaTitle = download.title,
          url = download.sourceUrl,
          status = "DOWNLOADING",
          currentChapter = null,
          totalChapters = download.totalChapters,
          completedChapters = 0,
          filesDownloaded = 0,
          percentage = 0,
          error = null,
        ),
      )
      eventPublisher.publishEvent(
        DomainEvent.DownloadStarted(
          downloadId = download.id,
          title = download.title,
          sourceUrl = download.sourceUrl,
          libraryId = download.libraryId,
          totalChapters = download.totalChapters,
        ),
      )

      val library =
        if (download.libraryId != null) {
          libraryRepository.findByIdOrNull(download.libraryId)
        } else {
          null
        }

      val libraryPath =
        if (library != null) {
          library.path
        } else {
          Paths.get(System.getProperty("user.home"), "Downloads", "komga")
        }

      val mangaDexId = GalleryDlWrapper.extractMangaDexId(download.sourceUrl)

      val destinationPath: java.nio.file.Path
      val komgaSeriesId: String?

      if (mangaDexId != null) {
        destinationPath = libraryPath.resolve(mangaDexId)
        komgaSeriesId =
          if (download.libraryId != null) {
            findExistingMangaFolder(download.libraryId, libraryPath, mangaDexId)?.komgaSeriesId
          } else {
            null
          }
      } else {
        destinationPath = libraryPath.resolve(sanitizeFileName(download.title ?: "Unknown"))
        komgaSeriesId = null
      }

      if (!destinationPath.toFile().exists()) {
        destinationPath.toFile().mkdirs()
        logger.info { "Created manga folder: $destinationPath" }
      }

      logger.info { "Starting download to: $destinationPath" }

      if (mangaDexId != null) {
        val existingCbzCount =
          destinationPath
            .toFile()
            .listFiles()
            ?.count { it.isFile && it.extension.lowercase() == "cbz" }
            ?: 0
        val apiChapterCount = galleryDlWrapper.getMangaDexChapterCount(mangaDexId)
        if (apiChapterCount != null && apiChapterCount > 0 && existingCbzCount >= apiChapterCount) {
          logger.info { "Pre-check: ${download.title} has $existingCbzCount CBZs, API reports $apiChapterCount chapters — skipping download" }
          updateDownloadStatus(
            download,
            DownloadStatus.COMPLETED,
            completedDate = LocalDateTime.now(),
            progressPercent = 100,
            destinationPath = destinationPath.toString(),
          )
          downloadProgressHandler.broadcastProgress(
            DownloadProgressDto(
              type = "completed",
              downloadId = download.id,
              mangaTitle = download.title,
              url = download.sourceUrl,
              status = "COMPLETED",
              currentChapter = null,
              totalChapters = apiChapterCount,
              completedChapters = apiChapterCount,
              filesDownloaded = 0,
              percentage = 100,
              error = null,
            ),
          )
          return
        }
      }

      val isCancelled = { cancelledIds.contains(download.id) }

      val result =
        galleryDlWrapper.download(
          url = download.sourceUrl,
          destinationPath = destinationPath,
          libraryPath = library?.path,
          komgaSeriesId = komgaSeriesId,
          isCancelled = isCancelled,
          onProcessStarted = { process -> setActiveProcess(download.id, process) },
        ) { progress ->
          if (isCancelled()) {
            logger.info { "Download ${download.id} cancelled during processing, aborting" }
            cancelledIds.remove(download.id)
            throw InterruptedException("Download cancelled: ${download.id}")
          }
          downloadQueueRepository.update(
            download.copy(
              progressPercent = progress.percent,
              currentChapter = progress.currentChapter,
              totalChapters = if (progress.totalChapters > 0) progress.totalChapters else download.totalChapters,
              lastModifiedDate = LocalDateTime.now(),
            ),
          )

          downloadProgressHandler.broadcastProgress(
            DownloadProgressDto(
              type = "progress",
              downloadId = download.id,
              mangaTitle = download.title,
              url = download.sourceUrl,
              status = "DOWNLOADING",
              currentChapter = progress.currentChapter.toString(),
              totalChapters = if (progress.totalChapters > 0) progress.totalChapters else download.totalChapters,
              completedChapters = progress.currentChapter,
              filesDownloaded = progress.currentChapter,
              percentage = progress.percent,
              error = null,
            ),
          )
          eventPublisher.publishEvent(
            DomainEvent.DownloadProgress(
              downloadId = download.id,
              title = download.title,
              status = "DOWNLOADING",
              progressPercent = progress.percent,
              currentChapter = progress.currentChapter,
              totalChapters = if (progress.totalChapters > 0) progress.totalChapters else download.totalChapters,
              message = progress.message,
            ),
          )
        }

      if (result.success) {
        val finalTitle = result.mangaTitle ?: download.title
        val finalPath = destinationPath

        logger.info { "Download completed to: $finalPath (manga folder: ${result.mangaTitle})" }

        updateDownloadStatus(
          download.copy(title = finalTitle),
          DownloadStatus.COMPLETED,
          completedDate = LocalDateTime.now(),
          progressPercent = 100,
          destinationPath = finalPath.toString(),
        )

        downloadProgressHandler.broadcastProgress(
          DownloadProgressDto(
            type = "completed",
            downloadId = download.id,
            mangaTitle = finalTitle ?: download.title,
            url = download.sourceUrl,
            status = "COMPLETED",
            currentChapter = null,
            totalChapters = download.totalChapters,
            completedChapters = download.totalChapters,
            filesDownloaded = result.filesDownloaded,
            percentage = 100,
            error = null,
          ),
        )
        eventPublisher.publishEvent(
          DomainEvent.DownloadCompleted(
            downloadId = download.id,
            title = finalTitle ?: download.title,
            libraryId = download.libraryId,
            filesDownloaded = result.filesDownloaded,
          ),
        )

        if (library != null) {
          logger.info { "Download completed for library ${library.name}. Please manually trigger library scan." }
        }

        logger.info { "Download completed: ${download.id} - ${result.filesDownloaded} files" }
      } else {
        updateDownloadStatus(
          download,
          DownloadStatus.FAILED,
          errorMessage = result.errorMessage ?: "Download failed",
        )

        downloadProgressHandler.broadcastProgress(
          DownloadProgressDto(
            type = "failed",
            downloadId = download.id,
            mangaTitle = download.title,
            url = download.sourceUrl,
            status = "FAILED",
            currentChapter = null,
            totalChapters = download.totalChapters,
            completedChapters = null,
            filesDownloaded = 0,
            percentage = null,
            error = result.errorMessage ?: "Download failed",
          ),
        )
        eventPublisher.publishEvent(
          DomainEvent.DownloadFailed(
            downloadId = download.id,
            title = download.title,
            errorMessage = result.errorMessage ?: "Download failed",
          ),
        )

        logger.error { "Download failed: ${download.id} - ${result.errorMessage}" }
      }
    } catch (e: Exception) {
      logger.error(e) { "Error processing download ${download.id}" }

      updateDownloadStatus(
        download,
        DownloadStatus.FAILED,
        errorMessage = e.message ?: "Unknown error",
      )

      downloadProgressHandler.broadcastProgress(
        DownloadProgressDto(
          type = "error",
          downloadId = download.id,
          mangaTitle = download.title,
          url = download.sourceUrl,
          status = "FAILED",
          currentChapter = null,
          totalChapters = download.totalChapters,
          completedChapters = null,
          filesDownloaded = 0,
          percentage = null,
          error = e.message ?: "Unknown error",
        ),
      )
      eventPublisher.publishEvent(
        DomainEvent.DownloadFailed(
          downloadId = download.id,
          title = download.title,
          errorMessage = e.message ?: "Unknown error",
        ),
      )
    } finally {
      activeDownloads.remove(download.id)
    }
  }

  private fun updateDownloadStatus(
    download: DownloadQueue,
    status: DownloadStatus,
    startedDate: LocalDateTime? = download.startedDate,
    completedDate: LocalDateTime? = download.completedDate,
    progressPercent: Int = download.progressPercent,
    destinationPath: String? = download.destinationPath,
    errorMessage: String? = download.errorMessage,
  ) {
    downloadQueueRepository.update(
      download.copy(
        status = status,
        startedDate = startedDate,
        completedDate = completedDate,
        progressPercent = progressPercent,
        destinationPath = destinationPath,
        errorMessage = errorMessage,
        lastModifiedDate = LocalDateTime.now(),
      ),
    )
  }

  private data class FolderLookupResult(
    val folder: java.io.File,
    val komgaSeriesId: String,
  )

  private fun findExistingMangaFolder(
    libraryId: String,
    libraryPath: java.nio.file.Path,
    mangaDexId: String,
  ): FolderLookupResult? {
    val uuidFolder = libraryPath.resolve(mangaDexId).toFile()
    if (uuidFolder.exists()) {
      val series =
        seriesRepository
          .findAllByLibraryId(libraryId)
          .firstOrNull { it.path.toFile() == uuidFolder }
      return FolderLookupResult(uuidFolder, series?.id ?: "")
    }

    val seriesId =
      seriesMetadataRepository.findSeriesIdByLinkUrlContaining(libraryId, mangaDexId)
    if (seriesId != null) {
      val series = seriesRepository.findByIdOrNull(seriesId)
      if (series != null && series.path.toFile().exists()) {
        logger.info { "findExistingMangaFolder: found via DB link: ${series.path}" }
        return FolderLookupResult(series.path.toFile(), series.id)
      }
    }

    val allSeries = seriesRepository.findAllByLibraryId(libraryId)
    val idWithSpaces = mangaDexId.replace("-", " ")
    val byPath =
      allSeries.firstOrNull { series ->
        val path = series.url.toString()
        path.contains(mangaDexId) || path.contains(idWithSpaces)
      }
    if (byPath != null && byPath.path.toFile().exists()) {
      logger.info { "findExistingMangaFolder: found by path containing UUID: ${byPath.path}" }
      return FolderLookupResult(byPath.path.toFile(), byPath.id)
    }

    val libraryDir = libraryPath.toFile()
    if (libraryDir.isDirectory) {
      val match =
        libraryDir
          .listFiles()
          ?.filter { it.isDirectory }
          ?.firstOrNull { dir ->
            val seriesJson = java.io.File(dir, "series.json")
            seriesJson.exists() &&
              try {
                val content = seriesJson.readText()
                content.contains(mangaDexId) || content.contains(idWithSpaces)
              } catch (_: Exception) {
                false
              }
          }
      if (match != null) {
        val series = allSeries.firstOrNull { it.path.toFile() == match }
        logger.info { "findExistingMangaFolder: found by series.json scan: ${match.absolutePath}" }
        return FolderLookupResult(match, series?.id ?: "")
      }
    }

    return null
  }

  fun migrateLibraryToUuidFolders(libraryId: String): MigrationResult {
    val library = libraryRepository.findByIdOrNull(libraryId) ?: return MigrationResult(0, 0)
    val libraryDir = library.path.toFile()
    if (!libraryDir.isDirectory) return MigrationResult(0, 0)

    var foldersRenamed = 0
    var cbzRenamed = 0

    val folders =
      libraryDir
        .listFiles()
        ?.filter { it.isDirectory }
        ?: return MigrationResult(0, 0)

    for (folder in folders) {
      if (MANGADEX_UUID_REGEX.matches(folder.name)) {
        cbzRenamed += migrateCbzToGalleryDlFormat(folder)
        continue
      }

      val seriesJson = java.io.File(folder, "series.json")
      if (!seriesJson.exists()) continue

      val content =
        try {
          seriesJson.readText()
        } catch (_: Exception) {
          continue
        }

      val mangaDexId = extractMangaDexIdFromSeriesJson(content) ?: continue
      val uuidFolder = java.io.File(libraryDir, mangaDexId)
      if (uuidFolder.exists()) continue

      if (folder.renameTo(uuidFolder)) {
        foldersRenamed++
        logger.info { "Migrated folder: ${folder.name} -> $mangaDexId" }
        cbzRenamed += migrateCbzToGalleryDlFormat(uuidFolder)
      } else {
        logger.warn { "Failed to migrate folder: ${folder.name}" }
      }
    }

    if (foldersRenamed > 0 || cbzRenamed > 0) {
      logger.info { "Migration complete: $foldersRenamed folders, $cbzRenamed CBZs renamed" }
    }

    return MigrationResult(foldersRenamed, cbzRenamed)
  }

  private fun migrateCbzToGalleryDlFormat(folder: java.io.File): Int {
    val chTitleGroup = Regex("""^Ch\.\s*(\d+(?:\.\d+)?)\s*-\s*.*?\[([^\]]+)\]\.cbz$""")
    val chGroupOnly = Regex("""^Ch\.\s*(\d+(?:\.\d+)?)\s*\[([^\]]+)\]\.cbz$""")
    val chTitleOnly = Regex("""^Ch\.\s*(\d+(?:\.\d+)?)\s*-\s*.*\.cbz$""")
    val chPlain = Regex("""^Ch\.\s*(\d+(?:\.\d+)?)\.cbz$""")
    val galleryDlNoVolume = Regex("""^c(\d+(?:\.\d+)?)(.*?)\.cbz$""")
    var renamed = 0

    val cbzFiles =
      folder
        .listFiles()
        ?.filter { it.isFile && it.extension.lowercase() == "cbz" }
        ?: return 0

    for (cbz in cbzFiles) {
      if (cbz.name.lowercase().matches(Regex("""^v\d+ .+"""))) continue

      val match =
        chTitleGroup.find(cbz.name)
          ?: chGroupOnly.find(cbz.name)
          ?: chTitleOnly.find(cbz.name)
          ?: chPlain.find(cbz.name)

      val num: String
      val group: String?

      if (match != null) {
        num = match.groupValues[1]
        group = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
      } else if (galleryDlNoVolume.matches(cbz.name)) {
        num = galleryDlNoVolume.find(cbz.name)!!.groupValues[1]
        group = null
      } else {
        continue
      }

      val volume = extractVolumeFromCbz(cbz)
      val volumePrefix = if (volume != null) "v$volume " else ""

      val newName =
        if (match != null) {
          if (group != null) {
            "${volumePrefix}c$num [$group].cbz"
          } else {
            "${volumePrefix}c$num.cbz"
          }
        } else {
          "${volumePrefix}${cbz.name}"
        }

      if (cbz.name == newName) continue

      val target = java.io.File(folder, newName)
      if (!target.exists() && cbz.renameTo(target)) {
        renamed++
        logger.info { "Migrated CBZ: ${cbz.name} -> $newName" }
      }
    }

    return renamed
  }

  private fun extractVolumeFromCbz(cbzFile: java.io.File): String? =
    try {
      java.util.zip.ZipFile(cbzFile).use { zip ->
        val entry = zip.getEntry("ComicInfo.xml") ?: return@use null
        val content = zip.getInputStream(entry).bufferedReader().readText()
        Regex("""<Volume>(\d+)</Volume>""").find(content)?.groupValues?.get(1)
      }
    } catch (_: Exception) {
      null
    }

  private fun extractMangaDexIdFromSeriesJson(content: String): String? {
    val comicIdMatch = Regex(""""comicid"\s*:\s*"([a-f0-9-]{36})"""").find(content)
    if (comicIdMatch != null) return comicIdMatch.groupValues[1]
    val urlMatch = Regex("""mangadex\.org/title/([a-f0-9-]{36})""").find(content)
    return urlMatch?.groupValues?.get(1)
  }

  data class MigrationResult(
    val foldersRenamed: Int,
    val cbzRenamed: Int,
  )

  private fun sanitizeFileName(name: String): String =
    name
      .replace(Regex("[\\\\/:*?\"<>|]"), "")
      .trim()
      .trimEnd('.')

  companion object {
    private val MANGADEX_UUID_REGEX = Regex("""^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$""")
  }
}
