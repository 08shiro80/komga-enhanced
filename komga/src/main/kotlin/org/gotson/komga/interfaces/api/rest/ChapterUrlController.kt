package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.gotson.komga.domain.model.ChapterUrl
import org.gotson.komga.domain.model.ChapterUrlBatchCheckResult
import org.gotson.komga.domain.model.ChapterUrlCheckResult
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.infrastructure.mangadex.MangaDexClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/**
 * REST controller for chapter URL tracking.
 *
 * This API enables gallery-dl and other download tools to:
 * - Check if chapters have already been downloaded (duplicate prevention)
 * - Get list of downloaded URLs for a series
 * - Calculate which chapters are new (not yet downloaded)
 */
@RestController
@RequestMapping("api/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Chapter URLs", description = "Chapter URL tracking for duplicate prevention")
class ChapterUrlController(
  private val chapterUrlRepository: ChapterUrlRepository,
  private val mangaDexClient: MangaDexClient,
) {
  /**
   * Check if a single URL has been downloaded.
   * This is the primary endpoint for gallery-dl to check before downloading.
   */
  @GetMapping("/check-url")
  @Operation(summary = "Check if a URL has been downloaded")
  fun checkUrl(
    @Parameter(description = "The chapter URL to check")
    @RequestParam
    url: String,
  ): ChapterUrlCheckResult {
    logger.debug { "Checking URL: $url" }

    val existing = chapterUrlRepository.findByUrl(url)

    return ChapterUrlCheckResult(
      url = url,
      exists = existing != null,
      seriesId = existing?.seriesId,
      chapter = existing?.chapter,
      downloadedAt = existing?.downloadedAt,
    )
  }

  /**
   * Check multiple URLs at once.
   * More efficient than individual calls for batch checking.
   */
  @PostMapping("/check-urls")
  @Operation(summary = "Check multiple URLs for duplicates")
  fun checkUrls(
    @RequestBody urls: List<String>,
  ): ChapterUrlBatchCheckResult {
    logger.debug { "Batch checking ${urls.size} URLs" }

    val results = chapterUrlRepository.existsByUrls(urls)
    val existingCount = results.count { it.value }

    return ChapterUrlBatchCheckResult(
      results = results,
      existingCount = existingCount,
      newCount = urls.size - existingCount,
    )
  }

  /**
   * Get all downloaded URLs for a manga.
   * Used by gallery-dl to determine which chapters to skip.
   *
   * @param mangaId MangaDex manga UUID or Komga series ID
   * @param lang Language filter
   */
  @GetMapping("/downloaded-urls")
  @Operation(summary = "Get downloaded URLs for a manga")
  fun getDownloadedUrls(
    @Parameter(description = "MangaDex manga ID or Komga series ID")
    @RequestParam
    mangaId: String,
    @Parameter(description = "Language filter")
    @RequestParam(defaultValue = "en")
    lang: String,
  ): DownloadedUrlsResponse {
    logger.debug { "Getting downloaded URLs for manga $mangaId (lang: $lang)" }

    // Try to find series by manga ID (stored in chapter URLs)
    // This allows querying by MangaDex ID without knowing the Komga series ID
    val urls = chapterUrlRepository.findUrlsBySeriesIdAndLang(mangaId, lang)

    return DownloadedUrlsResponse(
      mangaId = mangaId,
      lang = lang,
      urls = urls.toList(),
      count = urls.size,
    )
  }

  /**
   * Get all chapter URLs for a series.
   */
  @GetMapping("/series/{seriesId}/chapter-urls")
  @Operation(summary = "Get all chapter URLs for a series")
  fun getChapterUrlsForSeries(
    @PathVariable seriesId: String,
    @RequestParam(required = false) lang: String?,
  ): List<ChapterUrlDto> {
    val chapters =
      if (lang != null) {
        chapterUrlRepository.findBySeriesIdAndLang(seriesId, lang)
      } else {
        chapterUrlRepository.findBySeriesId(seriesId)
      }

    return chapters.map { it.toDto() }
  }

  /**
   * Calculate new chapters by comparing MangaDex feed with downloaded URLs.
   * This is the CRITICAL endpoint for preventing duplicate downloads.
   *
   * Flow:
   * 1. Get all available chapters from MangaDex API
   * 2. Get all downloaded URLs from database
   * 3. Return only chapters that haven't been downloaded
   */
  @GetMapping("/series/{seriesId}/new-chapters")
  @Operation(summary = "Get chapters that haven't been downloaded yet")
  fun getNewChapters(
    @PathVariable seriesId: String,
    @Parameter(description = "MangaDex manga URL or ID")
    @RequestParam
    mangaUrl: String,
    @RequestParam(defaultValue = "en") lang: String,
  ): NewChaptersResponse {
    logger.info { "Calculating new chapters for series $seriesId from $mangaUrl" }

    // Extract MangaDex ID
    val mangaId =
      mangaDexClient.extractMangaId(mangaUrl)
        ?: if (mangaUrl.matches(Regex("[a-f0-9-]{36}"))) mangaUrl else null

    if (mangaId == null) {
      logger.warn { "Could not extract manga ID from URL: $mangaUrl" }
      return NewChaptersResponse(
        seriesId = seriesId,
        mangaId = "",
        availableCount = 0,
        downloadedCount = 0,
        newCount = 0,
        newChapters = emptyList(),
      )
    }

    // Get all available chapters from MangaDex
    val availableChapters = mangaDexClient.getAllChapters(mangaId, lang)
    logger.debug { "Found ${availableChapters.size} chapters on MangaDex" }

    // Get downloaded URLs for this series
    val downloadedUrls = chapterUrlRepository.findUrlsBySeriesIdAndLang(seriesId, lang).toSet()
    logger.debug { "Found ${downloadedUrls.size} downloaded chapters in database" }

    // Filter to only new chapters
    val newChapters =
      availableChapters.filter { chapter ->
        chapter.url !in downloadedUrls
      }

    logger.info {
      "New chapters calculation: ${availableChapters.size} available, " +
        "${downloadedUrls.size} downloaded, ${newChapters.size} new"
    }

    return NewChaptersResponse(
      seriesId = seriesId,
      mangaId = mangaId,
      availableCount = availableChapters.size,
      downloadedCount = downloadedUrls.size,
      newCount = newChapters.size,
      newChapters =
        newChapters.map {
          NewChapterDto(
            url = it.url,
            chapter = it.chapter,
            volume = it.volume,
            title = it.title,
            lang = it.lang,
            scanlationGroup = it.scanlationGroup,
          )
        },
    )
  }

  /**
   * Get statistics about chapter URL tracking.
   */
  @GetMapping("/chapter-urls/stats")
  @Operation(summary = "Get chapter URL tracking statistics")
  fun getStats(): ChapterUrlStats {
    val totalCount = chapterUrlRepository.count()

    return ChapterUrlStats(
      totalUrls = totalCount,
    )
  }

  /**
   * Delete all chapter URLs for a series.
   */
  @DeleteMapping("/series/{seriesId}/chapter-urls")
  @Operation(summary = "Delete all chapter URLs for a series")
  fun deleteChapterUrlsForSeries(
    @PathVariable seriesId: String,
  ): ResponseEntity<Any> {
    val count = chapterUrlRepository.countBySeriesId(seriesId)
    chapterUrlRepository.deleteBySeriesId(seriesId)
    logger.info { "Deleted $count chapter URLs for series $seriesId" }

    return ResponseEntity
      .status(HttpStatus.OK)
      .body(mapOf("deleted" to count))
  }

  /**
   * Delete a specific chapter URL.
   */
  @DeleteMapping("/chapter-urls/{id}")
  @Operation(summary = "Delete a chapter URL by ID")
  fun deleteChapterUrl(
    @PathVariable id: String,
  ): ResponseEntity<Any> {
    chapterUrlRepository.delete(id)
    logger.info { "Deleted chapter URL: $id" }
    return ResponseEntity.noContent().build()
  }
}

// --- DTOs ---

data class ChapterUrlDto(
  val id: String,
  val seriesId: String,
  val url: String,
  val chapter: Double,
  val volume: Int?,
  val title: String?,
  val lang: String,
  val downloadedAt: String,
  val source: String,
  val scanlationGroup: String?,
)

private fun ChapterUrl.toDto() =
  ChapterUrlDto(
    id = id,
    seriesId = seriesId,
    url = url,
    chapter = chapter,
    volume = volume,
    title = title,
    lang = lang,
    downloadedAt = downloadedAt.toString(),
    source = source,
    scanlationGroup = scanlationGroup,
  )

data class DownloadedUrlsResponse(
  val mangaId: String,
  val lang: String,
  val urls: List<String>,
  val count: Int,
)

data class NewChaptersResponse(
  val seriesId: String,
  val mangaId: String,
  val availableCount: Int,
  val downloadedCount: Int,
  val newCount: Int,
  val newChapters: List<NewChapterDto>,
)

data class NewChapterDto(
  val url: String,
  val chapter: Double,
  val volume: Int?,
  val title: String?,
  val lang: String,
  val scanlationGroup: String?,
)

data class ChapterUrlStats(
  val totalUrls: Long,
)
