package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class SeriesReadState(
  val seriesId: String,
  val libraryId: String,
  val oneshot: Boolean,
  val highestNumberSort: Float,
  val firstReadDate: LocalDateTime,
  val lastReadDate: LocalDateTime,
)
