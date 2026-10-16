package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.SyncState

interface SyncStateRepository {
  fun findByUserIdAndSeriesIdAndTracker(
    userId: String,
    seriesId: String,
    tracker: String,
  ): SyncState?

  fun insert(state: SyncState)

  fun update(state: SyncState)

  fun deleteByUserIdAndTracker(
    userId: String,
    tracker: String,
  )
}
