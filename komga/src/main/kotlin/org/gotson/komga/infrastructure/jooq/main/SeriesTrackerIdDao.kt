package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.SeriesTrackerId
import org.gotson.komga.domain.persistence.SeriesTrackerIdRepository
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class SeriesTrackerIdDao(
  private val dslRW: DSLContext,
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : SeriesTrackerIdRepository {
  private val table = DSL.table("SERIES_TRACKER_ID")
  private val seriesIdField = DSL.field("SERIES_ID", String::class.java)
  private val trackerField = DSL.field("TRACKER", String::class.java)
  private val trackerIdField = DSL.field("TRACKER_ID", String::class.java)
  private val statusField = DSL.field("STATUS", String::class.java)
  private val sourceField = DSL.field("SOURCE", String::class.java)
  private val lastModifiedField = DSL.field("LAST_MODIFIED_DATE", LocalDateTime::class.java)
  private val link = Tables.SERIES_METADATA_LINK

  override fun findBySeriesId(seriesId: String): Collection<SeriesTrackerId> =
    dslRO
      .select(seriesIdField, trackerField, trackerIdField, statusField, sourceField)
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .fetch()
      .map { it.toDomain() }

  override fun findByTracker(tracker: String): Collection<SeriesTrackerId> =
    dslRO
      .select(seriesIdField, trackerField, trackerIdField, statusField, sourceField)
      .from(table)
      .where(trackerField.eq(tracker))
      .fetch()
      .map { it.toDomain() }

  @Transactional
  override fun accept(
    seriesId: String,
    tracker: String,
    trackerId: String,
    source: SeriesTrackerId.Source,
  ) {
    dslRW
      .deleteFrom(table)
      .where(seriesIdField.eq(seriesId))
      .and(trackerField.eq(tracker))
      .and(statusField.eq(SeriesTrackerId.Status.ACCEPTED.name))
      .and(trackerIdField.ne(trackerId))
      .execute()
    upsert(seriesId, tracker, trackerId, SeriesTrackerId.Status.ACCEPTED, source)
  }

  @Transactional
  override fun reject(
    seriesId: String,
    tracker: String,
    trackerId: String,
  ) {
    upsert(seriesId, tracker, trackerId, SeriesTrackerId.Status.REJECTED, SeriesTrackerId.Source.MANUAL)
  }

  override fun findLinkedSeriesUrls(urlPart: String): Collection<Pair<String, String>> =
    dslRO
      .select(link.SERIES_ID, link.URL)
      .from(link)
      .where(link.URL.contains(urlPart))
      .fetch()
      .map { it.value1() to it.value2() }

  private fun upsert(
    seriesId: String,
    tracker: String,
    trackerId: String,
    status: SeriesTrackerId.Status,
    source: SeriesTrackerId.Source,
  ) {
    dslRW
      .insertInto(table)
      .columns(seriesIdField, trackerField, trackerIdField, statusField, sourceField)
      .values(seriesId, tracker, trackerId, status.name, source.name)
      .onConflict(seriesIdField, trackerField, trackerIdField)
      .doUpdate()
      .set(statusField, status.name)
      .set(sourceField, source.name)
      .set(lastModifiedField, LocalDateTime.now(ZoneId.of("Z")))
      .execute()
  }

  private fun Record.toDomain() =
    SeriesTrackerId(
      seriesId = get(seriesIdField),
      tracker = get(trackerField),
      trackerId = get(trackerIdField),
      status = SeriesTrackerId.Status.valueOf(get(statusField)),
      source = SeriesTrackerId.Source.valueOf(get(sourceField)),
    )
}
