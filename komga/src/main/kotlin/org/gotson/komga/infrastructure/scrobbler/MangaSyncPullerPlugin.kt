package org.gotson.komga.infrastructure.scrobbler

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.SeriesTrackerId
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesReadStateRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.SeriesTrackerIdRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.domain.service.BookLifecycle
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

private val logger = KotlinLogging.logger {}

private const val PLUGIN_ID = "manga-scrobbler"
private const val LOG_PREFIX = "[manga-scrobbler]"

private enum class PullOutcome {
  IMPORTED,
  PUSHED,
  UNCHANGED,
}

@Component
class MangaSyncPullerPlugin(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val userTrackerRepository: UserTrackerRepository,
  private val komgaUserRepository: KomgaUserRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesTrackerIdRepository: SeriesTrackerIdRepository,
  private val seriesReadStateRepository: SeriesReadStateRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val bookLifecycle: BookLifecycle,
  private val trackerIdResolver: TrackerIdResolver,
  private val trackerTokenProvider: TrackerTokenProvider,
  private val trackerStatusRecorder: TrackerStatusRecorder,
  private val trackerConnectionChecker: TrackerConnectionChecker,
  private val syncStateRecorder: SyncStateRecorder,
  private val mangaScrobblerPlugin: MangaScrobblerPlugin,
  mangaTrackerClients: List<MangaTrackerClient>,
  clock: Clock = Clock.systemUTC(),
) {
  private val clients = mangaTrackerClients.associateBy { it.tracker }

  private val sentBack = LogThrottle(Duration.ofHours(24), clock)

  private val executor: ExecutorService =
    Executors.newSingleThreadExecutor { r -> Thread(r, "scrobbler-puller").apply { isDaemon = true } }

  private val running = AtomicBoolean(false)

  @PreDestroy
  fun shutdown() {
    executor.shutdownNow()
  }

  @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
  fun pullAllTrackers() {
    if (!ScrobblerConfig.enabled(pluginRepository, PLUGIN_ID)) return
    if (!running.compareAndSet(false, true)) {
      logger.debug { "$LOG_PREFIX Previous tracker pull is still running, skipping this run" }
      return
    }
    executor.submit {
      try {
        pullAll()
      } catch (e: Exception) {
        logger.error(e) { "$LOG_PREFIX Unexpected error while pulling the trackers" }
      } finally {
        running.set(false)
      }
    }
  }

  private fun pullAll() {
    val config = ScrobblerConfig.load(pluginConfigRepository, PLUGIN_ID)
    clients.keys.forEach { tracker ->
      val accounts = userTrackerRepository.findByTracker(tracker).filter { it.active }
      if (accounts.isEmpty()) return@forEach
      val seriesByMediaId = seriesByMediaId(tracker)
      accounts.forEach { account ->
        try {
          pull(account, config, seriesByMediaId)
        } catch (e: Exception) {
          logger.error(e) { "$LOG_PREFIX Unexpected error while pulling ${trackerDisplayNames[tracker]} for user ${account.userId}" }
        }
      }
    }
  }

  private fun pull(
    account: UserTracker,
    config: ScrobblerConfig,
    seriesByMediaId: Map<String, Set<String>>,
  ) {
    val user = komgaUserRepository.findByIdOrNull(account.userId) ?: return
    val client = clients.getValue(account.tracker)
    val name = trackerDisplayNames[account.tracker]
    val identified =
      try {
        if (needsIdentity(account)) trackerConnectionChecker.confirmIdentity(account) else account
      } catch (e: TrackerCallException) {
        trackerStatusRecorder.failure(account, e)
        logger.debug { "$LOG_PREFIX $name pull for user ${account.userId} skipped, the remote account could not be identified: ${e.message}" }
        return
      }
    val remote =
      try {
        trackerTokenProvider.withToken(identified) { token -> client.library(identified, token) }
      } catch (e: TrackerCallException) {
        trackerStatusRecorder.failure(identified, e)
        if (e !is TrackerAuthException) logger.info { "$LOG_PREFIX $name pull for user ${identified.userId} postponed: ${e.message}" }
        return
      }
    trackerStatusRecorder.success(identified)

    var imported = 0
    var pushed = 0
    remote.forEach { entry ->
      seriesByMediaId[entry.mediaId].orEmpty().forEach { seriesId ->
        when (apply(identified, user, config, seriesId, entry)) {
          PullOutcome.IMPORTED -> imported++
          PullOutcome.PUSHED -> pushed++
          PullOutcome.UNCHANGED -> Unit
        }
      }
    }
    val summary = "$LOG_PREFIX $name pull for user ${identified.userId}: $imported series updated from $name, $pushed series sent back"
    if (imported > 0 || pushed > 0) logger.info { summary } else logger.debug { summary }
  }

  private fun needsIdentity(account: UserTracker): Boolean =
    account.identityCheckRequired ||
      (account.tracker == UserTracker.ANILIST && account[UserTracker.KEY_REMOTE_USER_ID] == null)

  private fun apply(
    account: UserTracker,
    user: KomgaUser,
    config: ScrobblerConfig,
    seriesId: String,
    entry: RemoteEntry,
  ): PullOutcome {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: return PullOutcome.UNCHANGED
    if (!config.includesLibrary(series.libraryId) || !user.canAccessLibrary(series.libraryId)) return PullOutcome.UNCHANGED
    val remoteProgress = entry.progress
    if (remoteProgress <= 0) return PullOutcome.UNCHANGED
    val state = seriesReadStateRepository.findByUserIdAndSeriesId(user.id, seriesId)
    val local = state?.let { if (it.oneshot) 1 else floor(it.highestNumberSort.toDouble()).toInt() } ?: 0

    return when {
      remoteProgress > local -> {
        syncStateRecorder.record(user.id, seriesId, account.tracker, remoteProgress, entry.status?.name, remoteUpdate = true)
        val books = bookRepository.findAllBySeriesId(seriesId)
        val numbers = bookMetadataRepository.findAllByIds(books.map { it.id }).associate { it.bookId to it.numberSort }
        val alreadyRead =
          readProgressRepository
            .findAllByBookIdsAndUserId(books.map { it.id }, user.id)
            .filter { it.completed }
            .map { it.bookId }
            .toSet()
        val toMark =
          books.filter { book ->
            val number = numbers[book.id]
            book.id !in alreadyRead && number != null && number > 0 && number <= remoteProgress.toFloat()
          }
        toMark.forEach { bookLifecycle.markReadProgressCompleted(it.id, user) }
        if (toMark.isEmpty()) PullOutcome.UNCHANGED else PullOutcome.IMPORTED
      }

      remoteProgress < local -> {
        if (!sentBack.shouldLog("${user.id}:$seriesId:${account.tracker}:$local")) return PullOutcome.UNCHANGED
        syncStateRecorder.recordRemote(user.id, seriesId, account.tracker, remoteProgress, entry.status?.name)
        mangaScrobblerPlugin.schedule(user.id, seriesId, listOf(account.tracker))
        PullOutcome.PUSHED
      }

      else -> {
        syncStateRecorder.record(user.id, seriesId, account.tracker, remoteProgress, entry.status?.name, remoteUpdate = true)
        PullOutcome.UNCHANGED
      }
    }
  }

  private fun seriesByMediaId(tracker: String): Map<String, Set<String>> {
    val accepted = seriesTrackerIdRepository.findByTracker(tracker).filter { it.status == SeriesTrackerId.Status.ACCEPTED }
    val acceptedSeries = accepted.map { it.seriesId }.toSet()
    val fromLinks =
      seriesTrackerIdRepository
        .findLinkedSeriesUrls(trackerLinkUrlPart(tracker))
        .filter { (seriesId, _) -> seriesId !in acceptedSeries }
        .mapNotNull { (seriesId, url) -> trackerIdResolver.fromLink(tracker, url)?.takeIf { it.toIntOrNull() != null }?.let { seriesId to it } }
    return (accepted.map { it.seriesId to it.trackerId } + fromLinks)
      .groupBy({ it.second }, { it.first })
      .mapValues { it.value.toSet() }
  }
}
