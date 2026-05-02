package org.gotson.komga.interfaces.api.rest.dto

data class OversizedPageDto(
  val bookId: String,
  val bookName: String,
  val seriesId: String,
  val seriesTitle: String,
  val pageNumber: Int,
  val width: Int,
  val height: Int,
  val ratio: Double,
  val fileSize: Long,
  val mediaType: String,
)

data class SplitRequestDto(
  val maxHeight: Int? = null,
  val maxRatio: Double? = null,
  val bookIds: List<String>? = null,
  val mode: String? = null,
)

data class IgnoreOversizedPageRequestDto(
  val bookId: String,
  val pageNumber: Int,
  val mode: String,
)

data class IgnoreOversizedPagesRequestDto(
  val mode: String,
  val pages: List<IgnoredPageKeyDto>,
)

data class IgnoredPageKeyDto(
  val bookId: String,
  val pageNumber: Int,
)

data class DeleteOversizedPageRequestDto(
  val bookId: String,
  val pageNumber: Int,
  val mode: String,
)

data class DeleteOversizedPagesRequestDto(
  val mode: String,
  val pages: List<IgnoredPageKeyDto>,
)

data class DeletePagesResultDto(
  val bookId: String,
  val deleted: Int,
  val success: Boolean,
  val message: String,
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
