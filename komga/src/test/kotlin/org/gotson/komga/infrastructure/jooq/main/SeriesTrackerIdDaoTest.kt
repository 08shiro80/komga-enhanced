package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.SeriesTrackerId
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI

@SpringBootTest
class SeriesTrackerIdDaoTest(
  @Autowired private val seriesTrackerIdDao: SeriesTrackerIdDao,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesMetadataRepository: SeriesMetadataRepository,
  @Autowired private val libraryRepository: LibraryRepository,
) {
  private val library = makeLibrary()
  private val series = makeSeries("Tracked", libraryId = library.id)

  @BeforeAll
  fun setup() {
    libraryRepository.insert(library)
    seriesRepository.insert(series)
    seriesMetadataRepository.insert(SeriesMetadata(title = series.name, seriesId = series.id))
  }

  @AfterAll
  fun tearDown() {
    seriesMetadataRepository.delete(series.id)
    seriesRepository.deleteAll()
    libraryRepository.deleteAll()
  }

  @Test
  fun `given accepted and rejected IDs when storing then only one ID per tracker is accepted and rejections are kept`() {
    seriesTrackerIdDao.accept(series.id, UserTracker.ANILIST, "100107", SeriesTrackerId.Source.SEARCH)
    seriesTrackerIdDao.accept(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Source.MANGADEX)
    seriesTrackerIdDao.reject(series.id, UserTracker.ANILIST, "100107")
    seriesTrackerIdDao.accept(series.id, UserTracker.MAL, "109696", SeriesTrackerId.Source.LINK)

    assertThat(seriesTrackerIdDao.findBySeriesId(series.id)).containsExactlyInAnyOrder(
      SeriesTrackerId(series.id, UserTracker.ANILIST, "100114", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.MANGADEX),
      SeriesTrackerId(series.id, UserTracker.ANILIST, "100107", SeriesTrackerId.Status.REJECTED, SeriesTrackerId.Source.MANUAL),
      SeriesTrackerId(series.id, UserTracker.MAL, "109696", SeriesTrackerId.Status.ACCEPTED, SeriesTrackerId.Source.LINK),
    )
    assertThat(seriesTrackerIdDao.findByTracker(UserTracker.MAL).map { it.trackerId }).containsExactly("109696")
  }

  @Test
  fun `given series links when searching linked series by URL part then the series and URL are returned`() {
    val metadata = seriesMetadataRepository.findById(series.id)
    seriesMetadataRepository.update(metadata.copy(links = listOf(WebLink("AniList", URI("https://anilist.co/manga/30002")))))

    assertThat(seriesTrackerIdDao.findLinkedSeriesUrls("anilist.co/manga/")).containsExactly(series.id to "https://anilist.co/manga/30002")
  }
}
