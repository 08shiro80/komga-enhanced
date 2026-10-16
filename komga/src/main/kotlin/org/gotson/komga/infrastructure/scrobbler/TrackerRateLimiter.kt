package org.gotson.komga.infrastructure.scrobbler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.infrastructure.download.MangaDexRateLimiter
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private val window = Duration.ofMinutes(1)
private val maxSleep = Duration.ofSeconds(1)
private val defaultBackoff = Duration.ofMinutes(1)
private val maxBackoff = Duration.ofHours(6)

val interactiveMaxWait: Duration = Duration.ofSeconds(10)

private const val METRON_DAILY_LIMIT = 5000
private const val HEADER_DAILY_LIMIT = "X-RateLimit-Sustained-Limit"
private const val HEADER_DAILY_REMAINING = "X-RateLimit-Sustained-Remaining"
private const val HEADER_DAILY_RESET = "X-RateLimit-Sustained-Reset"

private val requestsPerMinute =
  mapOf(
    UserTracker.ANILIST to 30,
    UserTracker.MAL to 60,
    UserTracker.KITSU to 60,
    UserTracker.METRON to 20,
  )

@Component
class TrackerRateLimiter(
  private val mangaDexRateLimiter: MangaDexRateLimiter,
  private val clock: Clock = Clock.systemUTC(),
) {
  private class DailyBudget(
    var limit: Int,
    var remaining: Int,
    var resetAt: Instant,
  )

  private val requests = ConcurrentHashMap<String, ArrayDeque<Instant>>()
  private val blockedUntil = ConcurrentHashMap<String, Instant>()
  private val dailyBudgets = ConcurrentHashMap<String, DailyBudget>()
  private val waitLimit = ThreadLocal<Duration?>()

  fun <T> withMaxWait(
    limit: Duration,
    call: () -> T,
  ): T {
    val previous = waitLimit.get()
    waitLimit.set(limit)
    try {
      return call()
    } finally {
      waitLimit.set(previous)
    }
  }

  fun maxWait(): Duration? = waitLimit.get()

  fun acquire(
    tracker: String,
    accountKey: String? = null,
  ) {
    blockedFor(tracker)?.let { throw TrackerRateLimitedException("$tracker is rate limited, try again in ${it.seconds}s", it) }
    if (tracker == UserTracker.MANGADEX) {
      mangaDexRateLimiter.waitIfNeeded()
      return
    }
    val limit = requestsPerMinute[tracker] ?: return
    if (tracker == UserTracker.METRON && accountKey != null) takeDailyBudget(accountKey)
    val timestamps = requests.computeIfAbsent(tracker) { ArrayDeque() }
    val maxWait = waitLimit.get()
    val started = clock.instant()
    while (true) {
      val wait =
        synchronized(timestamps) {
          val now = clock.instant()
          while (timestamps.isNotEmpty() && !timestamps.first().isAfter(now.minus(window))) timestamps.removeFirst()
          if (timestamps.size < limit) {
            timestamps.addLast(now)
            null
          } else {
            Duration.between(now, timestamps.first().plus(window))
          }
        } ?: return
      if (maxWait != null && Duration.between(started, clock.instant()).plus(wait) > maxWait) {
        throw TrackerRateLimitedException("$tracker is rate limited, try again later", wait)
      }
      logger.debug { "$tracker rate limit: waiting ${wait.toMillis()}ms" }
      try {
        Thread.sleep(wait.coerceAtMost(maxSleep).toMillis().coerceAtLeast(1))
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
      }
    }
  }

  fun backoff(
    tracker: String,
    retryAfter: Duration?,
  ) {
    val delay = (retryAfter ?: defaultBackoff).coerceIn(Duration.ofSeconds(1), maxBackoff)
    val until = clock.instant().plus(delay)
    blockedUntil.merge(tracker, until) { old, new -> if (new.isAfter(old)) new else old }
    logger.info { "$tracker answered with a rate limit, pausing requests for ${delay.seconds}s" }
  }

  fun blockedFor(tracker: String): Duration? {
    val until = blockedUntil[tracker] ?: return null
    val remaining = Duration.between(clock.instant(), until)
    if (remaining.isNegative || remaining.isZero) {
      blockedUntil.remove(tracker, until)
      return null
    }
    return remaining
  }

  fun recordDailyBudget(
    accountKey: String,
    headers: HttpHeaders,
  ) {
    val remaining = headers.getFirst(HEADER_DAILY_REMAINING)?.trim()?.toIntOrNull() ?: return
    val resetAt =
      headers
        .getFirst(HEADER_DAILY_RESET)
        ?.trim()
        ?.toLongOrNull()
        ?.let { Instant.ofEpochSecond(it) }
        ?: nextDay()
    val limit = headers.getFirst(HEADER_DAILY_LIMIT)?.trim()?.toIntOrNull()
    val budget = budgetFor(accountKey)
    synchronized(budget) {
      if (limit != null) budget.limit = limit
      budget.remaining = remaining
      budget.resetAt = resetAt
    }
  }

  private fun takeDailyBudget(accountKey: String) {
    val budget = budgetFor(accountKey)
    synchronized(budget) {
      val now = clock.instant()
      if (!now.isBefore(budget.resetAt)) {
        budget.remaining = budget.limit
        budget.resetAt = nextDay()
      }
      if (budget.remaining <= 0) {
        val wait = Duration.between(now, budget.resetAt)
        throw TrackerRateLimitedException("${UserTracker.METRON} daily request limit of ${budget.limit} reached, try again in ${wait.seconds}s", wait)
      }
      budget.remaining--
    }
  }

  private fun budgetFor(accountKey: String): DailyBudget = dailyBudgets.computeIfAbsent(sha256(accountKey)) { DailyBudget(METRON_DAILY_LIMIT, METRON_DAILY_LIMIT, nextDay()) }

  private fun sha256(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

  private fun nextDay(): Instant =
    LocalDate
      .ofInstant(clock.instant(), ZoneOffset.UTC)
      .plusDays(1)
      .atStartOfDay(ZoneOffset.UTC)
      .toInstant()
}
