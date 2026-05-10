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
   * Run the match pipeline. Returns null if no provider produced a candidate
   * scoring >= minScore.
   *
   * @param onlyEnabled if true (default), skips providers whose plugin row has `enabled = false`.
   */
  fun match(series: Series, onlyEnabled: Boolean = true): MatchResult? {
    val threshold = minScore()
    val priorities = providerPriority()
    if (priorities.isEmpty()) {
      logger.debug { "Auto-match: empty priority list, skipping series='${series.name}'" }
      return null
    }

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

      // Score every candidate's primary title, take the highest.
      var best: Pair<MetadataSearchResult, Double>? = null
      for (r in results) {
        val s = TitleNormalizer.score(series.name, r.title)
        if (best == null || s > best.second) best = r to s
        if (s == 1.0) break // perfect — stop scanning this provider
      }
      val (cand, score) = best ?: continue
      logger.info { "Auto-match: provider='$short' best='${cand.title}' score=${"%.2f".format(score)} for series='${series.name}'" }
      if (score >= threshold) {
        return MatchResult(
          pluginId = fullPluginId(short),
          provider = provider,
          externalId = cand.externalId,
          score = score,
          titleSeen = cand.title,
          candidate = cand,
        )
      }
    }
    logger.info { "Auto-match: no provider above threshold=${threshold} for series='${series.name}'" }
    return null
  }
}
