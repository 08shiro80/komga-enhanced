package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.SeriesTrackerId

interface SeriesTrackerIdRepository {
  fun findBySeriesId(seriesId: String): Collection<SeriesTrackerId>

  fun findByTracker(tracker: String): Collection<SeriesTrackerId>

  fun accept(
    seriesId: String,
    tracker: String,
    trackerId: String,
    source: SeriesTrackerId.Source,
  )

  fun reject(
    seriesId: String,
    tracker: String,
    trackerId: String,
  )

  fun findLinkedSeriesUrls(urlPart: String): Collection<Pair<String, String>>
}
