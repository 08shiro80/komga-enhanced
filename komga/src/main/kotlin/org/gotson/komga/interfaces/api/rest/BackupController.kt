package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.gotson.komga.domain.service.BackupInfo
import org.gotson.komga.domain.service.BackupLifecycle
import org.gotson.komga.domain.service.FullBackupInfo
import org.gotson.komga.domain.service.RestoreInfo
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@RestController
@Tag(name = "Backup", description = "Database backup and restore operations")
@RequestMapping("/api/v1/backup", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class BackupController(
  private val backupLifecycle: BackupLifecycle,
) {
  @GetMapping
  @Operation(
    summary = "List all backups",
    description = "Get a list of all available database backups",
  )
  fun listBackups(): List<BackupDto> = backupLifecycle.listBackups().map { it.toDto() }

  @PostMapping
  @Operation(
    summary = "Create new backup",
    description = "Create a new backup of the main database",
    responses = [
      ApiResponse(responseCode = "201", description = "Backup created successfully"),
      ApiResponse(responseCode = "500", description = "Failed to create backup"),
    ],
  )
  fun createBackup(): ResponseEntity<BackupDto> =
    try {
      val backup = backupLifecycle.createBackup()
      logger.info { "Backup created via API: ${backup.fileName}" }
      ResponseEntity.status(HttpStatus.CREATED).body(backup.toDto())
    } catch (e: Exception) {
      logger.error(e) { "Failed to create backup via API" }
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create backup: ${e.message}")
    }

  @PostMapping("/full")
  @Operation(
    summary = "Create full backup",
    description = "Create a backup of both main and tasks databases",
    responses = [
      ApiResponse(responseCode = "201", description = "Full backup created successfully"),
      ApiResponse(responseCode = "500", description = "Failed to create backup"),
    ],
  )
  fun createFullBackup(): ResponseEntity<FullBackupDto> =
    try {
      val backup = backupLifecycle.createFullBackup()
      logger.info { "Full backup created via API" }
      ResponseEntity.status(HttpStatus.CREATED).body(backup.toDto())
    } catch (e: Exception) {
      logger.error(e) { "Failed to create full backup via API" }
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create full backup: ${e.message}")
    }

  @GetMapping("/{fileName}/download")
  @Operation(
    summary = "Download backup file",
    description = "Download a specific backup file",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "Backup file",
        content = [Content(mediaType = "application/octet-stream")],
      ),
      ApiResponse(responseCode = "404", description = "Backup not found"),
    ],
  )
  fun downloadBackup(
    @Parameter(description = "Backup file name")
    @PathVariable
    fileName: String,
    response: HttpServletResponse,
  ): ResponseEntity<FileSystemResource> =
    try {
      val file = backupLifecycle.getBackupFile(fileName)

      if (!file.exists()) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Backup file not found: $fileName")
      }

      logger.info { "Downloading backup via API: $fileName" }

      val resource = FileSystemResource(file)

      ResponseEntity
        .ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(file.length())
        .body(resource)
    } catch (e: SecurityException) {
      logger.error(e) { "Security violation: attempted to access file outside backup directory" }
      throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
    } catch (e: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
    } catch (e: Exception) {
      logger.error(e) { "Failed to download backup: $fileName" }
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download backup: ${e.message}")
    }

  @DeleteMapping("/{fileName}")
  @Operation(
    summary = "Delete backup",
    description = "Delete a specific backup file",
    responses = [
      ApiResponse(responseCode = "204", description = "Backup deleted successfully"),
      ApiResponse(responseCode = "404", description = "Backup not found"),
    ],
  )
  fun deleteBackup(
    @Parameter(description = "Backup file name")
    @PathVariable
    fileName: String,
  ): ResponseEntity<Void> =
    try {
      val deleted = backupLifecycle.deleteBackup(fileName)

      if (deleted) {
        logger.info { "Deleted backup via API: $fileName" }
        ResponseEntity.noContent().build()
      } else {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Backup not found: $fileName")
      }
    } catch (e: SecurityException) {
      logger.error(e) { "Security violation: attempted to delete file outside backup directory" }
      throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
    } catch (e: Exception) {
      logger.error(e) { "Failed to delete backup: $fileName" }
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete backup: ${e.message}")
    }

  @PostMapping("/clean")
  @Operation(
    summary = "Clean old backups",
    description = "Delete old backups, keeping only the most recent N backups",
    responses = [
      ApiResponse(responseCode = "200", description = "Old backups cleaned successfully"),
    ],
  )
  fun cleanOldBackups(
    @Parameter(description = "Number of backups to keep (default: 10)")
    @RequestParam(defaultValue = "10")
    keep: Int,
  ): ResponseEntity<CleanupResultDto> =
    try {
      val deleted = backupLifecycle.cleanOldBackups(keep)
      logger.info { "Cleaned $deleted old backups via API" }

      ResponseEntity.ok(
        CleanupResultDto(
          deletedCount = deleted,
          keptCount = keep,
          message = "Deleted $deleted old backups, kept $keep most recent",
        ),
      )
    } catch (e: Exception) {
      logger.error(e) { "Failed to clean old backups" }
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to clean backups: ${e.message}")
    }

  @PostMapping("/restore/{fileName}")
  @Operation(
    summary = "Restore from backup",
    description = "Restore database from a backup file. Note: Requires application restart.",
    responses = [
      ApiResponse(responseCode = "200", description = "Restore prepared"),
      ApiResponse(responseCode = "404", description = "Backup not found"),
    ],
  )
  fun restoreBackup(
    @Parameter(description = "Backup file name")
    @PathVariable
    fileName: String,
  ): ResponseEntity<RestoreInfoDto> =
    try {
      val restoreInfo = backupLifecycle.restoreBackup(fileName)
      logger.warn { "Restore initiated via API: $fileName (requires restart)" }

      ResponseEntity.ok(restoreInfo.toDto())
    } catch (e: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
    } catch (e: SecurityException) {
      logger.error(e) { "Security violation during restore" }
      throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
    } catch (e: Exception) {
      logger.error(e) { "Failed to restore backup: $fileName" }
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to restore backup: ${e.message}")
    }
}

// DTOs
data class BackupDto(
  val fileName: String,
  val filePath: String,
  val createdDate: LocalDateTime,
  val sizeBytes: Long,
  val sizeMb: Double,
  val type: String,
)

data class FullBackupDto(
  val mainDatabase: BackupDto,
  val tasksDatabase: BackupDto,
  val createdDate: LocalDateTime,
  val totalSizeMb: Double,
)

data class RestoreInfoDto(
  val backupFileName: String,
  val requiresRestart: Boolean,
  val message: String,
)

data class CleanupResultDto(
  val deletedCount: Int,
  val keptCount: Int,
  val message: String,
)

// Extension functions
fun BackupInfo.toDto() =
  BackupDto(
    fileName = fileName,
    filePath = filePath,
    createdDate = createdDate,
    sizeBytes = sizeBytes,
    sizeMb = sizeBytes / 1024.0 / 1024.0,
    type = type.name,
  )

fun FullBackupInfo.toDto() =
  FullBackupDto(
    mainDatabase = mainDatabase.toDto(),
    tasksDatabase = tasksDatabase.toDto(),
    createdDate = createdDate,
    totalSizeMb = (mainDatabase.sizeBytes + tasksDatabase.sizeBytes) / 1024.0 / 1024.0,
  )

fun RestoreInfo.toDto() =
  RestoreInfoDto(
    backupFileName = backupFileName,
    requiresRestart = requiresRestart,
    message = message,
  )
