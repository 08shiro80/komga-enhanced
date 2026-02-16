package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.DownloadQueue
import org.gotson.komga.domain.model.DownloadStatus
import org.gotson.komga.domain.model.SourceType
import org.gotson.komga.domain.persistence.DownloadQueueRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.DownloadQueueRecord
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class DownloadQueueDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  DownloadQueueRepository {
  private val dq = Tables.DOWNLOAD_QUEUE

  override fun findById(id: String): DownloadQueue = findByIdOrNull(id) ?: throw NoSuchElementException("DownloadQueue not found: $id")

  override fun findByIdOrNull(id: String): DownloadQueue? =
    dslRO
      .selectFrom(dq)
      .where(dq.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findAll(): Collection<DownloadQueue> =
    dslRO
      .selectFrom(dq)
      .orderBy(dq.CREATED_DATE.desc())
      .fetch()
      .map { it.toDomain() }

  override fun findByStatus(status: DownloadStatus): Collection<DownloadQueue> =
    dslRO
      .selectFrom(dq)
      .where(dq.STATUS.eq(status.name))
      .orderBy(dq.PRIORITY.asc(), dq.CREATED_DATE.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findPendingOrdered(): Collection<DownloadQueue> =
    dslRO
      .selectFrom(dq)
      .where(dq.STATUS.eq(DownloadStatus.PENDING.name))
      .orderBy(dq.PRIORITY.asc(), dq.CREATED_DATE.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findActiveDownloads(): Collection<DownloadQueue> =
    dslRO
      .selectFrom(dq)
      .where(dq.STATUS.`in`(DownloadStatus.DOWNLOADING.name, DownloadStatus.PENDING.name))
      .orderBy(dq.PRIORITY.asc(), dq.CREATED_DATE.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findByLibraryId(libraryId: String): Collection<DownloadQueue> =
    dslRO
      .selectFrom(dq)
      .where(dq.LIBRARY_ID.eq(libraryId))
      .orderBy(dq.CREATED_DATE.desc())
      .fetch()
      .map { it.toDomain() }

  override fun existsBySourceUrlAndStatusIn(
    sourceUrl: String,
    statuses: Collection<DownloadStatus>,
  ): Boolean =
    dslRO.fetchExists(
      dslRO
        .selectFrom(dq)
        .where(dq.SOURCE_URL.eq(sourceUrl))
        .and(dq.STATUS.`in`(statuses.map { it.name })),
    )

  override fun delete(id: String) {
    dslRW
      .deleteFrom(dq)
      .where(dq.ID.eq(id))
      .execute()
  }

  override fun insert(download: DownloadQueue) {
    dslRW
      .insertInto(
        dq,
        dq.ID,
        dq.SOURCE_URL,
        dq.SOURCE_TYPE,
        dq.TITLE,
        dq.AUTHOR,
        dq.STATUS,
        dq.PROGRESS_PERCENT,
        dq.CURRENT_CHAPTER,
        dq.TOTAL_CHAPTERS,
        dq.LIBRARY_ID,
        dq.DESTINATION_PATH,
        dq.ERROR_MESSAGE,
        dq.PLUGIN_ID,
        dq.METADATA_JSON,
        dq.CREATED_BY,
        dq.STARTED_DATE,
        dq.COMPLETED_DATE,
        dq.PRIORITY,
        dq.RETRY_COUNT,
        dq.MAX_RETRIES,
      ).values(
        download.id,
        download.sourceUrl,
        download.sourceType.name,
        download.title,
        download.author,
        download.status.name,
        download.progressPercent,
        download.currentChapter,
        download.totalChapters,
        download.libraryId,
        download.destinationPath,
        download.errorMessage,
        download.pluginId,
        download.metadataJson,
        download.createdBy,
        download.startedDate,
        download.completedDate,
        download.priority,
        download.retryCount,
        download.maxRetries,
      ).execute()
  }

  override fun update(download: DownloadQueue) {
    dslRW
      .update(dq)
      .set(dq.SOURCE_URL, download.sourceUrl)
      .set(dq.SOURCE_TYPE, download.sourceType.name)
      .set(dq.TITLE, download.title)
      .set(dq.AUTHOR, download.author)
      .set(dq.STATUS, download.status.name)
      .set(dq.PROGRESS_PERCENT, download.progressPercent)
      .set(dq.CURRENT_CHAPTER, download.currentChapter)
      .set(dq.TOTAL_CHAPTERS, download.totalChapters)
      .set(dq.LIBRARY_ID, download.libraryId)
      .set(dq.DESTINATION_PATH, download.destinationPath)
      .set(dq.ERROR_MESSAGE, download.errorMessage)
      .set(dq.PLUGIN_ID, download.pluginId)
      .set(dq.METADATA_JSON, download.metadataJson)
      .set(dq.CREATED_BY, download.createdBy)
      .set(dq.STARTED_DATE, download.startedDate)
      .set(dq.COMPLETED_DATE, download.completedDate)
      .set(dq.PRIORITY, download.priority)
      .set(dq.RETRY_COUNT, download.retryCount)
      .set(dq.MAX_RETRIES, download.maxRetries)
      .set(dq.LAST_MODIFIED_DATE, download.lastModifiedDate)
      .where(dq.ID.eq(download.id))
      .execute()
  }

  override fun count(): Long = dslRO.fetchCount(dq).toLong()

  override fun countByStatus(status: DownloadStatus): Long =
    dslRO
      .fetchCount(dq, dq.STATUS.eq(status.name))
      .toLong()

  override fun deleteByStatus(status: DownloadStatus): Int =
    dslRW
      .deleteFrom(dq)
      .where(dq.STATUS.eq(status.name))
      .execute()

  private fun DownloadQueueRecord.toDomain() =
    DownloadQueue(
      id = id,
      sourceUrl = sourceUrl,
      sourceType = SourceType.valueOf(sourceType),
      title = title,
      author = author,
      status = DownloadStatus.valueOf(status),
      progressPercent = progressPercent,
      currentChapter = currentChapter,
      totalChapters = totalChapters,
      libraryId = libraryId,
      destinationPath = destinationPath,
      errorMessage = errorMessage,
      pluginId = pluginId,
      metadataJson = metadataJson,
      createdBy = createdBy,
      startedDate = startedDate?.toCurrentTimeZone(),
      completedDate = completedDate?.toCurrentTimeZone(),
      priority = priority,
      retryCount = retryCount,
      maxRetries = maxRetries,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
