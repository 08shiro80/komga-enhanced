package org.gotson.komga.interfaces.api.rest.dto

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
)

data class DownloadActionDto(
  val action: String, // pause, resume, cancel, retry
)

data class FollowConfigDto(
  val urls: List<String>,
  val enabled: Boolean,
  val checkInterval: Int,
  val lastCheckTime: String?,
)

data class FollowConfigUpdateDto(
  val urls: List<String>,
  val enabled: Boolean,
  val checkInterval: Int,
)

data class FollowTxtDto(
  val libraryId: String,
  val libraryName: String,
  val content: String,
)

data class FollowTxtUpdateDto(
  val content: String,
)

data class SchedulerSettingsDto(
  val enabled: Boolean,
  val intervalHours: Int,
)

data class SchedulerSettingsUpdateDto(
  val enabled: Boolean,
  val intervalHours: Int,
)
