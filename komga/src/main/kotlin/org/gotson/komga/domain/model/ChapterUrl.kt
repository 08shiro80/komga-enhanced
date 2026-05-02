package org.gotson.komga.domain.model

import java.time.LocalDateTime

/**
 * Represents a downloaded chapter URL for duplicate detection.
 *
 * This model tracks which chapter URLs have been downloaded for a series,
 * enabling Komga to prevent duplicate downloads by checking if a chapter
 * URL already exists in the database before starting a download.
 *
 * The workflow is:
 * 1. gallery-dl downloads chapters and writes .chapter-urls.json
 * 2. Komga library scan imports URLs from .chapter-urls.json
 * 3. Before starting a new download, Komga queries this table
 * 4. Only chapters not in this table are downloaded
 */
data class ChapterUrl(
  val id: String,
  val seriesId: String,
  val url: String,
  val chapter: Double,
  val volume: Int? = null,
  val title: String? = null,
  val lang: String = "en",
  val downloadedAt: LocalDateTime,
  val source: String = "gallery-dl",
  val chapterId: String? = null,
  val scanlationGroup: String? = null,
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable {
  /**
   * Check if this chapter matches a given URL (case-insensitive)
   */
  fun matchesUrl(otherUrl: String): Boolean = url.equals(otherUrl, ignoreCase = true)

  /**
   * Check if this is the same chapter (by chapter number and language)
   */
  fun isSameChapter(
    otherChapter: Double,
    otherLang: String = "en",
  ): Boolean = chapter == otherChapter && lang == otherLang
}

/**
 * DTO for importing chapter URLs from .chapter-urls.json
 */
data class ChapterUrlImportDto(
  val url: String,
  val chapter: Double,
  val volume: Int? = null,
  val title: String? = null,
  val lang: String = "en",
  val downloadedAt: String? = null,
  val source: String = "gallery-dl",
  val chapterId: String? = null,
  val scanlationGroup: String? = null,
) {
  /**
   * Convert to ChapterUrl domain object
   */
  fun toChapterUrl(
    seriesId: String,
    id: String,
  ): ChapterUrl =
    ChapterUrl(
      id = id,
      seriesId = seriesId,
      url = url,
      chapter = chapter,
      volume = volume,
      title = title,
      lang = lang,
      downloadedAt =
        downloadedAt?.let {
          try {
            LocalDateTime.parse(it.replace("Z", "").substringBefore("+"))
          } catch (e: Exception) {
            LocalDateTime.now()
          }
        } ?: LocalDateTime.now(),
      source = source,
      chapterId = chapterId,
      scanlationGroup = scanlationGroup,
    )
}

/**
 * Result of a chapter URL import operation
 */
data class ChapterUrlImportResult(
  val seriesId: String,
  val totalInFile: Int,
  val imported: Int,
  val skippedDuplicates: Int,
  val errors: List<String> = emptyList(),
) {
  val success: Boolean
    get() = errors.isEmpty()
}

/**
 * Response for duplicate check API
 */
data class ChapterUrlCheckResult(
  val url: String,
  val exists: Boolean,
  val seriesId: String? = null,
  val chapter: Double? = null,
  val downloadedAt: LocalDateTime? = null,
)

/**
 * Batch check result for multiple URLs
 */
data class ChapterUrlBatchCheckResult(
  val results: Map<String, Boolean>,
  val existingCount: Int,
  val newCount: Int,
) {
  val totalChecked: Int
    get() = results.size
}
