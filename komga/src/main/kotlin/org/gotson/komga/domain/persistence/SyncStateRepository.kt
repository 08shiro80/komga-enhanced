package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.SyncState
import java.time.LocalDateTime

interface SyncStateRepository {
  fun findByUserIdAndSeriesIdAndTracker(
    userId: String,
    seriesId: String,
    tracker: String,
  ): SyncState?

  fun findByUserIdAndTracker(
    userId: String,
    tracker: String,
  ): Collection<SyncState>

  fun findByTracker(tracker: String): Collection<SyncState>

  fun findByTrackerUpdatedSince(
    tracker: String,
    since: LocalDateTime,
  ): Collection<SyncState>

  fun insert(state: SyncState)

  fun update(state: SyncState)

  fun delete(id: String)

  fun deleteBySeriesId(seriesId: String)
}
