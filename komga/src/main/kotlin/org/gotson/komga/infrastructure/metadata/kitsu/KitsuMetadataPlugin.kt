package org.gotson.komga.infrastructure.metadata.kitsu

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.service.Author
import org.gotson.komga.domain.service.MetadataDetails
import org.gotson.komga.domain.service.MetadataSearchResult
import org.gotson.komga.domain.service.OnlineMetadataProvider
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

private val logger = KotlinLogging.logger {}

@Service
class KitsuMetadataPlugin(
  private val objectMapper: ObjectMapper,
) : OnlineMetadataProvider {
  private val restClient = RestClient.create("https://kitsu.app/api/edge")

  override fun search(query: String): List<MetadataSearchResult> {
    return try {
      logger.info { "Searching Kitsu for: $query" }

      val response =
        restClient
          .get()
          .uri { uriBuilder ->
            uriBuilder
              .path("/manga")
              .queryParam("filter[text]", query)
              .queryParam("page[limit]", 20)
              .queryParam("fields[manga]", "titles,canonicalTitle,synopsis,posterImage,startDate,status,subtype,genres")
              .queryParam("include", "genres")
              .build()
          }.header("Accept", "application/vnd.api+json")
          .retrieve()
          .body(String::class.java)

      if (response == null) return emptyList()

      val json = objectMapper.readTree(response)
      val data = json.get("data") ?: return emptyList()
      if (!data.isArray) return emptyList()

      val includedGenres = parseIncludedGenres(json.get("included"))

      data.mapNotNull { item ->
        val id = item.get("id")?.asText() ?: return@mapNotNull null
        val attrs = item.get("attributes") ?: return@mapNotNull null

        val title = attrs.get("canonicalTitle")?.asText() ?: return@mapNotNull null
        val synopsis = attrs.get("synopsis")?.asText()
        val posterImage =
          attrs
            .get("posterImage")
            ?.get("large")
            ?.asText()
            ?: attrs
              .get("posterImage")
              ?.get("medium")
              ?.asText()
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

        MetadataSearchResult(
          externalId = id,
          title = title,
          description = synopsis,
          coverUrl = posterImage,
          author = null,
          year = year,
          status = status,
          tags = genres,
          provider = "Kitsu",
        )
      }
    } catch (e: RestClientException) {
      logger.error(e) { "Error searching Kitsu" }
      emptyList()
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error searching Kitsu" }
      emptyList()
    }
  }

  override fun getMetadata(externalId: String): MetadataDetails? {
    return try {
      logger.info { "Fetching Kitsu metadata for ID: $externalId" }

      val response =
        restClient
          .get()
          .uri { uriBuilder ->
            uriBuilder
              .path("/manga/$externalId")
              .queryParam("include", "genres,staff,staff.person")
              .build()
          }.header("Accept", "application/vnd.api+json")
          .retrieve()
          .body(String::class.java)

      if (response == null) return null

      val json = objectMapper.readTree(response)
      val attrs = json.get("data")?.get("attributes") ?: return null

      val canonicalTitle = attrs.get("canonicalTitle")?.asText() ?: "Unknown"
      val titlesNode = attrs.get("titles")
      val alternativeTitles = extractAlternativeTitles(titlesNode, canonicalTitle)

      val synopsis = attrs.get("synopsis")?.asText()
      val posterImage =
        attrs
          .get("posterImage")
          ?.get("large")
          ?.asText()
          ?: attrs
            .get("posterImage")
            ?.get("medium")
            ?.asText()
      val status = mapKitsuStatus(attrs.get("status")?.asText())
      val ageRating = mapKitsuAgeRating(attrs.get("ageRating")?.asText())

      val startDate = attrs.get("startDate")?.asText()

      val included = json.get("included")
      val authors = extractAuthors(included)
      val genres = extractGenres(included)

      val titleSort = titlesNode?.get("en_jp")?.asText() ?: canonicalTitle

      MetadataDetails(
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
    } catch (e: RestClientException) {
      logger.error(e) { "Error fetching Kitsu metadata" }
      null
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error fetching Kitsu metadata" }
      null
    }
  }

  private fun parseIncludedGenres(included: JsonNode?): Map<String, String> {
    if (included == null || !included.isArray) return emptyMap()
    val genres = mutableMapOf<String, String>()
    for (item in included) {
      if (item.get("type")?.asText() == "genres") {
        val id = item.get("id")?.asText() ?: continue
        val name = item.get("attributes")?.get("name")?.asText() ?: continue
        genres[id] = name
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

  private fun extractAuthors(included: JsonNode?): List<Author> {
    if (included == null || !included.isArray) return emptyList()

    val people = mutableMapOf<String, String>()
    for (item in included) {
      if (item.get("type")?.asText() == "people") {
        val id = item.get("id")?.asText() ?: continue
        val name = item.get("attributes")?.get("name")?.asText() ?: continue
        people[id] = name
      }
    }

    val authors = mutableListOf<Author>()
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
        val name = people[personId] ?: continue
        authors.add(Author(name, role))
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
