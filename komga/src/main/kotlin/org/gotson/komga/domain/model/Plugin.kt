package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class Plugin(
  val id: String,
  val name: String,
  val version: String,
  val author: String?,
  val description: String?,
  val enabled: Boolean = true,
  val pluginType: PluginType,
  val entryPoint: String,
  val sourceUrl: String?,
  val installedDate: LocalDateTime,
  val lastUpdated: LocalDateTime,
  val configSchema: String?, // JSON schema
  val dependencies: String?, // JSON array
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable

enum class PluginType {
  METADATA, // Metadata providers (fetch info from external sources)
  DOWNLOAD, // Download plugins (manga-py, etc.)
  TASK, // Custom task plugins
  PROCESSOR, // Content processors
  NOTIFIER, // Notification plugins
  ANALYZER, // Content analyzers
}

data class PluginPermission(
  val id: String,
  val pluginId: String,
  val permissionType: PermissionType,
  val permissionDetail: String?, // specific resource being accessed
  val granted: Boolean = false,
  val grantedBy: String?,
  val grantedDate: LocalDateTime?,
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable

enum class PermissionType {
  API_ACCESS, // Access to Komga API
  FILESYSTEM, // File system access
  DATABASE, // Direct database access
  NETWORK, // Network/HTTP access
  SYSTEM, // System commands execution
  LIBRARY_READ, // Read from libraries
  LIBRARY_WRITE, // Write to libraries
  METADATA_READ, // Read metadata
  METADATA_WRITE, // Write metadata
}

data class PluginConfig(
  val id: String,
  val pluginId: String,
  val configKey: String,
  val configValue: String?,
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable

data class PluginLog(
  val id: String,
  val pluginId: String,
  val logLevel: LogLevel,
  val message: String,
  val exceptionTrace: String?,
  val createdDate: LocalDateTime = LocalDateTime.now(),
)

enum class LogLevel {
  DEBUG,
  INFO,
  WARN,
  ERROR,
}

// Plugin descriptor used for installation/loading
data class PluginDescriptor(
  val id: String,
  val name: String,
  val version: String,
  val author: String?,
  val description: String?,
  val pluginType: PluginType,
  val entryPoint: String,
  val requiredPermissions: List<PermissionRequest>,
  val configSchema: Map<String, Any>?,
  val dependencies: List<String>,
  val minKomgaVersion: String?,
  val maxKomgaVersion: String?,
)

data class PermissionRequest(
  val permissionType: PermissionType,
  val reason: String, // why this permission is needed
  val detail: String? = null, // specific resource
)
