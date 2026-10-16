package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
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
  @Autowired private val dsl: DSLContext,
) {
  private val user1 = KomgaUser("tracker-user1@example.org", "")
  private val user2 = KomgaUser("tracker-user2@example.org", "")
  private val ut = Tables.USER_TRACKER

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

  private fun rawValue(
    userId: String,
    tracker: String,
    key: String,
  ): String? =
    dsl
      .select(ut.CONFIG_VALUE)
      .from(ut)
      .where(ut.USER_ID.eq(userId))
      .and(ut.TRACKER.eq(tracker))
      .and(ut.CONFIG_KEY.eq(key))
      .fetchOne(ut.CONFIG_VALUE)

  private fun insertRaw(
    userId: String,
    tracker: String,
    key: String,
    value: String,
  ) {
    dsl
      .insertInto(ut, ut.USER_ID, ut.TRACKER, ut.CONFIG_KEY, ut.CONFIG_VALUE)
      .values(userId, tracker, key, value)
      .execute()
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
  fun `given an account with secrets when saving then only secrets are stored encrypted and read back as plaintext`() {
    userTrackerDao.save(UserTracker(user1.id, UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "hunter2")))

    val storedPassword = rawValue(user1.id, UserTracker.KITSU, UserTracker.KEY_PASSWORD)

    assertThat(storedPassword).startsWith("enc:v1:").doesNotContain("hunter2")
    assertThat(rawValue(user1.id, UserTracker.KITSU, UserTracker.KEY_USERNAME)).isEqualTo("reader")
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.KITSU)?.settings)
      .isEqualTo(mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "hunter2"))
  }

  @Test
  fun `given plaintext secrets from before the encryption when reading and migrating then values stay readable and get encrypted once`() {
    insertRaw(user1.id, UserTracker.ANILIST, UserTracker.KEY_TOKEN, "legacy-token")
    insertRaw(user1.id, UserTracker.ANILIST, UserTracker.KEY_CLIENT_ID, "client")

    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.ANILIST)?.get(UserTracker.KEY_TOKEN)).isEqualTo("legacy-token")

    val encrypted = userTrackerDao.encryptPlaintextSecrets()
    val storedToken = rawValue(user1.id, UserTracker.ANILIST, UserTracker.KEY_TOKEN)

    assertThat(encrypted).isPositive()
    assertThat(storedToken).startsWith("enc:v1:")
    assertThat(rawValue(user1.id, UserTracker.ANILIST, UserTracker.KEY_CLIENT_ID)).isEqualTo("client")
    assertThat(userTrackerDao.encryptPlaintextSecrets()).isZero()
    assertThat(rawValue(user1.id, UserTracker.ANILIST, UserTracker.KEY_TOKEN)).isEqualTo(storedToken)
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.ANILIST)?.get(UserTracker.KEY_TOKEN)).isEqualTo("legacy-token")
  }

  @Test
  fun `given a secret that cannot be decrypted when reading then the secret is missing and the account is not linked`() {
    insertRaw(user1.id, UserTracker.ANILIST, UserTracker.KEY_TOKEN, "enc:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
    insertRaw(user1.id, UserTracker.ANILIST, UserTracker.KEY_CLIENT_ID, "client")

    val account = userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.ANILIST)

    assertThat(account?.settings).isEqualTo(mapOf(UserTracker.KEY_CLIENT_ID to "client"))
    assertThat(account?.unreadableKeys).containsExactly(UserTracker.KEY_TOKEN)
    assertThat(account?.linked).isFalse()
    assertThat(userTrackerDao.countEncryptedSecrets()).isPositive()
  }

  @Test
  fun `given a linked account when updating the status then only the status keys change and a missing account is not created`() {
    userTrackerDao.save(UserTracker(user1.id, UserTracker.KITSU, mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw")))

    val updated =
      userTrackerDao.updateStatus(
        user1.id,
        UserTracker.KITSU,
        mapOf(UserTracker.KEY_STATUS to UserTracker.STATUS_RECONNECT_REQUIRED, UserTracker.KEY_LAST_ERROR to "rejected"),
      )
    userTrackerDao.updateStatus(user1.id, UserTracker.KITSU, mapOf(UserTracker.KEY_LAST_ERROR to null))
    val missing = userTrackerDao.updateStatus(user2.id, UserTracker.KITSU, mapOf(UserTracker.KEY_STATUS to UserTracker.STATUS_OK))

    assertThat(updated).isTrue()
    assertThat(missing).isFalse()
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.KITSU)?.settings).isEqualTo(
      mapOf(UserTracker.KEY_USERNAME to "reader", UserTracker.KEY_PASSWORD to "pw", UserTracker.KEY_STATUS to UserTracker.STATUS_RECONNECT_REQUIRED),
    )
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.KITSU)?.active).isFalse()
    assertThat(userTrackerDao.findByUserIdAndTracker(user2.id, UserTracker.KITSU)).isNull()
  }

  @Test
  fun `given unchanged refresh token when storing refreshed tokens then both tokens are updated and stored encrypted`() {
    userTrackerDao.save(UserTracker(user1.id, UserTracker.MAL, mapOf(UserTracker.KEY_ACCESS_TOKEN to "old", UserTracker.KEY_REFRESH_TOKEN to "r1")))

    val updated = userTrackerDao.updateRefreshedTokens(user1.id, UserTracker.MAL, "old", "r1", "new", "r2")

    assertThat(updated).isTrue()
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.MAL)?.settings)
      .isEqualTo(mapOf(UserTracker.KEY_ACCESS_TOKEN to "new", UserTracker.KEY_REFRESH_TOKEN to "r2"))
    assertThat(rawValue(user1.id, UserTracker.MAL, UserTracker.KEY_ACCESS_TOKEN)).startsWith("enc:v1:")
    assertThat(rawValue(user1.id, UserTracker.MAL, UserTracker.KEY_REFRESH_TOKEN)).startsWith("enc:v1:")
  }

  @Test
  fun `given plaintext tokens when storing refreshed tokens then the comparison still matches`() {
    insertRaw(user1.id, UserTracker.MAL, UserTracker.KEY_ACCESS_TOKEN, "old")
    insertRaw(user1.id, UserTracker.MAL, UserTracker.KEY_REFRESH_TOKEN, "r1")

    val updated = userTrackerDao.updateRefreshedTokens(user1.id, UserTracker.MAL, "old", "r1", "new", null)

    assertThat(updated).isTrue()
    assertThat(userTrackerDao.findByUserIdAndTracker(user1.id, UserTracker.MAL)?.settings)
      .isEqualTo(mapOf(UserTracker.KEY_ACCESS_TOKEN to "new", UserTracker.KEY_REFRESH_TOKEN to "r1"))
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
  fun `given an account with an unreadable secret when checking existence then it exists without decrypting and other accounts do not`() {
    insertRaw(user1.id, UserTracker.ANILIST, UserTracker.KEY_TOKEN, "enc:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

    assertThat(userTrackerDao.exists(user1.id, UserTracker.ANILIST)).isTrue()
    assertThat(userTrackerDao.exists(user1.id, UserTracker.MAL)).isFalse()
    assertThat(userTrackerDao.exists(user2.id, UserTracker.ANILIST)).isFalse()
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
