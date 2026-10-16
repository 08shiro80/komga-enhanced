package org.gotson.komga.infrastructure.download

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

@Component
class MangaDexRateLimiter {
  private val requestTimestamps = ConcurrentLinkedQueue<Instant>()
  private val lock = ReentrantLock()

  companion object {
    private const val MAX_REQUESTS_PER_SECOND = 5
    private const val ONE_SECOND_MILLIS = 1000L
  }

  fun waitIfNeeded() {
    while (true) {
      var waitTimeMillis = 0L
      lock.withLock {
        val now = Instant.now()
        val oneSecondAgo = now.minusMillis(ONE_SECOND_MILLIS)
        while (requestTimestamps.isNotEmpty() && requestTimestamps.peek().isBefore(oneSecondAgo)) {
          requestTimestamps.poll()
        }

        if (requestTimestamps.size < MAX_REQUESTS_PER_SECOND) {
          requestTimestamps.offer(now)
          return
        }

        val oldest = requestTimestamps.peek()
        if (oldest != null) {
          waitTimeMillis = ONE_SECOND_MILLIS - (now.toEpochMilli() - oldest.toEpochMilli()) + 50
        }
      }

      if (waitTimeMillis > 0) {
        logger.debug { "MangaDex rate limit: waiting ${waitTimeMillis}ms" }
        Thread.sleep(waitTimeMillis)
      }
    }
  }

  fun reset() {
    lock.withLock {
      requestTimestamps.clear()
    }
  }
}
