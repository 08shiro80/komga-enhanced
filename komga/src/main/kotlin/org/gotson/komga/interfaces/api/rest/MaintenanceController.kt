package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.infrastructure.maintenance.FixRegistry
import org.gotson.komga.infrastructure.maintenance.ThumbnailMigrationExecutor
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration.TagNames.DOWNLOADS
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/v1/maintenance", produces = ["application/json"])
@PreAuthorize("hasRole('ADMIN')")
class MaintenanceController(
  private val fixRegistry: FixRegistry,
  private val thumbnailMigrationExecutor: ThumbnailMigrationExecutor,
) {
  @GetMapping("fixes")
  @Operation(summary = "List all available one-click maintenance fixes (schema-driven, rendered dynamically by the UI)", tags = [DOWNLOADS])
  fun listFixes(): List<FixRegistry.Fix> = fixRegistry.findAll()

  @PostMapping("thumbnails/migrate")
  @Operation(summary = "Move existing thumbnail images out of the main database into the external thumbnails database", tags = [DOWNLOADS])
  fun migrateThumbnails(): Map<String, Int> =
    mapOf(
      "books" to thumbnailMigrationExecutor.migrateBookThumbnails(),
      "pageHashes" to thumbnailMigrationExecutor.migratePageHashThumbnails(),
    )

  @PostMapping("thumbnails/cleanup-orphans")
  @Operation(summary = "Delete externalized thumbnails whose owning book or page-hash no longer exists", tags = [DOWNLOADS])
  fun cleanupOrphanThumbnails(): Map<String, Int> = mapOf("deleted" to thumbnailMigrationExecutor.cleanupOrphans())

  @PostMapping("thumbnails/vacuum")
  @Operation(summary = "Reclaim freed space in the main database after migrating thumbnails (takes an exclusive lock and can run for a while)", tags = [DOWNLOADS])
  fun vacuumMainDatabase() {
    thumbnailMigrationExecutor.vacuum()
  }
}
