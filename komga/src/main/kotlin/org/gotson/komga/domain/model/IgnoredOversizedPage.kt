package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class IgnoredOversizedPage(
  val bookId: String,
  val pageNumber: Int,
  val mode: String,
  val createdDate: LocalDateTime = LocalDateTime.now(),
)
