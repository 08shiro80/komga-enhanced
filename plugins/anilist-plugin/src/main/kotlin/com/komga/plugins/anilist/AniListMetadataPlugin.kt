package com.komga.plugins.anilist

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.infrastructure.plugin.api.MetadataProviderPlugin
import org.gotson.komga.infrastructure.plugin.api.PluginAuthor
import org.gotson.komga.infrastructure.plugin.api.PluginContext
import org.gotson.komga.infrastructure.plugin.api.PluginMetadataDetails
import org.gotson.komga.infrastructure.plugin.api.PluginSearchResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * External, dynamically-loaded AniList metadata provider (GraphQL). Ported from
 * the former built-in plugin. Config key `preferred_title_language`
 * (english/romaji/native, default english).
 */
class AniListMetadataPlugin : MetadataProviderPlugin {
  override val id = "anilist-metadata"
  override val name = "AniList"
  override val version = "1.0.0"
  override val author = "Kasch_X"
  override val description = "Fetches manga and anime metadata from the AniList GraphQL API. Loaded as an external plugin."
  override val configSchema =
    """
    {
      "type": "object",
      "properties": {
        "preferred_title_language": {
          "type": "string",
          "title": "Preferred title language",
          "description": "Which title to prefer: english, romaji or native",
          "default": "english",
          "enum": ["english", "romaji", "native"]
        }
      }
    }
    """.trimIndent()

  private val endpoint = "https://graphql.anilist.co"
  private val httpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build()
  private val mapper = ObjectMapper()

  private var context: PluginContext? = null

  override fun initialize(context: PluginContext) {
    this.context = context
  }

  private fun preferredTitleType(): String = context?.config?.get("preferred_title_language") ?: "english"

  override fun search(query: String): List<PluginSearchResult> {
    return try {
      val preferredType = preferredTitleType()
      val graphQLQuery =
        """
        query (${"$"}search: String) {
          Page(page: 1, perPage: 20) {
            media(search: ${"$"}search, type: MANGA) {
              id
              title { romaji english native }
              description
              coverImage { large }
              staff { edges { node { name { full } } role } }
              startDate { year }
              status
              genres
              tags { name }
            }
          }
        }
        """.trimIndent()
      val body =
        mapper.writeValueAsString(
          mapOf(
            "query" to graphQLQuery,
            "variables" to mapOf("search" to query),
          ),
        )
      val response = post(body) ?: return emptyList()

      val json = mapper.readTree(response)
      val mediaArray = json.get("data")?.get("Page")?.get("media")
      if (mediaArray == null || !mediaArray.isArray) return emptyList()

      mediaArray.map { item ->
        val titleNode = item.get("title")
        val title = extractTitle(titleNode, preferredType)
        val description = item.get("description")?.asText()?.let { stripHtml(it) }
        val coverUrl = item.get("coverImage")?.get("large")?.asText()
        val year = item.get("startDate")?.get("year")?.asInt()
        val status = item.get("status")?.asText()

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

        val genres = item.get("genres")?.map { it.asText() } ?: emptyList()
        val tags = item.get("tags")?.map { it.get("name").asText() } ?: emptyList()

        PluginSearchResult(
          externalId = item.get("id").asText(),
          title = title,
          description = description,
          coverUrl = coverUrl,
          author = author,
          year = year,
          status = status,
          tags = (genres + tags).distinct(),
        )
      }
    } catch (e: Exception) {
      context?.error("Error searching AniList for '$query'", e)
      emptyList()
    }
  }

