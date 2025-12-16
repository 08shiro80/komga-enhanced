package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.FollowConfig
import org.gotson.komga.domain.persistence.FollowConfigRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.Scheduled
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
  private val downloadService: DownloadService,
  private val libraryRepository: LibraryRepository,
  private val followConfigRepository: FollowConfigRepository,
  private val taskScheduler: TaskScheduler,
) {
  private val lastCheckTimes = ConcurrentHashMap<String, LocalDateTime>()
  private var scheduledTask: ScheduledFuture<*>? = null
  private val isEnabled = AtomicBoolean(false)
  private var currentIntervalHours = 24

  init {
    // Load config on startup and schedule if enabled
    try {
      val config = followConfigRepository.findDefault()
      if (config != null) {
        isEnabled.set(config.enabled)
        currentIntervalHours = config.checkIntervalHours
        if (config.enabled) {
          scheduleFollowCheck(config.checkIntervalHours)
          logger.info { "Follow config scheduler initialized: enabled=${config.enabled}, interval=${config.checkIntervalHours}h" }
        }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to load follow config on startup" }
    }
  }

  /**
   * Update the schedule based on new config
   */
  fun updateSchedule(
    enabled: Boolean,
    intervalHours: Int,
  ) {
    isEnabled.set(enabled)
    currentIntervalHours = intervalHours

    // Cancel existing schedule
    scheduledTask?.cancel(false)
    scheduledTask = null

    if (enabled) {
      scheduleFollowCheck(intervalHours)
      logger.info { "Follow check schedule updated: interval=${intervalHours}h" }
    } else {
      logger.info { "Follow check schedule disabled" }
    }
  }

  private fun scheduleFollowCheck(intervalHours: Int) {
    val intervalMillis = intervalHours * 60 * 60 * 1000L

    scheduledTask =
      taskScheduler.scheduleAtFixedRate(
        { checkFollowConfig() },
        Instant.now().plusMillis(intervalMillis), // First run after interval
        Duration.ofMillis(intervalMillis),
      )

    logger.info { "Scheduled follow check every ${intervalHours} hours" }
  }

  /**
   * Process the stored FollowConfig URLs
   */
  fun processFollowConfigNow(config: FollowConfig) {
    logger.info { "Processing follow config URLs: ${config.urls.size} URLs" }

    config.urls.forEach { url ->
      try {
        downloadService.createDownload(
          sourceUrl = url,
          libraryId = null,
          title = null,
          createdBy = "follow-config",
          priority = 5,
        )
        logger.info { "Added to queue from follow config: $url" }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to add URL from follow config: $url" }
      }
    }

    // Update last check time
    followConfigRepository.save(config.copy(lastCheckTime = LocalDateTime.now()))
  }

  private fun checkFollowConfig() {
    if (!isEnabled.get()) {
      logger.debug { "Follow check disabled, skipping" }
      return
    }

    try {
      val config = followConfigRepository.findDefault()
      if (config != null && config.enabled && config.urls.isNotEmpty()) {
        logger.info { "Running scheduled follow check" }
        processFollowConfigNow(config)
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

      downloadService.processFollowList(followListPath, library.id)
      lastCheckTimes[libraryId] = LocalDateTime.now()

      logger.info { "Manual check completed for library: ${library.name}" }
    } catch (e: Exception) {
      logger.error(e) { "Error during manual check for library: $libraryId" }
    }
  }

  /**
   * Check for new chapters by processing follow.txt files in all libraries
   * Default: runs every 6 hours (configurable via komga.download.cron property)
   */
  @Scheduled(cron = "\${komga.download.cron:0 0 */6 * * *}")
  fun checkForNewChapters() {
    logger.info { "Starting scheduled check for new chapters" }

    try {
      val libraries = libraryRepository.findAll()
      logger.info { "Checking ${libraries.size} libraries for follow.txt files" }

      var processedCount = 0

      libraries.forEach { library ->
        val followListPath = library.path.resolve("follow.txt")

        if (followListPath.toFile().exists()) {
          logger.info { "Processing follow.txt for library: ${library.name}" }

          try {
            downloadService.processFollowList(followListPath, library.id)
            lastCheckTimes[library.id] = LocalDateTime.now()
            processedCount++
          } catch (e: Exception) {
            logger.error(e) { "Error processing follow.txt for library ${library.name}" }
          }
        } else {
          logger.debug { "No follow.txt found for library: ${library.name}" }
        }
      }

      logger.info { "Scheduled check completed. Processed $processedCount follow lists." }
    } catch (e: Exception) {
      logger.error(e) { "Error during scheduled chapter check" }
    }
  }
}
