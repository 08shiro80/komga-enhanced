package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.IgnoredOversizedPage
import org.gotson.komga.domain.persistence.IgnoredOversizedPageRepository
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class IgnoredOversizedPageDao(
  private val dslRW: DSLContext,
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : IgnoredOversizedPageRepository {
  private val table = DSL.table("IGNORED_OVERSIZED_PAGE")
  private val bookIdField = DSL.field("BOOK_ID", String::class.java)
  private val pageNumberField = DSL.field("PAGE_NUMBER", Int::class.java)
  private val modeField = DSL.field("MODE", String::class.java)
  private val createdDateField = DSL.field("CREATED_DATE", LocalDateTime::class.java)

  override fun findAllByMode(mode: String): Collection<IgnoredOversizedPage> =
    dslRO
      .select()
      .from(table)
      .where(modeField.eq(mode))
      .fetch()
      .map { it.toDomain() }

  override fun findKeysByMode(mode: String): Set<Pair<String, Int>> =
    dslRO
      .select(bookIdField, pageNumberField)
      .from(table)
      .where(modeField.eq(mode))
      .fetch()
      .map { Pair(it.get(bookIdField)!!, it.get(pageNumberField)!!) }
      .toSet()

  override fun existsByKey(
    bookId: String,
    pageNumber: Int,
    mode: String,
  ): Boolean =
    dslRO
      .fetchExists(
        dslRO
          .selectOne()
          .from(table)
          .where(bookIdField.eq(bookId))
          .and(pageNumberField.eq(pageNumber))
          .and(modeField.eq(mode)),
      )

  override fun insert(ignoredPage: IgnoredOversizedPage) {
    dslRW
      .insertInto(table)
      .columns(bookIdField, pageNumberField, modeField, createdDateField)
      .values(
        ignoredPage.bookId,
        ignoredPage.pageNumber,
        ignoredPage.mode,
        ignoredPage.createdDate,
      ).execute()
  }

  override fun delete(
    bookId: String,
    pageNumber: Int,
    mode: String,
  ) {
    dslRW
      .deleteFrom(table)
      .where(bookIdField.eq(bookId))
      .and(pageNumberField.eq(pageNumber))
      .and(modeField.eq(mode))
      .execute()
  }

  override fun deleteByBookId(bookId: String) {
    dslRW
      .deleteFrom(table)
      .where(bookIdField.eq(bookId))
      .execute()
  }

  override fun deleteByBookIdAndMode(
    bookId: String,
    mode: String,
  ) {
    dslRW
      .deleteFrom(table)
      .where(bookIdField.eq(bookId))
      .and(modeField.eq(mode))
      .execute()
  }

  private fun Record.toDomain(): IgnoredOversizedPage =
    IgnoredOversizedPage(
      bookId = get(bookIdField)!!,
      pageNumber = get(pageNumberField)!!,
      mode = get(modeField)!!,
      createdDate = getTimestamp(createdDateField) ?: LocalDateTime.now(),
    )

  private fun Record.getTimestamp(field: org.jooq.Field<LocalDateTime?>): LocalDateTime? {
    val raw = get(field.name) ?: return null
    return when (raw) {
      is LocalDateTime -> raw.toCurrentTimeZone()
      is java.sql.Timestamp -> raw.toLocalDateTime()
      is String ->
        try {
          LocalDateTime.parse(raw.replace(" ", "T").substringBefore("+"))
        } catch (_: Exception) {
          LocalDateTime.now()
        }
      else -> LocalDateTime.now()
    }
  }
}
