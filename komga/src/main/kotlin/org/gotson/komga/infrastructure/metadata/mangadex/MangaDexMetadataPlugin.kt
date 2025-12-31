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
    titleNode: JsonNode?,
    preferredLang: String,
  ): String {
    if (titleNode == null) return "Unknown"

    // Try preferred language first
    titleNode
      .get(preferredLang)
      ?.asText()
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    // Fallback to English if not preferred
    if (preferredLang != "en") {
      titleNode
        .get("en")
        ?.asText()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    }

    // Fallback to any available language
    titleNode.fields()?.let { fields ->
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
        val title = extractTitle(attributes.get("title"), preferredLang)
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
                  ?.next()
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

      val title = extractTitle(attributes.get("title"), preferredLang)
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
              ?.next()
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
          if (contentRating == "safe")
            0
          else if (contentRating == "suggestive")
            13
          else
            18,
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
