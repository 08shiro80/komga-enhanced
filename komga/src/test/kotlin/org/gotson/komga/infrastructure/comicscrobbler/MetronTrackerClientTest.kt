package org.gotson.komga.infrastructure.comicscrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.infrastructure.metadata.tracker.TitleSearchResult
import org.gotson.komga.infrastructure.metadata.tracker.TrackerTitleSearchProvider
import org.gotson.komga.infrastructure.scrobbler.TrackerClients
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimiter
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val AUTHORIZATION = "Basic cmVhZGVyOnB3"

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MetronTrackerClientTest {
  private val restTemplate = RestTemplate()
  private val server = MockRestServiceServer.createServer(restTemplate)
  private val trackerTitleSearchProvider = mockk<TrackerTitleSearchProvider>()
  private val client =
    MetronTrackerClient(
      TrackerClients(mockk<TrackerRateLimiter>(relaxed = true), restTemplate.requestFactory),
      trackerTitleSearchProvider,
      ObjectMapper(),
    )
  private val meta = SeriesMetadata(title = "Saga", seriesId = "series")

  @Test
  fun `given a result whose title only matches without the year when searching the series then it is found`() {
    every { trackerTitleSearchProvider.searchCandidates(meta) } returns listOf("Saga")
    every { trackerTitleSearchProvider.localTitles(meta) } returns setOf("saga")
    server
      .expect(requestTo("https://metron.cloud/api/series/?name=Saga"))
      .andExpect(method(HttpMethod.GET))
      .andExpect(header("Authorization", AUTHORIZATION))
      .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
      .andRespond(withSuccess("""{"count":2,"results":[{"id":1,"series":"Saga (2012)"},{"id":2,"series":"Saga of the Swamp Thing (1982)"}]}""", MediaType.APPLICATION_JSON))

    assertThat(client.searchSeries(AUTHORIZATION, meta)).isEqualTo(TitleSearchResult.Found("1"))
    server.verify()
  }

  @Test
  fun `given an exact title match when searching the series then results that only match without the year are ignored`() {
    every { trackerTitleSearchProvider.searchCandidates(meta) } returns listOf("Saga")
    every { trackerTitleSearchProvider.localTitles(meta) } returns setOf("saga")
    server
      .expect(requestTo("https://metron.cloud/api/series/?name=Saga"))
      .andRespond(withSuccess("""{"results":[{"id":1,"series":"Saga (2012)"},{"id":3,"series":"Saga"}]}""", MediaType.APPLICATION_JSON))

    assertThat(client.searchSeries(AUTHORIZATION, meta)).isEqualTo(TitleSearchResult.Found("3"))
    server.verify()
  }

  @Test
  fun `given a not found candidate when searching the series then the next candidate is tried and several matches are ambiguous`() {
    every { trackerTitleSearchProvider.searchCandidates(meta) } returns listOf("Saga", "Saga Comic")
    every { trackerTitleSearchProvider.localTitles(meta) } returns setOf("saga", "sagacomic")
    server
      .expect(requestTo("https://metron.cloud/api/series/?name=Saga"))
      .andRespond(withResourceNotFound())
    server
      .expect(requestTo("https://metron.cloud/api/series/?name=Saga%20Comic"))
      .andRespond(withSuccess("""{"results":[{"id":4,"series":"Saga Comic (2001)"},{"id":5,"series":"Saga Comic (2019)"}]}""", MediaType.APPLICATION_JSON))

    assertThat(client.searchSeries(AUTHORIZATION, meta)).isEqualTo(TitleSearchResult.Ambiguous(listOf("4", "5")))
    server.verify()
  }

  @Test
  fun `given failing and empty searches when searching the series then the failure is reported`() {
    every { trackerTitleSearchProvider.searchCandidates(meta) } returns listOf("Saga", "Other")
    every { trackerTitleSearchProvider.localTitles(meta) } returns setOf("saga", "other")
    server
      .expect(requestTo("https://metron.cloud/api/series/?name=Saga"))
      .andRespond(withServerError())
    server
      .expect(requestTo("https://metron.cloud/api/series/?name=Other"))
      .andRespond(withSuccess("""{"results":[]}""", MediaType.APPLICATION_JSON))

    val result = client.searchSeries(AUTHORIZATION, meta)

    server.verify()
    assertThat(result).isInstanceOf(TitleSearchResult.Failed::class.java)
    assertThat((result as TitleSearchResult.Failed).reason).startsWith("Metron series search for 'Saga' failed:")
  }

  @Test
  fun `given no matching result when searching the series then nothing is found`() {
    every { trackerTitleSearchProvider.searchCandidates(meta) } returns listOf("Saga")
    every { trackerTitleSearchProvider.localTitles(meta) } returns setOf("saga")
    server
      .expect(requestTo("https://metron.cloud/api/series/?name=Saga"))
      .andRespond(withSuccess("""{"results":[{"id":2,"series":"Saga of the Swamp Thing"}]}""", MediaType.APPLICATION_JSON))

    assertThat(client.searchSeries(AUTHORIZATION, meta)).isEqualTo(TitleSearchResult.NotFound)
    server.verify()
  }

  @Test
  fun `given issues of a series when reading the issue ids then only issues with the same normalized number are returned`() {
    server
      .expect(requestTo("https://metron.cloud/api/issue/?series_id=1&number=1.0"))
      .andExpect(method(HttpMethod.GET))
      .andExpect(header("Authorization", AUTHORIZATION))
      .andRespond(
        withSuccess(
          """{"results":[{"id":10,"number":"1"},{"id":11,"number":"1.5"},{"id":10,"number":"1.00"},{"id":"x","number":"1"},{"id":12,"number":"001"}]}""",
          MediaType.APPLICATION_JSON,
        ),
      )

    assertThat(client.issueIds(AUTHORIZATION, "1", "1.0")).containsExactly(10, 12)
    server.verify()
  }

  @Test
  fun `given a read issue when scrobbling then the issue id and read date are posted as JSON`() {
    server
      .expect(requestTo("https://metron.cloud/api/collection/scrobble/"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header("Authorization", AUTHORIZATION))
      .andExpect(header("Content-Type", startsWith(MediaType.APPLICATION_JSON_VALUE)))
      .andExpect(content().json("""{"issue_id":10,"date_read":"2026-03-04T10:15Z"}""", JsonCompareMode.STRICT))
      .andRespond(withSuccess())

    client.scrobble(AUTHORIZATION, 10, OffsetDateTime.of(2026, 3, 4, 10, 15, 0, 0, ZoneOffset.UTC))

    server.verify()
  }

  @Test
  fun `given credentials when checking access then a series search is sent`() {
    server
      .expect(requestTo("https://metron.cloud/api/series/?name=Batman"))
      .andExpect(header("Authorization", AUTHORIZATION))
      .andRespond(withSuccess("""{"results":[]}""", MediaType.APPLICATION_JSON))

    client.checkAccess(AUTHORIZATION)

    server.verify()
  }
}
