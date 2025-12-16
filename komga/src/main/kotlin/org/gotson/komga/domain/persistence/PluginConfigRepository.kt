package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.PluginConfig

interface PluginConfigRepository {
  fun findById(id: String): PluginConfig

  fun findByIdOrNull(id: String): PluginConfig?

  fun findAll(): Collection<PluginConfig>

  fun findByPluginId(pluginId: String): Collection<PluginConfig>

  fun findByPluginIdAndKey(
    pluginId: String,
    configKey: String,
  ): PluginConfig?

  fun insert(config: PluginConfig)

  fun update(config: PluginConfig)

  fun delete(id: String)

  fun deleteByPluginId(pluginId: String)

  fun count(): Long
}
