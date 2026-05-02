package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.service.TachiyomiImportResult
import org.gotson.komga.domain.service.TachiyomiImporter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files

private val logger = KotlinLogging.logger {}

@RestController
@Tag(name = "Import", description = "Import operations for external app data")
@RequestMapping("/api/v1/import", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class ImportController(
  private val tachiyomiImporter: TachiyomiImporter,
  private val libraryRepository: LibraryRepository,
) {
  @PostMapping("/tachiyomi", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(
    summary = "Import Tachiyomi backup",
    description = "Import MangaDex manga from a Tachiyomi/Mihon backup file (.proto.gz, .tachibk, or .json) into a library's follow.txt",
    responses = [
      ApiResponse(responseCode = "200", description = "Import completed successfully"),
      ApiResponse(responseCode = "400", description = "Invalid backup file or format"),
      ApiResponse(responseCode = "404", description = "Library not found"),
      ApiResponse(responseCode = "500", description = "Import failed"),
    ],
  )
  fun importTachiyomi(
    @Parameter(description = "Tachiyomi backup file (.proto.gz, .tachibk, or .json)")
    @RequestParam("file")
    file: MultipartFile,
    @Parameter(description = "Target library ID where follow.txt will be updated")
    @RequestParam("libraryId")
    libraryId: String,
  ): ResponseEntity<TachiyomiImportResultDto> {
    logger.info { "Received Tachiyomi import request for library: $libraryId" }

    // Validate file
    if (file.isEmpty) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty")
    }

    val originalFilename = file.originalFilename ?: "backup"
    logger.info { "Processing backup file: $originalFilename (${file.size} bytes)" }

    // Find library
    val library =
      libraryRepository.findByIdOrNull(libraryId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found: $libraryId")

    // Create temporary file
    val tempFile = Files.createTempFile("tachiyomi-import-", getExtension(originalFilename))

    return try {
      // Save uploaded file to temp
      file.transferTo(tempFile)
      logger.debug { "Saved backup to temp file: $tempFile" }

      // Run import
      val result = tachiyomiImporter.importBackup(tempFile, library)

      logger.info {
        "Tachiyomi import completed: ${result.successCount} imported, ${result.skipped.size} skipped, ${result.errors.size} errors"
      }

      ResponseEntity.ok(result.toDto())
    } catch (e: IllegalArgumentException) {
      logger.error(e) { "Invalid backup format" }
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid backup format: ${e.message}")
    } catch (e: Exception) {
      logger.error(e) { "Failed to import Tachiyomi backup" }
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Import failed: ${e.message}")
    } finally {
      // Clean up temp file
      try {
        Files.deleteIfExists(tempFile)
        logger.debug { "Deleted temp file: $tempFile" }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to delete temp file: $tempFile" }
      }
    }
  }

  private fun getExtension(filename: String): String =
    when {
      filename.endsWith(".proto.gz") -> ".proto.gz"
      filename.endsWith(".tachibk") -> ".tachibk"
      filename.endsWith(".json.gz") -> ".json.gz"
      filename.endsWith(".json") -> ".json"
      else -> ".backup"
    }
}

// DTOs
data class TachiyomiImportResultDto(
  val totalInBackup: Int,
  val mangaDexCount: Int,
  val importedCount: Int,
  val skippedCount: Int,
  val errorCount: Int,
  val imported: List<String>,
  val skipped: List<String>,
  val errors: List<String>,
  val success: Boolean,
  val message: String,
)

// Extension function
fun TachiyomiImportResult.toDto() =
  TachiyomiImportResultDto(
    totalInBackup = totalInBackup,
    mangaDexCount = mangaDexCount,
    importedCount = imported.size,
    skippedCount = skipped.size,
    errorCount = errors.size,
    imported = imported,
    skipped = skipped,
    errors = errors,
    success = !hasErrors || imported.isNotEmpty(),
    message =
      buildString {
        append("Imported ${imported.size} manga from $mangaDexCount MangaDex entries")
        if (skipped.isNotEmpty()) append(", skipped ${skipped.size} (already exist)")
        if (errors.isNotEmpty()) append(", ${errors.size} errors")
      },
  )
