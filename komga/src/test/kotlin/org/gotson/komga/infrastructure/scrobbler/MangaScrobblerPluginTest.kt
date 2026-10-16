package org.gotson.komga.infrastructure.scrobbler

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginConfig
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.SeriesReadState
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesReadStateRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.download.MangaDexApiClient
import org.gotson.komga.infrastructure.download.MangaDexLookup
import org.gotson.komga.infrastructure.download.MangaDexTrackingInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MangaScrobblerPluginTest {
  private val pluginRepository = mockk<PluginRepository>()
  private val pluginConfigRepository = mockk<PluginConfigRepository>()
  private val bookRepository = mockk<BookRepository>()
  private val seriesRepository = mockk<SeriesRepository>()
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>()
  private val seriesReadStateRepository = mockk<SeriesReadStateRepository>()
  private val userTrackerRepository = mockk<UserTrackerRepository>()
  private val trackerIdDiscovery = mockk<TrackerIdDiscovery>()
  private val trackerTokenProvider = mockk<TrackerTokenProvider>()
  private val trackerStatusRecorder = mockk<TrackerStatusRecorder>(relaxed = true)
  private val trackerConnectionChecker = mockk<TrackerConnectionChecker>()
  private val syncStateRecorder = mockk<SyncStateRecorder>(relaxed = true)
  private val anilistClient =
    mockk<MangaTrackerClient>(relaxed = true) {
      every { tracker } returns UserTracker.ANILIST
    }

  private val mangaDexApiClient = mockk<MangaDexApiClient>(relaxed = true)
  private val malClient =
    mockk<MangaTrackerClient>(relaxed = true) {
      every { tracker } returns UserTracker.MAL
    }

  private val clock = ShiftableClock()

  private val scrobbler =
    MangaScrobblerPlugin(
      pluginRepository,
      pluginConfigRepository,
      bookRepository,
      seriesRepository,
      seriesMetadataRepository,
      seriesReadStateRepository,
      userTrackerRepository,
      mockk<ChapterUrlRepository>(relaxed = true),
      trackerIdDiscovery,
      trackerTokenProvider,
      trackerStatusRecorder,
      trackerConnectionChecker,
      syncStateRecorder,
      mangaDexApiClient,
      mockk<MangaDexTrackerClient>(relaxed = true),
      listOf(anilistClient, malClient),
      clock,
    )

  private val series = makeSeries("Series").copy(id = "series", libraryId = "library")
  private val book = makeBook("c010", seriesId = "series", id = "book")
  private val meta = SeriesMetadata(title = "Series", seriesId = "series")
  private val reader = "reader"
  private val account = UserTracker(reader, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "token"))
  private val readState = SeriesReadState(series.id, "library", false, 10.5F, LocalDateTime.of(2026, 1, 2, 10, 0), LocalDateTime.of(2026, 3, 4, 10, 0))

  @BeforeEach
  fun setup() {
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
    every { bookRepository.findByIdOrNull(book.id) } returns book
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns meta
    every { seriesReadStateRepository.findByUserIdAndSeriesId(reader, series.id) } returns readState
    every { userTrackerRepository.findByUserId(reader) } returns listOf(account)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.ANILIST) } returns account
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.MAL) } returns null
    every { syncStateRecorder.progress(any(), any(), any()) } returns 0
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returns TrackerIdResolution(mapOf(UserTracker.ANILIST to "100114"))
    every { trackerTokenProvider.withToken<Any?>(any(), any()) } answers { secondArg<(String) -> Any?>().invoke("token") }
  }

  @AfterEach
  fun tearDown() {
    scrobbler.shutdown()
  }

  private fun finish() {
    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(book.id, reader, 1, true)))
  }

  private fun retryAttempts(): Map<*, *> =
    MangaScrobblerPlugin::class.java
      .getDeclaredField("retryAttempts")
      .apply { isAccessible = true }
      .get(scrobbler) as Map<*, *>

  private fun identityBackoff(): Map<*, *> =
    MangaScrobblerPlugin::class.java
      .getDeclaredField("identityBackoff")
      .apply { isAccessible = true }
      .get(scrobbler) as Map<*, *>

  private fun awaitNoRetryAttempts() {
    val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
    while (retryAttempts().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
    assertThat(retryAttempts()).isEmpty()
  }

  private val discoveryFailure = TrackerIdResolution(emptyMap(), mapOf(UserTracker.ANILIST to TrackerIdFailure("anilist rate limit reached", Duration.ofMillis(200))))

  @Test
  fun `given a remote entry behind the highest read chapter when the user finishes a book then the chapter is sent as current with the start date`() {
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)
    val update = slot<EntryUpdate>()
    every { anilistClient.save(any(), any(), "100114", any(), capture(update)) } returns Unit

    finish()

    verify(timeout = 15_000) { syncStateRecorder.record(reader, series.id, UserTracker.ANILIST, 10, RemoteStatus.CURRENT.name, false) }
    assertThat(update.captured).isEqualTo(EntryUpdate(10, RemoteStatus.CURRENT, LocalDate.of(2026, 1, 2), null))
    verify { trackerStatusRecorder.success(account) }
  }

  @Test
  fun `given a stale volume_library_ids value for the library when the user finishes a book then the read chapter is sent`() {
    every { pluginConfigRepository.findByPluginId("manga-scrobbler") } returns listOf(PluginConfig("1", "manga-scrobbler", "volume_library_ids", "library"))
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)
    val update = slot<EntryUpdate>()
    every { anilistClient.save(any(), any(), "100114", any(), capture(update)) } returns Unit

    finish()

    verify(timeout = 15_000) { syncStateRecorder.record(reader, series.id, UserTracker.ANILIST, 10, RemoteStatus.CURRENT.name, false) }
    assertThat(update.captured).isEqualTo(EntryUpdate(10, RemoteStatus.CURRENT, LocalDate.of(2026, 1, 2), null))
  }

  @Test
  fun `given a one-shot series when the user finishes its book then chapter 1 is sent and the entry is completed`() {
    every { seriesRepository.findByIdOrNull(series.id) } returns series.copy(oneshot = true)
    every { seriesReadStateRepository.findByUserIdAndSeriesId(reader, series.id) } returns readState.copy(oneshot = true)
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)
    val update = slot<EntryUpdate>()
    every { anilistClient.save(any(), any(), "100114", any(), capture(update)) } returns Unit

    finish()

    verify(timeout = 15_000) { syncStateRecorder.record(reader, series.id, UserTracker.ANILIST, 1, RemoteStatus.COMPLETED.name, false) }
    assertThat(update.captured).isEqualTo(EntryUpdate(1, RemoteStatus.COMPLETED, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 3, 4)))
  }

  @Test
  fun `given a remote entry ahead of the local progress when the user finishes a book then the remote progress is not lowered`() {
    every { anilistClient.entry(any(), any(), "100114") } returns
      RemoteEntryState(RemoteEntry("100114", 80, RemoteStatus.CURRENT, null, null), null)

    finish()

    verify(timeout = 15_000) { syncStateRecorder.record(reader, series.id, UserTracker.ANILIST, 10, null, false) }
    verify(exactly = 0) { anilistClient.save(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given the total chapter count is reached when the user finishes a book then the entry is completed but a completed entry is never reopened`() {
    every { anilistClient.entry(any(), any(), "100114") } returns
      RemoteEntryState(RemoteEntry("100114", 5, RemoteStatus.CURRENT, LocalDate.of(2025, 1, 1), null), 10)
    val update = slot<EntryUpdate>()
    every { anilistClient.save(any(), any(), "100114", any(), capture(update)) } returns Unit

    finish()

    verify(timeout = 15_000) { anilistClient.save(any(), any(), "100114", any(), any()) }
    assertThat(update.captured).isEqualTo(EntryUpdate(10, RemoteStatus.COMPLETED, null, LocalDate.of(2026, 3, 4)))
  }

  @Test
  fun `given an already synced account when the user finishes a book then nothing is resolved`() {
    every { syncStateRecorder.progress(reader, series.id, UserTracker.ANILIST) } returns 10

    finish()

    verify(timeout = 15_000) { syncStateRecorder.progress(reader, series.id, UserTracker.ANILIST) }
    Thread.sleep(1_000)
    verify(exactly = 0) { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.MAL) }
    verify(exactly = 0) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given a paused account when the user finishes a book then nothing is resolved`() {
    every { userTrackerRepository.findByUserId(reader) } returns listOf(account.copy(settings = account.settings + (UserTracker.KEY_ENABLED to "false")))

    finish()

    verify(timeout = 15_000) { userTrackerRepository.findByUserId(reader) }
    Thread.sleep(1_000)
    verify(exactly = 0) { syncStateRecorder.progress(any(), any(), any()) }
    verify(exactly = 0) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given a rejected token when the user finishes a book then the account status is recorded`() {
    val failure = TrackerAuthException("anilist rejected the token")
    every { trackerTokenProvider.withToken<Any?>(any(), any()) } throws failure

    finish()

    verify(timeout = 15_000) { trackerStatusRecorder.failure(account, failure) }
    verify(exactly = 0) { syncStateRecorder.record(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given a rate limited push when the user finishes a book then the push is planned again after the Retry-After and sent`() {
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)
    val failure = TrackerRateLimitedException("anilist rate limit reached", Duration.ofMillis(200))
    every { trackerTokenProvider.withToken<Any?>(any(), any()) } throws failure andThenAnswer { secondArg<(String) -> Any?>().invoke("token") }

    finish()

    verify(timeout = 15_000) { anilistClient.save(any(), any(), "100114", any(), any()) }
    verify(exactly = 2) { trackerIdDiscovery.resolve(series, meta, listOf(UserTracker.ANILIST), any(), any(), any()) }
    verify(timeout = 15_000, exactly = 1) { trackerStatusRecorder.success(account) }
  }

  @Test
  fun `given an account whose identity must be checked when the user finishes a book then the identity is confirmed before the push`() {
    val marked = account.copy(settings = account.settings + (UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED))
    val confirmed = account.copy(settings = account.settings + (UserTracker.KEY_REMOTE_USER_ID to "1"))
    every { userTrackerRepository.findByUserId(reader) } returns listOf(marked)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.ANILIST) } returns marked
    every { trackerConnectionChecker.confirmIdentity(marked) } returns confirmed
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)

    finish()

    verify(timeout = 15_000) { trackerStatusRecorder.success(confirmed) }
    verifyOrder {
      trackerConnectionChecker.confirmIdentity(marked)
      syncStateRecorder.progress(reader, series.id, UserTracker.ANILIST)
      anilistClient.save(confirmed, any(), "100114", any(), any())
    }
  }

  @Test
  fun `given a failing identity check when the user finishes a book then nothing is pushed and the push is planned again`() {
    val marked = account.copy(settings = account.settings + (UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED))
    val failure = TrackerRateLimitedException("anilist rate limit reached", Duration.ofMillis(200))
    every { userTrackerRepository.findByUserId(reader) } returns listOf(marked)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.ANILIST) } returns marked
    every { trackerConnectionChecker.confirmIdentity(marked) } throws failure andThen account
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)

    finish()

    verify(timeout = 15_000) { trackerStatusRecorder.failure(marked, failure) }
    verify(timeout = 15_000) { anilistClient.save(account, any(), "100114", any(), any()) }
    verify(exactly = 2) { trackerConnectionChecker.confirmIdentity(marked) }
    verify(exactly = 1) { anilistClient.save(any(), any(), any(), any(), any()) }
    assertThat(identityBackoff()).isEmpty()
  }

  @Test
  fun `given an identity backoff when it is cleared because of new credentials then the identity is checked again right away`() {
    val marked = account.copy(settings = account.settings + (UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED))
    val failure = TrackerUnavailableException("anilist returned HTTP 503")
    every { userTrackerRepository.findByUserId(reader) } returns listOf(marked)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.ANILIST) } returns marked
    every { trackerConnectionChecker.confirmIdentity(marked) } throws failure

    finish()
    verify(timeout = 15_000) { trackerStatusRecorder.failure(marked, failure) }
    assertThat(identityBackoff()).isNotEmpty()

    scrobbler.clearIdentityBackoff(reader, UserTracker.ANILIST)
    assertThat(identityBackoff()).isEmpty()
    finish()

    verify(timeout = 15_000, exactly = 2) { trackerConnectionChecker.confirmIdentity(marked) }
  }

  @Test
  fun `given an identity check that keeps failing when many series are planned then one identity check runs per backoff window and nothing is pushed`() {
    val marked = account.copy(settings = account.settings + (UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED))
    val failure = TrackerUnavailableException("anilist returned HTTP 503")
    val others = listOf("series-2", "series-3").map { makeSeries(it).copy(id = it, libraryId = "library") }
    others.forEach {
      every { seriesRepository.findByIdOrNull(it.id) } returns it
      every { seriesMetadataRepository.findByIdOrNull(it.id) } returns SeriesMetadata(title = it.name, seriesId = it.id)
      every { seriesReadStateRepository.findByUserIdAndSeriesId(reader, it.id) } returns readState.copy(seriesId = it.id)
    }
    every { seriesReadStateRepository.findByUserIdReadSince(reader, any()) } returns listOf(readState) + others.map { readState.copy(seriesId = it.id) }
    every { userTrackerRepository.findByUserId(reader) } returns listOf(marked)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.ANILIST) } returns marked
    every { userTrackerRepository.findByTracker(any()) } returns emptyList()
    every { userTrackerRepository.findByTracker(UserTracker.ANILIST) } returns listOf(marked)
    every { trackerConnectionChecker.confirmIdentity(marked) } throws failure

    assertThat(scrobbler.reconcileNow(reader, UserTracker.ANILIST)).isEqualTo(3)

    verify(timeout = 15_000) { trackerStatusRecorder.failure(marked, failure) }
    Thread.sleep(1_000)
    verify(exactly = 1) { trackerConnectionChecker.confirmIdentity(marked) }
    verify(exactly = 1) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }

    scrobbler.reconcileRecentlyRead()
    Thread.sleep(6_500)
    verify(exactly = 1) { trackerConnectionChecker.confirmIdentity(marked) }

    clock.shift = Duration.ofMinutes(11)
    scrobbler.reconcileRecentlyRead()

    verify(timeout = 15_000, exactly = 2) { trackerConnectionChecker.confirmIdentity(marked) }
    Thread.sleep(1_000)
    verify(exactly = 2) { trackerConnectionChecker.confirmIdentity(marked) }
    verify(exactly = 0) { anilistClient.save(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given a failed ID lookup when the user finishes a book then the push is planned again and sent once the ID is found`() {
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returnsMany
      listOf(
        TrackerIdResolution(emptyMap(), mapOf(UserTracker.ANILIST to TrackerIdFailure("anilist rate limit reached", Duration.ofMillis(200)))),
        TrackerIdResolution(mapOf(UserTracker.ANILIST to "100114")),
      )

    finish()

    verify(timeout = 15_000) { anilistClient.save(any(), any(), "100114", any(), any()) }
    verify(exactly = 2) { trackerIdDiscovery.resolve(series, meta, listOf(UserTracker.ANILIST), any(), any(), any()) }
  }

  @Test
  fun `given an ID that is not found when the user finishes a book then the push is not planned again`() {
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returns TrackerIdResolution(emptyMap())

    finish()

    verify(timeout = 15_000) { trackerIdDiscovery.resolve(series, meta, listOf(UserTracker.ANILIST), any(), any(), any()) }
    Thread.sleep(6_500)
    verify(exactly = 1) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given a retry without Retry-After when computing the delay then it starts at 10 minutes and doubles up to 6 hours`() {
    assertThat(retryDelay(1, null)).isEqualTo(Duration.ofMinutes(10))
    assertThat(retryDelay(2, null)).isEqualTo(Duration.ofMinutes(20))
    assertThat(retryDelay(6, null)).isEqualTo(Duration.ofMinutes(320))
    assertThat(retryDelay(7, null)).isEqualTo(Duration.ofHours(6))
    assertThat(retryDelay(50, null)).isEqualTo(Duration.ofHours(6))
    assertThat(retryDelay(3, Duration.ofHours(10))).isEqualTo(Duration.ofHours(10))
  }

  @Test
  fun `given a slow tracker when the user finishes a book then the other tracker is synced without waiting for it`() {
    val malAccount = UserTracker(reader, UserTracker.MAL, mapOf(UserTracker.KEY_ACCESS_TOKEN to "token"))
    val release = CountDownLatch(1)
    every { userTrackerRepository.findByUserId(reader) } returns listOf(account, malAccount)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.MAL) } returns malAccount
    every { trackerIdDiscovery.resolve(any(), any(), listOf(UserTracker.ANILIST), any(), any(), any()) } answers {
      release.await(30, TimeUnit.SECONDS)
      TrackerIdResolution(mapOf(UserTracker.ANILIST to "100114"))
    }
    every { trackerIdDiscovery.resolve(any(), any(), listOf(UserTracker.MAL), any(), any(), any()) } returns TrackerIdResolution(mapOf(UserTracker.MAL to "2"))
    every { malClient.entry(any(), any(), "2") } returns RemoteEntryState(null, null)
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)

    finish()

    verify(timeout = 15_000) { malClient.save(malAccount, any(), "2", any(), any()) }
    verify(exactly = 0) { anilistClient.save(any(), any(), any(), any(), any()) }
    release.countDown()
    verify(timeout = 15_000) { anilistClient.save(account, any(), "100114", any(), any()) }
  }

  @Test
  fun `given a MangaDex skip because of the content rating when the reconcile job runs then the series is not looked up again`() {
    val mangaDexAccount =
      UserTracker(
        reader,
        UserTracker.MANGADEX,
        mapOf(
          UserTracker.KEY_USERNAME to "reader",
          UserTracker.KEY_PASSWORD to "pw",
          UserTracker.KEY_CLIENT_ID to "id",
          UserTracker.KEY_CLIENT_SECRET to "secret",
          UserTracker.KEY_MAX_CONTENT_RATING to "safe",
        ),
      )
    val mangaId = "0f6b4c0e-2a3b-4c5d-8e9f-0a1b2c3d4e5f"
    every { userTrackerRepository.findByUserId(reader) } returns listOf(mangaDexAccount)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.MANGADEX) } returns mangaDexAccount
    every { userTrackerRepository.findByTracker(any()) } returns emptyList()
    every { userTrackerRepository.findByTracker(UserTracker.MANGADEX) } returns listOf(mangaDexAccount)
    every { seriesReadStateRepository.findByUserIdReadSince(reader, any()) } returns listOf(readState)
    every { trackerIdDiscovery.resolve(any(), any(), listOf(UserTracker.MANGADEX), any(), any(), any()) } returns TrackerIdResolution(mapOf(UserTracker.MANGADEX to mangaId))
    every { mangaDexApiClient.lookupMangaForTracking(mangaId) } returns MangaDexLookup.Found(MangaDexTrackingInfo(emptyMap(), "erotica"))

    finish()
    verify(timeout = 15_000) { trackerStatusRecorder.success(mangaDexAccount) }

    scrobbler.reconcileRecentlyRead()
    Thread.sleep(6_500)

    verify(exactly = 1) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }
    verify(exactly = 1) { mangaDexApiClient.lookupMangaForTracking(mangaId) }
    verify(exactly = 0) { syncStateRecorder.record(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given series in an excluded library or a paused account when reconciling now then only the series that are planned are counted`() {
    every { pluginConfigRepository.findByPluginId("manga-scrobbler") } returns listOf(PluginConfig("1", "manga-scrobbler", "exclude_library_ids", "excluded"))
    every { seriesReadStateRepository.findByUserIdReadSince(reader, null) } returns listOf(readState, readState.copy(seriesId = "other", libraryId = "excluded"))

    assertThat(scrobbler.reconcileNow(reader, UserTracker.ANILIST)).isEqualTo(1)

    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.ANILIST) } returns account.copy(settings = account.settings + (UserTracker.KEY_ENABLED to "false"))

    assertThat(scrobbler.reconcileNow(reader, UserTracker.ANILIST)).isEqualTo(0)
  }

  @Test
  fun `given a series that is a oneshot only by genre or tag when the user finishes its book then chapter 1 is sent and the entry is completed`() {
    every { seriesReadStateRepository.findByUserIdAndSeriesId(reader, series.id) } returns readState.copy(oneshot = true)
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)
    val update = slot<EntryUpdate>()
    every { anilistClient.save(any(), any(), "100114", any(), capture(update)) } returns Unit

    finish()

    verify(timeout = 15_000) { syncStateRecorder.record(reader, series.id, UserTracker.ANILIST, 1, RemoteStatus.COMPLETED.name, false) }
    assertThat(update.captured).isEqualTo(EntryUpdate(1, RemoteStatus.COMPLETED, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 3, 4)))
  }

  @Test
  fun `given an account whose identity must be checked and an old sync state at the target when the user finishes a book then the identity is checked and a switched account is sent`() {
    val marked = account.copy(settings = account.settings + (UserTracker.KEY_IDENTITY_CHECK to UserTracker.IDENTITY_CHECK_REQUIRED))
    val switched = account.copy(settings = account.settings + (UserTracker.KEY_REMOTE_USER_ID to "2"))
    var progress = 10
    every { syncStateRecorder.progress(reader, series.id, UserTracker.ANILIST) } answers { progress }
    every { userTrackerRepository.findByUserId(reader) } returns listOf(marked)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.ANILIST) } returns marked
    every { trackerConnectionChecker.confirmIdentity(marked) } answers {
      progress = 0
      switched
    }
    every { anilistClient.entry(any(), any(), "100114") } returns RemoteEntryState(null, null)

    finish()

    verify(timeout = 15_000) { anilistClient.save(switched, any(), "100114", any(), any()) }
    verify(exactly = 1) { trackerConnectionChecker.confirmIdentity(marked) }
  }

  @Test
  fun `given a retried push when the ID is then not found then the retry attempts are cleared`() {
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returnsMany listOf(discoveryFailure, TrackerIdResolution(emptyMap()))

    finish()

    verify(timeout = 15_000, exactly = 2) { trackerIdDiscovery.resolve(series, meta, listOf(UserTracker.ANILIST), any(), any(), any()) }
    awaitNoRetryAttempts()
  }

  @Test
  fun `given a retried push when the tracker is already synced at the push then the retry attempts are cleared`() {
    every { syncStateRecorder.progress(reader, series.id, UserTracker.ANILIST) } returnsMany listOf(0, 0, 10)
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returnsMany
      listOf(discoveryFailure, TrackerIdResolution(mapOf(UserTracker.ANILIST to "100114")))

    finish()

    verify(timeout = 15_000, exactly = 2) { trackerIdDiscovery.resolve(series, meta, listOf(UserTracker.ANILIST), any(), any(), any()) }
    verify(timeout = 15_000, exactly = 3) { syncStateRecorder.progress(reader, series.id, UserTracker.ANILIST) }
    awaitNoRetryAttempts()
    verify(exactly = 0) { anilistClient.save(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given a retried push when the tracker is already synced before the lookup then the retry attempts are cleared`() {
    every { syncStateRecorder.progress(reader, series.id, UserTracker.ANILIST) } returnsMany listOf(0, 10)
    every { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) } returns discoveryFailure

    finish()

    verify(timeout = 15_000, exactly = 2) { syncStateRecorder.progress(reader, series.id, UserTracker.ANILIST) }
    awaitNoRetryAttempts()
    verify(exactly = 1) { trackerIdDiscovery.resolve(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `given a retried MangaDex push when it is skipped because of the content rating then the retry attempts are cleared`() {
    val mangaDexAccount =
      UserTracker(
        reader,
        UserTracker.MANGADEX,
        mapOf(
          UserTracker.KEY_USERNAME to "reader",
          UserTracker.KEY_PASSWORD to "pw",
          UserTracker.KEY_CLIENT_ID to "id",
          UserTracker.KEY_CLIENT_SECRET to "secret",
          UserTracker.KEY_MAX_CONTENT_RATING to "safe",
        ),
      )
    val mangaId = "0f6b4c0e-2a3b-4c5d-8e9f-0a1b2c3d4e5f"
    every { userTrackerRepository.findByUserId(reader) } returns listOf(mangaDexAccount)
    every { userTrackerRepository.findByUserIdAndTracker(reader, UserTracker.MANGADEX) } returns mangaDexAccount
    every { trackerIdDiscovery.resolve(any(), any(), listOf(UserTracker.MANGADEX), any(), any(), any()) } returnsMany
      listOf(
        TrackerIdResolution(emptyMap(), mapOf(UserTracker.MANGADEX to TrackerIdFailure("MangaDex returned HTTP 503", Duration.ofMillis(200)))),
        TrackerIdResolution(mapOf(UserTracker.MANGADEX to mangaId)),
      )
    every { mangaDexApiClient.lookupMangaForTracking(mangaId) } returns MangaDexLookup.Found(MangaDexTrackingInfo(emptyMap(), "erotica"))

    finish()

    verify(timeout = 15_000) { mangaDexApiClient.lookupMangaForTracking(mangaId) }
    awaitNoRetryAttempts()
    verify(exactly = 0) { syncStateRecorder.record(any(), any(), any(), any(), any(), any()) }
  }
}

private class ShiftableClock(
  var shift: Duration = Duration.ZERO,
) : Clock() {
  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = this

  override fun instant(): Instant = Instant.now().plus(shift)
}
