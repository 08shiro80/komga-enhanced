package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.ComicScrobble
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDateTime

@SpringBootTest
class ComicScrobbleDaoTest(
  @Autowired private val comicScrobbleDao: ComicScrobbleDao,
  @Autowired private val dsl: DSLContext,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val userRepository: KomgaUserRepository,
) {
  private val library = makeLibrary()
  private val series1 = makeSeries("Comic 1", libraryId = library.id)
  private val series2 = makeSeries("Comic 2", libraryId = library.id)
  private val book = makeBook("Comic 1 #1", libraryId = library.id, seriesId = series1.id)
  private val user1 = KomgaUser("scrobble-user1@example.org", "")
  private val user2 = KomgaUser("scrobble-user2@example.org", "")
  private val unlinked = KomgaUser("scrobble-unlinked@example.org", "")

  private val ut = Tables.USER_TRACKER
  private val table = DSL.table("COMIC_SCROBBLE")
  private val metronIssueId = DSL.field("METRON_ISSUE_ID", Int::class.java)
  private val bookId = DSL.field("BOOK_ID", String::class.java)

  @BeforeAll
  fun setup() {
    libraryRepository.insert(library)
    seriesRepository.insert(series1)
    seriesRepository.insert(series2)
    bookRepository.insert(book)
    userRepository.insert(user1)
    userRepository.insert(user2)
    userRepository.insert(unlinked)
    listOf(user1, user2).forEach {
      dsl
        .insertInto(ut, ut.USER_ID, ut.TRACKER, ut.CONFIG_KEY, ut.CONFIG_VALUE)
        .values(it.id, UserTracker.METRON, UserTracker.KEY_USERNAME, "reader")
        .execute()
    }
  }

  @AfterEach
  fun cleanup() {
    comicScrobbleDao.deleteByUserId(user1.id)
    comicScrobbleDao.deleteByUserId(user2.id)
    comicScrobbleDao.deleteByUserId(unlinked.id)
  }

  @AfterAll
  fun tearDown() {
    bookRepository.deleteAll()
    seriesRepository.deleteAll()
    userRepository.deleteAll()
    libraryRepository.deleteAll()
  }

  @Test
  fun `given scrobbles of several users and series when finding issue numbers then only the issues of that user and series are returned`() {
    comicScrobbleDao.insert(ComicScrobble(user1.id, series1.id, "1", 100, book.id, LocalDateTime.of(2026, 3, 4, 10, 0)))
    comicScrobbleDao.insert(ComicScrobble(user1.id, series1.id, "2", 101, null, null))
    comicScrobbleDao.insert(ComicScrobble(user1.id, series2.id, "1", 200, null, null))
    comicScrobbleDao.insert(ComicScrobble(user2.id, series1.id, "3", 102, null, null))

    assertThat(comicScrobbleDao.findIssueNumbers(user1.id, series1.id)).containsExactlyInAnyOrder("1", "2")
    assertThat(comicScrobbleDao.findIssueNumbers(user1.id, series2.id)).containsExactly("1")
    assertThat(comicScrobbleDao.findIssueNumbers(user2.id, series1.id)).containsExactly("3")
    assertThat(comicScrobbleDao.findIssueNumbers(user2.id, series2.id)).isEmpty()
  }

  @Test
  fun `given an issue already scrobbled when inserting it again then the first scrobble is kept`() {
    comicScrobbleDao.insert(ComicScrobble(user1.id, series1.id, "1", 100, book.id, LocalDateTime.of(2026, 3, 4, 10, 0)))
    comicScrobbleDao.insert(ComicScrobble(user1.id, series1.id, "1", 999, null, null))

    assertThat(comicScrobbleDao.findIssueNumbers(user1.id, series1.id)).containsExactly("1")
    val stored = dsl.select(metronIssueId, bookId).from(table).fetch()
    assertThat(stored).hasSize(1)
    assertThat(stored.first().value1()).isEqualTo(100)
    assertThat(stored.first().value2()).isEqualTo(book.id)
  }

  @Test
  fun `given scrobbles of two users when deleting by user then only that user's scrobbles are removed`() {
    comicScrobbleDao.insert(ComicScrobble(user1.id, series1.id, "1", 100, null, null))
    comicScrobbleDao.insert(ComicScrobble(user2.id, series1.id, "1", 100, null, null))

    comicScrobbleDao.deleteByUserId(user1.id)

    assertThat(comicScrobbleDao.findIssueNumbers(user1.id, series1.id)).isEmpty()
    assertThat(comicScrobbleDao.findIssueNumbers(user2.id, series1.id)).containsExactly("1")
  }

  @Test
  fun `given a user without a Metron account when inserting a scrobble then nothing is stored`() {
    dsl
      .insertInto(ut, ut.USER_ID, ut.TRACKER, ut.CONFIG_KEY, ut.CONFIG_VALUE)
      .values(unlinked.id, UserTracker.ANILIST, UserTracker.KEY_CLIENT_ID, "client")
      .execute()

    comicScrobbleDao.insert(ComicScrobble(unlinked.id, series1.id, "1", 100, book.id, LocalDateTime.of(2026, 3, 4, 10, 0)))

    assertThat(comicScrobbleDao.findIssueNumbers(unlinked.id, series1.id)).isEmpty()
  }
}
