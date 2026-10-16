package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class ComicScrobble(
  val userId: String,
  val seriesId: String,
  val issueNumber: String,
  val metronIssueId: Int,
  val bookId: String?,
  val dateRead: LocalDateTime?,
)
