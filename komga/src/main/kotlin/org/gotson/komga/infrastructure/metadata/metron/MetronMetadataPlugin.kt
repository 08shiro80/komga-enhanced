package org.gotson.komga.infrastructure.metadata.metron

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
import java.net.URLEncoder
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class MetronMetadataPlugin(
  private val objectMapper: ObjectMapper,
  private val pluginConfigRepository: PluginConfigRepository,
) : OnlineMetadataProvider {
  private val restClient = RestClient.create("https://metron.cloud")
  private val pluginId = "metron-metadata"

  private fun credentials(): Pair<String, String> {
    val user = pluginConfigRepository.findByPluginIdAndKey(pluginId, "metron_username")?.configValue ?: ""
    val pass = pluginConfigRepository.findByPluginIdAndKey(pluginId, "metron_password")?.configValue ?: ""
    return user to pass
  }

  private fun basicAuth(username: String, password: String): String {
    val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    return "Basic $encoded"
  }

  private fun authHeader(): String {
    val (user, pass) = credentials()
    return basicAuth(user, pass)
  }

  private fun hasCredentials(): Boolean {
    val (user, pass) = credentials()
    return user.isNotBlank() && pass.isNotBlank()
  }

  override fun search(query: String): List<MetadataSearchResult> {
    if (!hasCredentials()) {
      logger.warn { "Metron credentials not configured for search" }
      return emptyList()
    }

    return try {
      logger.info { "Searching Metron for: $query" }
      val encodedName = URLEncoder.encode(query, "UTF-8")
      val response = restClient.get()
        .uri("/api/series/?name=$encodedName")
        .header("Authorization", authHeader())
        .retrieve()
        .body(String::class.java)

      if (response == null) return emptyList()

      val json = objectMapper.readTree(response)
      val results = json.get("results")

      if (results == null || !results.isArray) return emptyList()

      results.map { item ->
        val id = item.get("id").asText()
        val name = item.get("name")?.asText() ?: "Unknown"
        val publisherNode = item.get("publisher")
        val publisher = publisherNode?.get("name")?.asText()
        val description = item.get("description")?.asText()
        val year = item.get("year")?.asInt()
        val status = item.get("status")?.asText()
        val coverUrl = item.get("cover")?.asText()

        MetadataSearchResult(
          externalId = id,
          title = name,
          description = description,
          coverUrl = coverUrl,
          author = publisher,
          year = year,
          status = status,
          provider = "Metron",
        )
      }
    } catch (e: RestClientException) {
      logger.error(e) { "Error searching Metron" }
      emptyList()
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error searching Metron" }
      emptyList()
    }
  }

  override fun getMetadata(externalId: String): MetadataDetails? {
    if (!hasCredentials()) {
      logger.warn { "Metron credentials not configured for metadata" }
      return null
    }

    return try {
      logger.info { "Fetching Metron metadata for series ID: $externalId" }
      val response = restClient.get()
        .uri("/api/series/$externalId/")
        .header("Authorization", authHeader())
        .retrieve()
        .body(String::class.java)

      if (response == null) return null

      val series = objectMapper.readTree(response)
      val title = series.get("name")?.asText() ?: return null
      val publisherNode = series.get("publisher")
      val publisher = publisherNode?.get("name")?.asText()

      val description = series.get("description")?.asText()
      val coverUrl = series.get("cover")?.asText()
      val year = series.get("year")?.asInt()
      val status = series.get("status")?.asText()

      val genres = mutableListOf<String>()
      val genresNode = series.get("genres")
      if (genresNode != null && genresNode.isArray) {
        for (g in genresNode) {
          g.get("name")?.asText()?.let { genres.add(it) }
        }
      }

      val authors = mutableListOf<Author>()

      val releaseDate = year?.toString()

      MetadataDetails(
        title = title,
        titleSort = null,
        summary = description,
        publisher = publisher,
        ageRating = null,
        releaseDate = releaseDate,
        authors = authors,
        genres = genres,
        tags = emptyList(),
        language = "en",
        status = status,
        coverUrl = coverUrl,
        alternativeTitles = emptyMap(),
      )
    } catch (e: RestClientException) {
      logger.error(e) { "Error fetching Metron metadata" }
      null
    } catch (e: Exception) {
      logger.error(e) { "Unexpected error fetching Metron metadata" }
      null
    }
  }
}
