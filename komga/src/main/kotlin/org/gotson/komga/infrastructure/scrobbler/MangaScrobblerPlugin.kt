package org.gotson.komga.infrastructure.scrobbler

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.SeriesReadState
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesReadStateRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.download.MangaDexApiClient
import org.gotson.komga.infrastructure.download.MangaDexLookup
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.floor

private val logger = KotlinLogging.logger {}

private const val PLUGIN_ID = "manga-scrobbler"
private const val LOG_PREFIX = "[manga-scrobbler]"

private val debounce = Duration.ofSeconds(5)
private val recentWindow = Duration.ofDays(7)
private val retryMin = Duration.ofSeconds(1)
private val retryBase = Duration.ofMinutes(10)
private val retryMax = Duration.ofHours(6)

private val contentRatingOrder = listOf("safe", "suggestive", "erotica", "pornographic")

private val mangaDexChapterUrl = Regex("""mangadex\.org/chapter/([0-9a-f-]{36})""", RegexOption.IGNORE_CASE)

internal fun retryDelay(
  attempt: Int,
  retryAfter: Duration?,
): Duration {
  if (retryAfter != null) return retryAfter.coerceAtLeast(retryMin)
  val backoff = retryBase.multipliedBy(1L shl (attempt - 1).coerceIn(0, 10))
  return if (backoff > retryMax) retryMax else backoff
}

