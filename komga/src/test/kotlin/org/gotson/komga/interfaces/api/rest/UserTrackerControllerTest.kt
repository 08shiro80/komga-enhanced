package org.gotson.komga.interfaces.api.rest

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.scrobbler.MangaScrobblerPlugin
import org.gotson.komga.infrastructure.scrobbler.TrackerAuthException
import org.gotson.komga.infrastructure.scrobbler.TrackerAuthorizationProvider
import org.gotson.komga.infrastructure.scrobbler.TrackerConnectionChecker
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimitedException
import org.gotson.komga.infrastructure.scrobbler.TrackerStatusRecorder
import org.gotson.komga.infrastructure.scrobbler.TrackerTokenProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.net.URI

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class UserTrackerControllerTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val userTrackerRepository: UserTrackerRepository,
  @Autowired private val trackerAuthorizationProvider: TrackerAuthorizationProvider,
) {
  @MockkBean(relaxed = true)
  private lateinit var trackerTokenProvider: TrackerTokenProvider

  @MockkBean(relaxed = true)
  private lateinit var trackerConnectionChecker: TrackerConnectionChecker

  @MockkBean(relaxed = true)
  private lateinit var trackerStatusRecorder: TrackerStatusRecorder

  @MockkBean(relaxed = true)
  private lateinit var mangaScrobblerPlugin: MangaScrobblerPlugin

  private val user1 = KomgaUser("tracker-api1@example.org", "", id = "tracker-api1")
  private val user2 = KomgaUser("tracker-api2@example.org", "", id = "tracker-api2")

  private val redirectUri = "http://localhost/api/v1/trackers/anilist/callback"

  @BeforeAll
  fun setup() {
    userRepository.insert(user1)
    userRepository.insert(user2)
  }

  @BeforeEach
  fun stubTokenExpiry() {
    every { trackerTokenProvider.tokenExpiry(any()) } returns null
  }

  @AfterEach
  fun cleanup() {
    UserTracker.trackers.forEach {
      userTrackerRepository.delete(user1.id, it)
      userTrackerRepository.delete(user2.id, it)
    }
  }

  @AfterAll
  fun tearDown() {
    userRepository.deleteAll()
  }

  private fun saveAnilistClient(userId: String) {
    userTrackerRepository.save(
      UserTracker(userId, UserTracker.ANILIST, mapOf(UserTracker.KEY_CLIENT_ID to "client-1", UserTracker.KEY_CLIENT_SECRET to "secret-1")),
    )
  }

  private fun startAnilistAuthorization(
    userId: String,
    returnTo: String = "/account/trackers",
  ): String {
    val url =
      trackerAuthorizationProvider.start(
        UserTracker(userId, UserTracker.ANILIST, mapOf(UserTracker.KEY_CLIENT_ID to "client-1", UserTracker.KEY_CLIENT_SECRET to "secret-1")),
        redirectUri,
        returnTo,
      )
    return URI(url)
      .rawQuery
      .split("&")
      .first { it.startsWith("state=") }
      .removePrefix("state=")
  }

  @Test
  @WithAnonymousUser
  fun `given anonymous user when listing trackers then unauthorized and the callback sends the browser back to the trackers page`() {
    mockMvc
      .get("/api/v1/trackers")
      .andExpect { status { isUnauthorized() } }

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=c&state=s")
      .andExpect {
        status { isFound() }
        header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=session_expired") }
      }

    mockMvc
      .post("/api/v1/trackers/anilist/authorize") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"returnTo":"/account/trackers"}"""
      }.andExpect { status { isUnauthorized() } }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given linked account when listing trackers then secrets are never returned`() {
    userTrackerRepository.save(
      UserTracker(
        user1.id,
        UserTracker.MAL,
        mapOf(UserTracker.KEY_ACCESS_TOKEN to "secret-access", UserTracker.KEY_CLIENT_ID to "my-client", UserTracker.KEY_CLIENT_SECRET to "secret-client"),
      ),
    )

    mockMvc
      .get("/api/v1/trackers")
      .andExpect {
        status { isOk() }
        jsonPath("$.length()") { value(UserTracker.trackers.size) }
        jsonPath("$[?(@.tracker == 'mal')].linked") { value(true) }
        jsonPath("$[?(@.tracker == 'mal')].enabled") { value(true) }
        jsonPath("$[?(@.tracker == 'mal')].settings.client_id") { value("my-client") }
        jsonPath("$[?(@.tracker == 'mal')].settings.access_token") { doesNotExist() }
        jsonPath("$[?(@.tracker == 'mal')].settings.client_secret") { doesNotExist() }
        jsonPath("$[?(@.tracker == 'mal')].authorizationRedirectUri") { value("http://localhost/api/v1/trackers/mal/callback") }
        jsonPath("$[?(@.tracker == 'anilist')].authorizationRedirectUri") { value(redirectUri) }
        jsonPath("$[?(@.tracker == 'mal')].status.state") { value("unknown") }
        jsonPath("$[?(@.tracker == 'anilist')].status.state") { value("not_linked") }
        jsonPath("$[?(@.tracker == 'mal')].scrobblerEnabled") { value(false) }
        content { string(not(containsString("secret-"))) }
      }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api2")
  fun `given account of another user when listing trackers then it is not visible`() {
    userTrackerRepository.save(UserTracker(user1.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "user1-token")))

    mockMvc
      .get("/api/v1/trackers")
      .andExpect {
        status { isOk() }
        jsonPath("$[?(@.tracker == 'anilist')].linked") { value(false) }
      }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given omitted secret when updating then the stored secret is kept`() {
    userTrackerRepository.save(UserTracker(user1.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "keep-me")))

    mockMvc
      .put("/api/v1/trackers/anilist") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"enabled":false,"settings":{"client_id":"client-1"}}"""
      }.andExpect { status { isNoContent() } }

    val account = userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.ANILIST)
    assertThat(account?.get(UserTracker.KEY_TOKEN)).isEqualTo("keep-me")
    assertThat(account?.get(UserTracker.KEY_CLIENT_ID)).isEqualTo("client-1")
    assertThat(account?.enabled).isFalse()
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given an unlinked tracker when switching it off then the paused state is kept`() {
    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"enabled":false}"""
      }.andExpect { status { isNoContent() } }

    assertThat(userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.KITSU)?.settings)
      .isEqualTo(mapOf(UserTracker.KEY_ENABLED to "false"))
    mockMvc
      .get("/api/v1/trackers")
      .andExpect {
        status { isOk() }
        jsonPath("$[?(@.tracker == 'kitsu')].enabled") { value(false) }
        jsonPath("$[?(@.tracker == 'kitsu')].linked") { value(false) }
      }

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"enabled":true}"""
      }.andExpect { status { isNoContent() } }

    assertThat(userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.KITSU)).isNull()
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a linked tracker when switching it off and on then only the enabled flag changes`() {
    userTrackerRepository.save(UserTracker(user1.id, UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw")))

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"enabled":false}"""
      }.andExpect { status { isNoContent() } }

    val paused = userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.KITSU)
    assertThat(paused?.enabled).isFalse()
    assertThat(paused?.linked).isTrue()
    assertThat(paused?.active).isFalse()
    assertThat(paused?.get(UserTracker.KEY_PASSWORD)).isEqualTo("pw")
    verify(exactly = 0) { mangaScrobblerPlugin.clearIdentityBackoff(any(), any()) }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a paused linked tracker when all credentials are removed then the account is unlinked`() {
    userTrackerRepository.save(
      UserTracker(user1.id, UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw", UserTracker.KEY_ENABLED to "false")),
    )

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"enabled":false,"settings":{"username":null,"password":""}}"""
      }.andExpect { status { isNoContent() } }

    assertThat(userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.KITSU)).isNull()
    verify { trackerStatusRecorder.resetSyncState(user1.id, UserTracker.KITSU) }
    verify { trackerTokenProvider.invalidate(match { it.userId == user1.id && it.tracker == UserTracker.KITSU }) }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given unknown setting or tracker when updating then request is rejected`() {
    mockMvc
      .put("/api/v1/trackers/anilist") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"password":"x"}}"""
      }.andExpect { status { isBadRequest() } }

    mockMvc
      .put("/api/v1/trackers/anilist") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"token":"manual"}}"""
      }.andExpect { status { isBadRequest() } }

    mockMvc
      .put("/api/v1/trackers/mal") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"access_token":"manual"}}"""
      }.andExpect { status { isBadRequest() } }

    mockMvc
      .put("/api/v1/trackers/unknown") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{}}"""
      }.andExpect { status { isNotFound() } }

    mockMvc
      .put("/api/v1/trackers/mangadex") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"max_content_rating":"everything"}}"""
      }.andExpect { status { isBadRequest() } }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given Kitsu when updating then only username and password are accepted and link the account`() {
    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"access_token":"manual"}}"""
      }.andExpect { status { isBadRequest() } }

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"username":"reader@example.org","password":"secret-kitsu"}}"""
      }.andExpect { status { isNoContent() } }

    mockMvc
      .get("/api/v1/trackers")
      .andExpect {
        status { isOk() }
        jsonPath("$[?(@.tracker == 'kitsu')].linked") { value(true) }
        jsonPath("$[?(@.tracker == 'kitsu')].settings.username") { value("reader@example.org") }
        content { string(not(containsString("secret-"))) }
      }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given Metron with an API token or only part of the login when listing then linked follows the alternatives`() {
    userTrackerRepository.save(UserTracker(user1.id, UserTracker.METRON, mapOf(UserTracker.KEY_TOKEN to "metron-token")))

    mockMvc
      .get("/api/v1/trackers")
      .andExpect { jsonPath("$[?(@.tracker == 'metron')].linked") { value(true) } }

    userTrackerRepository.save(UserTracker(user1.id, UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "u")))

    mockMvc
      .get("/api/v1/trackers")
      .andExpect { jsonPath("$[?(@.tracker == 'metron')].linked") { value(false) } }

    userTrackerRepository.save(UserTracker(user1.id, UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "u", UserTracker.KEY_PASSWORD to "p")))

    mockMvc
      .get("/api/v1/trackers")
      .andExpect { jsonPath("$[?(@.tracker == 'metron')].linked") { value(true) } }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given an account that must be connected again when the credentials change then the status is cleared but a pause keeps it`() {
    userTrackerRepository.save(
      UserTracker(
        user1.id,
        UserTracker.KITSU,
        mapOf(
          UserTracker.KEY_USERNAME to "reader",
          UserTracker.KEY_PASSWORD to "old",
          UserTracker.KEY_STATUS to UserTracker.STATUS_RECONNECT_REQUIRED,
          UserTracker.KEY_STATUS_MESSAGE to "kitsu rejected the login",
          UserTracker.KEY_LAST_ERROR to "kitsu rejected the login",
        ),
      ),
    )

    mockMvc
      .get("/api/v1/trackers")
      .andExpect {
        jsonPath("$[?(@.tracker == 'kitsu')].status.state") { value(UserTracker.STATUS_RECONNECT_REQUIRED) }
        jsonPath("$[?(@.tracker == 'kitsu')].settings.status") { doesNotExist() }
      }

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"enabled":false}"""
      }.andExpect { status { isNoContent() } }
    assertThat(userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.KITSU)?.reconnectRequired).isTrue()

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"password":"new"}}"""
      }.andExpect { status { isNoContent() } }

    val updated = userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.KITSU)
    assertThat(updated?.reconnectRequired).isFalse()
    assertThat(updated?.get(UserTracker.KEY_LAST_ERROR)).isEqualTo("kitsu rejected the login")
    assertThat(updated?.identityCheckRequired).isTrue()
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a MangaDex account that must be connected again when only the content rating or the enabled flag changes then the status is kept`() {
    userTrackerRepository.save(
      UserTracker(
        user1.id,
        UserTracker.MANGADEX,
        mapOf(
          UserTracker.KEY_USERNAME to "reader",
          UserTracker.KEY_PASSWORD to "pw",
          UserTracker.KEY_CLIENT_ID to "id",
          UserTracker.KEY_CLIENT_SECRET to "secret",
          UserTracker.KEY_STATUS to UserTracker.STATUS_RECONNECT_REQUIRED,
        ),
      ),
    )

    mockMvc
      .put("/api/v1/trackers/mangadex") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"max_content_rating":"safe"}}"""
      }.andExpect { status { isNoContent() } }
    mockMvc
      .put("/api/v1/trackers/mangadex") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"enabled":true}"""
      }.andExpect { status { isNoContent() } }

    val updated = userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.MANGADEX)
    assertThat(updated?.get(UserTracker.KEY_MAX_CONTENT_RATING)).isEqualTo("safe")
    assertThat(updated?.reconnectRequired).isTrue()
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a linked account when an update removes every credential then the account, its sync state and its cached token are removed`() {
    userTrackerRepository.save(UserTracker(user1.id, UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw")))

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"username":null,"password":null}}"""
      }.andExpect { status { isNoContent() } }

    assertThat(userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.KITSU)).isNull()
    verify { trackerStatusRecorder.resetSyncState(user1.id, UserTracker.KITSU) }
    verify { trackerTokenProvider.invalidate(match { it.userId == user1.id && it.tracker == UserTracker.KITSU }) }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given an account that must be connected again when the same password is saved again then the status is cleared and the identity is checked`() {
    userTrackerRepository.save(
      UserTracker(
        user1.id,
        UserTracker.KITSU,
        mapOf(
          UserTracker.KEY_USERNAME to "reader",
          UserTracker.KEY_PASSWORD to "same",
          UserTracker.KEY_STATUS to UserTracker.STATUS_RECONNECT_REQUIRED,
          UserTracker.KEY_STATUS_MESSAGE to "kitsu rejected the login",
          UserTracker.KEY_ACCOUNT_NAME to "reader",
          UserTracker.KEY_REMOTE_USER_ID to "42",
        ),
      ),
    )

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"password":"same"}}"""
      }.andExpect { status { isNoContent() } }

    val updated = userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.KITSU)
    assertThat(updated?.reconnectRequired).isFalse()
    assertThat(updated?.get(UserTracker.KEY_STATUS_MESSAGE)).isNull()
    assertThat(updated?.identityCheckRequired).isTrue()
    assertThat(updated?.get(UserTracker.KEY_REMOTE_USER_ID)).isEqualTo("42")
    assertThat(updated?.get(UserTracker.KEY_ACCOUNT_NAME)).isEqualTo("reader")
    verify { trackerTokenProvider.invalidate(match { it.userId == user1.id && it.tracker == UserTracker.KITSU }) }
    verify(exactly = 0) { trackerStatusRecorder.resetSyncState(any(), any()) }
    verify(exactly = 1) { mangaScrobblerPlugin.clearIdentityBackoff(user1.id, UserTracker.KITSU) }

    mockMvc
      .put("/api/v1/trackers/kitsu") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"identity_check":null}}"""
      }.andExpect { status { isBadRequest() } }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a Metron account when only the API token changes then nothing is reset, another username resets the sync state`() {
    userTrackerRepository.save(
      UserTracker(user1.id, UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "comics", UserTracker.KEY_PASSWORD to "p", UserTracker.KEY_TOKEN to "old")),
    )

    mockMvc
      .put("/api/v1/trackers/metron") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"token":"new","username":"comics"}}"""
      }.andExpect { status { isNoContent() } }

    verify(exactly = 0) { trackerStatusRecorder.resetSyncState(any(), any()) }
    assertThat(userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.METRON)?.identityCheckRequired).isFalse()

    mockMvc
      .put("/api/v1/trackers/metron") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"username":"other-comics"}}"""
      }.andExpect { status { isNoContent() } }

    verify(exactly = 1) { trackerStatusRecorder.resetSyncState(user1.id, UserTracker.METRON) }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given an unlinked account or a disabled scrobbler when syncing now then the request is rejected`() {
    mockMvc
      .post("/api/v1/trackers/anilist/sync")
      .andExpect { status { isBadRequest() } }

    userTrackerRepository.save(UserTracker(user1.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t")))

    mockMvc
      .post("/api/v1/trackers/anilist/sync")
      .andExpect {
        status {
          isBadRequest()
          reason(containsString("manga-scrobbler"))
        }
      }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given linked account when unlinking then the account is removed`() {
    userTrackerRepository.save(UserTracker(user1.id, UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "u", UserTracker.KEY_PASSWORD to "p")))

    mockMvc
      .delete("/api/v1/trackers/metron")
      .andExpect { status { isNoContent() } }

    assertThat(userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.METRON)).isNull()
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given configured clients when starting the authorization then the provider URL is returned`() {
    saveAnilistClient(user1.id)
    userTrackerRepository.save(UserTracker(user1.id, UserTracker.MAL, mapOf(UserTracker.KEY_CLIENT_ID to "mal-client")))

    mockMvc
      .post("/api/v1/trackers/anilist/authorize") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"returnTo":"/account/trackers"}"""
      }.andExpect {
        status { isOk() }
        jsonPath("$.authorizationUrl") { value(startsWith("https://anilist.co/api/v2/oauth/authorize?response_type=code&client_id=client-1&redirect_uri=")) }
        jsonPath("$.authorizationUrl") { value(containsString("state=")) }
        jsonPath("$.authorizationUrl") { value(not(containsString("secret-1"))) }
      }

    mockMvc
      .post("/api/v1/trackers/mal/authorize") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"returnTo":"/account/trackers"}"""
      }.andExpect {
        status { isOk() }
        jsonPath("$.authorizationUrl") { value(startsWith("https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=mal-client")) }
        jsonPath("$.authorizationUrl") { value(containsString("code_challenge_method=plain")) }
      }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given missing client or unsupported tracker when starting the authorization then request is rejected`() {
    mockMvc
      .post("/api/v1/trackers/anilist/authorize") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"returnTo":"/account/trackers"}"""
      }.andExpect { status { isBadRequest() } }

    userTrackerRepository.save(UserTracker(user1.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_CLIENT_ID to "client-1")))

    mockMvc
      .post("/api/v1/trackers/anilist/authorize") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"returnTo":"/account/trackers"}"""
      }.andExpect { status { isBadRequest() } }

    mockMvc
      .post("/api/v1/trackers/kitsu/authorize") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"returnTo":"/account/trackers"}"""
      }.andExpect { status { isBadRequest() } }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a return path outside the application when starting the authorization then request is rejected`() {
    saveAnilistClient(user1.id)

    listOf("https://evil.example/x", "//evil.example/x", "/\\evil.example", "account/trackers", "/account?x=1").forEach { returnTo ->
      mockMvc
        .post("/api/v1/trackers/anilist/authorize") {
          contentType = MediaType.APPLICATION_JSON
          content = """{"returnTo":"${returnTo.replace("\\", "\\\\")}"}"""
        }.andExpect { status { isBadRequest() } }
    }

    listOf("/other/account/trackers", "/komga/%zz", "/komga/%2").forEach { returnTo ->
      mockMvc
        .post("/komga/api/v1/trackers/anilist/authorize") {
          contextPath = "/komga"
          contentType = MediaType.APPLICATION_JSON
          content = """{"returnTo":"$returnTo"}"""
        }.andExpect { status { isBadRequest() } }
    }

    mockMvc
      .post("/komga/api/v1/trackers/anilist/authorize") {
        contextPath = "/komga"
        contentType = MediaType.APPLICATION_JSON
        content = """{"returnTo":"/komga/next/account/trackers"}"""
      }.andExpect {
        status { isOk() }
        jsonPath("$.authorizationUrl") { value(containsString("redirect_uri=http%3A%2F%2Flocalhost%2Fkomga%2Fapi%2Fv1%2Ftrackers%2Fanilist%2Fcallback")) }
      }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a valid state when the provider returns a code then the code is exchanged once and the browser is sent back`() {
    saveAnilistClient(user1.id)
    every { trackerTokenProvider.completeAuthorization(any(), any(), any(), any()) } answers { firstArg() }
    val state = startAnilistAuthorization(user1.id)

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=the-code&state=$state")
      .andExpect {
        status { isFound() }
        header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=success") }
      }

    verify(exactly = 1) {
      trackerTokenProvider.completeAuthorization(match { it.userId == user1.id && it.tracker == UserTracker.ANILIST }, "the-code", redirectUri, null)
    }

    verify(exactly = 1) { trackerConnectionChecker.check(any()) }
    verify(exactly = 1) { mangaScrobblerPlugin.clearIdentityBackoff(user1.id, UserTracker.ANILIST) }

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=the-code&state=$state")
      .andExpect {
        status { isFound() }
        header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=invalid_state") }
      }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a failing exchange or a denied login when the callback is opened then the browser is sent back with an error`() {
    saveAnilistClient(user1.id)
    every { trackerTokenProvider.completeAuthorization(any(), any(), any(), any()) } throws TrackerAuthException("anilist token request failed (HTTP 400)")

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=bad&state=${startAnilistAuthorization(user1.id)}")
      .andExpect {
        status { isFound() }
        header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=exchange_failed") }
      }

    mockMvc
      .get("/api/v1/trackers/anilist/callback?error=access_denied&state=${startAnilistAuthorization(user1.id)}")
      .andExpect {
        status { isFound() }
        header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=denied") }
      }

    mockMvc
      .get("/komga/api/v1/trackers/anilist/callback?error=access_denied&state=${startAnilistAuthorization(user1.id, "/komga/next/account/trackers")}") {
        contextPath = "/komga"
      }.andExpect {
        status { isFound() }
        header { string("Location", "/komga/next/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=denied") }
      }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given a rate limit or an unexpected failure during the exchange when the callback is opened then the browser is sent back with an error`() {
    saveAnilistClient(user1.id)
    every { trackerTokenProvider.completeAuthorization(any(), any(), any(), any()) } throws TrackerRateLimitedException("anilist is rate limited, try again later", null)

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=c&state=${startAnilistAuthorization(user1.id)}")
      .andExpect {
        status { isFound() }
        header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=rate_limited") }
      }

    every { trackerTokenProvider.completeAuthorization(any(), any(), any(), any()) } throws DataAccessResourceFailureException("database is locked")

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=c&state=${startAnilistAuthorization(user1.id)}")
      .andExpect {
        status { isFound() }
        header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=exchange_failed") }
      }
  }

  @Test
  @WithAnonymousUser
  fun `given a tracker without authorization flow or an unknown tracker when the callback is opened then the browser is sent back with an error`() {
    mockMvc
      .get("/api/v1/trackers/kitsu/callback?code=c&state=s")
      .andExpect {
        status { isFound() }
        header { string("Location", "/account/trackers?trackerAuth=kitsu&trackerAuthResult=error&trackerAuthError=unsupported_tracker") }
      }

    mockMvc
      .get("/komga/api/v1/trackers/unknown tracker/callback") {
        contextPath = "/komga"
      }.andExpect {
        status { isFound() }
        header { string("Location", "/komga/account/trackers?trackerAuth=unknown%20tracker&trackerAuthResult=error&trackerAuthError=unsupported_tracker") }
      }

    verify(exactly = 0) { trackerTokenProvider.completeAuthorization(any(), any(), any(), any()) }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api2")
  fun `given a state of another user or tracker when the callback is opened then nothing is exchanged`() {
    val state = startAnilistAuthorization(user1.id)

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=stolen&state=$state")
      .andExpect { header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=state_mismatch") } }

    mockMvc
      .get("/api/v1/trackers/mal/callback?code=stolen&state=$state")
      .andExpect { header { string("Location", "/account/trackers?trackerAuth=mal&trackerAuthResult=error&trackerAuthError=state_mismatch") } }

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=stolen&state=unknown")
      .andExpect { header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=invalid_state") } }

    mockMvc
      .get("/api/v1/trackers/anilist/callback?code=stolen")
      .andExpect { header { string("Location", "/account/trackers?trackerAuth=anilist&trackerAuthResult=error&trackerAuthError=invalid_state") } }

    verify(exactly = 0) { trackerTokenProvider.completeAuthorization(any(), any(), any(), any()) }
  }
}
