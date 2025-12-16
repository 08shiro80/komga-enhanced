package org.gotson.komga.infrastructure.download

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Rate limiter for MangaDex API requests.
 *
 * Implements rate limiting according to MangaDex API documentation:
 * https://api.mangadex.org/docs/2-limitations/
 *
 * - 5 requests per second per IP
 * - 40 requests per minute for authenticated users
 * - 10 requests per minute for anonymous users (not used in Komga)
 */
@Component
class MangaDexRateLimiter {
  private val requestTimestamps = ConcurrentLinkedQueue<Instant>()
  private val lock = ReentrantLock()

  companion object {
    private const val MAX_REQUESTS_PER_SECOND = 5
    private const val MAX_REQUESTS_PER_MINUTE = 40
    private const val ONE_SECOND_MILLIS = 1000L
    private const val ONE_MINUTE_MILLIS = 60000L
  }

  /**
   * Waits if necessary to comply with MangaDex rate limits.
   * Should be called before making any request to MangaDex API.
   */
  fun waitIfNeeded() {
    lock.withLock {
      val now = Instant.now()

      // Clean up timestamps older than 1 minute
      val oneMinuteAgo = now.minusMillis(ONE_MINUTE_MILLIS)
      while (requestTimestamps.isNotEmpty() && requestTimestamps.peek().isBefore(oneMinuteAgo)) {
        requestTimestamps.poll()
      }

      // Count requests in the last second
      val oneSecondAgo = now.minusMillis(ONE_SECOND_MILLIS)
      val requestsInLastSecond = requestTimestamps.count { it.isAfter(oneSecondAgo) }

      // Count total requests in the last minute
      val requestsInLastMinute = requestTimestamps.size

      // Calculate wait time if limits are exceeded
      val waitTimeMillis =
        when {
          // Check per-second limit first (more restrictive)
          requestsInLastSecond >= MAX_REQUESTS_PER_SECOND -> {
            // Find oldest request in last second
            val oldestInSecond = requestTimestamps.filter { it.isAfter(oneSecondAgo) }.minOrNull()
            if (oldestInSecond != null) {
              val millisSinceOldest = now.toEpochMilli() - oldestInSecond.toEpochMilli()
              ONE_SECOND_MILLIS - millisSinceOldest + 100 // Add 100ms buffer
            } else {
              0L
            }
          }
          // Check per-minute limit
          requestsInLastMinute >= MAX_REQUESTS_PER_MINUTE -> {
            // Find oldest request in last minute
            val oldest = requestTimestamps.peek()
            if (oldest != null) {
              val millisSinceOldest = now.toEpochMilli() - oldest.toEpochMilli()
              ONE_MINUTE_MILLIS - millisSinceOldest + 100 // Add 100ms buffer
            } else {
              0L
            }
          }
          else -> 0L
        }

      if (waitTimeMillis > 0) {
        logger.info { "MangaDex rate limit approaching. Waiting ${waitTimeMillis}ms before next request." }
        Thread.sleep(waitTimeMillis)
      }

      // Record this request
      requestTimestamps.offer(Instant.now())
    }
  }

  /**
   * Clears all rate limit history. Useful for testing.
   */
  fun reset() {
    lock.withLock {
      requestTimestamps.clear()
    }
  }

  /**
   * Returns current request counts for monitoring/debugging.
   */
  fun getStats(): RateLimitStats {
    lock.withLock {
      val now = Instant.now()
      val oneSecondAgo = now.minusMillis(ONE_SECOND_MILLIS)
      val oneMinuteAgo = now.minusMillis(ONE_MINUTE_MILLIS)

      return RateLimitStats(
        requestsInLastSecond = requestTimestamps.count { it.isAfter(oneSecondAgo) },
        requestsInLastMinute = requestTimestamps.count { it.isAfter(oneMinuteAgo) },
        maxPerSecond = MAX_REQUESTS_PER_SECOND,
        maxPerMinute = MAX_REQUESTS_PER_MINUTE,
      )
    }
  }

  data class RateLimitStats(
    val requestsInLastSecond: Int,
    val requestsInLastMinute: Int,
    val maxPerSecond: Int,
    val maxPerMinute: Int,
  )
}
