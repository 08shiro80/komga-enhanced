package org.gotson.komga.infrastructure.metadata.tracker

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.model.MetadataPatchTarget
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.SeriesMetadataPatch
import org.gotson.komga.domain.model.SeriesTrackerId
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesTrackerIdRepository
import org.gotson.komga.infrastructure.metadata.SeriesMetadataProvider
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimiter
import org.gotson.komga.infrastructure.scrobbler.interactiveMaxWait
import org.springframework.stereotype.Component
import java.net.URI

private val logger = KotlinLogging.logger {}

@Component
class TrackerLinkEnricher(
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val pluginRepository: PluginRepository,
  private val seriesTrackerIdRepository: SeriesTrackerIdRepository,
  private val trackerTitleSearchProvider: TrackerTitleSearchProvider,
  private val trackerRateLimiter: TrackerRateLimiter,
) : SeriesMetadataProvider {
  override fun shouldLibraryHandlePatch(
    library: Library,
    target: MetadataPatchTarget,
  ): Boolean = target == MetadataPatchTarget.SERIES

  override fun getSeriesMetadata(series: Series): SeriesMetadataPatch? {
    val meta = seriesMetadataRepository.findById(series.id)
    if (meta.linksLock) return null

    val title = meta.title
    val existingLabels = meta.links.map { it.label.lowercase() }.toSet()
    logger.debug { "TrackerLinkEnricher: series='$title', existing link labels=$existingLabels" }
    val newLinks = mutableListOf<WebLink>()

    if (isMetadataPluginEnabled("anilist") && "anilist" !in existingLabels) {
      foundId(UserTracker.ANILIST, meta)?.let { id ->
        newLinks.add(WebLink("AniList", URI("https://anilist.co/manga/$id")))
      }
    }
    if ("myanimelist" !in existingLabels) {
      foundId(UserTracker.MAL, meta)?.let { id ->
        newLinks.add(WebLink("MyAnimeList", URI("https://myanimelist.net/manga/$id")))
      }
    }
    if (isMetadataPluginEnabled("mangadex") && "mangadex" !in existingLabels) {
      foundId(UserTracker.MANGADEX, meta)?.let { id ->
        newLinks.add(WebLink("MangaDex", URI("https://mangadex.org/title/$id")))
      }
    }
    if (isMetadataPluginEnabled("kitsu") && "kitsu" !in existingLabels) {
      foundId(UserTracker.KITSU, meta)?.let { id ->
        newLinks.add(WebLink("Kitsu", URI("https://kitsu.app/manga/$id")))
      }
    }

    if (newLinks.isEmpty()) {
      logger.debug { "TrackerLinkEnricher: no new links for '$title'" }
      return null
    }
    // preserve existing links since each provider's patch replaces the entire field
    val allLinks = meta.links + newLinks
    logger.info { "TrackerLinkEnricher: added ${newLinks.map { it.label }.joinToString()} for '$title'" }
    return SeriesMetadataPatch(
      title = null,
      titleSort = null,
      status = null,
      summary = null,
      readingDirection = null,
      publisher = null,
      ageRating = null,
      language = null,
      genres = null,
      totalBookCount = null,
      collections = emptySet(),
      alternateTitles = null,
      links = allLinks,
    )
  }

  private fun foundId(
    tracker: String,
    meta: SeriesMetadata,
  ): String? {
    val result = trackerRateLimiter.withMaxWait(interactiveMaxWait) { trackerTitleSearchProvider.search(tracker, meta) }
    val id = (result as? TitleSearchResult.Found)?.id ?: return null
    val rejected =
      seriesTrackerIdRepository
        .findBySeriesId(meta.seriesId)
        .any { it.tracker == tracker && it.trackerId == id && it.status == SeriesTrackerId.Status.REJECTED }
    return if (rejected) null else id
  }

  private fun isMetadataPluginEnabled(shortName: String): Boolean {
    val plugin = pluginRepository.findByIdOrNull("$shortName-metadata") ?: return false
    return plugin.enabled
  }
}
