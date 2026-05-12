package org.gotson.komga.infrastructure.automatch

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.domain.service.MetadataSearchResult
import org.gotson.komga.domain.service.OnlineMetadataProvider
import org.gotson.komga.infrastructure.metadata.anilist.AniListMetadataPlugin
import org.gotson.komga.infrastructure.metadata.kitsu.KitsuMetadataPlugin
import org.gotson.komga.infrastructure.metadata.mangadex.MangaDexMetadataPlugin
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Best-match result + the `OnlineMetadataProvider` instance so the caller can
 * fetch full details without re-resolving the plugin.
 */
data class MatchResult(
  val pluginId: String,
  val provider: OnlineMetadataProvider,
  val externalId: String,
  val score: Double,
  val titleSeen: String,
  val candidate: MetadataSearchResult,
)

/**
 * Result of scanning all providers: [primary] is the first (by priority) whose
 * best title score clears [min_score]; [trackerLinkMatches] includes every provider
 * whose best score clears the (lower) tracker-links threshold for extra URLs in series.json.
 */
data class AutomatchScanResult(
  val primary: MatchResult?,
  val trackerLinkMatches: List<MatchResult>,
)

/**
 * Picks the best metadata match for a Komga `Series` across enabled metadata
 * plugins. Komf-style: walk a configured priority list, search each provider
 * with a normalized query, and accept the first provider whose top candidate
 * scores above the threshold. We don't aggregate across providers — once a
 * confident match is found in (e.g.) AniList we stop, mirroring user intent
 * "use my preferred source if it has it; otherwise fall back".
 */
@Service
class AutoMetadataMatcher(
  private val pluginRepository: PluginRepository,
  private val pluginConfigRepository: PluginConfigRepository,
  private val anilist: AniListMetadataPlugin,
  private val mangadex: MangaDexMetadataPlugin,
  private val kitsu: KitsuMetadataPlugin,
) {
  companion object {
    const val AUTO_PLUGIN_ID = "auto-metadata"
    const val DEFAULT_PRIORITY = "anilist,mangadex,kitsu"
    const val DEFAULT_MIN_SCORE = 0.85
  }

  private fun providerById(id: String): OnlineMetadataProvider? =
    when (id) {
      "anilist", "anilist-metadata" -> anilist
      "mangadex", "mangadex-metadata" -> mangadex
      "kitsu", "kitsu-metadata" -> kitsu
      else -> null
    }

  private fun fullPluginId(short: String): String =
    if (short.endsWith("-metadata")) short else "$short-metadata"

  fun isEnabled(): Boolean =
    pluginConfigRepository
      .findByPluginIdAndKey(AUTO_PLUGIN_ID, "enabled")
      ?.configValue
      ?.equals("true", ignoreCase = true) ?: false

  fun providerPriority(): List<String> {
    val csv =
      pluginConfigRepository
        .findByPluginIdAndKey(AUTO_PLUGIN_ID, "provider_priority")
        ?.configValue
        ?.takeIf { it.isNotBlank() } ?: DEFAULT_PRIORITY
    return csv.split(',').map { it.trim().lowercase().removeSuffix("-metadata") }.filter { it.isNotBlank() }
  }

  fun minScore(): Double =
    pluginConfigRepository
      .findByPluginIdAndKey(AUTO_PLUGIN_ID, "min_score")
      ?.configValue
      ?.toDoubleOrNull() ?: DEFAULT_MIN_SCORE

  /**
   * Floor for extra `tracker_links` in series.json. Defaults to `min_score - 0.08`
   * (never above [minScore]). Optional config `min_score_tracker_links` overrides.
   */
  fun minScoreForTrackerLinks(): Double {
    val p = minScore()
    val explicit =
      pluginConfigRepository
        .findByPluginIdAndKey(AUTO_PLUGIN_ID, "min_score_tracker_links")
        ?.configValue
        ?.toDoubleOrNull()
    val raw = explicit ?: (p - 0.08)
    return raw.coerceIn(0.0, p)
  }

  fun excludedLibraryIds(): Set<String> =
    pluginConfigRepository
      .findByPluginIdAndKey(AUTO_PLUGIN_ID, "exclude_library_ids")
      ?.configValue
      ?.split(',')
      ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
      ?.toSet()
      ?: emptySet()

  fun isLibraryExcluded(libraryId: String): Boolean = libraryId in excludedLibraryIds()

  /**
   * Single pass over [providerPriority]: first provider meeting [minScore] becomes [AutomatchScanResult.primary];
   * every provider whose best candidate meets [minScoreForTrackerLinks] is listed in [AutomatchScanResult.trackerLinkMatches].
   */
  fun scan(
    series: Series,
    onlyEnabled: Boolean = true,
  ): AutomatchScanResult {
    val primaryThreshold = minScore()
    val linkThreshold = minScoreForTrackerLinks()
    val priorities = providerPriority()
    if (priorities.isEmpty()) {
      logger.debug { "Auto-match: empty priority list, skipping series='${series.name}'" }
      return AutomatchScanResult(null, emptyList())
    }

    var primary: MatchResult? = null
    val trackerLinkMatches = mutableListOf<MatchResult>()

    for (short in priorities) {
      val provider = providerById(short) ?: run {
        logger.warn { "Auto-match: unknown provider '$short' in priority list" }
        continue
      }
      if (onlyEnabled) {
        val plugin = pluginRepository.findByIdOrNull(fullPluginId(short))
        if (plugin == null || !plugin.enabled) {
          logger.debug { "Auto-match: provider '$short' disabled or missing, skipping for series='${series.name}'" }
          continue
        }
      }

      val results =
        try {
          provider.search(series.name)
        } catch (e: Exception) {
          logger.warn(e) { "Auto-match: search failed on '$short' for series='${series.name}'" }
          emptyList()
        }
      if (results.isEmpty()) continue

      var best: Pair<MetadataSearchResult, Double>? = null
      for (r in results) {
        val s = TitleNormalizer.score(series.name, r.title)
        if (best == null || s > best.second) best = r to s
        if (s == 1.0) break
      }
      val (cand, score) = best ?: continue
      logger.debug { "Auto-match: provider='$short' best='${cand.title}' score=${"%.2f".format(score)} for series='${series.name}'" }

      if (score >= linkThreshold) {
        trackerLinkMatches.add(
          MatchResult(
            pluginId = fullPluginId(short),
            provider = provider,
            externalId = cand.externalId,
            score = score,
            titleSeen = cand.title,
            candidate = cand,
          ),
        )
      }
      if (primary == null && score >= primaryThreshold) {
        primary =
          MatchResult(
            pluginId = fullPluginId(short),
            provider = provider,
            externalId = cand.externalId,
            score = score,
            titleSeen = cand.title,
            candidate = cand,
          )
      }
    }

    if (primary == null) {
      logger.debug { "Auto-match: no provider above primary threshold=$primaryThreshold for series='${series.name}'" }
    }
    return AutomatchScanResult(primary, trackerLinkMatches)
  }

  fun match(series: Series, onlyEnabled: Boolean = true): MatchResult? = scan(series, onlyEnabled).primary
}
