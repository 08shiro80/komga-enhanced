package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.FollowSchedule
import org.gotson.komga.domain.persistence.FollowScheduleRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

@Service
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
        .forEach {
          try {
            scheduleLibrary(it)
          } catch (e: Exception) {
            logger.warn(e) { "Failed to schedule follow check for library ${it.libraryId}" }
          }
        }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to initialize follow schedules" }
    }
  }

  fun getSchedule(libraryId: String): FollowSchedule = followScheduleRepository.findByLibraryId(libraryId) ?: FollowSchedule(libraryId = libraryId)

  fun updateSchedule(schedule: FollowSchedule): FollowSchedule {
    val merged =
      schedule.copy(
        lastCheckTime = schedule.lastCheckTime ?: followScheduleRepository.findByLibraryId(schedule.libraryId)?.lastCheckTime,
      )
    followScheduleRepository.save(merged)
    tasks.remove(merged.libraryId)?.cancel(false)
    if (merged.enabled) scheduleLibrary(merged)
    logger.info {
      "Follow schedule for library ${merged.libraryId}: enabled=${merged.enabled}, " +
        "mode=${merged.scheduleMode}, interval=${merged.intervalHours}h, time=${merged.checkTime}"
    }
    return merged
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
    tasks.compute(schedule.libraryId) { _, old ->
      old?.cancel(false)
      task
    }
  }

  private fun buildTask(schedule: FollowSchedule): ScheduledFuture<*>? {
    val runnable = Runnable { runCheck(schedule.libraryId) }
    if (schedule.scheduleMode == "fixed_time" && !schedule.checkTime.isNullOrBlank()) {
      val parts = schedule.checkTime.split(":")
      val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()
      val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()
      if (parts.size == 2 && hour != null && minute != null && hour in 0..23 && minute in 0..59) {
        return taskScheduler.schedule(runnable, CronTrigger("0 $minute $hour * * *"))
      }
      logger.warn { "Invalid checkTime '${schedule.checkTime}' for library ${schedule.libraryId}, using interval" }
    }
    val intervalMillis = schedule.intervalHours.coerceAtLeast(1).toLong() * 60 * 60 * 1000L
    // Base the first run on the last recorded check so a check missed while the server was down
    // fires shortly after startup instead of being delayed a full interval (or skipped forever
    // when reboots happen more often than the interval).
    val firstRun =
      schedule.lastCheckTime
        ?.atZone(ZoneId.systemDefault())
        ?.toInstant()
        ?.plusMillis(intervalMillis)
        ?.coerceAtLeast(Instant.now().plusMillis(60_000L))
        ?: Instant.now().plusMillis(intervalMillis)
    return taskScheduler.scheduleAtFixedRate(
      runnable,
      firstRun,
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
