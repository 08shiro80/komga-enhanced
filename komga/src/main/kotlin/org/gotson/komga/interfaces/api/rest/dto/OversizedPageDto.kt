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

data class SplitRequestDto(
  val maxHeight: Int = 2000,
  val bookIds: List<String>? = null,
)

data class SplitResultDto(
  val bookId: String,
  val bookName: String,
  val pagesAnalyzed: Int,
  val pagesSplit: Int,
  val newPagesCreated: Int,
  val success: Boolean,
  val message: String,
)
