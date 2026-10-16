package org.gotson.komga.infrastructure.scrobbler

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val MAX_ENTRIES = 10_000

class LogThrottle(
  private val ttl: Duration,
  private val clock: Clock,
) {
  private val until = ConcurrentHashMap<String, Instant>()

  fun isActive(key: String): Boolean = until[key]?.let { clock.instant().isBefore(it) } ?: false

  fun shouldLog(key: String): Boolean {
    val now = clock.instant()
    val current = until[key]
    if (current != null && now.isBefore(current)) return false
    if (until.size >= MAX_ENTRIES) until.entries.removeIf { !now.isBefore(it.value) }
    until[key] = now.plus(ttl)
    return true
  }
}
