package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.Plugin

interface PluginRepository {
  fun findById(pluginId: String): Plugin

  fun findByIdOrNull(pluginId: String): Plugin?

  fun findAll(): Collection<Plugin>

  fun delete(pluginId: String)

  fun insert(plugin: Plugin)

  fun update(plugin: Plugin)

  fun count(): Long
}
