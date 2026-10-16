package org.gotson.komga.interfaces.api.rest

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class UserTrackerControllerTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val userTrackerRepository: UserTrackerRepository,
) {
  private val user1 = KomgaUser("tracker-api1@example.org", "", id = "tracker-api1")
  private val user2 = KomgaUser("tracker-api2@example.org", "", id = "tracker-api2")

  @BeforeAll
  fun setup() {
    userRepository.insert(user1)
    userRepository.insert(user2)
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

  @Test
  @WithAnonymousUser
  fun `given anonymous user when listing trackers then unauthorized`() {
    mockMvc
      .get("/api/v1/trackers")
      .andExpect { status { isUnauthorized() } }
  }

  @Test
  @WithMockCustomUser(id = "tracker-api1")
  fun `given linked account when listing trackers then secrets are never returned`() {
    mockMvc
      .put("/api/v1/trackers/mal") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"settings":{"access_token":"secret-access","client_id":"my-client","client_secret":"secret-client"}}"""
      }.andExpect { status { isNoContent() } }

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
        content = """{"enabled":false,"settings":{}}"""
      }.andExpect { status { isNoContent() } }

    val account = userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.ANILIST)
    assertThat(account?.get(UserTracker.KEY_TOKEN)).isEqualTo("keep-me")
    assertThat(account?.enabled).isFalse()
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
  fun `given linked account when unlinking then the account is removed`() {
    userTrackerRepository.save(UserTracker(user1.id, UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "u", UserTracker.KEY_PASSWORD to "p")))

    mockMvc
      .delete("/api/v1/trackers/metron")
      .andExpect { status { isNoContent() } }

    assertThat(userTrackerRepository.findByUserIdAndTracker(user1.id, UserTracker.METRON)).isNull()
  }
}
