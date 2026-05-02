package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.DownloadQueue
import org.gotson.komga.domain.model.DownloadStatus

interface DownloadQueueRepository {
  fun findById(id: String): DownloadQueue

  fun findByIdOrNull(id: String): DownloadQueue?

  fun findAll(): Collection<DownloadQueue>

  fun findByStatus(status: DownloadStatus): Collection<DownloadQueue>

  fun findPendingOrdered(): Collection<DownloadQueue>

  fun findActiveDownloads(): Collection<DownloadQueue>

  fun findByLibraryId(libraryId: String): Collection<DownloadQueue>

  fun existsBySourceUrlAndStatusIn(
    sourceUrl: String,
    statuses: Collection<DownloadStatus>,
  ): Boolean

  fun delete(id: String)

  fun insert(download: DownloadQueue)

  fun update(download: DownloadQueue)

  fun count(): Long

  fun countByStatus(status: DownloadStatus): Long

  fun deleteByStatus(status: DownloadStatus): Int
}
