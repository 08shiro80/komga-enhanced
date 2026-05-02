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
    lock.withLock {
      val now = Instant.now()

      val oneSecondAgo = now.minusMillis(ONE_SECOND_MILLIS)
      while (requestTimestamps.isNotEmpty() && requestTimestamps.peek().isBefore(oneSecondAgo)) {
        requestTimestamps.poll()
      }

      val requestsInLastSecond = requestTimestamps.size

      if (requestsInLastSecond >= MAX_REQUESTS_PER_SECOND) {
        val oldest = requestTimestamps.peek()
        if (oldest != null) {
          val millisSinceOldest = now.toEpochMilli() - oldest.toEpochMilli()
          val waitTimeMillis = ONE_SECOND_MILLIS - millisSinceOldest + 50
          if (waitTimeMillis > 0) {
            logger.debug { "MangaDex rate limit: waiting ${waitTimeMillis}ms" }
            Thread.sleep(waitTimeMillis)
          }
        }
        while (requestTimestamps.isNotEmpty() && requestTimestamps.peek().isBefore(Instant.now().minusMillis(ONE_SECOND_MILLIS))) {
          requestTimestamps.poll()
        }
      }

      requestTimestamps.offer(Instant.now())
    }
  }

  fun reset() {
    lock.withLock {
      requestTimestamps.clear()
    }
  }
}
