package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.download.MangaDexRateLimiter
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerTokenProviderTest {
  private val userTrackerRepository = mockk<UserTrackerRepository>(relaxed = true)
  private val restTemplate = RestTemplate()
  private val server = MockRestServiceServer.createServer(restTemplate)
  private val trackerRateLimiter = TrackerRateLimiter(mockk<MangaDexRateLimiter>(relaxed = true))
  private val provider = TrackerTokenProvider(userTrackerRepository, ObjectMapper(), trackerRateLimiter, restTemplate.requestFactory)

  @Test
  fun `given an AniList client when completing the authorization then the code is exchanged with a JSON body and the token is stored`() {
    val account = UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_CLIENT_ID to "anilist-client", UserTracker.KEY_CLIENT_SECRET to "anilist-secret"))
    server
      .expect(requestTo("https://anilist.co/api/v2/oauth/token"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header("Content-Type", startsWith(MediaType.APPLICATION_JSON_VALUE)))
      .andExpect(
        content().json(
          """
          {
            "grant_type": "authorization_code",
            "client_id": "anilist-client",
            "client_secret": "anilist-secret",
            "redirect_uri": "https://komga.example/komga/api/v1/trackers/anilist/callback",
            "code": "the-code"
          }
          """.trimIndent(),
          JsonCompareMode.STRICT,
        ),
      ).andRespond(withSuccess("""{"token_type":"Bearer","expires_in":31536000,"access_token":"anilist-token"}""", MediaType.APPLICATION_JSON))

    val saved = provider.completeAuthorization(account, "the-code", "https://komga.example/komga/api/v1/trackers/anilist/callback", null)

    server.verify()
    assertThat(saved.settings).containsEntry(UserTracker.KEY_TOKEN, "anilist-token").containsKey(UserTracker.KEY_TOKEN_EXPIRES_AT)
    assertThat(saved.identityCheckRequired).isTrue()
    assertThat(provider.tokenExpiry(saved)).isAfter(Instant.now().plus(Duration.ofDays(364)))
    verify {
      userTrackerRepository.save(saved)
    }
  }

  @Test
  fun `given a MyAnimeList client with secret when completing the authorization then the code is exchanged with the PKCE verifier`() {
    val account = UserTracker("user", UserTracker.MAL, mapOf(UserTracker.KEY_CLIENT_ID to "mal-client", UserTracker.KEY_CLIENT_SECRET to "mal-secret"))
    server
      .expect(requestTo("https://myanimelist.net/v1/oauth2/token"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header("Content-Type", startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)))
      .andExpect(
        content().string(
          "grant_type=authorization_code&client_id=mal-client&client_secret=mal-secret&code=the-code&code_verifier=verifier-123" +
            "&redirect_uri=https%3A%2F%2Fkomga.example%2Fapi%2Fv1%2Ftrackers%2Fmal%2Fcallback",
        ),
      ).andRespond(withSuccess("""{"access_token":"mal-access","refresh_token":"mal-refresh","expires_in":3600}""", MediaType.APPLICATION_JSON))

    provider.completeAuthorization(account, "the-code", "https://komga.example/api/v1/trackers/mal/callback", "verifier-123")

    server.verify()
    verify {
      userTrackerRepository.save(
        UserTracker(
          "user",
          UserTracker.MAL,
          mapOf(
            UserTracker.KEY_CLIENT_ID to "mal-client",
            UserTracker.KEY_CLIENT_SECRET to "mal-secret",
            UserTracker.KEY_ACCESS_TOKEN to "mal-access",
            UserTracker.KEY_REFRESH_TOKEN to "mal-refresh",
            UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED,
          ),
        ),
      )
    }
    assertThat(provider.token(account)).isEqualTo("mal-access")
  }

  @Test
  fun `given a Kitsu login when a token is needed then the password grant is used and the token is cached`() {
    val account = UserTracker("user", UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader@example.org", UserTracker.KEY_PASSWORD to "p a*ss~"))
    server
      .expect(ExpectedCount.once(), requestTo("https://kitsu.app/api/oauth/token"))
      .andExpect(content().string("grant_type=password&username=reader%40example.org&password=p%20a%2Ass~"))
      .andRespond(withSuccess("""{"access_token":"kitsu-access","refresh_token":"kitsu-refresh","expires_in":2592000}""", MediaType.APPLICATION_JSON))

    assertThat(provider.token(account)).isEqualTo("kitsu-access")
    assertThat(provider.token(account)).isEqualTo("kitsu-access")
    server.verify()
  }

  @Test
  fun `given a token request holding the account when an interactive call needs the account then it gives up within its wait limit and a background call waits`() {
    val account = UserTracker("user", UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw"))
    val requested = CountDownLatch(1)
    val release = CountDownLatch(1)
    server
      .expect(ExpectedCount.once(), requestTo("https://kitsu.app/api/oauth/token"))
      .andRespond { request ->
        requested.countDown()
        release.await(30, TimeUnit.SECONDS)
        withSuccess("""{"access_token":"kitsu-access","refresh_token":"kitsu-refresh","expires_in":2592000}""", MediaType.APPLICATION_JSON).createResponse(request)
      }
    val holder = Executors.newFixedThreadPool(2)
    try {
      val first = holder.submit<String> { provider.token(account) }
      assertThat(requested.await(15, TimeUnit.SECONDS)).isTrue()

      assertThatThrownBy { trackerRateLimiter.withMaxWait(Duration.ofMillis(200)) { provider.token(account) } }
        .isInstanceOf(TrackerRateLimitedException::class.java)
        .hasMessage("kitsu account is busy, try again later")

      val second = holder.submit<String> { provider.token(account) }
      Thread.sleep(300)
      assertThat(second.isDone).isFalse()
      release.countDown()
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo("kitsu-access")
      assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo("kitsu-access")
      assertThat(provider.token(account)).isEqualTo("kitsu-access")
    } finally {
      holder.shutdownNow()
    }
    server.verify()
  }

  @Test
  fun `given a rejected Kitsu token when calling with the token then the refresh token is used once`() {
    val account = UserTracker("user", UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw"))
    server
      .expect(requestTo("https://kitsu.app/api/oauth/token"))
      .andExpect(content().string("grant_type=password&username=reader&password=pw"))
      .andRespond(withSuccess("""{"access_token":"first","refresh_token":"kitsu-refresh","expires_in":2592000}""", MediaType.APPLICATION_JSON))
    server
      .expect(requestTo("https://kitsu.app/api/oauth/token"))
      .andExpect(content().string("grant_type=refresh_token&refresh_token=kitsu-refresh"))
      .andRespond(withSuccess("""{"access_token":"second","refresh_token":"kitsu-refresh-2","expires_in":2592000}""", MediaType.APPLICATION_JSON))
    val used = mutableListOf<String>()

    val result =
      provider.withToken(account) { token ->
        used.add(token)
        if (token == "first") throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders(), ByteArray(0), null)
        "ok"
      }

    assertThat(result).isEqualTo("ok")
    assertThat(used).containsExactly("first", "second")
    server.verify()
  }

  @Test
  fun `given a rejected AniList token when calling with the token then the account must be connected again`() {
    val account = UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "anilist-token"))

    assertThatThrownBy {
      provider.withToken(account) { throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders(), """{"errors":[{"message":"Invalid token"}]}""".toByteArray(), null) }
    }.isInstanceOf(TrackerAuthException::class.java)
  }

  @Test
  fun `given an AniList JWT without stored expiry when reading the expiry then the exp claim is used`() {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1893456000}""".toByteArray())
    val account = UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "header.$payload.signature"))

    assertThat(provider.tokenExpiry(account)).isEqualTo(Instant.ofEpochSecond(1893456000))
  }

  @Test
  fun `given a MyAnimeList client without secret when completing the authorization then no client secret is sent`() {
    val account = UserTracker("user", UserTracker.MAL, mapOf(UserTracker.KEY_CLIENT_ID to "mal-client"))
    server
      .expect(requestTo("https://myanimelist.net/v1/oauth2/token"))
      .andExpect(
        content().string(
          "grant_type=authorization_code&client_id=mal-client&code=the-code&code_verifier=verifier-123" +
            "&redirect_uri=https%3A%2F%2Fkomga.example%2Fapi%2Fv1%2Ftrackers%2Fmal%2Fcallback",
        ),
      ).andRespond(withSuccess("""{"access_token":"mal-access","refresh_token":"mal-refresh","expires_in":3600}""", MediaType.APPLICATION_JSON))

    provider.completeAuthorization(account, "the-code", "https://komga.example/api/v1/trackers/mal/callback", "verifier-123")

    server.verify()
  }

  @Test
  fun `given a provider error that echoes secrets when completing the authorization then the error is redacted and nothing is stored`() {
    val account = UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_CLIENT_ID to "anilist-client", UserTracker.KEY_CLIENT_SECRET to "anilist-secret"))
    server
      .expect(requestTo("https://anilist.co/api/v2/oauth/token"))
      .andRespond(withBadRequest().body("""{"error":"invalid_client","hint":"anilist-secret / the-code"}"""))

    assertThatThrownBy { provider.completeAuthorization(account, "the-code", "https://komga.example/api/v1/trackers/anilist/callback", null) }
      .isInstanceOf(TrackerAuthException::class.java)
      .hasMessageContaining("HTTP 400")
      .hasMessageContaining("invalid_client")
      .hasMessageNotContaining("anilist-secret")
      .hasMessageNotContaining("the-code")
    verify(exactly = 0) { userTrackerRepository.save(any()) }
  }

  @Test
  fun `given incomplete client settings when completing the authorization then no request is sent`() {
    assertThatThrownBy { provider.completeAuthorization(UserTracker("user", UserTracker.ANILIST, emptyMap()), "c", "r", null) }
      .isInstanceOf(TrackerAuthException::class.java)
    assertThatThrownBy { provider.completeAuthorization(UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_CLIENT_ID to "id")), "c", "r", null) }
      .isInstanceOf(TrackerAuthException::class.java)
    assertThatThrownBy { provider.completeAuthorization(UserTracker("user", UserTracker.MAL, mapOf(UserTracker.KEY_CLIENT_ID to "id")), "c", "r", null) }
      .isInstanceOf(TrackerAuthException::class.java)
    assertThatThrownBy { provider.completeAuthorization(UserTracker("user", UserTracker.KITSU, mapOf(UserTracker.KEY_CLIENT_ID to "id")), "c", "r", null) }
      .isInstanceOf(TrackerAuthException::class.java)

    server.verify()
    verify(exactly = 0) { userTrackerRepository.save(any()) }
  }

  @Test
  fun `given a rate limited token request when a token is needed again then no request is sent until the Retry-After passed`() {
    val account = UserTracker("user", UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw"))
    val headers = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, "120") }
    server
      .expect(ExpectedCount.once(), requestTo("https://kitsu.app/api/oauth/token"))
      .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers))

    assertThatThrownBy { provider.token(account) }
      .isInstanceOfSatisfying(TrackerRateLimitedException::class.java) { assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(120)) }
    assertThatThrownBy { provider.token(account) }.isInstanceOf(TrackerRateLimitedException::class.java)

    server.verify()
    assertThat(trackerRateLimiter.blockedFor(UserTracker.KITSU)).isNotNull()
  }
}
