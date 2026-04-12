package org.gotson.komga.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookMetadataPatch
import org.gotson.komga.domain.model.BookMetadataPatchCapability
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.ChapterUrl
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MetadataPatchTarget
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.infrastructure.metadata.BookMetadataProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

private val chapterUrlPattern = Regex("mangadex\\.org/chapter/[0-9a-f-]+")

@Service
class BookMetadataLifecycle(
  private val bookMetadataProviders: List<BookMetadataProvider>,
  private val metadataApplier: MetadataApplier,
  private val mediaRepository: MediaRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val libraryRepository: LibraryRepository,
  private val chapterUrlRepository: ChapterUrlRepository,
  private val readListLifecycle: ReadListLifecycle,
  private val eventPublisher: ApplicationEventPublisher,
) {
  fun refreshMetadata(
    book: Book,
    capabilities: Set<BookMetadataPatchCapability>,
  ) {
    logger.info { "Refresh metadata for book: $book with capabilities: $capabilities" }
    val media = mediaRepository.findById(book.id)

    if (media.status != Media.Status.READY) {
      logger.warn { "Skipping metadata refresh for book ${book.name}: media status is ${media.status}" }
      return
    }

    val library = libraryRepository.findById(book.libraryId)
    var changed = false

    bookMetadataProviders.forEach { provider ->
      when {
        capabilities.intersect(provider.capabilities).isEmpty() ->
          logger.debug { "Provider does not support requested capabilities, skipping: ${provider.javaClass.simpleName}" }

        !(provider.shouldLibraryHandlePatch(library, MetadataPatchTarget.BOOK) || provider.shouldLibraryHandlePatch(library, MetadataPatchTarget.READLIST)) ->
          logger.debug { "Library is not set to import book or read lists metadata for this provider, skipping: ${provider.javaClass.simpleName}" }

        else -> {
          logger.debug { "Provider: ${provider.javaClass.simpleName}" }
          val patch =
            try {
              provider.getBookMetadataFromBook(BookWithMedia(book, media))
            } catch (e: Exception) {
              logger.error(e) { "Error while getting metadata from ${provider.javaClass.simpleName} for book: $book" }
              null
            }

          if (provider.shouldLibraryHandlePatch(library, MetadataPatchTarget.BOOK)) {
            handlePatchForBookMetadata(patch, book)
            changed = true
          }

          if (provider.shouldLibraryHandlePatch(library, MetadataPatchTarget.READLIST)) {
            patch?.readLists?.forEach { readList ->
              readListLifecycle.addBookToReadList(readList.name, book, readList.number)
            }
          }

          if (library.importChapterUrls && patch != null) {
            tryImportChapterUrl(patch, book)
          }
        }
      }
    }

    if (changed) eventPublisher.publishEvent(DomainEvent.BookUpdated(book))
  }

  private fun tryImportChapterUrl(
    patch: BookMetadataPatch,
    book: Book,
  ) {
    val url =
      patch.links
        ?.firstOrNull { chapterUrlPattern.containsMatchIn(it.url.toString()) }
        ?.url
        ?.toString()
        ?: return

    val existingUrls = chapterUrlRepository.findUrlsBySeriesId(book.seriesId)
    if (url in existingUrls) return

    try {
      chapterUrlRepository.insert(
        ChapterUrl(
          id =
            TsidCreator
              .getTsid256()
              .toString(),
          seriesId = book.seriesId,
          url = url,
          chapter = patch.numberSort?.toDouble() ?: 0.0,
          title = patch.title,
          downloadedAt = LocalDateTime.now(),
          source = "comicinfo-metadata",
          scanlationGroup =
            patch.authors
              ?.firstOrNull { it.role == "translator" }
              ?.name,
        ),
      )
      logger.debug { "Imported chapter URL from metadata: $url for book ${book.name}" }
    } catch (e: Exception) {
      logger.debug { "Chapter URL already exists or insert failed: $url — ${e.message}" }
    }
  }

  private fun handlePatchForBookMetadata(
    patch: BookMetadataPatch?,
    book: Book,
  ) {
    patch?.let { bPatch ->
      bookMetadataRepository.findById(book.id).let {
        logger.debug { "Apply metadata for book: $book" }

        logger.debug { "Original metadata: $it" }
        logger.debug { "Patch: $bPatch" }
        val patched = metadataApplier.apply(bPatch, it)
        logger.debug { "Patched metadata: $patched" }

        bookMetadataRepository.update(patched)
      }
    }
  }
}
