package org.gotson.komga.interfaces.api.rest.dto

import org.gotson.komga.domain.model.UserTracker
import java.time.Instant

data class UserTrackerDto(
  val tracker: String,
  val linked: Boolean,
  val enabled: Boolean,
  val settings: Map<String, String>,
  val secretsSet: Set<String>,
  val authorizationRedirectUri: String?,
  val scrobblerEnabled: Boolean,
  val status: TrackerStatusDto,
)

data class TrackerStatusDto(
  val state: String,
  val message: String?,
  val accountName: String?,
  val lastSuccessAt: Instant?,
  val lastErrorAt: Instant?,
  val lastError: String?,
  val tokenExpiresAt: Instant?,
)

data class UserTrackerUpdateDto(
  val enabled: Boolean? = null,
  val settings: Map<String, String?> = emptyMap(),
)

data class TrackerCheckResultDto(
  val success: Boolean,
  val message: String,
)

data class TrackerAuthorizationRequestDto(
  val returnTo: String,
)

data class TrackerAuthorizationDto(
  val authorizationUrl: String,
)

data class TrackerSyncDto(
  val queued: Int,
)

private fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun UserTracker.toDto(
  authorizationRedirectUri: String?,
  scrobblerEnabled: Boolean,
  tokenExpiresAt: Instant?,
) = UserTrackerDto(
  tracker = tracker,
  linked = linked,
  enabled = enabled,
  settings = settings.filterKeys { it != UserTracker.KEY_ENABLED && it !in UserTracker.secretKeys && it !in UserTracker.statusKeys },
  secretsSet = settings.filter { it.key in UserTracker.secretKeys && it.value.isNotBlank() }.keys,
  authorizationRedirectUri = authorizationRedirectUri,
  scrobblerEnabled = scrobblerEnabled,
  status =
    TrackerStatusDto(
      state =
        when {
          unreadableKeys.isNotEmpty() -> "secrets_unreadable"
          !linked -> "not_linked"
          else -> this[UserTracker.KEY_STATUS] ?: "unknown"
        },
      message = if (unreadableKeys.isNotEmpty()) null else this[UserTracker.KEY_STATUS_MESSAGE],
      accountName = this[UserTracker.KEY_ACCOUNT_NAME],
      lastSuccessAt = instant(this[UserTracker.KEY_LAST_SUCCESS_AT]),
      lastErrorAt = instant(this[UserTracker.KEY_LAST_ERROR_AT]),
      lastError = this[UserTracker.KEY_LAST_ERROR],
      tokenExpiresAt = tokenExpiresAt,
    ),
)
