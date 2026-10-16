package org.gotson.komga.infrastructure.scrobbler

import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.infrastructure.comicscrobbler.MetronTrackerClient
import org.gotson.komga.infrastructure.metadata.metron.MetronHttp
import org.springframework.stereotype.Component

data class TrackerCheckResult(
  val success: Boolean,
  val message: String,
)

@Component
class TrackerConnectionChecker(
  private val trackerTokenProvider: TrackerTokenProvider,
  private val trackerStatusRecorder: TrackerStatusRecorder,
  private val mangaDexTrackerClient: MangaDexTrackerClient,
  private val metronTrackerClient: MetronTrackerClient,
  private val trackerRateLimiter: TrackerRateLimiter,
  mangaTrackerClients: List<MangaTrackerClient>,
) {
  private val clients = mangaTrackerClients.associateBy { it.tracker }

  fun check(account: UserTracker): TrackerCheckResult {
    if (account.unreadableKeys.isNotEmpty()) return TrackerCheckResult(false, "The stored credentials cannot be decrypted, link the account again")
    if (!account.linked) return TrackerCheckResult(false, "Account is not fully configured")
    return try {
      trackerRateLimiter.withMaxWait(interactiveMaxWait) { checkRemote(account) }
    } catch (e: TrackerCallException) {
      trackerStatusRecorder.failure(account, e)
      TrackerCheckResult(false, e.message ?: "${account.tracker} connection failed")
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      TrackerCheckResult(false, "Connection test interrupted")
    }
  }

  fun confirmIdentity(account: UserTracker): UserTracker {
    val remote = identify(account)
    return account.copy(
      settings =
        account.settings - UserTracker.KEY_IDENTITY_CHECK +
          mapOf(UserTracker.KEY_ACCOUNT_NAME to remote.name, UserTracker.KEY_REMOTE_USER_ID to remote.id),
    )
  }

  private fun checkRemote(account: UserTracker): TrackerCheckResult =
    when (account.tracker) {
      UserTracker.METRON -> checkMetron(account)
      UserTracker.MANGADEX -> {
        trackerTokenProvider.invalidate(account)
        TrackerCheckResult(true, "Connected as ${identify(account).name}")
      }
      else -> {
        if (clients[account.tracker] == null) {
          TrackerCheckResult(false, "Unknown tracker")
        } else {
          if (account.tracker == UserTracker.KITSU) trackerTokenProvider.invalidate(account)
          TrackerCheckResult(true, "Connected as ${identify(account).name}")
        }
      }
    }

  private fun identify(account: UserTracker): RemoteAccount {
    val remote =
      if (account.tracker == UserTracker.MANGADEX) {
        trackerTokenProvider.withToken(account) { mangaDexTrackerClient.viewer(it) }
      } else {
        val client = clients[account.tracker] ?: throw TrackerRejectedException("${account.tracker} cannot identify the remote account")
        trackerTokenProvider.withToken(account) { client.viewer(account, it) }
      }
    trackerStatusRecorder.success(account, remote.name, remote.id)
    return remote
  }

  private fun checkMetron(account: UserTracker): TrackerCheckResult {
    val authorization = MetronHttp.authorization(account) ?: return TrackerCheckResult(false, "Account is not fully configured")
    try {
      metronTrackerClient.checkAccess(authorization)
    } catch (e: Exception) {
      throw classifyTrackerFailure(UserTracker.METRON, e, listOf(authorization))
    }
    val name = if (account[UserTracker.KEY_TOKEN] != null) "API token" else account[UserTracker.KEY_USERNAME]
    trackerStatusRecorder.success(account, name)
    return TrackerCheckResult(true, if (account[UserTracker.KEY_TOKEN] != null) "Connected with API token" else "Connected as $name")
  }
}
