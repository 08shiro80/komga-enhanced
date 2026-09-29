package org.gotson.komga.infrastructure.datasource

import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

@Component
@DependsOn("flyway")
class FlywayForkMigrationInitializer(
  @Qualifier("sqliteDataSourceRW")
  private val dataSource: DataSource,
) : InitializingBean {
  // Exact fork migration versions (db/migration/fork/sqlite). A bare version-range cutoff cannot be
  // used: base migrations (e.g. V20260225 tags_view, V20260921 book_projection) sit between fork
  // versions and would be wrongly relocated into fork history, breaking base Flyway on next startup.
  private val forkMigrationVersions =
    listOf(
      "20250930120000",
      "20251201000000",
      "20251201000001",
      "20251201000002",
      "20251201000003",
      "20251204000000",
      "20251211000000",
      "20260301000000",
      "20260315000000",
      "20260315000001",
      "20260401000000",
      "20260412000000",
      "20260502000000",
      "20260511000000",
      "20260529000000",
      "20260608000000",
      "20260615000000",
      "20260809000000",
    )

  override fun afterPropertiesSet() {
    migrateFromMainHistory()

    Flyway
      .configure()
      .locations("classpath:db/migration/fork/sqlite")
      .dataSource(dataSource)
      .table("flyway_fork_history")
      .baselineOnMigrate(true)
      .load()
      .apply {
        migrate()
      }
  }

  private fun tableExists(
    conn: java.sql.Connection,
    tableName: String,
  ): Boolean {
    val stmt =
      conn.prepareStatement(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$tableName'",
      )
    return stmt.use { s ->
      val rs = s.executeQuery()
      rs.use { r -> r.next() && r.getInt(1) > 0 }
    }
  }

  private fun migrateFromMainHistory() {
    dataSource.connection.use { conn ->
      if (tableExists(conn, "flyway_fork_history")) return
      if (!tableExists(conn, "flyway_schema_history")) return

      val versionInClause = forkMigrationVersions.joinToString(",") { "'$it'" }

      val countStmt =
        conn.prepareStatement(
          "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ($versionInClause)",
        )
      val count =
        countStmt.use { s ->
          val rs = s.executeQuery()
          rs.use { r ->
            if (r.next()) r.getInt(1) else 0
          }
        }

      if (count == 0) return

      logger.info { "Migrating $count fork entries from flyway_schema_history to flyway_fork_history" }

      val createStmt =
        conn.prepareStatement(
          """CREATE TABLE IF NOT EXISTS flyway_fork_history (
          installed_rank INTEGER NOT NULL,
          version VARCHAR(50),
          description VARCHAR(200) NOT NULL,
          type VARCHAR(20) NOT NULL,
          script VARCHAR(1000) NOT NULL,
          checksum INTEGER,
          installed_by VARCHAR(100) NOT NULL,
          installed_on TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')),
          execution_time INTEGER NOT NULL,
          success INTEGER NOT NULL,
          CONSTRAINT flyway_fork_history_pk PRIMARY KEY (installed_rank)
        )""",
        )
      createStmt.use { it.execute() }

      val copyStmt =
        conn.prepareStatement(
          """INSERT INTO flyway_fork_history
          SELECT * FROM flyway_schema_history WHERE version IN ($versionInClause)""",
        )
      copyStmt.use { it.execute() }

      val deleteStmt =
        conn.prepareStatement(
          "DELETE FROM flyway_schema_history WHERE version IN ($versionInClause)",
        )
      deleteStmt.use { it.execute() }

      logger.info { "Fork migration history moved successfully" }
    }
  }
}
