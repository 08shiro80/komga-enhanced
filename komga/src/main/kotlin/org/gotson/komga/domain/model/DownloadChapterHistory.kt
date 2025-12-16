package org.gotson.komga.domain.model

import java.time.LocalDateTime

/**
 * Represents a downloaded chapter in the history.
 *
 * This tracks individual chapters that have been downloaded as part of a download queue item.
 * Used for tracking which chapters have already been downloaded to prevent duplicates.
 */
data class DownloadChapterHistory(
  val downloadId: String,
  val chapterUrl: String,
  val chapterNumber: String?,
  val downloadedAt: LocalDateTime,
  val cbzFilename: String?,
)
