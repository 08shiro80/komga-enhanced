package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.infrastructure.maintenance.FixRegistry
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration.TagNames.DOWNLOADS
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/v1/maintenance", produces = ["application/json"])
@PreAuthorize("hasRole('ADMIN')")
class MaintenanceController(
  private val fixRegistry: FixRegistry,
) {
  @GetMapping("fixes")
  @Operation(summary = "List all available one-click maintenance fixes (schema-driven, rendered dynamically by the UI)", tags = [DOWNLOADS])
  fun listFixes(): List<FixRegistry.Fix> = fixRegistry.findAll()
}
