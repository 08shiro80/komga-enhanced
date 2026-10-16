package org.gotson.komga.infrastructure.maintenance

import org.gotson.komga.domain.persistence.PluginRepository
import org.springframework.stereotype.Component

@Component
class FixRegistry(
  private val pluginRepository: PluginRepository,
) {
  data class Fix(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val endpoint: String,
    val method: String,
    val params: List<Param>,
  )

  data class Param(
    val key: String,
    val label: String,
    val type: String,
    val default: Any?,
    val min: Int?,
    val max: Int?,
    val hint: String?,
  )

  private data class Registration(
    val fix: Fix,
    val isEnabled: () -> Boolean,
  )

  private val registrations: List<Registration> =
    listOf(
      Registration(
        fix =
          Fix(
            id = "mangadex-force-resync",
            title = "MangaDex Subscription — Force Resync",
            description = "Rewinds last_check_time and re-runs the followed-manga feed check now. Use when a manga's chapters were silently skipped (e.g. an old COMPLETED queue row blocked them) or after a long restart gap. isChapterKnown still blocks duplicates.",
            icon = "mdi-wrench-outline",
            endpoint = "/api/v1/downloads/mangadex-subscription/force-resync",
            method = "POST",
            params =
              listOf(
                Param(
                  key = "lookbackDays",
                  label = "Lookback window (days)",
                  type = "number",
                  default = 7,
                  min = 1,
                  max = 30,
                  hint = "How far back to scan the followed-manga feed",
                ),
              ),
          ),
        isEnabled = { pluginRepository.findByIdOrNull("mangadex-subscription")?.enabled == true },
      ),
      Registration(
        fix =
          Fix(
            id = "import-follow-txt",
            title = "Import legacy follow.txt → Follow list",
            description = "One-time migration: reads the selected library's follow.txt file and inserts its URLs into the DB-backed follow list (duplicates are skipped). The follow.txt file is left untouched.",
            icon = "mdi-file-import-outline",
            endpoint = "/api/v1/downloads/follows/{libraryId}/import-follow-txt",
            method = "POST",
            params =
              listOf(
                Param(
                  key = "libraryId",
                  label = "Library",
                  type = "library",
                  default = null,
                  min = null,
                  max = null,
                  hint = "Library whose follow.txt should be imported",
                ),
              ),
          ),
        isEnabled = { true },
      ),
      Registration(
        fix =
          Fix(
            id = "thumbnails-migrate",
            title = "Externalize thumbnails → shrink main database",
            description = "Moves existing book and duplicate-page thumbnail images out of the main database into the separate thumbnails database. New thumbnails already go there automatically. Safe to re-run and resumable; run the VACUUM fix afterwards to actually reclaim the freed space.",
            icon = "mdi-database-arrow-right-outline",
            endpoint = "/api/v1/maintenance/thumbnails/migrate",
            method = "POST",
            params = emptyList(),
          ),
        isEnabled = { true },
      ),
      Registration(
        fix =
          Fix(
            id = "thumbnails-vacuum",
            title = "Reclaim database space (VACUUM)",
            description = "Compacts the main database to reclaim space freed by externalizing thumbnails. Takes an exclusive lock and can run for a while on large databases, and needs free disk space roughly equal to the database size. Run after migrating thumbnails.",
            icon = "mdi-database-refresh-outline",
            endpoint = "/api/v1/maintenance/thumbnails/vacuum",
            method = "POST",
            params = emptyList(),
          ),
        isEnabled = { true },
      ),
      Registration(
        fix =
          Fix(
            id = "thumbnails-cleanup-orphans",
            title = "Clean up orphaned thumbnails",
            description = "Deletes externalized thumbnail images whose owning book or page-hash no longer exists in the main database. A safety net for the rare case a delete did not fully propagate across both databases.",
            icon = "mdi-broom",
            endpoint = "/api/v1/maintenance/thumbnails/cleanup-orphans",
            method = "POST",
            params = emptyList(),
          ),
        isEnabled = { true },
      ),
    )

  fun findAll(): List<Fix> =
    registrations
      .filter { it.isEnabled() }
      .map { it.fix }
}
