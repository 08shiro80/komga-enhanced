package org.gotson.komga.infrastructure.automatch

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.service.MetadataDetails
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

/**
 * Writes a Mylar-shaped `series.json` into a series folder. Reused by
 * `PluginController.applyMetadata` (UI-driven) and `AutoMetadataApplier`
 * (background-driven) so both paths produce identical output — including the
 * provider-aware `web_url` that `MylarSeriesProvider` reads back to populate
 * `SeriesMetadata.links`.
 */
@Service
class SeriesJsonWriter(
  private val objectMapper: ObjectMapper,
) {
  /** Map a metadata-plugin id (e.g. "anilist-metadata") to a normalized provider tag. */
  fun providerOfPluginId(pluginId: String?): String? =
    pluginId?.removeSuffix("-metadata")?.lowercase()?.takeIf { it.isNotBlank() }

  /** Compute a canonical web_url given a normalized provider tag and externalId. */
  fun webUrl(provider: String?, externalId: String?): String? {
    val ext = externalId?.takeIf { it.isNotBlank() } ?: return null
    return when (provider?.lowercase()) {
      "anilist" -> "https://anilist.co/manga/$ext"
      "mangadex" -> "https://mangadex.org/title/$ext"
      "kitsu" -> "https://kitsu.app/manga/$ext"
      else -> defaultWebUrl(ext)
    }
  }

  /** UUID → MangaDex, all-digits → AniList. Used when caller didn't tell us the provider. */
  fun defaultWebUrl(externalId: String): String? {
    val ext = externalId.trim().ifBlank { return null }
    return when {
      Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(ext) ->
        "https://mangadex.org/title/$ext"
      ext.all { it.isDigit() } -> "https://anilist.co/manga/$ext"
      else -> null
    }
  }

  /** Translate any of the upstream status enums (Mylar / AniList / Kitsu) to a Mylar-friendly status string. */
  fun normalizeStatus(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when (raw.lowercase()) {
      "ongoing", "continuing" -> "Continuing"
      "completed", "ended", "finished" -> "Ended"
      "hiatus", "paused" -> "Hiatus"
      "cancelled", "canceled", "dropped" -> "Cancelled"
      "releasing", "current" -> "Continuing"
      "not_yet_released" -> "Continuing"
      else -> raw
    }
  }

  /** Map an integer age rating to the Mylar `age_rating` bucket. */
  fun ageRatingBucket(rating: Int?): String? {
    if (rating == null) return null
    return when {
      rating <= 0 -> "All"
      rating <= 9 -> "9+"
      rating <= 12 -> "12+"
      rating <= 15 -> "15+"
      rating <= 17 -> "17+"
      else -> "Adult"
    }
  }

  /**
   * Atomically write a Mylar-shaped series.json describing [details] into [seriesPath].
   * [pluginId] is used to compute the right web_url so MylarSeriesProvider can produce
   * a tracker-resolvable WebLink. Returns the path written.
   */
  fun write(
    seriesPath: Path,
    details: MetadataDetails,
    externalId: String?,
    pluginId: String?,
    authors: List<Pair<String, String>>? = null,
    altTitles: Map<String, String>? = null,
  ): Path {
    val provider = providerOfPluginId(pluginId)
    val metadata = mutableMapOf<String, Any>("type" to "comicSeries", "name" to details.title)
    details.summary?.let { metadata["description_text"] = it }
    details.publisher?.let { metadata["publisher"] = it }
    ageRatingBucket(details.ageRating)?.let { metadata["age_rating"] = it }
    details.releaseDate?.toIntOrNull()?.let { metadata["year"] = it }
    normalizeStatus(details.status)?.let { metadata["status"] = it }
    externalId?.takeIf { it.isNotBlank() }?.let { metadata["comicid"] = it }
    if (details.genres.isNotEmpty()) metadata["genres"] = details.genres
    if (details.tags.isNotEmpty()) metadata["tags"] = details.tags

    val resolvedAlt = altTitles ?: details.alternativeTitles
    if (resolvedAlt.isNotEmpty()) {
      metadata["alternate_titles"] = resolvedAlt.map { (title, lang) -> mapOf("title" to title, "language" to lang) }
    }

    val resolvedAuthors = authors ?: details.authors.map { it.name to it.role }
    if (resolvedAuthors.isNotEmpty()) {
      val writers = resolvedAuthors.filter { it.second.equals("author", true) || it.second.equals("writer", true) }.map { it.first }.distinct()
      val artists = resolvedAuthors.filter { it.second.equals("artist", true) || it.second.equals("penciller", true) }.map { it.first }.distinct()
      if (writers.isNotEmpty()) metadata["authors"] = writers
      if (artists.isNotEmpty()) metadata["artists"] = artists
    }
    webUrl(provider, externalId)?.let { metadata["web_url"] = it }

    val seriesJson = mapOf("metadata" to metadata)
    val target = seriesPath.resolve("series.json").toFile()
    val tmp = File(target.parent, ".series.json.tmp")
    tmp.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(seriesJson))
    try {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    logger.info { "Wrote series.json: ${target.absolutePath} (provider=$provider, externalId=$externalId)" }
    return target.toPath()
  }
}
