package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class BlacklistedChapter(
  val id: String,
  val seriesId: String,
  val chapterUrl: String,
  val chapterNumber: String? = null,
  val chapterTitle: String? = null,
  val createdDate: LocalDateTime = LocalDateTime.now(),
)
