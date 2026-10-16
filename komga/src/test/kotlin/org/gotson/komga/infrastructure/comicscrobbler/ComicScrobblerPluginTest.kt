package org.gotson.komga.infrastructure.comicscrobbler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.ComicScrobble
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ComicScrobbleRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesReadStateRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.scrobbler.TrackerIdDiscovery
import org.gotson.komga.infrastructure.scrobbler.TrackerIdFailure
import org.gotson.komga.infrastructure.scrobbler.TrackerIdResolution
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimitedException
import org.gotson.komga.infrastructure.scrobbler.TrackerStatusRecorder
import org.gotson.komga.infrastructure.scrobbler.TrackerUnavailableException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ComicScrobblerPluginTest {
  private val pluginRepository = mockk<PluginRepository>()
  private val pluginConfigRepository = mockk<PluginConfigRepository>()
  private val bookRepository = mockk<BookRepository>()
  private val bookMetadataRepository = mockk<BookMetadataRepository>()
  private val seriesRepository = mockk<SeriesRepository>()
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>()
  private val userTrackerRepository = mockk<UserTrackerRepository>()
  private val comicScrobbleRepository = mockk<ComicScrobbleRepository>(relaxed = true)
  private val trackerIdDiscovery = mockk<TrackerIdDiscovery>()
  private val trackerStatusRecorder = mockk<TrackerStatusRecorder>(relaxed = true)
  private val metronTrackerClient = mockk<MetronTrackerClient>(relaxed = true)

  private val scrobbler =
    ComicScrobblerPlugin(
      pluginRepository,
      pluginConfigRepository,
      bookRepository,
      bookMetadataRepository,
      seriesRepository,
      seriesMetadataRepository,
      mockk<SeriesReadStateRepository>(relaxed = true),
      mockk<ReadProgressRepository>(relaxed = true),
      userTrackerRepository,
      comicScrobbleRepository,
      trackerIdDiscovery,
      trackerStatusRecorder,
      metronTrackerClient,
    )

  private val series = makeSeries("Batman (2016)", libraryId = "comics").copy(id = "series")
  private val book = makeBook("Batman 012", seriesId = series.id, id = "book")
  private val readDate = LocalDateTime.of(2026, 5, 6, 20, 30)
  private val account = UserTracker("reader", UserTracker.METRON, mapOf(UserTracker.KEY_TOKEN to "metron-token"))

  @BeforeEach
  fun setup() {
    every { pluginRepository.findByIdOrNull("comic-scrobbler") } returns
      Plugin(
        id = "comic-scrobbler",
        name = "Comic Scrobbler",
        version = "3.0.0",
        author = null,
        description = null,
        enabled = true,
        pluginType = PluginType.SCROBBLER,
        entryPoint = "",
        sourceUrl = null,
        installedDate = LocalDateTime.now(),
        lastUpdated = LocalDateTime.now(),
        configSchema = null,
        dependencies = null,
      )
    every { pluginConfigRepository.findByPluginId("comic-scrobbler") } returns emptyList()
    every { userTrackerRepository.findByUserIdAndTracker("reader", UserTracker.METRON) } returns account
    every { userTrackerRepository.findByUserIdAndTracker("other", UserTracker.METRON) } returns null
    every { bookRepository.findByIdOrNull(book.id) } returns book
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns SeriesMetadata(title = "Batman (2016)", seriesId = series.id)
    every { bookMetadataRepository.findByIdOrNull(book.id) } returns BookMetadata(title = "Batman 012", number = "012", numberSort = 12F, bookId = book.id)
    every { comicScrobbleRepository.findIssueNumbers("reader", series.id) } returns setOf("11")
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returns TrackerIdResolution(mapOf(UserTracker.METRON to "500"))
    every { metronTrackerClient.issueIds("Bearer metron-token", "500", "12") } returns listOf(9001)
  }

  @AfterEach
  fun tearDown() {
    scrobbler.shutdown()
  }

  @Test
  fun `given a read issue of a linked user when finishing then the issue of the Metron series is scrobbled with the read date and recorded`() {
    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "reader", 1, true, readDate)))

    verify(timeout = 5000) { metronTrackerClient.scrobble("Bearer metron-token", 9001, readDate.atZone(ZoneId.systemDefault()).toOffsetDateTime()) }
    verify(timeout = 5000) { comicScrobbleRepository.insert(ComicScrobble("reader", series.id, "12", 9001, book.id, readDate)) }
  }

  @Test
  fun `given a Metron issue link on the book when finishing then the linked issue is used without resolving the series`() {
    every { bookMetadataRepository.findByIdOrNull(book.id) } returns
      BookMetadata(title = "Batman 012", number = "12", numberSort = 12F, bookId = book.id, links = listOf(WebLink("Metron", URI("https://metron.cloud/issue/4242/"))))

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "reader", 1, true, readDate)))

    verify(timeout = 5000) { metronTrackerClient.scrobble(any(), 4242, any()) }
    verify(exactly = 0) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given an already scrobbled issue or a user without Metron account when finishing then nothing is sent`() {
    every { comicScrobbleRepository.findIssueNumbers("reader", series.id) } returns setOf("12")

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "reader", 1, true, readDate)))
    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "other", 1, true, readDate)))

    verify(timeout = 5000) { comicScrobbleRepository.findIssueNumbers("reader", series.id) }
    Thread.sleep(500)
    verify(exactly = 0) { metronTrackerClient.scrobble(any(), any(), any()) }
  }

  @Test
  fun `given a rate limited scrobble when finishing then it is planned again after the Retry-After and recorded`() {
    every { metronTrackerClient.scrobble(any(), 9001, any()) } throws TrackerRateLimitedException("metron rate limit reached", Duration.ofMillis(200)) andThen Unit

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "reader", 1, true, readDate)))

    verify(timeout = 5000) { comicScrobbleRepository.insert(ComicScrobble("reader", series.id, "12", 9001, book.id, readDate)) }
    verify(exactly = 2) { metronTrackerClient.scrobble(any(), 9001, any()) }
  }

  @Test
  fun `given a Metron series search stopped by the daily limit when finishing then it is planned again at the reset and scrobbled`() {
    val untilReset = Duration.ofMillis(300)
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returnsMany
      listOf(
        TrackerIdResolution(emptyMap(), mapOf(UserTracker.METRON to TrackerIdFailure("metron daily request limit of 5000 reached", untilReset))),
        TrackerIdResolution(mapOf(UserTracker.METRON to "500")),
      )

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "reader", 1, true, readDate)))

    verify(timeout = 5000) { comicScrobbleRepository.insert(ComicScrobble("reader", series.id, "12", 9001, book.id, readDate)) }
    verify(exactly = 2) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }
    verify(exactly = 1) { trackerStatusRecorder.failure(account, match { it is TrackerRateLimitedException && it.retryAfter == untilReset }) }
  }

  @Test
  fun `given a failed Metron series search without wait time when finishing then the scrobble is postponed with the backoff`() {
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returns
      TrackerIdResolution(emptyMap(), mapOf(UserTracker.METRON to TrackerIdFailure("metron is unavailable")))

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "reader", 1, true, readDate)))

    verify(timeout = 5000) { trackerStatusRecorder.failure(account, match { it is TrackerUnavailableException }) }
    Thread.sleep(500)
    verify(exactly = 1) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }
    verify(exactly = 0) { metronTrackerClient.scrobble(any(), any(), any()) }
  }

  @Test
  fun `given an issue Metron does not know when finishing it again then Metron is not asked again for a day`() {
    every { metronTrackerClient.issueIds("Bearer metron-token", "500", "12") } returns emptyList()

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "reader", 1, true, readDate)))
    verify(timeout = 5000) { metronTrackerClient.issueIds("Bearer metron-token", "500", "12") }
    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, "reader", 1, true, readDate)))
    Thread.sleep(500)

    verify(exactly = 1) { metronTrackerClient.issueIds(any(), any(), any()) }
    verify(exactly = 2) { comicScrobbleRepository.findIssueNumbers("reader", series.id) }
    verify(exactly = 0) { metronTrackerClient.scrobble(any(), any(), any()) }
  }
}