  override fun getMetadata(externalId: String): PluginMetadataDetails? {
    return try {
      val preferredType = preferredTitleType()
      val graphQLQuery =
        """
        query (${"$"}id: Int) {
          Media(id: ${"$"}id, type: MANGA) {
            title { romaji english native }
            description
            coverImage { large }
            staff { edges { node { name { full } } role } }
            startDate { year month day }
            status
            genres
            tags { name }
            isAdult
          }
        }
        """.trimIndent()
      val body =
        mapper.writeValueAsString(
          mapOf(
            "query" to graphQLQuery,
            "variables" to mapOf("id" to externalId.toIntOrNull()),
          ),
        )
      val response = post(body) ?: return null

      val json = mapper.readTree(response)
      val media = json.get("data")?.get("Media") ?: return null

      val titleNode = media.get("title")
      val title = extractTitle(titleNode, preferredType)
      val alternativeTitles = extractAlternativeTitles(titleNode, title)
      val description = media.get("description")?.asText()?.let { stripHtml(it) }
      val coverUrl = media.get("coverImage")?.get("large")?.asText()
      val status = media.get("status")?.asText()
      val isAdult = media.get("isAdult")?.asBoolean() ?: false

      val startDate = media.get("startDate")
      val year = startDate?.get("year")?.asInt()
      val month = startDate?.get("month")?.asInt()
      val day = startDate?.get("day")?.asInt()
      val releaseDate = buildReleaseDate(year, month, day)

      val authors = mutableListOf<PluginAuthor>()
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
          if (name != null && role != null) authors.add(PluginAuthor(name, role))
        }
      }

      val genres = media.get("genres")?.map { it.asText() } ?: emptyList()
      val tags = media.get("tags")?.map { it.get("name").asText() } ?: emptyList()
      val languageCode =
        when (preferredType) {
          "english" -> "en"
          "romaji" -> "ja-ro"
          "native" -> "ja"
          else -> "en"
        }

      PluginMetadataDetails(
        title = title,
        titleSort = titleNode?.get("romaji")?.asText(),
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
    } catch (e: Exception) {
      context?.error("Error fetching AniList metadata for '$externalId'", e)
      null
    }
  }

  private fun post(body: String): String? {
    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(endpoint))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(Duration.ofSeconds(30))
        .build()
    var attempt = 0
    while (true) {
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      val code = response.statusCode()
      if (code in 200..299) return response.body()
      if ((code == 429 || code == 503) && attempt < 3) {
        val waitSeconds =
          response
            .headers()
            .firstValue("Retry-After")
            .map { it.toLongOrNull() }
            .orElse(null)
            ?.coerceIn(1L, 60L)
            ?: (1L shl attempt)
        context?.warn("AniList rate limited (HTTP $code), retrying in ${waitSeconds}s")
        Thread.sleep(waitSeconds * 1000)
        attempt++
      } else {
        context?.warn("AniList returned HTTP $code")
        return null
      }
    }
  }

  private fun buildReleaseDate(
    year: Int?,
    month: Int?,
    day: Int?,
  ): String? {
    if (year == null) return null
    return buildString {
      append(year)
      if (month != null) {
        append("-${month.toString().padStart(2, '0')}")
        if (day != null) append("-${day.toString().padStart(2, '0')}")
      }
    }
  }

  private fun extractTitle(
    titleNode: JsonNode?,
    preferredType: String,
  ): String {
    if (titleNode == null) return "Unknown"
    titleNode
      .get(preferredType)
      ?.asText()
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }
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

  private fun extractAlternativeTitles(
    titleNode: JsonNode?,
    primaryTitle: String,
  ): Map<String, String> {
    if (titleNode == null) return emptyMap()
    val typeToLangMap =
      mapOf(
        "english" to "en",
        "romaji" to "ja-ro",
        "native" to "ja",
      )
    val alternativeTitles = mutableMapOf<String, String>()
    typeToLangMap.forEach { (type, lang) ->
      val title = titleNode.get(type)?.asText()
      if (!title.isNullOrBlank() && title != primaryTitle) alternativeTitles[title] = lang
    }
    return alternativeTitles
  }

  private fun stripHtml(html: String): String =
    html
      .replace(Regex("<[^>]*>"), "")
      .replace(Regex("\\s+"), " ")
      .trim()
}
