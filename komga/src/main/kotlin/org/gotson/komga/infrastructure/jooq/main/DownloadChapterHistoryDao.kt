package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.DownloadChapterHistory
import org.gotson.komga.domain.persistence.DownloadChapterHistoryRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDateTime

@Component
class DownloadChapterHistoryDao(
  private val jdbcTemplate: JdbcTemplate,
) : DownloadChapterHistoryRepository {
  private val rowMapper =
    RowMapper { rs: ResultSet, _: Int ->
      DownloadChapterHistory(
        downloadId = rs.getString("DOWNLOAD_ID"),
        chapterUrl = rs.getString("CHAPTER_URL"),
        chapterNumber = rs.getString("CHAPTER_NUMBER"),
        downloadedAt = rs.getTimestamp("DOWNLOADED_AT").toLocalDateTime(),
        cbzFilename = rs.getString("CBZ_FILENAME"),
      )
    }

  override fun findByDownloadId(downloadId: String): Collection<DownloadChapterHistory> {
    val sql =
      """
      SELECT DOWNLOAD_ID, CHAPTER_URL, CHAPTER_NUMBER, DOWNLOADED_AT, CBZ_FILENAME
      FROM DOWNLOAD_CHAPTER_HISTORY
      WHERE DOWNLOAD_ID = ?
      ORDER BY DOWNLOADED_AT DESC
      """.trimIndent()
    return jdbcTemplate.query(sql, rowMapper, downloadId)
  }

  override fun findByChapterUrl(chapterUrl: String): DownloadChapterHistory? {
    val sql =
      """
      SELECT DOWNLOAD_ID, CHAPTER_URL, CHAPTER_NUMBER, DOWNLOADED_AT, CBZ_FILENAME
      FROM DOWNLOAD_CHAPTER_HISTORY
      WHERE CHAPTER_URL = ?
      """.trimIndent()
    return jdbcTemplate.query(sql, rowMapper, chapterUrl).firstOrNull()
  }

  override fun existsByChapterUrl(chapterUrl: String): Boolean {
    val sql =
      """
      SELECT COUNT(*) FROM DOWNLOAD_CHAPTER_HISTORY WHERE CHAPTER_URL = ?
      """.trimIndent()
    val count = jdbcTemplate.queryForObject(sql, Int::class.java, chapterUrl) ?: 0
    return count > 0
  }

  override fun insert(history: DownloadChapterHistory) {
    val sql =
      """
      INSERT INTO DOWNLOAD_CHAPTER_HISTORY (DOWNLOAD_ID, CHAPTER_URL, CHAPTER_NUMBER, DOWNLOADED_AT, CBZ_FILENAME)
      VALUES (?, ?, ?, ?, ?)
      """.trimIndent()
    jdbcTemplate.update(
      sql,
      history.downloadId,
      history.chapterUrl,
      history.chapterNumber,
      history.downloadedAt,
      history.cbzFilename,
    )
  }

  override fun deleteByDownloadId(downloadId: String) {
    val sql = "DELETE FROM DOWNLOAD_CHAPTER_HISTORY WHERE DOWNLOAD_ID = ?"
    jdbcTemplate.update(sql, downloadId)
  }

  override fun count(): Long {
    val sql = "SELECT COUNT(*) FROM DOWNLOAD_CHAPTER_HISTORY"
    return jdbcTemplate.queryForObject(sql, Long::class.java) ?: 0L
  }

  override fun countByDownloadId(downloadId: String): Long {
    val sql = "SELECT COUNT(*) FROM DOWNLOAD_CHAPTER_HISTORY WHERE DOWNLOAD_ID = ?"
    return jdbcTemplate.queryForObject(sql, Long::class.java, downloadId) ?: 0L
  }
}
