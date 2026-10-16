package org.gotson.komga.infrastructure.scrobbler

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.UserTracker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerAuthorizationProviderTest {
  private var now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } answers { now } }
  private val provider = TrackerAuthorizationProvider(clock)

  private val anilist = UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_CLIENT_ID to "anilist-client", UserTracker.KEY_CLIENT_SECRET to "anilist-secret"))
  private val mal = UserTracker("user", UserTracker.MAL, mapOf(UserTracker.KEY_CLIENT_ID to "mal-client"))
  private val redirectUri = "https://komga.example/komga/api/v1/trackers/anilist/callback"

  private fun query(url: String): Map<String, String> =
    URI(url)
      .rawQuery
      .split("&")
      .associate {
        val (key, value) = it.split("=", limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
      }

  private fun stateOf(url: String) = query(url).getValue("state")

  @Test
  fun `given an AniList client when starting then the authorize URL has no PKCE and no secret`() {
    val url = provider.start(anilist, redirectUri, "/komga/next/account/trackers")

    assertThat(url).startsWith("https://anilist.co/api/v2/oauth/authorize?").doesNotContain("anilist-secret")
    assertThat(query(url))
      .containsEntry("response_type", "code")
      .containsEntry("client_id", "anilist-client")
      .containsEntry("redirect_uri", redirectUri)
      .containsKey("state")
      .doesNotContainKeys("code_challenge", "code_challenge_method")
    assertThat(provider.consume(stateOf(url), "user", UserTracker.ANILIST).codeVerifier).isNull()
  }

  @Test
  fun `given a MyAnimeList client when starting then the challenge is the plain verifier of the pending authorization`() {
    val url = provider.start(mal, redirectUri, "/account/trackers")
    val params = query(url)

    assertThat(url).startsWith("https://myanimelist.net/v1/oauth2/authorize?")
    assertThat(params).containsEntry("code_challenge_method", "plain")
    val challenge = params.getValue("code_challenge")
    assertThat(challenge).hasSizeBetween(43, 128).matches("[A-Za-z0-9._~-]+")

    val pending = provider.consume(params.getValue("state"), "user", UserTracker.MAL)

    assertThat(pending.codeVerifier).isEqualTo(challenge)
    assertThat(pending.redirectUri).isEqualTo(redirectUri)
    assertThat(pending.returnTo).isEqualTo("/account/trackers")
  }

  @Test
  fun `given a state when consuming twice then the second use is rejected`() {
    val state = stateOf(provider.start(anilist, redirectUri, "/account/trackers"))

    provider.consume(state, "user", UserTracker.ANILIST)

    assertThatThrownBy { provider.consume(state, "user", UserTracker.ANILIST) }
      .isInstanceOfSatisfying(TrackerAuthorizationStateException::class.java) { assertThat(it.userMismatch).isFalse() }
  }

  @Test
  fun `given a state of another user or tracker when consuming then it is rejected without being used up`() {
    val state = stateOf(provider.start(anilist, redirectUri, "/account/trackers"))

    assertThatThrownBy { provider.consume(state, "attacker", UserTracker.ANILIST) }
      .isInstanceOfSatisfying(TrackerAuthorizationStateException::class.java) { assertThat(it.userMismatch).isTrue() }
    assertThatThrownBy { provider.consume(state, "user", UserTracker.MAL) }
      .isInstanceOfSatisfying(TrackerAuthorizationStateException::class.java) { assertThat(it.userMismatch).isTrue() }

    assertThat(provider.consume(state, "user", UserTracker.ANILIST).userId).isEqualTo("user")
  }

  @Test
  fun `given an expired state when consuming then it is rejected`() {
    val state = stateOf(provider.start(anilist, redirectUri, "/account/trackers"))

    now = now.plus(Duration.ofMinutes(11))

    assertThatThrownBy { provider.consume(state, "user", UserTracker.ANILIST) }
      .isInstanceOfSatisfying(TrackerAuthorizationStateException::class.java) { assertThat(it.userMismatch).isFalse() }
  }

  @Test
  fun `given a pending authorization when starting again then the previous state is discarded`() {
    val first = stateOf(provider.start(anilist, redirectUri, "/account/trackers"))
    val second = stateOf(provider.start(anilist, redirectUri, "/account/trackers"))

    assertThat(second).isNotEqualTo(first)
    assertThatThrownBy { provider.consume(first, "user", UserTracker.ANILIST) }
      .isInstanceOf(TrackerAuthorizationStateException::class.java)
    assertThat(provider.consume(second, "user", UserTracker.ANILIST).tracker).isEqualTo(UserTracker.ANILIST)
  }

  @Test
  fun `given missing client settings or an unsupported tracker when starting then no state is created`() {
    assertThatThrownBy { provider.start(UserTracker("user", UserTracker.MAL, emptyMap()), redirectUri, "/") }
      .isInstanceOf(TrackerAuthException::class.java)
    assertThatThrownBy { provider.start(UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_CLIENT_ID to "id")), redirectUri, "/") }
      .isInstanceOf(TrackerAuthException::class.java)
    assertThatThrownBy { provider.start(UserTracker("user", UserTracker.KITSU, mapOf(UserTracker.KEY_CLIENT_ID to "id")), redirectUri, "/") }
      .isInstanceOf(TrackerAuthException::class.java)
  }
}
