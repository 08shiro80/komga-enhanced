package org.gotson.komga.infrastructure.metadata.tracker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.infrastructure.scrobbler.TrackerClients
import org.gotson.komga.infrastructure.scrobbler.TrackerNotFoundException
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimitedException
import org.gotson.komga.infrastructure.scrobbler.classifyTrackerFailure
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val MAX_CANDIDATES = 5
private const val MAX_RESULTS = 5
private const val MAX_CACHED_SEARCHES = 10_000

private val notFoundTtl = Duration.ofHours(24)

private val preferredLanguages = listOf("ja-ro", "en", "ja")

private val malNovelTypes = setOf("novel", "light_novel")

private val kitsuNovelTypes = setOf("novel")

fun normalizeTitle(title: String): String {
  val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase()
  return buildString {
    normalized.codePoints().forEach { if (Character.isLetterOrDigit(it)) appendCodePoint(it) }
  }
}

sealed interface TitleSearchResult {
  data class Found(
    val id: String,
  ) : TitleSearchResult

  data object NotFound : TitleSearchResult

  data class Ambiguous(
    val ids: List<String>,
  ) : TitleSearchResult

  data class Failed(
    val reason: String,
    val retryAfter: Duration? = null,
  ) : TitleSearchResult
}

@Component
class TrackerTitleSearchProvider(
  private val pluginConfigRepository: PluginConfigRepository,
  private val objectMapper: ObjectMapper,
  private val trackerClients: TrackerClients,
  private val clock: Clock = Clock.systemUTC(),
) {
  private data class Hit(
    val id: String,
    val titles: List<String>,
    val manga: Boolean,
  )

  private val notFoundUntil = ConcurrentHashMap<String, Instant>()

  fun searchCandidates(meta: SeriesMetadata): List<String> {
    val alternates = meta.alternateTitles
    val preferred = preferredLanguages.flatMap { language -> alternates.filter { it.label.equals(language, ignoreCase = true) } }
    val ordered = listOf(meta.title) + (preferred + (alternates - preferred.toSet())).map { it.title }
    return ordered
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinctBy { it.lowercase() }
      .take(MAX_CANDIDATES)
  }

  fun localTitles(meta: SeriesMetadata): Set<String> =
    (listOf(meta.title) + meta.alternateTitles.map { it.title })
      .map { normalizeTitle(it) }
      .filter { it.isNotEmpty() }
      .toSet()

  fun search(
    tracker: String,
    meta: SeriesMetadata,
    userMalClientId: String? = null,
  ): TitleSearchResult {
    if (tracker !in setOf(UserTracker.ANILIST, UserTracker.MAL, UserTracker.KITSU, UserTracker.MANGADEX)) {
      return TitleSearchResult.Failed("no title search for $tracker")
    }
    val cacheKey = "${meta.seriesId}:$tracker"
    val cachedUntil = notFoundUntil[cacheKey]
    if (cachedUntil != null) {
      if (clock.instant().isBefore(cachedUntil)) return TitleSearchResult.NotFound
      notFoundUntil.remove(cacheKey, cachedUntil)
    }
    val malClientId =
      if (tracker == UserTracker.MAL) {
        pluginConfigRepository.findByPluginIdAndKey("auto-metadata", "mal_client_id")?.configValue?.takeIf { it.isNotBlank() }
          ?: userMalClientId
          ?: return TitleSearchResult.Failed("no MyAnimeList client ID is available for the title search")
      } else {
        null
      }

    val localTitles = localTitles(meta)
    var failure: String? = null
    var retryAfter: Duration? = null
    for (title in searchCandidates(meta)) {
      try {
        val hits =
          when (tracker) {
            UserTracker.ANILIST -> searchAnilist(title)
            UserTracker.MAL -> searchMal(title, malClientId.orEmpty())
            UserTracker.KITSU -> searchKitsu(title)
            else -> searchMangaDex(title)
          }
        val matching =
          hits
            .filter { hit -> hit.manga && hit.titles.any { normalizeTitle(it) in localTitles } }
            .map { it.id }
            .distinct()
        when {
          matching.size == 1 -> return TitleSearchResult.Found(matching.first())
          matching.size > 1 -> {
            remember(cacheKey)
            return TitleSearchResult.Ambiguous(matching)
          }
        }
      } catch (e: Exception) {
        val classified = classifyTrackerFailure(tracker, e)
        if (classified is TrackerNotFoundException) continue
        failure = "$tracker search for '$title' failed: ${classified.message}"
        if (classified is TrackerRateLimitedException) retryAfter = classified.retryAfter
        logger.debug(e) { "Tracker title search: $failure" }
      }
    }
    if (failure != null) return TitleSearchResult.Failed(failure, retryAfter)
    remember(cacheKey)
    return TitleSearchResult.NotFound
  }

  fun invalidate(seriesId: String) {
    notFoundUntil.keys.removeIf { it.startsWith("$seriesId:") }
  }

  fun findKitsuIdBySlug(slug: String): String? {
    val resp =
      trackerClients
        .client(UserTracker.KITSU)
        .get()
        .uri { b ->
          b
            .path("/manga")
            .queryParam("filter[slug]", slug)
            .build()
        }.header("Accept", "application/vnd.api+json")
        .retrieve()
        .body(String::class.java)
    return resp
      ?.let { objectMapper.readTree(it) }
      ?.at("/data/0/id")
      ?.asText(null)
      ?.takeIf { it.isNotBlank() }
  }

  private fun remember(cacheKey: String) {
    if (notFoundUntil.size >= MAX_CACHED_SEARCHES) notFoundUntil.entries.removeIf { !clock.instant().isBefore(it.value) }
    if (notFoundUntil.size >= MAX_CACHED_SEARCHES) notFoundUntil.clear()
    notFoundUntil[cacheKey] = clock.instant().plus(notFoundTtl)
  }

  private fun texts(node: JsonNode?): List<String> =
    when {
      node == null || node.isNull || node.isMissingNode -> emptyList()
      node.isTextual -> listOf(node.asText())
      node.isContainerNode ->
        node
          .elements()
          .asSequence()
          .flatMap { texts(it) }
          .toList()
      else -> emptyList()
    }

  private fun hits(
    items: JsonNode,
    id: (JsonNode) -> JsonNode?,
    titles: (JsonNode) -> List<JsonNode?>,
    manga: (JsonNode) -> Boolean,
  ): List<Hit> =
    items
      .take(MAX_RESULTS)
      .mapNotNull { item ->
        id(item)
          ?.asText(null)
          ?.takeIf { it.isNotBlank() && it != "null" }
          ?.let { Hit(it, titles(item).flatMap { node -> texts(node) }, manga(item)) }
      }

  private fun searchAnilist(title: String): List<Hit> {
    val query =
      """
      query (${'$'}search: String) {
        Page(page: 1, perPage: $MAX_RESULTS) {
          media(search: ${'$'}search, type: MANGA, format_in: [MANGA, ONE_SHOT]) { id format title { romaji english native } synonyms }
        }
      }
      """.trimIndent()
    val resp =
      trackerClients
        .client(UserTracker.ANILIST)
        .post()
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .body(mapOf("query" to query, "variables" to mapOf("search" to title)))
        .retrieve()
        .body(String::class.java) ?: return emptyList()
    return hits(
      objectMapper.readTree(resp).at("/data/Page/media"),
      { it.get("id") },
      { listOf(it.get("title"), it.get("synonyms")) },
      { it.get("format")?.asText() in setOf("MANGA", "ONE_SHOT") },
    )
  }

  private fun searchMal(
    title: String,
    clientId: String,
  ): List<Hit> {
    val resp =
      trackerClients
        .client(UserTracker.MAL)
        .get()
        .uri { b ->
          b
            .path("/v2/manga")
            .queryParam("q", title)
            .queryParam("limit", MAX_RESULTS)
            .queryParam("fields", "alternative_titles,media_type")
            .build()
        }.header("X-MAL-CLIENT-ID", clientId)
        .retrieve()
        .body(String::class.java) ?: return emptyList()
    return hits(
      objectMapper.readTree(resp).at("/data"),
      { it.at("/node/id") },
      { listOf(it.at("/node/title"), it.at("/node/alternative_titles")) },
      { it.at("/node/media_type").asText("") !in malNovelTypes },
    )
  }

  private fun searchKitsu(title: String): List<Hit> {
    val resp =
      trackerClients
        .client(UserTracker.KITSU)
        .get()
        .uri { b ->
          b
            .path("/manga")
            .queryParam("filter[text]", title)
            .queryParam("page[limit]", MAX_RESULTS)
            .queryParam("fields[manga]", "canonicalTitle,titles,abbreviatedTitles,subtype")
            .build()
        }.header("Accept", "application/vnd.api+json")
        .retrieve()
        .body(String::class.java) ?: return emptyList()
    return hits(
      objectMapper.readTree(resp).at("/data"),
      { it.get("id") },
      { listOf(it.at("/attributes/canonicalTitle"), it.at("/attributes/titles"), it.at("/attributes/abbreviatedTitles")) },
      { it.at("/attributes/subtype").asText("") !in kitsuNovelTypes },
    )
  }

  private fun searchMangaDex(title: String): List<Hit> {
    val resp =
      trackerClients
        .client(UserTracker.MANGADEX)
        .get()
        .uri { b ->
          b
            .path("/manga")
            .queryParam("title", title)
            .queryParam("limit", MAX_RESULTS)
            .queryParam("contentRating[]", "safe")
            .queryParam("contentRating[]", "suggestive")
            .queryParam("contentRating[]", "erotica")
            .queryParam("contentRating[]", "pornographic")
            .build()
        }.retrieve()
        .body(String::class.java) ?: return emptyList()
    return hits(
      objectMapper.readTree(resp).at("/data"),
      { it.get("id") },
      { listOf(it.at("/attributes/title"), it.at("/attributes/altTitles")) },
      { true },
    )
  }
}
