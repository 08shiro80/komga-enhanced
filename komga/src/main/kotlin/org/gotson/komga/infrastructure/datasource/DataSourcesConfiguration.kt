package org.gotson.komga.infrastructure.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.configuration.KomgaSettingsProvider
import org.gotson.komga.infrastructure.configuration.SettingChangedEvent
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

@Configuration
class DataSourcesConfiguration(
  private val komgaProperties: KomgaProperties,
  private val settingsProvider: ObjectProvider<KomgaSettingsProvider>,
) {
  @Bean("sqliteDataSourceRW")
  @Primary
  fun sqliteDataSourceRW(): DataSource =
    buildDataSource("SqliteMainPoolRW", SqliteUdfDataSource::class.java, komgaProperties.database)
      .apply {
        // force pool size to 1 if the pool is only used for writes
        if (komgaProperties.database.shouldSeparateReadFromWrites()) this.maximumPoolSize = 1
      }

  @Bean("sqliteDataSourceRO")
  fun sqliteDataSourceRO(): DataSource =
    if (komgaProperties.database.shouldSeparateReadFromWrites())
      buildDataSource("SqliteMainPoolRO", SqliteUdfDataSource::class.java, komgaProperties.database)
    else
      sqliteDataSourceRW()

  @EventListener(ApplicationReadyEvent::class)
  fun syncPoolSizeFromSettings() {
    resizeRoPool()
  }

  @EventListener(SettingChangedEvent.TaskPoolSize::class)
  fun taskPoolSizeChanged() {
    resizeRoPool()
  }

  private fun resizeRoPool() {
    if (komgaProperties.database.poolSize != null) return
    if (!komgaProperties.database.shouldSeparateReadFromWrites()) return
    val taskPoolSize = settingsProvider.ifAvailable?.taskPoolSize ?: return
    val defaultSize =
      Runtime
        .getRuntime()
        .availableProcessors()
        .coerceAtMost(komgaProperties.database.maxPoolSize)
    val target = maxOf(taskPoolSize, defaultSize)
    val ds = sqliteDataSourceRO() as? HikariDataSource ?: return
    if (ds.maximumPoolSize == target) return
    logger.info { "Resizing SqliteMainPoolRO from ${ds.maximumPoolSize} to $target connections (taskPoolSize)" }
    ds.maximumPoolSize = target
  }

  @Bean("tasksDataSourceRW")
  fun tasksDataSourceRW(): DataSource =
    buildDataSource("SqliteTasksPoolRW", SQLiteDataSource::class.java, komgaProperties.tasksDb)
      .apply {
        // pool size is always 1:
        // - if there's only 1 pool for read and writes, size should be 1
        // - if there's a separate read pool, the write pool size should be 1
        this.maximumPoolSize = 1
      }

  @Bean("tasksDataSourceRO")
  fun tasksDataSourceRO(): DataSource =
    if (komgaProperties.tasksDb.shouldSeparateReadFromWrites())
      buildDataSource("SqliteTasksPoolRO", SQLiteDataSource::class.java, komgaProperties.tasksDb)
    else
      tasksDataSourceRW()

  @Bean("thumbnailsDataSourceRW")
  fun thumbnailsDataSourceRW(): DataSource =
    buildDataSource("SqliteThumbnailsPoolRW", SQLiteDataSource::class.java, komgaProperties.thumbnailsDb)
      .apply {
        this.maximumPoolSize = 1
      }

  @Bean("thumbnailsDataSourceRO")
  fun thumbnailsDataSourceRO(): DataSource =
    if (komgaProperties.thumbnailsDb.shouldSeparateReadFromWrites())
      buildDataSource("SqliteThumbnailsPoolRO", SQLiteDataSource::class.java, komgaProperties.thumbnailsDb)
    else
      thumbnailsDataSourceRW()

  @ConditionalOnProperty(
    value = ["komga.jdbc-session-enabled"],
    havingValue = "true",
    matchIfMissing = false,
  )
  @Bean("sessionsDataSource")
  @SpringSessionDataSource
  fun sessionsDataSource(): DataSource = buildDataSource("SqliteSessionsPool", SQLiteDataSource::class.java, komgaProperties.sessionsDb)

  private fun buildDataSource(
    poolName: String,
    dataSourceClass: Class<out SQLiteDataSource>,
    databaseProps: KomgaProperties.Database,
  ): HikariDataSource {
    val dataSource =
      DataSourceBuilder
        .create()
        .driverClassName("org.sqlite.JDBC")
        .url("jdbc:sqlite:${databaseProps.file}")
        .type(dataSourceClass)
        .build()

    with(dataSource) {
      setEnforceForeignKeys(true)
      setGetGeneratedKeys(false)
    }
    databaseProps.pragmas.forEach { (key, value) ->
      val normalizedKey = key.replace("_", "").replace("-", "")
      val pragma = SQLiteConfig.Pragma.values().firstOrNull { it.name.replace("_", "").equals(normalizedKey, ignoreCase = true) }
      if (pragma != null) {
        dataSource.config.setPragma(pragma, value)
      } else {
        logger.warn { "Unknown SQLite pragma '$key' for pool $poolName — ignoring" }
      }
    }
    with(databaseProps) {
      journalMode?.let { dataSource.setJournalMode(it.name) }
      busyTimeout?.let { dataSource.config.busyTimeout = it.toMillis().toInt() }
    }

    val poolSize =
      if (databaseProps.isMemory()) {
        1
      } else if (databaseProps.poolSize != null) {
        databaseProps.poolSize!!
      } else {
        Runtime
          .getRuntime()
          .availableProcessors()
          .coerceAtMost(databaseProps.maxPoolSize)
      }

    return HikariDataSource(
      HikariConfig().apply {
        this.dataSource = dataSource
        this.poolName = poolName
        this.maximumPoolSize = poolSize
      },
    )
  }

  fun KomgaProperties.Database.isMemory() = file.contains(":memory:") || file.contains("mode=memory")

  fun KomgaProperties.Database.shouldSeparateReadFromWrites(): Boolean = !isMemory() && journalMode == SQLiteConfig.JournalMode.WAL
}
