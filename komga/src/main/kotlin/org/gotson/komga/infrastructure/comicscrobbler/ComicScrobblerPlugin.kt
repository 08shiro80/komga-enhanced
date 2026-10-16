package org.gotson.komga.infrastructure.comicscrobbler

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.gotson.komga.domain.model.ComicScrobble
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ComicScrobbleRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesReadStateRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.metadata.metron.MetronHttp
import org.gotson.komga.infrastructure.scrobbler.LogThrottle
import org.gotson.komga.infrastructure.scrobbler.ScrobblerConfig
import org.gotson.komga.infrastructure.scrobbler.TrackerAuthException
import org.gotson.komga.infrastructure.scrobbler.TrackerCallException
import org.gotson.komga.infrastructure.scrobbler.TrackerIdDiscovery
import org.gotson.komga.infrastructure.scrobbler.TrackerRateLimitedException
import org.gotson.komga.infrastructure.scrobbler.TrackerStatusRecorder
import org.gotson.komga.infrastructure.scrobbler.TrackerUnauthorizedException
import org.gotson.komga.infrastructure.scrobbler.TrackerUnavailableException
import org.gotson.komga.infrastructure.scrobbler.classifyTrackerFailure
import org.gotson.komga.infrastructure.scrobbler.retryDelay
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

private const val PLUGIN_ID = "comic-scrobbler"
private const val LOG_PREFIX = "[comic-scrobbler]"

private val recentWindow = Duration.ofDays(7)

private val metronIssueLink = Regex("""metron\.cloud/issue/(\d+)""", RegexOption.IGNORE_CASE)

