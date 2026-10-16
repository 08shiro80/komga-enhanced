package org.gotson.komga.infrastructure.scrobbler

import org.gotson.komga.domain.model.UserTracker
import org.springframework.http.HttpHeaders
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

private val baseUrls =
  mapOf(
    UserTracker.ANILIST to "https://graphql.anilist.co",
    UserTracker.MAL to "https://api.myanimelist.net",
    UserTracker.KITSU to "https://kitsu.app/api/edge",
    UserTracker.MANGADEX to "https://api.mangadex.org",
    UserTracker.METRON to "https://metron.cloud",
  )

private fun defaultRequestFactory(): ClientHttpRequestFactory =
  JdkClientHttpRequestFactory(
    java.net.http.HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
      .build(),
  ).apply { setReadTimeout(Duration.ofSeconds(60)) }

@Component
class TrackerClients(
  private val trackerRateLimiter: TrackerRateLimiter,
  requestFactory: ClientHttpRequestFactory = defaultRequestFactory(),
) {
  private val clients =
    baseUrls.mapValues { (tracker, baseUrl) ->
      RestClient
        .builder()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .requestInterceptor(rateLimitInterceptor(tracker))
        .build()
    }

  fun client(tracker: String): RestClient = clients[tracker] ?: throw IllegalArgumentException("Unknown tracker $tracker")

  private fun rateLimitInterceptor(tracker: String) =
    ClientHttpRequestInterceptor { request, body, execution ->
      val accountKey = if (tracker == UserTracker.METRON) request.headers.getFirst(HttpHeaders.AUTHORIZATION) else null
      trackerRateLimiter.acquire(tracker, accountKey)
      val response = execution.execute(request, body)
      if (accountKey != null) trackerRateLimiter.recordDailyBudget(accountKey, response.headers)
      if (response.statusCode.value() == 429) trackerRateLimiter.backoff(tracker, parseRetryAfter(response.headers))
      response
    }
}
