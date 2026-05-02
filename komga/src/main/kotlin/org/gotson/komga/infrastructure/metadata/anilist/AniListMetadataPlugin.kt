package org.gotson.komga.infrastructure.metadata.anilist

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.service.Author
import org.gotson.komga.domain.service.MetadataDetails
import org.gotson.komga.domain.service.MetadataSearchResult
import org.gotson.komga.domain.service.OnlineMetadataProvider
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

private val logger = KotlinLogging.logger {}

/**
 * AniList metadata provider with configurable title language
 *
 * Configuration (via Plugin Settings):
 * - preferred_title_language: Title type to prefer (english, romaji, native)
 *   Default: "english"
 *
 * Available options:
 * - english: English title (e.g., "Attack on Titan")
 * - romaji: Romanized Japanese (e.g., "Shingeki no Kyojin")
 * - native: Original script (e.g., "進撃の巨人")
 */
@Service
class AniListMetadataPlugin(
  private val objectMapper: ObjectMapper,
  private val pluginConfigRepository: PluginConfigRepository,
) : OnlineMetadataProvider {
  private val restClient = RestClient.create("https://graphql.anilist.co")
  private val pluginId = "anilist-metadata"

  /**
   * Get the preferred title type from config, default to "english"
   */
  private fun getPreferredTitleType(): String = pluginConfigRepository.findByPluginIdAndKey(pluginId, "preferred_title_language")?.configValue ?: "english"

  /**
   * Extract title based on preferred type with fallbacks
   * Priority: preferred type → english → romaji → native
   */
  private fun extractTitle(
    titleNode: JsonNode?,
    preferredType: String,
  ): String {
    if (titleNode == null) return "Unknown"

    // Try preferred type first
    titleNode
      .get(preferredType)
      ?.asText()
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    // Fallback chain: english → romaji → native
    val fallbackOrder =
      when (preferredType) {
        "english" -> listOf("romaji", "native")
        "romaji" -> listOf("english", "native")
        "native" -> listOf("english", "romaji")
        else -> listOf("english", "romaji", "native")
      }

    for (fallback in fallbackOrder) {
      titleNode
        .get(fallback)
        ?.asText()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    }

    return "Unknown"
  }

  /**
   * Extract all alternative titles (excluding the primary title)
   * Returns a map of title -> language code
   */
  private fun extractAlternativeTitles(
    titleNode: JsonNode?,
    primaryTitle: String,
  ): Map<String, String> {
    if (titleNode == null) return emptyMap()

    val alternativeTitles = mutableMapOf<String, String>()

    // Map AniList title types to language codes
    val typeToLangMap =
      mapOf(
        "english" to "en",
        "romaji" to "ja-ro",
        "native" to "ja",
      )

    typeToLangMap.forEach { (type, lang) ->
      val title = titleNode.get(type)?.asText()
      if (!title.isNullOrBlank() && title != primaryTitle) {
        alternativeTitles[title] = lang
      }
    }

    return alternativeTitles
  }

  override fun search(query: String): List<MetadataSearchResult> {
    return try {
      logger.info { "Searching AniList for: $query" }
      val preferredType = getPreferredTitleType()
      logger.debug { "Using preferred title type: $preferredType" }

      val graphQLQuery =
        """
        query (${"$"}search: String) {
          Page(page: 1, perPage: 20) {
            media(search: ${"$"}search, type: MANGA) {
              id
              title {
                romaji
                english
                native
              }
              description
              coverImage {
                large
              }
              staff {
                edges {
                  node {
                    name {
                      full
                    }
                  }
                  role
                }
              }
              startDate {
                year
              }
              status
              genres
              tags {
                name
              }
            }
          }
        }
        """.trimIndent()

      val requestBody =
        mapOf(
          "query" to graphQLQuery,
          "variables" to mapOf("search" to query),
        )

      val response =
        restClient
          .post()
          .contentType(MediaType.APPLICATION_JSON)
          .body(requestBody)
          .retrieve()
          .body(String::class.java)

      if (response == null) return emptyList()

      val json = objectMapper.readTree(response)
      val mediaArray = json.get("data")?.get("Page")?.get("media")

      if (mediaArray == null || !mediaArray.isArray) return emptyList()

      mediaArray.map { item ->
        val id = item.get("id").asText()
        val titleNode = item.get("title")
        val title = extractTitle(titleNode, preferredType)

        val description = item.get("description")?.asText()?.let { stripHtml(it) }
        val coverUrl = item.get("coverImage")?.get("large")?.asText()
        val year = item.get("startDate")?.get("year")?.asInt()
        val status = item.get("status")?.asText()

        // Extract author
        var author: String? = null
        val staffEdges = item.get("staff")?.get("edges")
        if (staffEdges != null && staffEdges.isArray) {
          for (edge in staffEdges) {
            val role = edge.get("role")?.asText()
            if (role == "Story" || role == "Story & Art") {
              author =
                edge
                  .get("node")
                  ?.get("name")
                  ?.get("full")
                  ?.asText()
              break
            }
          }
        }

        // Extract tags
        val genres = item.get("genres")?.map { it.asText() } ?: emptyList()
        val tags = item.get("tags")?.map { it.get("name").asText() } ?: emptyList()
        val allTags = (genres + tags).distinct()

        MetadataSearchResult(
          externalId = id,
          title = title,
          description = description,
          coverUrl = coverUrl,
          author = author,
          year = year,
          status = status,
          tags = allTags,
          provider = "AniList",
        )
      }
    } catch (e: RestClientException) {
      logger.error(e) { "Error searching AniList" }
      emptyList()
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error searching AniList" }
      emptyList()
    }
  }

  override fun getMetadata(externalId: String): MetadataDetails? {
    return try {
      logger.info { "Fetching AniList metadata for ID: $externalId" }
      val preferredType = getPreferredTitleType()

      val graphQLQuery =
        """
        query (${"$"}id: Int) {
          Media(id: ${"$"}id, type: MANGA) {
            title {
              romaji
              english
              native
            }
            description
            coverImage {
              large
            }
            staff {
              edges {
                node {
                  name {
                    full
                  }
                }
                role
              }
            }
            startDate {
              year
              month
              day
            }
            status
            genres
            tags {
              name
            }
            isAdult
          }
        }
        """.trimIndent()

      val requestBody =
        mapOf(
          "query" to graphQLQuery,
          "variables" to mapOf("id" to externalId.toIntOrNull()),
        )

      val response =
        restClient
          .post()
          .contentType(MediaType.APPLICATION_JSON)
          .body(requestBody)
          .retrieve()
          .body(String::class.java)

      if (response == null) return null

      val json = objectMapper.readTree(response)
      val media = json.get("data")?.get("Media") ?: return null

      val titleNode = media.get("title")
      val title = extractTitle(titleNode, preferredType)
      val alternativeTitles = extractAlternativeTitles(titleNode, title)

      val description = media.get("description")?.asText()?.let { stripHtml(it) }
      val coverUrl = media.get("coverImage")?.get("large")?.asText()
      val status = media.get("status")?.asText()
      val isAdult = media.get("isAdult")?.asBoolean() ?: false

      // Build release date
      val startDate = media.get("startDate")
      val year = startDate?.get("year")?.asInt()
      val month = startDate?.get("month")?.asInt()
      val day = startDate?.get("day")?.asInt()
      val releaseDate =
        if (year != null) {
          buildString {
            append(year)
            if (month != null) {
              append("-${month.toString().padStart(2, '0')}")
              if (day != null) {
                append("-${day.toString().padStart(2, '0')}")
              }
            }
          }
        } else {
          null
        }

      // Extract authors and artists
      val authors = mutableListOf<Author>()
      val staffEdges = media.get("staff")?.get("edges")
      if (staffEdges != null && staffEdges.isArray) {
        for (edge in staffEdges) {
          val role = edge.get("role")?.asText()
          val name =
            edge
              .get("node")
              ?.get("name")
              ?.get("full")
              ?.asText()
          if (name != null && role != null) {
            authors.add(Author(name, role))
          }
        }
      }

      // Extract genres and tags
      val genres = media.get("genres")?.map { it.asText() } ?: emptyList()
      val tags = media.get("tags")?.map { it.get("name").asText() } ?: emptyList()

      // Map preferred type to language code
      val languageCode =
        when (preferredType) {
          "english" -> "en"
          "romaji" -> "ja-ro"
          "native" -> "ja"
          else -> "en"
        }

      logger.info { "Extracted ${alternativeTitles.size} alternative titles for '$title'" }

      MetadataDetails(
        title = title,
        titleSort = titleNode?.get("romaji")?.asText(), // Always use romaji for sorting
        summary = description,
        publisher = null,
        ageRating = if (isAdult) 18 else 13,
        releaseDate = releaseDate,
        authors = authors,
        tags = tags,
        genres = genres,
        language = languageCode,
        status = status,
        coverUrl = coverUrl,
        alternativeTitles = alternativeTitles,
      )
    } catch (e: RestClientException) {
      logger.error(e) { "Error fetching AniList metadata" }
      null
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error fetching AniList metadata" }
      null
    }
  }

  private fun stripHtml(html: String): String =
    html
      .replace(Regex("<[^>]*>"), "")
      .replace(Regex("\\s+"), " ")
      .trim()
}
