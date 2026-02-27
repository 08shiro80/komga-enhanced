package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.FollowConfig
import org.gotson.komga.domain.persistence.FollowConfigRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Service
@ConditionalOnProperty(
  prefix = "komga.download",
  name = ["enabled"],
  havingValue = "true",
  matchIfMissing = true,
)
class DownloadScheduler(
  private val downloadExecutor: DownloadExecutor,
  private val libraryRepository: LibraryRepository,
  private val followConfigRepository: FollowConfigRepository,
  private val taskScheduler: TaskScheduler,
  private val chapterChecker: ChapterChecker,
) {
  private val lastCheckTimes = ConcurrentHashMap<String, LocalDateTime>()
  private var scheduledTask: ScheduledFuture<*>? = null
  private val isEnabled = AtomicBoolean(false)
  private var currentIntervalHours = 24
  private var currentScheduleMode = "interval"
  private var currentCheckTime: String? = null

  init {
    try {
      val config = followConfigRepository.findDefault()
      if (config != null) {
        isEnabled.set(config.enabled)
        currentIntervalHours = config.checkIntervalHours
        currentScheduleMode = config.scheduleMode
        currentCheckTime = config.checkTime
        if (config.enabled) {
          scheduleFollowCheck(config.checkIntervalHours, config.scheduleMode, config.checkTime)
          logger.info { "Follow config scheduler initialized: enabled=${config.enabled}, mode=${config.scheduleMode}, interval=${config.checkIntervalHours}h, checkTime=${config.checkTime}" }
        }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to load follow config on startup" }
    }
  }

  fun updateSchedule(
    enabled: Boolean,
    intervalHours: Int,
    scheduleMode: String = "interval",
    checkTime: String? = null,
  ) {
    isEnabled.set(enabled)
    currentIntervalHours = intervalHours
    currentScheduleMode = scheduleMode
    currentCheckTime = checkTime

    scheduledTask?.cancel(false)
    scheduledTask = null

    if (enabled) {
      scheduleFollowCheck(intervalHours, scheduleMode, checkTime)
      logger.info { "Follow check schedule updated: mode=$scheduleMode, interval=${intervalHours}h, checkTime=$checkTime" }
    } else {
      logger.info { "Follow check schedule disabled" }
    }
  }

  private fun scheduleFollowCheck(
    intervalHours: Int,
    scheduleMode: String = "interval",
    checkTime: String? = null,
  ) {
    if (scheduleMode == "fixed_time" && !checkTime.isNullOrBlank()) {
      val parts = checkTime.split(":")
      if (parts.size == 2) {
        val hour = parts[0].padStart(2, '0')
        val minute = parts[1].padStart(2, '0')
        val cronExpression = "0 $minute $hour * * *"
        scheduledTask =
          taskScheduler.schedule(
            { checkFollowConfig() },
            CronTrigger(cronExpression),
          )
        logger.info { "Scheduled follow check at $hour:$minute daily (cron: $cronExpression)" }
      } else {
        logger.warn { "Invalid checkTime format: $checkTime, falling back to interval mode" }
        scheduleAtInterval(intervalHours)
      }
    } else {
      scheduleAtInterval(intervalHours)
    }
  }

  private fun scheduleAtInterval(intervalHours: Int) {
    val intervalMillis = intervalHours * 60 * 60 * 1000L
    scheduledTask =
      taskScheduler.scheduleAtFixedRate(
        { checkFollowConfig() },
        Instant.now().plusMillis(intervalMillis),
        Duration.ofMillis(intervalMillis),
      )
    logger.info { "Scheduled follow check every $intervalHours hours" }
  }

  fun processFollowConfigNow(config: FollowConfig) {
    logger.info { "Processing follow config via ChapterChecker: ${config.urls.size} URLs" }

    val summary = chapterChecker.checkAndQueueNewChapters()

    logger.info {
      "Follow config check complete: ${summary.needsDownloadCount} manga need downloads, " +
        "${summary.upToDateCount} up to date, ${summary.errorCount} errors (${summary.durationMs}ms)"
    }

    followConfigRepository.save(config.copy(lastCheckTime = LocalDateTime.now()))
  }

  private fun checkFollowConfig() {
    if (!isEnabled.get()) {
      logger.debug { "Follow check disabled, skipping" }
      return
    }

    try {
      checkForNewChapters()

      val config = followConfigRepository.findDefault()
      if (config != null) {
        followConfigRepository.save(config.copy(lastCheckTime = LocalDateTime.now()))
      }
    } catch (e: Exception) {
      logger.error(e) { "Error in scheduled follow check" }
    }
  }

  fun getLastCheckTime(libraryId: String): LocalDateTime? = lastCheckTimes[libraryId]

  fun checkFollowListNow(libraryId: String) {
    logger.info { "Manual follow list check triggered for library: $libraryId" }

    try {
      val library =
        libraryRepository.findByIdOrNull(libraryId)
          ?: run {
            logger.warn { "Library not found: $libraryId" }
            return
          }

      val followListPath = library.path.resolve("follow.txt")

      if (!followListPath.toFile().exists()) {
        logger.warn { "No follow.txt found in library: ${library.name}" }
        return
      }

      val urls =
        followListPath
          .toFile()
          .readLines()
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") }

      if (urls.isEmpty()) {
        logger.info { "No URLs in follow.txt for library: ${library.name}" }
        return
      }

      logger.info { "Checking ${urls.size} URLs from follow.txt via ChapterChecker" }
      val summary = chapterChecker.checkUrls(urls)

      summary.results
        .filter { it.needsDownload }
        .forEach { result ->
          if (!downloadExecutor.isUrlAlreadyQueued(result.url)) {
            try {
              downloadExecutor.createDownload(
                sourceUrl = result.url,
                libraryId = library.id,
                title = result.title,
                createdBy = "follow-list",
                priority = 5,
              )
              logger.info { "Queued from follow.txt: ${result.title ?: result.url}" }
            } catch (e: Exception) {
              logger.warn(e) { "Failed to queue URL from follow.txt: ${result.url}" }
            }
          }
        }

      lastCheckTimes[libraryId] = LocalDateTime.now()
      logger.info { "Manual check completed for library: ${library.name}" }
    } catch (e: Exception) {
      logger.error(e) { "Error during manual check for library: $libraryId" }
    }
  }

  fun checkForNewChapters() {
    logger.info { "Starting scheduled check for new chapters via ChapterChecker" }

    try {
      val summary = chapterChecker.checkAndQueueNewChapters()
      logger.info {
        "Scheduled check completed: ${summary.needsDownloadCount} need download, " +
          "${summary.upToDateCount} up to date (${summary.durationMs}ms)"
      }

      val libraries = libraryRepository.findAll()
      libraries.forEach { library ->
        val followListPath = library.path.resolve("follow.txt")
        if (followListPath.toFile().exists()) {
          checkFollowListNow(library.id)
        }
      }
    } catch (e: Exception) {
      logger.error(e) { "Error during scheduled chapter check" }
    }
  }
}
