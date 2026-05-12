package org.gotson.komga.infrastructure.rate

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * Simple in-process rate limiter for Metron API (metron.cloud).
 * Metron enforces 20 requests per minute and ~5000 per day.
 *
 * Tracks timestamps of recent calls. If the sliding window of
 * [maxPerMinute] calls in the last 60 seconds is full, callers should
 * back off and retry.
 */
@Component
class MetronRateLimiter(
  private val maxPerMinute: Int = 20,
) {
  private val windows = ConcurrentHashMap<String, Window>()

  data class Window(
    val timestamps: LongArray,
    @Volatile var head: Int = 0,
  )

  /**
   * Returns true if the call is allowed, false if the caller should wait.
   */
  fun tryAcquire(key: String = "default"): Boolean {
    val w = windows.computeIfAbsent(key) { Window(LongArray(maxPerMinute)) }
    val now = Instant.now().toEpochMilli()
    val cutoff = now - 60_000

    synchronized(w) {
      // Count calls within the last 60 seconds
      var count = 0
      for (i in 0 until maxPerMinute) {
        if (w.timestamps[i] > cutoff) count++
      }

      if (count >= maxPerMinute) {
        // Find the oldest slot and how long to wait
        val oldest = w.timestamps.min()
        val waitMs = max(0L, oldest + 60_000 - now)
        logger.debug { "Metron rate limit: ${count}/${maxPerMinute} in window, wait ${waitMs}ms" }
        return false
      }

      // Record this call
      w.timestamps[w.head % maxPerMinute] = now
      w.head = (w.head + 1) % maxPerMinute
      return true
    }
  }

  /**
   * Returns the suggested wait time in milliseconds before the next call
   * should be attempted, or 0 if the window is not full.
   */
  fun suggestedBackoffMs(key: String = "default"): Long {
    val w = windows[key] ?: return 0
    val now = Instant.now().toEpochMilli()
    val cutoff = now - 60_000

    synchronized(w) {
      var count = 0
      var oldest = Long.MAX_VALUE
      for (i in 0 until maxPerMinute) {
        val ts = w.timestamps[i]
        if (ts > cutoff) {
          count++
          if (ts < oldest) oldest = ts
        }
      }
      if (count >= maxPerMinute) {
        return max(0L, oldest + 60_000 - now)
      }
      return 0
    }
  }
}
