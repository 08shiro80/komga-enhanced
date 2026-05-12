package org.gotson.komga.infrastructure.metadata.tracker

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.model.MetadataPatchTarget
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesMetadataPatch
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.infrastructure.metadata.SeriesMetadataProvider
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class TrackerLinkEnricher(
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val pluginRepository: PluginRepository,
  private val objectMapper: ObjectMapper,
) : SeriesMetadataProvider {
  private val timeoutFactory = JdkClientHttpRequestFactory(
    java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
  ).apply { setReadTimeout(Duration.ofSeconds(15)) }
  private val anilistClient = RestClient.builder().baseUrl("https://graphql.anilist.co").requestFactory(timeoutFactory).build()
  private val malClient = RestClient.builder().baseUrl("https://api.myanimelist.net").requestFactory(timeoutFactory).build()
  private val kitsuClient = RestClient.builder().baseUrl("https://kitsu.app/api/edge").requestFactory(timeoutFactory).build()
  private val mangadexClient = RestClient.builder().baseUrl("https://api.mangadex.org").requestFactory(timeoutFactory).build()
  private val searchCache = ConcurrentHashMap<String, Boolean>()

  override fun shouldLibraryHandlePatch(
    library: Library,
    target: MetadataPatchTarget,
  ): Boolean = target == MetadataPatchTarget.SERIES

  private fun libraryIdExcluded(libraryId: String): Boolean {
    val excluded = pluginConfigRepository.findByPluginIdAndKey("manga-scrobbler", "exclude_library_ids")?.configValue
    if (excluded.isNullOrBlank()) return false
    return excluded.split(',').any { it.trim() == libraryId }
  }

  override fun getSeriesMetadata(series: Series): SeriesMetadataPatch? {
    val meta = seriesMetadataRepository.findById(series.id)
    if (meta.linksLock) return null
    if (libraryIdExcluded(series.libraryId)) return null

    val existingLabels = meta.links.map { it.label.lowercase() }.toSet()
    logger.debug { "TrackerLinkEnricher: series='${series.name}', existing link labels=$existingLabels" }
    val newLinks = mutableListOf<WebLink>()

    if (isMetadataPluginEnabled("anilist") && "anilist" !in existingLabels) {
      searchAnilist(series.name)?.let { id ->
        newLinks.add(WebLink("AniList", URI("https://anilist.co/manga/$id")))
      }
    }
    if ("myanimelist" !in existingLabels) {
      searchMal(series.name)?.let { id ->
        newLinks.add(WebLink("MyAnimeList", URI("https://myanimelist.net/manga/$id")))
      }
    }
    if (isMetadataPluginEnabled("mangadex") && "mangadex" !in existingLabels) {
      searchMangaDex(series.name)?.let { id ->
        newLinks.add(WebLink("MangaDex", URI("https://mangadex.org/title/$id")))
      }
    }
    if (isMetadataPluginEnabled("kitsu") && "kitsu" !in existingLabels) {
      searchKitsu(series.name)?.let { id ->
        newLinks.add(WebLink("Kitsu", URI("https://kitsu.app/manga/$id")))
      }
    }

    if (newLinks.isEmpty()) {
      logger.debug { "TrackerLinkEnricher: no new links for '${series.name}'" }
      return null
    }
    // preserve existing links since each provider's patch replaces the entire field
    val allLinks = meta.links + newLinks
    logger.info { "TrackerLinkEnricher: added ${newLinks.map { it.label }.joinToString()} for '${series.name}'" }
    return SeriesMetadataPatch(
      title = null, titleSort = null, status = null, summary = null,
      readingDirection = null, publisher = null, ageRating = null, language = null,
      genres = null, totalBookCount = null, collections = emptySet(),
      alternateTitles = null, links = allLinks,
    )
  }

  private fun isMetadataPluginEnabled(shortName: String): Boolean {
    val plugin = pluginRepository.findByIdOrNull("$shortName-metadata") ?: return false
    return plugin.enabled
  }

  private fun searchAnilist(title: String): String? {
    val cacheKey = "$title:anilist"
    if (searchCache.getOrDefault(cacheKey, false)) return null
    return try {
      val query = """
        query (${'$'}search: String) {
          Page(page: 1, perPage: 3) {
            media(search: ${'$'}search, type: MANGA) { id title { romaji english native } }
          }
        }
      """.trimIndent()
      val body = mapOf("query" to query, "variables" to mapOf("search" to title))
      val resp = anilistClient.post()
        .contentType(MediaType.APPLICATION_JSON).body(body)
        .retrieve().body(String::class.java) ?: return null
      val json = objectMapper.readTree(resp)
      val id = json.at("/data/Page/media/0/id").asText(null)?.takeIf { it != "null" }
      if (id == null) {
        searchCache[cacheKey] = true
        null
      } else {
        id
      }
    } catch (e: Exception) {
      logger.debug(e) { "TrackerLinkEnricher: anilist error for '$title': ${e.message}" }
      searchCache[cacheKey] = true
      null
    }
  }

  private fun searchMal(title: String): String? {
    val cacheKey = "$title:mal"
    if (searchCache.getOrDefault(cacheKey, false)) return null
    val clientId = pluginConfigRepository.findByPluginIdAndKey("manga-scrobbler", "mal_client_id")?.configValue
    if (clientId.isNullOrBlank()) {
      logger.debug { "TrackerLinkEnricher: MAL client_id not configured, skipping '$title'" }
      return null
    }
    return try {
      val resp = malClient.get()
        .uri { b -> b.path("/v2/manga")
          .queryParam("q", title).queryParam("limit", 3).build() }
        .header("X-MAL-CLIENT-ID", clientId)
        .retrieve().body(String::class.java) ?: return null
      val json = objectMapper.readTree(resp)
      val id = json.at("/data/0/node/id").asText(null)?.takeIf { it != "null" }
      if (id == null) {
        searchCache[cacheKey] = true
        null
      } else {
        id
      }
    } catch (e: Exception) {
      logger.debug(e) { "TrackerLinkEnricher: mal error for '$title': ${e.message}" }
      searchCache[cacheKey] = true
      null
    }
  }

  private fun searchKitsu(title: String): String? {
    val cacheKey = "$title:kitsu"
    if (searchCache.getOrDefault(cacheKey, false)) return null
    return try {
      val resp = kitsuClient.get()
        .uri { b -> b.path("/manga")
          .queryParam("filter[text]", title)
          .queryParam("page[limit]", 3).build() }
        .header("Accept", "application/vnd.api+json")
        .retrieve().body(String::class.java) ?: return null
      val json = objectMapper.readTree(resp)
      val id = json.at("/data/0/id").asText(null)?.takeIf { it != "null" }
      if (id == null) {
        searchCache[cacheKey] = true
        null
      } else {
        id
      }
    } catch (e: Exception) {
      logger.debug(e) { "TrackerLinkEnricher: kitsu error for '$title': ${e.message}" }
      searchCache[cacheKey] = true
      null
    }
  }

  private fun searchMangaDex(title: String): String? {
    val cacheKey = "$title:mangadex"
    if (searchCache.getOrDefault(cacheKey, false)) return null
    return try {
      val resp = mangadexClient.get()
        .uri { b -> b.path("/manga").queryParam("title", title).queryParam("limit", 3)
          .queryParam("contentRating[]", "safe").queryParam("contentRating[]", "suggestive")
          .queryParam("contentRating[]", "erotica").queryParam("contentRating[]", "pornographic").build() }
        .retrieve().body(String::class.java) ?: return null
      val json = objectMapper.readTree(resp)
      val id = json.at("/data/0/id").asText(null)?.takeIf { it != "null" }
      if (id == null) {
        searchCache[cacheKey] = true
        null
      } else {
        id
      }
    } catch (e: Exception) {
      logger.debug(e) { "TrackerLinkEnricher: mangadex error for '$title': ${e.message}" }
      searchCache[cacheKey] = true
      null
    }
  }
}
