package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.DownloadItem
import org.gotson.komga.domain.model.DownloadStatus

interface DownloadItemRepository {
  fun findById(id: String): DownloadItem

  fun findByIdOrNull(id: String): DownloadItem?

  fun findAll(): Collection<DownloadItem>

  fun findByQueueId(queueId: String): Collection<DownloadItem>

  fun findByChapterUrl(chapterUrl: String): DownloadItem?

  fun findByStatus(status: DownloadStatus): Collection<DownloadItem>

  fun existsByChapterUrl(chapterUrl: String): Boolean

  fun delete(id: String)

  fun deleteByQueueId(queueId: String)

  fun insert(item: DownloadItem)

  fun update(item: DownloadItem)

  fun count(): Long

  fun countByQueueId(queueId: String): Long
}
