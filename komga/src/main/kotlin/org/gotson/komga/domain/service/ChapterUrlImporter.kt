package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.ChapterUrl
import org.gotson.komga.domain.model.ChapterUrlImportDto
import org.gotson.komga.domain.model.ChapterUrlImportResult
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * Service for importing chapter URLs from .chapter-urls.json files.
 *
 * This service is called during library scans to import chapter URL tracking data
 * written by gallery-dl's chapter_tracker postprocessor.
 *
 * The import process:
 * 1. Find .chapter-urls.json in series directory
 * 2. Parse the JSON file
 * 3. Match to existing series by path
 * 4. Import new URLs (skip duplicates)
 * 5. Delete the .chapter-urls.json file after successful import
 */
@Service
class ChapterUrlImporter(
  private val chapterUrlRepository: ChapterUrlRepository,
  private val seriesRepository: SeriesRepository,
  private val objectMapper: ObjectMapper,
) {
  companion object {
    const val TRACKER_FILENAME = ".chapter-urls.json"
  }

  /**
   * Import chapter URLs from a series directory.
   *
   * @param seriesPath Path to the series directory
   * @param seriesId ID of the series (if known)
   * @return Import result with statistics
   */
  fun importFromSeriesPath(
    seriesPath: Path,
    seriesId: String? = null,
  ): ChapterUrlImportResult {
    val trackerFile = seriesPath.resolve(TRACKER_FILENAME)

    if (!Files.exists(trackerFile)) {
      logger.debug { "No tracker file found at $trackerFile" }
      return ChapterUrlImportResult(
        seriesId = seriesId ?: "",
        totalInFile = 0,
        imported = 0,
        skippedDuplicates = 0,
      )
    }

    logger.info { "Found chapter tracker file: $trackerFile" }

    try {
      // Read and parse the JSON file
      val content = Files.readString(trackerFile)
      val trackerData = objectMapper.readValue<ChapterTrackerData>(content)

      // Determine series ID
      val resolvedSeriesId =
        seriesId
          ?: findSeriesIdByPath(seriesPath)
          ?: run {
            logger.warn { "Could not find series for path: $seriesPath" }
            return ChapterUrlImportResult(
              seriesId = "",
              totalInFile = trackerData.chapters.size,
              imported = 0,
              skippedDuplicates = 0,
              errors = listOf("Series not found for path: $seriesPath"),
            )
          }

      // Import chapters
      val result = importChapters(resolvedSeriesId, trackerData.chapters)

      // Delete tracker file after successful import
      if (result.success) {
        try {
          Files.delete(trackerFile)
          logger.info { "Deleted tracker file after successful import: $trackerFile" }
        } catch (e: Exception) {
          logger.warn(e) { "Failed to delete tracker file: $trackerFile" }
        }
      }

      return result
    } catch (e: Exception) {
      logger.error(e) { "Failed to import chapter URLs from $trackerFile" }
      return ChapterUrlImportResult(
        seriesId = seriesId ?: "",
        totalInFile = 0,
        imported = 0,
        skippedDuplicates = 0,
        errors = listOf("Failed to parse tracker file: ${e.message}"),
      )
    }
  }

  /**
   * Import chapters for a series.
   */
  private fun importChapters(
    seriesId: String,
    chapters: List<ChapterTrackerChapter>,
  ): ChapterUrlImportResult {
    var imported = 0
    var skippedDuplicates = 0
    val errors = mutableListOf<String>()

    // Get existing URLs for this series
    val existingUrls = chapterUrlRepository.findUrlsBySeriesId(seriesId).toSet()

    chapters.forEach { chapter ->
      try {
        if (chapter.url in existingUrls) {
          skippedDuplicates++
          logger.debug { "Skipping duplicate URL: ${chapter.url}" }
          return@forEach
        }

        // Check if URL exists globally (might be linked to different series)
        if (chapterUrlRepository.existsByUrl(chapter.url)) {
          skippedDuplicates++
          logger.debug { "URL already exists in database: ${chapter.url}" }
          return@forEach
        }

        // Create and insert new ChapterUrl
        val chapterUrl =
          ChapterUrl(
            id = TsidCreator.getTsid256().toString(),
            seriesId = seriesId,
            url = chapter.url,
            chapter = chapter.chapter,
            volume = chapter.volume,
            title = chapter.title,
            lang = chapter.lang,
            downloadedAt = parseDateTime(chapter.downloadedAt),
            source = chapter.source,
            chapterId = chapter.chapterId,
            scanlationGroup = chapter.scanlationGroup,
          )

        chapterUrlRepository.insert(chapterUrl)
        imported++
        logger.debug { "Imported chapter URL: ${chapter.url} (ch. ${chapter.chapter})" }
      } catch (e: Exception) {
        val error = "Failed to import chapter ${chapter.chapter}: ${e.message}"
        errors.add(error)
        logger.warn(e) { error }
      }
    }

    logger.info {
      "Import complete for series $seriesId: $imported imported, $skippedDuplicates duplicates skipped"
    }

    return ChapterUrlImportResult(
      seriesId = seriesId,
      totalInFile = chapters.size,
      imported = imported,
      skippedDuplicates = skippedDuplicates,
      errors = errors,
    )
  }

  /**
   * Find series ID by its file path.
   */
  private fun findSeriesIdByPath(path: Path): String? {
    val pathStr = path.toString()
    return try {
      seriesRepository
        .findAll()
        .find { series ->
          series.path.toString() == pathStr ||
            series.path.toString().replace("\\", "/") == pathStr.replace("\\", "/")
        }?.id
    } catch (e: Exception) {
      logger.warn(e) { "Error finding series by path: $path" }
      null
    }
  }

  /**
   * Parse ISO datetime string to LocalDateTime.
   */
  private fun parseDateTime(dateStr: String?): LocalDateTime {
    if (dateStr.isNullOrBlank()) return LocalDateTime.now()
    return try {
      // Handle various ISO formats
      val normalized =
        dateStr
          .replace("Z", "")
          .substringBefore("+")
          .substringBefore("-", dateStr.substringBeforeLast("-"))
      LocalDateTime.parse(normalized)
    } catch (e: Exception) {
      logger.debug { "Failed to parse date '$dateStr', using current time" }
      LocalDateTime.now()
    }
  }

  /**
   * Scan a library path for all .chapter-urls.json files and import them.
   */
  fun scanAndImportLibrary(libraryPath: Path): List<ChapterUrlImportResult> {
    val results = mutableListOf<ChapterUrlImportResult>()

    try {
      Files.walk(libraryPath, 2).use { stream ->
        stream
          .filter { Files.isDirectory(it) }
          .filter { Files.exists(it.resolve(TRACKER_FILENAME)) }
          .forEach { seriesPath ->
            val result = importFromSeriesPath(seriesPath)
            if (result.totalInFile > 0) {
              results.add(result)
            }
          }
      }
    } catch (e: Exception) {
      logger.error(e) { "Error scanning library for tracker files: $libraryPath" }
    }

    val totalImported = results.sumOf { it.imported }
    val totalSkipped = results.sumOf { it.skippedDuplicates }
    logger.info {
      "Library scan complete: ${results.size} series processed, $totalImported URLs imported, $totalSkipped duplicates skipped"
    }

    return results
  }
}

/**
 * Data class for parsing .chapter-urls.json
 */
private data class ChapterTrackerData(
  val manga_id: String? = null,
  val source: String? = null,
  val updated_at: String? = null,
  val chapters: List<ChapterTrackerChapter> = emptyList(),
)

/**
 * Data class for chapter entries in .chapter-urls.json
 */
private data class ChapterTrackerChapter(
  val url: String,
  val chapter: Double = 0.0,
  val volume: Int? = null,
  val title: String? = null,
  val lang: String = "en",
  val downloaded_at: String? = null,
  val source: String = "gallery-dl",
  val chapter_id: String? = null,
  val scanlation_group: String? = null,
) {
  // Provide snake_case to camelCase mapping
  val downloadedAt: String? get() = downloaded_at
  val chapterId: String? get() = chapter_id
  val scanlationGroup: String? get() = scanlation_group
}
