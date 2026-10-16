package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.Follow
import org.gotson.komga.domain.persistence.FollowRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class FollowDao(
  private val dslRW: DSLContext,
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : FollowRepository {
  private val table = DSL.table("FOLLOW")
  private val idField = DSL.field("ID", String::class.java)
  private val libraryIdField = DSL.field("LIBRARY_ID", String::class.java)
  private val urlField = DSL.field("URL", String::class.java)
  private val titleField = DSL.field("TITLE", String::class.java)
  private val seriesIdField = DSL.field("SERIES_ID", String::class.java)
  private val enabledField = DSL.field("ENABLED", Boolean::class.java)
  private val addedAtField = DSL.field("ADDED_AT", LocalDateTime::class.java)
  private val lastCheckedAtField = DSL.field("LAST_CHECKED_AT", LocalDateTime::class.java)

  override fun findAllByLibraryId(libraryId: String): List<Follow> =
    dslRO
      .select()
      .from(table)
      .where(libraryIdField.eq(libraryId))
      .orderBy(addedAtField.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findAllBySeriesId(seriesId: String): List<Follow> =
    dslRO
      .select()
      .from(table)
      .where(seriesIdField.eq(seriesId))
      .orderBy(addedAtField.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findById(id: String): Follow? =
    dslRO
      .select()
      .from(table)
      .where(idField.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun existsByLibraryIdAndUrl(
    libraryId: String,
    url: String,
  ): Boolean =
    dslRO.fetchExists(
      dslRO
        .selectOne()
        .from(table)
        .where(libraryIdField.eq(libraryId))
        .and(urlField.eq(url)),
    )

  override fun insert(follow: Follow) {
    dslRW
      .insertInto(table)
      .columns(
        idField,
        libraryIdField,
        urlField,
        titleField,
        seriesIdField,
        enabledField,
        addedAtField,
        lastCheckedAtField,
      ).values(
        follow.id,
        follow.libraryId,
        follow.url,
        follow.title,
        follow.seriesId,
        follow.enabled,
        follow.addedAt,
        follow.lastCheckedAt,
      ).execute()
  }

  override fun insertAll(follows: Collection<Follow>) {
    if (follows.isEmpty()) return

    val batch =
      dslRW
        .batch(
          dslRW
            .insertInto(table)
            .columns(
              idField,
              libraryIdField,
              urlField,
              titleField,
              seriesIdField,
              enabledField,
              addedAtField,
              lastCheckedAtField,
            ).values(
              null as String?,
              null as String?,
              null as String?,
              null as String?,
              null as String?,
              null as Boolean?,
              null as LocalDateTime?,
              null as LocalDateTime?,
            ).onConflictDoNothing(),
        )

    follows.forEach { follow ->
      batch.bind(
        follow.id,
        follow.libraryId,
        follow.url,
        follow.title,
        follow.seriesId,
        follow.enabled,
        follow.addedAt,
        follow.lastCheckedAt,
      )
    }

    batch.execute()
  }

  override fun update(follow: Follow) {
    dslRW
      .update(table)
      .set(titleField, follow.title)
      .set(enabledField, follow.enabled)
      .where(idField.eq(follow.id))
      .execute()
  }

  override fun delete(id: String) {
    dslRW.deleteFrom(table).where(idField.eq(id)).execute()
  }

  override fun deleteByIds(ids: Collection<String>) {
    if (ids.isEmpty()) return
    dslRW.deleteFrom(table).where(idField.`in`(ids)).execute()
  }

  override fun clearSeriesId(seriesId: String) {
    dslRW
      .update(table)
      .setNull(seriesIdField)
      .where(seriesIdField.eq(seriesId))
      .execute()
  }

  override fun deleteAllByLibraryId(libraryId: String) {
    dslRW.deleteFrom(table).where(libraryIdField.eq(libraryId)).execute()
  }

  override fun updateLastCheckedBatch(
    ids: Collection<String>,
    checkedAt: LocalDateTime,
  ) {
    if (ids.isEmpty()) return
    dslRW
      .update(table)
      .set(lastCheckedAtField, checkedAt)
      .where(idField.`in`(ids))
      .execute()
  }

  private fun Record.toDomain(): Follow =
    Follow(
      id = get(idField)!!,
      libraryId = get(libraryIdField)!!,
      url = get(urlField)!!,
      title = get(titleField),
      seriesId = get(seriesIdField),
      enabled =
        when (val raw = get(enabledField.name)) {
          is Boolean -> raw
          is Number -> raw.toInt() != 0
          else -> true
        },
      addedAt = getTimestamp(addedAtField) ?: LocalDateTime.now(),
      lastCheckedAt = getTimestamp(lastCheckedAtField),
    )
}
