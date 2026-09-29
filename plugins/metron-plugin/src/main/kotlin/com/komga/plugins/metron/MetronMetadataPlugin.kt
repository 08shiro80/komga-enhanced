package com.komga.plugins.metron

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.infrastructure.plugin.api.MetadataProviderPlugin
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
import java.util.Base64

/**
 * External, dynamically-loaded Metron (metron.cloud) comic metadata provider.
 * Ported from the former built-in plugin. Requires config keys `metron_username`
 * and `metron_password` (a free metron.cloud account).
 */
class MetronMetadataPlugin : MetadataProviderPlugin {
  override val id = "metron-metadata"
  override val name = "Metron"
  override val version = "1.0.0"
  override val author = "Kasch_X"
  override val description = "Fetches comic series metadata from metron.cloud (free account required). Loaded as an external plugin."
  override val configSchema =
    """
    {
      "type": "object",
      "properties": {
        "metron_username": {
          "type": "string",
          "title": "Metron Username",
          "description": "Your metron.cloud account username"
        },
        "metron_password": {
          "type": "string",
          "title": "Metron Password",
          "format": "password",
          "description": "Your metron.cloud account password"
        }
      },
      "required": ["metron_username", "metron_password"]
    }
    """.trimIndent()

  private val baseUrl = "https://metron.cloud"
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

  private fun credentials(): Pair<String, String> {
    val cfg = context?.config ?: emptyMap()
    return (cfg["metron_username"] ?: "") to (cfg["metron_password"] ?: "")
  }

  private fun authHeader(): String? {
    val (user, pass) = credentials()
    if (user.isBlank() || pass.isBlank()) return null
    val encoded = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(StandardCharsets.UTF_8))
    return "Basic $encoded"
  }

  override fun search(query: String): List<PluginSearchResult> {
    val auth =
      authHeader() ?: run {
        context?.warn("Metron credentials not configured for search")
        return emptyList()
      }
    return try {
      val encodedName = URLEncoder.encode(query, StandardCharsets.UTF_8)
      val response = get("/api/series/?name=$encodedName", auth) ?: return emptyList()

      val json = mapper.readTree(response)
      val results = json.get("results")
      if (results == null || !results.isArray) return emptyList()

      results.map { item ->
        // Metron SeriesList exposes the display name as `series` and the year as
        // `year_began`; description/cover/status only exist on the detail endpoint.
        PluginSearchResult(
          externalId = item.get("id").asText(),
          title = item.get("series")?.asText() ?: "Unknown",
          year = item.get("year_began")?.asInt(),
        )
      }
    } catch (e: Exception) {
      context?.error("Error searching Metron for '$query'", e)
      emptyList()
    }
  }

  override fun getMetadata(externalId: String): PluginMetadataDetails? {
    val auth =
      authHeader() ?: run {
        context?.warn("Metron credentials not configured for metadata")
        return null
      }
    return try {
      val response = get("/api/series/$externalId/", auth) ?: return null

      val series = mapper.readTree(response)
      val title = series.get("name")?.asText() ?: return null
      val year = series.get("year_began")?.asInt()

      val genres = mutableListOf<String>()
      val genresNode = series.get("genres")
      if (genresNode != null && genresNode.isArray) {
        for (g in genresNode) {
          g.get("name")?.asText()?.let { genres.add(it) }
        }
      }

      PluginMetadataDetails(
        title = title,
        titleSort = series.get("sort_name")?.asText(),
        summary = series.get("desc")?.asText(),
        publisher = series.get("publisher")?.get("name")?.asText(),
        ageRating = null,
        releaseDate = year?.toString(),
        authors = emptyList(),
        genres = genres,
        tags = emptyList(),
        language = "en",
        status = series.get("status")?.asText(),
        coverUrl = null,
        alternativeTitles = emptyMap(),
      )
    } catch (e: Exception) {
      context?.error("Error fetching Metron metadata for '$externalId'", e)
      null
    }
  }

  private fun get(
    path: String,
    auth: String,
  ): String? {
    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create("$baseUrl$path"))
        .header("Authorization", auth)
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(90))
        .GET()
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
        context?.warn("Metron rate limited (HTTP $code), retrying in ${waitSeconds}s")
        Thread.sleep(waitSeconds * 1000)
        attempt++
      } else {
        context?.warn("Metron returned HTTP $code")
        return null
      }
    }
  }
}
