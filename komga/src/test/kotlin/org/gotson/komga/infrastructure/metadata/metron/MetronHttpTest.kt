package org.gotson.komga.infrastructure.metadata.metron

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.UserTracker
import org.junit.jupiter.api.Test
import java.util.Base64

class MetronHttpTest {
  @Test
  fun `given an API token and a login when building the authorization then the bearer token is preferred`() {
    val account = UserTracker("u", UserTracker.METRON, mapOf(UserTracker.KEY_TOKEN to "api-token", UserTracker.KEY_USERNAME to "user", UserTracker.KEY_PASSWORD to "pass"))

    assertThat(MetronHttp.authorization(account)).isEqualTo("Bearer api-token")
  }

  @Test
  fun `given only a login when building the authorization then basic auth is used`() {
    val account = UserTracker("u", UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "user", UserTracker.KEY_PASSWORD to "pass"))

    assertThat(MetronHttp.authorization(account)).isEqualTo("Basic ${Base64.getEncoder().encodeToString("user:pass".toByteArray())}")
  }

  @Test
  fun `given an incomplete login when building the authorization then there is none`() {
    val account = UserTracker("u", UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "user"))

    assertThat(MetronHttp.authorization(account)).isNull()
    assertThat(account.linked).isFalse()
  }
}
