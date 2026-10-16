package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.WebLink
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private val logger = KotlinLogging.logger {}

private val linkPatterns =
  mapOf(
    UserTracker.ANILIST to Regex("""anilist\.co/manga/(\d+)""", RegexOption.IGNORE_CASE),
    UserTracker.MAL to Regex("""myanimelist\.net/manga/(\d+)""", RegexOption.IGNORE_CASE),
    UserTracker.KITSU to Regex("""kitsu\.(?:app|io)/manga/([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE),
    UserTracker.MANGADEX to Regex("""mangadex\.org/title/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""", RegexOption.IGNORE_CASE),
    UserTracker.METRON to Regex("""metron\.cloud/series/(\d+)""", RegexOption.IGNORE_CASE),
  )

private val mappingKeys =
  mapOf(
    UserTracker.ANILIST to "anilist_id",
    UserTracker.MAL to "mal_id",
    UserTracker.KITSU to "kitsu_id",
    UserTracker.MANGADEX to "mangadex_id",
    UserTracker.METRON to "metron_series_id",
  )

fun trackerLinkUrlPart(tracker: String): String =
  when (tracker) {
    UserTracker.ANILIST -> "anilist.co/manga/"
    UserTracker.MAL -> "myanimelist.net/manga/"
    UserTracker.KITSU -> "kitsu."
    UserTracker.MANGADEX -> "mangadex.org/title/"
    else -> "metron.cloud/series/"
  }

@Component
class TrackerIdResolver(
  private val objectMapper: ObjectMapper,
  clock: Clock = Clock.systemUTC(),
) {
  private val invalidMappingsLog = LogThrottle(Duration.ofHours(24), clock)

  fun fromLinks(links: Collection<WebLink>): Map<String, List<String>> =
    linkPatterns.mapValues { (_, pattern) ->
      links
        .mapNotNull { pattern.find(it.url.toString())?.groupValues?.get(1) }
        .distinct()
    }

  fun fromLink(
    tracker: String,
    url: String,
  ): String? =
    linkPatterns[tracker]
      ?.find(url)
      ?.groupValues
      ?.get(1)

  fun fromMappings(
    seriesId: String,
    seriesTitle: String,
    config: Map<String, String?>,
  ): Map<String, String> {
    val mappingsJson = config["mappings"]
    if (mappingsJson.isNullOrBlank()) return emptyMap()
    return try {
      val tree = objectMapper.readTree(mappingsJson)
      val node =
        tree.get(seriesId)
          ?: tree
            .properties()
            .firstOrNull { it.key.equals(seriesTitle, ignoreCase = true) }
            ?.value
          ?: return emptyMap()
      mappingKeys
        .mapNotNull { (tracker, key) ->
          node
            .get(key)
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "0" }
            ?.let { tracker to it }
        }.toMap()
    } catch (e: JsonProcessingException) {
      if (invalidMappingsLog.shouldLog(mappingsJson.hashCode().toString())) logger.warn { "Invalid scrobbler mappings JSON: ${e.originalMessage}" }
      emptyMap()
    }
  }
}
