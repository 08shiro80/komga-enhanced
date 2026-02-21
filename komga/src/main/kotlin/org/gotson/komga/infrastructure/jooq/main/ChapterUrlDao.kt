package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.ChapterUrl
import org.gotson.komga.domain.persistence.ChapterUrlRepository
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class ChapterUrlDao(
  private val dslRW: DSLContext,
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : ChapterUrlRepository {
  private val table = DSL.table("CHAPTER_URL")
  private val idField = DSL.field("ID", String::class.java)
  private val seriesIdField = DSL.field("SERIES_ID", String::class.java)
  private val urlField = DSL.field("URL", String::class.java)
  private val chapterField = DSL.field("CHAPTER", Double::class.java)
  private val volumeField = DSL.field("VOLUME", Int::class.java)
  private val titleField = DSL.field("TITLE", String::class.java)
  private val langField = DSL.field("LANG", String::class.java)
  private val downloadedAtField = DSL.field("DOWNLOADED_AT", LocalDateTime::class.java)
  private val sourceField = DSL.field("SOURCE", String::class.java)
  private val chapterIdField = DSL.field("CHAPTER_ID", String::class.java)
  private val scanlationGroupField = DSL.field("SCANLATION_GROUP", String::class.java)
  private val createdDateField = DSL.field("CREATED_DATE", LocalDateTime::class.java)
  private val lastModifiedDateField = DSL.field("LAST_MODIFIED_DATE", LocalDateTime::class.java)

  override fun findById(id: String): ChapterUrl = findByIdOrNull(id) ?: throw NoSuchElementException("ChapterUrl not found: $id")

  override fun findByIdOrNull(id: String): ChapterUrl? =
    dslRO
      .select()
      .from(table)
      .where(idField.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findByUrl(url: String): ChapterUrl? =
    dslRO
      .select()
      .from(table)
      .where(urlField.eq(url))
      .fetchOne()
      ?.toDomain()

  override fun existsByUrl(url: String): Boolean =
    dslRO
      .fetchExists(
        dslRO
          .selectOne()
          .from(table)
          .where(urlField.eq(url)),
      )

  override fun existsByUrls(urls: Collection<String>): Map<String, Boolean> {
    if (urls.isEmpty()) return emptyMap()

    val existingUrls =
      dslRO
        .select(urlField)
        .from(table)
        .where(urlField.`in`(urls))
        .fetch(urlField)
        .toSet()

    return urls.associateWith { it in existingUrls }
  }

  override fun findBySeriesId(seriesId: String): Collection<ChapterUrl> =
    dslRO
      .select()
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .orderBy(chapterField.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findBySeriesIdAndLang(
    seriesId: String,
    lang: String,
  ): Collection<ChapterUrl> =
    dslRO
      .select()
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .and(langField.eq(lang))
      .orderBy(chapterField.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findUrlsBySeriesId(seriesId: String): Collection<String> =
    dslRO
      .select(urlField)
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .fetch(urlField)

  override fun findUrlsBySeriesIdAndLang(
    seriesId: String,
    lang: String,
  ): Collection<String> =
    dslRO
      .select(urlField)
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .and(langField.eq(lang))
      .fetch(urlField)

  override fun findAll(): Collection<ChapterUrl> =
    dslRO
      .select()
      .from(table)
      .orderBy(createdDateField.desc())
      .fetch()
      .map { it.toDomain() }

  override fun findBySource(source: String): Collection<ChapterUrl> =
    dslRO
      .select()
      .from(table)
      .where(sourceField.eq(source))
      .orderBy(createdDateField.desc())
      .fetch()
      .map { it.toDomain() }

  override fun insert(chapterUrl: ChapterUrl) {
    dslRW
      .insertInto(table)
      .columns(
        idField,
        seriesIdField,
        urlField,
        chapterField,
        volumeField,
        titleField,
        langField,
        downloadedAtField,
        sourceField,
        chapterIdField,
        scanlationGroupField,
        createdDateField,
        lastModifiedDateField,
      ).values(
        chapterUrl.id,
        chapterUrl.seriesId,
        chapterUrl.url,
        chapterUrl.chapter,
        chapterUrl.volume,
        chapterUrl.title,
        chapterUrl.lang,
        chapterUrl.downloadedAt,
        chapterUrl.source,
        chapterUrl.chapterId,
        chapterUrl.scanlationGroup,
        chapterUrl.createdDate,
        chapterUrl.lastModifiedDate,
      ).execute()
  }

  override fun insertAll(chapterUrls: Collection<ChapterUrl>) {
    if (chapterUrls.isEmpty()) return

    val batch =
      dslRW
        .batch(
          dslRW
            .insertInto(table)
            .columns(
              idField,
              seriesIdField,
              urlField,
              chapterField,
              volumeField,
              titleField,
              langField,
              downloadedAtField,
              sourceField,
              chapterIdField,
              scanlationGroupField,
              createdDateField,
              lastModifiedDateField,
            ).values(
              null as String?,
              null as String?,
              null as String?,
              null as Double?,
              null as Int?,
              null as String?,
              null as String?,
              null as LocalDateTime?,
              null as String?,
              null as String?,
              null as String?,
              null as LocalDateTime?,
              null as LocalDateTime?,
            ),
        )

    chapterUrls.forEach { chapterUrl ->
      batch.bind(
        chapterUrl.id,
        chapterUrl.seriesId,
        chapterUrl.url,
        chapterUrl.chapter,
        chapterUrl.volume,
        chapterUrl.title,
        chapterUrl.lang,
        chapterUrl.downloadedAt,
        chapterUrl.source,
        chapterUrl.chapterId,
        chapterUrl.scanlationGroup,
        chapterUrl.createdDate,
        chapterUrl.lastModifiedDate,
      )
    }

    batch.execute()
  }

  override fun update(chapterUrl: ChapterUrl) {
    dslRW
      .update(table)
      .set(seriesIdField, chapterUrl.seriesId)
      .set(urlField, chapterUrl.url)
      .set(chapterField, chapterUrl.chapter)
      .set(volumeField, chapterUrl.volume)
      .set(titleField, chapterUrl.title)
      .set(langField, chapterUrl.lang)
      .set(downloadedAtField, chapterUrl.downloadedAt)
      .set(sourceField, chapterUrl.source)
      .set(chapterIdField, chapterUrl.chapterId)
      .set(scanlationGroupField, chapterUrl.scanlationGroup)
      .set(lastModifiedDateField, LocalDateTime.now())
      .where(idField.eq(chapterUrl.id))
      .execute()
  }

  override fun delete(id: String) {
    dslRW
      .deleteFrom(table)
      .where(idField.eq(id))
      .execute()
  }

  override fun deleteBySeriesId(seriesId: String) {
    dslRW
      .deleteFrom(table)
      .where(seriesIdField.eq(seriesId))
      .execute()
  }

  override fun deleteByUrl(url: String) {
    dslRW
      .deleteFrom(table)
      .where(urlField.eq(url))
      .execute()
  }

  override fun count(): Long =
    dslRO
      .selectCount()
      .from(table)
      .fetchOne(0, Long::class.java) ?: 0L

  override fun countBySeriesId(seriesId: String): Long =
    dslRO
      .selectCount()
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .fetchOne(0, Long::class.java) ?: 0L

  override fun countBySeriesIdAndLang(
    seriesId: String,
    lang: String,
  ): Long =
    dslRO
      .selectCount()
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .and(langField.eq(lang))
      .fetchOne(0, Long::class.java) ?: 0L

  override fun findDistinctLangsBySeriesId(seriesId: String): Collection<String> =
    dslRO
      .selectDistinct(langField)
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .fetch(langField)

  override fun findBySeriesIdAndChapterRange(
    seriesId: String,
    minChapter: Double,
    maxChapter: Double,
  ): Collection<ChapterUrl> =
    dslRO
      .select()
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .and(chapterField.ge(minChapter))
      .and(chapterField.le(maxChapter))
      .orderBy(chapterField.asc())
      .fetch()
      .map { it.toDomain() }

  override fun deleteAll(): Long =
    dslRW
      .deleteFrom(table)
      .execute()
      .toLong()

  override fun deleteByDateRange(
    from: LocalDateTime,
    to: LocalDateTime,
  ): Long =
    dslRW
      .deleteFrom(table)
      .where(downloadedAtField.ge(from))
      .and(downloadedAtField.le(to))
      .execute()
      .toLong()

  override fun countByDateRange(
    from: LocalDateTime,
    to: LocalDateTime,
  ): Long =
    dslRO
      .selectCount()
      .from(table)
      .where(downloadedAtField.ge(from))
      .and(downloadedAtField.le(to))
      .fetchOne(0, Long::class.java) ?: 0L

  override fun findByDateRange(
    from: LocalDateTime,
    to: LocalDateTime,
  ): Collection<ChapterUrl> =
    dslRO
      .select()
      .from(table)
      .where(downloadedAtField.ge(from))
      .and(downloadedAtField.le(to))
      .orderBy(downloadedAtField.desc())
      .fetch()
      .map { it.toDomain() }

  private fun Record.toDomain(): ChapterUrl =
    ChapterUrl(
      id = get(idField)!!,
      seriesId = get(seriesIdField)!!,
      url = get(urlField)!!,
      chapter = (get(chapterField) as? Number)?.toDouble() ?: 0.0,
      volume = get(volumeField),
      title = get(titleField),
      lang = get(langField) ?: "en",
      downloadedAt = getTimestamp(downloadedAtField) ?: LocalDateTime.now(),
      source = get(sourceField) ?: "gallery-dl",
      chapterId = get(chapterIdField),
      scanlationGroup = get(scanlationGroupField),
      createdDate = getTimestamp(createdDateField) ?: LocalDateTime.now(),
      lastModifiedDate = getTimestamp(lastModifiedDateField) ?: LocalDateTime.now(),
    )

  private fun Record.getTimestamp(field: org.jooq.Field<LocalDateTime?>): LocalDateTime? {
    val raw = get(field) ?: return null
    return try {
      raw.toCurrentTimeZone()
    } catch (_: ClassCastException) {
      val value = get(field.name)
      when (value) {
        is java.sql.Timestamp -> value.toLocalDateTime()
        is String ->
          try {
            LocalDateTime.parse(value.replace(" ", "T").substringBefore("+"))
          } catch (_: Exception) {
            LocalDateTime.now()
          }
        else -> LocalDateTime.now()
      }
    }
  }
}
