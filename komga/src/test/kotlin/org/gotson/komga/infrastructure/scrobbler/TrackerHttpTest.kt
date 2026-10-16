package org.gotson.komga.infrastructure.scrobbler

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.UserTracker
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.time.Duration

class TrackerHttpTest {
  private fun responseError(
    status: Int,
    body: String,
  ) = RestClientResponseException("error", status, "status", HttpHeaders(), body.toByteArray(), Charsets.UTF_8)

  @Test
  fun `given a provider error body with secrets when building the detail then secrets are redacted and whitespace is collapsed`() {
    val detail =
      responseError(401, "{\n  \"error\": \"invalid_grant\",\n  \"echo\": \"client-secret auth-code pkce-verifier\"\n}")
        .providerErrorDetail(listOf("client-secret", "auth-code", "pkce-verifier", null, " "))

    assertThat(detail)
      .isEqualTo("HTTP 401: { \"error\": \"invalid_grant\", \"echo\": \"*** *** ***\" }")
  }

  @Test
  fun `given a long provider error body when building the detail then the body is cut to 300 characters`() {
    val detail = responseError(500, "x".repeat(1000)).providerErrorDetail(emptyList())

    assertThat(detail).isEqualTo("HTTP 500: ${"x".repeat(300)}")
  }

  @Test
  fun `given an empty provider error body when building the detail then only the status is returned`() {
    assertThat(responseError(403, "  \n ").providerErrorDetail(emptyList())).isEqualTo("HTTP 403")
  }

  @Test
  fun `given provider failures when classifying then they map to the tracker failure types`() {
    val retryHeaders = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, "120") }
    val rateLimited = RestClientResponseException("limited", 429, "Too Many Requests", retryHeaders, ByteArray(0), Charsets.UTF_8)

    assertThat(classifyTrackerFailure(UserTracker.MAL, responseError(401, ""))).isInstanceOf(TrackerUnauthorizedException::class.java)
    assertThat(classifyTrackerFailure(UserTracker.ANILIST, responseError(400, """{"errors":[{"message":"Invalid token"}]}"""))).isInstanceOf(TrackerUnauthorizedException::class.java)
    assertThat(classifyTrackerFailure(UserTracker.KITSU, responseError(400, "bad"))).isInstanceOf(TrackerRejectedException::class.java)
    assertThat(classifyTrackerFailure(UserTracker.MAL, responseError(403, "forbidden"))).isInstanceOf(TrackerRejectedException::class.java)
    assertThat(classifyTrackerFailure(UserTracker.MAL, responseError(404, ""))).isInstanceOf(TrackerNotFoundException::class.java)
    assertThat(classifyTrackerFailure(UserTracker.MAL, responseError(503, ""))).isInstanceOf(TrackerUnavailableException::class.java)
    assertThat(classifyTrackerFailure(UserTracker.METRON, rateLimited))
      .isInstanceOfSatisfying(TrackerRateLimitedException::class.java) { assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(120)) }
    assertThat(classifyTrackerFailure(UserTracker.KITSU, ResourceAccessException("timeout"))).isInstanceOf(TrackerUnavailableException::class.java)
  }

  @Test
  fun `given form parameters when encoding then RFC 3986 percent-encoding is used and null values are skipped`() {
    val encoded =
      formEncode(
        "username" to "reader name",
        "password" to "p*ss~w+rd&=é",
        "client_secret" to null,
        "grant_type" to "password",
      )

    assertThat(encoded).isEqualTo("username=reader%20name&password=p%2Ass~w%2Brd%26%3D%C3%A9&grant_type=password")
  }
}
