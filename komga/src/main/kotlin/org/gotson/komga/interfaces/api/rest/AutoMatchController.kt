package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.application.tasks.HIGH_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.infrastructure.automatch.ApplyOutcome
import org.gotson.komga.infrastructure.automatch.AutoMetadataApplier
import org.gotson.komga.infrastructure.automatch.AutoMetadataMatcher
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration.TagNames
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

private val logger = KotlinLogging.logger {}

/**
 * Endpoints for the auto-match feature. Two modes:
 *  - sync per-series (`POST /api/v1/automatch/series/{id}`) — useful from the UI
 *    to give the user immediate feedback about whether a match was found.
 *  - async bulk per-library (`POST /api/v1/automatch/libraries/{id}`) — queues
 *    one task per series; returns immediately with the number queued. Existing
 *    `Task.AutoMatchSeriesMetadata.uniqueId` deduplication prevents duplicate
 *    enqueues if the user clicks twice.
 */
@RestController
@RequestMapping("api/v1/automatch", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class AutoMatchController(
  private val applier: AutoMetadataApplier,
  private val matcher: AutoMetadataMatcher,
  private val seriesRepository: SeriesRepository,
  private val taskEmitter: TaskEmitter,
) {
  @PostMapping("series/{seriesId}")
  @Operation(summary = "Auto-match metadata for a single series synchronously", tags = [TagNames.PLUGINS])
  fun matchSeriesNow(
    @PathVariable seriesId: String,
    @RequestParam(defaultValue = "false") force: Boolean,
  ): ApplyOutcome {
    val series = seriesRepository.findByIdOrNull(seriesId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found")
    return applier.apply(series, force = force)
  }

  @PostMapping("series/{seriesId}/queue")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Queue a background auto-match task for a single series", tags = [TagNames.PLUGINS])
  fun queueSeriesMatch(
    @PathVariable seriesId: String,
    @RequestParam(defaultValue = "false") force: Boolean,
  ) {
    val series = seriesRepository.findByIdOrNull(seriesId)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found")
    if (matcher.isLibraryExcluded(series.libraryId)) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Series library is excluded from auto-match")
    }
    taskEmitter.autoMatchSeriesMetadata(seriesId, force = force, priority = HIGH_PRIORITY - 1)
  }

  @PostMapping("libraries/{libraryId}")
  @Operation(summary = "Queue auto-match tasks for every series in a library", tags = [TagNames.PLUGINS])
  fun queueLibraryMatch(
    @PathVariable libraryId: String,
    @RequestParam(defaultValue = "false") force: Boolean,
  ): Map<String, Any> {
    val series = seriesRepository.findAllByLibraryId(libraryId)
    if (series.isEmpty()) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "No series in library $libraryId")
    }
    val excluded = matcher.excludedLibraryIds()
    val toQueue = series.filter { it.libraryId !in excluded }
    val skippedExcluded = series.size - toQueue.size
    if (!matcher.isEnabled()) {
      logger.warn { "Auto-match queued for ${toQueue.size} series in library=$libraryId but auto-metadata plugin is disabled — tasks will no-op until enabled." }
    }
    toQueue.forEach {
      taskEmitter.autoMatchSeriesMetadata(it.id, force = force, priority = HIGH_PRIORITY - 1)
    }
    return mapOf(
      "libraryId" to libraryId,
      "queued" to toQueue.size,
      "skippedExcludedLibrary" to skippedExcluded,
      "force" to force,
      "matcherEnabled" to matcher.isEnabled(),
    )
  }
}
