package org.gotson.komga.domain.persistence

interface SinglePageBookIgnoreRepository {
  fun findAllIgnoredIds(): Set<String>

  fun ignore(bookId: String)

  fun unignore(bookId: String)
}
