package org.gotson.komga.interfaces.api.rest.dto

data class SinglePageBookDto(
  val bookId: String,
  val bookName: String,
  val seriesId: String,
  val seriesTitle: String,
  val fileSize: Long,
  val mediaType: String,
  val ignored: Boolean,
)
