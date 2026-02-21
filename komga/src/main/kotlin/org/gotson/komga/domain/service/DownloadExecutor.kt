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
  private val galleryDlWrapper: GalleryDlWrapper,
  private val libraryLifecycle: LibraryLifecycle,
  private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
  private val downloadProgressHandler: DownloadProgressHandler,
  private val eventPublisher: ApplicationEventPublisher,
) {
  private val processing = AtomicBoolean(false)
  private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()
  private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

  @Scheduled(fixedDelay = 30000, initialDelay = 10000)
  fun processQueue() {
    if (!processing.compareAndSet(false, true)) {
      logger.debug { "Download processing already in progress, skipping" }
      return
    }

    try {
      if (!galleryDlWrapper.isInstalled()) {
        logger.warn { "gallery-dl is not installed, skipping download processing" }
        return
      }

      val pending = downloadQueueRepository.findPendingOrdered()

      if (pending.isEmpty()) {
        logger.debug { "No pending downloads" }
        return
      }

      val download = pending.first()

      if (activeDownloads.containsKey(download.id)) {
        logger.debug { "Download ${download.id} already being processed" }
        return
      }

      logger.info { "Processing download: ${download.id} - ${download.sourceUrl}" }
      processDownload(download)
    } catch (e: Exception) {
      logger.error(e) { "Error processing download queue" }
    } finally {
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

      val mangaFolderName = sanitizeFileName(download.title ?: "Unknown")
      val libraryPath =
        if (library != null) {
          library.path
        } else {
          Paths.get(System.getProperty("user.home"), "Downloads", "komga")
        }

      val destinationPath = libraryPath.resolve(mangaFolderName)

      if (!destinationPath.toFile().exists()) {
        destinationPath.toFile().mkdirs()
        logger.info { "Created manga folder: $destinationPath" }
      }

      logger.info { "Starting download to: $destinationPath" }

      val isCancelled = { cancelledIds.contains(download.id) }

      val result =
        galleryDlWrapper.download(
          url = download.sourceUrl,
          destinationPath = destinationPath,
          libraryPath = library?.path,
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
        val finalPath = destinationPath
        val finalTitle = result.mangaTitle ?: download.title

        logger.info { "Download completed to: $finalPath (manga folder: ${result.mangaTitle})" }

        updateDownloadStatus(
          download,
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

  private fun sanitizeFileName(name: String): String =
    name
      .replace(Regex("[\\\\/:*?\"<>|]"), "")
      .trim()
      .trimEnd('.')
}
