package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class SyncStateDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  SyncStateRepository {
  private val ss = Tables.SYNC_STATE

  override fun findBySeriesIdAndTracker(seriesId: String, tracker: String): SyncState? =
    dslRO
      .selectFrom(ss)
      .where(ss.SERIES_ID.eq(seriesId))
      .and(ss.TRACKER.eq(tracker))
      .fetchOne()
      ?.toDomain()

  override fun findByTracker(tracker: String): Collection<SyncState> =
    dslRO
      .selectFrom(ss)
      .where(ss.TRACKER.eq(tracker))
      .fetch()
      .map { it.toDomain() }

  override fun findByTrackerUpdatedSince(tracker: String, since: LocalDateTime): Collection<SyncState> =
    dslRO
      .selectFrom(ss)
      .where(ss.TRACKER.eq(tracker))
      .and(ss.LAST_UPDATE_TIMESTAMP.greaterOrEqual(since))
      .fetch()
      .map { it.toDomain() }

  override fun insert(state: SyncState) {
    dslRW
      .insertInto(
        ss,
        ss.ID,
        ss.BOOK_ID,
        ss.SERIES_ID,
        ss.TRACKER,
        ss.PROGRESS,
        ss.STATUS,
        ss.SCORE,
        ss.LAST_SYNC_TIMESTAMP,
        ss.LAST_UPDATE_TIMESTAMP,
      ).values(
        state.id,
        state.bookId,
        state.seriesId,
        state.tracker,
        state.progress,
        state.status,
        state.score,
        state.lastSyncTimestamp,
        state.lastUpdateTimestamp,
      ).execute()
  }

  override fun update(state: SyncState) {
    dslRW
      .update(ss)
      .set(ss.BOOK_ID, state.bookId)
      .set(ss.PROGRESS, state.progress)
      .set(ss.STATUS, state.status)
      .set(ss.SCORE, state.score)
      .set(ss.LAST_SYNC_TIMESTAMP, state.lastSyncTimestamp)
      .set(ss.LAST_UPDATE_TIMESTAMP, state.lastUpdateTimestamp)
      .set(ss.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(ss.ID.eq(state.id))
      .execute()
  }

  override fun delete(id: String) {
    dslRW
      .deleteFrom(ss)
      .where(ss.ID.eq(id))
      .execute()
  }

  override fun deleteBySeriesId(seriesId: String) {
    dslRW
      .deleteFrom(ss)
      .where(ss.SERIES_ID.eq(seriesId))
      .execute()
  }

  private fun org.gotson.komga.jooq.main.tables.records.SyncStateRecord.toDomain() =
    SyncState(
      id = id,
      bookId = bookId,
      seriesId = seriesId,
      tracker = tracker,
      progress = progress,
      status = status,
      score = score,
      lastSyncTimestamp = lastSyncTimestamp?.toCurrentTimeZone(),
      lastUpdateTimestamp = lastUpdateTimestamp?.toCurrentTimeZone(),
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