@Component
class MangaScrobblerPlugin(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val bookRepository: BookRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val seriesReadStateRepository: SeriesReadStateRepository,
  private val userTrackerRepository: UserTrackerRepository,
  private val chapterUrlRepository: ChapterUrlRepository,
  private val trackerIdDiscovery: TrackerIdDiscovery,
  private val trackerTokenProvider: TrackerTokenProvider,
  private val trackerStatusRecorder: TrackerStatusRecorder,
  private val trackerConnectionChecker: TrackerConnectionChecker,
  private val syncStateRecorder: SyncStateRecorder,
  private val mangaDexApiClient: MangaDexApiClient,
  private val mangaDexTrackerClient: MangaDexTrackerClient,
  mangaTrackerClients: List<MangaTrackerClient>,
  private val clock: Clock = Clock.systemUTC(),
) {
  private data class IdentityBackoff(
    val attempts: Int,
    val until: Instant,
  )

  private val clients = mangaTrackerClients.associateBy { it.tracker }

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "scrobbler-scheduler").apply { isDaemon = true } }

  private val trackerExecutors: Map<String, ExecutorService> =
    UserTracker.mangaTrackers.associateWith { tracker ->
      Executors.newSingleThreadExecutor { r -> Thread(r, "scrobbler-$tracker").apply { isDaemon = true } }
    }

  private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
  private val retryAttempts = ConcurrentHashMap<String, Int>()
  private val identityBackoff = ConcurrentHashMap<String, IdentityBackoff>()
  private val skipLog = LogThrottle(Duration.ofHours(24), clock)
  private val skipped = LogThrottle(Duration.ofHours(24), clock)

  @PreDestroy
  fun shutdown() {
    scheduler.shutdownNow()
    trackerExecutors.values.forEach { it.shutdownNow() }
  }

  @EventListener
  fun onReadProgressChanged(event: DomainEvent.ReadProgressChanged) {
    if (!event.progress.completed) return
    if (!isEnabled()) return
    val seriesId = bookRepository.findByIdOrNull(event.progress.bookId)?.seriesId ?: return
    schedule(event.progress.userId, seriesId, UserTracker.mangaTrackers)
  }

  @Scheduled(fixedDelay = 1_800_000, initialDelay = 300_000)
  fun reconcileRecentlyRead() {
    if (!isEnabled()) return
    val since = LocalDateTime.ofInstant(clock.instant().minus(recentWindow), ZoneId.systemDefault())
    val config = ScrobblerConfig.load(pluginConfigRepository, PLUGIN_ID)
    UserTracker.mangaTrackers
      .flatMap { userTrackerRepository.findByTracker(it) }
      .filter { it.active && !identityPaused(it) }
      .groupBy { it.userId }
      .forEach { (userId, accounts) ->
        seriesReadStateRepository
          .findByUserIdReadSince(userId, since)
          .filter { config.includesLibrary(it.libraryId) }
          .forEach { state ->
            val target = targetFor(state)
            val behind =
              accounts
                .filter { it.identityCheckRequired || target > syncStateRecorder.progress(userId, state.seriesId, it.tracker) }
                .filterNot { pending.containsKey(scheduleKey(userId, state.seriesId, listOf(it.tracker))) }
                .filterNot { account ->
                  skipped.isActive(skipKey(userId, state.seriesId, account.tracker, target)).also {
                    if (it) logger.debug { "$LOG_PREFIX Series ${state.seriesId} was skipped for ${account.tracker} at $target within the last day, not reconciling it" }
                  }
                }
            if (behind.isNotEmpty()) schedule(userId, state.seriesId, behind.map { it.tracker })
          }
      }
  }

  fun reconcileNow(
    userId: String,
    tracker: String,
  ): Int {
    if (!isEnabled()) return 0
    userTrackerRepository.findByUserIdAndTracker(userId, tracker)?.takeIf { it.active } ?: return 0
    val config = ScrobblerConfig.load(pluginConfigRepository, PLUGIN_ID)
    val states =
      seriesReadStateRepository
        .findByUserIdReadSince(userId, null)
        .filter { config.includesLibrary(it.libraryId) }
    states.forEach { schedule(userId, it.seriesId, listOf(tracker)) }
    return states.size
  }

  fun schedule(
    userId: String,
    seriesId: String,
    trackers: Collection<String>,
    delay: Duration = debounce,
  ) {
    val key = scheduleKey(userId, seriesId, trackers)
    pending.remove(key)?.cancel(false)
    pending[key] =
      scheduler.schedule(
        {
          pending.remove(key)
          try {
            dispatch(userId, seriesId, trackers)
          } catch (e: Exception) {
            logger.error(e) { "$LOG_PREFIX Unexpected error while preparing the sync of series $seriesId for user $userId" }
          }
        },
        delay.toMillis(),
        TimeUnit.MILLISECONDS,
      )
  }

  private fun dispatch(
    userId: String,
    seriesId: String,
    trackers: Collection<String>,
  ) {
    if (!isEnabled()) return
    val config = ScrobblerConfig.load(pluginConfigRepository, PLUGIN_ID)
    val series = seriesRepository.findByIdOrNull(seriesId) ?: return
    if (!config.includesLibrary(series.libraryId)) {
      logger.debug { "$LOG_PREFIX Library ${series.libraryId} is not scrobbled, skipping series $seriesId" }
      return
    }
    val meta = seriesMetadataRepository.findByIdOrNull(seriesId) ?: return
    val state = seriesReadStateRepository.findByUserIdAndSeriesId(userId, seriesId) ?: return
    val target = targetFor(state)
    if (target <= 0) {
      if (skipLog.shouldLog("$userId:$seriesId:number")) {
        logger.info { "$LOG_PREFIX Skipping '${meta.title}': the highest read book has no positive number (${state.highestNumberSort})" }
      }
      return
    }

    val (accounts, synced) =
      userTrackerRepository
        .findByUserId(userId)
        .filter { it.tracker in trackers && it.tracker in UserTracker.mangaTrackers && it.active && !identityPaused(it) }
        .partition { it.identityCheckRequired || target > syncStateRecorder.progress(userId, seriesId, it.tracker) }
    synced.forEach { retryAttempts.remove(retryKey(it, series)) }
    if (accounts.isEmpty()) return

    val userMalClientId = userTrackerRepository.findByUserIdAndTracker(userId, UserTracker.MAL)?.get(UserTracker.KEY_CLIENT_ID)
    accounts.forEach { account ->
      trackerExecutors.getValue(account.tracker).submit {
        try {
          if (identityPaused(account)) return@submit
          val resolution = trackerIdDiscovery.resolve(series, meta, listOf(account.tracker), config.values, userMalClientId)
          val mediaId = resolution.ids[account.tracker]
          if (mediaId == null) {
            val failure = resolution.failures[account.tracker]
            if (failure == null) {
              retryAttempts.remove(retryKey(account, series))
            } else {
              val logged = skipLog.shouldLog("${account.userId}:${series.id}:${account.tracker}:failure:discovery")
              retryLater(account, series, meta, failure.retryAfter, failure.reason, logged)
            }
            return@submit
          }
          push(account, series, meta, state, mediaId, target)
        } catch (e: Exception) {
          logger.error(e) { "$LOG_PREFIX Unexpected error while syncing '${meta.title}' to ${trackerDisplayNames[account.tracker]} for user $userId" }
        }
      }
    }
  }

  private fun scheduleKey(
    userId: String,
    seriesId: String,
    trackers: Collection<String>,
  ) = "$userId:$seriesId:${trackers.sorted().joinToString(",")}"

  private fun skipKey(
    userId: String,
    seriesId: String,
    tracker: String,
    target: Int,
  ) = "$userId:$seriesId:$tracker:$target"

  private fun retryKey(
    account: UserTracker,
    series: Series,
  ) = "${account.userId}:${series.id}:${account.tracker}"

  private fun targetFor(state: SeriesReadState): Int = if (state.oneshot) 1 else floor(state.highestNumberSort.toDouble()).toInt()

  private fun push(
    account: UserTracker,
    series: Series,
    meta: SeriesMetadata,
    state: SeriesReadState,
    mediaId: String,
    target: Int,
  ) {
    val current = userTrackerRepository.findByUserIdAndTracker(account.userId, account.tracker)?.takeIf { it.active } ?: return
    val name = trackerDisplayNames[current.tracker]
    val retryKey = retryKey(current, series)
    try {
      val identified = if (current.identityCheckRequired) confirmIdentity(current) else current
      if (target <= syncStateRecorder.progress(identified.userId, series.id, identified.tracker)) {
        retryAttempts.remove(retryKey)
        return
      }
      if (identified.tracker == UserTracker.MANGADEX) {
        pushMangaDex(identified, series, meta, mediaId, target)
      } else {
        pushEntry(identified, series, meta, state, mediaId, target)
      }
      trackerStatusRecorder.success(identified)
      retryAttempts.remove(retryKey)
    } catch (e: TrackerCallException) {
      trackerStatusRecorder.failure(current, e)
      val logged = skipLog.shouldLog("$retryKey:failure:${e::class.simpleName}")
      when (e) {
        is TrackerAuthException, is TrackerUnauthorizedException -> retryAttempts.remove(retryKey)
        is TrackerRateLimitedException, is TrackerUnavailableException ->
          retryLater(current, series, meta, (e as? TrackerRateLimitedException)?.retryAfter, e.message, logged)
        else -> {
          retryAttempts.remove(retryKey)
          val message = "$LOG_PREFIX $name sync of '${meta.title}' failed: ${e.message}"
          if (logged) logger.warn { message } else logger.debug { message }
        }
      }
    }
  }

  fun clearIdentityBackoff(
    userId: String,
    tracker: String,
  ) {
    identityBackoff.remove(identityKey(userId, tracker))
  }

  private fun identityKey(account: UserTracker) = identityKey(account.userId, account.tracker)

  private fun identityKey(
    userId: String,
    tracker: String,
  ) = "$userId:$tracker"

  private fun identityPaused(account: UserTracker): Boolean {
    if (!account.identityCheckRequired) return false
    val until = identityBackoff[identityKey(account)]?.until ?: return false
    if (!clock.instant().isBefore(until)) return false
    logger.debug { "$LOG_PREFIX Identity check of the ${trackerDisplayNames[account.tracker]} account of user ${account.userId} failed, no sync is planned before $until" }
    return true
  }

  private fun confirmIdentity(account: UserTracker): UserTracker =
    try {
      trackerConnectionChecker.confirmIdentity(account).also { identityBackoff.remove(identityKey(account)) }
    } catch (e: TrackerCallException) {
      if (e !is TrackerAuthException && e !is TrackerUnauthorizedException) {
        identityBackoff.compute(identityKey(account)) { _, previous ->
          val attempts = (previous?.attempts ?: 0) + 1
          IdentityBackoff(attempts, clock.instant().plus(retryDelay(attempts, (e as? TrackerRateLimitedException)?.retryAfter)))
        }
      }
      throw e
    }

  private fun retryLater(
    account: UserTracker,
    series: Series,
    meta: SeriesMetadata,
    retryAfter: Duration?,
    reason: String?,
    logged: Boolean,
  ) {
    val retryKey = retryKey(account, series)
    val delay = retryDelay(retryAttempts.merge(retryKey, 1) { old, _ -> old + 1 } ?: 1, retryAfter)
    if (!scheduler.isShutdown) schedule(account.userId, series.id, listOf(account.tracker), delay)
    val message = "$LOG_PREFIX ${trackerDisplayNames[account.tracker]} sync of '${meta.title}' postponed, it is retried automatically in ${delay.seconds}s: $reason"
    if (logged) logger.info { message } else logger.debug { message }
  }

  private fun pushEntry(
    account: UserTracker,
    series: Series,
    meta: SeriesMetadata,
    state: SeriesReadState,
    mediaId: String,
    target: Int,
  ) {
    val client = clients.getValue(account.tracker)
    val name = trackerDisplayNames[account.tracker]
    trackerTokenProvider.withToken(account) { token ->
      val remote = client.entry(account, token, mediaId)
      val remoteProgress = remote.entry?.progress ?: 0
      if (remoteProgress >= target) {
        syncStateRecorder.record(account.userId, series.id, account.tracker, target)
        logger.debug { "$LOG_PREFIX $name already has '${meta.title}' at $remoteProgress, nothing to update" }
        return@withToken
      }
      val total = remote.totalChapters
      val remoteStatus = remote.entry?.status
      val completes = state.oneshot || (total != null && target >= total)
      val status =
        when {
          remoteStatus == RemoteStatus.COMPLETED || remoteStatus == RemoteStatus.REPEATING -> null
          completes -> RemoteStatus.COMPLETED
          remoteStatus == null || remoteStatus == RemoteStatus.PLANNING -> RemoteStatus.CURRENT
          else -> null
        }
      val update =
        EntryUpdate(
          progress = target,
          status = status,
          startedAt = if (remote.entry?.startedAt == null) state.firstReadDate.toLocalDate() else null,
          completedAt = if (status == RemoteStatus.COMPLETED && remote.entry?.completedAt == null) state.lastReadDate.toLocalDate() else null,
        )
      client.save(account, token, mediaId, remote, update)
      syncStateRecorder.record(account.userId, series.id, account.tracker, target, status?.name)
      logger.info { "$LOG_PREFIX $name: '${meta.title}' → chapter $target${status?.let { " (${it.name.lowercase()})" } ?: ""}" }
    }
  }

  private fun pushMangaDex(
    account: UserTracker,
    series: Series,
    meta: SeriesMetadata,
    mangaId: String,
    target: Int,
  ) {
    val maxRating = account[UserTracker.KEY_MAX_CONTENT_RATING] ?: contentRatingOrder.last()
    val rating =
      when (val info = mangaDexApiClient.lookupMangaForTracking(mangaId)) {
        is MangaDexLookup.Found -> info.value.contentRating
        is MangaDexLookup.Failed -> throw TrackerUnavailableException(info.reason)
        MangaDexLookup.NotFound -> throw TrackerNotFoundException("MangaDex has no manga $mangaId")
      }
    if (rating != null && contentRatingOrder.indexOf(rating) > contentRatingOrder.indexOf(maxRating)) {
      if (skipped.shouldLog(skipKey(account.userId, series.id, account.tracker, target))) {
        logger.info { "$LOG_PREFIX Skipping '${meta.title}' for MangaDex: content rating $rating is above the configured maximum $maxRating" }
      }
      return
    }

    val chapterIds = chapterIdsUpTo(series.id, mangaId, target)
    if (chapterIds.isEmpty()) {
      if (skipped.shouldLog(skipKey(account.userId, series.id, account.tracker, target))) {
        logger.info { "$LOG_PREFIX Skipping '${meta.title}' for MangaDex: no MangaDex chapters up to $target are known" }
      }
      return
    }
    trackerTokenProvider.withToken(account) { token ->
      val alreadyRead = mangaDexTrackerClient.readChapterIds(token, mangaId)
      val toMark = chapterIds - alreadyRead
      if (toMark.isNotEmpty()) mangaDexTrackerClient.markRead(token, mangaId, toMark)
      syncStateRecorder.record(account.userId, series.id, account.tracker, target)
      logger.info { "$LOG_PREFIX MangaDex: '${meta.title}' → ${toMark.size} chapters up to $target marked read" }
    }
  }

  private fun chapterIdsUpTo(
    seriesId: String,
    mangaId: String,
    target: Int,
  ): Set<String> {
    val local = chapterUrlRepository.findBySeriesId(seriesId)
    val fromLocal =
      local
        .filter { it.chapter > 0 && it.chapter <= target }
        .mapNotNull { mangaDexChapterUrl.find(it.url)?.groupValues?.get(1) }
        .toSet()
    if (fromLocal.isNotEmpty()) return fromLocal
    val languages = local.map { it.lang }.distinct().ifEmpty { listOf(defaultLanguage()) }
    return languages
      .flatMap { mangaDexApiClient.getChaptersForManga(mangaId, it) }
      .filter { chapter -> chapter.chapterNumber?.toDoubleOrNull()?.let { it > 0 && it <= target } == true }
      .mapNotNull { it.chapterId }
      .toSet()
  }

  private fun defaultLanguage(): String =
    pluginConfigRepository
      .findByPluginIdAndKey("gallery-dl-downloader", "default_language")
      ?.configValue
      ?.takeIf { it.isNotBlank() }
      ?: "en"

  private fun isEnabled(): Boolean = ScrobblerConfig.enabled(pluginRepository, PLUGIN_ID)
}
