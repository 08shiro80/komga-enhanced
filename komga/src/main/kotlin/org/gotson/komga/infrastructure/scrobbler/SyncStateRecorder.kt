package org.gotson.komga.infrastructure.scrobbler

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.SyncState
import org.gotson.komga.domain.persistence.SyncStateRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@Component
class SyncStateRecorder(
  private val syncStateRepository: SyncStateRepository,
  private val userTrackerRepository: UserTrackerRepository,
) {
  fun progress(
    userId: String,
    seriesId: String,
    tracker: String,
  ): Int = syncStateRepository.findByUserIdAndSeriesIdAndTracker(userId, seriesId, tracker)?.progress ?: 0

  fun record(
    userId: String,
    seriesId: String,
    tracker: String,
    progress: Int,
    status: String? = null,
    remoteUpdate: Boolean = false,
  ) = write(userId, seriesId, tracker, progress, status, remoteUpdate, keepHigher = true)

  fun recordRemote(
    userId: String,
    seriesId: String,
    tracker: String,
    progress: Int,
    status: String?,
  ) = write(userId, seriesId, tracker, progress, status, remoteUpdate = true, keepHigher = false)

  private fun write(
    userId: String,
    seriesId: String,
    tracker: String,
    progress: Int,
    status: String?,
    remoteUpdate: Boolean,
    keepHigher: Boolean,
  ) {
    if (!userTrackerRepository.exists(userId, tracker)) return
    val now = LocalDateTime.now()

    fun update(existing: SyncState) {
      syncStateRepository.update(
        existing.copy(
          progress = if (keepHigher) maxOf(existing.progress, progress) else progress,
          status = status ?: existing.status,
          lastSyncTimestamp = now,
          lastUpdateTimestamp = if (remoteUpdate) now else existing.lastUpdateTimestamp,
          lastModifiedDate = now,
        ),
      )
    }

    val existing = syncStateRepository.findByUserIdAndSeriesIdAndTracker(userId, seriesId, tracker)
    if (existing != null) {
      update(existing)
      return
    }
    try {
      syncStateRepository.insert(
        SyncState(
          id = TsidCreator.getTsid256().toString(),
          userId = userId,
          bookId = null,
          seriesId = seriesId,
          tracker = tracker,
          progress = progress,
          status = status,
          lastSyncTimestamp = now,
          lastUpdateTimestamp = if (remoteUpdate) now else null,
        ),
      )
    } catch (e: DataIntegrityViolationException) {
      val raced = syncStateRepository.findByUserIdAndSeriesIdAndTracker(userId, seriesId, tracker)
      if (raced == null) {
        logger.warn(e) { "Could not record the $tracker sync state of user $userId for series $seriesId" }
      } else {
        update(raced)
      }
    }
  }
}
