package org.gotson.komga.infrastructure.comicscrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.rate.MetronRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ComicScrobblerPluginTest {
  private val pluginRepository = mockk<PluginRepository>()
  private val pluginConfigRepository = mockk<PluginConfigRepository>()
  private val bookRepository = mockk<BookRepository>()
  private val userTrackerRepository = mockk<UserTrackerRepository>()

  private val scrobbler =
    ComicScrobblerPlugin(
      pluginRepository,
      pluginConfigRepository,
      bookRepository,
      mockk<BookMetadataRepository>(relaxed = true),
      mockk<SeriesMetadataRepository>(relaxed = true),
      mockk<SyncStateRepository>(relaxed = true),
      userTrackerRepository,
      ObjectMapper(),
      mockk<MetronRateLimiter>(relaxed = true),
    )

  @Test
  fun `given a user without a Metron account when finishing a book then the book is not processed`() {
    every { pluginRepository.findByIdOrNull("comic-scrobbler") } returns
      Plugin(
        id = "comic-scrobbler",
        name = "Comic Scrobbler",
        version = "2.0.0",
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
    every { userTrackerRepository.findByUserIdAndTracker("other", UserTracker.METRON) } returns null
    every { userTrackerRepository.findByUserIdAndTracker("reader", UserTracker.METRON) } returns
      UserTracker("reader", UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "u", UserTracker.KEY_PASSWORD to "p"))
    every { bookRepository.findByIdOrNull(any()) } returns null

    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress("other-book", "other", 1, true)))
    scrobbler.onReadProgressChanged(DomainEvent.ReadProgressChanged(ReadProgress("reader-book", "reader", 1, true)))

    verify(timeout = 5000) { bookRepository.findByIdOrNull("reader-book") }
    verify(exactly = 0) { bookRepository.findByIdOrNull("other-book") }
  }
}
