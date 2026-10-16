package org.gotson.komga.application.startup

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.gotson.komga.infrastructure.configuration.KomgaSettingsProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class LogLevelInitializer(
  private val settingsProvider: KomgaSettingsProvider,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun restoreLogLevelFromSettings() {
    val level = settingsProvider.logLevel
    val newLevel =
      when (level.uppercase()) {
        "DEBUG" -> Level.DEBUG
        "TRACE" -> Level.TRACE
        "INFO" -> Level.INFO
        "ERROR" -> Level.ERROR
        "OFF" -> Level.OFF
        else -> Level.WARN
      }
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    loggerContext
      .getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
      .level = newLevel
    loggerContext
      .getLogger("org.gotson.komga")
      .level = newLevel
  }
}
