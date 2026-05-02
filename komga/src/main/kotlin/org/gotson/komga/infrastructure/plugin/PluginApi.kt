package org.gotson.komga.infrastructure.plugin

import org.gotson.komga.domain.model.AlternateTitle
import org.gotson.komga.domain.model.Author
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DownloadProgress
import org.gotson.komga.domain.model.DownloadQueue
import org.gotson.komga.domain.model.DownloadRequest
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.PermissionType
import org.gotson.komga.domain.model.PluginDescriptor
import org.gotson.komga.domain.model.PluginPermission
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.WebLink

/**
 * Base interface that all plugins must implement
 */
interface KomgaPlugin {
  /**
   * Called when the plugin is loaded
   */
  fun onLoad()

  /**
   * Called when the plugin is enabled
   */
  fun onEnable()

  /**
   * Called when the plugin is disabled
   */
  fun onDisable()

  /**
   * Get the plugin descriptor
   */
  fun getDescriptor(): PluginDescriptor

  /**
   * Validate plugin configuration
   */
  fun validateConfig(config: Map<String, String>): ConfigValidationResult
}

data class ConfigValidationResult(
  val valid: Boolean,
  val errors: List<String> = emptyList(),
)

/**
 * Interface for metadata provider plugins
 * These fetch metadata from external sources
 */
interface MetadataProviderPlugin : KomgaPlugin {
  /**
   * Search for series by name
   */
  suspend fun searchSeries(
    query: String,
    language: String? = null,
  ): List<SeriesSearchResult>

  /**
   * Get detailed metadata for a series
   */
  suspend fun getSeriesMetadata(
    seriesId: String,
    sourceUrl: String,
  ): SeriesMetadataResult?

  /**
   * Get book/chapter metadata
   */
  suspend fun getBookMetadata(
    bookId: String,
    sourceUrl: String,
  ): BookMetadataResult?

  /**
   * Supported languages for this provider
   */
  fun getSupportedLanguages(): List<String>

  /**
   * Can this provider handle this URL?
   */
  fun canHandle(url: String): Boolean
}

data class SeriesSearchResult(
  val sourceId: String,
  val title: String,
  val alternativeTitles: List<String> = emptyList(),
  val description: String?,
  val author: String?,
  val artist: String?,
  val genres: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
  val status: String?, // Ongoing, Completed, etc.
  val year: Int?,
  val coverUrl: String?,
  val sourceUrl: String,
  val language: String?,
  val rating: Float?,
)

data class SeriesMetadataResult(
  val title: String,
  val titleSort: String?,
  val alternativeTitles: List<AlternateTitle> = emptyList(),
  val summary: String?,
  val publisher: String?,
  val ageRating: Int?,
  val language: String?,
  val genres: Set<String> = emptySet(),
  val tags: Set<String> = emptySet(),
  val authors: List<Author> = emptyList(),
  val status: SeriesMetadata.Status? = null,
  val totalBookCount: Int?,
  val coverUrl: String?,
  val links: List<WebLink> = emptyList(),
)

data class BookMetadataResult(
  val title: String,
  val number: String?,
  val numberSort: Float?,
  val summary: String?,
  val releaseDate: java.time.LocalDate?,
  val authors: List<Author> = emptyList(),
  val tags: Set<String> = emptySet(),
  val isbn: String?,
  val coverUrl: String?,
  val links: List<WebLink> = emptyList(),
)

/**
 * Interface for download provider plugins
 * These handle downloading content from external sources
 */
interface DownloadProviderPlugin : KomgaPlugin {
  /**
   * Check if this plugin can handle the given URL
   */
  fun canHandleUrl(url: String): Boolean

  /**
   * Get available chapters/volumes for a series
   */
  suspend fun getAvailableChapters(sourceUrl: String): List<ChapterInfo>

  /**
   * Start a download
   */
  suspend fun startDownload(request: DownloadRequest): DownloadQueue

