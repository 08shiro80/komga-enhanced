package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.IgnoredOversizedPage

interface IgnoredOversizedPageRepository {
  fun findAllByMode(mode: String): Collection<IgnoredOversizedPage>

  fun findKeysByMode(mode: String): Set<Pair<String, Int>>

  fun existsByKey(
    bookId: String,
    pageNumber: Int,
    mode: String,
  ): Boolean

  fun insert(ignoredPage: IgnoredOversizedPage)

  fun delete(
    bookId: String,
    pageNumber: Int,
    mode: String,
  )

  fun deleteByBookId(bookId: String)

  fun deleteByBookIdAndMode(
    bookId: String,
    mode: String,
  )
}
