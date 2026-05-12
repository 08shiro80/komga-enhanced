package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.springframework.stereotype.Component

private val resolverLogger = KotlinLogging.logger {}

/**
 * Resolves external manga tracker IDs from [SeriesMetadata] links and optional
 * JSON [mappings] (same semantics as [MangaScrobblerPlugin]). Shared with
 * [MangaSyncPullerPlugin] so pull and push stay aligned.
 */
@Component
class TrackerIdResolver(
  private val objectMapper: ObjectMapper,
  private val seriesMetadataRepository: SeriesMetadataRepository,
) {
  companion object {
    private val anilistIdRegex = Regex("""anilist\.co/manga/(\d+)""", RegexOption.IGNORE_CASE)
    private val malIdRegex = Regex("""myanimelist\.net/manga/(\d+)""", RegexOption.IGNORE_CASE)
    private val kitsuIdRegex = Regex("""kitsu\.app/manga/(\d+)""", RegexOption.IGNORE_CASE)
    private val mangadexIdRegex = Regex("""mangadex\.org/title/([0-9a-f-]+)""", RegexOption.IGNORE_CASE)
  }

  fun resolveFromKomgaSeriesId(
    komgaSeriesId: String,
    config: Map<String, String?>,
  ): MangaTrackerIds {
    val meta = seriesMetadataRepository.findByIdOrNull(komgaSeriesId) ?: return MangaTrackerIds(null, null, null, null)
    return resolve(meta.title, meta.links, config)
  }

  fun resolve(
    seriesTitle: String,
    links: List<WebLink>,
    config: Map<String, String?>,
  ): MangaTrackerIds {
    var anilistId: Int? = null
    var malId: Int? = null
    var kitsuId: Int? = null
    var mangadexId: String? = null

    if ((config["auto_detect_links"] ?: "true").toBoolean()) {
      for (link in links) {
        val url = link.url.toString()
        if (anilistId == null) anilistIdRegex.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { anilistId = it }
        if (malId == null) malIdRegex.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { malId = it }
        if (kitsuId == null) kitsuIdRegex.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { kitsuId = it }
        if (mangadexId == null) mangadexIdRegex.find(url)?.groupValues?.get(1)?.let { mangadexId = it }
      }
    }

    val mappingsJson = config["mappings"]
    if (!mappingsJson.isNullOrBlank()) {
      try {
        val tree = objectMapper.readTree(mappingsJson)
        val match = tree.fields().asSequence().firstOrNull { it.key.equals(seriesTitle, ignoreCase = true) }
        match?.value?.let { node ->
          node.get("anilist_id")?.asInt(0)?.takeIf { it > 0 }?.let { anilistId = it }
          node.get("mal_id")?.asInt(0)?.takeIf { it > 0 }?.let { malId = it }
          node.get("kitsu_id")?.asInt(0)?.takeIf { it > 0 }?.let { kitsuId = it }
          node.get("mangadex_id")?.asText()?.takeIf { it.isNotBlank() }?.let { mangadexId = it }
        }
      } catch (e: Exception) {
        resolverLogger.warn(e) { "Invalid manga-scrobbler mappings JSON" }
      }
    }

    return MangaTrackerIds(anilistId, malId, kitsuId, mangadexId)
  }
}

data class MangaTrackerIds(
  val anilistId: Int?,
  val malId: Int?,
  val kitsuId: Int?,
  val mangadexId: String?,
)
