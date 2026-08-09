package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.FollowSchedule
import org.gotson.komga.domain.persistence.FollowScheduleRepository
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class FollowScheduleDao(
  private val dslRW: DSLContext,
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : FollowScheduleRepository {
  private val table = DSL.table("FOLLOW_SCHEDULE")
  private val libraryIdField = DSL.field("LIBRARY_ID", String::class.java)
  private val enabledField = DSL.field("ENABLED", Boolean::class.java)
  private val scheduleModeField = DSL.field("SCHEDULE_MODE", String::class.java)
  private val intervalHoursField = DSL.field("INTERVAL_HOURS", Int::class.java)
  private val checkTimeField = DSL.field("CHECK_TIME", String::class.java)
  private val lastCheckTimeField = DSL.field("LAST_CHECK_TIME", LocalDateTime::class.java)

  override fun findAll(): List<FollowSchedule> =
    dslRO
      .select()
      .from(table)
      .fetch()
      .map { it.toDomain() }

  override fun findByLibraryId(libraryId: String): FollowSchedule? =
    dslRO
      .select()
      .from(table)
      .where(libraryIdField.eq(libraryId))
      .fetchOne()
      ?.toDomain()

  override fun save(schedule: FollowSchedule) {
    dslRW.deleteFrom(table).where(libraryIdField.eq(schedule.libraryId)).execute()
    dslRW
      .insertInto(table)
      .columns(
        libraryIdField,
        enabledField,
        scheduleModeField,
        intervalHoursField,
        checkTimeField,
        lastCheckTimeField,
      ).values(
        schedule.libraryId,
        schedule.enabled,
        schedule.scheduleMode,
        schedule.intervalHours,
        schedule.checkTime,
        schedule.lastCheckTime,
      ).execute()
  }

  override fun delete(libraryId: String) {
    dslRW.deleteFrom(table).where(libraryIdField.eq(libraryId)).execute()
  }

  override fun updateLastCheckTime(
    libraryId: String,
    checkedAt: LocalDateTime,
  ) {
    dslRW
      .update(table)
      .set(lastCheckTimeField, checkedAt)
      .where(libraryIdField.eq(libraryId))
      .execute()
  }

  private fun Record.toDomain(): FollowSchedule =
    FollowSchedule(
      libraryId = get(libraryIdField)!!,
      enabled =
        when (val raw = get(enabledField)) {
          is Boolean -> raw
          is Number -> raw.toInt() != 0
          else -> false
        },
      scheduleMode = get(scheduleModeField) ?: "interval",
      intervalHours = (get(intervalHoursField) as? Number)?.toInt() ?: 24,
      checkTime = get(checkTimeField),
      lastCheckTime = getTimestamp(lastCheckTimeField),
    )

  private fun Record.getTimestamp(field: org.jooq.Field<LocalDateTime?>): LocalDateTime? {
    val raw = get(field.name) ?: return null
    return when (raw) {
      is LocalDateTime -> raw.toCurrentTimeZone()
      is java.sql.Timestamp -> raw.toLocalDateTime()
      is String ->
        try {
          LocalDateTime.parse(raw.replace(" ", "T").substringBefore("+"))
        } catch (_: Exception) {
          LocalDateTime.now()
        }
      else -> LocalDateTime.now()
    }
  }
}
