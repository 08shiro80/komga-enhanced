package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.ChapterUrlImportResult
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Service
class ChapterUrlImporter(
  private val chapterUrlRepository: ChapterUrlRepository,
  private val seriesRepository: SeriesRepository,
  private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) {
  companion object {
    const val TRACKER_FILENAME = ".chapter-urls.json"
  }

  fun scanAndImportLibrary(libraryPath: Path): List<ChapterUrlImportResult> {
    cleanupTrackerFiles(libraryPath)
    return emptyList()
  }

  fun importFromSeriesPath(
    seriesPath: Path,
    seriesId: String? = null,
  ): ChapterUrlImportResult =
    ChapterUrlImportResult(
      seriesId = seriesId ?: "",
      totalInFile = 0,
      imported = 0,
      skippedDuplicates = 0,
    )

  private fun cleanupTrackerFiles(libraryPath: Path) {
    try {
      Files.walk(libraryPath, 2).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .filter { it.fileName.toString() == TRACKER_FILENAME }
          .forEach { trackerFile ->
            try {
              Files.delete(trackerFile)
              logger.info { "Cleaned up legacy tracker file: $trackerFile" }
            } catch (e: Exception) {
              logger.warn(e) { "Failed to delete legacy tracker file: $trackerFile" }
            }
          }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Error scanning for legacy tracker files: $libraryPath" }
    }
  }
}
