package org.gotson.komga.interfaces.api.rest.dto

import org.gotson.komga.domain.model.UserTracker

data class UserTrackerDto(
  val tracker: String,
  val linked: Boolean,
  val enabled: Boolean,
  val settings: Map<String, String>,
  val secretsSet: Set<String>,
)

data class UserTrackerUpdateDto(
  val enabled: Boolean? = null,
  val settings: Map<String, String?> = emptyMap(),
)

data class TrackerCheckResultDto(
  val success: Boolean,
  val message: String,
)

fun UserTracker.toDto() =
  UserTrackerDto(
    tracker = tracker,
    linked = linked,
    enabled = enabled,
    settings = settings.filterKeys { it != UserTracker.KEY_ENABLED && it !in UserTracker.secretKeys },
    secretsSet = settings.filter { it.key in UserTracker.secretKeys && it.value.isNotBlank() }.keys,
  )
