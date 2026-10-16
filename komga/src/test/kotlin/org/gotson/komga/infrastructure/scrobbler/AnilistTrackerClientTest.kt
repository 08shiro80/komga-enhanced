package org.gotson.komga.infrastructure.scrobbler

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.UserTracker
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class AnilistTrackerClientTest {
  private val restTemplate = RestTemplate()
  private val server = MockRestServiceServer.createServer(restTemplate)
  private val client = AnilistTrackerClient(TrackerClients(mockk<TrackerRateLimiter>(relaxed = true), restTemplate.requestFactory), ObjectMapper())
  private val account = UserTracker("user", UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "token"))

  @Test
  fun `given a list entry when reading the entry then the media id is sent as an integer and the entry is parsed`() {
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header("Authorization", "Bearer token"))
      .andExpect(header("Content-Type", startsWith(MediaType.APPLICATION_JSON_VALUE)))
      .andExpect(jsonPath("$.variables.id").value(100114))
      .andExpect(jsonPath("$.query").value(containsString("Media(id: \$id)")))
      .andExpect(jsonPath("$.query").value(containsString("mediaListEntry { id progress status")))
      .andRespond(
        withSuccess(
          """
          {"data":{"Media":{"chapters":50,"mediaListEntry":{
            "id":777,"progress":12,"status":"CURRENT",
            "startedAt":{"year":2026,"month":1,"day":null},
            "completedAt":{"year":null,"month":null,"day":null}
          }}}}
          """.trimIndent(),
          MediaType.APPLICATION_JSON,
        ),
      )

    val state = client.entry(account, "token", "100114")

    server.verify()
    assertThat(state).isEqualTo(
      RemoteEntryState(RemoteEntry("100114", 12, RemoteStatus.CURRENT, LocalDate.of(2026, 1, 1), null, "777"), 50),
    )
  }

  @Test
  fun `given no list entry when reading the entry then only the totals are returned`() {
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andRespond(withSuccess("""{"data":{"Media":{"chapters":40,"mediaListEntry":null}}}""", MediaType.APPLICATION_JSON))

    assertThat(client.entry(account, "token", "100114")).isEqualTo(RemoteEntryState(null, 40))
    server.verify()
  }

  @Test
  fun `given an unknown media when reading the entry then not found is raised`() {
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andRespond(withSuccess("""{"data":{"Media":null}}""", MediaType.APPLICATION_JSON))

    assertThatThrownBy { client.entry(account, "token", "1") }.isInstanceOf(TrackerNotFoundException::class.java)
  }

  @Test
  fun `given GraphQL errors when calling AniList then they are classified by status and message`() {
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andRespond(withSuccess("""{"errors":[{"message":"Too Many Requests.","status":429}],"data":null}""", MediaType.APPLICATION_JSON))
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andRespond(withSuccess("""{"errors":[{"message":"Invalid token","status":400}],"data":null}""", MediaType.APPLICATION_JSON))
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andRespond(withSuccess("""{"errors":[{"message":"validation","status":400}],"data":null}""", MediaType.APPLICATION_JSON))

    assertThatThrownBy { client.entry(account, "token", "1") }.isInstanceOf(TrackerRateLimitedException::class.java)
    assertThatThrownBy { client.entry(account, "token", "1") }.isInstanceOf(TrackerUnauthorizedException::class.java)
    assertThatThrownBy { client.entry(account, "token", "1") }.isInstanceOf(TrackerRejectedException::class.java)
    server.verify()
  }

  @Test
  fun `given an update when saving then only the set fields are declared and sent`() {
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header("Authorization", "Bearer token"))
      .andExpect(
        jsonPath("$.query").value(
          "mutation (\$mediaId: Int, \$progress: Int, \$status: MediaListStatus, \$completedAt: FuzzyDateInput) " +
            "{ SaveMediaListEntry(mediaId: \$mediaId, progress: \$progress, status: \$status, completedAt: \$completedAt) { id progress } }",
        ),
      ).andExpect(
        content().json(
          """{"variables":{"mediaId":100114,"progress":10,"status":"COMPLETED","completedAt":{"year":2026,"month":3,"day":4}}}""",
          JsonCompareMode.LENIENT,
        ),
      ).andExpect(jsonPath("$.variables.startedAt").doesNotExist())
      .andRespond(withSuccess("""{"data":{"SaveMediaListEntry":{"id":777,"progress":10}}}""", MediaType.APPLICATION_JSON))

    client.save(account, "token", "100114", RemoteEntryState(null, 10), EntryUpdate(10, RemoteStatus.COMPLETED, null, LocalDate.of(2026, 3, 4)))

    server.verify()
  }

  @Test
  fun `given a library in several chunks when reading the library then all chunks are read and duplicates removed`() {
    val linked = account.copy(settings = account.settings + (UserTracker.KEY_REMOTE_USER_ID to "42"))
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andExpect(jsonPath("$.variables.userId").value(42))
      .andExpect(jsonPath("$.variables.chunk").value(1))
      .andExpect(jsonPath("$.variables.perChunk").value(500))
      .andExpect(jsonPath("$.query").value(containsString("MediaListCollection(userId: \$userId, type: MANGA")))
      .andRespond(
        withSuccess(
          """
          {"data":{"MediaListCollection":{"hasNextChunk":true,"lists":[{"entries":[
            {"mediaId":1,"progress":3,"status":"CURRENT","startedAt":{"year":2025,"month":5,"day":6},"completedAt":{"year":null}},
            {"mediaId":2,"progress":7,"status":"PAUSED","startedAt":null,"completedAt":null}
          ]}]}}}
          """.trimIndent(),
          MediaType.APPLICATION_JSON,
        ),
      )
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andExpect(jsonPath("$.variables.chunk").value(2))
      .andRespond(
        withSuccess(
          """
          {"data":{"MediaListCollection":{"hasNextChunk":false,"lists":[{"entries":[
            {"mediaId":2,"progress":7,"status":"PAUSED"},
            {"mediaId":3,"progress":20,"status":"UNKNOWN","completedAt":{"year":2026,"month":2,"day":3}}
          ]}]}}}
          """.trimIndent(),
          MediaType.APPLICATION_JSON,
        ),
      )

    val entries = client.library(linked, "token")

    server.verify()
    assertThat(entries).containsExactly(
      RemoteEntry("1", 3, RemoteStatus.CURRENT, LocalDate.of(2025, 5, 6), null),
      RemoteEntry("2", 7, RemoteStatus.PAUSED, null, null),
      RemoteEntry("3", 20, null, null, LocalDate.of(2026, 2, 3)),
    )
  }

  @Test
  fun `given no stored remote user when reading the library then the viewer is queried first`() {
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andExpect(jsonPath("$.query").value("{ Viewer { id name } }"))
      .andRespond(withSuccess("""{"data":{"Viewer":{"id":42,"name":"reader"}}}""", MediaType.APPLICATION_JSON))
    server
      .expect(requestTo("https://graphql.anilist.co"))
      .andExpect(jsonPath("$.variables.userId").value(42))
      .andRespond(withSuccess("""{"data":{"MediaListCollection":{"hasNextChunk":false,"lists":[]}}}""", MediaType.APPLICATION_JSON))

    assertThat(client.library(account, "token")).isEmpty()
    server.verify()
  }
}
