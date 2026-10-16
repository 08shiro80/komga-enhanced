package org.gotson.komga.infrastructure.metadata.tracker

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesTrackerIdRepository
import org.gotson.komga.infrastructure.download.MangaDexRateLimiter
import org.gotson.komga.infrastructure.scrobbler.TrackerClients
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimiter
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerLinkEnricherTest {
  @Volatile
  private var now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } answers { now } }
  private val restTemplate = RestTemplate()
  private val server = MockRestServiceServer.createServer(restTemplate)
  private val rateLimiter = TrackerRateLimiter(mockk<MangaDexRateLimiter>(relaxed = true), clock)
  private val pluginConfigRepository = mockk<PluginConfigRepository>()
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>()
  private val pluginRepository = mockk<PluginRepository>()
  private val seriesTrackerIdRepository = mockk<SeriesTrackerIdRepository>()

  private val enricher =
    TrackerLinkEnricher(
      seriesMetadataRepository,
      pluginRepository,
      seriesTrackerIdRepository,
      TrackerTitleSearchProvider(pluginConfigRepository, ObjectMapper(), TrackerClients(rateLimiter, restTemplate.requestFactory), clock),
      rateLimiter,
    )

  private val series = makeSeries("Isekai Kenkokuki").copy(id = "series")
  private val meta = SeriesMetadata(title = "Isekai Kenkokuki", seriesId = series.id)

  @BeforeEach
  fun setup() {
    every { seriesMetadataRepository.findById(series.id) } returns meta
    every { pluginRepository.findByIdOrNull(any()) } returns null
    every { pluginRepository.findByIdOrNull("anilist-metadata") } returns mockk<Plugin> { every { enabled } returns true }
    every { pluginConfigRepository.findByPluginIdAndKey(any(), any()) } returns null
    every { seriesTrackerIdRepository.findBySeriesId(series.id) } returns emptyList()
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  fun `given an exhausted AniList rate limit when enriching links then the task fails at once and searches again once the limit is free`() {
    repeat(30) { rateLimiter.acquire(UserTracker.ANILIST) }

    val started = System.nanoTime()
    val limited = enricher.getSeriesMetadata(series)

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5))
    assertThat(limited).isNull()

    now = now.plusSeconds(61)
    server
      .expect(requestTo(startsWith("https://graphql.anilist.co")))
      .andRespond(
        withSuccess(
          """{"data":{"Page":{"media":[{"id":100114,"format":"MANGA","title":{"romaji":"Isekai Kenkokuki"},"synonyms":[]}]}}}""",
          MediaType.APPLICATION_JSON,
        ),
      )

    val patch = enricher.getSeriesMetadata(series)

    assertThat(patch?.links).containsExactly(WebLink("AniList", URI("https://anilist.co/manga/100114")))
    server.verify()
  }
}
