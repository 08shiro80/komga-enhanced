package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DownloadQueue
import org.gotson.komga.domain.model.DownloadStatus
import org.gotson.komga.domain.model.SourceType
import org.gotson.komga.domain.persistence.DownloadQueueRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.gotson.komga.interfaces.api.websocket.DownloadProgressDto
import org.gotson.komga.interfaces.api.websocket.DownloadProgressHandler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Service
class DownloadService(
  private val downloadQueueRepository: DownloadQueueRepository,
  private val libraryRepository: LibraryRepository,
  private val galleryDlWrapper: GalleryDlWrapper,
  private val libraryLifecycle: LibraryLifecycle,
  private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
  private val downloadProgressHandler: DownloadProgressHandler,
) {
  private val processing = AtomicBoolean(false)
  private val activeDownloads = ConcurrentHashMap<String, DownloadQueue>()

  /**
   * Process download queue every 30 seconds
   */
  @Scheduled(fixedDelay = 30000, initialDelay = 10000)
  fun processQueue() {
    if (!processing.compareAndSet(false, true)) {
      logger.debug { "Download processing already in progress, skipping" }
      return
    }

    try {
      // Check if gallery-dl is available
      if (!galleryDlWrapper.isInstalled()) {
        logger.warn { "gallery-dl is not installed, skipping download processing. Please install with: pip install gallery-dl" }
        return
      }

      // Get pending downloads ordered by priority
      val pending = downloadQueueRepository.findPendingOrdered()

      if (pending.isEmpty()) {
        logger.debug { "No pending downloads" }
        return
      }

      // Process one download at a time for now
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

  /**
   * Auto-retry failed downloads every 5 minutes
   * Only retries downloads that haven't exceeded maxRetries
   */
  @Scheduled(fixedDelay = 300000, initialDelay = 60000) // 5 minutes
  fun autoRetryFailedDownloads() {
    try {
      val failed = downloadQueueRepository.findByStatus(DownloadStatus.FAILED)
      val retriable = failed.filter { it.canRetry() }

      if (retriable.isEmpty()) {
        logger.debug { "No failed downloads to auto-retry" }
        return
      }

      // Only retry downloads that failed more than 5 minutes ago (exponential backoff)
      val now = LocalDateTime.now()
      val toRetry =
        retriable.filter { download ->
          val waitMinutes = (download.retryCount + 1) * 5L // 5, 10, 15 minutes for retries 1, 2, 3
          val lastModified = download.lastModifiedDate
          java.time.Duration.between(lastModified, now).toMinutes() >= waitMinutes
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

          // Broadcast retry event via WebSocket
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

  /**
   * Create a new download request
   */
  fun createDownload(
    sourceUrl: String,
    libraryId: String?,
    title: String?,
    createdBy: String,
    priority: Int = 5,
  ): DownloadQueue {
    // Validate library exists
    if (libraryId != null) {
      libraryRepository.findByIdOrNull(libraryId)
        ?: throw IllegalArgumentException("Library not found: $libraryId")
    }

    // Try to get manga info
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

  /**
   * Cancel a download
   */
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

    activeDownloads.remove(downloadId)
    logger.info { "Cancelled download: $downloadId" }
  }

  /**
   * Retry a failed download
   */
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

  /**
   * Delete a download from queue
   */
  fun deleteDownload(downloadId: String) {
    downloadQueueRepository.delete(downloadId)
    activeDownloads.remove(downloadId)
    logger.info { "Deleted download: $downloadId" }
  }

  /**
   * Process a follow.txt file to add manga URLs to download queue
   */
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

      urls.forEach { url ->
        try {
          // gallery-dl's --download-archive will handle duplicate chapter detection
          createDownload(
            sourceUrl = url,
            libraryId = libraryId,
            title = null, // Will be fetched from gallery-dl
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

  private fun processDownload(download: DownloadQueue) {
    activeDownloads[download.id] = download

    try {
      // Mark as downloading
      updateDownloadStatus(download, DownloadStatus.DOWNLOADING, startedDate = LocalDateTime.now())

      // Broadcast download started via WebSocket
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

      // Determine destination path
      val library =
        if (download.libraryId != null) {
          libraryRepository.findByIdOrNull(download.libraryId)
        } else {
          null
        }

      // Create manga folder with English title from our metadata (NOT from gallery-dl)
      // This ensures we use the English title, not Japanese/romanized
      val mangaFolderName = sanitizeFileName(download.title ?: "Unknown")
      val libraryPath =
        if (library != null) {
          library.path
        } else {
          Paths.get(System.getProperty("user.home"), "Downloads", "komga")
        }

      // destinationPath is Library/MangaTitle - gallery-dl creates chapter folders inside
      val destinationPath = libraryPath.resolve(mangaFolderName)

      // Create the manga folder if it doesn't exist
      if (!destinationPath.toFile().exists()) {
        destinationPath.toFile().mkdirs()
        logger.info { "Created manga folder: $destinationPath" }
      }

      logger.info { "Starting download to: $destinationPath" }

      // Download using gallery-dl
      val result =
        galleryDlWrapper.download(download.sourceUrl, destinationPath, library?.path) { progress ->
          // Update progress in database
          downloadQueueRepository.update(
            download.copy(
              progressPercent = progress.percent,
              currentChapter = progress.currentChapter,
              totalChapters = if (progress.totalChapters > 0) progress.totalChapters else download.totalChapters,
              lastModifiedDate = LocalDateTime.now(),
            ),
          )

          // Broadcast progress via WebSocket
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
        }

      if (result.success) {
        // gallery-dl creates the manga folder automatically via {manga} pattern
        // No folder renaming needed - the manga title comes directly from MangaDex
        val finalPath = destinationPath // Library path - manga folder created by gallery-dl
        val finalTitle = result.mangaTitle ?: download.title

        logger.info { "Download completed to: $finalPath (manga folder: ${result.mangaTitle})" }

        // Mark as completed
        updateDownloadStatus(
          download,
          DownloadStatus.COMPLETED,
          completedDate = LocalDateTime.now(),
          progressPercent = 100,
          destinationPath = finalPath.toString(),
        )

        // Broadcast completion via WebSocket
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

        // TODO: Trigger library scan if download was for a library
        // For now, user needs to manually trigger library scan after download completes
        if (library != null) {
          logger.info { "Download completed for library ${library.name}. Please manually trigger library scan." }
        }

        logger.info { "Download completed: ${download.id} - ${result.filesDownloaded} files" }
      } else {
        // Mark as failed
        updateDownloadStatus(
          download,
          DownloadStatus.FAILED,
          errorMessage = result.errorMessage ?: "Download failed",
        )

        // Broadcast failure via WebSocket
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

        logger.error { "Download failed: ${download.id} - ${result.errorMessage}" }
      }
    } catch (e: Exception) {
      logger.error(e) { "Error processing download ${download.id}" }

      updateDownloadStatus(
        download,
        DownloadStatus.FAILED,
        errorMessage = e.message ?: "Unknown error",
      )

      // Broadcast error via WebSocket
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
    name.replace(Regex("[^a-zA-Z0-9-_ ]"), "_").trim()
}
