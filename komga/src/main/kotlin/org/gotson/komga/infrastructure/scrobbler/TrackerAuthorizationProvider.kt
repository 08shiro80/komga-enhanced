package org.gotson.komga.infrastructure.scrobbler

import org.gotson.komga.domain.model.UserTracker
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val ANILIST_AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
private const val MAL_AUTHORIZE_URL = "https://myanimelist.net/v1/oauth2/authorize"

private val stateTtl = Duration.ofMinutes(10)

data class PendingTrackerAuthorization(
  val userId: String,
  val tracker: String,
  val codeVerifier: String?,
  val redirectUri: String,
  val returnTo: String,
  val expiresAt: Instant,
)

class TrackerAuthorizationStateException(
  message: String,
  val userMismatch: Boolean,
) : IllegalStateException(message)

@Component
class TrackerAuthorizationProvider(
  private val clock: Clock = Clock.systemUTC(),
) {
  private val pending = ConcurrentHashMap<String, PendingTrackerAuthorization>()

  private val random = SecureRandom()

  fun start(
    account: UserTracker,
    redirectUri: String,
    returnTo: String,
  ): String {
    val clientId = account[UserTracker.KEY_CLIENT_ID] ?: throw TrackerAuthException("${account.tracker} client ID is not set")
    val now = clock.instant()
    val authorizeUrl =
      when (account.tracker) {
        UserTracker.ANILIST -> {
          if (account[UserTracker.KEY_CLIENT_SECRET] == null) throw TrackerAuthException("anilist client secret is not set")
          ANILIST_AUTHORIZE_URL
        }

        UserTracker.MAL -> MAL_AUTHORIZE_URL

        else -> throw TrackerAuthException("${account.tracker} does not support the authorization code flow")
      }
    val codeVerifier = if (account.tracker == UserTracker.MAL) randomToken(64) else null
    val state = randomToken(32)

    pending.values.removeIf { it.expiresAt.isBefore(now) || (it.userId == account.userId && it.tracker == account.tracker) }
    pending[state] = PendingTrackerAuthorization(account.userId, account.tracker, codeVerifier, redirectUri, returnTo, now.plus(stateTtl))

    val query =
      formEncode(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "state" to state,
        "code_challenge" to codeVerifier,
        "code_challenge_method" to codeVerifier?.let { "plain" },
      )
    return "$authorizeUrl?$query"
  }

  fun consume(
    state: String,
    userId: String,
    tracker: String,
  ): PendingTrackerAuthorization {
    val entry = pending[state] ?: throw TrackerAuthorizationStateException("Unknown or already used authorization state", false)
    if (entry.expiresAt.isBefore(clock.instant())) {
      pending.remove(state, entry)
      throw TrackerAuthorizationStateException("Authorization state expired", false)
    }
    if (entry.userId != userId || entry.tracker != tracker) throw TrackerAuthorizationStateException("Authorization state belongs to another user or tracker", true)
    if (!pending.remove(state, entry)) throw TrackerAuthorizationStateException("Unknown or already used authorization state", false)
    return entry
  }

  private fun randomToken(bytes: Int): String =
    Base64
      .getUrlEncoder()
      .withoutPadding()
      .encodeToString(ByteArray(bytes).also { random.nextBytes(it) })
}
