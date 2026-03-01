package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.BlacklistedChapter
import org.gotson.komga.domain.persistence.BlacklistedChapterRepository
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class BlacklistedChapterDao(
  private val dslRW: DSLContext,
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : BlacklistedChapterRepository {
  private val table = DSL.table("BLACKLISTED_CHAPTER")
  private val idField = DSL.field("ID", String::class.java)
  private val seriesIdField = DSL.field("SERIES_ID", String::class.java)
  private val chapterUrlField = DSL.field("CHAPTER_URL", String::class.java)
  private val chapterNumberField = DSL.field("CHAPTER_NUMBER", String::class.java)
  private val chapterTitleField = DSL.field("CHAPTER_TITLE", String::class.java)
  private val createdDateField = DSL.field("CREATED_DATE", LocalDateTime::class.java)

  override fun findBySeriesId(seriesId: String): Collection<BlacklistedChapter> =
    dslRO
      .select()
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .orderBy(createdDateField.desc())
      .fetch()
      .map { it.toDomain() }

  override fun findUrlsBySeriesId(seriesId: String): Set<String> =
    dslRO
      .select(chapterUrlField)
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .fetch(chapterUrlField)
      .toSet()

  override fun findAll(): Collection<BlacklistedChapter> =
    dslRO
      .select()
      .from(table)
      .orderBy(createdDateField.desc())
      .fetch()
      .map { it.toDomain() }

  override fun existsByChapterUrl(url: String): Boolean =
    dslRO
      .fetchExists(
        dslRO
          .selectOne()
          .from(table)
          .where(chapterUrlField.eq(url)),
      )

  override fun insert(blacklistedChapter: BlacklistedChapter) {
    dslRW
      .insertInto(table)
      .columns(
        idField,
        seriesIdField,
        chapterUrlField,
        chapterNumberField,
        chapterTitleField,
        createdDateField,
      ).values(
        blacklistedChapter.id,
        blacklistedChapter.seriesId,
        blacklistedChapter.chapterUrl,
        blacklistedChapter.chapterNumber,
        blacklistedChapter.chapterTitle,
        blacklistedChapter.createdDate,
      ).execute()
  }

  override fun deleteByChapterUrl(url: String) {
    dslRW
      .deleteFrom(table)
      .where(chapterUrlField.eq(url))
      .execute()
  }

  override fun deleteById(id: String) {
    dslRW
      .deleteFrom(table)
      .where(idField.eq(id))
      .execute()
  }

  override fun countBySeriesId(seriesId: String): Long =
    dslRO
      .selectCount()
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .fetchOne(0, Long::class.java) ?: 0L

  private fun Record.toDomain(): BlacklistedChapter =
    BlacklistedChapter(
      id = get(idField)!!,
      seriesId = get(seriesIdField)!!,
      chapterUrl = get(chapterUrlField)!!,
      chapterNumber = get(chapterNumberField),
      chapterTitle = get(chapterTitleField),
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
