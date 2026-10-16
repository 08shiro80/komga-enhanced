package org.gotson.komga.infrastructure.scrobbler

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SeriesReadState
import org.gotson.komga.domain.model.SeriesTrackerId
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesReadStateRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.SeriesTrackerIdRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.domain.service.BookLifecycle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MangaSyncPullerPluginTest {
  private val pluginRepository = mockk<PluginRepository>()
  private val pluginConfigRepository = mockk<PluginConfigRepository>()
  private val userTrackerRepository = mockk<UserTrackerRepository>()
  private val komgaUserRepository = mockk<KomgaUserRepository>()
  private val seriesRepository = mockk<SeriesRepository>()
  private val seriesTrackerIdRepository = mockk<SeriesTrackerIdRepository>()
  private val seriesReadStateRepository = mockk<SeriesReadStateRepository>()
  private val bookRepository = mockk<BookRepository>()
  private val bookMetadataRepository = mockk<BookMetadataRepository>()
  private val readProgressRepository = mockk<ReadProgressRepository>()
  private val bookLifecycle = mockk<BookLifecycle>(relaxed = true)
  private val trackerTokenProvider = mockk<TrackerTokenProvider>()
  private val trackerStatusRecorder = mockk<TrackerStatusRecorder>(relaxed = true)
  private val trackerConnectionChecker = mockk<TrackerConnectionChecker>()
  private val syncStateRecorder = mockk<SyncStateRecorder>(relaxed = true)
  private val mangaScrobblerPlugin = mockk<MangaScrobblerPlugin>(relaxed = true)
  private val anilistClient =
    mockk<MangaTrackerClient> {
      every { tracker } returns UserTracker.ANILIST
    }
  private var now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } answers { now } }

  private val puller =
    MangaSyncPullerPlugin(
      pluginRepository,
      pluginConfigRepository,
      userTrackerRepository,
      komgaUserRepository,
      seriesRepository,
      seriesTrackerIdRepository,
      seriesReadStateRepository,
      bookRepository,
      bookMetadataRepository,
      readProgressRepository,
      bookLifecycle,
      TrackerIdResolver(ObjectMapper()),
      trackerTokenProvider,
      trackerStatusRecorder,
      trackerConnectionChecker,
      syncStateRecorder,
      mangaScrobblerPlugin,
      listOf(anilistClient),
      clock,
    )

  private val user = KomgaUser("reader@example.org", "", id = "reader")
  private val series = makeSeries("Series", libraryId = "library").copy(id = "series")
  private val books = (1..4).map { makeBook("c00$it", seriesId = series.id, id = "book$it") }
  private val active = UserTracker(user.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t1", UserTracker.KEY_REMOTE_USER_ID to "1"))
  private val paused = UserTracker("paused", UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t2", UserTracker.KEY_ENABLED to "false"))
  private val logAppender = ListAppender<ILoggingEvent>()
  private val scrobblerLogger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("org.gotson.komga.infrastructure.scrobbler")
  private var previousLevel: Level? = null

  @BeforeEach
  fun setup() {
    previousLevel = scrobblerLogger.level
    scrobblerLogger.level = Level.DEBUG
    logAppender.start()
    scrobblerLogger.addAppender(logAppender)
    every { pluginRepository.findByIdOrNull("manga-scrobbler") } returns
      Plugin(
        id = "manga-scrobbler",
        name = "Manga Scrobbler",
        version = "4.0.0",
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
    every { pluginConfigRepository.findByPluginId("manga-scrobbler") } returns emptyList()
    every { userTrackerRepository.findByTracker(UserTracker.ANILIST) } returns listOf(active, paused)
    every { komgaUserRepository.findByIdOrNull(user.id) } returns user
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { seriesTrackerIdRepository.findByTracker(UserTracker.ANILIST) } returns
      listOf(SeriesTrackerId(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.LINK))
    every { seriesTrackerIdRepository.findLinkedSeriesUrls(any()) } returns emptyList()
    every { bookRepository.findAllBySeriesId(series.id) } returns books
    every { bookMetadataRepository.findAllByIds(any()) } returns books.mapIndexed { i, b -> BookMetadata(title = b.name, number = "${i + 1}", numberSort = (i + 1).toFloat(), bookId = b.id) }
    every { readProgressRepository.findAllByBookIdsAndUserId(any(), user.id) } returns listOf(ReadProgress("book1", user.id, 1, true))
    every { trackerTokenProvider.withToken<Any?>(any(), any()) } answers { secondArg<(String) -> Any?>().invoke("t1") }
  }

  @AfterEach
  fun tearDown() {
    puller.shutdown()
    scrobblerLogger.detachAppender(logAppender)
    scrobblerLogger.level = previousLevel
  }

  private fun pullSummaries() = logAppender.list.filter { it.formattedMessage.contains("pull for user ${user.id}:") }

  @Test
  fun `given a remote progress ahead of the local one when pulling then the missing books are marked read through the lifecycle and only active accounts are pulled`() {
    every { seriesReadStateRepository.findByUserIdAndSeriesId(user.id, series.id) } returns
      SeriesReadState(series.id, "library", false, 1F, LocalDateTime.now(), LocalDateTime.now())
    every { anilistClient.library(active, "t1") } returns listOf(RemoteEntry("100114", 3, RemoteStatus.CURRENT, null, null))

    puller.pullAllTrackers()

    verify(timeout = 15_000) { bookLifecycle.markReadProgressCompleted("book3", user) }
    verify { syncStateRecorder.record(user.id, series.id, UserTracker.ANILIST, 3, "CURRENT", true) }
    verify(exactly = 1) { bookLifecycle.markReadProgressCompleted("book2", user) }
    verify(exactly = 1) { bookLifecycle.markReadProgressCompleted("book3", user) }
    verify(exactly = 0) { bookLifecycle.markReadProgressCompleted("book1", user) }
    verify(exactly = 0) { bookLifecycle.markReadProgressCompleted("book4", user) }
    verify(exactly = 0) { anilistClient.library(paused, any()) }
  }

  @Test
  fun `given a remote progress behind the local one when pulling then the series is sent again and nothing is marked unread`() {
    every { seriesReadStateRepository.findByUserIdAndSeriesId(user.id, series.id) } returns
      SeriesReadState(series.id, "library", false, 4F, LocalDateTime.now(), LocalDateTime.now())
    every { anilistClient.library(active, "t1") } returns listOf(RemoteEntry("100114", 2, RemoteStatus.CURRENT, null, null))

    puller.pullAllTrackers()

    verify(timeout = 15_000) { mangaScrobblerPlugin.schedule(user.id, series.id, listOf(UserTracker.ANILIST)) }
    verifyOrder {
      syncStateRecorder.recordRemote(user.id, series.id, UserTracker.ANILIST, 2, "CURRENT")
      mangaScrobblerPlugin.schedule(user.id, series.id, listOf(UserTracker.ANILIST))
    }
    verify(exactly = 0) { syncStateRecorder.record(any(), any(), any(), any(), any(), any()) }
    verify(exactly = 0) { bookLifecycle.markReadProgressCompleted(any(), any()) }
    verify(timeout = 15_000) { trackerStatusRecorder.success(active) }
    Thread.sleep(500)
    val summaries = pullSummaries()
    assertThat(summaries).hasSize(1)
    assertThat(summaries.single().level).isEqualTo(Level.INFO)
    assertThat(summaries.single().formattedMessage).contains("0 series updated", "1 series sent back")
  }

  @Test
  fun `given a remote progress that stays behind the local one when pulling again within a day then it is sent back only once per day`() {
    every { seriesReadStateRepository.findByUserIdAndSeriesId(user.id, series.id) } returns
      SeriesReadState(series.id, "library", false, 4F, LocalDateTime.now(), LocalDateTime.now())
    every { anilistClient.library(active, "t1") } returns listOf(RemoteEntry("100114", 2, RemoteStatus.CURRENT, null, null))

    fun pullAndWait(pulls: Int) {
      puller.pullAllTrackers()
      val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
      while (pullSummaries().size < pulls && System.nanoTime() < deadline) Thread.sleep(50)
      assertThat(pullSummaries()).hasSize(pulls)
      Thread.sleep(200)
    }

    pullAndWait(1)
    pullAndWait(2)

    verify(exactly = 1) { mangaScrobblerPlugin.schedule(user.id, series.id, listOf(UserTracker.ANILIST)) }
    verify(exactly = 1) { syncStateRecorder.recordRemote(user.id, series.id, UserTracker.ANILIST, 2, "CURRENT") }
    assertThat(pullSummaries()[1].level).isEqualTo(Level.DEBUG)
    assertThat(pullSummaries()[1].formattedMessage).contains("0 series sent back")

    now = now.plus(Duration.ofHours(25))
    pullAndWait(3)

    verify(exactly = 2) { mangaScrobblerPlugin.schedule(user.id, series.id, listOf(UserTracker.ANILIST)) }
  }

  @Test
  fun `given a remote progress equal to the local one when pulling then nothing is sent back and the summary is only logged at debug level`() {
    every { seriesReadStateRepository.findByUserIdAndSeriesId(user.id, series.id) } returns
      SeriesReadState(series.id, "library", false, 2F, LocalDateTime.now(), LocalDateTime.now())
    every { anilistClient.library(active, "t1") } returns listOf(RemoteEntry("100114", 2, RemoteStatus.CURRENT, null, null))

    puller.pullAllTrackers()

    verify(timeout = 15_000) { syncStateRecorder.record(user.id, series.id, UserTracker.ANILIST, 2, "CURRENT", true) }
    Thread.sleep(500)
    verify(exactly = 0) { mangaScrobblerPlugin.schedule(any(), any(), any(), any()) }
    verify(exactly = 0) { syncStateRecorder.recordRemote(any(), any(), any(), any(), any()) }
    assertThat(pullSummaries().map { it.level }).containsExactly(Level.DEBUG)
  }

  @Test
  fun `given an account whose identity must be checked when pulling then the confirmed account is pulled`() {
    val marked = active.copy(settings = active.settings + (UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED))
    val confirmed = active.copy(settings = active.settings + (UserTracker.KEY_REMOTE_USER_ID to "2"))
    every { userTrackerRepository.findByTracker(UserTracker.ANILIST) } returns listOf(marked)
    every { trackerConnectionChecker.confirmIdentity(marked) } returns confirmed
    every { seriesReadStateRepository.findByUserIdAndSeriesId(user.id, series.id) } returns null
    every { anilistClient.library(confirmed, "t1") } returns emptyList()

    puller.pullAllTrackers()

    verify(timeout = 15_000) { trackerStatusRecorder.success(confirmed) }
    verifyOrder {
      trackerConnectionChecker.confirmIdentity(marked)
      anilistClient.library(confirmed, "t1")
    }
  }

  @Test
  fun `given an AniList account without a remote user ID when pulling then the identity is determined first`() {
    val unidentified = UserTracker(user.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t1"))
    every { userTrackerRepository.findByTracker(UserTracker.ANILIST) } returns listOf(unidentified)
    every { trackerConnectionChecker.confirmIdentity(unidentified) } returns active
    every { anilistClient.library(active, "t1") } returns emptyList()

    puller.pullAllTrackers()

    verify(timeout = 15_000) { anilistClient.library(active, "t1") }
    verify(exactly = 0) { anilistClient.library(unidentified, any()) }
  }

  @Test
  fun `given a failing identity check when pulling then the account is skipped and the marker is kept`() {
    val marked = active.copy(settings = active.settings + (UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED))
    val failure = TrackerUnavailableException("anilist is unavailable")
    every { userTrackerRepository.findByTracker(UserTracker.ANILIST) } returns listOf(marked)
    every { trackerConnectionChecker.confirmIdentity(marked) } throws failure

    puller.pullAllTrackers()

    verify(timeout = 15_000) { trackerStatusRecorder.failure(marked, failure) }
    Thread.sleep(500)
    verify(exactly = 0) { anilistClient.library(any(), any()) }
    verify(exactly = 0) { trackerStatusRecorder.success(any(), any(), any()) }
  }

  @Test
  fun `given a rate limited tracker when pulling then the account status records the failure and nothing is changed`() {
    val failure = TrackerRateLimitedException("anilist rate limit reached", null)
    every { trackerTokenProvider.withToken<Any?>(active, any()) } throws failure

    puller.pullAllTrackers()

    verify(timeout = 15_000) { trackerStatusRecorder.failure(active, failure) }
    verify(exactly = 0) { bookLifecycle.markReadProgressCompleted(any(), any()) }
  }

  @Test
  fun `given a pull still running when the next run is due then it is skipped and the scheduler thread is not blocked`() {
    val release = CountDownLatch(1)
    val started = CountDownLatch(1)
    every { seriesReadStateRepository.findByUserIdAndSeriesId(user.id, series.id) } returns null
    every { anilistClient.library(active, "t1") } answers {
      started.countDown()
      release.await(15, TimeUnit.SECONDS)
      emptyList()
    }

    puller.pullAllTrackers()
    assertThat(started.await(15, TimeUnit.SECONDS)).isTrue()
    puller.pullAllTrackers()
    release.countDown()

    verify(timeout = 15_000) { trackerStatusRecorder.success(active) }
    Thread.sleep(500)
    verify(exactly = 1) { anilistClient.library(active, "t1") }

    puller.pullAllTrackers()

    verify(timeout = 15_000, exactly = 2) { anilistClient.library(active, "t1") }
  }
}
