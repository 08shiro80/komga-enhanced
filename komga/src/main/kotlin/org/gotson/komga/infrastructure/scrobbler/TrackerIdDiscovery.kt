package org.gotson.komga.infrastructure.scrobbler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.SeriesTrackerId
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.SeriesTrackerIdRepository
import org.gotson.komga.infrastructure.download.MangaDexApiClient
import org.gotson.komga.infrastructure.download.MangaDexLookup
import org.gotson.komga.infrastructure.download.MangaDexTrackingInfo
import org.gotson.komga.infrastructure.metadata.tracker.TitleSearchResult
import org.gotson.komga.infrastructure.metadata.tracker.TrackerTitleSearchProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val MAX_CACHE_ENTRIES = 10_000

private val notFoundTtl = Duration.ofHours(24)
private val failedBackoff = Duration.ofMinutes(10)

private val uuidRegex = Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""", RegexOption.IGNORE_CASE)
private val mangaDexChapterRegex = Regex("""mangadex\.org/chapter/([0-9a-f-]{36})""", RegexOption.IGNORE_CASE)

val trackerDisplayNames =
  mapOf(
    UserTracker.ANILIST to "AniList",
    UserTracker.MAL to "MyAnimeList",
    UserTracker.KITSU to "Kitsu",
    UserTracker.MANGADEX to "MangaDex",
    UserTracker.METRON to "Metron",
  )

data class TrackerIdFailure(
  val reason: String,
  val retryAfter: Duration? = null,
)

data class TrackerIdResolution(
  val ids: Map<String, String>,
  val failures: Map<String, TrackerIdFailure> = emptyMap(),
)

@Component
class TrackerIdDiscovery(
  private val trackerIdResolver: TrackerIdResolver,
  private val seriesTrackerIdRepository: SeriesTrackerIdRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val seriesRepository: SeriesRepository,
  private val chapterUrlRepository: ChapterUrlRepository,
  private val mangaDexApiClient: MangaDexApiClient,
  private val trackerTitleSearchProvider: TrackerTitleSearchProvider,
  private val eventPublisher: ApplicationEventPublisher,
  private val clock: Clock = Clock.systemUTC(),
) {
  private sealed interface Lookup {
    data class Found(
      val id: String,
    ) : Lookup

    data class NotFound(
      val reason: String,
    ) : Lookup

    data class Failed(
      val reason: String,
      val retryAfter: Duration? = null,
    ) : Lookup
  }

  private inner class Context(
    val series: Series,
    val meta: SeriesMetadata,
    val config: Map<String, String?>,
    val userMalClientId: String?,
    val metronSearch: ((SeriesMetadata) -> TitleSearchResult)?,
  ) {
    private val stored = seriesTrackerIdRepository.findBySeriesId(series.id)
    val accepted = stored.filter { it.status == SeriesTrackerId.Status.ACCEPTED }.associate { it.tracker to it.trackerId }.toMutableMap()
    val rejected = stored.filter { it.status == SeriesTrackerId.Status.REJECTED }.map { it.tracker to it.trackerId }.toSet()
    val mappings = trackerIdResolver.fromMappings(series.id, meta.title, config)
    val links = trackerIdResolver.fromLinks(meta.links)
    val discover = (config["discover_ids"] ?: "true").toBoolean()
    val resolved = mutableMapOf<String, Lookup>()
    var mangaDexInfo: MangaDexLookup<MangaDexTrackingInfo>? = null
  }

  private val notFoundUntil = ConcurrentHashMap<String, Instant>()
  private val failedUntil = ConcurrentHashMap<String, Instant>()
  private val reportedUntil = ConcurrentHashMap<String, Instant>()
  private val linkLock = Any()

  fun resolve(
    series: Series,
    meta: SeriesMetadata,
    trackers: Collection<String>,
    config: Map<String, String?>,
    userMalClientId: String? = null,
    metronSearch: ((SeriesMetadata) -> TitleSearchResult)? = null,
  ): TrackerIdResolution {
    val context = Context(series, meta, config, userMalClientId, metronSearch)
    val ids = mutableMapOf<String, String>()
    val failures = mutableMapOf<String, TrackerIdFailure>()
    trackers.distinct().forEach { tracker ->
      when (val lookup = lookup(context, tracker)) {
        is Lookup.Found -> ids[tracker] = lookup.id
        is Lookup.NotFound -> report(series.id, meta.title, tracker, lookup.reason)
        is Lookup.Failed -> {
          logger.debug { "[scrobbler] ${trackerDisplayNames[tracker]} ID lookup for '${meta.title}' failed and will be retried: ${lookup.reason}" }
          failures[tracker] = TrackerIdFailure(lookup.reason, lookup.retryAfter)
        }
      }
    }
    if (context.accepted.isNotEmpty()) storeLinks(series.id)
    return TrackerIdResolution(ids, failures)
  }

  fun <T> withLinkLock(block: () -> T): T = synchronized(linkLock) { block() }

  fun linksEdited(
    seriesId: String,
    oldLinks: Collection<WebLink>,
    newLinks: Collection<WebLink>,
  ) {
    val before = trackerIdResolver.fromLinks(oldLinks)
    val after = trackerIdResolver.fromLinks(newLinks)
    synchronized(linkLock) {
      val accepted = acceptedIds(seriesId)
      trackerDisplayNames.keys.forEach { tracker ->
        val removed = before[tracker].orEmpty() - after[tracker].orEmpty().toSet()
        val added = after[tracker].orEmpty() - before[tracker].orEmpty().toSet()
        removed.forEach { seriesTrackerIdRepository.reject(seriesId, tracker, it) }
        if (removed.isNotEmpty()) {
          accepted[tracker]
            ?.takeIf { it !in after[tracker].orEmpty() && it !in removed }
            ?.let { seriesTrackerIdRepository.reject(seriesId, tracker, it) }
        }
        added.firstOrNull()?.toKnownId(tracker)?.let { seriesTrackerIdRepository.accept(seriesId, tracker, it, SeriesTrackerId.Source.MANUAL) }
      }
      invalidate(seriesId)
    }
  }

  private fun acceptedIds(seriesId: String): Map<String, String> =
    seriesTrackerIdRepository
      .findBySeriesId(seriesId)
      .filter { it.status == SeriesTrackerId.Status.ACCEPTED }
      .associate { it.tracker to it.trackerId }

  fun invalidate(seriesId: String) {
    listOf(notFoundUntil, failedUntil, reportedUntil).forEach { cache -> cache.keys.removeIf { it.startsWith("$seriesId:") } }
    trackerTitleSearchProvider.invalidate(seriesId)
  }

  private fun String.toKnownId(tracker: String): String? = if (tracker == UserTracker.KITSU && toIntOrNull() == null) null else this

  private fun lookup(
    context: Context,
    tracker: String,
    localOnly: Boolean = false,
  ): Lookup =
    context.resolved.getOrPut(if (localOnly) "$tracker:local" else tracker) {
      lookupUncached(context, tracker, localOnly)
    }

  private fun lookupUncached(
    context: Context,
    tracker: String,
    localOnly: Boolean,
  ): Lookup {
    val seriesId = context.series.id
    context.mappings[tracker]?.let { return accept(context, tracker, it, SeriesTrackerId.Source.MAPPING) }
    context.accepted[tracker]?.let { return Lookup.Found(it) }

    for (candidate in context.links[tracker].orEmpty()) {
      val id =
        if (tracker == UserTracker.KITSU && candidate.toIntOrNull() == null) {
          when (val slug = kitsuIdFromSlug(candidate)) {
            is Lookup.Found -> slug.id
            is Lookup.Failed -> return slug
            is Lookup.NotFound -> continue
          }
        } else {
          candidate
        }
      return accept(context, tracker, id, SeriesTrackerId.Source.LINK)
    }

    if (!context.discover) return Lookup.NotFound("no mapping or link, and ID discovery is disabled (discover_ids)")
    val key = if (localOnly) "$seriesId:$tracker:local" else "$seriesId:$tracker"
    if (isActive(notFoundUntil, key)) return Lookup.NotFound("no match within the last 24 hours")
    remainingFor(failedUntil, key)?.let { return Lookup.Failed("previous lookup failed, waiting before retrying", it) }

    val result =
      when (tracker) {
        UserTracker.MANGADEX -> discoverMangaDex(context, titleSearch = !localOnly)
        UserTracker.METRON -> discoverMetron(context)
        else -> discoverFromMangaDexLinks(context, tracker) ?: discoverByTitle(context, tracker)
      }
    when (result) {
      is Lookup.NotFound -> remember(notFoundUntil, key, notFoundTtl)
      is Lookup.Failed -> remember(failedUntil, key, result.retryAfter?.coerceAtLeast(failedBackoff) ?: failedBackoff)
      is Lookup.Found -> Unit
    }
    return result
  }

  private fun accept(
    context: Context,
    tracker: String,
    id: String,
    source: SeriesTrackerId.Source,
  ): Lookup {
    if (context.accepted[tracker] != id) {
      seriesTrackerIdRepository.accept(context.series.id, tracker, id, source)
      context.accepted[tracker] = id
      logger.info { "[scrobbler] ${trackerDisplayNames[tracker]} ID $id assigned to '${context.meta.title}' (${source.name.lowercase()})" }
    }
    return Lookup.Found(id)
  }

  private fun discoverMangaDex(
    context: Context,
    titleSearch: Boolean,
  ): Lookup {
    val candidates =
      listOfNotNull(
        context.series.mangaDexUuid,
        context.series.url.path
          .trimEnd('/')
          .substringAfterLast('/'),
      ).filter { uuidRegex.matches(it) }
        .distinct()
    var failure: String? = null
    for (candidate in candidates) {
      if (UserTracker.MANGADEX to candidate in context.rejected) continue
      when (val info = mangaDexApiClient.lookupMangaForTracking(candidate)) {
        is MangaDexLookup.Found -> {
          context.mangaDexInfo = info
          return accept(context, UserTracker.MANGADEX, candidate, SeriesTrackerId.Source.MANGADEX)
        }
        is MangaDexLookup.Failed -> failure = info.reason
        MangaDexLookup.NotFound -> Unit
      }
    }

    val chapterId =
      chapterUrlRepository
        .findBySeriesId(context.series.id)
        .firstNotNullOfOrNull { mangaDexChapterRegex.find(it.url)?.groupValues?.get(1) }
    if (chapterId != null) {
      when (val manga = mangaDexApiClient.lookupMangaIdForChapter(chapterId)) {
        is MangaDexLookup.Found -> if (UserTracker.MANGADEX to manga.value !in context.rejected) return accept(context, UserTracker.MANGADEX, manga.value, SeriesTrackerId.Source.MANGADEX)
        is MangaDexLookup.Failed -> failure = manga.reason
        MangaDexLookup.NotFound -> Unit
      }
    }

    if (!titleSearch) return failure?.let { Lookup.Failed(it) } ?: Lookup.NotFound("no MangaDex link, folder UUID or MangaDex chapter")
    return when (val search = discoverByTitle(context, UserTracker.MANGADEX)) {
      is Lookup.NotFound -> failure?.let { Lookup.Failed(it) } ?: search
      else -> search
    }
  }

  private fun discoverFromMangaDexLinks(
    context: Context,
    tracker: String,
  ): Lookup? {
    val mangaDexId = (lookup(context, UserTracker.MANGADEX, localOnly = true) as? Lookup.Found)?.id ?: return null
    val info =
      context.mangaDexInfo
        ?: mangaDexApiClient.lookupMangaForTracking(mangaDexId).also { context.mangaDexInfo = it }
    val links =
      when (info) {
        is MangaDexLookup.Found -> info.value.links
        is MangaDexLookup.Failed -> return Lookup.Failed(info.reason)
        MangaDexLookup.NotFound -> return null
      }
    val raw =
      when (tracker) {
        UserTracker.ANILIST -> links["al"]
        UserTracker.MAL -> links["mal"]
        UserTracker.KITSU -> links["kt"]
        else -> null
      }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id =
      if (tracker == UserTracker.KITSU && raw.toIntOrNull() == null) {
        when (val slug = kitsuIdFromSlug(raw)) {
          is Lookup.Found -> slug.id
          is Lookup.Failed -> return slug
          is Lookup.NotFound -> return null
        }
      } else {
        raw.toIntOrNull()?.toString() ?: return null
      }
    if (tracker to id in context.rejected) return null
    return accept(context, tracker, id, SeriesTrackerId.Source.MANGADEX)
  }

  private fun discoverByTitle(
    context: Context,
    tracker: String,
  ): Lookup =
    when (val result = trackerTitleSearchProvider.search(tracker, context.meta, context.userMalClientId)) {
      is TitleSearchResult.Found -> searchResult(context, tracker, result.id)
      is TitleSearchResult.NotFound -> Lookup.NotFound("no link, no MangaDex link and no verified title match")
      is TitleSearchResult.Ambiguous -> Lookup.NotFound("the title search matched several entries (${result.ids.joinToString()})")
      is TitleSearchResult.Failed -> Lookup.Failed(result.reason, result.retryAfter)
    }

  private fun discoverMetron(context: Context): Lookup {
    val search = context.metronSearch ?: return Lookup.NotFound("no Metron series link or mapping")
    return when (val result = search(context.meta)) {
      is TitleSearchResult.Found -> searchResult(context, UserTracker.METRON, result.id)
      is TitleSearchResult.NotFound -> Lookup.NotFound("no link and no verified Metron series match")
      is TitleSearchResult.Ambiguous -> Lookup.NotFound("the Metron search matched several series (${result.ids.joinToString()})")
      is TitleSearchResult.Failed -> Lookup.Failed(result.reason, result.retryAfter)
    }
  }

  private fun searchResult(
    context: Context,
    tracker: String,
    id: String,
  ): Lookup =
    if (tracker to id in context.rejected) {
      Lookup.NotFound("the only verified match $id was rejected for this series")
    } else {
      accept(context, tracker, id, SeriesTrackerId.Source.SEARCH)
    }

  private fun kitsuIdFromSlug(slug: String): Lookup =
    try {
      trackerTitleSearchProvider.findKitsuIdBySlug(slug)?.let { Lookup.Found(it) } ?: Lookup.NotFound("unknown Kitsu slug $slug")
    } catch (e: Exception) {
      Lookup.Failed("Kitsu slug lookup for $slug failed: ${classifyTrackerFailure(UserTracker.KITSU, e).message}")
    }

  private fun report(
    seriesId: String,
    title: String,
    tracker: String,
    reason: String,
  ) {
    val key = "$seriesId:$tracker"
    if (isActive(reportedUntil, key)) return
    remember(reportedUntil, key, notFoundTtl)
    logger.info { "[scrobbler] No ${trackerDisplayNames[tracker]} ID found for '$title': $reason" }
  }

  private fun isActive(
    cache: ConcurrentHashMap<String, Instant>,
    key: String,
  ): Boolean = remainingFor(cache, key) != null

  private fun remainingFor(
    cache: ConcurrentHashMap<String, Instant>,
    key: String,
  ): Duration? {
    val until = cache[key] ?: return null
    val now = clock.instant()
    if (now.isBefore(until)) return Duration.between(now, until)
    cache.remove(key, until)
    return null
  }

  private fun remember(
    cache: ConcurrentHashMap<String, Instant>,
    key: String,
    ttl: Duration,
  ) {
    if (cache.size >= MAX_CACHE_ENTRIES) cache.entries.removeIf { !clock.instant().isBefore(it.value) }
    if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
    cache[key] = clock.instant().plus(ttl)
  }

  private fun storeLinks(seriesId: String) {
    try {
      val (meta, newLinks) =
        synchronized(linkLock) {
          val current = seriesMetadataRepository.findByIdOrNull(seriesId) ?: return
          if (current.linksLock) return
          val present = trackerIdResolver.fromLinks(current.links)
          val existingLabels = current.links.map { it.label.lowercase() }.toSet()
          val added =
            acceptedIds(seriesId)
              .filter { (tracker, id) -> id !in present[tracker].orEmpty() }
              .mapNotNull { (tracker, id) -> link(tracker, id) }
              .filter { it.label.lowercase() !in existingLabels }
          if (added.isEmpty()) return
          seriesMetadataRepository.update(current.copy(links = current.links + added))
          current to added
        }
      seriesRepository.findByIdOrNull(seriesId)?.let { eventPublisher.publishEvent(DomainEvent.SeriesUpdated(it)) }
      logger.info { "[scrobbler] Added ${newLinks.joinToString { it.label }} links for '${meta.title}'" }
    } catch (e: Exception) {
      logger.warn(e) { "[scrobbler] Could not store tracker links for series $seriesId" }
    }
  }

  private fun link(
    tracker: String,
    id: String,
  ): WebLink? =
    when (tracker) {
      UserTracker.ANILIST -> WebLink("AniList", URI("https://anilist.co/manga/$id"))
      UserTracker.MAL -> WebLink("MyAnimeList", URI("https://myanimelist.net/manga/$id"))
      UserTracker.KITSU -> WebLink("Kitsu", URI("https://kitsu.app/manga/$id"))
      UserTracker.MANGADEX -> WebLink("MangaDex", URI("https://mangadex.org/title/$id"))
      UserTracker.METRON -> WebLink("Metron", URI("https://metron.cloud/series/$id"))
      else -> null
    }
}