  /**
   * Pause a download
   */
  suspend fun pauseDownload(queueId: String): Boolean

  /**
   * Resume a download
   */
  suspend fun resumeDownload(queueId: String): Boolean

  /**
   * Cancel a download
   */
  suspend fun cancelDownload(queueId: String): Boolean

  /**
   * Get download progress
   */
  suspend fun getProgress(queueId: String): DownloadProgress

  /**
   * Check for updates on a series
   */
  suspend fun checkForUpdates(
    sourceUrl: String,
    lastKnownChapter: String?,
  ): UpdateCheckResult
}

data class ChapterInfo(
  val number: String,
  val title: String?,
  val url: String,
  val releaseDate: java.time.LocalDate?,
  val language: String?,
  val scanlationGroup: String?,
)

data class UpdateCheckResult(
  val hasUpdates: Boolean,
  val latestChapter: String?,
  val newChapters: List<ChapterInfo> = emptyList(),
)

/**
 * Interface for task plugins
 * These perform custom background tasks
 */
interface TaskPlugin : KomgaPlugin {
  /**
   * Execute the task
   */
  suspend fun execute(context: TaskContext): TaskResult

  /**
   * Get task schedule (cron expression or interval)
   */
  fun getSchedule(): TaskSchedule?

  /**
   * Can this task be manually triggered?
   */
  fun canTriggerManually(): Boolean = true
}

data class TaskContext(
  val taskId: String,
  val parameters: Map<String, Any>,
  val triggeredBy: String?, // user ID or "system"
)

data class TaskResult(
  val success: Boolean,
  val message: String?,
  val data: Map<String, Any>? = null,
)

sealed class TaskSchedule {
  data class Cron(
    val expression: String,
  ) : TaskSchedule()

  data class Interval(
    val minutes: Long,
  ) : TaskSchedule()

  object Manual : TaskSchedule()
}

/**
 * Context provided to plugins for accessing Komga services
 */
interface PluginContext {
  /**
   * Get plugin-specific configuration
   */
  fun getConfig(key: String): String?

  /**
   * Set plugin-specific configuration
   */
  fun setConfig(
    key: String,
    value: String,
  )

  /**
   * Log a message
   */
  fun log(
    level: LogLevel,
    message: String,
    exception: Throwable? = null,
  )

  /**
   * Get granted permissions
   */
  fun getPermissions(): List<PluginPermission>

  /**
   * Check if a specific permission is granted
   */
  fun hasPermission(
    permissionType: PermissionType,
    detail: String? = null,
  ): Boolean

  /**
   * Get the plugin's data directory
   */
  fun getDataDirectory(): java.nio.file.Path

  /**
   * HTTP client for making web requests (if NETWORK permission granted)
   */
  fun getHttpClient(): PluginHttpClient?

  /**
   * Access to Komga APIs (if API_ACCESS permission granted)
   */
  fun getKomgaApi(): KomgaApiClient?
}

/**
 * HTTP client for plugins
 */
interface PluginHttpClient {
  suspend fun get(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ): HttpResponse

  suspend fun post(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
  ): HttpResponse

  suspend fun downloadFile(
    url: String,
    destination: java.nio.file.Path,
    progressCallback: ((Long, Long) -> Unit)? = null,
  )
}

data class HttpResponse(
  val statusCode: Int,
  val body: String,
  val headers: Map<String, List<String>>,
)

/**
 * Komga API client for plugins
 */
interface KomgaApiClient {
  suspend fun getLibraries(): List<Library>

  suspend fun getSeries(seriesId: String): Series?

  suspend fun getBook(bookId: String): Book?

  suspend fun updateSeriesMetadata(
    seriesId: String,
    metadata: SeriesMetadataResult,
  )

  suspend fun updateBookMetadata(
    bookId: String,
    metadata: BookMetadataResult,
  )

  suspend fun addBookToLibrary(
    libraryId: String,
    filePath: String,
  ): Book?
}
