package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.SeriesReadState
import java.time.LocalDateTime

interface SeriesReadStateRepository {
  fun findByUserIdAndSeriesId(
    userId: String,
    seriesId: String,
  ): SeriesReadState?

  fun findByUserIdReadSince(
    userId: String,
    since: LocalDateTime?,
  ): Collection<SeriesReadState>
}
