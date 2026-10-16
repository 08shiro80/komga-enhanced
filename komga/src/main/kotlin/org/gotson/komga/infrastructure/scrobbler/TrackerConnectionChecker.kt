package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.infrastructure.metadata.metron.MetronHttp
import org.gotson.komga.infrastructure.rate.MetronRateLimiter
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.util.Base64

data class TrackerCheckResult(
  val success: Boolean,
  val message: String,
)

@Component
class TrackerConnectionChecker(
  private val trackerTokenProvider: TrackerTokenProvider,
  private val rateLimiter: MetronRateLimiter,
  private val objectMapper: ObjectMapper,
) {
  private val client =
    RestClient
      .builder()
      .requestFactory(
        JdkClientHttpRequestFactory(
          java.net.http.HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build(),
        ).apply { setReadTimeout(Duration.ofSeconds(30)) },
      ).build()

  private val metronClient = MetronHttp.restClient()

  fun check(account: UserTracker): TrackerCheckResult {
    if (!account.linked) return TrackerCheckResult(false, "Account is not fully configured")
    return try {
      when (account.tracker) {
        UserTracker.ANILIST -> checkAnilist(account)
        UserTracker.MAL -> checkMal(account)
        UserTracker.KITSU -> checkKitsu(account)
        UserTracker.MANGADEX -> checkMangaDex(account)
        UserTracker.METRON -> checkMetron(account)
        else -> TrackerCheckResult(false, "Unknown tracker")
      }
    } catch (e: RestClientResponseException) {
      TrackerCheckResult(false, "${account.tracker} rejected the request (HTTP ${e.statusCode.value()})")
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      TrackerCheckResult(false, "Connection test interrupted")
    } catch (e: Exception) {
      TrackerCheckResult(false, "Connection failed: ${e.message}")
    }
  }

  private fun checkAnilist(account: UserTracker): TrackerCheckResult {
    val response =
      client
        .post()
        .uri("https://graphql.anilist.co")
        .header("Authorization", "Bearer ${account[UserTracker.KEY_TOKEN]}")
        .contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("query" to "{ Viewer { name } }"))
        .retrieve()
        .body(String::class.java)
    val json = response?.let { objectMapper.readTree(it) }
    val name = json?.at("/data/Viewer/name")?.asText(null)
    return if (name.isNullOrBlank()) {
      TrackerCheckResult(false, "AniList did not return a user: ${json?.get("errors") ?: "empty response"}")
    } else {
      TrackerCheckResult(true, "Connected as $name")
    }
  }

  private fun checkMal(account: UserTracker): TrackerCheckResult {
    val name =
      withRefresh(
        { trackerTokenProvider.malToken(account) },
        { trackerTokenProvider.refreshMal(account) },
      ) { token ->
        client
          .get()
          .uri("https://api.myanimelist.net/v2/users/@me")
          .header("Authorization", "Bearer $token")
          .retrieve()
          .body(String::class.java)
          ?.let { objectMapper.readTree(it).get("name")?.asText() }
      }
    return if (name.isNullOrBlank()) TrackerCheckResult(false, "MyAnimeList did not return a user") else TrackerCheckResult(true, "Connected as $name")
  }

  private fun checkKitsu(account: UserTracker): TrackerCheckResult {
    val name =
      withRefresh(
        { trackerTokenProvider.kitsuToken(account) },
        { trackerTokenProvider.refreshKitsu(account) },
      ) { token ->
        client
          .get()
          .uri("https://kitsu.app/api/edge/users?filter[self]=true")
          .header("Authorization", "Bearer $token")
          .header("Accept", "application/vnd.api+json")
          .retrieve()
          .body(String::class.java)
          ?.let {
            objectMapper
              .readTree(it)
              .at("/data/0/attributes/name")
              .asText(null)
          }
      }
    return if (name.isNullOrBlank()) TrackerCheckResult(false, "Kitsu did not return a user") else TrackerCheckResult(true, "Connected as $name")
  }

  private fun checkMangaDex(account: UserTracker): TrackerCheckResult {
    trackerTokenProvider.invalidate(account)
    val token = trackerTokenProvider.mangadexToken(account) ?: return TrackerCheckResult(false, "MangaDex authentication failed")
    val response =
      client
        .get()
        .uri("https://api.mangadex.org/user/me")
        .header("Authorization", "Bearer $token")
        .retrieve()
        .body(String::class.java)
    val name = response?.let { objectMapper.readTree(it).at("/data/attributes/username").asText(null) }
    return TrackerCheckResult(true, if (name.isNullOrBlank()) "Authenticated" else "Connected as $name")
  }

  private fun checkMetron(account: UserTracker): TrackerCheckResult {
    while (!rateLimiter.tryAcquire()) {
      Thread.sleep(rateLimiter.suggestedBackoffMs().coerceAtLeast(50))
    }
    val credentials = "${account[UserTracker.KEY_USERNAME]}:${account[UserTracker.KEY_PASSWORD]}"
    metronClient
      .get()
      .uri("/api/series/?name=Batman")
      .header("Authorization", "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}")
      .retrieve()
      .body(String::class.java)
    return TrackerCheckResult(true, "Connected as ${account[UserTracker.KEY_USERNAME]}")
  }

  private fun withRefresh(
    token: () -> String?,
    refresh: () -> String?,
    call: (String) -> String?,
  ): String? {
    val current = token() ?: return null
    return try {
      call(current)
    } catch (e: RestClientResponseException) {
      if (e.statusCode.value() != 401 && e.statusCode.value() != 403) throw e
      val refreshed = refresh() ?: throw e
      call(refreshed)
    }
  }
}
