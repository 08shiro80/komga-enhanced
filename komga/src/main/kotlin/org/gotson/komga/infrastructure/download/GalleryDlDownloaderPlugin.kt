package org.gotson.komga.infrastructure.download

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DownloadProgress
import org.gotson.komga.domain.model.DownloadQueue
import org.gotson.komga.domain.model.DownloadStatus
import org.gotson.komga.domain.model.PermissionRequest
import org.gotson.komga.domain.model.PermissionType
import org.gotson.komga.domain.model.PluginDescriptor
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.infrastructure.plugin.ChapterInfo
import org.gotson.komga.infrastructure.plugin.ConfigValidationResult
import org.gotson.komga.infrastructure.plugin.DownloadProviderPlugin
import org.gotson.komga.infrastructure.plugin.UpdateCheckResult
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class GalleryDlDownloaderPlugin(
  private val galleryDlWrapper: GalleryDlWrapper,
) : DownloadProviderPlugin {
  private var enabled = false

  override fun onLoad() {
    logger.info { "gallery-dl Downloader Plugin loaded" }
  }

  override fun onEnable() {
    enabled = true
    logger.info { "gallery-dl Downloader Plugin enabled" }
  }

  override fun onDisable() {
    enabled = false
    logger.info { "gallery-dl Downloader Plugin disabled" }
  }

  override fun getDescriptor(): PluginDescriptor =
    PluginDescriptor(
      id = "gallery-dl-downloader",
      name = "gallery-dl Downloader",
      version = "1.0.0",
      author = "Komga Team",
      description =
        "Downloads manga from 1000+ websites using gallery-dl. " +
          "Supports MangaDex, MangaPlus, and many more sites. " +
          "Requires gallery-dl to be installed: pip install gallery-dl",
      pluginType = PluginType.DOWNLOAD,
      entryPoint = "org.gotson.komga.infrastructure.download.GalleryDlDownloaderPlugin",
      requiredPermissions =
        listOf(
          PermissionRequest(
            PermissionType.NETWORK,
            "Required to download manga from external websites (MangaDex, MangaPlus, and 1000+ other sites)",
          ),
          PermissionRequest(
            PermissionType.FILESYSTEM,
            "Required to save downloaded manga files and create CBZ archives in library directories",
          ),
        ),
      configSchema = null,
      dependencies = emptyList(),
      minKomgaVersion = null,
      maxKomgaVersion = null,
    )

  override fun validateConfig(config: Map<String, String>): ConfigValidationResult {
    val errors = mutableListOf<String>()

    // Check if gallery-dl is installed
    if (!galleryDlWrapper.isInstalled()) {
      errors.add(
        "gallery-dl is not installed. Please install it with: pip install gallery-dl",
      )
    }

    return ConfigValidationResult(
      valid = errors.isEmpty(),
      errors = errors,
    )
  }

  override fun canHandleUrl(url: String): Boolean {
    // gallery-dl supports 1000+ sites including:
    // - MangaDex: https://mangadex.org/title/...
    // - MangaPlus: https://mangaplus.shueisha.co.jp/...
    // - And many more manga/comic sites
    // If the plugin is enabled, we can attempt to handle any URL
    // gallery-dl will fail gracefully if the site is not supported
    return enabled
  }

  override suspend fun getAvailableChapters(sourceUrl: String): List<ChapterInfo> {
    logger.debug { "Getting available chapters for: $sourceUrl" }

    return try {
      val mangaInfo = galleryDlWrapper.getChapterInfo(sourceUrl)

      // gallery-dl doesn't provide individual chapter details in getChapterInfo
      // so we return a single chapter info indicating all chapters available
      listOf(
        ChapterInfo(
          number = "1-${mangaInfo.totalChapters}",
          title = "${mangaInfo.title} (${mangaInfo.totalChapters} chapters)",
          url = sourceUrl,
          releaseDate = null,
          language = "en",
          scanlationGroup = null,
        ),
      )
    } catch (e: Exception) {
      logger.error(e) { "Failed to get chapters for: $sourceUrl" }
      emptyList()
    }
  }

  override suspend fun startDownload(request: org.gotson.komga.domain.model.DownloadRequest): DownloadQueue {
    logger.info { "Starting download via plugin: ${request.sourceUrl}" }

    // This is handled by DownloadService which uses GalleryDlWrapper directly
    // This method is here to satisfy the interface but actual download logic
    // is in DownloadService.kt
    throw UnsupportedOperationException(
      "Use DownloadService to start downloads. This plugin provides metadata only.",
    )
  }

  override suspend fun pauseDownload(queueId: String): Boolean {
    logger.warn { "Pause download not supported for gallery-dl" }
    return false
  }

  override suspend fun resumeDownload(queueId: String): Boolean {
    logger.warn { "Resume download not supported for gallery-dl" }
    return false
  }

  override suspend fun cancelDownload(queueId: String): Boolean {
    logger.warn { "Cancel download via plugin not supported - use DownloadService instead" }
    return false
  }

  override suspend fun getProgress(queueId: String): DownloadProgress {
    // Progress is tracked by DownloadService, not by the plugin
    throw UnsupportedOperationException("Progress tracking is handled by DownloadService")
  }

  override suspend fun checkForUpdates(
    sourceUrl: String,
    lastKnownChapter: String?,
  ): UpdateCheckResult {
    logger.debug { "Checking for updates: $sourceUrl (last known: $lastKnownChapter)" }

    return try {
      val mangaInfo = galleryDlWrapper.getChapterInfo(sourceUrl)

      // Parse last known chapter number
      val lastChapterNum =
        lastKnownChapter?.toIntOrNull() ?: 0

      val hasUpdates = mangaInfo.totalChapters > lastChapterNum

      UpdateCheckResult(
        hasUpdates = hasUpdates,
        latestChapter = mangaInfo.totalChapters.toString(),
        newChapters =
          if (hasUpdates) {
            listOf(
              ChapterInfo(
                number = "${lastChapterNum + 1}-${mangaInfo.totalChapters}",
                title = "New chapters available",
                url = sourceUrl,
                releaseDate = null,
                language = "en",
                scanlationGroup = null,
              ),
            )
          } else {
            emptyList()
          },
      )
    } catch (e: Exception) {
      logger.error(e) { "Failed to check for updates: $sourceUrl" }
      UpdateCheckResult(
        hasUpdates = false,
        latestChapter = lastKnownChapter,
        newChapters = emptyList(),
      )
    }
  }
}
