package org.gotson.komga.infrastructure.comicscrobbler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.infrastructure.metadata.tracker.TitleSearchResult
import org.gotson.komga.infrastructure.metadata.tracker.TrackerTitleSearchProvider
import org.gotson.komga.infrastructure.metadata.tracker.normalizeTitle
import org.gotson.komga.infrastructure.scrobbler.TrackerClients
import org.gotson.komga.infrastructure.scrobbler.TrackerNotFoundException
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimitedException
import org.gotson.komga.infrastructure.scrobbler.classifyTrackerFailure
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.OffsetDateTime

private val yearSuffix = Regex("""\s*\(\d{4}\)\s*$""")

fun normalizeIssueNumber(number: String): String {
  val trimmed = number.trim()
  return trimmed.toBigDecimalOrNull()?.let { normalizeDecimal(it) } ?: trimmed.uppercase()
}

private fun normalizeDecimal(value: BigDecimal): String = if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()

@Component
class MetronTrackerClient(
  private val trackerClients: TrackerClients,
  private val trackerTitleSearchProvider: TrackerTitleSearchProvider,
  private val objectMapper: ObjectMapper,
) {
  fun searchSeries(
    authorization: String,
    meta: SeriesMetadata,
  ): TitleSearchResult {
    val localTitles = trackerTitleSearchProvider.localTitles(meta)
    var failure: String? = null
    var retryAfter: Duration? = null
    for (title in trackerTitleSearchProvider.searchCandidates(meta)) {
      try {
        val results =
          get(authorization, "/api/series/?name={name}", title)
            .get("results")
            ?.toList()
            .orEmpty()
        val exact = results.filter { normalizeTitle(it.get("series")?.asText("") ?: "") in localTitles }
        val withoutYear = results.filter { normalizeTitle((it.get("series")?.asText("") ?: "").replace(yearSuffix, "")) in localTitles }
        val matching = (exact.ifEmpty { withoutYear }).mapNotNull { it.get("id")?.asText(null) }.distinct()
        when {
          matching.size == 1 -> return TitleSearchResult.Found(matching.first())
          matching.size > 1 -> return TitleSearchResult.Ambiguous(matching)
        }
      } catch (e: Exception) {
        val classified = classifyTrackerFailure(UserTracker.METRON, e)
        if (classified is TrackerNotFoundException) continue
        failure = "Metron series search for '$title' failed: ${classified.message}"
        if (classified is TrackerRateLimitedException) retryAfter = classified.retryAfter
      }
    }
    return failure?.let { TitleSearchResult.Failed(it, retryAfter) } ?: TitleSearchResult.NotFound
  }

  fun issueIds(
    authorization: String,
    seriesId: String,
    issueNumber: String,
  ): List<Int> {
    val wanted = normalizeIssueNumber(issueNumber)
    return get(authorization, "/api/issue/?series_id={series}&number={number}", seriesId, issueNumber)
      .get("results")
      ?.filter { normalizeIssueNumber(it.get("number")?.asText("") ?: "") == wanted }
      ?.mapNotNull { it.get("id")?.takeIf { id -> id.canConvertToInt() }?.asInt() }
      ?.distinct()
      .orEmpty()
  }

  fun scrobble(
    authorization: String,
    issueId: Int,
    dateRead: OffsetDateTime,
  ) {
    trackerClients
      .client(UserTracker.METRON)
      .post()
      .uri("/api/collection/scrobble/")
      .header("Authorization", authorization)
      .contentType(MediaType.APPLICATION_JSON)
      .accept(MediaType.APPLICATION_JSON)
      .body(mapOf("issue_id" to issueId, "date_read" to dateRead.toString()))
      .retrieve()
      .toBodilessEntity()
  }

  fun checkAccess(authorization: String) {
    get(authorization, "/api/series/?name={name}", "Batman")
  }

  private fun get(
    authorization: String,
    uriTemplate: String,
    vararg variables: Any,
  ): JsonNode =
    objectMapper.readTree(
      trackerClients
        .client(UserTracker.METRON)
        .get()
        .uri(uriTemplate, *variables)
        .header("Authorization", authorization)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .body(String::class.java)
        ?: "{}",
    )
}
