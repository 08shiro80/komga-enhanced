package org.gotson.komga.infrastructure.metadata.mangadex

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.service.Author
import org.gotson.komga.domain.service.MetadataDetails
import org.gotson.komga.domain.service.MetadataSearchResult
import org.gotson.komga.domain.service.OnlineMetadataProvider
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * MangaDex metadata provider with configurable title language
 *
 * Configuration (via Plugin Settings):
 * - preferred_title_language: Language code for titles (en, ja, ja-ro, ko, zh, etc.)
 *   Default: "en" (English)
 *
 * Available languages: en, ja, ja-ro (romanized Japanese), ko, zh, zh-hk, pt-br, es, fr, de, it, ru, etc.
 */
@Service
class MangaDexMetadataPlugin(
  private val objectMapper: ObjectMapper,
  private val pluginConfigRepository: PluginConfigRepository,
) : OnlineMetadataProvider {
  private val restClient = RestClient.create("https://api.mangadex.org")
  private val pluginId = "mangadex-metadata"

  /**
   * Get the preferred title language from config, default to "en"
   */
  private fun getPreferredLanguage(): String = pluginConfigRepository.findByPluginIdAndKey(pluginId, "preferred_title_language")?.configValue ?: "en"

  /**
   * Extract title in preferred language with fallbacks
   * Priority: preferred language → en → first available
   */
  private fun extractTitle(
    attributes: JsonNode?,
    preferredLang: String,
  ): String {
    if (attributes == null) return "Unknown"
    val titleNode = attributes.get("title")
    val altTitles = attributes.get("altTitles")

    titleNode
      ?.get(preferredLang)
      ?.asText()
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    altTitles
      ?.firstNotNullOfOrNull { it.get(preferredLang)?.asText()?.takeIf { t -> t.isNotBlank() } }
      ?.let { return it }

    if (preferredLang != "en") {
      titleNode
        ?.get("en")
        ?.asText()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
      altTitles
        ?.firstNotNullOfOrNull { it.get("en")?.asText()?.takeIf { t -> t.isNotBlank() } }
        ?.let { return it }
    }

    titleNode?.fields()?.let { fields ->
      if (fields.hasNext()) {
        return fields.next().value?.asText() ?: "Unknown"
      }
    }

    return "Unknown"
  }

  /**
   * Extract description in preferred language with fallbacks
   */
  private fun extractDescription(
    descNode: JsonNode?,
    preferredLang: String,
  ): String? {
    if (descNode == null) return null

    // Try preferred language first
    descNode
      .get(preferredLang)
      ?.asText()
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    // Fallback to English
    if (preferredLang != "en") {
      descNode
        .get("en")
        ?.asText()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    }

    // Fallback to any available
    descNode.fields()?.let { fields ->
      if (fields.hasNext()) {
        return fields.next().value?.asText()
      }
    }

    return null
  }

  /**
   * Extract all alternative titles from both main title node and altTitles array
   * Returns a map of title -> language code, excluding the primary title
   */
  private fun extractAlternativeTitles(
    attributes: JsonNode?,
    primaryTitle: String,
  ): Map<String, String> {
    if (attributes == null) return emptyMap()

    val alternativeTitles = mutableMapOf<String, String>()

    // Extract from main title node (different language versions)
    attributes.get("title")?.fields()?.forEach { entry ->
      val lang = entry.key
      val title = entry.value?.asText()
      if (!title.isNullOrBlank() && title != primaryTitle) {
        alternativeTitles[title] = lang
      }
    }

    // Extract from altTitles array (additional titles like romanized, localized names)
    attributes.get("altTitles")?.forEach { altTitleNode ->
      altTitleNode?.fields()?.forEach { entry ->
        val lang = entry.key
        val title = entry.value?.asText()
        if (!title.isNullOrBlank() && title != primaryTitle && !alternativeTitles.containsKey(title)) {
          alternativeTitles[title] = lang
        }
      }
    }

    return alternativeTitles
  }

  override fun search(query: String): List<MetadataSearchResult> {
    return try {
      logger.info { "Searching MangaDex for: $query" }
      val preferredLang = getPreferredLanguage()
      logger.debug { "Using preferred language: $preferredLang" }

      val response =
        restClient
          .get()
          .uri { builder ->
            builder
              .path("/manga")
              .queryParam("title", query)
              .queryParam("limit", 20)
              .queryParam("includes[]", "cover_art")
              .queryParam("includes[]", "author")
              .queryParam("includes[]", "artist")
              .queryParam("contentRating[]", "safe")
              .queryParam("contentRating[]", "suggestive")
              .queryParam("contentRating[]", "erotica")
              .queryParam("contentRating[]", "pornographic")
              .build()
          }.retrieve()
          .body(String::class.java)

      if (response == null) return emptyList()

      val json = objectMapper.readTree(response)
      val dataArray = json.get("data")

      if (dataArray == null || !dataArray.isArray) return emptyList()

      dataArray.map { item ->
        val id = item.get("id").asText()
        val attributes = item.get("attributes")
        val title = extractTitle(attributes, preferredLang)
        val description = extractDescription(attributes.get("description"), preferredLang)
        val status = attributes.get("status")?.asText()
        val year = attributes.get("year")?.asInt()

        // Extract tags in preferred language with fallback
        val tags =
          attributes
            .get("tags")
            ?.mapNotNull { tag ->
              val nameNode = tag.get("attributes")?.get("name")
              nameNode
                ?.get(preferredLang)
                ?.asText()
                ?: nameNode
                  ?.get("en")
                  ?.asText()
                ?: nameNode
                  ?.fields()
                  ?.asSequence()
                  ?.firstOrNull()
                  ?.value
                  ?.asText()
            }?.filter { it.isNotEmpty() } ?: emptyList()

        // Extract cover URL
        var coverUrl: String? = null
        val relationships = item.get("relationships")
        if (relationships != null && relationships.isArray) {
          for (rel in relationships) {
            if (rel.get("type")?.asText() == "cover_art") {
              val fileName = rel.get("attributes")?.get("fileName")?.asText()
              if (fileName != null) {
                coverUrl = "https://uploads.mangadex.org/covers/$id/$fileName.256.jpg"
              }
              break
            }
          }
        }

        // Extract author
        var author: String? = null
        if (relationships != null && relationships.isArray) {
          for (rel in relationships) {
            if (rel.get("type")?.asText() == "author") {
              author = rel.get("attributes")?.get("name")?.asText()
              break
            }
          }
        }

        MetadataSearchResult(
          externalId = id,
          title = title,
          description = description,
          coverUrl = coverUrl,
          author = author,
          year = year,
          status = status,
          tags = tags,
          provider = "MangaDex",
        )
      }
    } catch (e: RestClientException) {
      logger.error(e) { "Error searching MangaDex" }
      emptyList()
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error searching MangaDex" }
      emptyList()
    }
  }

  fun searchAdvanced(
    query: String?,
    includedTagIds: List<String>,
    excludedTagIds: List<String>,
    status: List<String>,
    contentRating: List<String>,
    publicationDemographic: List<String>,
    hasAvailableChapters: Boolean?,
    offset: Int,
    limit: Int,
    order: String? = null,
    orderDir: String? = null,
  ): MangaDexSearchPage {
    return try {
      val preferredLang = getPreferredLanguage()
      val effectiveRatings =
        contentRating.ifEmpty {
          listOf("safe", "suggestive", "erotica", "pornographic")
        }
      val effectiveLimit = limit.coerceIn(1, 100)
      val effectiveOffset = offset.coerceAtLeast(0)

      val response =
        restClient
          .get()
          .uri { builder ->
            builder.path("/manga")
            builder.queryParam("limit", effectiveLimit)
            builder.queryParam("offset", effectiveOffset)
            builder.queryParam("includes[]", "cover_art")
            builder.queryParam("includes[]", "author")
            builder.queryParam("includes[]", "artist")
            if (!query.isNullOrBlank()) builder.queryParam("title", query)
            includedTagIds.forEach { builder.queryParam("includedTags[]", it) }
            excludedTagIds.forEach { builder.queryParam("excludedTags[]", it) }
            status.forEach { builder.queryParam("status[]", it) }
            effectiveRatings.forEach { builder.queryParam("contentRating[]", it) }
            publicationDemographic.forEach { builder.queryParam("publicationDemographic[]", it) }
            if (hasAvailableChapters == true) builder.queryParam("hasAvailableChapters", "true")
            val safeOrderFields = setOf("followedCount", "relevance", "latestUploadedChapter", "createdAt", "updatedAt", "title", "rating", "year")
            val dir = if (orderDir == "asc") "asc" else "desc"
            when {
              order != null && order in safeOrderFields -> builder.queryParam("order[$order]", dir)
              query.isNullOrBlank() -> builder.queryParam("order[followedCount]", "desc")
            }
            builder.build()
          }.retrieve()
          .body(String::class.java) ?: return MangaDexSearchPage(emptyList(), 0, effectiveOffset, effectiveLimit)

      val data = parseSearchResponse(response, preferredLang)
      val total = objectMapper.readTree(response).get("total")?.asInt() ?: data.size
      MangaDexSearchPage(data, total, effectiveOffset, effectiveLimit)
    } catch (e: RestClientException) {
      logger.error(e) { "Error in MangaDex advanced search" }
      MangaDexSearchPage(emptyList(), 0, offset, limit)
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error in MangaDex advanced search" }
      MangaDexSearchPage(emptyList(), 0, offset, limit)
    }
  }

  // 24h cache for "does this manga have downloadable chapters in <language>?"
  private val downloadableCache = ConcurrentHashMap<Pair<String, String>, Pair<Boolean, Instant>>()
  private val downloadableCacheTtl = Duration.ofHours(24)

  /**
   * True when MangaDex has at least one chapter for `mangaId` in `language` that is
   * actually downloadable: `externalUrl == null` (not a webnovel/comico/etc. link)
   * AND `pages > 0`. Cached per (mangaId, language) for 24h.
   *
   * Costs one extra `/manga/{id}/feed` call per uncached manga — call sparingly.
   */
  fun hasDownloadableChapters(
    mangaId: String,
    language: String,
  ): Boolean {
    val key = mangaId to language
    downloadableCache[key]?.let { (value, ts) ->
      if (Instant.now().isBefore(ts.plus(downloadableCacheTtl))) return value
    }
    // null = transient error → don't cache (otherwise a blip hides the title for 24h)
    val result = computeHasDownloadable(mangaId, language)
    if (result != null) downloadableCache[key] = result to Instant.now()
    return result ?: false
  }

  private fun computeHasDownloadable(
    mangaId: String,
    language: String,
  ): Boolean? {
    try {
      var offset = 0
      val limit = 100
      while (offset + limit <= 10000) {
        val currentOffset = offset
        val response =
          restClient
            .get()
            .uri { builder ->
              builder.path("/manga/$mangaId/feed")
              builder.queryParam("translatedLanguage[]", language)
              builder.queryParam("contentRating[]", "safe")
              builder.queryParam("contentRating[]", "suggestive")
              builder.queryParam("contentRating[]", "erotica")
              builder.queryParam("contentRating[]", "pornographic")
              builder.queryParam("limit", limit)
              builder.queryParam("offset", currentOffset)
              builder.queryParam("order[chapter]", "asc")
              builder.build()
            }.retrieve()
            .body(String::class.java) ?: return false
        val root = objectMapper.readTree(response)
        val data = root.get("data") ?: return false
        if (!data.isArray) return false
        val found =
          data.any { item ->
            val attrs = item.get("attributes")
            if (attrs == null) {
              false
            } else {
              val hasExternalUrl = attrs.hasNonNull("externalUrl") && attrs.get("externalUrl").asText().isNotBlank()
              val pages = attrs.get("pages")?.asInt() ?: 0
              !hasExternalUrl && pages > 0
            }
          }
        if (found) return true
        val total = root.get("total")?.asInt() ?: 0
        offset += limit
        if (data.isEmpty || offset >= total) return false
      }
      return false
    } catch (e: Exception) {
      logger.warn(e) { "downloadable-check failed for $mangaId ($language) — assuming false (not cached)" }
      return null
    }
  }

  @Volatile private var cachedTags: List<MangaDexTag>? = null

  fun getTags(): List<MangaDexTag> {
    cachedTags?.let { return it }
    return try {
      val response =
        restClient
          .get()
          .uri("/manga/tag")
          .retrieve()
          .body(String::class.java)
          ?: return emptyList()
      val json = objectMapper.readTree(response)
      val data = json.get("data") ?: return emptyList()
      if (!data.isArray) return emptyList()
      val tags =
        data
          .mapNotNull { item ->
            val id = item.get("id")?.asText() ?: return@mapNotNull null
            val attrs = item.get("attributes") ?: return@mapNotNull null
            val name =
              attrs
                .get("name")
                ?.get("en")
                ?.asText()
                ?: attrs
                  .get("name")
                  ?.fields()
                  ?.asSequence()
                  ?.firstOrNull()
                  ?.value
                  ?.asText()
                ?: return@mapNotNull null
            val group = attrs.get("group")?.asText() ?: "other"
            MangaDexTag(id = id, name = name, group = group)
          }.sortedWith(compareBy({ it.group }, { it.name }))
      cachedTags = tags
      tags
    } catch (e: Exception) {
      logger.error(e) { "Failed to fetch MangaDex tag list" }
      emptyList()
    }
  }

  private fun parseSearchResponse(
    response: String,
    preferredLang: String,
  ): List<MetadataSearchResult> {
    val json = objectMapper.readTree(response)
    val dataArray = json.get("data") ?: return emptyList()
    if (!dataArray.isArray) return emptyList()
    return dataArray.map { item ->
      val id = item.get("id").asText()
      val attributes = item.get("attributes")
      val title = extractTitle(attributes, preferredLang)
      val description = extractDescription(attributes.get("description"), preferredLang)
      val status = attributes.get("status")?.asText()
      val year = attributes.get("year")?.asInt()
      val tags =
        attributes
          .get("tags")
          ?.mapNotNull { tag ->
            val nameNode =
              tag
                .get("attributes")
                ?.get("name")
            nameNode
              ?.get(preferredLang)
              ?.asText()
              ?: nameNode
                ?.get("en")
                ?.asText()
              ?: nameNode
                ?.fields()
                ?.asSequence()
                ?.firstOrNull()
                ?.value
                ?.asText()
          }?.filter { it.isNotEmpty() } ?: emptyList()
      var coverUrl: String? = null
      var author: String? = null
      val relationships = item.get("relationships")
      if (relationships != null && relationships.isArray) {
        for (rel in relationships) {
          when (rel.get("type")?.asText()) {
            "cover_art" -> {
              val fileName = rel.get("attributes")?.get("fileName")?.asText()
              if (fileName != null && coverUrl == null) {
                coverUrl = "https://uploads.mangadex.org/covers/$id/$fileName.256.jpg"
              }
            }
            "author" -> if (author == null) author = rel.get("attributes")?.get("name")?.asText()
          }
        }
      }
      MetadataSearchResult(
        externalId = id,
        title = title,
        description = description,
        coverUrl = coverUrl,
        author = author,
        year = year,
        status = status,
        tags = tags,
        provider = "MangaDex",
      )
    }
  }

  override fun getMetadata(externalId: String): MetadataDetails? {
    return try {
      logger.info { "Fetching MangaDex metadata for ID: $externalId" }
      val preferredLang = getPreferredLanguage()

      val response =
        restClient
          .get()
          .uri("/manga/$externalId?includes[]=cover_art&includes[]=author&includes[]=artist")
          .retrieve()
          .body(String::class.java)

      if (response == null) return null

      val json = objectMapper.readTree(response)
      val data = json.get("data") ?: return null
      val attributes = data.get("attributes") ?: return null

      val title = extractTitle(attributes, preferredLang)
      val alternativeTitles = extractAlternativeTitles(attributes, title)
      val description = extractDescription(attributes.get("description"), preferredLang)
      val status = attributes.get("status")?.asText()
      val year = attributes.get("year")?.asInt()
      val contentRating = attributes.get("contentRating")?.asText()

      // Extract tags in preferred language with fallback
      val tags =
        attributes.get("tags")?.mapNotNull { tag ->
          val nameNode = tag.get("attributes")?.get("name")
          nameNode
            ?.get(preferredLang)
            ?.asText()
            ?: nameNode
              ?.get("en")
              ?.asText()
            ?: nameNode
              ?.fields()
              ?.asSequence()
              ?.firstOrNull()
              ?.value
              ?.asText()
        } ?: emptyList()

      // Extract cover URL
      var coverUrl: String? = null
      val relationships = data.get("relationships")
      if (relationships != null && relationships.isArray) {
        for (rel in relationships) {
          if (rel.get("type")?.asText() == "cover_art") {
            val fileName = rel.get("attributes")?.get("fileName")?.asText()
            if (fileName != null) {
              coverUrl = "https://uploads.mangadex.org/covers/$externalId/$fileName"
            }
            break
          }
        }
      }

      // Extract authors and artists
      val authors = mutableListOf<Author>()
      if (relationships != null && relationships.isArray) {
        for (rel in relationships) {
          val type = rel.get("type")?.asText()
          if (type == "author" || type == "artist") {
            val name = rel.get("attributes")?.get("name")?.asText()
            if (name != null) {
              authors.add(Author(name, type.capitalize()))
            }
          }
        }
      }

      logger.info { "Extracted ${alternativeTitles.size} alternative titles for '$title'" }

      MetadataDetails(
        title = title,
        titleSort = null,
        summary = description,
        publisher = null,
        ageRating =
          when (contentRating) {
            "safe" -> 0
            "suggestive" -> 13
            "erotica", "pornographic" -> 18
            else -> null
          },
        releaseDate = year?.toString(),
        authors = authors,
        tags = tags,
        genres = emptyList(),
        language = preferredLang,
        status = status,
        coverUrl = coverUrl,
        alternativeTitles = alternativeTitles,
      )
    } catch (e: RestClientException) {
      logger.error(e) { "Error fetching MangaDex metadata" }
      null
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error fetching MangaDex metadata" }
      null
    }
  }
}

private fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

data class MangaDexTag(
  val id: String,
  val name: String,
  val group: String,
)

data class MangaDexSearchPage(
  val data: List<MetadataSearchResult>,
  val total: Int,
  val offset: Int,
  val limit: Int,
)
