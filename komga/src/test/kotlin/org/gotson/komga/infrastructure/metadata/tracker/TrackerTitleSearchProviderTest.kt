package org.gotson.komga.infrastructure.metadata.tracker

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.AlternateTitle
import org.gotson.komga.domain.model.PluginConfig
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.infrastructure.scrobbler.TrackerClients
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimiter
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerTitleSearchProviderTest {
  private val pluginConfigRepository = mockk<PluginConfigRepository>()
  private val restTemplate = RestTemplate()
  private val server = MockRestServiceServer.createServer(restTemplate)
  private var now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } answers { now } }
  private val provider =
    TrackerTitleSearchProvider(
      pluginConfigRepository,
      ObjectMapper(),
      TrackerClients(mockk<TrackerRateLimiter>(relaxed = true), restTemplate.requestFactory),
      clock,
    )

  private val meta =
    SeriesMetadata(
      title = "Different World Foundation",
      seriesId = "0PRJ3931EME80",
      alternateTitles =
        listOf(
          AlternateTitle("JA", "異世界建国記"),
          AlternateTitle("EN", "different world foundation"),
          AlternateTitle("EN", "Otherworld Nation Founding Chronicles"),
          AlternateTitle("KO", "이세계 건국기"),
          AlternateTitle("ZH", "异世界建国记"),
          AlternateTitle("IT", "Cronache dalla Fondazione di un Impero in un Altro Mondo"),
          AlternateTitle("JA-RO", "Isekai Kenkokuki"),
        ),
    )

  private fun anilist(vararg media: String) = """{"data":{"Page":{"media":[${media.joinToString(",")}]}}}"""

  private fun anilistMedia(
    id: Int,
    format: String,
    romaji: String,
    synonyms: String = "",
  ) = """{"id":$id,"format":"$format","title":{"romaji":"$romaji","english":null,"native":null},"synonyms":[$synonyms]}"""

  @Test
  fun `given a title and alternate titles when building candidates then they are ordered, deduplicated and limited`() {
    assertThat(provider.searchCandidates(meta)).containsExactly(
      "Different World Foundation",
      "Isekai Kenkokuki",
      "Otherworld Nation Founding Chronicles",
      "異世界建国記",
      "이세계 건국기",
    )
  }

  @Test
  fun `given a novel and an unrelated first hit when searching then only the verified manga with a matching title is returned`() {
    server
      .expect(requestTo(startsWith("https://graphql.anilist.co")))
      .andExpect(jsonPath("$.variables.search").value("Different World Foundation"))
      .andExpect(jsonPath("$.query").value(containsString("format_in: [MANGA, ONE_SHOT]")))
      .andRespond(
        withSuccess(
          anilist(
            anilistMedia(136045, "MANGA", "Shinrigaku de Isekai Harem Kenkokuki"),
            anilistMedia(100107, "NOVEL", "Isekai Kenkokuki", "\"Different World Foundation\""),
            anilistMedia(100114, "MANGA", "Isekai Kenkokuki", "\"Different World Foundation\""),
          ),
          MediaType.APPLICATION_JSON,
        ),
      )

    assertThat(provider.search(UserTracker.ANILIST, meta)).isEqualTo(TitleSearchResult.Found("100114"))
    server.verify()
  }

  @Test
  fun `given a match for a later candidate when searching then candidates are tried in order and the search stops at the first match`() {
    server
      .expect(requestTo(startsWith("https://graphql.anilist.co")))
      .andExpect(jsonPath("$.variables.search").value("Different World Foundation"))
      .andRespond(withSuccess(anilist(), MediaType.APPLICATION_JSON))
    server
      .expect(requestTo(startsWith("https://graphql.anilist.co")))
      .andExpect(jsonPath("$.variables.search").value("Isekai Kenkokuki"))
      .andRespond(withSuccess(anilist(anilistMedia(100114, "MANGA", "Isekai Kenkokuki")), MediaType.APPLICATION_JSON))

    assertThat(provider.search(UserTracker.ANILIST, meta)).isEqualTo(TitleSearchResult.Found("100114"))
    server.verify()
  }

  @Test
  fun `given two different matching entries when searching then the result is ambiguous`() {
    server
      .expect(requestTo(startsWith("https://graphql.anilist.co")))
      .andRespond(
        withSuccess(anilist(anilistMedia(1, "MANGA", "Isekai Kenkokuki"), anilistMedia(2, "ONE_SHOT", "Different World Foundation")), MediaType.APPLICATION_JSON),
      )

    assertThat(provider.search(UserTracker.ANILIST, meta)).isEqualTo(TitleSearchResult.Ambiguous(listOf("1", "2")))
  }

  @Test
  fun `given no match for any candidate when searching then at most five requests are sent and the series is not searched again for 24 hours`() {
    server
      .expect(ExpectedCount.times(10), requestTo(startsWith("https://kitsu.app/api/edge/manga")))
      .andRespond(withSuccess("""{"data":[]}""", MediaType.APPLICATION_JSON))

    assertThat(provider.search(UserTracker.KITSU, meta)).isEqualTo(TitleSearchResult.NotFound)
    assertThat(provider.search(UserTracker.KITSU, meta)).isEqualTo(TitleSearchResult.NotFound)
    now = now.plus(Duration.ofHours(25))
    assertThat(provider.search(UserTracker.KITSU, meta)).isEqualTo(TitleSearchResult.NotFound)

    server.verify()
  }

  @Test
  fun `given a server error when searching then the result is failed and not cached`() {
    server
      .expect(ExpectedCount.times(10), requestTo(startsWith("https://kitsu.app/api/edge/manga")))
      .andRespond(withServerError())

    assertThat(provider.search(UserTracker.KITSU, meta)).isInstanceOf(TitleSearchResult.Failed::class.java)
    assertThat(provider.search(UserTracker.KITSU, meta)).isInstanceOf(TitleSearchResult.Failed::class.java)

    server.verify()
  }

  @Test
  fun `given no server MAL client id when searching MyAnimeList then the client id of the user is used and otherwise nothing is sent`() {
    every { pluginConfigRepository.findByPluginIdAndKey("auto-metadata", "mal_client_id") } returns null

    assertThat(provider.search(UserTracker.MAL, meta)).isInstanceOf(TitleSearchResult.Failed::class.java)

    server
      .expect(requestTo(startsWith("https://api.myanimelist.net/v2/manga")))
      .andExpect(header("X-MAL-CLIENT-ID", "user-client"))
      .andRespond(
        withSuccess(
          """{"data":[{"node":{"id":109696,"title":"Isekai Kenkokuki","media_type":"manga","alternative_titles":{"synonyms":[],"en":"","ja":"異世界建国記"}}}]}""",
          MediaType.APPLICATION_JSON,
        ),
      )
    assertThat(provider.search(UserTracker.MAL, meta, "user-client")).isEqualTo(TitleSearchResult.Found("109696"))
    server.verify()
  }

  @Test
  fun `given a server MAL client id when searching MyAnimeList then it is preferred`() {
    every { pluginConfigRepository.findByPluginIdAndKey("auto-metadata", "mal_client_id") } returns
      PluginConfig(id = "c", pluginId = "auto-metadata", configKey = "mal_client_id", configValue = "server-client")
    server
      .expect(requestTo(startsWith("https://api.myanimelist.net/v2/manga")))
      .andExpect(header("X-MAL-CLIENT-ID", "server-client"))
      .andRespond(
        withSuccess("""{"data":[{"node":{"id":1,"title":"Different World Foundation","media_type":"light_novel"}}]}""", MediaType.APPLICATION_JSON),
      )
    server
      .expect(ExpectedCount.manyTimes(), requestTo(startsWith("https://api.myanimelist.net/v2/manga")))
      .andRespond(withSuccess("""{"data":[]}""", MediaType.APPLICATION_JSON))

    assertThat(provider.search(UserTracker.MAL, meta, "user-client")).isEqualTo(TitleSearchResult.NotFound)
  }
}
