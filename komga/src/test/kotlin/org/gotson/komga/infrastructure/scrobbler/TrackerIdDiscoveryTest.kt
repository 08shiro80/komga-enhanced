package org.gotson.komga.infrastructure.scrobbler

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.AlternateTitle
import org.gotson.komga.domain.model.ChapterUrl
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.SeriesTrackerId
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.SeriesTrackerIdRepository
import org.gotson.komga.infrastructure.download.MangaDexApiClient
import org.gotson.komga.infrastructure.download.MangaDexLookup
import org.gotson.komga.infrastructure.download.MangaDexTrackingInfo
import org.gotson.komga.infrastructure.metadata.tracker.TitleSearchResult
import org.gotson.komga.infrastructure.metadata.tracker.TrackerTitleSearchProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

private const val MANGADEX_ID = "ec8f26f4-bc9f-43b6-93ec-60a05638fca0"

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerIdDiscoveryTest {
  private val seriesTrackerIdRepository = mockk<SeriesTrackerIdRepository>(relaxed = true)
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>(relaxed = true)
  private val seriesRepository = mockk<SeriesRepository>()
  private val chapterUrlRepository = mockk<ChapterUrlRepository>()
  private val mangaDexApiClient = mockk<MangaDexApiClient>()
  private val titleSearch = mockk<TrackerTitleSearchProvider>()
  private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
  private var now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } answers { now } }

  private val discovery =
    TrackerIdDiscovery(
      TrackerIdResolver(ObjectMapper()),
      seriesTrackerIdRepository,
      seriesMetadataRepository,
      seriesRepository,
      chapterUrlRepository,
      mangaDexApiClient,
      titleSearch,
      eventPublisher,
      clock,
    )

  private val series = makeSeries(MANGADEX_ID).copy(id = "0PRJ3931EME80")
  private val mangaDexLink = WebLink("MangaDex", URI("https://mangadex.org/title/$MANGADEX_ID"))
  private val meta =
    SeriesMetadata(
      title = "Different World Foundation",
      seriesId = series.id,
      links = listOf(mangaDexLink),
      alternateTitles = listOf(AlternateTitle("JA-RO", "Isekai Kenkokuki")),
    )
  private val logAppender = ListAppender<ILoggingEvent>()
  private val discoveryLogger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("org.gotson.komga.infrastructure.scrobbler")
  private var previousLevel: Level? = null
  private val storedIds = mutableListOf<SeriesTrackerId>()

  @BeforeEach
  fun setup() {
    previousLevel = discoveryLogger.level
    discoveryLogger.level = Level.INFO
    logAppender.start()
    discoveryLogger.addAppender(logAppender)
    every { seriesTrackerIdRepository.findBySeriesId(series.id) } answers { storedIds.toList() }
    every { seriesTrackerIdRepository.accept(series.id, any(), any(), any()) } answers {
      val tracker = secondArg<String>()
      val id = thirdArg<String>()
      storedIds.removeIf { it.tracker == tracker && (it.status == SeriesTrackerId.Status.ACCEPTED || it.trackerId == id) }
      storedIds += SeriesTrackerId(series.id, tracker, id, SeriesTrackerId.Status.ACCEPTED, arg(3))
    }
    every { seriesTrackerIdRepository.reject(series.id, any(), any()) } answers {
      val tracker = secondArg<String>()
      val id = thirdArg<String>()
      storedIds.removeIf { it.tracker == tracker && it.trackerId == id }
      storedIds += SeriesTrackerId(series.id, tracker, id, SeriesTrackerId.Status.REJECTED, SeriesTrackerId.Source.MANUAL)
    }
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns meta
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { chapterUrlRepository.findBySeriesId(series.id) } returns emptyList()
    every { titleSearch.invalidate(any()) } returns Unit
  }

  @AfterEach
  fun tearDown() {
    discoveryLogger.detachAppender(logAppender)
    discoveryLogger.level = previousLevel
  }

  private fun resolve(
    trackers: List<String>,
    config: Map<String, String?> = emptyMap(),
    metadata: SeriesMetadata = meta,
  ) = discovery.resolve(series, metadata, trackers, config).ids

  private fun mangaDexLinks(vararg links: Pair<String, String>) {
    every { mangaDexApiClient.lookupMangaForTracking(MANGADEX_ID) } returns MangaDexLookup.Found(MangaDexTrackingInfo(links.toMap(), "safe"))
  }

  @Test
  fun `given only a MangaDex link when resolving then the IDs come from the MangaDex entry and are stored with links`() {
    mangaDexLinks("al" to "100114", "kt" to "40130", "mal" to "109696")
    val updated = slot<SeriesMetadata>()
    every { seriesMetadataRepository.update(capture(updated)) } returns Unit

    val ids = resolve(listOf(UserTracker.ANILIST, UserTracker.MAL, UserTracker.KITSU))

    assertThat(ids).isEqualTo(mapOf(UserTracker.ANILIST to "100114", UserTracker.MAL to "109696", UserTracker.KITSU to "40130"))
    verify { seriesTrackerIdRepository.accept(series.id, UserTracker.MANGADEX, MANGADEX_ID, SeriesTrackerId.Source.LINK) }
    verify { seriesTrackerIdRepository.accept(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Source.MANGADEX) }
    verify(exactly = 0) { titleSearch.search(any(), any(), any()) }
    assertThat(updated.captured.links).containsExactly(
      mangaDexLink,
      WebLink("AniList", URI("https://anilist.co/manga/100114")),
      WebLink("MyAnimeList", URI("https://myanimelist.net/manga/109696")),
      WebLink("Kitsu", URI("https://kitsu.app/manga/40130")),
    )
    verify { eventPublisher.publishEvent(DomainEvent.SeriesUpdated(series)) }
  }

  @Test
  fun `given mappings and stored IDs when resolving then they take precedence over links and discovery`() {
    every { seriesTrackerIdRepository.findBySeriesId(series.id) } returns
      listOf(SeriesTrackerId(series.id, UserTracker.MAL, "5", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.SEARCH))
    val config = mapOf("mappings" to """{"${series.id}":{"anilist_id":30002}}""")

    val ids = resolve(listOf(UserTracker.ANILIST, UserTracker.MAL), config)

    assertThat(ids).isEqualTo(mapOf(UserTracker.ANILIST to "30002", UserTracker.MAL to "5"))
    verify { seriesTrackerIdRepository.accept(series.id, UserTracker.ANILIST, "30002", SeriesTrackerId.Source.MAPPING) }
    verify(exactly = 0) { mangaDexApiClient.lookupMangaForTracking(any()) }
  }

  @Test
  fun `given a Kitsu slug and a rejected AniList ID when resolving then the slug is resolved and the rejected ID is not used`() {
    every { seriesTrackerIdRepository.findBySeriesId(series.id) } returns
      listOf(SeriesTrackerId(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Status.REJECTED, SeriesTrackerId.Source.MANUAL))
    mangaDexLinks("al" to "100114", "kt" to "jujutsu-kaisen")
    every { titleSearch.findKitsuIdBySlug("jujutsu-kaisen") } returns "40815"
    every { titleSearch.search(UserTracker.ANILIST, meta, null) } returns TitleSearchResult.NotFound

    val ids = resolve(listOf(UserTracker.ANILIST, UserTracker.KITSU))

    assertThat(ids).isEqualTo(mapOf(UserTracker.KITSU to "40815"))
    verify(exactly = 0) { seriesTrackerIdRepository.accept(series.id, UserTracker.ANILIST, "100114", any()) }
  }

  @Test
  fun `given discovery is disabled when resolving then only mappings and links are used`() {
    val ids = resolve(listOf(UserTracker.ANILIST), mapOf("discover_ids" to "false"))

    assertThat(ids).isEmpty()
    verify(exactly = 0) { mangaDexApiClient.lookupMangaForTracking(any()) }
    verify(exactly = 0) { titleSearch.search(any(), any(), any()) }
  }

  @Test
  fun `given no MangaDex link when resolving then the folder UUID and the downloaded chapters are used before the title search`() {
    val withoutLink = SeriesMetadata(title = "Series", seriesId = series.id)
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns withoutLink
    every { mangaDexApiClient.lookupMangaForTracking(MANGADEX_ID) } returns MangaDexLookup.NotFound
    every { chapterUrlRepository.findBySeriesId(series.id) } returns
      listOf(ChapterUrl("c", series.id, "https://mangadex.org/chapter/11111111-2222-3333-4444-555555555555", 1.0, downloadedAt = LocalDateTime.now()))
    every { mangaDexApiClient.lookupMangaIdForChapter("11111111-2222-3333-4444-555555555555") } returns MangaDexLookup.Found("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    every { mangaDexApiClient.lookupMangaForTracking("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") } returns MangaDexLookup.Found(MangaDexTrackingInfo(mapOf("al" to "7"), "safe"))

    val ids = resolve(listOf(UserTracker.ANILIST, UserTracker.MANGADEX), metadata = withoutLink)

    assertThat(ids).isEqualTo(mapOf(UserTracker.ANILIST to "7", UserTracker.MANGADEX to "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
    verify(exactly = 0) { titleSearch.search(any(), any(), any()) }
  }

  @Test
  fun `given no ID anywhere when resolving twice then the title search runs once and the reason is logged once per day`() {
    mangaDexLinks()
    every { titleSearch.search(UserTracker.KITSU, meta, null) } returns TitleSearchResult.NotFound

    resolve(listOf(UserTracker.KITSU))
    resolve(listOf(UserTracker.KITSU))

    verify(exactly = 1) { titleSearch.search(UserTracker.KITSU, meta, null) }
    assertThat(logAppender.list.map { it.formattedMessage }.filter { it.startsWith("[scrobbler] No Kitsu ID found for 'Different World Foundation'") }).hasSize(1)

    now = now.plus(Duration.ofHours(25))
    resolve(listOf(UserTracker.KITSU))
    verify(exactly = 2) { titleSearch.search(UserTracker.KITSU, meta, null) }
  }

  @Test
  fun `given a failing lookup when resolving then nothing is cached as not found and no not-found reason is logged`() {
    every { mangaDexApiClient.lookupMangaForTracking(MANGADEX_ID) } returns MangaDexLookup.Failed("MangaDex returned HTTP 503")

    val first = discovery.resolve(series, meta, listOf(UserTracker.ANILIST), emptyMap())
    now = now.plus(Duration.ofMinutes(11))
    resolve(listOf(UserTracker.ANILIST))

    assertThat(first.ids).isEmpty()
    assertThat(first.failures).isEqualTo(mapOf(UserTracker.ANILIST to TrackerIdFailure("MangaDex returned HTTP 503")))
    verify(exactly = 2) { mangaDexApiClient.lookupMangaForTracking(MANGADEX_ID) }
    assertThat(logAppender.list.map { it.formattedMessage }.filter { it.startsWith("[scrobbler] No AniList ID found") }).isEmpty()
  }

  @Test
  fun `given a Metron search stopped by the daily limit when resolving then the failure carries the wait until the reset and no search runs before it`() {
    val untilReset = Duration.ofHours(5)
    var searches = 0
    val search: (SeriesMetadata) -> TitleSearchResult = {
      searches++
      TitleSearchResult.Failed("Metron daily request limit of 5000 reached", untilReset)
    }

    val first = discovery.resolve(series, meta, listOf(UserTracker.METRON), emptyMap(), metronSearch = search)
    now = now.plus(Duration.ofHours(1))
    val second = discovery.resolve(series, meta, listOf(UserTracker.METRON), emptyMap(), metronSearch = search)

    assertThat(first.ids).isEmpty()
    assertThat(first.failures[UserTracker.METRON]?.retryAfter).isEqualTo(untilReset)
    assertThat(second.failures[UserTracker.METRON]?.retryAfter).isEqualTo(Duration.ofHours(4))
    assertThat(searches).isEqualTo(1)

    now = now.plus(Duration.ofHours(4))
    discovery.resolve(series, meta, listOf(UserTracker.METRON), emptyMap(), metronSearch = search)
    assertThat(searches).isEqualTo(2)
  }

  @Test
  fun `given a locked links field when resolving then the IDs are used but no link is written`() {
    val locked = SeriesMetadata(title = meta.title, seriesId = series.id, links = listOf(mangaDexLink), linksLock = true)
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns locked
    mangaDexLinks("al" to "100114")

    assertThat(resolve(listOf(UserTracker.ANILIST), metadata = locked)).isEqualTo(mapOf(UserTracker.ANILIST to "100114"))
    verify(exactly = 0) { seriesMetadataRepository.update(any()) }
  }

  @Test
  fun `given only an AniList account and no local MangaDex source when resolving then MangaDex is not asked and the AniList title search runs`() {
    val plain = makeSeries("Plain Title").copy(id = series.id)
    val withoutLink = SeriesMetadata(title = "Plain Title", seriesId = series.id)
    every { titleSearch.search(UserTracker.ANILIST, withoutLink, null) } returns TitleSearchResult.Found("7")

    val ids = discovery.resolve(plain, withoutLink, listOf(UserTracker.ANILIST), emptyMap()).ids

    assertThat(ids).isEqualTo(mapOf(UserTracker.ANILIST to "7"))
    verify(exactly = 0) { mangaDexApiClient.lookupMangaForTracking(any()) }
    verify(exactly = 0) { mangaDexApiClient.lookupMangaIdForChapter(any()) }
    verify(exactly = 0) { titleSearch.search(UserTracker.MANGADEX, any(), any()) }
    verify { seriesTrackerIdRepository.accept(series.id, UserTracker.ANILIST, "7", SeriesTrackerId.Source.SEARCH) }
  }

  @Test
  fun `given MangaDex was not found as intermediate step when a MangaDex account resolves the series then the MangaDex title search still runs`() {
    val plain = makeSeries("Plain Title").copy(id = series.id)
    val withoutLink = SeriesMetadata(title = "Plain Title", seriesId = series.id)
    every { titleSearch.search(UserTracker.ANILIST, withoutLink, null) } returns TitleSearchResult.NotFound
    every { titleSearch.search(UserTracker.MANGADEX, withoutLink, null) } returns TitleSearchResult.Found(MANGADEX_ID)

    val anilist = discovery.resolve(plain, withoutLink, listOf(UserTracker.ANILIST), emptyMap()).ids
    verify(exactly = 0) { titleSearch.search(UserTracker.MANGADEX, any(), any()) }
    val mangaDex = discovery.resolve(plain, withoutLink, listOf(UserTracker.MANGADEX), emptyMap()).ids

    assertThat(anilist).isEmpty()
    assertThat(mangaDex).isEqualTo(mapOf(UserTracker.MANGADEX to MANGADEX_ID))
    verify(exactly = 1) { titleSearch.search(UserTracker.MANGADEX, withoutLink, null) }
    verify { seriesTrackerIdRepository.accept(series.id, UserTracker.MANGADEX, MANGADEX_ID, SeriesTrackerId.Source.SEARCH) }
  }

  @Test
  fun `given a link edit when the old tracker link is replaced then it and the accepted ID are rejected and the new link is accepted as manual`() {
    every { seriesTrackerIdRepository.findBySeriesId(series.id) } returns
      listOf(SeriesTrackerId(series.id, UserTracker.ANILIST, "999", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.SEARCH))

    discovery.linksEdited(
      series.id,
      listOf(WebLink("AniList", URI("https://anilist.co/manga/100107"))),
      listOf(WebLink("AniList", URI("https://anilist.co/manga/100114"))),
    )

    verify { seriesTrackerIdRepository.reject(series.id, UserTracker.ANILIST, "100107") }
    verify { seriesTrackerIdRepository.reject(series.id, UserTracker.ANILIST, "999") }
    verify { seriesTrackerIdRepository.accept(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Source.MANUAL) }
    verify { titleSearch.invalidate(series.id) }
  }

  @Test
  fun `given a link edit when tracker links are removed then the accepted IDs of these trackers are rejected whatever their source`() {
    every { seriesTrackerIdRepository.findBySeriesId(series.id) } returns
      listOf(
        SeriesTrackerId(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.SEARCH),
        SeriesTrackerId(series.id, UserTracker.MAL, "109696", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.MANGADEX),
        SeriesTrackerId(series.id, UserTracker.KITSU, "40815", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.LINK),
        SeriesTrackerId(series.id, UserTracker.MANGADEX, MANGADEX_ID, SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.LINK),
      )

    discovery.linksEdited(
      series.id,
      listOf(
        mangaDexLink,
        WebLink("AniList", URI("https://anilist.co/manga/100107")),
        WebLink("MyAnimeList", URI("https://myanimelist.net/manga/109696")),
        WebLink("Kitsu", URI("https://kitsu.app/manga/jujutsu-kaisen")),
      ),
      listOf(mangaDexLink),
    )

    verify { seriesTrackerIdRepository.reject(series.id, UserTracker.ANILIST, "100107") }
    verify { seriesTrackerIdRepository.reject(series.id, UserTracker.ANILIST, "100114") }
    verify(exactly = 1) { seriesTrackerIdRepository.reject(series.id, UserTracker.MAL, "109696") }
    verify { seriesTrackerIdRepository.reject(series.id, UserTracker.KITSU, "jujutsu-kaisen") }
    verify { seriesTrackerIdRepository.reject(series.id, UserTracker.KITSU, "40815") }
    verify(exactly = 0) { seriesTrackerIdRepository.reject(series.id, UserTracker.MANGADEX, any()) }
    verify(exactly = 0) { seriesTrackerIdRepository.accept(any(), any(), any(), any()) }
    verify { titleSearch.invalidate(series.id) }
  }

  @Test
  fun `given a link edit when a Kitsu ID link is replaced by a slug then the accepted ID is rejected and nothing is accepted yet`() {
    every { seriesTrackerIdRepository.findBySeriesId(series.id) } returns
      listOf(SeriesTrackerId(series.id, UserTracker.KITSU, "40815", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.LINK))

    discovery.linksEdited(
      series.id,
      listOf(WebLink("Kitsu", URI("https://kitsu.app/manga/40815"))),
      listOf(WebLink("Kitsu", URI("https://kitsu.app/manga/berserk"))),
    )

    verify(exactly = 1) { seriesTrackerIdRepository.reject(series.id, UserTracker.KITSU, "40815") }
    verify(exactly = 0) { seriesTrackerIdRepository.accept(any(), any(), any(), any()) }
    verify(exactly = 0) { titleSearch.findKitsuIdBySlug(any()) }
  }

  @Test
  fun `given a rejected ID when a link resolves to it then the link wins, without a link the ID stays blocked for MangaDex data and the title search`() {
    discovery.linksEdited(
      series.id,
      listOf(WebLink("Kitsu", URI("https://kitsu.app/manga/40815"))),
      listOf(WebLink("Kitsu", URI("https://kitsu.app/manga/berserk"))),
    )
    verify(exactly = 1) { seriesTrackerIdRepository.reject(series.id, UserTracker.KITSU, "40815") }

    every { seriesTrackerIdRepository.findBySeriesId(series.id) } returns
      listOf(
        SeriesTrackerId(series.id, UserTracker.KITSU, "40815", SeriesTrackerId.Status.REJECTED, SeriesTrackerId.Source.MANUAL),
        SeriesTrackerId(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Status.REJECTED, SeriesTrackerId.Source.MANUAL),
      )
    every { titleSearch.findKitsuIdBySlug("berserk") } returns "40815"
    mangaDexLinks("al" to "100114")
    every { titleSearch.search(UserTracker.ANILIST, any(), null) } returns TitleSearchResult.Found("100114")
    val linked = SeriesMetadata(title = meta.title, seriesId = series.id, links = listOf(mangaDexLink, WebLink("Kitsu", URI("https://kitsu.app/manga/berserk"))))

    val ids = resolve(listOf(UserTracker.KITSU, UserTracker.ANILIST), metadata = linked)

    assertThat(ids).isEqualTo(mapOf(UserTracker.KITSU to "40815"))
    verify(exactly = 1) { seriesTrackerIdRepository.accept(series.id, UserTracker.KITSU, "40815", SeriesTrackerId.Source.LINK) }
    verify(exactly = 1) { titleSearch.search(UserTracker.ANILIST, any(), null) }
    verify(exactly = 0) { seriesTrackerIdRepository.accept(series.id, UserTracker.ANILIST, any(), any()) }
  }

  @Test
  fun `given a link edit rejecting an ID during a running resolve when the links are stored then the rejected ID is not written back`() {
    storedIds += SeriesTrackerId(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.SEARCH)
    mangaDexLinks()
    every { titleSearch.search(UserTracker.KITSU, meta, null) } answers {
      discovery.linksEdited(series.id, listOf(WebLink("AniList", URI("https://anilist.co/manga/100114"))), emptyList())
      TitleSearchResult.NotFound
    }

    val ids = resolve(listOf(UserTracker.ANILIST, UserTracker.KITSU))

    assertThat(ids).isEqualTo(mapOf(UserTracker.ANILIST to "100114"))
    verify { seriesTrackerIdRepository.reject(series.id, UserTracker.ANILIST, "100114") }
    verify(exactly = 0) { seriesMetadataRepository.update(any()) }
  }

  @Test
  fun `given a links edit under the link lock when a resolve stores links in parallel then the rejected link is not written back`() {
    val aniListLink = WebLink("AniList", URI("https://anilist.co/manga/100114"))
    var current = meta.copy(links = listOf(mangaDexLink, aniListLink))
    every { seriesMetadataRepository.findByIdOrNull(series.id) } answers { current }
    every { seriesMetadataRepository.update(any()) } answers { current = firstArg() }
    storedIds += SeriesTrackerId(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.SEARCH)
    val resolver = Thread { discovery.resolve(series, current, listOf(UserTracker.ANILIST), emptyMap()) }

    discovery.withLinkLock {
      seriesMetadataRepository.update(current.copy(links = listOf(mangaDexLink)))
      resolver.start()
      val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
      while (!resolver.waitsForLinkLock() && System.nanoTime() < deadline) Thread.sleep(10)
      assertThat(resolver.waitsForLinkLock()).isTrue()
      discovery.linksEdited(series.id, listOf(mangaDexLink, aniListLink), listOf(mangaDexLink))
    }
    resolver.join(15_000)

    assertThat(current.links).containsExactly(mangaDexLink)
    verify(exactly = 1) { seriesMetadataRepository.update(any()) }
    verify { seriesTrackerIdRepository.reject(series.id, UserTracker.ANILIST, "100114") }
  }

  private fun Thread.waitsForLinkLock(): Boolean = state == Thread.State.BLOCKED && stackTrace.any { it.methodName == "storeLinks" }
}
