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
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MalTrackerClientTest {
  private val restTemplate = RestTemplate()
  private val server = MockRestServiceServer.createServer(restTemplate)
  private val client = MalTrackerClient(TrackerClients(mockk<TrackerRateLimiter>(relaxed = true), restTemplate.requestFactory), ObjectMapper())
  private val account = UserTracker("user", UserTracker.MAL, mapOf(UserTracker.KEY_ACCESS_TOKEN to "token"))

  @Test
  fun `given the current user when reading the viewer then id and name are parsed`() {
    server
      .expect(requestTo("https://api.myanimelist.net/v2/users/@me"))
      .andExpect(method(HttpMethod.GET))
      .andExpect(header("Authorization", "Bearer token"))
      .andRespond(withSuccess("""{"id":1234,"name":"reader"}""", MediaType.APPLICATION_JSON))

    assertThat(client.viewer(account, "token")).isEqualTo(RemoteAccount("1234", "reader"))
    server.verify()
  }

  @Test
  fun `given a list status when reading the entry then the fields are requested and the entry is parsed`() {
    server
      .expect(requestTo("https://api.myanimelist.net/v2/manga/2?fields=num_chapters,my_list_status"))
      .andExpect(method(HttpMethod.GET))
      .andExpect(header("Authorization", "Bearer token"))
      .andRespond(
        withSuccess(
          """
          {"id":2,"num_chapters":0,"my_list_status":{
            "status":"reading","is_rereading":false,"num_chapters_read":30,"start_date":"2026-01-02"
          }}
          """.trimIndent(),
          MediaType.APPLICATION_JSON,
        ),
      )

    val state = client.entry(account, "token", "2")

    server.verify()
    assertThat(state).isEqualTo(RemoteEntryState(RemoteEntry("2", 30, RemoteStatus.CURRENT, LocalDate.of(2026, 1, 2), null), null))
  }

  @Test
  fun `given no list status when reading the entry then no entry is returned`() {
    server
      .expect(requestTo("https://api.myanimelist.net/v2/manga/2?fields=num_chapters,my_list_status"))
      .andRespond(withSuccess("""{"id":2,"num_chapters":120}""", MediaType.APPLICATION_JSON))

    assertThat(client.entry(account, "token", "2")).isEqualTo(RemoteEntryState(null, 120))
    server.verify()
  }

  @Test
  fun `given an update when saving then the list status is patched as a form`() {
    server
      .expect(requestTo("https://api.myanimelist.net/v2/manga/2/my_list_status"))
      .andExpect(method(HttpMethod.PATCH))
      .andExpect(header("Authorization", "Bearer token"))
      .andExpect(header("Content-Type", startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)))
      .andExpect(content().string("num_chapters_read=31&status=completed"))
      .andRespond(withSuccess())

    client.save(account, "token", "2", RemoteEntryState(null, 31), EntryUpdate(31, RemoteStatus.COMPLETED, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 3, 4)))

    server.verify()
  }

  @Test
  fun `given a rereading status when saving then the chapters are sent without a status`() {
    server
      .expect(requestTo("https://api.myanimelist.net/v2/manga/2/my_list_status"))
      .andExpect(content().string("num_chapters_read=4"))
      .andRespond(withSuccess())

    client.save(account, "token", "2", RemoteEntryState(null, null), EntryUpdate(4, RemoteStatus.REPEATING, null, null))

    server.verify()
  }

  @Test
  fun `given a paged manga list when reading the library then all pages are followed and statuses are mapped`() {
    server
      .expect(requestTo("https://api.myanimelist.net/v2/users/@me/mangalist?fields=list_status&limit=1000&nsfw=true"))
      .andExpect(header("Authorization", "Bearer token"))
      .andRespond(
        withSuccess(
          """
          {"data":[
            {"node":{"id":1},"list_status":{"status":"reading","is_rereading":true,"num_chapters_read":5,"num_volumes_read":0}},
            {"node":{"id":2},"list_status":{"status":"plan_to_read","num_chapters_read":0,"num_volumes_read":0,"finish_date":"2026-02-30"}},
            {"node":{"id":3}}
          ],"paging":{"next":"https://api.myanimelist.net/v2/users/@me/mangalist?offset=1000&fields=list_status&limit=1000&nsfw=true"}}
          """.trimIndent(),
          MediaType.APPLICATION_JSON,
        ),
      )
    server
      .expect(requestTo("https://api.myanimelist.net/v2/users/@me/mangalist?offset=1000&fields=list_status&limit=1000&nsfw=true"))
      .andRespond(
        withSuccess(
          """
          {"data":[
            {"node":{"id":4},"list_status":{"status":"completed","num_chapters_read":50,"num_volumes_read":5,"start_date":"2025-01-01","finish_date":"2025-06-30"}},
            {"node":{"id":5},"list_status":{"status":"on_hold","num_chapters_read":2,"num_volumes_read":0}},
            {"node":{"id":6},"list_status":{"status":"dropped","num_chapters_read":1,"num_volumes_read":0}}
          ],"paging":{}}
          """.trimIndent(),
          MediaType.APPLICATION_JSON,
        ),
      )

    val entries = client.library(account, "token")

    server.verify()
    assertThat(entries).containsExactly(
      RemoteEntry("1", 5, RemoteStatus.REPEATING, null, null),
      RemoteEntry("2", 0, RemoteStatus.PLANNING, null, null),
      RemoteEntry("4", 50, RemoteStatus.COMPLETED, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
      RemoteEntry("5", 2, RemoteStatus.PAUSED, null, null),
      RemoteEntry("6", 1, RemoteStatus.DROPPED, null, null),
    )
  }
}
