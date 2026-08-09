package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.FollowSchedule
import java.time.LocalDateTime

interface FollowScheduleRepository {
  fun findAll(): List<FollowSchedule>

  fun findByLibraryId(libraryId: String): FollowSchedule?

  fun save(schedule: FollowSchedule)

  fun delete(libraryId: String)

  fun updateLastCheckTime(
    libraryId: String,
    checkedAt: LocalDateTime,
  )
}
