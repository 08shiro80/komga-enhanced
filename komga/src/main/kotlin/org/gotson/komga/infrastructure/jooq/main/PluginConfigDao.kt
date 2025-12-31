package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.PluginConfig
import org.gotson.komga.domain.persistence.PluginConfigRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.PluginConfigRecord
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class PluginConfigDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  PluginConfigRepository {
  private val pc = Tables.PLUGIN_CONFIG

  override fun findById(id: String): PluginConfig = findByIdOrNull(id) ?: throw NoSuchElementException("PluginConfig not found: $id")

  override fun findByIdOrNull(id: String): PluginConfig? =
    dslRO
      .selectFrom(pc)
      .where(pc.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findAll(): Collection<PluginConfig> =
    dslRO
      .selectFrom(pc)
      .fetch()
      .map { it.toDomain() }

  override fun findByPluginId(pluginId: String): Collection<PluginConfig> =
    dslRO
      .selectFrom(pc)
      .where(pc.PLUGIN_ID.eq(pluginId))
      .fetch()
      .map { it.toDomain() }

  override fun findByPluginIdAndKey(
    pluginId: String,
    configKey: String,
  ): PluginConfig? =
    dslRO
      .selectFrom(pc)
      .where(pc.PLUGIN_ID.eq(pluginId))
      .and(pc.CONFIG_KEY.eq(configKey))
      .fetchOne()
      ?.toDomain()

  override fun insert(config: PluginConfig) {
    dslRW
      .insertInto(
        pc,
        pc.ID,
        pc.PLUGIN_ID,
        pc.CONFIG_KEY,
        pc.CONFIG_VALUE,
      ).values(
        config.id,
        config.pluginId,
        config.configKey,
        config.configValue,
      ).execute()
  }

  override fun update(config: PluginConfig) {
    dslRW
      .update(pc)
      .set(pc.CONFIG_VALUE, config.configValue)
      .set(pc.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(pc.ID.eq(config.id))
      .execute()
  }

  override fun delete(id: String) {
    dslRW
      .deleteFrom(pc)
      .where(pc.ID.eq(id))
      .execute()
  }

  override fun deleteByPluginId(pluginId: String) {
    dslRW
      .deleteFrom(pc)
      .where(pc.PLUGIN_ID.eq(pluginId))
      .execute()
  }

  override fun count(): Long = dslRO.fetchCount(pc).toLong()

  private fun PluginConfigRecord.toDomain() =
    PluginConfig(
      id = id,
      pluginId = pluginId,
      configKey = configKey,
      configValue = configValue,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
