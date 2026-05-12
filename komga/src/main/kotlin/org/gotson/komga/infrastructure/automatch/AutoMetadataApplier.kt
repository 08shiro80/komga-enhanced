package org.gotson.komga.infrastructure.automatch

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.application.tasks.HIGH_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

data class ApplyOutcome(
  val matched: Boolean,
  val pluginId: String? = null,
  val externalId: String? = null,
  val score: Double? = null,
  val matchedTitle: String? = null,
  val skippedReason: String? = null,
)

/**
 * Composes [AutoMetadataMatcher] + [SeriesJsonWriter] into the user-facing
 * "auto-match this series" operation. We deliberately keep this synchronous
 * and idempotent — the async layer (Task + TaskEmitter) wraps it for
 * background execution.
 */
@Service
class AutoMetadataApplier(
  private val matcher: AutoMetadataMatcher,
  private val seriesJsonWriter: SeriesJsonWriter,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val taskEmitter: TaskEmitter,
) {
  /**
   * @param force if false (default), respect both the global "auto-metadata enabled"
   *   toggle and the "skip if already linked" rule. force=true bypasses both gates,
   *   so an admin can force-rematch a single series even when the global feature
   *   is off.
   * @param triggerRefresh if true, queue a `RefreshSeriesMetadata` so the
   *   freshly written series.json is read back and merged into the DB. Set
   *   false when calling from inside an existing refresh handler — the
   *   in-flight refresh will already pick up the new series.json from disk.
   */
  fun apply(series: Series, force: Boolean = false, triggerRefresh: Boolean = true): ApplyOutcome {
    if (!force && !matcher.isEnabled()) {
      logger.debug { "Auto-match: feature disabled, skipping series='${series.name}'" }
      return ApplyOutcome(matched = false, skippedReason = "disabled")
    }

    if (matcher.isLibraryExcluded(series.libraryId)) {
      logger.debug { "Auto-match: library ${series.libraryId} excluded, skipping series='${series.name}'" }
      return ApplyOutcome(matched = false, skippedReason = "excluded-library")
    }

    val meta = seriesMetadataRepository.findById(series.id)

    if (!force && meta.links.isNotEmpty()) {
      logger.debug { "Auto-match: series='${series.name}' already has ${meta.links.size} link(s), skipping (use force=true to override)" }
      return ApplyOutcome(matched = false, skippedReason = "already-linked")
    }

    val scan = matcher.scan(series)
    val match = scan.primary ?: return ApplyOutcome(matched = false, skippedReason = "no-match-above-threshold")

    val details =
      try {
        match.provider.getMetadata(match.externalId)
      } catch (e: Exception) {
        logger.warn(e) { "Auto-match: getMetadata failed for plugin='${match.pluginId}' id=${match.externalId}" }
        null
      } ?: return ApplyOutcome(
        matched = false,
        pluginId = match.pluginId,
        externalId = match.externalId,
        score = match.score,
        skippedReason = "details-unavailable",
      )

    val trackerLinkPairs =
      scan.trackerLinkMatches.mapNotNull { m ->
        seriesJsonWriter.providerOfPluginId(m.pluginId)?.let { tag -> tag to m.externalId }
      }

    seriesJsonWriter.write(
      seriesPath = series.path,
      details = details,
      externalId = match.externalId,
      pluginId = match.pluginId,
      trackerLinkPairs = trackerLinkPairs,
    )

    if (triggerRefresh) {
      // HIGH_PRIORITY so the new series.json is consumed before any backlog
      // of book-level refreshes drains.
      taskEmitter.refreshSeriesMetadata(series.id, priority = HIGH_PRIORITY)
    }

    logger.info {
      "Auto-match: applied plugin='${match.pluginId}' id=${match.externalId} score=${"%.2f".format(match.score)} for series='${series.name}'"
    }
    return ApplyOutcome(
      matched = true,
      pluginId = match.pluginId,
      externalId = match.externalId,
      score = match.score,
      matchedTitle = match.titleSeen,
    )
  }
}
