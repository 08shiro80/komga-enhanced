package org.gotson.komga.interfaces.api.rest.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.LocalDateTime

data class DownloadDto(
  val id: String,
  val sourceUrl: String,
  val title: String?,
  val status: String,
  val progressPercent: Int,
  val currentChapter: Int,
  val totalChapters: Int?,
  val libraryId: String?,
  val errorMessage: String?,
  val createdDate: LocalDateTime,
  val startedDate: LocalDateTime?,
  val completedDate: LocalDateTime?,
  val priority: Int,
)

data class DownloadCreateDto(
  val sourceUrl: String,
  val title: String?,
  val libraryId: String?,
  val priority: Int = 5,
  val seriesId: String? = null,
  val chapterRange: String? = null,
  val customFilename: String? = null,
  val customChapterNumber: String? = null,
  val customVolume: String? = null,
  val customChapterTitle: String? = null,
  val skipIfChapterExists: Boolean = true,
) {
  fun toOverrides(): org.gotson.komga.domain.service.ChapterDownloadOverrides? {
    val any =
      seriesId != null ||
        !chapterRange.isNullOrBlank() ||
        !customFilename.isNullOrBlank() ||
        !customChapterNumber.isNullOrBlank() ||
        !customVolume.isNullOrBlank() ||
        !customChapterTitle.isNullOrBlank()
    return if (!any) {
      null
    } else {
      org.gotson.komga.domain.service.ChapterDownloadOverrides(
        seriesId = seriesId,
        chapterRange = chapterRange,
        customFilename = customFilename,
        customChapterNumber = customChapterNumber,
        customVolume = customVolume,
        customChapterTitle = customChapterTitle,
        skipIfChapterExists = skipIfChapterExists,
      )
    }
  }
}

data class DownloadActionDto(
  val action: String, // pause, resume, cancel, retry
)

data class ClearResultDto(
  val deletedCount: Int,
  val status: String,
  val message: String,
)

data class ChapterCheckResultDto(
  val url: String,
  val mangaId: String?,
  val title: String?,
  val apiChapterCount: Int,
  val downloadedChapterCount: Int,
  val filesystemChapterCount: Int,
  val newChaptersEstimate: Int,
  val needsDownload: Boolean,
  val error: String?,
)

data class ChapterCheckSummaryDto(
  val totalManga: Int,
  val checkedCount: Int,
  val needsDownloadCount: Int,
  val upToDateCount: Int,
  val errorCount: Int,
  val results: List<ChapterCheckResultDto>,
  val durationMs: Long,
)

data class FollowDto(
  val id: String,
  val libraryId: String,
  val url: String,
  val title: String?,
  val seriesId: String?,
  val enabled: Boolean,
  val addedAt: LocalDateTime,
  val lastCheckedAt: LocalDateTime?,
)

data class FollowCreationDto(
  @field:NotBlank val url: String,
  val title: String? = null,
  val seriesId: String? = null,
)

data class FollowUpdateDto(
  val title: String? = null,
  val enabled: Boolean? = null,
)

data class FollowBatchCreationDto(
  @field:NotEmpty val urls: List<String>,
)

data class FollowBatchDeleteDto(
  @field:NotEmpty val ids: List<String>,
)

data class FollowBatchResultDto(
  val added: Int,
  val skipped: Int,
)

data class FollowCheckResultDto(
  val queued: Int,
)

data class FollowScheduleDto(
  val libraryId: String,
  val enabled: Boolean,
  val scheduleMode: String,
  val intervalHours: Int,
  val checkTime: String?,
  val lastCheckTime: LocalDateTime?,
)

data class FollowScheduleUpdateDto(
  val enabled: Boolean,
  val scheduleMode: String = "interval",
  val intervalHours: Int = 24,
  val checkTime: String? = null,
)
