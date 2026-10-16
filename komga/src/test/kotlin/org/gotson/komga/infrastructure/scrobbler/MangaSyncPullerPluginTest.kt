package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class MangaSyncPullerPluginTest {
  private val pluginRepository = mockk<PluginRepository>()
  private val pluginConfigRepository = mockk<PluginConfigRepository>()
  private val readProgressRepository = mockk<ReadProgressRepository>(relaxed = true)
  private val syncStateRepository = mockk<SyncStateRepository>()
  private val userTrackerRepository = mockk<UserTrackerRepository>()
  private val trackerIdResolver = mockk<TrackerIdResolver>()
  private val trackerTokenProvider = mockk<TrackerTokenProvider>()

  private val puller =
    MangaSyncPullerPlugin(
      pluginRepository,
      pluginConfigRepository,
      mockk<BookRepository>(relaxed = true),
      mockk<BookMetadataRepository>(relaxed = true),
      mockk<SeriesRepository>(relaxed = true),
      readProgressRepository,
      syncStateRepository,
      userTrackerRepository,
      trackerIdResolver,
      trackerTokenProvider,
      ObjectMapper(),
    )

  @Test
  fun `given an active and a paused account when pulling then only the active user's sync states are read`() {
    every { pluginRepository.findByIdOrNull("manga-scrobbler") } returns
      Plugin(
        id = "manga-scrobbler",
        name = "Manga Scrobbler",
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
    every { pluginConfigRepository.findByPluginId("manga-scrobbler") } returns emptyList()
    every { userTrackerRepository.findByTracker(UserTracker.ANILIST) } returns
      listOf(
        UserTracker("active", UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t1")),
        UserTracker("paused", UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t2", UserTracker.KEY_ENABLED to "false")),
      )
    every { userTrackerRepository.findByTracker(UserTracker.MAL) } returns emptyList()
    every { userTrackerRepository.findByTracker(UserTracker.KITSU) } returns emptyList()
    every { syncStateRepository.findByUserIdAndTracker("active", UserTracker.ANILIST) } returns
      listOf(SyncState(id = "s1", userId = "active", bookId = null, seriesId = "series", tracker = UserTracker.ANILIST, progress = 3))
    every { trackerIdResolver.resolveFromKomgaSeriesId("series", any()) } returns MangaTrackerIds(null, null, null, null)

    puller.pullAllTrackers()

    verify(exactly = 1) { syncStateRepository.findByUserIdAndTracker("active", UserTracker.ANILIST) }
    verify(exactly = 0) { syncStateRepository.findByUserIdAndTracker("paused", any()) }
    verify(exactly = 0) { readProgressRepository.save(any<Collection<ReadProgress>>()) }
  }
}
