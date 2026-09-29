package com.komga.plugins.kitsu

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.infrastructure.plugin.api.MetadataProviderPlugin
import org.gotson.komga.infrastructure.plugin.api.PluginAuthor
import org.gotson.komga.infrastructure.plugin.api.PluginContext
import org.gotson.komga.infrastructure.plugin.api.PluginMetadataDetails
import org.gotson.komga.infrastructure.plugin.api.PluginSearchResult
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * External, dynamically-loaded Kitsu metadata provider. Ported from the former
 * built-in plugin to exercise the runtime plugin-loading pipeline. Uses the JDK
 * HTTP client + Jackson (provided by Komga's class loader at runtime).
 */
class KitsuMetadataPlugin : MetadataProviderPlugin {
  override val id = "kitsu-metadata"
  override val name = "Kitsu"
  override val version = "1.0.0"
  override val author = "Kasch_X"
  override val description = "Fetches manga metadata from kitsu.app. Loaded as an external plugin."

  private val baseUrl = "https://kitsu.app/api/edge"
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

  override fun search(query: String): List<PluginSearchResult> {
    return try {
      val url =
        "$baseUrl/manga?filter%5Btext%5D=${enc(query)}" +
          "&page%5Blimit%5D=20" +
          "&fields%5Bmanga%5D=${enc("titles,canonicalTitle,synopsis,posterImage,startDate,status,subtype,genres")}" +
          "&include=genres"
      val response = fetch(url) ?: return emptyList()

      val json = mapper.readTree(response)
      val data = json.get("data") ?: return emptyList()
      if (!data.isArray) return emptyList()

      val includedGenres = parseIncludedGenres(json.get("included"))

      data.mapNotNull { item ->
        val externalId = item.get("id")?.asText() ?: return@mapNotNull null
        val attrs = item.get("attributes") ?: return@mapNotNull null

        val title = attrs.get("canonicalTitle")?.asText() ?: return@mapNotNull null
        val synopsis = attrs.get("synopsis")?.asText()
        val posterImage = posterImageOf(attrs)
        val year =
          attrs
            .get("startDate")
            ?.asText()
            ?.take(4)
            ?.toIntOrNull()
        val status = mapKitsuStatus(attrs.get("status")?.asText())

        val genreIds = item.get("relationships")?.get("genres")?.get("data")
        val genres =
          if (genreIds != null && genreIds.isArray) {
            genreIds.mapNotNull { ref -> includedGenres[ref.get("id")?.asText()] }
          } else {
            emptyList()
          }

        PluginSearchResult(
          externalId = externalId,
          title = title,
          description = synopsis,
          coverUrl = posterImage,
          author = null,
          year = year,
          status = status,
          tags = genres,
        )
      }
    } catch (e: Exception) {
      context?.error("Error searching Kitsu for '$query'", e)
      emptyList()
    }
  }

  override fun getMetadata(externalId: String): PluginMetadataDetails? {
    return try {
      val url = "$baseUrl/manga/${enc(externalId)}?include=${enc("genres,staff,staff.person")}"
      val response = fetch(url) ?: return null

      val json = mapper.readTree(response)
      val attrs = json.get("data")?.get("attributes") ?: return null

      val canonicalTitle = attrs.get("canonicalTitle")?.asText() ?: "Unknown"
      val titlesNode = attrs.get("titles")
      val alternativeTitles = extractAlternativeTitles(titlesNode, canonicalTitle)

      val synopsis = attrs.get("synopsis")?.asText()
      val posterImage = posterImageOf(attrs)
      val status = mapKitsuStatus(attrs.get("status")?.asText())
      val ageRating = mapKitsuAgeRating(attrs.get("ageRating")?.asText())
      val startDate = attrs.get("startDate")?.asText()

      val included = json.get("included")
      val authors = extractAuthors(included)
      val genres = extractGenres(included)

      val titleSort = titlesNode?.get("en_jp")?.asText() ?: canonicalTitle

      PluginMetadataDetails(
        title = canonicalTitle,
        titleSort = titleSort,
        summary = synopsis,
        publisher = null,
        ageRating = ageRating,
        releaseDate = startDate,
        authors = authors,
        tags = emptyList(),
        genres = genres,
        language = "en",
        status = status,
        coverUrl = posterImage,
        alternativeTitles = alternativeTitles,
      )
    } catch (e: Exception) {
      context?.error("Error fetching Kitsu metadata for '$externalId'", e)
      null
    }
  }

  private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  private fun fetch(url: String): String? {
    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .header("Accept", "application/vnd.api+json")
        .GET()
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
        context?.warn("Kitsu rate limited (HTTP $code), retrying in ${waitSeconds}s")
        Thread.sleep(waitSeconds * 1000)
        attempt++
      } else {
        context?.warn("Kitsu returned HTTP $code")
        return null
      }
    }
  }

  private fun posterImageOf(attrs: JsonNode): String? =
    attrs
      .get("posterImage")
      ?.get("large")
      ?.asText()
      ?: attrs
        .get("posterImage")
        ?.get("medium")
        ?.asText()

  private fun parseIncludedGenres(included: JsonNode?): Map<String, String> {
    if (included == null || !included.isArray) return emptyMap()
    val genres = mutableMapOf<String, String>()
    for (item in included) {
      if (item.get("type")?.asText() == "genres") {
        val genreId = item.get("id")?.asText() ?: continue
        val genreName = item.get("attributes")?.get("name")?.asText() ?: continue
        genres[genreId] = genreName
      }
    }
    return genres
  }

  private fun extractAlternativeTitles(
    titlesNode: JsonNode?,
    primaryTitle: String,
  ): Map<String, String> {
    if (titlesNode == null) return emptyMap()

    val langMap =
      mapOf(
        "en" to "en",
        "en_us" to "en",
        "en_jp" to "ja-ro",
        "ja_jp" to "ja",
        "ko_kr" to "ko",
        "zh_cn" to "zh",
      )

    val alternativeTitles = mutableMapOf<String, String>()
    titlesNode.fields().forEach { (key, value) ->
      val title = value.asText()
      if (title.isNotBlank() && title != primaryTitle) {
        alternativeTitles[title] = langMap[key] ?: key
      }
    }
    return alternativeTitles
  }

  private fun extractAuthors(included: JsonNode?): List<PluginAuthor> {
    if (included == null || !included.isArray) return emptyList()

    val people = mutableMapOf<String, String>()
    for (item in included) {
      if (item.get("type")?.asText() == "people") {
        val personId = item.get("id")?.asText() ?: continue
        val personName = item.get("attributes")?.get("name")?.asText() ?: continue
        people[personId] = personName
      }
    }

    val authors = mutableListOf<PluginAuthor>()
    for (item in included) {
      if (item.get("type")?.asText() == "mediaStaff") {
        val role = item.get("attributes")?.get("role")?.asText() ?: continue
        val personId =
          item
            .get("relationships")
            ?.get("person")
            ?.get("data")
            ?.get("id")
            ?.asText()
            ?: continue
        val personName = people[personId] ?: continue
        authors.add(PluginAuthor(personName, role))
      }
    }
    return authors
  }

  private fun extractGenres(included: JsonNode?): List<String> {
    if (included == null || !included.isArray) return emptyList()
    return included
      .filter { it.get("type")?.asText() == "genres" }
      .mapNotNull { it.get("attributes")?.get("name")?.asText() }
  }

  private fun mapKitsuStatus(status: String?): String? =
    when (status) {
      "current" -> "RELEASING"
      "finished" -> "FINISHED"
      "tba" -> "NOT_YET_RELEASED"
      "unreleased" -> "NOT_YET_RELEASED"
      "upcoming" -> "NOT_YET_RELEASED"
      else -> status
    }

  private fun mapKitsuAgeRating(rating: String?): Int? =
    when (rating) {
      "G" -> 0
      "PG" -> 10
      "R" -> 17
      "R18" -> 18
      else -> null
    }
}
