package org.gotson.komga.infrastructure.automatch

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.application.tasks.HIGH_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DomainEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * When a new series shows up (initial scan, library add, etc.) and auto-match
 * is enabled, queue a background `Task.AutoMatchSeriesMetadata`. We do not run
 * the match inline — it would block the scan and burn the API rate budget all
 * at once if a fresh library has hundreds of new series.
 */
@Component
class AutoMetadataEventListener(
  private val matcher: AutoMetadataMatcher,
  private val taskEmitter: TaskEmitter,
) {
  @EventListener
  fun onSeriesAdded(event: DomainEvent.SeriesAdded) {
    if (!matcher.isEnabled()) {
      logger.debug { "Auto-match disabled — not enqueueing match for new series='${event.series.name}'" }
      return
    }
    if (matcher.isLibraryExcluded(event.series.libraryId)) {
      logger.debug { "Auto-match: library ${event.series.libraryId} excluded, not enqueueing '${event.series.name}'" }
      return
    }
    logger.info { "Enqueueing auto-match for new series='${event.series.name}' (id=${event.series.id})" }
    taskEmitter.autoMatchSeriesMetadata(event.series.id, force = false, priority = HIGH_PRIORITY - 1)
  }
}
