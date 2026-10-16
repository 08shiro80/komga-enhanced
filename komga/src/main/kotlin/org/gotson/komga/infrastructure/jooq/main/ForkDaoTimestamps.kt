package org.gotson.komga.infrastructure.jooq.main

import org.jooq.Field
import org.jooq.Record
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * Read a nullable timestamp column written by the hand-written fork DAOs (ChapterUrl / Follow /
 * FollowSchedule / BlacklistedChapter / IgnoredOversizedPage). These store `LocalDateTime` locally (no
 * jOOQ converter), so the raw value may come back as `LocalDateTime`, `java.sql.Timestamp`, or a `String`
 * depending on the driver. Shared here to keep the fork DAOs in sync (previously an identical private copy lived in each).
 */
fun Record.getTimestamp(field: Field<LocalDateTime?>): LocalDateTime? {
  val raw = get(field.name) ?: return null
  return when (raw) {
    is LocalDateTime -> raw
    is Timestamp -> raw.toLocalDateTime()
    is String ->
      try {
        LocalDateTime.parse(raw.replace(" ", "T").substringBefore("+"))
      } catch (_: Exception) {
        LocalDateTime.now()
      }
    else -> LocalDateTime.now()
  }
}
