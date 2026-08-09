package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.FollowSchedule
import org.gotson.komga.domain.persistence.FollowScheduleRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

@Service
@ConditionalOnProperty(
  prefix = "komga.download",
  name = ["enabled"],
  havingValue = "true",
  matchIfMissing = true,
)
class FollowScheduleLifecycle(
  private val followScheduleRepository: FollowScheduleRepository,
  private val followLifecycle: FollowLifecycle,
  private val taskScheduler: TaskScheduler,
) {
  private val tasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

  @EventListener(ApplicationReadyEvent::class)
  fun init() {
    try {
      followScheduleRepository
        .findAll()
        .filter { it.enabled }
        .forEach { scheduleLibrary(it) }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to initialize follow schedules" }
    }
  }

  fun getSchedule(libraryId: String): FollowSchedule = followScheduleRepository.findByLibraryId(libraryId) ?: FollowSchedule(libraryId = libraryId)

  fun updateSchedule(schedule: FollowSchedule): FollowSchedule {
    followScheduleRepository.save(schedule)
    tasks.remove(schedule.libraryId)?.cancel(false)
    if (schedule.enabled) scheduleLibrary(schedule)
    logger.info {
      "Follow schedule for library ${schedule.libraryId}: enabled=${schedule.enabled}, " +
        "mode=${schedule.scheduleMode}, interval=${schedule.intervalHours}h, time=${schedule.checkTime}"
    }
    return schedule
  }

  fun removeSchedule(libraryId: String) {
    tasks.remove(libraryId)?.cancel(false)
    followScheduleRepository.delete(libraryId)
  }

  @EventListener
  fun onLibraryDeleted(event: DomainEvent.LibraryDeleted) {
    removeSchedule(event.library.id)
  }

  private fun scheduleLibrary(schedule: FollowSchedule) {
    val task = buildTask(schedule) ?: return
    tasks[schedule.libraryId] = task
  }

  private fun buildTask(schedule: FollowSchedule): ScheduledFuture<*>? {
    val runnable = Runnable { runCheck(schedule.libraryId) }
    if (schedule.scheduleMode == "fixed_time" && !schedule.checkTime.isNullOrBlank()) {
      val parts = schedule.checkTime.split(":")
      if (parts.size == 2) {
        val hour = parts[0].padStart(2, '0')
        val minute = parts[1].padStart(2, '0')
        return taskScheduler.schedule(runnable, CronTrigger("0 $minute $hour * * *"))
      }
      logger.warn { "Invalid checkTime '${schedule.checkTime}' for library ${schedule.libraryId}, using interval" }
    }
    val intervalMillis = schedule.intervalHours.coerceAtLeast(1) * 60 * 60 * 1000L
    return taskScheduler.scheduleAtFixedRate(
      runnable,
      Instant.now().plusMillis(intervalMillis),
      Duration.ofMillis(intervalMillis),
    )
  }

  private fun runCheck(libraryId: String) {
    try {
      val queued = followLifecycle.checkNow(libraryId)
      followScheduleRepository.updateLastCheckTime(libraryId, LocalDateTime.now())
      logger.info { "Scheduled follow check for library $libraryId queued $queued download(s)" }
    } catch (e: Exception) {
      logger.error(e) { "Scheduled follow check failed for library $libraryId" }
    }
  }
}
