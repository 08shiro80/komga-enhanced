package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.language.toCurrentTimeZone
import org.gotson.komga.language.toUTC
import org.jooq.DSLContext
import org.jooq.impl.DSL
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
  private val ut = Tables.USER_TRACKER

  override fun findByUserIdAndSeriesIdAndTracker(
    userId: String,
    seriesId: String,
    tracker: String,
  ): SyncState? =
    dslRO
      .selectFrom(ss)
      .where(ss.USER_ID.eq(userId))
      .and(ss.SERIES_ID.eq(seriesId))
      .and(ss.TRACKER.eq(tracker))
      .fetchOne()
      ?.toDomain()

  override fun insert(state: SyncState) {
    dslRW
      .insertInto(
        ss,
        ss.ID,
        ss.USER_ID,
        ss.BOOK_ID,
        ss.SERIES_ID,
        ss.TRACKER,
        ss.PROGRESS,
        ss.STATUS,
        ss.SCORE,
        ss.LAST_SYNC_TIMESTAMP,
        ss.LAST_UPDATE_TIMESTAMP,
      ).select(
        DSL
          .select(
            DSL.`val`(state.id, ss.ID),
            DSL.`val`(state.userId, ss.USER_ID),
            DSL.`val`(state.bookId, ss.BOOK_ID),
            DSL.`val`(state.seriesId, ss.SERIES_ID),
            DSL.`val`(state.tracker, ss.TRACKER),
            DSL.`val`(state.progress, ss.PROGRESS),
            DSL.`val`(state.status, ss.STATUS),
            DSL.`val`(state.score, ss.SCORE),
            DSL.`val`(state.lastSyncTimestamp?.toUTC(), ss.LAST_SYNC_TIMESTAMP),
            DSL.`val`(state.lastUpdateTimestamp?.toUTC(), ss.LAST_UPDATE_TIMESTAMP),
          ).whereExists(account(state)),
      ).execute()
  }

  override fun update(state: SyncState) {
    dslRW
      .update(ss)
      .set(ss.BOOK_ID, state.bookId)
      .set(ss.PROGRESS, state.progress)
      .set(ss.STATUS, state.status)
      .set(ss.SCORE, state.score)
      .set(ss.LAST_SYNC_TIMESTAMP, state.lastSyncTimestamp?.toUTC())
      .set(ss.LAST_UPDATE_TIMESTAMP, state.lastUpdateTimestamp?.toUTC())
      .set(ss.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(ss.ID.eq(state.id))
      .andExists(account(state))
      .execute()
  }

  private fun account(state: SyncState) =
    DSL
      .selectOne()
      .from(ut)
      .where(ut.USER_ID.eq(state.userId))
      .and(ut.TRACKER.eq(state.tracker))

  override fun deleteByUserIdAndTracker(
    userId: String,
    tracker: String,
  ) {
    dslRW
      .deleteFrom(ss)
      .where(ss.USER_ID.eq(userId))
      .and(ss.TRACKER.eq(tracker))
      .execute()
  }

  private fun org.gotson.komga.jooq.main.tables.records.SyncStateRecord.toDomain() =
    SyncState(
      id = id,
      userId = userId,
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
