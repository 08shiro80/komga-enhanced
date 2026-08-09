package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaExtension
import org.gotson.komga.domain.model.OversizedPageCandidate
import org.gotson.komga.domain.model.SinglePageBookCandidate

interface MediaRepository {
  fun findById(bookId: String): Media

  fun findByIdOrNull(bookId: String): Media?

  fun findAllBookIdsByLibraryIdAndMediaTypeAndWithMissingPageHash(
    libraryId: String,
    mediaTypes: Collection<String>,
    pageHashing: Int,
  ): Collection<String>

  fun findAllOversizedPageCandidates(minDimension: Int): Collection<OversizedPageCandidate>

  fun findAllSinglePageBookCandidates(): Collection<SinglePageBookCandidate>

  fun getPagesSizes(bookIds: Collection<String>): Collection<Pair<String, Int>>

  fun findExtensionByIdOrNull(bookId: String): MediaExtension?

  fun insert(media: Media)

  fun insert(medias: Collection<Media>)

  fun update(media: Media)

  /**
   * Performs a full copy of a Media, including MediaExtension, pages, and files.
   */
  fun copy(
    fromBookId: String,
    toBookId: String,
  )

  fun delete(bookId: String)

  fun delete(bookIds: Collection<String>)

  fun count(): Long

  fun countByStatus(status: Media.Status): Long
}
