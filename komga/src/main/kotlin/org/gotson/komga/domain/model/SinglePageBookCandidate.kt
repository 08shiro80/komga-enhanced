package org.gotson.komga.domain.model

data class SinglePageBookCandidate(
  val bookId: String,
  val bookName: String,
  val seriesId: String,
  val seriesName: String,
  val seriesTitle: String?,
  val fileSize: Long,
  val mediaType: String,
)
