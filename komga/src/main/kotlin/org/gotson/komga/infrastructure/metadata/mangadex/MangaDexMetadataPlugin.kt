package org.gotson.komga.infrastructure.metadata.mangadex

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
class MangaDexMetadataPlugin(
  private val objectMapper: ObjectMapper,
) : OnlineMetadataProvider {
  private val restClient = RestClient.create("https://api.mangadex.org")

  override fun search(query: String): List<MetadataSearchResult> {
    return try {
      logger.info { "Searching MangaDex for: $query" }

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
        val title =
          attributes.get("title")?.get("en")?.asText()
            ?: attributes
              .get("title")
              ?.fields()
              ?.next()
              ?.value
              ?.asText()
            ?: "Unknown"
        val description = attributes.get("description")?.get("en")?.asText()
        val status = attributes.get("status")?.asText()
        val year = attributes.get("year")?.asInt()

        // Extract tags
        val tags =
          attributes
            .get("tags")
            ?.map { tag ->
              tag
                .get("attributes")
                ?.get("name")
                ?.get("en")
                ?.asText() ?: ""
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

      val title =
        attributes.get("title")?.get("en")?.asText()
          ?: attributes
            .get("title")
            ?.fields()
            ?.next()
            ?.value
            ?.asText()
          ?: "Unknown"

      val description = attributes.get("description")?.get("en")?.asText()
      val status = attributes.get("status")?.asText()
      val year = attributes.get("year")?.asInt()
      val contentRating = attributes.get("contentRating")?.asText()

      // Extract tags and genres
      val tags =
        attributes.get("tags")?.mapNotNull { tag ->
          tag
            .get("attributes")
            ?.get("name")
            ?.get("en")
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
        language = "en",
        status = status,
        coverUrl = coverUrl,
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
