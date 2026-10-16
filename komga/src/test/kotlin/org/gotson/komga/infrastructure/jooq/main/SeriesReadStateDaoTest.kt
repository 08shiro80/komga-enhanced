package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.SeriesReadState
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDateTime

@SpringBootTest
class SeriesReadStateDaoTest(
  @Autowired private val seriesReadStateDao: SeriesReadStateDao,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val bookMetadataRepository: BookMetadataRepository,
  @Autowired private val readProgressRepository: ReadProgressRepository,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val seriesMetadataRepository: SeriesMetadataRepository,
) {
  private val library = makeLibrary()
  private val series = makeSeries("Series", libraryId = library.id)
  private val oneshot = makeSeries("Oneshot", libraryId = library.id).copy(oneshot = true)
  private val deletedSeries = makeSeries("Deleted", libraryId = library.id).copy(deletedDate = LocalDateTime.now())
  private val user1 = KomgaUser("read-state-user1@example.org", "")
  private val user2 = KomgaUser("read-state-user2@example.org", "")
  private val user3 = KomgaUser("read-state-user3@example.org", "")
  private val genreOneshot = makeSeries("Genre Oneshot", libraryId = library.id)
  private val tagOneshot = makeSeries("Tag Oneshot", libraryId = library.id)
  private val spacedOneshot = makeSeries("Spaced Oneshot", libraryId = library.id)
  private val singleBook = makeSeries("Single Book", libraryId = library.id)
  private val labelled =
    mapOf(
      genreOneshot to SeriesMetadata(title = genreOneshot.name, seriesId = genreOneshot.id, genres = setOf("Oneshot")),
      tagOneshot to SeriesMetadata(title = tagOneshot.name, seriesId = tagOneshot.id, tags = setOf("One-Shot")),
      spacedOneshot to SeriesMetadata(title = spacedOneshot.name, seriesId = spacedOneshot.id, genres = setOf("Drama"), tags = setOf("one shot")),
      singleBook to SeriesMetadata(title = singleBook.name, seriesId = singleBook.id, genres = setOf("Action"), tags = setOf("shot")),
    )
  private val labelledBooks = labelled.keys.map { makeBook("${it.name} 1", libraryId = library.id, seriesId = it.id) }

  private val first = makeBook("c001", libraryId = library.id, seriesId = series.id)
  private val second = makeBook("c002.5", libraryId = library.id, seriesId = series.id)
  private val inProgress = makeBook("c003", libraryId = library.id, seriesId = series.id)
  private val deletedBook = makeBook("c010", libraryId = library.id, seriesId = series.id).copy(deletedDate = LocalDateTime.now())
  private val oneshotBook = makeBook("oneshot", libraryId = library.id, seriesId = oneshot.id)
  private val bookOfDeletedSeries = makeBook("deleted", libraryId = library.id, seriesId = deletedSeries.id)

  private val firstRead = LocalDateTime.of(2026, 1, 2, 10, 0)
  private val lastRead = LocalDateTime.of(2026, 3, 4, 10, 0)

  @BeforeAll
  fun setup() {
    libraryRepository.insert(library)
    seriesRepository.insert(series)
    seriesRepository.insert(oneshot)
    seriesRepository.insert(deletedSeries)
    userRepository.insert(user1)
    userRepository.insert(user2)
    val numbers =
      mapOf(
        first to 1F,
        second to 2.5F,
        inProgress to 3F,
        deletedBook to 10F,
        oneshotBook to 0F,
        bookOfDeletedSeries to 7F,
      )
    numbers.forEach { (book, number) ->
      bookRepository.insert(book)
      bookMetadataRepository.insert(BookMetadata(title = book.name, number = number.toString(), numberSort = number, bookId = book.id))
    }
    readProgressRepository.save(
      listOf(
        ReadProgress(first.id, user1.id, 1, true, firstRead),
        ReadProgress(second.id, user1.id, 1, true, lastRead),
        ReadProgress(inProgress.id, user1.id, 1, false, lastRead.plusDays(1)),
        ReadProgress(deletedBook.id, user1.id, 1, true, lastRead.plusDays(2)),
        ReadProgress(oneshotBook.id, user1.id, 1, true, firstRead),
        ReadProgress(bookOfDeletedSeries.id, user1.id, 1, true, firstRead),
        ReadProgress(first.id, user2.id, 1, true, lastRead),
      ),
    )
    userRepository.insert(user3)
    labelled.forEach { (labelledSeries, metadata) ->
      seriesRepository.insert(labelledSeries)
      seriesMetadataRepository.insert(metadata)
    }
    labelledBooks.forEach { book ->
      bookRepository.insert(book)
      bookMetadataRepository.insert(BookMetadata(title = book.name, number = "1", numberSort = 1F, bookId = book.id))
    }
    readProgressRepository.save(labelledBooks.map { ReadProgress(it.id, user3.id, 1, true, firstRead) })
  }

  @AfterAll
  fun tearDown() {
    readProgressRepository.deleteAll()
    seriesMetadataRepository.delete(labelled.keys.map { it.id })
    bookMetadataRepository.delete((listOf(first, second, inProgress, deletedBook, oneshotBook, bookOfDeletedSeries) + labelledBooks).map { it.id })
    bookRepository.deleteAll()
    seriesRepository.deleteAll()
    userRepository.deleteAll()
    libraryRepository.deleteAll()
  }

  @Test
  fun `given completed books when finding the read state of a series then the highest number and read dates of completed live books are returned`() {
    assertThat(seriesReadStateDao.findByUserIdAndSeriesId(user1.id, series.id))
      .isEqualTo(SeriesReadState(series.id, library.id, false, 2.5F, firstRead, lastRead))
  }

  @Test
  fun `given two users when finding the read state of a series then each user only sees their own progress`() {
    assertThat(seriesReadStateDao.findByUserIdAndSeriesId(user2.id, series.id))
      .isEqualTo(SeriesReadState(series.id, library.id, false, 1F, lastRead, lastRead))
    assertThat(seriesReadStateDao.findByUserIdAndSeriesId(user2.id, oneshot.id)).isNull()
  }

  @Test
  fun `given a deleted series when finding the read state then it is not returned`() {
    assertThat(seriesReadStateDao.findByUserIdAndSeriesId(user1.id, deletedSeries.id)).isNull()
  }

  @Test
  fun `given no lower bound when finding read states then every live series with a completed book is returned`() {
    assertThat(seriesReadStateDao.findByUserIdReadSince(user1.id, null)).containsExactlyInAnyOrder(
      SeriesReadState(series.id, library.id, false, 2.5F, firstRead, lastRead),
      SeriesReadState(oneshot.id, library.id, true, 0F, firstRead, firstRead),
    )
  }

  @Test
  fun `given a lower bound when finding read states then only series modified since then are returned`() {
    assertThat(seriesReadStateDao.findByUserIdReadSince(user1.id, LocalDateTime.now().minusHours(1)).map { it.seriesId })
      .containsExactlyInAnyOrder(series.id, oneshot.id)
    assertThat(seriesReadStateDao.findByUserIdReadSince(user1.id, LocalDateTime.now().plusHours(1))).isEmpty()
  }

  @Test
  fun `given series with a oneshot genre or tag when finding read states then they are oneshots and a single book series without one is not`() {
    assertThat(seriesReadStateDao.findByUserIdReadSince(user3.id, null).associate { it.seriesId to it.oneshot }).isEqualTo(
      mapOf(
        genreOneshot.id to true,
        tagOneshot.id to true,
        spacedOneshot.id to true,
        singleBook.id to false,
      ),
    )
    assertThat(seriesReadStateDao.findByUserIdAndSeriesId(user3.id, tagOneshot.id)?.oneshot).isTrue()
  }
}
