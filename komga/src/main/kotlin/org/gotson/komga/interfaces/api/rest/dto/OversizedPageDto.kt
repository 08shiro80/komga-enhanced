package org.gotson.komga.interfaces.api.rest.dto

data class OversizedPageDto(
  val bookId: String,
  val bookName: String,
  val seriesId: String,
  val seriesTitle: String,
  val pageNumber: Int,
  val width: Int,
  val height: Int,
  val fileSize: Long,
  val mediaType: String,
)
