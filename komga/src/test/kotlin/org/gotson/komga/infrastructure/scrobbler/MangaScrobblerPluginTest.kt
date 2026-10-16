package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class MangaScrobblerPluginTest {
  private val pluginRepository = mockk<PluginRepository>()
  private val pluginConfigRepository = mockk<PluginConfigRepository>()
  private val bookRepository = mockk<BookRepository>()
  private val bookMetadataRepository = mockk<BookMetadataRepository>()
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>()
  private val syncStateRepository = mockk<SyncStateRepository>(relaxed = true)
  private val userTrackerRepository = mockk<UserTrackerRepository>()
  private val trackerIdResolver = mockk<TrackerIdResolver>()
  private val trackerTokenProvider = mockk<TrackerTokenProvider>(relaxed = true)

  private val scrobbler =
    MangaScrobblerPlugin(
      pluginRepository,
      pluginConfigRepository,
      bookRepository,
      bookMetadataRepository,
      seriesMetadataRepository,
      syncStateRepository,
      userTrackerRepository,
      trackerIdResolver,
      trackerTokenProvider,
      ObjectMapper(),
    )

  private val reader = "reader"
  private val other = "other"
  private val readerBook = makeBook("c010", seriesId = "series", id = "reader-book")
  private val otherBook = makeBook("c011", seriesId = "series", id = "other-book")

  private fun givenEnabledPlugin() {
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
    every { bookRepository.findByIdOrNull(readerBook.id) } returns readerBook
    every { bookMetadataRepository.findByIdOrNull(readerBook.id) } returns BookMetadata(title = "c010", number = "10", numberSort = 10F, bookId = readerBook.id)
    every { seriesMetadataRepository.findByIdOrNull("series") } returns SeriesMetadata(title = "Series", seriesId = "series")
    every { trackerIdResolver.resolve(any(), any(), any()) } returns MangaTrackerIds(null, null, null, null)
  }

  @Test
  fun `given a reader with a linked tracker and another user without when both finish a book then only the reader's progress is processed`() {
    givenEnabledPlugin()
    every { userTrackerRepository.findByUserId(other) } returns emptyList()
    every { userTrackerRepository.findByUserId(reader) } returns
      listOf(UserTracker(reader, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "reader-token")))

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(otherBook.id, other, 1, true)))
    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(readerBook.id, reader, 1, true)))

    verify(timeout = 5000) { trackerIdResolver.resolve("Series", any(), any()) }
    verify(exactly = 1) { userTrackerRepository.findByUserId(other) }
    verify(exactly = 0) { bookRepository.findByIdOrNull(otherBook.id) }
    verify(exactly = 0) { syncStateRepository.insert(any()) }
  }

  @Test
  fun `given a paused tracker when the user finishes a book then nothing is processed`() {
    givenEnabledPlugin()
    every { userTrackerRepository.findByUserId(other) } returns
      listOf(UserTracker(other, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t", UserTracker.KEY_ENABLED to "false")))
    every { userTrackerRepository.findByUserId(reader) } returns
      listOf(UserTracker(reader, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "reader-token")))

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(otherBook.id, other, 1, true)))
    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress(readerBook.id, reader, 1, true)))

    verify(timeout = 5000) { trackerIdResolver.resolve("Series", any(), any()) }
    verify(exactly = 0) { bookRepository.findByIdOrNull(otherBook.id) }
  }
}
