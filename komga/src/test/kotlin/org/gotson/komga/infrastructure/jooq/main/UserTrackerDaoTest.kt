package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class UserTrackerDaoTest(
  @Autowired private val userTrackerDao: UserTrackerDao,
  @Autowired private val userRepository: KomgaUserRepository,
) {
  private val user1 = KomgaUser("tracker-user1@example.org", "")
  private val user2 = KomgaUser("tracker-user2@example.org", "")

  @BeforeAll
  fun setup() {
    userRepository.insert(user1)
    userRepository.insert(user2)
  }

  @AfterEach
  fun cleanup() {
    UserTracker.trackers.forEach {
      userTrackerDao.delete(user1.id, it)
      userTrackerDao.delete(user2.id, it)
    }
  }

  @AfterAll
  fun tearDown() {
    userRepository.deleteAll()
  }

  @Test
  fun `given accounts of two users when finding by user then each user only gets their own accounts`() {
    userTrackerDao.save(UserTracker(user1.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "token-1")))
    userTrackerDao.save(UserTracker(user2.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "token-2")))

    val forUser1 = userTrackerDao.findByUserId(user1.id)
    val forUser2 = userTrackerDao.findByUserIdAndTracker(user2.id, UserTracker.ANILIST)

    assertThat(forUser1).hasSize(1)
    assertThat(forUser1.first()[UserTracker.KEY_TOKEN]).isEqualTo("token-1")
    assertThat(forUser2?.get(UserTracker.KEY_TOKEN)).isEqualTo("token-2")
    assertThat(userTrackerDao.findByTracker(UserTracker.ANILIST)).hasSize(2)
  }

  @Test
  fun `given existing account when saving then all previous settings are replaced`() {
    userTrackerDao.save(
      UserTracker(user1.id, UserTracker.MAL, mapOf(UserTracker.KEY_ACCESS_TOKEN to "a", UserTracker.KEY_REFRESH_TOKEN to "r")),
    )
    userTrackerDao.save(UserTracker(user1.id, UserTracker.MAL, mapOf(UserTracker.KEY_ACCESS_TOKEN to "b")))

    val account = userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.MAL)

    assertThat(account?.settings).containsExactlyEntriesOf(mapOf(UserTracker.KEY_ACCESS_TOKEN to "b"))
  }

  @Test
  fun `given unchanged refresh token when storing refreshed tokens then both tokens are updated`() {
    userTrackerDao.save(UserTracker(user1.id, UserTracker.KITSU, mapOf(UserTracker.KEY_ACCESS_TOKEN to "old", UserTracker.KEY_REFRESH_TOKEN to "r1")))

    val updated = userTrackerDao.updateRefreshedTokens(user1.id, UserTracker.KITSU, "old", "r1", "new", "r2")

    assertThat(updated).isTrue()
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.KITSU)?.settings)
      .isEqualTo(mapOf(UserTracker.KEY_ACCESS_TOKEN to "new", UserTracker.KEY_REFRESH_TOKEN to "r2"))
  }

  @Test
  fun `given replaced or unlinked account when storing refreshed tokens then nothing is written`() {
    userTrackerDao.save(UserTracker(user1.id, UserTracker.MAL, mapOf(UserTracker.KEY_ACCESS_TOKEN to "a", UserTracker.KEY_REFRESH_TOKEN to "replaced")))

    val updatedReplaced = userTrackerDao.updateRefreshedTokens(user1.id, UserTracker.MAL, "a", "r1", "stale", "stale-r")
    val updatedNewAccess = userTrackerDao.updateRefreshedTokens(user1.id, UserTracker.MAL, "previous", "replaced", "stale", "stale-r")
    val updatedUnlinked = userTrackerDao.updateRefreshedTokens(user1.id, UserTracker.KITSU, "a", "r1", "stale", "stale-r")

    assertThat(updatedReplaced).isFalse()
    assertThat(updatedNewAccess).isFalse()
    assertThat(updatedUnlinked).isFalse()
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.MAL)?.get(UserTracker.KEY_ACCESS_TOKEN)).isEqualTo("a")
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.KITSU)).isNull()
  }

  @Test
  fun `given account when deleting then only that tracker of that user is removed`() {
    userTrackerDao.save(UserTracker(user1.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t")))
    userTrackerDao.save(UserTracker(user1.id, UserTracker.METRON, mapOf(UserTracker.KEY_USERNAME to "u", UserTracker.KEY_PASSWORD to "p")))

    userTrackerDao.delete(user1.id, UserTracker.ANILIST)

    assertThat(userTrackerDao.findByUserId(user1.id).map { it.tracker }).containsExactly(UserTracker.METRON)
  }

  @Test
  fun `given account when the user is deleted then the account is deleted too`() {
    val user = KomgaUser("tracker-deleted@example.org", "")
    userRepository.insert(user)
    userTrackerDao.save(UserTracker(user.id, UserTracker.ANILIST, mapOf(UserTracker.KEY_TOKEN to "t")))

    userRepository.delete(user.id)

    assertThat(userTrackerDao.findByUserId(user.id)).isEmpty()
  }
}
