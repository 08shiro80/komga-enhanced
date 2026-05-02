package org.gotson.komga.domain.service

import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.sql.DataSource
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private val logger = KotlinLogging.logger {}

@Service
class BackupLifecycle(
  private val komgaProperties: org.gotson.komga.infrastructure.configuration.KomgaProperties,
  private val applicationContext: org.springframework.context.ApplicationContext,
  @Qualifier("sqliteDataSourceRW") private val dataSourceRW: DataSource,
  @Qualifier("sqliteDataSourceRO") private val dataSourceRO: DataSource,
) {
  private val backupDir: Path
    get() = Paths.get(komgaProperties.configDir.toString(), "backups")

  init {
    // Ensure backup directory exists
    if (!backupDir.exists()) {
      Files.createDirectories(backupDir)
      logger.info { "Created backup directory: $backupDir" }
    }
  }

  /**
   * Create a backup of the main database
   */
  fun createBackup(): BackupInfo {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val backupFileName = "komga_backup_$timestamp.db"
    val backupFile = backupDir.resolve(backupFileName)

    val sourceDb = Paths.get(komgaProperties.configDir.toString(), "database.sqlite")

    if (!sourceDb.exists() || !sourceDb.isRegularFile()) {
      // Check if using in-memory database
      if (komgaProperties.database.file.contains("mode=memory")) {
        throw IllegalStateException(
          "Cannot create backup: Database is running in in-memory mode. " +
            "To enable backups, please run Komga with the 'localdb' profile: " +
            "SET SPRING_PROFILES_ACTIVE=dev,localdb,noclaim && gradlew.bat bootRun",
        )
      }
      throw IllegalStateException("Database file not found: $sourceDb. Configured database: ${komgaProperties.database.file}")
    }

    logger.info { "Creating backup from $sourceDb to $backupFile" }

    try {
      // Copy database file
      Files.copy(sourceDb, backupFile, StandardCopyOption.REPLACE_EXISTING)

      val fileSize = backupFile.fileSize()
      logger.info { "Backup created successfully: $backupFile (${fileSize / 1024 / 1024} MB)" }

      return BackupInfo(
        fileName = backupFileName,
        filePath = backupFile.toString(),
        createdDate = LocalDateTime.now(),
        sizeBytes = fileSize,
        type = BackupType.MANUAL,
      )
    } catch (e: Exception) {
      logger.error(e) { "Failed to create backup" }
      throw BackupException("Failed to create backup: ${e.message}", e)
    }
  }

  /**
   * Create a backup of the tasks database
   */
  fun createTasksBackup(): BackupInfo {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val backupFileName = "komga_tasks_backup_$timestamp.db"
    val backupFile = backupDir.resolve(backupFileName)

    val sourceDb = Paths.get(komgaProperties.configDir.toString(), "tasks.sqlite")

    if (!sourceDb.exists() || !sourceDb.isRegularFile()) {
      // Check if using in-memory database
      if (komgaProperties.tasksDb.file.contains("mode=memory")) {
        throw IllegalStateException(
          "Cannot create tasks backup: Tasks database is running in in-memory mode. " +
            "To enable backups, please run Komga with the 'localdb' profile: " +
            "SET SPRING_PROFILES_ACTIVE=dev,localdb,noclaim && gradlew.bat bootRun",
        )
      }
      throw IllegalStateException("Tasks database file not found: $sourceDb. Configured database: ${komgaProperties.tasksDb.file}")
    }

    logger.info { "Creating tasks backup from $sourceDb to $backupFile" }

    try {
      Files.copy(sourceDb, backupFile, StandardCopyOption.REPLACE_EXISTING)

      val fileSize = backupFile.fileSize()
      logger.info { "Tasks backup created successfully: $backupFile" }

      return BackupInfo(
        fileName = backupFileName,
        filePath = backupFile.toString(),
        createdDate = LocalDateTime.now(),
        sizeBytes = fileSize,
        type = BackupType.MANUAL,
      )
    } catch (e: Exception) {
      logger.error(e) { "Failed to create tasks backup" }
      throw BackupException("Failed to create tasks backup: ${e.message}", e)
    }
  }

  /**
   * Create a full backup (both databases)
   */
  fun createFullBackup(): FullBackupInfo {
    val mainBackup = createBackup()
    val tasksBackup = createTasksBackup()

    return FullBackupInfo(
      mainDatabase = mainBackup,
      tasksDatabase = tasksBackup,
      createdDate = LocalDateTime.now(),
    )
  }

  /**
   * List all available backups
   */
  fun listBackups(): List<BackupInfo> {
    if (!backupDir.exists()) {
      return emptyList()
    }

    return Files
      .list(backupDir)
      .filter { it.isRegularFile() && it.fileName.toString().endsWith(".db") }
      .map { path ->
        BackupInfo(
          fileName = path.fileName.toString(),
          filePath = path.toString(),
          createdDate =
            LocalDateTime.ofEpochSecond(
              Files.getLastModifiedTime(path).toInstant().epochSecond,
              0,
              java.time.ZoneOffset.UTC,
            ),
          sizeBytes = path.fileSize(),
          type = BackupType.MANUAL,
        )
      }.sorted()
      .toList()
  }

  /**
   * Delete a backup file
   */
  fun deleteBackup(fileName: String): Boolean {
    val backupFile = backupDir.resolve(fileName)

    if (!backupFile.exists() || !backupFile.isRegularFile()) {
      logger.warn { "Backup file not found: $backupFile" }
      return false
    }

    // Security check: ensure file is in backup directory
    if (!backupFile.startsWith(backupDir)) {
      throw SecurityException("Attempted to delete file outside backup directory")
    }

    return try {
      Files.delete(backupFile)
      logger.info { "Deleted backup: $backupFile" }
      true
    } catch (e: Exception) {
      logger.error(e) { "Failed to delete backup: $backupFile" }
      false
    }
  }

  /**
   * Get backup file for download
   */
  fun getBackupFile(fileName: String): File {
    val backupFile = backupDir.resolve(fileName)

    if (!backupFile.exists() || !backupFile.isRegularFile()) {
      throw IllegalArgumentException("Backup file not found: $fileName")
    }

    // Security check: ensure file is in backup directory
    if (!backupFile.startsWith(backupDir)) {
      throw SecurityException("Attempted to access file outside backup directory")
    }

    return backupFile.toFile()
  }

  /**
   * Clean old backups (keep only last N backups)
   */
  fun cleanOldBackups(keepCount: Int = 10): Int {
    val backups = listBackups()

    if (backups.size <= keepCount) {
      return 0
    }

    val toDelete = backups.sortedByDescending { it.createdDate }.drop(keepCount)
    var deleted = 0

    toDelete.forEach { backup ->
      if (deleteBackup(backup.fileName)) {
        deleted++
      }
    }

    logger.info { "Cleaned $deleted old backups (kept $keepCount)" }
    return deleted
  }

  /**
   * Execute checkpoint and close WAL journal for SQLite
   */
  private fun checkpointAndClose(ds: DataSource) {
    try {
      ds.connection.use { conn ->
        conn.createStatement().use { stmt ->
          // Checkpoint all pending WAL transactions
          stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)")
          logger.info { "WAL checkpoint completed" }
        }
      }
    } catch (e: Exception) {
      logger.warn(e) { "Could not execute checkpoint" }
    }
  }

  /**
   * Close all HikariCP datasources for the main database
   */
  private fun closeAllMainDataSources() {
    logger.info { "Closing all database connections..." }

    // First, checkpoint to flush WAL
    try {
      checkpointAndClose(dataSourceRW)
    } catch (e: Exception) {
      logger.warn(e) { "Failed to checkpoint RW datasource" }
    }

    // Close RW datasource
    if (dataSourceRW is HikariDataSource) {
      try {
        dataSourceRW.close()
        logger.info { "RW DataSource closed" }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to close RW datasource" }
      }
    }

    // Close RO datasource if it's different from RW
    if (dataSourceRO !== dataSourceRW && dataSourceRO is HikariDataSource) {
      try {
        dataSourceRO.close()
        logger.info { "RO DataSource closed" }
      } catch (e: Exception) {
        logger.warn(e) { "Failed to close RO datasource" }
      }
    }

    // Force garbage collection to release any lingering handles
    System.gc()
    Thread.sleep(500)
  }

  /**
   * Restore from backup (requires application restart)
   */
  fun restoreBackup(fileName: String): RestoreInfo {
    val backupFile = backupDir.resolve(fileName)

    if (!backupFile.exists() || !backupFile.isRegularFile()) {
      throw IllegalArgumentException("Backup file not found: $fileName")
    }

    // Security check
    if (!backupFile.startsWith(backupDir)) {
      throw SecurityException("Attempted to access file outside backup directory")
    }

    val targetDb = Paths.get(komgaProperties.configDir.toString(), "database.sqlite")
    val tempBackup = Paths.get(komgaProperties.configDir.toString(), "database.sqlite.pre-restore")
    val walFile = Paths.get(komgaProperties.configDir.toString(), "database.sqlite-wal")
    val shmFile = Paths.get(komgaProperties.configDir.toString(), "database.sqlite-shm")

    try {
      // Close all database connections
      closeAllMainDataSources()

      // Wait for file to be unlocked with retry mechanism
      var attempts = 0
      val maxAttempts = 15
      var fileUnlocked = false

      while (attempts < maxAttempts && !fileUnlocked) {
        try {
          if (targetDb.exists()) {
            // Try to rename file to test if it's locked
            val testFile = targetDb.resolveSibling("database.sqlite.test")
            Files.move(targetDb, testFile, StandardCopyOption.ATOMIC_MOVE)
            Files.move(testFile, targetDb, StandardCopyOption.ATOMIC_MOVE)
            fileUnlocked = true
          } else {
            fileUnlocked = true
          }
        } catch (e: Exception) {
          attempts++
          if (attempts < maxAttempts) {
            val waitTime = 1000L // Fixed 1 second wait
            logger.warn { "Database file is locked, waiting ${waitTime}ms before retry (attempt $attempts/$maxAttempts)" }
            System.gc() // Try to force release
            Thread.sleep(waitTime)
          } else {
            logger.error { "Database file remains locked after $maxAttempts attempts" }
            throw BackupException(
              "Database file is locked by another process. The application will shut down. " +
                "Please restart the application and try restore again, or manually replace the database file.",
              e,
            )
          }
        }
      }

      logger.info { "Database file unlocked successfully after $attempts attempts" }

      // Create backup of current database before restoring
      if (targetDb.exists()) {
        Files.copy(targetDb, tempBackup, StandardCopyOption.REPLACE_EXISTING)
        logger.info { "Created pre-restore backup: $tempBackup" }
      }

      // Delete WAL and SHM files if they exist (they will be recreated)
      if (walFile.exists()) {
        Files.delete(walFile)
        logger.info { "Deleted WAL file" }
      }
      if (shmFile.exists()) {
        Files.delete(shmFile)
        logger.info { "Deleted SHM file" }
      }

      // Copy backup file to target location
      Files.copy(backupFile, targetDb, StandardCopyOption.REPLACE_EXISTING)
      logger.info { "Restored database from backup: $fileName" }

      // Schedule application shutdown (user must restart manually for safety)
      Thread {
        try {
          Thread.sleep(2000) // Give time for response to be sent
          logger.info { "Shutting down application after backup restore..." }
          val exitCode =
            org.springframework.boot.SpringApplication
              .exit(applicationContext, { 0 })
          System.exit(exitCode)
        } catch (e: Exception) {
          logger.error(e) { "Failed to shutdown application" }
        }
      }.start()

      return RestoreInfo(
        backupFileName = fileName,
        requiresRestart = true,
        message = "Database restored successfully. Application will shut down in 2 seconds. Please restart manually.",
      )
    } catch (e: BackupException) {
      // Re-throw BackupException as-is
      throw e
    } catch (e: Exception) {
      logger.error(e) { "Failed to restore backup: ${e.message}" }
      throw BackupException("Failed to restore backup: ${e.message}", e)
    }
  }
}

data class BackupInfo(
  val fileName: String,
  val filePath: String,
  val createdDate: LocalDateTime,
  val sizeBytes: Long,
  val type: BackupType,
) : Comparable<BackupInfo> {
  override fun compareTo(other: BackupInfo): Int = other.createdDate.compareTo(this.createdDate) // Newest first
}

data class FullBackupInfo(
  val mainDatabase: BackupInfo,
  val tasksDatabase: BackupInfo,
  val createdDate: LocalDateTime,
)

data class RestoreInfo(
  val backupFileName: String,
  val requiresRestart: Boolean,
  val message: String,
)

enum class BackupType {
  MANUAL,
  AUTOMATIC,
  SCHEDULED,
}

class BackupException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
