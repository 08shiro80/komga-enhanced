package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.ChapterUrl

/**
 * Repository for managing downloaded chapter URLs.
 *
 * Used to track which chapters have been downloaded to prevent duplicates.
 */
interface ChapterUrlRepository {
  /**
   * Find a chapter URL by its ID
   */
  fun findById(id: String): ChapterUrl

  /**
   * Find a chapter URL by its ID, or null if not found
   */
  fun findByIdOrNull(id: String): ChapterUrl?

  /**
   * Find a chapter URL by its URL string
   */
  fun findByUrl(url: String): ChapterUrl?

  /**
   * Check if a URL exists in the database
   */
  fun existsByUrl(url: String): Boolean

  /**
   * Check multiple URLs and return which ones exist
   * @return Map of URL to exists boolean
   */
  fun existsByUrls(urls: Collection<String>): Map<String, Boolean>

  /**
   * Find all chapter URLs for a series
   */
  fun findBySeriesId(seriesId: String): Collection<ChapterUrl>

  /**
   * Find all chapter URLs for a series and language
   */
  fun findBySeriesIdAndLang(
    seriesId: String,
    lang: String,
  ): Collection<ChapterUrl>

  /**
   * Find all downloaded URLs for a series (just the URL strings)
   */
  fun findUrlsBySeriesId(seriesId: String): Collection<String>

  /**
   * Find all downloaded URLs for a series and language
   */
  fun findUrlsBySeriesIdAndLang(
    seriesId: String,
    lang: String,
  ): Collection<String>

  /**
   * Find all chapter URLs
   */
  fun findAll(): Collection<ChapterUrl>

  /**
   * Find chapter URLs by source (e.g., "mangadex", "webtoon")
   */
  fun findBySource(source: String): Collection<ChapterUrl>

  /**
   * Insert a new chapter URL
   */
  fun insert(chapterUrl: ChapterUrl)

  /**
   * Insert multiple chapter URLs in a batch
   */
  fun insertAll(chapterUrls: Collection<ChapterUrl>)

  /**
   * Update an existing chapter URL
   */
  fun update(chapterUrl: ChapterUrl)

  /**
   * Delete a chapter URL by ID
   */
  fun delete(id: String)

  /**
   * Delete all chapter URLs for a series
   */
  fun deleteBySeriesId(seriesId: String)

  /**
   * Delete a chapter URL by its URL string
   */
  fun deleteByUrl(url: String)

  /**
   * Count all chapter URLs
   */
  fun count(): Long

  /**
   * Count chapter URLs for a series
   */
  fun countBySeriesId(seriesId: String): Long

  /**
   * Count chapter URLs for a series and language
   */
  fun countBySeriesIdAndLang(
    seriesId: String,
    lang: String,
  ): Long

  /**
   * Get distinct languages for a series
   */
  fun findDistinctLangsBySeriesId(seriesId: String): Collection<String>

  /**
   * Find chapter URLs by chapter number range
   */
  fun findBySeriesIdAndChapterRange(
    seriesId: String,
    minChapter: Double,
    maxChapter: Double,
  ): Collection<ChapterUrl>

  /**
   * Delete all chapter URLs
   */
  fun deleteAll(): Long

  /**
   * Delete chapter URLs downloaded within a date range
   */
  fun deleteByDateRange(
    from: java.time.LocalDateTime,
    to: java.time.LocalDateTime,
  ): Long

  /**
   * Count chapter URLs downloaded within a date range
   */
  fun countByDateRange(
    from: java.time.LocalDateTime,
    to: java.time.LocalDateTime,
  ): Long

  /**
   * Find chapter URLs by date range
   */
  fun findByDateRange(
    from: java.time.LocalDateTime,
    to: java.time.LocalDateTime,
  ): Collection<ChapterUrl>
}
