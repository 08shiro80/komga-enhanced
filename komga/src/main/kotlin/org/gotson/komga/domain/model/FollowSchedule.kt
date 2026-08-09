package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class FollowSchedule(
  val libraryId: String,
  val enabled: Boolean = false,
  val scheduleMode: String = "interval",
  val intervalHours: Int = 24,
  val checkTime: String? = null,
  val lastCheckTime: LocalDateTime? = null,
)
