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
) {
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
  }

  @AfterAll
  fun tearDown() {
    syncStateDao.deleteBySeriesId(series.id)
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
    assertThat(syncStateDao.findByUserIdAndTracker(user1.id, UserTracker.ANILIST).map { it.id }).containsExactly("state-1")
    assertThat(syncStateDao.findByTracker(UserTracker.ANILIST)).hasSize(2)
  }
}
