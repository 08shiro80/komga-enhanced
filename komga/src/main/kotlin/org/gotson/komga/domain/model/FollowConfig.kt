package org.gotson.komga.domain.model

import java.time.LocalDateTime

/**
 * Configuration for follow.txt auto-download functionality
 */
data class FollowConfig(
  val id: String = "default",
  val urls: List<String> = emptyList(),
  val enabled: Boolean = false,
  val checkIntervalHours: Int = 24,
  val scheduleMode: String = "interval",
  val checkTime: String? = null,
  val lastCheckTime: LocalDateTime? = null,
  val createdDate: LocalDateTime = LocalDateTime.now(),
  val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
)
