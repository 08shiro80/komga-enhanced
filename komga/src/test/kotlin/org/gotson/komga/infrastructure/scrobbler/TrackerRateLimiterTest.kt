package org.gotson.komga.infrastructure.scrobbler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.infrastructure.download.MangaDexRateLimiter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerRateLimiterTest {
  @Volatile
  private var now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } answers { now } }
  private val mangaDexRateLimiter = mockk<MangaDexRateLimiter>(relaxed = true)
  private val limiter = TrackerRateLimiter(mangaDexRateLimiter, clock)

  @Test
  fun `given a 429 answer with Retry-After when acquiring then the tracker is paused until the time passed`() {
    limiter.backoff(UserTracker.ANILIST, Duration.ofSeconds(90))

    assertThatThrownBy { limiter.acquire(UserTracker.ANILIST) }
      .isInstanceOfSatisfying(TrackerRateLimitedException::class.java) { assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(90)) }
    assertThatCode { limiter.acquire(UserTracker.KITSU) }.doesNotThrowAnyException()

    now = now.plusSeconds(91)
    assertThatCode { limiter.acquire(UserTracker.ANILIST) }.doesNotThrowAnyException()
  }

  @Test
  fun `given an exhausted request budget when acquiring then the caller waits until a slot is free`() {
    repeat(20) { limiter.acquire(UserTracker.METRON) }
    val acquired = AtomicBoolean(false)
    val waiter = thread { limiter.acquire(UserTracker.METRON).also { acquired.set(true) } }

    waiter.join(1_500)
    assertThat(waiter.isAlive).isTrue()
    assertThat(acquired.get()).isFalse()

    now = now.plusSeconds(61)
    waiter.join(5_000)
    assertThat(acquired.get()).isTrue()
  }

  @Test
  fun `given a waiting caller when its thread is interrupted then the interruption is passed on`() {
    repeat(20) { limiter.acquire(UserTracker.METRON) }
    val failure = AtomicReference<Throwable>()
    val interrupted = AtomicBoolean(false)
    val waiter =
      thread {
        try {
          limiter.acquire(UserTracker.METRON)
        } catch (e: InterruptedException) {
          failure.set(e)
          interrupted.set(Thread.currentThread().isInterrupted)
        }
      }

    Thread.sleep(300)
    waiter.interrupt()
    waiter.join(5_000)

    assertThat(failure.get()).isInstanceOf(InterruptedException::class.java)
    assertThat(interrupted.get()).isTrue()
  }

  @Test
  fun `given a request thread with a wait limit when the budget is exhausted for longer then it fails at once with a clear message`() {
    repeat(20) { limiter.acquire(UserTracker.METRON) }

    assertThatThrownBy { limiter.withMaxWait(interactiveMaxWait) { limiter.acquire(UserTracker.METRON) } }
      .isInstanceOf(TrackerRateLimitedException::class.java)
      .hasMessageContaining("rate limited, try again later")

    now = now.plusSeconds(61)
    assertThatCode { limiter.withMaxWait(interactiveMaxWait) { limiter.acquire(UserTracker.METRON) } }.doesNotThrowAnyException()
  }

  @Test
  fun `given a Metron daily budget reported as used up when acquiring then the account waits until the reported reset`() {
    val reset = now.plusSeconds(3_600)
    limiter.recordDailyBudget(
      "Token a",
      HttpHeaders().apply {
        set("X-RateLimit-Sustained-Limit", "5000")
        set("X-RateLimit-Sustained-Remaining", "0")
        set("X-RateLimit-Sustained-Reset", reset.epochSecond.toString())
      },
    )

    assertThatThrownBy { limiter.acquire(UserTracker.METRON, "Token a") }
      .isInstanceOfSatisfying(TrackerRateLimitedException::class.java) { assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(3_600)) }
    assertThatCode { limiter.acquire(UserTracker.METRON, "Token b") }.doesNotThrowAnyException()

    now = reset
    assertThatCode { limiter.acquire(UserTracker.METRON, "Token a") }.doesNotThrowAnyException()
  }

  @Test
  fun `given a Metron daily budget without reset header when it is used up then the account waits until the next day`() {
    limiter.recordDailyBudget("Token a", HttpHeaders().apply { set("X-RateLimit-Sustained-Remaining", "1") })

    assertThatCode { limiter.acquire(UserTracker.METRON, "Token a") }.doesNotThrowAnyException()
    assertThatThrownBy { limiter.acquire(UserTracker.METRON, "Token a") }
      .isInstanceOfSatisfying(TrackerRateLimitedException::class.java) { assertThat(it.retryAfter).isEqualTo(Duration.ofHours(14)) }
  }

  @Test
  fun `given a Metron Authorization header when its daily budget is tracked then only the SHA-256 hash of the header is kept as key`() {
    val header = "Basic cmVhZGVyOnNlY3JldA=="
    limiter.recordDailyBudget(header, HttpHeaders().apply { set("X-RateLimit-Sustained-Remaining", "10") })
    limiter.acquire(UserTracker.METRON, header)

    val budgets =
      TrackerRateLimiter::class.java
        .getDeclaredField("dailyBudgets")
        .apply { isAccessible = true }
        .get(limiter) as Map<*, *>
    val expected =
      HexFormat
        .of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(header.toByteArray(Charsets.UTF_8)))

    assertThat(budgets.keys).containsExactly(expected)
    assertThat(budgets.keys.map { it.toString() }).noneMatch { it.contains("cmVhZGVyOnNlY3JldA") }
  }

  @Test
  fun `given MangaDex when acquiring then the shared MangaDex rate limiter is used`() {
    limiter.acquire(UserTracker.MANGADEX)

    verify(exactly = 1) { mangaDexRateLimiter.waitIfNeeded() }
  }
}
