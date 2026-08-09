package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.persistence.SinglePageBookIgnoreRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class SinglePageBookIgnoreDao(
  private val dslRW: DSLContext,
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : SinglePageBookIgnoreRepository {
  private val table = DSL.table("IGNORED_SINGLE_PAGE_BOOK")
  private val bookIdField = DSL.field("BOOK_ID", String::class.java)

  override fun findAllIgnoredIds(): Set<String> =
    dslRO
      .select(bookIdField)
      .from(table)
      .fetch()
      .map { it.get(bookIdField)!! }
      .toSet()

  override fun ignore(bookId: String) {
    dslRW
      .insertInto(table)
      .columns(bookIdField)
      .values(bookId)
      .onDuplicateKeyIgnore()
      .execute()
  }

  override fun unignore(bookId: String) {
    dslRW.deleteFrom(table).where(bookIdField.eq(bookId)).execute()
  }
}
