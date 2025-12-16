package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.LogLevel
import org.gotson.komga.domain.model.PluginLog
import org.gotson.komga.domain.persistence.PluginLogRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.PluginLogRecord
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Component

@Component
class PluginLogDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  PluginLogRepository {
  private val pl = Tables.PLUGIN_LOG

  override fun findById(id: String): PluginLog = findByIdOrNull(id) ?: throw Exception("PluginLog not found: $id")

  override fun findByIdOrNull(id: String): PluginLog? =
    dslRO
      .selectFrom(pl)
      .where(pl.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findAll(pageable: Pageable): Page<PluginLog> {
    val count = dslRO.fetchCount(pl).toLong()

    val results =
      dslRO
        .selectFrom(pl)
        .orderBy(pl.CREATED_DATE.desc())
        .limit(pageable.pageSize)
        .offset(pageable.offset)
        .fetch()
        .map { it.toDomain() }

    return PageImpl(results, pageable, count)
  }

  override fun findByPluginId(
    pluginId: String,
    pageable: Pageable,
  ): Page<PluginLog> {
    val count =
      dslRO
        .fetchCount(
          dslRO
            .selectFrom(pl)
            .where(pl.PLUGIN_ID.eq(pluginId)),
        ).toLong()

    val results =
      dslRO
        .selectFrom(pl)
        .where(pl.PLUGIN_ID.eq(pluginId))
        .orderBy(pl.CREATED_DATE.desc())
        .limit(pageable.pageSize)
        .offset(pageable.offset)
        .fetch()
        .map { it.toDomain() }

    return PageImpl(results, pageable, count)
  }

  override fun findByPluginIdAndLevel(
    pluginId: String,
    logLevel: LogLevel,
    pageable: Pageable,
  ): Page<PluginLog> {
    val count =
      dslRO
        .fetchCount(
          dslRO
            .selectFrom(pl)
            .where(pl.PLUGIN_ID.eq(pluginId))
            .and(pl.LOG_LEVEL.eq(logLevel.name)),
        ).toLong()

    val results =
      dslRO
        .selectFrom(pl)
        .where(pl.PLUGIN_ID.eq(pluginId))
        .and(pl.LOG_LEVEL.eq(logLevel.name))
        .orderBy(pl.CREATED_DATE.desc())
        .limit(pageable.pageSize)
        .offset(pageable.offset)
        .fetch()
        .map { it.toDomain() }

    return PageImpl(results, pageable, count)
  }

  override fun insert(log: PluginLog) {
    dslRW
      .insertInto(
        pl,
        pl.ID,
        pl.PLUGIN_ID,
        pl.LOG_LEVEL,
        pl.MESSAGE,
        pl.EXCEPTION_TRACE,
      ).values(
        log.id,
        log.pluginId,
        log.logLevel.name,
        log.message,
        log.exceptionTrace,
      ).execute()
  }

  override fun delete(id: String) {
    dslRW
      .deleteFrom(pl)
      .where(pl.ID.eq(id))
      .execute()
  }

  override fun deleteByPluginId(pluginId: String) {
    dslRW
      .deleteFrom(pl)
      .where(pl.PLUGIN_ID.eq(pluginId))
      .execute()
  }

  override fun deleteOlderThan(cutoffDate: java.time.LocalDateTime) {
    dslRW
      .deleteFrom(pl)
      .where(pl.CREATED_DATE.lt(cutoffDate))
      .execute()
  }

  override fun count(): Long = dslRO.fetchCount(pl).toLong()

  private fun PluginLogRecord.toDomain() =
    PluginLog(
      id = id,
      pluginId = pluginId,
      logLevel = LogLevel.valueOf(logLevel),
      message = message,
      exceptionTrace = exceptionTrace,
      createdDate = createdDate.toCurrentTimeZone(),
    )
}
