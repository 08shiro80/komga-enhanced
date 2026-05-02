package org.gotson.komga.interfaces.api.rest.dto

import java.time.LocalDateTime

data class PluginDto(
  val id: String,
  val name: String,
  val version: String,
  val enabled: Boolean,
  val pluginType: String,
  val description: String?,
  val author: String?,
  val entryPoint: String,
  val sourceUrl: String?,
  val installedDate: LocalDateTime,
  val lastUpdated: LocalDateTime,
  val configSchema: String?,
  val dependencies: String?,
)

data class PluginUpdateDto(
  val enabled: Boolean,
)
