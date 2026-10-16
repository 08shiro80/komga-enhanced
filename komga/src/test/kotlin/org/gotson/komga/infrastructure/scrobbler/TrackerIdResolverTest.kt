package org.gotson.komga.infrastructure.scrobbler

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerIdResolverTest {
  private var now = Instant.parse("2026-10-17T10:00:00Z")
  private val clock = mockk<Clock>().also { every { it.instant() } answers { now } }
  private val resolver = TrackerIdResolver(ObjectMapper(), clock)
  private val logAppender = ListAppender<ILoggingEvent>()
  private val scrobblerLogger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("org.gotson.komga.infrastructure.scrobbler")
  private var previousLevel: Level? = null

  @BeforeEach
  fun setup() {
    previousLevel = scrobblerLogger.level
    scrobblerLogger.level = Level.INFO
    logAppender.start()
    scrobblerLogger.addAppender(logAppender)
  }

  @AfterEach
  fun tearDown() {
    scrobblerLogger.detachAppender(logAppender)
    scrobblerLogger.level = previousLevel
  }

  private fun warnings() = logAppender.list.filter { it.level == Level.WARN && it.formattedMessage.startsWith("Invalid scrobbler mappings JSON") }

  @Test
  fun `given invalid mappings JSON when resolving repeatedly then the warning is logged once per day and per JSON`() {
    val config = mapOf("mappings" to """{"series": {""")

    repeat(3) { assertThat(resolver.fromMappings("series", "Series", config)).isEmpty() }
    assertThat(warnings()).hasSize(1)

    resolver.fromMappings("series", "Series", mapOf("mappings" to """{"other": ["""))
    assertThat(warnings()).hasSize(2)

    now = now.plus(Duration.ofHours(25))
    resolver.fromMappings("series", "Series", config)
    assertThat(warnings()).hasSize(3)
  }
}
