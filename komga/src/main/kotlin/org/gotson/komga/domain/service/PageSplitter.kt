package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.io.FilenameUtils
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.HistoricalEvent
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.HistoricalEventRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.infrastructure.image.ImageSplitter
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}

/**
 * Service for splitting tall pages in books.
 * Similar to TachiyomiSY's "split tall images" feature.
 */
@Service
class PageSplitter(
  private val bookAnalyzer: BookAnalyzer,
  private val fileSystemScanner: FileSystemScanner,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val libraryRepository: LibraryRepository,
  private val imageSplitter: ImageSplitter,
  private val transactionTemplate: TransactionTemplate,
  private val eventPublisher: ApplicationEventPublisher,
  private val historicalEventRepository: HistoricalEventRepository,
) {
  companion object {
    const val MIN_VALID_DIMENSION = 50
    const val MAX_WIDE_RATIO = 10.0
  }

  /**
   * Splits tall pages in a book based on the maximum height threshold.
   *
   * @param book The book to process
   * @param maxHeight Maximum height before a page is split
   * @return Result with information about the split operation
   */
  fun splitTallPages(
    book: Book,
    maxHeight: Int? = null,
    maxRatio: Double? = null,
    mode: SplitMode = SplitMode.TALL,
    pageNumbers: Set<Int>? = null,
  ): SplitResult {
    if (book.path.exists().not()) {
      throw FileNotFoundException("File not found: ${book.path}")
    }

    val media = mediaRepository.findById(book.id)

    if (media.status != Media.Status.READY) {
      throw IllegalStateException("Media not ready for book: ${book.id}")
    }

    val pagesToSplit = mutableListOf<PageToSplit>()
    media.pages.forEachIndexed { index, page ->
      val pageNumber = index + 1
      val dimension = page.dimension
      if (dimension == null || dimension.width <= 0 || dimension.height <= 0) return@forEachIndexed
      // Reject pathological strip images (webtoon dividers like 720x1, 1200x15, 1200x25)
      if (dimension.width < MIN_VALID_DIMENSION || dimension.height < MIN_VALID_DIMENSION) return@forEachIndexed
      // If an explicit page set was provided, only split those pages — ratio checks are skipped
      // for explicit selections since the user already decided via the UI.
      if (pageNumbers != null && pageNumber !in pageNumbers) return@forEachIndexed

      if (mode == SplitMode.WIDE) {
        val aspectRatio = dimension.width.toDouble() / dimension.height
        if (pageNumbers == null && aspectRatio > MAX_WIDE_RATIO) return@forEachIndexed
        val effectiveMaxWidth = (dimension.height * (maxRatio ?: 1.0)).toInt()
        if (pageNumbers == null && dimension.width <= effectiveMaxWidth) return@forEachIndexed
        pagesToSplit.add(
          PageToSplit(
            pageIndex = index,
            fileName = page.fileName,
            height = dimension.height,
            width = dimension.width,
            effectiveMax = effectiveMaxWidth,
            mode = SplitMode.WIDE,
          ),
        )
      } else {
        val effectiveMaxHeight =
          if (maxRatio != null) {
            (dimension.width * maxRatio).toInt()
          } else {
            maxHeight ?: 2000
          }
        if (pageNumbers == null && dimension.height <= effectiveMaxHeight) return@forEachIndexed
        pagesToSplit.add(
          PageToSplit(
            pageIndex = index,
            fileName = page.fileName,
            height = dimension.height,
            width = dimension.width,
            effectiveMax = effectiveMaxHeight,
            mode = SplitMode.TALL,
          ),
        )
      }
    }

    if (pagesToSplit.isEmpty()) {
      val thresholdDesc =
        when {
          mode == SplitMode.WIDE -> "width ratio ${maxRatio ?: 1.0}:1"
          maxRatio != null -> "ratio $maxRatio:1"
          else -> "height ${maxHeight ?: 2000}px"
        }
      logger.info { "No pages need splitting in book: ${book.name}" }
      return SplitResult(
        bookId = book.id,
        bookName = book.name,
        pagesAnalyzed = media.pages.size,
        pagesSplit = 0,
        newPagesCreated = 0,
        success = true,
        message = "No pages exceed threshold of $thresholdDesc",
      )
    }

    logger.info { "Found ${pagesToSplit.size} pages to split in book: ${book.name}" }

    // Create backup and process
    val backupPath = book.path.parent.resolve("${book.path.nameWithoutExtension}_backup.${book.path.extension}")
    val tempPath = book.path.parent.resolve("${book.path.nameWithoutExtension}_split.${book.path.extension}")

    val originalComment =
      try {
        ZipFile(book.path.toFile()).use { it.comment }
      } catch (_: Exception) {
        null
      }

    try {
      // Create backup
      Files.copy(book.path, backupPath, StandardCopyOption.REPLACE_EXISTING)
      logger.debug { "Created backup at: $backupPath" }

      var newPagesCreated = 0

      // Create new archive with split pages
      ZipArchiveOutputStream(tempPath.outputStream()).use { zipStream ->
        zipStream.setMethod(ZipArchiveOutputStream.DEFLATED)
        zipStream.setLevel(Deflater.NO_COMPRESSION)
        if (!originalComment.isNullOrBlank()) zipStream.setComment(originalComment)

        var newPageNumber = 1

        media.pages.forEachIndexed { index, page ->
          val pageToSplit = pagesToSplit.find { it.pageIndex == index }

          if (pageToSplit != null) {
            val splitImages =
              try {
                val imageBytes = bookAnalyzer.getFileContent(BookWithMedia(book, media), page.fileName)
                if (pageToSplit.mode == SplitMode.WIDE) {
                  imageSplitter.splitWideImage(imageBytes, pageToSplit.effectiveMax, getFormatFromMediaType(page.mediaType))
                } else {
                  imageSplitter.splitTallImage(imageBytes, pageToSplit.effectiveMax, getFormatFromMediaType(page.mediaType))
                }
              } catch (e: Exception) {
                logger.warn(e) { "Failed to split page ${index + 1} (${page.fileName}, ${pageToSplit.width}x${pageToSplit.height}) in book: ${book.name}" }
                throw e
              }

            splitImages.forEachIndexed { partIndex, partBytes ->
              val extension = getExtensionFromMediaType(page.mediaType)
              val newFileName = generateSplitPageName(page.fileName, partIndex + 1, splitImages.size, extension)

              zipStream.putArchiveEntry(ZipArchiveEntry(newFileName))
              zipStream.write(partBytes)
              zipStream.closeArchiveEntry()

              newPageNumber++
              if (partIndex > 0) newPagesCreated++
            }

            logger.debug { "Split page ${index + 1} into ${splitImages.size} parts" }
          } else {
            // Copy page as-is
            val content = bookAnalyzer.getFileContent(BookWithMedia(book, media), page.fileName)
            zipStream.putArchiveEntry(ZipArchiveEntry(page.fileName))
            zipStream.write(content)
            zipStream.closeArchiveEntry()
            newPageNumber++
          }
        }

        // Also copy non-page files (like ComicInfo.xml)
        media.files.forEach { file ->
          val content = bookAnalyzer.getFileContent(BookWithMedia(book, media), file.fileName)
          zipStream.putArchiveEntry(ZipArchiveEntry(file.fileName))
          zipStream.write(content)
          zipStream.closeArchiveEntry()
        }
      }

      // Replace original with new file
      book.path.deleteIfExists()
      Files.move(tempPath, book.path, StandardCopyOption.REPLACE_EXISTING)
      logger.debug { "Replaced original file with split version" }

      // Re-analyze the book
      val updatedBook =
        fileSystemScanner.scanFile(book.path)?.copy(
          id = book.id,
          seriesId = book.seriesId,
          libraryId = book.libraryId,
        ) ?: throw IllegalStateException("Could not scan updated book")

      val updatedMedia = bookAnalyzer.analyze(updatedBook, libraryRepository.findById(book.libraryId).analyzeDimensions)

      transactionTemplate.executeWithoutResult {
        bookRepository.update(updatedBook)
        mediaRepository.update(updatedMedia)
      }

      // Clean up backup
      backupPath.deleteIfExists()

      historicalEventRepository.insert(
        HistoricalEvent.BookConverted(updatedBook, book),
      )
      eventPublisher.publishEvent(DomainEvent.BookUpdated(updatedBook))

      return SplitResult(
        bookId = book.id,
        bookName = book.name,
        pagesAnalyzed = media.pages.size,
        pagesSplit = pagesToSplit.size,
        newPagesCreated = newPagesCreated,
        success = true,
        message = "Successfully split ${pagesToSplit.size} pages, created $newPagesCreated new pages",
      )
    } catch (e: Exception) {
      logger.error(e) { "Failed to split pages in book: ${book.name}" }

      // Restore backup if it exists
      if (backupPath.exists()) {
        try {
          Files.move(backupPath, book.path, StandardCopyOption.REPLACE_EXISTING)
          logger.info { "Restored backup after failure" }
        } catch (restoreError: Exception) {
          logger.error(restoreError) { "Failed to restore backup!" }
        }
      }

      // Clean up temp file
      tempPath.deleteIfExists()

      return SplitResult(
        bookId = book.id,
        bookName = book.name,
        pagesAnalyzed = media.pages.size,
        pagesSplit = 0,
        newPagesCreated = 0,
        success = false,
        message = "Failed: ${e.message}",
      )
    }
  }

  private fun generateSplitPageName(
    originalName: String,
    partNumber: Int,
    totalParts: Int,
    extension: String,
  ): String {
    val baseName = FilenameUtils.removeExtension(originalName)
    val paddedPart = partNumber.toString().padStart(2, '0')
    return "${baseName}_part${paddedPart}of$totalParts.$extension"
  }

  private fun getFormatFromMediaType(mediaType: String): String =
    when {
      mediaType.contains("png") -> "png"
      mediaType.contains("webp") -> "webp"
      mediaType.contains("gif") -> "gif"
      else -> "jpg"
    }

  private fun getExtensionFromMediaType(mediaType: String): String =
    when {
      mediaType.contains("png") -> "png"
      mediaType.contains("webp") -> "webp"
      mediaType.contains("gif") -> "gif"
      mediaType.contains("jpeg") || mediaType.contains("jpg") -> "jpg"
      else -> "jpg"
    }
}

enum class SplitMode { TALL, WIDE }

data class PageToSplit(
  val pageIndex: Int,
  val fileName: String,
  val height: Int,
  val width: Int,
  val effectiveMax: Int,
  val mode: SplitMode,
)

data class SplitResult(
  val bookId: String,
  val bookName: String,
  val pagesAnalyzed: Int,
  val pagesSplit: Int,
  val newPagesCreated: Int,
  val success: Boolean,
  val message: String,
)
