package org.gotson.komga.interfaces.sse.dto

data class DownloadSseDto(
  val downloadId: String,
  val title: String?,
  val sourceUrl: String?,
  val status: String,
  val progressPercent: Int,
  val currentChapter: Int?,
  val totalChapters: Int?,
  val libraryId: String?,
  val filesDownloaded: Int?,
  val errorMessage: String?,
  val message: String?,
)
