package org.gotson.komga.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserTrackerTest {
  private fun linked(
    tracker: String,
    vararg settings: Pair<String, String>,
  ) = UserTracker("u", tracker, settings.toMap()).linked

  @Test
  fun `given single-alternative trackers when checking linked then all required keys are needed`() {
    assertThat(linked(UserTracker.ANILIST, UserTracker.KEY_TOKEN to "t")).isTrue()
    assertThat(linked(UserTracker.ANILIST, UserTracker.KEY_CLIENT_ID to "c")).isFalse()
    assertThat(linked(UserTracker.MAL, UserTracker.KEY_ACCESS_TOKEN to "a")).isTrue()
    assertThat(linked(UserTracker.MAL, UserTracker.KEY_CLIENT_ID to "c", UserTracker.KEY_REFRESH_TOKEN to "r")).isFalse()
    assertThat(linked(UserTracker.KITSU, UserTracker.KEY_USERNAME to "u", UserTracker.KEY_PASSWORD to "p")).isTrue()
    assertThat(linked(UserTracker.KITSU, UserTracker.KEY_USERNAME to "u")).isFalse()
    assertThat(
      linked(
        UserTracker.MANGADEX,
        UserTracker.KEY_USERNAME to "u",
        UserTracker.KEY_PASSWORD to "p",
        UserTracker.KEY_CLIENT_ID to "c",
        UserTracker.KEY_CLIENT_SECRET to "s",
      ),
    ).isTrue()
    assertThat(linked(UserTracker.MANGADEX, UserTracker.KEY_USERNAME to "u", UserTracker.KEY_PASSWORD to "p", UserTracker.KEY_CLIENT_ID to "c")).isFalse()
  }

  @Test
  fun `given Metron when checking linked then an API token or a complete login is enough`() {
    assertThat(linked(UserTracker.METRON, UserTracker.KEY_TOKEN to "t")).isTrue()
    assertThat(linked(UserTracker.METRON, UserTracker.KEY_USERNAME to "u", UserTracker.KEY_PASSWORD to "p")).isTrue()
    assertThat(linked(UserTracker.METRON, UserTracker.KEY_PASSWORD to "p")).isFalse()
    assertThat(linked(UserTracker.METRON, UserTracker.KEY_TOKEN to " ")).isFalse()
  }

  @Test
  fun `given only a stored enabled flag when checking then the account is neither linked nor active`() {
    val paused = UserTracker("u", UserTracker.KITSU, mapOf(UserTracker.KEY_ENABLED to "false"))

    assertThat(paused.enabled).isFalse()
    assertThat(paused.linked).isFalse()
    assertThat(paused.active).isFalse()
  }

  @Test
  fun `given an account that must be connected again when checking then it is linked but not active`() {
    val account = UserTracker("u", UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t", UserTracker.KEY_STATUS to UserTracker.STATUS_RECONNECT_REQUIRED))

    assertThat(account.linked).isTrue()
    assertThat(account.reconnectRequired).isTrue()
    assertThat(account.active).isFalse()
  }

  @Test
  fun `given an unknown tracker when checking linked then it is never linked`() {
    assertThat(linked("unknown", UserTracker.KEY_TOKEN to "t")).isFalse()
  }
}
