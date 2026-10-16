package org.gotson.komga.infrastructure.scrobbler

import org.gotson.komga.domain.model.UserTracker
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.URLEncoder
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val MAX_ERROR_BODY_LENGTH = 300

private val whitespace = Regex("\\s+")

sealed class TrackerCallException(
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)

class TrackerAuthException(
  message: String,
  cause: Throwable? = null,
) : TrackerCallException(message, cause)

class TrackerUnauthorizedException(
  message: String,
  cause: Throwable? = null,
) : TrackerCallException(message, cause)

class TrackerRateLimitedException(
  message: String,
  val retryAfter: Duration?,
  cause: Throwable? = null,
) : TrackerCallException(message, cause)

class TrackerUnavailableException(
  message: String,
  cause: Throwable? = null,
) : TrackerCallException(message, cause)

class TrackerNotFoundException(
  message: String,
  cause: Throwable? = null,
) : TrackerCallException(message, cause)

class TrackerRejectedException(
  message: String,
  cause: Throwable? = null,
) : TrackerCallException(message, cause)

fun RestClientResponseException.providerErrorDetail(secrets: Collection<String?>): String {
  val body =
    secrets
      .filterNotNull()
      .filter { it.isNotBlank() }
      .fold(responseBodyAsString) { acc, secret -> acc.replace(secret, "***") }
      .replace(whitespace, " ")
      .trim()
  val status = "HTTP ${statusCode.value()}"
  return if (body.isEmpty()) status else "$status: ${body.take(MAX_ERROR_BODY_LENGTH)}"
}

fun parseRetryAfter(headers: HttpHeaders): Duration? {
  val value = headers.getFirst(HttpHeaders.RETRY_AFTER)?.trim() ?: return null
  value.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
  return try {
    Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).takeIf { !it.isNegative }
  } catch (_: DateTimeParseException) {
    null
  }
}

fun classifyTrackerFailure(
  tracker: String,
  e: Exception,
  secrets: Collection<String?> = emptyList(),
): TrackerCallException =
  when (e) {
    is TrackerCallException -> e
    is RestClientResponseException -> {
      val detail = e.providerErrorDetail(secrets)
      val status = e.statusCode.value()
      when {
        status == 401 -> TrackerUnauthorizedException("$tracker rejected the credentials ($detail)", e)
        status == 400 && tracker == UserTracker.ANILIST && e.responseBodyAsString.contains("Invalid token") ->
          TrackerUnauthorizedException("$tracker rejected the token ($detail)", e)
        status == 429 -> TrackerRateLimitedException("$tracker rate limit reached ($detail)", e.responseHeaders?.let { parseRetryAfter(it) }, e)
        status == 404 -> TrackerNotFoundException("$tracker returned not found ($detail)", e)
        status >= 500 -> TrackerUnavailableException("$tracker is unavailable ($detail)", e)
        else -> TrackerRejectedException("$tracker rejected the request ($detail)", e)
      }
    }
    is RestClientException -> TrackerUnavailableException("$tracker could not be reached: ${e.message}", e)
    else -> TrackerUnavailableException("$tracker request failed: ${e.message}", e)
  }

fun formEncode(vararg params: Pair<String, String?>): String =
  params
    .mapNotNull { (key, value) -> value?.let { "$key=${rfc3986Encode(it)}" } }
    .joinToString("&")

private fun rfc3986Encode(value: String): String =
  URLEncoder
    .encode(value, Charsets.UTF_8)
    .replace("+", "%20")
    .replace("*", "%2A")
    .replace("%7E", "~")
