package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.SyncState
import java.time.LocalDateTime

interface SyncStateRepository {
  fun findBySeriesIdAndTracker(seriesId: String, tracker: String): SyncState?

  fun findByTracker(tracker: String): Collection<SyncState>

  fun findByTrackerUpdatedSince(tracker: String, since: LocalDateTime): Collection<SyncState>

  fun insert(state: SyncState)

  fun update(state: SyncState)

  fun delete(id: String)

  fun deleteBySeriesId(seriesId: String)
}
