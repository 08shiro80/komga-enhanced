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
  /**
   * Splits tall pages in a book based on the maximum height threshold.
   *
   * @param book The book to process
   * @param maxHeight Maximum height before a page is split
   * @return Result with information about the split operation
   */
  fun splitTallPages(
    book: Book,
    maxHeight: Int,
  ): SplitResult {
    if (book.path.exists().not()) {
      throw FileNotFoundException("File not found: ${book.path}")
    }

    val media = mediaRepository.findById(book.id)

    if (media.status != Media.Status.READY) {
      throw IllegalStateException("Media not ready for book: ${book.id}")
    }

    // Find pages that need splitting
    val pagesToSplit = mutableListOf<PageToSplit>()
    media.pages.forEachIndexed { index, page ->
      val dimension = page.dimension
      if (dimension != null && dimension.height > maxHeight) {
        pagesToSplit.add(
          PageToSplit(
            pageIndex = index,
            fileName = page.fileName,
            height = dimension.height,
            width = dimension.width,
          ),
        )
      }
    }

    if (pagesToSplit.isEmpty()) {
      logger.info { "No pages need splitting in book: ${book.name}" }
      return SplitResult(
        bookId = book.id,
        bookName = book.name,
        pagesAnalyzed = media.pages.size,
        pagesSplit = 0,
        newPagesCreated = 0,
        success = true,
        message = "No pages exceed height threshold of $maxHeight",
      )
    }

    logger.info { "Found ${pagesToSplit.size} pages to split in book: ${book.name}" }

    // Create backup and process
    val backupPath = book.path.parent.resolve("${book.path.nameWithoutExtension}_backup.${book.path.extension}")
    val tempPath = book.path.parent.resolve("${book.path.nameWithoutExtension}_split.${book.path.extension}")

    try {
      // Create backup
      Files.copy(book.path, backupPath, StandardCopyOption.REPLACE_EXISTING)
      logger.debug { "Created backup at: $backupPath" }

      var newPagesCreated = 0

      // Create new archive with split pages
      ZipArchiveOutputStream(tempPath.outputStream()).use { zipStream ->
        zipStream.setMethod(ZipArchiveOutputStream.DEFLATED)
        zipStream.setLevel(Deflater.NO_COMPRESSION)

        var newPageNumber = 1

        media.pages.forEachIndexed { index, page ->
          val pageToSplit = pagesToSplit.find { it.pageIndex == index }

          if (pageToSplit != null) {
            // This page needs splitting
            val imageBytes = bookAnalyzer.getFileContent(BookWithMedia(book, media), page.fileName)
            val splitImages = imageSplitter.splitTallImage(imageBytes, maxHeight, getFormatFromMediaType(page.mediaType))

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
      logger.info { "Replaced original file with split version" }

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

data class PageToSplit(
  val pageIndex: Int,
  val fileName: String,
  val height: Int,
  val width: Int,
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
