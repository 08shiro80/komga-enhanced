package org.gotson.komga.infrastructure.scrobbler

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

class LogThrottleTest {
  private var now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } answers { now } }
  private val throttle = LogThrottle(Duration.ofHours(24), clock)

  @Test
  fun `given a repeated failure when logging then it is logged once per key and day`() {
    val key = "reader:series:anilist:failure:TrackerUnavailableException"

    assertThat(throttle.isActive(key)).isFalse()
    assertThat(throttle.shouldLog(key)).isTrue()
    assertThat(throttle.shouldLog(key)).isFalse()
    assertThat(throttle.isActive(key)).isTrue()
    assertThat(throttle.shouldLog("reader:series:anilist:failure:TrackerRateLimitedException")).isTrue()

    now = now.plus(Duration.ofHours(24))

    assertThat(throttle.isActive(key)).isFalse()
    assertThat(throttle.shouldLog(key)).isTrue()
  }
}
