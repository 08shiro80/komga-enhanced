package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.ChapterUrl
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.SinglePageBookIgnoreRepository
import org.gotson.komga.infrastructure.download.GalleryDlException
import org.gotson.komga.infrastructure.download.GalleryDlWrapper
import org.gotson.komga.infrastructure.util.CbzSafeWriter
import org.gotson.komga.interfaces.api.rest.dto.SinglePageBookDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/v1/media-management/single-page-books", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class SinglePageBooksController(
  private val mediaRepository: MediaRepository,
  private val singlePageBookIgnoreRepository: SinglePageBookIgnoreRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val chapterUrlRepository: ChapterUrlRepository,
  private val galleryDlWrapper: GalleryDlWrapper,
  private val taskEmitter: TaskEmitter,
) {
  @GetMapping
  @Operation(summary = "List books that contain only a single page")
  fun getSinglePageBooks(
    @RequestParam(name = "includeIgnored", required = false, defaultValue = "false") includeIgnored: Boolean,
    @RequestParam(name = "search", required = false) search: String?,
  ): List<SinglePageBookDto> {
    val ignoredIds = singlePageBookIgnoreRepository.findAllIgnoredIds()
    val chapterUrlsBySeries = HashMap<String, Collection<ChapterUrl>>()
    val searchTerm =
      search
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase()
    return mediaRepository
      .findAllSinglePageBookCandidates()
      .asSequence()
      .filter { includeIgnored || it.bookId !in ignoredIds }
      .map { c ->
        val sourceUrl =
          chapterUrlsBySeries
            .getOrPut(c.seriesId) { chapterUrlRepository.findBySeriesId(c.seriesId) }
            .firstOrNull { abs(it.chapter - c.numberSort) < NUMBER_MATCH_EPSILON }
            ?.url
        SinglePageBookDto(
          bookId = c.bookId,
          bookName = c.bookName,
          seriesId = c.seriesId,
          seriesTitle = c.seriesTitle?.takeIf { it.isNotBlank() } ?: c.seriesName,
          fileSize = c.fileSize,
          mediaType = c.mediaType,
          ignored = c.bookId in ignoredIds,
          sourceUrl = sourceUrl,
        )
      }.filter { dto ->
        searchTerm == null ||
          dto.bookName.lowercase().contains(searchTerm) ||
          dto.seriesTitle.lowercase().contains(searchTerm)
      }.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, SinglePageBookDto::seriesTitle)
          .thenBy(String.CASE_INSENSITIVE_ORDER, SinglePageBookDto::bookName),
      ).toList()
  }

  @PostMapping("{bookId}/ignore")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Mark a single-page book as ignored")
  fun ignore(
    @PathVariable bookId: String,
  ) {
    singlePageBookIgnoreRepository.ignore(bookId)
  }

  @DeleteMapping("{bookId}/ignore")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove a single-page book from the ignore list")
  fun unignore(
    @PathVariable bookId: String,
  ) {
    singlePageBookIgnoreRepository.unignore(bookId)
  }

  @PostMapping("{bookId}/repair")
  @Operation(summary = "Re-download a single-page book from its tracked chapter URL; replace only if the fresh download succeeds. dryRun=true downloads and verifies but makes no changes.")
  fun repair(
    @PathVariable bookId: String,
    @RequestParam(defaultValue = "false") dryRun: Boolean,
  ): SinglePageRepairResultDto {
    val book =
      bookRepository.findByIdOrNull(bookId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found: $bookId")
    val metadata =
      bookMetadataRepository.findByIdOrNull(bookId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book metadata not found: $bookId")

    val chapterUrl =
      resolveChapterUrl(book.seriesId, metadata.numberSort)
        ?: return SinglePageRepairResultDto(bookId, RepairOutcome.NO_SOURCE_URL.name, message = "No tracked chapter URL for this book")

    val bookPath = book.path
    val tmpDir = Files.createTempDirectory(bookPath.parent, ".repair_")
    try {
      try {
        galleryDlWrapper.download(url = chapterUrl.url, destinationPath = tmpDir, komgaSeriesId = null)
      } catch (e: GalleryDlException) {
        logger.warn(e) { "Single-page repair: download failed for ${bookPath.fileName} (${chapterUrl.url}) — keeping local file" }
        return SinglePageRepairResultDto(bookId, RepairOutcome.SOURCE_GONE.name, message = "Source unavailable: ${e.message?.take(200)}")
      }

      val newCbz =
        tmpDir
          .toFile()
          .walkTopDown()
          .firstOrNull { it.isFile && it.extension.lowercase() == "cbz" }
          ?: run {
            logger.warn { "Single-page repair: no CBZ produced for ${bookPath.fileName} (${chapterUrl.url}) — chapter likely removed at source, keeping local file" }
            return SinglePageRepairResultDto(bookId, RepairOutcome.SOURCE_GONE.name, message = "Re-download produced no file — chapter may have been removed at the source")
          }

      val newPageCount = countImages(newCbz.toPath())
      if (newPageCount == 0) {
        logger.warn { "Single-page repair: re-downloaded CBZ for ${bookPath.fileName} has no images — keeping local file" }
        return SinglePageRepairResultDto(bookId, RepairOutcome.SOURCE_GONE.name, message = "Re-downloaded file contained no images")
      }

      val outcome = if (newPageCount > 1) RepairOutcome.REPAIRED else RepairOutcome.STILL_SINGLE_PAGE
      if (dryRun) {
        logger.info { "Single-page repair (dry-run): would replace ${bookPath.fileName} — $newPageCount page(s) (${outcome.name})" }
        return SinglePageRepairResultDto(bookId, outcome.name, pageCount = newPageCount, message = "Dry-run: fresh download OK with $newPageCount page(s); no changes made")
      }
      CbzSafeWriter.safelyReplace(
        target = bookPath,
        write = { out ->
          Files
            .newInputStream(newCbz.toPath())
            .use { it.copyTo(out) }
        },
      )
      taskEmitter.analyzeBook(book)
      logger.info { "Single-page repair: replaced ${bookPath.fileName} — $newPageCount page(s) (${outcome.name})" }
      return SinglePageRepairResultDto(bookId, outcome.name, pageCount = newPageCount)
    } catch (e: IOException) {
      logger.warn(e) { "Single-page repair: I/O error for ${bookPath.fileName} — keeping local file" }
      return SinglePageRepairResultDto(bookId, RepairOutcome.FAILED.name, message = e.message?.take(200))
    } finally {
      runCatching { tmpDir.toFile().deleteRecursively() }
    }
  }

  private fun resolveChapterUrl(
    seriesId: String,
    numberSort: Float,
  ): ChapterUrl? =
    chapterUrlRepository
      .findBySeriesId(seriesId)
      .firstOrNull { abs(it.chapter - numberSort) < NUMBER_MATCH_EPSILON }

  private fun countImages(path: Path): Int =
    ZipFile(path.toFile()).use { zip ->
      zip
        .entries()
        .asSequence()
        .count { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
    }

  private enum class RepairOutcome {
    REPAIRED,
    STILL_SINGLE_PAGE,
    NO_SOURCE_URL,
    SOURCE_GONE,
    FAILED,
  }

  companion object {
    private const val NUMBER_MATCH_EPSILON = 0.001

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "jxl", "bmp", "jfif")
  }
}

data class SinglePageRepairResultDto(
  val bookId: String,
  val outcome: String,
  val pageCount: Int? = null,
  val message: String? = null,
)
