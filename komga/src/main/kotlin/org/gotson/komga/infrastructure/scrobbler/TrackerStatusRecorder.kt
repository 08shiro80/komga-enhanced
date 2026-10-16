package org.gotson.komga.infrastructure.scrobbler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.ComicScrobbleRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.springframework.stereotype.Component
import java.time.Clock

private val logger = KotlinLogging.logger {}

private const val MAX_ERROR_LENGTH = 500

@Component
class TrackerStatusRecorder(
  private val userTrackerRepository: UserTrackerRepository,
  private val syncStateRepository: SyncStateRepository,
  private val comicScrobbleRepository: ComicScrobbleRepository,
  private val clock: Clock = Clock.systemUTC(),
) {
  fun success(
    account: UserTracker,
    accountName: String? = null,
    remoteUserId: String? = null,
  ) {
    val previousRemoteId = account[UserTracker.KEY_REMOTE_USER_ID]
    if (remoteUserId != null && previousRemoteId != null && previousRemoteId != remoteUserId) {
      logger.info { "${account.tracker} account of user ${account.userId} changed to another remote account, resetting the sync state" }
      resetSyncState(account.userId, account.tracker)
    }
    val values =
      buildMap<String, String?> {
        put(UserTracker.KEY_STATUS, UserTracker.STATUS_OK)
        put(UserTracker.KEY_STATUS_MESSAGE, null)
        put(UserTracker.KEY_LAST_SUCCESS_AT, clock.instant().toString())
        accountName?.let { put(UserTracker.KEY_ACCOUNT_NAME, it) }
        remoteUserId?.let {
          put(UserTracker.KEY_REMOTE_USER_ID, it)
          put(UserTracker.KEY_IDENTITY_CHECK, null)
        }
      }
    if (account.reconnectRequired) logger.info { "${account.tracker} account of user ${account.userId} works again" }
    userTrackerRepository.updateStatus(account.userId, account.tracker, values)
  }

  fun failure(
    account: UserTracker,
    error: TrackerCallException,
  ) {
    val message = (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_LENGTH)
    val reconnect = error is TrackerAuthException || error is TrackerUnauthorizedException
    val values =
      buildMap<String, String?> {
        put(UserTracker.KEY_LAST_ERROR_AT, clock.instant().toString())
        put(UserTracker.KEY_LAST_ERROR, message)
        if (reconnect) {
          put(UserTracker.KEY_STATUS, UserTracker.STATUS_RECONNECT_REQUIRED)
          put(UserTracker.KEY_STATUS_MESSAGE, message)
        } else if (error is TrackerRejectedException) {
          put(UserTracker.KEY_STATUS, UserTracker.STATUS_ERROR)
          put(UserTracker.KEY_STATUS_MESSAGE, message)
        }
      }
    if (reconnect && !account.reconnectRequired) {
      logger.warn { "${account.tracker} account of user ${account.userId} must be connected again: $message" }
    }
    userTrackerRepository.updateStatus(account.userId, account.tracker, values)
  }

  fun resetSyncState(
    userId: String,
    tracker: String,
  ) {
    syncStateRepository.deleteByUserIdAndTracker(userId, tracker)
    if (tracker == UserTracker.METRON) comicScrobbleRepository.deleteByUserId(userId)
  }
}
