package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.Plugin
import org.gotson.komga.domain.model.PluginType
import org.gotson.komga.domain.persistence.PluginRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.PluginRecord
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class PluginDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  PluginRepository {
  private val p = Tables.PLUGIN

  override fun findById(pluginId: String): Plugin = findByIdOrNull(pluginId) ?: throw NoSuchElementException("Plugin not found: $pluginId")

  override fun findByIdOrNull(pluginId: String): Plugin? =
    dslRO
      .selectFrom(p)
      .where(p.ID.eq(pluginId))
      .fetchOne()
      ?.toDomain()

  override fun findAll(): Collection<Plugin> =
    dslRO
      .selectFrom(p)
      .fetch()
      .map { it.toDomain() }

  override fun delete(pluginId: String) {
    dslRW
      .deleteFrom(p)
      .where(p.ID.eq(pluginId))
      .execute()
  }

  override fun insert(plugin: Plugin) {
    dslRW
      .insertInto(
        p,
        p.ID,
        p.NAME,
        p.VERSION,
        p.ENABLED,
        p.PLUGIN_TYPE,
        p.DESCRIPTION,
        p.AUTHOR,
        p.ENTRY_POINT,
        p.SOURCE_URL,
        p.CONFIG_SCHEMA,
        p.DEPENDENCIES,
      ).values(
        plugin.id,
        plugin.name,
        plugin.version,
        plugin.enabled,
        plugin.pluginType.name,
        plugin.description,
        plugin.author,
        plugin.entryPoint,
        plugin.sourceUrl,
        plugin.configSchema,
        plugin.dependencies,
      ).execute()
  }

  override fun update(plugin: Plugin) {
    dslRW
      .update(p)
      .set(p.NAME, plugin.name)
      .set(p.VERSION, plugin.version)
      .set(p.ENABLED, plugin.enabled)
      .set(p.PLUGIN_TYPE, plugin.pluginType.name)
      .set(p.DESCRIPTION, plugin.description)
      .set(p.AUTHOR, plugin.author)
      .set(p.ENTRY_POINT, plugin.entryPoint)
      .set(p.SOURCE_URL, plugin.sourceUrl)
      .set(p.CONFIG_SCHEMA, plugin.configSchema)
      .set(p.DEPENDENCIES, plugin.dependencies)
      .set(p.LAST_UPDATED, plugin.lastUpdated)
      .where(p.ID.eq(plugin.id))
      .execute()
  }

  override fun count(): Long = dslRO.fetchCount(p).toLong()

  private fun PluginRecord.toDomain() =
    Plugin(
      id = id,
      name = name,
      version = version,
      enabled = enabled,
      pluginType = PluginType.valueOf(pluginType),
      description = description,
      author = author,
      entryPoint = entryPoint,
      sourceUrl = sourceUrl,
      installedDate = installedDate.toCurrentTimeZone(),
      lastUpdated = lastUpdated.toCurrentTimeZone(),
      configSchema = configSchema,
      dependencies = dependencies,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
