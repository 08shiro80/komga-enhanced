package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class SyncStateDaoTest(
  @Autowired private val syncStateDao: SyncStateDao,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val dsl: DSLContext,
) {
  private val ut = Tables.USER_TRACKER
  private val library = makeLibrary()
  private val series = makeSeries("Series")
  private val user1 = KomgaUser("sync-user1@example.org", "")
  private val user2 = KomgaUser("sync-user2@example.org", "")

  @BeforeAll
  fun setup() {
    libraryRepository.insert(library)
    seriesRepository.insert(series.copy(libraryId = library.id))
    userRepository.insert(user1)
    userRepository.insert(user2)
    listOf(user1, user2).forEach { user ->
      listOf(UserTracker.ANILIST, UserTracker.KITSU).forEach { linkAccount(user.id, it) }
    }
  }

  private fun linkAccount(
    userId: String,
    tracker: String,
  ) {
    dsl
      .insertInto(ut, ut.USER_ID, ut.TRACKER, ut.CONFIG_KEY, ut.CONFIG_VALUE)
      .values(userId, tracker, UserTracker.KEY_CLIENT_ID, "client")
      .execute()
  }

  private fun unlinkAccount(
    userId: String,
    tracker: String,
  ) {
    dsl
      .deleteFrom(ut)
      .where(ut.USER_ID.eq(userId))
      .and(ut.TRACKER.eq(tracker))
      .execute()
  }

  @AfterAll
  fun tearDown() {
    listOf(user1, user2).forEach { user ->
      listOf(UserTracker.ANILIST, UserTracker.KITSU).forEach { syncStateDao.deleteByUserIdAndTracker(user.id, it) }
    }
    userRepository.deleteAll()
    seriesRepository.deleteAll()
    libraryRepository.deleteAll()
  }

  @Test
  fun `given two users syncing the same series and tracker when finding then each user has their own state`() {
    syncStateDao.insert(SyncState(id = "state-1", userId = user1.id, bookId = null, seriesId = series.id, tracker = UserTracker.ANILIST, progress = 5))
    syncStateDao.insert(SyncState(id = "state-2", userId = user2.id, bookId = null, seriesId = series.id, tracker = UserTracker.ANILIST, progress = 12))

    val state1 = syncStateDao.findByUserIdAndSeriesIdAndTracker(user1.id, series.id, UserTracker.ANILIST)
    val state2 = syncStateDao.findByUserIdAndSeriesIdAndTracker(user2.id, series.id, UserTracker.ANILIST)

    assertThat(state1?.progress).isEqualTo(5)
    assertThat(state2?.progress).isEqualTo(12)
  }

  @Test
  fun `given states of two users when deleting by user and tracker then only that state is removed`() {
    syncStateDao.insert(SyncState(id = "state-3", userId = user1.id, bookId = null, seriesId = series.id, tracker = UserTracker.KITSU, progress = 3))
    syncStateDao.insert(SyncState(id = "state-4", userId = user2.id, bookId = null, seriesId = series.id, tracker = UserTracker.KITSU, progress = 4))

    syncStateDao.deleteByUserIdAndTracker(user1.id, UserTracker.KITSU)

    assertThat(syncStateDao.findByUserIdAndSeriesIdAndTracker(user1.id, series.id, UserTracker.KITSU)).isNull()
    assertThat(syncStateDao.findByUserIdAndSeriesIdAndTracker(user2.id, series.id, UserTracker.KITSU)?.progress).isEqualTo(4)
  }

  @Test
  fun `given no account of the user for the tracker when inserting or updating a state then nothing is written`() {
    syncStateDao.insert(SyncState(id = "state-5", userId = user1.id, bookId = null, seriesId = series.id, tracker = UserTracker.MAL, progress = 3))
    assertThat(syncStateDao.findByUserIdAndSeriesIdAndTracker(user1.id, series.id, UserTracker.MAL)).isNull()

    linkAccount(user1.id, UserTracker.MAL)
    syncStateDao.insert(SyncState(id = "state-6", userId = user1.id, bookId = null, seriesId = series.id, tracker = UserTracker.MAL, progress = 3))
    val stored = syncStateDao.findByUserIdAndSeriesIdAndTracker(user1.id, series.id, UserTracker.MAL)
    assertThat(stored?.progress).isEqualTo(3)

    unlinkAccount(user1.id, UserTracker.MAL)
    syncStateDao.update(stored!!.copy(progress = 9))

    assertThat(syncStateDao.findByUserIdAndSeriesIdAndTracker(user1.id, series.id, UserTracker.MAL)?.progress).isEqualTo(3)
    syncStateDao.deleteByUserIdAndTracker(user1.id, UserTracker.MAL)
  }
}
