package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.SeriesReadState
import org.gotson.komga.domain.persistence.SeriesReadStateRepository
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.language.toCurrentTimeZone
import org.gotson.komga.language.toUTC
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class SeriesReadStateDao(
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : SeriesReadStateRepository {
  private val r = Tables.READ_PROGRESS
  private val b = Tables.BOOK
  private val bm = Tables.BOOK_METADATA
  private val s = Tables.SERIES
  private val sg = Tables.SERIES_METADATA_GENRE
  private val st = Tables.SERIES_METADATA_TAG
  private val oneshotLabels = listOf("oneshot", "one-shot", "one shot")

  override fun findByUserIdAndSeriesId(
    userId: String,
    seriesId: String,
  ): SeriesReadState? = query(userId, b.SERIES_ID.eq(seriesId), null).firstOrNull()

  override fun findByUserIdReadSince(
    userId: String,
    since: LocalDateTime?,
  ): Collection<SeriesReadState> = query(userId, DSL.noCondition(), since)

  private fun query(
    userId: String,
    condition: Condition,
    since: LocalDateTime?,
  ): List<SeriesReadState> {
    val highest = DSL.max(bm.NUMBER_SORT)
    val firstRead = DSL.min(r.READ_DATE)
    val lastRead = DSL.max(r.READ_DATE)
    val lastModified = DSL.max(r.LAST_MODIFIED_DATE)
    val oneshot =
      DSL.field(
        s.ONESHOT.isTrue
          .or(DSL.exists(oneshotLabel(sg, sg.SERIES_ID, sg.GENRE)))
          .or(DSL.exists(oneshotLabel(st, st.SERIES_ID, st.TAG))),
      )
    return dslRO
      .select(b.SERIES_ID, s.LIBRARY_ID, oneshot, highest, firstRead, lastRead)
      .from(r)
      .join(b)
      .on(b.ID.eq(r.BOOK_ID))
      .join(bm)
      .on(bm.BOOK_ID.eq(b.ID))
      .join(s)
      .on(s.ID.eq(b.SERIES_ID))
      .where(r.USER_ID.eq(userId))
      .and(r.COMPLETED.isTrue)
      .and(b.DELETED_DATE.isNull)
      .and(s.DELETED_DATE.isNull)
      .and(condition)
      .groupBy(b.SERIES_ID, s.LIBRARY_ID, s.ONESHOT)
      .having(if (since != null) lastModified.ge(since.toUTC()) else DSL.noCondition())
      .fetch()
      .map {
        SeriesReadState(
          seriesId = it.value1(),
          libraryId = it.value2(),
          oneshot = it.value3(),
          highestNumberSort = it.value4(),
          firstReadDate = it.value5().toCurrentTimeZone(),
          lastReadDate = it.value6().toCurrentTimeZone(),
        )
      }
  }

  private fun oneshotLabel(
    table: Table<*>,
    seriesId: Field<String>,
    label: Field<String>,
  ) = DSL
    .selectOne()
    .from(table)
    .where(seriesId.eq(s.ID))
    .and(DSL.lower(label).`in`(oneshotLabels))
}
