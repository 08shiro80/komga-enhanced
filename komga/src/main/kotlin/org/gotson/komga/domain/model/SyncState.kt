package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class SyncState(
  val id: String,
  val bookId: String?,
  val seriesId: String,
  val tracker: String,
  val progress: Int = 0,
  val status: String? = null,
  val score: Int? = null,
  val lastSyncTimestamp: LocalDateTime? = null,
  val lastUpdateTimestamp: LocalDateTime? = null,
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable
