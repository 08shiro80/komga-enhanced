package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.DownloadChapterHistory

interface DownloadChapterHistoryRepository {
  fun findByDownloadId(downloadId: String): Collection<DownloadChapterHistory>

  fun findByChapterUrl(chapterUrl: String): DownloadChapterHistory?

  fun existsByChapterUrl(chapterUrl: String): Boolean

  fun insert(history: DownloadChapterHistory)

  fun deleteByDownloadId(downloadId: String)

  fun count(): Long

  fun countByDownloadId(downloadId: String): Long
}
