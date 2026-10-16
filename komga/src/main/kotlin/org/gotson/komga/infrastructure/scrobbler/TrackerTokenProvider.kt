package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val MAL_TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
private const val KITSU_TOKEN_URL = "https://kitsu.app/api/oauth/token"
private const val MANGADEX_TOKEN_URL = "https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token"

@Component
class TrackerTokenProvider(
  private val userTrackerRepository: UserTrackerRepository,
  private val objectMapper: ObjectMapper,
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
  private val kitsuUserIds = ConcurrentHashMap<String, String>()
  private val locks = ConcurrentHashMap<String, Any>()

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

  fun malToken(account: UserTracker): String? = oauthToken(account, MAL_TOKEN_URL)

  fun refreshMal(account: UserTracker): String? = synchronized(lockFor(account)) { refreshOauth(account, MAL_TOKEN_URL) }

  fun kitsuToken(account: UserTracker): String? = oauthToken(account, KITSU_TOKEN_URL)

  fun refreshKitsu(account: UserTracker): String? = synchronized(lockFor(account)) { refreshOauth(account, KITSU_TOKEN_URL) }

  fun mangadexToken(account: UserTracker): String? =
    synchronized(lockFor(account)) {
      validCached(account) ?: requestMangaDexToken(account)
    }

  fun invalidate(account: UserTracker) {
    cache.remove(cacheKey(account))
    kitsuUserIds.remove(cacheKey(account))
  }

  fun kitsuUserId(
    account: UserTracker,
    token: String,
  ): String? {
    val key = cacheKey(account)
    kitsuUserIds[key]?.let { return it }
    return try {
      val response =
        client
          .get()
          .uri("https://kitsu.app/api/edge/users?filter[self]=true")
          .header("Authorization", "Bearer $token")
          .header("Accept", "application/vnd.api+json")
          .retrieve()
          .body(String::class.java)
      val id =
        response
          ?.let { objectMapper.readTree(it) }
          ?.at("/data/0/id")
          ?.asText(null)
          ?.takeIf { it.isNotBlank() }
      if (id != null) {
        kitsuUserIds[key] = id
      } else {
        logger.warn { "Kitsu self-user lookup returned no user id for user ${account.userId}" }
      }
      id
    } catch (e: Exception) {
      logger.warn(e) { "Kitsu self-user lookup failed for user ${account.userId}: ${e.message}" }
      null
    }
  }

  private fun requestMangaDexToken(account: UserTracker): String? {
    val username = account[UserTracker.KEY_USERNAME] ?: return null
    val password = account[UserTracker.KEY_PASSWORD] ?: return null
    val clientId = account[UserTracker.KEY_CLIENT_ID] ?: return null
    val clientSecret = account[UserTracker.KEY_CLIENT_SECRET] ?: return null

    val cachedRefresh = cache[cacheKey(account)]?.refreshToken
    val refreshed =
      if (cachedRefresh != null) {
        requestToken(
          account,
          MANGADEX_TOKEN_URL,
          "grant_type=refresh_token&refresh_token=${encode(cachedRefresh)}&client_id=${encode(clientId)}&client_secret=${encode(clientSecret)}",
        )
      } else {
        null
      }
    val response =
      refreshed
        ?: requestToken(
          account,
          MANGADEX_TOKEN_URL,
          "grant_type=password&username=${encode(username)}&password=${encode(password)}&client_id=${encode(clientId)}&client_secret=${encode(clientSecret)}",
        )
        ?: return null
    return cacheToken(account, response)
  }

  private fun oauthToken(
    account: UserTracker,
    tokenUrl: String,
  ): String? =
    synchronized(lockFor(account)) {
      validCached(account)
        ?: (if (canRefresh(account)) refreshOauth(account, tokenUrl) else null)
        ?: account[UserTracker.KEY_ACCESS_TOKEN]
    }

  private fun canRefresh(account: UserTracker): Boolean =
    account[UserTracker.KEY_REFRESH_TOKEN] != null &&
      account[UserTracker.KEY_CLIENT_ID] != null &&
      account[UserTracker.KEY_CLIENT_SECRET] != null

  private fun refreshOauth(
    account: UserTracker,
    tokenUrl: String,
  ): String? {
    val current = userTrackerRepository.findByUserIdAndTracker(account.userId, account.tracker) ?: return null
    val refreshTkn = current[UserTracker.KEY_REFRESH_TOKEN] ?: return null
    val clientId = current[UserTracker.KEY_CLIENT_ID] ?: return null
    val clientSecret = current[UserTracker.KEY_CLIENT_SECRET] ?: return null
    val response =
      requestToken(
        account,
        tokenUrl,
        "grant_type=refresh_token&refresh_token=${encode(refreshTkn)}&client_id=${encode(clientId)}&client_secret=${encode(clientSecret)}",
      ) ?: return null
    val persisted =
      userTrackerRepository.updateRefreshedTokens(
        account.userId,
        account.tracker,
        current[UserTracker.KEY_ACCESS_TOKEN],
        refreshTkn,
        response.accessToken,
        response.refreshToken,
      )
    if (!persisted) {
      logger.warn { "${account.tracker} account of user ${account.userId} changed during token refresh, discarding the refreshed token" }
      return null
    }
    return cacheToken(account, response)
  }

  private fun requestToken(
    account: UserTracker,
    tokenUrl: String,
    body: String,
  ): TokenResponse? =
    try {
      val response =
        client
          .post()
          .uri(tokenUrl)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(body)
          .retrieve()
          .body(String::class.java)
      val json = response?.let { objectMapper.readTree(it) }
      val accessTkn =
        json
          ?.get("access_token")
          ?.takeIf { it.isTextual }
          ?.asText()
      if (json == null || accessTkn == null) {
        logger.error { "${account.tracker} token response for user ${account.userId} has no access_token" }
        null
      } else {
        val refreshTkn =
          json
            .get("refresh_token")
            ?.takeIf { it.isTextual }
            ?.asText()
        TokenResponse(accessTkn, refreshTkn, json.get("expires_in")?.asLong() ?: 0)
      }
    } catch (e: Exception) {
      logger.error(e) { "${account.tracker} token request failed for user ${account.userId}: ${e.message}" }
      null
    }

  private fun cacheToken(
    account: UserTracker,
    response: TokenResponse,
  ): String {
    cache[cacheKey(account)] = CachedToken(response.accessToken, Instant.now().plusSeconds(response.expiresIn - 60), response.refreshToken)
    logger.info { "${account.tracker} token obtained for user ${account.userId} (expires in ${response.expiresIn}s)" }
    return response.accessToken
  }

  private fun validCached(account: UserTracker): String? =
    cache[cacheKey(account)]
      ?.takeIf { it.expiry != null && Instant.now().isBefore(it.expiry) }
      ?.accessToken

  private fun lockFor(account: UserTracker): Any = locks.computeIfAbsent(cacheKey(account)) { Any() }

  private fun cacheKey(account: UserTracker) = "${account.userId}:${account.tracker}"

  private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
