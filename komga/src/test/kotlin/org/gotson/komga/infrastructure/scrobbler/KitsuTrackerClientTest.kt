package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.UserTracker
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.time.LocalDate

private const val JSON_API = "application/vnd.api+json"
private const val VIEWER_URL = "https://kitsu.app/api/edge/users?filter%5Bself%5D=true&fields%5Busers%5D=name"
private const val VIEWER_RESPONSE = """{"data":[{"id":"9","type":"users","attributes":{"name":"reader"}}]}"""

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KitsuTrackerClientTest {
  private val restTemplate = RestTemplate()
  private val server = MockRestServiceServer.createServer(restTemplate)
  private val client = KitsuTrackerClient(TrackerClients(mockk<TrackerRateLimiter>(relaxed = true), restTemplate.requestFactory), ObjectMapper())
  private val account = UserTracker("user", UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw"))
  private val jsonApi = MediaType.parseMediaType(JSON_API)

  private fun entryUrl(mediaId: String) =
    "https://kitsu.app/api/edge/library-entries?filter%5BuserId%5D=9&filter%5BmangaId%5D=$mediaId&page%5Blimit%5D=1&include=manga" +
      "&fields%5BlibraryEntries%5D=progress,status,startedAt,finishedAt,reconsuming,manga&fields%5Bmanga%5D=chapterCount"

  @Test
  fun `given the current user when reading the viewer then the self filter is used and the id is cached for later calls`() {
    server
      .expect(ExpectedCount.once(), requestTo(VIEWER_URL))
      .andExpect(method(HttpMethod.GET))
      .andExpect(header("Authorization", "Bearer token"))
      .andExpect(header("Accept", JSON_API))
      .andRespond(withSuccess(VIEWER_RESPONSE, jsonApi))
    server
      .expect(requestTo(entryUrl("55")))
      .andRespond(withSuccess("""{"data":[],"included":[{"id":"55","type":"manga","attributes":{"chapterCount":10}}]}""", jsonApi))

    assertThat(client.viewer(account, "token")).isEqualTo(RemoteAccount("9", "reader"))
    assertThat(client.entry(account, "token", "55")).isEqualTo(RemoteEntryState(null, 10))
    server.verify()
  }

  @Test
  fun `given a library entry with included manga when reading the entry then the entry and totals are parsed`() {
    server.expect(requestTo(VIEWER_URL)).andRespond(withSuccess(VIEWER_RESPONSE, jsonApi))
    server
      .expect(requestTo(entryUrl("55")))
      .andExpect(header("Authorization", "Bearer token"))
      .andRespond(
        withSuccess(
          """
          {"data":[{"id":"e1","type":"libraryEntries","attributes":{
            "progress":4,"status":"current","reconsuming":false,"startedAt":"2026-01-02T00:00:00.000Z","finishedAt":null
          }}],"included":[{"id":"55","type":"manga","attributes":{"chapterCount":100}}]}
          """.trimIndent(),
          jsonApi,
        ),
      )

    val state = client.entry(account, "token", "55")

    server.verify()
    assertThat(state).isEqualTo(RemoteEntryState(RemoteEntry("55", 4, RemoteStatus.CURRENT, LocalDate.of(2026, 1, 2), null, "e1"), 100))
  }

  @Test
  fun `given no included manga when reading the entry then the manga is fetched for the totals`() {
    server.expect(requestTo(VIEWER_URL)).andRespond(withSuccess(VIEWER_RESPONSE, jsonApi))
    server
      .expect(requestTo(entryUrl("55")))
      .andRespond(withSuccess("""{"data":[{"id":"e1","attributes":{"progress":7,"status":"completed","reconsuming":true}}]}""", jsonApi))
    server
      .expect(requestTo("https://kitsu.app/api/edge/manga/55?fields%5Bmanga%5D=chapterCount"))
      .andRespond(withSuccess("""{"data":{"id":"55","type":"manga","attributes":{"chapterCount":30}}}""", jsonApi))

    val state = client.entry(account, "token", "55")

    server.verify()
    assertThat(state).isEqualTo(RemoteEntryState(RemoteEntry("55", 7, RemoteStatus.REPEATING, null, null, "e1"), 30))
  }

  @Test
  fun `given an existing entry when saving then the entry is patched with JSON API attributes`() {
    server
      .expect(requestTo("https://kitsu.app/api/edge/library-entries/e1"))
      .andExpect(method(HttpMethod.PATCH))
      .andExpect(header("Authorization", "Bearer token"))
      .andExpect(header("Content-Type", startsWith(JSON_API)))
      .andExpect(
        content().json(
          """{"data":{"id":"e1","type":"libraryEntries","attributes":{"progress":10,"status":"completed","finishedAt":"2026-03-04T00:00Z"}}}""",
          JsonCompareMode.STRICT,
        ),
      ).andRespond(withSuccess())

    val current = RemoteEntryState(RemoteEntry("55", 4, RemoteStatus.CURRENT, LocalDate.of(2026, 1, 2), null, "e1"), 10)
    client.save(account, "token", "55", current, EntryUpdate(10, RemoteStatus.COMPLETED, null, LocalDate.of(2026, 3, 4)))

    server.verify()
  }

  @Test
  fun `given no entry when saving then a new entry is created with manga and user relationships`() {
    server.expect(requestTo(VIEWER_URL)).andRespond(withSuccess(VIEWER_RESPONSE, jsonApi))
    server
      .expect(requestTo("https://kitsu.app/api/edge/library-entries"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header("Content-Type", startsWith(JSON_API)))
      .andExpect(
        content().json(
          """
          {"data":{"type":"libraryEntries",
            "attributes":{"progress":3,"status":"current","startedAt":"2026-01-02T00:00Z"},
            "relationships":{"manga":{"data":{"id":"55","type":"manga"}},"user":{"data":{"id":"9","type":"users"}}}}}
          """.trimIndent(),
          JsonCompareMode.STRICT,
        ),
      ).andRespond(withSuccess())

    client.save(account, "token", "55", RemoteEntryState(null, null), EntryUpdate(3, RemoteStatus.CURRENT, LocalDate.of(2026, 1, 2), null))

    server.verify()
  }

  @Test
  fun `given a repeating status when saving then no status is sent`() {
    server
      .expect(requestTo("https://kitsu.app/api/edge/library-entries/e1"))
      .andExpect(content().json("""{"data":{"id":"e1","type":"libraryEntries","attributes":{"progress":2}}}""", JsonCompareMode.STRICT))
      .andRespond(withSuccess())

    val current = RemoteEntryState(RemoteEntry("55", 1, RemoteStatus.REPEATING, null, null, "e1"), null)
    client.save(account, "token", "55", current, EntryUpdate(2, RemoteStatus.REPEATING, null, null))

    server.verify()
  }

  @Test
  fun `given a paged library when reading the library then the next links are followed and statuses are mapped`() {
    server.expect(requestTo(VIEWER_URL)).andRespond(withSuccess(VIEWER_RESPONSE, jsonApi))
    server
      .expect(
        requestTo(
          "https://kitsu.app/api/edge/library-entries?filter%5BuserId%5D=9&filter%5Bkind%5D=manga&page%5Blimit%5D=500" +
            "&fields%5BlibraryEntries%5D=progress,status,startedAt,finishedAt,reconsuming,manga",
        ),
      ).andRespond(
        withSuccess(
          """
          {"data":[
            {"id":"e1","attributes":{"progress":5,"status":"on_hold"},"relationships":{"manga":{"data":{"id":"1","type":"manga"}}}},
            {"id":"e2","attributes":{"progress":0,"status":"planned"},"relationships":{"manga":{"data":null}}}
          ],"links":{"next":"https://kitsu.app/api/edge/library-entries?page[offset]=500"}}
          """.trimIndent(),
          jsonApi,
        ),
      )
    server
      .expect(requestTo("https://kitsu.app/api/edge/library-entries?page%5Boffset%5D=500"))
      .andRespond(
        withSuccess(
          """
          {"data":[
            {"id":"e3","attributes":{"progress":9,"status":"dropped","finishedAt":"2026-02-03T12:00:00.000Z"},"relationships":{"manga":{"data":{"id":"3","type":"manga"}}}},
            {"id":"e4","attributes":{"progress":2,"status":"planned","startedAt":"not a date"},"relationships":{"manga":{"data":{"id":"4","type":"manga"}}}}
          ],"links":{}}
          """.trimIndent(),
          jsonApi,
        ),
      )

    val entries = client.library(account, "token")

    server.verify()
    assertThat(entries).containsExactly(
      RemoteEntry("1", 5, RemoteStatus.PAUSED, null, null, "e1"),
      RemoteEntry("3", 9, RemoteStatus.DROPPED, null, LocalDate.of(2026, 2, 3), "e3"),
      RemoteEntry("4", 2, RemoteStatus.PLANNING, null, null, "e4"),
    )
  }
}
