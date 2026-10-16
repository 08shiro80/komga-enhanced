package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

private val logger = KotlinLogging.logger {}

private const val ANILIST_TOKEN_URL = "https://anilist.co/api/v2/oauth/token"
private const val MAL_TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
private const val KITSU_TOKEN_URL = "https://kitsu.app/api/oauth/token"
private const val MANGADEX_TOKEN_URL = "https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token"

private fun defaultRequestFactory(): ClientHttpRequestFactory =
  JdkClientHttpRequestFactory(
    java.net.http.HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build(),
  ).apply { setReadTimeout(Duration.ofSeconds(30)) }

@Component
class TrackerTokenProvider(
  private val userTrackerRepository: UserTrackerRepository,
  private val objectMapper: ObjectMapper,
  private val trackerRateLimiter: TrackerRateLimiter,
  requestFactory: ClientHttpRequestFactory = defaultRequestFactory(),
) {
  private data class CachedToken(
    val accessToken: String,
    val expiry: Instant?,
    val refreshToken: String? = null,
  )

  private data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
  )

  private val cache = ConcurrentHashMap<String, CachedToken>()
  private val locks = ConcurrentHashMap<String, ReentrantLock>()

  private val client =
    RestClient
      .builder()
      .requestFactory(requestFactory)
      .build()

  fun token(account: UserTracker): String =
    locked(account) {
      when (account.tracker) {
        UserTracker.ANILIST -> account[UserTracker.KEY_TOKEN] ?: throw TrackerAuthException("anilist account is not connected")
        UserTracker.MAL -> validCached(account) ?: malTokenWithStoredFallback(account)
        UserTracker.KITSU -> validCached(account) ?: requestKitsuToken(account)
        UserTracker.MANGADEX -> validCached(account) ?: requestMangaDexToken(account)
        else -> throw TrackerAuthException("${account.tracker} does not use tokens")
      }
    }

  fun refreshAfterUnauthorized(account: UserTracker): String =
    locked(account) {
      when (account.tracker) {
        UserTracker.MAL -> {
          if (!canRefreshMal(account)) throw TrackerAuthException("mal access token was rejected and cannot be refreshed, connect the account again")
          refreshMalToken(account)
        }
        UserTracker.KITSU -> {
          cache.computeIfPresent(cacheKey(account)) { _, cached -> cached.copy(expiry = null) }
          requestKitsuToken(account)
        }
        UserTracker.MANGADEX -> {
          cache.remove(cacheKey(account))
          requestMangaDexToken(account)
        }
        else -> throw TrackerAuthException("${account.tracker} rejected the token, connect the account again")
      }
    }

  fun <T> withToken(
    account: UserTracker,
    call: (String) -> T,
  ): T {
    val token = token(account)
    try {
      return call(token)
    } catch (e: Exception) {
      val failure = classifyTrackerFailure(account.tracker, e, listOf(token))
      if (failure !is TrackerUnauthorizedException) throw failure
    }
    val refreshed = refreshAfterUnauthorized(account)
    try {
      return call(refreshed)
    } catch (e: Exception) {
      val failure = classifyTrackerFailure(account.tracker, e, listOf(refreshed))
      if (failure is TrackerUnauthorizedException) throw TrackerAuthException(failure.message ?: "${account.tracker} rejected the token", failure)
      throw failure
    }
  }

  fun tokenExpiry(account: UserTracker): Instant? {
    account[UserTracker.KEY_TOKEN_EXPIRES_AT]?.let { stored -> return runCatching { Instant.parse(stored) }.getOrNull() }
    if (account.tracker != UserTracker.ANILIST) return null
    val payload = account[UserTracker.KEY_TOKEN]?.split('.')?.getOrNull(1) ?: return null
    return try {
      objectMapper
        .readTree(Base64.getUrlDecoder().decode(payload))
        .get("exp")
        ?.takeIf { it.canConvertToLong() }
        ?.let { Instant.ofEpochSecond(it.asLong()) }
    } catch (_: IllegalArgumentException) {
      null
    } catch (_: JsonProcessingException) {
      null
    }
  }

  fun completeAuthorization(
    account: UserTracker,
    code: String,
    redirectUri: String,
    codeVerifier: String?,
  ): UserTracker = trackerRateLimiter.withMaxWait(interactiveMaxWait) { exchangeAuthorizationCode(account, code, redirectUri, codeVerifier) }

  private fun exchangeAuthorizationCode(
    account: UserTracker,
    code: String,
    redirectUri: String,
    codeVerifier: String?,
  ): UserTracker {
    val clientId = account[UserTracker.KEY_CLIENT_ID] ?: throw TrackerAuthException("${account.tracker} client ID is not set")
    val clientSecret = account[UserTracker.KEY_CLIENT_SECRET]
    val response =
      when (account.tracker) {
        UserTracker.ANILIST -> {
          if (clientSecret == null) throw TrackerAuthException("anilist client secret is not set")
          requestToken(
            account,
            ANILIST_TOKEN_URL,
            mapOf(
              "grant_type" to "authorization_code",
              "client_id" to clientId,
              "client_secret" to clientSecret,
              "redirect_uri" to redirectUri,
              "code" to code,
            ),
            MediaType.APPLICATION_JSON,
            listOf(clientSecret, code),
          )
        }

        UserTracker.MAL -> {
          if (codeVerifier == null) throw TrackerAuthException("mal authorization requires a PKCE code verifier")
          requestToken(
            account,
            MAL_TOKEN_URL,
            formEncode(
              "grant_type" to "authorization_code",
              "client_id" to clientId,
              "client_secret" to clientSecret,
              "code" to code,
              "code_verifier" to codeVerifier,
              "redirect_uri" to redirectUri,
            ),
            MediaType.APPLICATION_FORM_URLENCODED,
            listOf(clientSecret, code, codeVerifier),
          )
        }

        else -> throw TrackerAuthException("${account.tracker} does not support the authorization code flow")
      }

    val settings = account.settings.filterKeys { it != UserTracker.KEY_STATUS && it != UserTracker.KEY_STATUS_MESSAGE }.toMutableMap()
    settings[UserTracker.KEY_IDENTITY_CHECK] = UserTracker.IDENTITY_CHECK_REQUIRED
    if (account.tracker == UserTracker.ANILIST) {
      settings[UserTracker.KEY_TOKEN] = response.accessToken
      if (response.expiresIn > 0) {
        settings[UserTracker.KEY_TOKEN_EXPIRES_AT] = Instant.now().plusSeconds(response.expiresIn).toString()
      } else {
        settings.remove(UserTracker.KEY_TOKEN_EXPIRES_AT)
      }
    } else {
      settings[UserTracker.KEY_ACCESS_TOKEN] = response.accessToken
      if (response.refreshToken != null) settings[UserTracker.KEY_REFRESH_TOKEN] = response.refreshToken else settings.remove(UserTracker.KEY_REFRESH_TOKEN)
    }
    val updated = UserTracker(account.userId, account.tracker, settings)
    locked(account) {
      userTrackerRepository.save(updated)
      invalidate(account)
      if (account.tracker == UserTracker.MAL) cacheToken(account, response)
    }
    return updated
  }

  fun invalidate(account: UserTracker) {
    cache.remove(cacheKey(account))
  }

  private fun requestMangaDexToken(account: UserTracker): String {
    val clientId = account[UserTracker.KEY_CLIENT_ID] ?: throw TrackerAuthException("mangadex client ID is not set")
    val clientSecret = account[UserTracker.KEY_CLIENT_SECRET] ?: throw TrackerAuthException("mangadex client secret is not set")
    return passwordGrant(account, MANGADEX_TOKEN_URL, listOf("client_id" to clientId, "client_secret" to clientSecret))
  }

  private fun requestKitsuToken(account: UserTracker): String = passwordGrant(account, KITSU_TOKEN_URL, emptyList())

  private fun passwordGrant(
    account: UserTracker,
    tokenUrl: String,
    clientParams: List<Pair<String, String>>,
  ): String {
    val username = account[UserTracker.KEY_USERNAME] ?: throw TrackerAuthException("${account.tracker} username is not set")
    val password = account[UserTracker.KEY_PASSWORD] ?: throw TrackerAuthException("${account.tracker} password is not set")
    val secrets = listOf(password) + clientParams.map { it.second }

    val cachedRefresh = cache[cacheKey(account)]?.refreshToken
    val refreshed =
      cachedRefresh?.let { refreshTkn ->
        try {
          requestToken(
            account,
            tokenUrl,
            formEncode("grant_type" to "refresh_token", "refresh_token" to refreshTkn, *clientParams.toTypedArray()),
            MediaType.APPLICATION_FORM_URLENCODED,
            secrets + refreshTkn,
          )
        } catch (e: TrackerAuthException) {
          logger.debug { "${account.tracker} token refresh failed for user ${account.userId}, requesting a new token: ${e.message}" }
          null
        }
      }
    val response =
      refreshed
        ?: requestToken(
          account,
          tokenUrl,
          formEncode("grant_type" to "password", "username" to username, "password" to password, *clientParams.toTypedArray()),
          MediaType.APPLICATION_FORM_URLENCODED,
          secrets,
        )
    return cacheToken(account, response)
  }

  private fun malTokenWithStoredFallback(account: UserTracker): String {
    val stored = account[UserTracker.KEY_ACCESS_TOKEN]
    if (!canRefreshMal(account)) return stored ?: throw TrackerAuthException("mal account is not connected")
    return try {
      refreshMalToken(account)
    } catch (e: TrackerAuthException) {
      stored ?: throw e
    }
  }

  private fun canRefreshMal(account: UserTracker): Boolean =
    account[UserTracker.KEY_REFRESH_TOKEN] != null &&
      account[UserTracker.KEY_CLIENT_ID] != null

  private fun refreshMalToken(account: UserTracker): String {
    val current =
      userTrackerRepository.findByUserIdAndTracker(account.userId, account.tracker)
        ?: throw TrackerAuthException("${account.tracker} account is not linked")
    val refreshTkn = current[UserTracker.KEY_REFRESH_TOKEN] ?: throw TrackerAuthException("${account.tracker} has no refresh token, connect the account again")
    val clientId = current[UserTracker.KEY_CLIENT_ID] ?: throw TrackerAuthException("${account.tracker} client ID is not set")
    val clientSecret = current[UserTracker.KEY_CLIENT_SECRET]
    val response =
      requestToken(
        account,
        MAL_TOKEN_URL,
        formEncode(
          "grant_type" to "refresh_token",
          "refresh_token" to refreshTkn,
          "client_id" to clientId,
          "client_secret" to clientSecret,
        ),
        MediaType.APPLICATION_FORM_URLENCODED,
        listOf(refreshTkn, clientSecret),
      )
    val persisted =
      userTrackerRepository.updateRefreshedTokens(
        account.userId,
        account.tracker,
        current[UserTracker.KEY_ACCESS_TOKEN],
        refreshTkn,
        response.accessToken,
        response.refreshToken,
      )
    if (!persisted) throw TrackerUnavailableException("${account.tracker} account changed during token refresh, discarding the refreshed token")
    return cacheToken(account, response)
  }

  private fun requestToken(
    account: UserTracker,
    tokenUrl: String,
    body: Any,
    contentType: MediaType,
    secrets: Collection<String?>,
  ): TokenResponse {
    trackerRateLimiter.acquire(account.tracker)
    val response =
      try {
        client
          .post()
          .uri(tokenUrl)
          .contentType(contentType)
          .accept(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(String::class.java)
      } catch (e: RestClientResponseException) {
        val detail = e.providerErrorDetail(secrets)
        throw when (e.statusCode.value()) {
          400, 401, 403 -> TrackerAuthException("${account.tracker} rejected the login ($detail)", e)
          429 -> {
            val retryAfter = e.responseHeaders?.let { parseRetryAfter(it) }
            trackerRateLimiter.backoff(account.tracker, retryAfter)
            TrackerRateLimitedException("${account.tracker} token request was rate limited ($detail)", retryAfter, e)
          }
          else -> TrackerUnavailableException("${account.tracker} token request failed ($detail)", e)
        }
      } catch (e: RestClientException) {
        throw TrackerUnavailableException("${account.tracker} token request failed: ${e.message}", e)
      }
    val json =
      try {
        response?.let { objectMapper.readTree(it) }
      } catch (e: JsonProcessingException) {
        throw TrackerUnavailableException("${account.tracker} token response is not valid JSON", e)
      } ?: throw TrackerUnavailableException("${account.tracker} token response is empty")
    val accessTkn =
      json
        .get("access_token")
        ?.takeIf { it.isTextual }
        ?.asText()
        ?: throw TrackerUnavailableException("${account.tracker} token response has no access_token")
    val refreshTkn =
      json
        .get("refresh_token")
        ?.takeIf { it.isTextual }
        ?.asText()
    return TokenResponse(accessTkn, refreshTkn, json.get("expires_in")?.asLong() ?: 0)
  }

  private fun cacheToken(
    account: UserTracker,
    response: TokenResponse,
  ): String {
    cache[cacheKey(account)] = CachedToken(response.accessToken, Instant.now().plusSeconds(response.expiresIn - 60), response.refreshToken)
    logger.debug { "${account.tracker} token obtained for user ${account.userId} (expires in ${response.expiresIn}s)" }
    return response.accessToken
  }

  private fun validCached(account: UserTracker): String? =
    cache[cacheKey(account)]
      ?.takeIf { it.expiry != null && Instant.now().isBefore(it.expiry) }
      ?.accessToken

  private fun <T> locked(
    account: UserTracker,
    call: () -> T,
  ): T {
    val lock = locks.computeIfAbsent(cacheKey(account)) { ReentrantLock() }
    val maxWait = trackerRateLimiter.maxWait()
    if (maxWait == null) {
      lock.lock()
    } else if (!lock.tryLock(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
      throw TrackerRateLimitedException("${account.tracker} account is busy, try again later", maxWait)
    }
    try {
      return call()
    } finally {
      lock.unlock()
    }
  }

  private fun cacheKey(account: UserTracker) = "${account.userId}:${account.tracker}"
}
