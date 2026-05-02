package org.gotson.komga.interfaces.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/plugins/gallery-dl-downloader")
class GalleryDlDiagnosticsController(
  private val galleryDlWrapper: GalleryDlWrapper,
) {
  /**
   * Test metadata extraction from a URL
   * Usage: GET /api/v1/plugins/gallery-dl-downloader/test-metadata?url=https://mangadex.org/title/...
   */
  @GetMapping("/test-metadata")
  fun testMetadataExtraction(
    @RequestParam url: String,
  ): ResponseEntity<Map<String, Any?>> =
    try {
      logger.info { "Testing metadata extraction for URL: $url" }
      val mangaInfo = galleryDlWrapper.getChapterInfo(url)

      ResponseEntity.ok(
        mapOf(
          "success" to true,
          "title" to mangaInfo.title,
          "author" to mangaInfo.author,
          "description" to mangaInfo.description,
          "scanlationGroup" to mangaInfo.scanlationGroup,
          "totalChapters" to mangaInfo.totalChapters,
          "alternativeTitles" to mangaInfo.alternativeTitles,
          "alternativeTitlesWithLanguage" to mangaInfo.alternativeTitlesWithLanguage,
        ),
      )
    } catch (e: Exception) {
      logger.error(e) { "Failed to extract metadata from URL: $url" }
      ResponseEntity.ok(
        mapOf(
          "success" to false,
          "error" to e.message,
          "stackTrace" to e.stackTraceToString(),
        ),
      )
    }
}
