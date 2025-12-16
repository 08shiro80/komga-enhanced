package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class DownloadQueue(
  val id: String,
  val sourceUrl: String,
  val sourceType: SourceType,
  val title: String?,
  val author: String?,
  val status: DownloadStatus = DownloadStatus.PENDING,
  val progressPercent: Int = 0,
  val currentChapter: Int?,
  val totalChapters: Int?,
  val libraryId: String?,
  val destinationPath: String?,
  val errorMessage: String?,
  val pluginId: String?,
  val metadataJson: String?, // JSON string with additional metadata
  val createdBy: String,
  val startedDate: LocalDateTime?,
  val completedDate: LocalDateTime?,
  val priority: Int = 5, // 1=highest, 10=lowest
  val retryCount: Int = 0,
  val maxRetries: Int = 3,
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable {
  fun isComplete() = status == DownloadStatus.COMPLETED

  fun isFailed() = status == DownloadStatus.FAILED

  fun isActive() = status == DownloadStatus.DOWNLOADING

  fun canRetry() = retryCount < maxRetries && (status == DownloadStatus.FAILED || status == DownloadStatus.PENDING)
}

enum class SourceType {
  MANGA_SITE, // manga websites (handled by manga-py)
  DIRECT_URL, // direct file URL
  TORRENT, // torrent file
  RSS_FEED, // RSS feed
  API_SOURCE, // custom API source
}

enum class DownloadStatus {
  PENDING,
  DOWNLOADING,
  COMPLETED,
  FAILED,
  PAUSED,
  CANCELLED,
}

data class DownloadItem(
  val id: String,
  val queueId: String,
  val chapterNumber: String,
  val chapterTitle: String?,
  val chapterUrl: String?,
  val status: DownloadStatus = DownloadStatus.PENDING,
  val fileSizeBytes: Int?,
  val downloadedBytes: Int = 0,
  val filePath: String?,
  val scanlationGroup: String?,
  val errorMessage: String?,
  val startedDate: LocalDateTime?,
  val completedDate: LocalDateTime?,
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable {
  fun getProgressPercent(): Int {
    if (fileSizeBytes == null || fileSizeBytes == 0) return 0
    return ((downloadedBytes.toDouble() / fileSizeBytes) * 100).toInt()
  }
}

data class UpdateCheck(
  val id: String,
  val seriesId: String,
  val sourceUrl: String,
  val lastCheckDate: LocalDateTime,
  val latestChapter: String?,
  val newChaptersCount: Int = 0,
  val checkEnabled: Boolean = true,
  val checkFrequency: Int = 24, // hours
  val pluginId: String?,
  val metadataJson: String?,
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable {
  fun hasNewChapters() = newChaptersCount > 0

  fun shouldCheck(): Boolean {
    if (!checkEnabled) return false
    val hoursSinceLastCheck =
      java.time.Duration
        .between(lastCheckDate, LocalDateTime.now())
        .toHours()
    return hoursSinceLastCheck >= checkFrequency
  }
}

data class UserBlacklist(
  val id: String,
  val userId: String,
  val blacklistType: BlacklistType,
  val blacklistValue: String,
  val createdDate: LocalDateTime = LocalDateTime.now(),
)

enum class BlacklistType {
  TAG,
  GENRE,
  PUBLISHER,
  AUTHOR,
  AGE_RATING,
  SERIES_STATUS,
  CONTENT_WARNING,
}

// DTOs for download requests
data class DownloadRequest(
  val sourceUrl: String,
  val libraryId: String?,
  val title: String?,
  val author: String?,
  val startChapter: Int? = null,
  val endChapter: Int? = null,
  val priority: Int = 5,
)

data class DownloadProgress(
  val queueId: String,
  val status: DownloadStatus,
  val progressPercent: Int,
  val currentItem: String?,
  val totalItems: Int?,
  val downloadedBytes: Long,
  val totalBytes: Long?,
  val speed: String?, // formatted string like "2.5 MB/s"
  val estimatedTimeRemaining: String?, // formatted string like "5 minutes"
  val errorMessage: String?,
)