@Component
class ComicScrobblerPlugin(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val seriesReadStateRepository: SeriesReadStateRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val userTrackerRepository: UserTrackerRepository,
  private val comicScrobbleRepository: ComicScrobbleRepository,
  private val trackerIdDiscovery: TrackerIdDiscovery,
  private val trackerStatusRecorder: TrackerStatusRecorder,
  private val metronTrackerClient: MetronTrackerClient,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val executor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "comic-scrobbler-worker").apply { isDaemon = true } }

  private val skipLog = LogThrottle(Duration.ofHours(24), clock)
  private val missingIssues = LogThrottle(Duration.ofHours(24), clock)
  private val retryAttempts = ConcurrentHashMap<String, Int>()
  private val retrying = ConcurrentHashMap.newKeySet<String>()

  @PreDestroy
  fun shutdown() {
    executor.shutdownNow()
  }

  @EventListener
  fun onReadProgressChanged(event: DomainEvent.ReadProgressChanged) {
    if (!event.progress.completed) return
    if (!isEnabled()) return
    submit(event.progress)
  }

  @Scheduled(fixedDelay = 1_800_000, initialDelay = 360_000)
  fun reconcileRecentlyRead() {
    if (!isEnabled()) return
    val since = LocalDateTime.ofInstant(clock.instant().minus(recentWindow), ZoneId.systemDefault())
    userTrackerRepository
      .findByTracker(UserTracker.METRON)
      .filter { it.active }
      .forEach { reconcile(it.userId, since) }
  }

  fun reconcileNow(userId: String): Int {
    if (!isEnabled()) return 0
    userTrackerRepository.findByUserIdAndTracker(userId, UserTracker.METRON)?.takeIf { it.active } ?: return 0
    return reconcile(userId, null)
  }

  private fun reconcile(
    userId: String,
    since: LocalDateTime?,
  ): Int {
    val config = ScrobblerConfig.load(pluginConfigRepository, PLUGIN_ID)
    var queued = 0
    seriesReadStateRepository
      .findByUserIdReadSince(userId, since)
      .filter { config.includesLibrary(it.libraryId) }
      .forEach { state ->
        val scrobbled = comicScrobbleRepository.findIssueNumbers(userId, state.seriesId)
        val books = bookRepository.findAllBySeriesId(state.seriesId)
        val numbers = bookMetadataRepository.findAllByIds(books.map { it.id }).associate { it.bookId to normalizeIssueNumber(it.number) }
        readProgressRepository
          .findAllByBookIdsAndUserId(books.map { it.id }, userId)
          .filter { it.completed && numbers[it.bookId]?.let { number -> number.isNotEmpty() && number !in scrobbled } == true }
          .filterNot { retryKey(it) in retrying }
          .forEach {
            submit(it)
            queued++
          }
      }
    return queued
  }

  private fun submit(
    progress: ReadProgress,
    delay: Duration = Duration.ZERO,
  ) {
    executor.schedule(
      {
        if (!delay.isZero) retrying.remove(retryKey(progress))
        try {
          handle(progress)
        } catch (e: Exception) {
          logger.error(e) { "$LOG_PREFIX Unexpected error for book ${progress.bookId}" }
        }
      },
      delay.toMillis(),
      TimeUnit.MILLISECONDS,
    )
  }

  private fun retryKey(progress: ReadProgress) = "${progress.userId}:${progress.bookId}"

  private fun handle(progress: ReadProgress) {
    val account = userTrackerRepository.findByUserIdAndTracker(progress.userId, UserTracker.METRON)?.takeIf { it.active } ?: return
    val authorization = MetronHttp.authorization(account) ?: return
    val config = ScrobblerConfig.load(pluginConfigRepository, PLUGIN_ID)
    val book = bookRepository.findByIdOrNull(progress.bookId) ?: return
    val series = seriesRepository.findByIdOrNull(book.seriesId) ?: return
    if (!config.includesLibrary(series.libraryId)) {
      logger.debug { "$LOG_PREFIX Library ${series.libraryId} is not scrobbled, skipping book ${book.id}" }
      return
    }
    val seriesMeta = seriesMetadataRepository.findByIdOrNull(series.id) ?: return
    val bookMeta = bookMetadataRepository.findByIdOrNull(book.id) ?: return
    val issueNumber = normalizeIssueNumber(bookMeta.number)
    if (issueNumber.isEmpty()) {
      if (skipLog.shouldLog("${account.userId}:${book.id}:number")) logger.info { "$LOG_PREFIX Skipping '${seriesMeta.title}' book ${book.name}: no issue number" }
      return
    }
    if (issueNumber in comicScrobbleRepository.findIssueNumbers(account.userId, series.id)) return

    try {
      val issueId =
        bookMeta.links.firstNotNullOfOrNull {
          metronIssueLink
            .find(it.url.toString())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        }
          ?: resolveIssue(account, authorization, series, seriesMeta, config, issueNumber)
          ?: return
      val dateRead = progress.readDate.atZone(ZoneId.systemDefault()).toOffsetDateTime()
      try {
        metronTrackerClient.scrobble(authorization, issueId, dateRead)
      } catch (e: Exception) {
        throw classifyTrackerFailure(UserTracker.METRON, e, listOf(authorization))
      }
      comicScrobbleRepository.insert(ComicScrobble(account.userId, series.id, issueNumber, issueId, book.id, progress.readDate))
      trackerStatusRecorder.success(account)
      retryAttempts.remove(retryKey(progress))
      logger.info { "$LOG_PREFIX Metron: '${seriesMeta.title}' #$issueNumber marked read (issue $issueId)" }
    } catch (e: TrackerCallException) {
      trackerStatusRecorder.failure(account, e)
      val logged = skipLog.shouldLog("${account.userId}:${series.id}:${UserTracker.METRON}:failure:${e::class.simpleName}")
      when (e) {
        is TrackerAuthException, is TrackerUnauthorizedException -> retryAttempts.remove(retryKey(progress))
        is TrackerRateLimitedException, is TrackerUnavailableException -> {
          val delay = retryDelay(retryAttempts.merge(retryKey(progress), 1) { old, _ -> old + 1 } ?: 1, (e as? TrackerRateLimitedException)?.retryAfter)
          if (!executor.isShutdown) {
            retrying.add(retryKey(progress))
            submit(progress, delay)
          }
          val message = "$LOG_PREFIX Metron sync of '${seriesMeta.title}' #$issueNumber postponed, it is retried automatically in ${delay.seconds}s: ${e.message}"
          if (logged) logger.info { message } else logger.debug { message }
        }
        else -> {
          retryAttempts.remove(retryKey(progress))
          val message = "$LOG_PREFIX Metron sync of '${seriesMeta.title}' #$issueNumber failed: ${e.message}"
          if (logged) logger.warn { message } else logger.debug { message }
        }
      }
    }
  }

  private fun resolveIssue(
    account: UserTracker,
    authorization: String,
    series: Series,
    seriesMeta: SeriesMetadata,
    config: ScrobblerConfig,
    issueNumber: String,
  ): Int? {
    val missingKey = "${series.id}:$issueNumber"
    if (missingIssues.isActive(missingKey)) {
      logger.debug { "$LOG_PREFIX Skipping '${seriesMeta.title}' #$issueNumber: it was not found on Metron within the last day" }
      return null
    }
    val resolution =
      trackerIdDiscovery.resolve(series, seriesMeta, listOf(UserTracker.METRON), config.values, metronSearch = { metronTrackerClient.searchSeries(authorization, it) })
    resolution.failures[UserTracker.METRON]?.let { failure ->
      throw if (failure.retryAfter != null) TrackerRateLimitedException(failure.reason, failure.retryAfter) else TrackerUnavailableException(failure.reason)
    }
    val metronSeriesId = resolution.ids[UserTracker.METRON] ?: return null
    val issueIds =
      try {
        metronTrackerClient.issueIds(authorization, metronSeriesId, issueNumber)
      } catch (e: Exception) {
        throw classifyTrackerFailure(UserTracker.METRON, e, listOf(authorization))
      }
    if (issueIds.size == 1) return issueIds.first()
    if (issueIds.isEmpty()) {
      if (missingIssues.shouldLog(missingKey)) logger.info { "$LOG_PREFIX Skipping '${seriesMeta.title}' #$issueNumber: Metron series $metronSeriesId has no issue #$issueNumber" }
    } else if (skipLog.shouldLog("${account.userId}:${series.id}:$issueNumber")) {
      logger.info { "$LOG_PREFIX Skipping '${seriesMeta.title}' #$issueNumber: Metron series $metronSeriesId has several issues #$issueNumber (${issueIds.joinToString()})" }
    }
    return null
  }

  private fun isEnabled(): Boolean = ScrobblerConfig.enabled(pluginRepository, PLUGIN_ID)
}
