package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.gotson.komga.infrastructure.mangadex.MangaDexClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.io.File
import java.time.LocalDateTime
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

@RestController
@Tag(name = "Health", description = "System health check endpoints")
@RequestMapping("/api/v1/health", produces = [MediaType.APPLICATION_JSON_VALUE])
class HealthCheckController(
  private val galleryDlWrapper: GalleryDlWrapper,
  private val mangaDexClient: MangaDexClient,
  private val dataSource: DataSource,
  @Value("\${komga.config-dir:#{systemProperties['user.home']+'/.komga'}}")
  private val configDir: String,
) {
  /**
   * Comprehensive health check for all download-related components.
   */
  @GetMapping
  @Operation(
    summary = "Full health check",
    description = "Check health status of all system components including gallery-dl, MangaDex API, database, and disk space",
  )
  fun healthCheck(): HealthCheckResponse {
    val checks = mutableMapOf<String, ComponentHealth>()

    // 1. gallery-dl Check
    checks["gallery-dl"] = checkGalleryDl()

    // 2. MangaDex API Check
    checks["mangadex-api"] = checkMangaDex()

    // 3. Database Check
    checks["database"] = checkDatabase()

    // 4. Disk Space Check
    checks["disk-space"] = checkDiskSpace()

    val overallStatus =
      when {
        checks.values.all { it.status == "UP" } -> "UP"
        checks.values.any { it.status == "DOWN" } -> "DEGRADED"
        else -> "UNKNOWN"
      }

    return HealthCheckResponse(
      status = overallStatus,
      timestamp = LocalDateTime.now(),
      components = checks,
    )
  }

  /**
   * Quick ping check - minimal overhead.
   */
  @GetMapping("/ping")
  @Operation(
    summary = "Quick ping",
    description = "Simple health check that returns immediately - useful for load balancers",
  )
  fun ping(): Map<String, String> = mapOf("status" to "OK", "timestamp" to LocalDateTime.now().toString())

  /**
   * Check a specific component.
   */
  @GetMapping("/{component}")
  @Operation(
    summary = "Check specific component",
    description = "Check health status of a specific component (gallery-dl, mangadex, database, disk-space)",
  )
  fun checkComponent(
    @PathVariable component: String,
  ): ComponentHealth =
    when (component.lowercase()) {
      "gallery-dl", "gallerydl" -> checkGalleryDl()
      "mangadex", "mangadex-api" -> checkMangaDex()
      "database", "db" -> checkDatabase()
      "disk-space", "disk", "storage" -> checkDiskSpace()
      else -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown component: $component")
    }

  private fun checkGalleryDl(): ComponentHealth =
    try {
      val installed = galleryDlWrapper.isInstalled()

      ComponentHealth(
        status = if (installed) "UP" else "DOWN",
        details =
          mapOf(
            "installed" to installed,
            "message" to if (installed) "gallery-dl is available" else "gallery-dl not found - install with: pip install gallery-dl",
          ),
      )
    } catch (e: Exception) {
      logger.warn(e) { "gallery-dl health check failed" }
      ComponentHealth(
        status = "DOWN",
        error = e.message,
        details = mapOf("installed" to false),
      )
    }

  private fun checkMangaDex(): ComponentHealth =
    try {
      // Simple connectivity test - search for a known manga
      val testResult = mangaDexClient.searchManga("one piece", limit = 1)

      if (testResult.isNotEmpty()) {
        ComponentHealth(
          status = "UP",
          details =
            mapOf(
              "connected" to true,
              "apiUrl" to "https://api.mangadex.org",
              "testQuery" to "Search returned ${testResult.size} result(s)",
            ),
        )
      } else {
        ComponentHealth(
          status = "UP",
          details =
            mapOf(
              "connected" to true,
              "apiUrl" to "https://api.mangadex.org",
              "testQuery" to "Search returned 0 results (API is responding)",
            ),
        )
      }
    } catch (e: Exception) {
      logger.warn(e) { "MangaDex API health check failed" }
      ComponentHealth(
        status = "DOWN",
        error = e.message,
        details =
          mapOf(
            "connected" to false,
            "apiUrl" to "https://api.mangadex.org",
          ),
      )
    }

  private fun checkDatabase(): ComponentHealth =
    try {
      dataSource.connection.use { connection ->
        val valid = connection.isValid(5) // 5 second timeout

        ComponentHealth(
          status = if (valid) "UP" else "DOWN",
          details =
            mapOf(
              "connected" to valid,
              "database" to (connection.metaData?.databaseProductName ?: "unknown"),
              "version" to (connection.metaData?.databaseProductVersion ?: "unknown"),
            ),
        )
      }
    } catch (e: Exception) {
      logger.warn(e) { "Database health check failed" }
      ComponentHealth(
        status = "DOWN",
        error = e.message,
        details = mapOf("connected" to false),
      )
    }

  private fun checkDiskSpace(): ComponentHealth =
    try {
      val configDirFile = File(configDir)
      val root = configDirFile.toPath().root?.toFile() ?: configDirFile

      val totalSpace = root.totalSpace
      val freeSpace = root.freeSpace
      val usableSpace = root.usableSpace
      val usedSpace = totalSpace - freeSpace
      val usedPercent = if (totalSpace > 0) (usedSpace.toDouble() / totalSpace * 100).toInt() else 0

      // Warn if less than 1GB free or more than 90% used
      val isLow = freeSpace < 1_073_741_824L || usedPercent > 90
      val isCritical = freeSpace < 104_857_600L || usedPercent > 95 // Less than 100MB

      val details =
        mutableMapOf<String, Any>(
          "path" to configDir,
          "totalBytes" to totalSpace,
          "totalGb" to String.format("%.2f", totalSpace / 1_073_741_824.0),
          "freeBytes" to freeSpace,
          "freeGb" to String.format("%.2f", freeSpace / 1_073_741_824.0),
          "usableBytes" to usableSpace,
          "usedPercent" to usedPercent,
        )
      if (isLow) {
        details["warning"] = "Low disk space"
      }

      ComponentHealth(
        status =
          when {
            isCritical -> "DOWN"
            isLow -> "WARN"
            else -> "UP"
          },
        details = details,
      )
    } catch (e: Exception) {
      logger.warn(e) { "Disk space health check failed" }
      ComponentHealth(
        status = "UNKNOWN",
        error = e.message,
        details = mapOf("path" to configDir),
      )
    }
}

data class HealthCheckResponse(
  val status: String,
  val timestamp: LocalDateTime,
  val components: Map<String, ComponentHealth>,
)

data class ComponentHealth(
  val status: String,
  val error: String? = null,
  val details: Map<String, Any> = emptyMap(),
)
