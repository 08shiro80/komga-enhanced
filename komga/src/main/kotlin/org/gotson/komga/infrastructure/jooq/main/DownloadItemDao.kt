package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.DownloadItem
import org.gotson.komga.domain.model.DownloadStatus
import org.gotson.komga.domain.persistence.DownloadItemRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.DownloadItemRecord
import org.gotson.komga.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class DownloadItemDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  DownloadItemRepository {
  private val di = Tables.DOWNLOAD_ITEM

  override fun findById(id: String): DownloadItem = findByIdOrNull(id) ?: throw NoSuchElementException("DownloadItem not found: $id")

  override fun findByIdOrNull(id: String): DownloadItem? =
    dslRO
      .selectFrom(di)
      .where(di.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findAll(): Collection<DownloadItem> =
    dslRO
      .selectFrom(di)
      .orderBy(di.CREATED_DATE.desc())
      .fetch()
      .map { it.toDomain() }

  override fun findByQueueId(queueId: String): Collection<DownloadItem> =
    dslRO
      .selectFrom(di)
      .where(di.QUEUE_ID.eq(queueId))
      .orderBy(di.CREATED_DATE.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findByChapterUrl(chapterUrl: String): DownloadItem? =
    dslRO
      .selectFrom(di)
      .where(di.CHAPTER_URL.eq(chapterUrl))
      .fetchOne()
      ?.toDomain()

  override fun findByStatus(status: DownloadStatus): Collection<DownloadItem> =
    dslRO
      .selectFrom(di)
      .where(di.STATUS.eq(status.name))
      .orderBy(di.CREATED_DATE.asc())
      .fetch()
      .map { it.toDomain() }

  override fun existsByChapterUrl(chapterUrl: String): Boolean =
    dslRO
      .fetchExists(
        dslRO
          .selectFrom(di)
          .where(di.CHAPTER_URL.eq(chapterUrl)),
      )

  override fun delete(id: String) {
    dslRW
      .deleteFrom(di)
      .where(di.ID.eq(id))
      .execute()
  }

  override fun deleteByQueueId(queueId: String) {
    dslRW
      .deleteFrom(di)
      .where(di.QUEUE_ID.eq(queueId))
      .execute()
  }

  override fun insert(item: DownloadItem) {
    dslRW
      .insertInto(
        di,
        di.ID,
        di.QUEUE_ID,
        di.CHAPTER_NUMBER,
        di.CHAPTER_TITLE,
        di.CHAPTER_URL,
        di.STATUS,
        di.FILE_SIZE_BYTES,
        di.DOWNLOADED_BYTES,
        di.FILE_PATH,
        di.SCANLATION_GROUP,
        di.ERROR_MESSAGE,
        di.STARTED_DATE,
        di.COMPLETED_DATE,
      ).values(
        item.id,
        item.queueId,
        item.chapterNumber,
        item.chapterTitle,
        item.chapterUrl,
        item.status.name,
        item.fileSizeBytes,
        item.downloadedBytes,
        item.filePath,
        item.scanlationGroup,
        item.errorMessage,
        item.startedDate,
        item.completedDate,
      ).execute()
  }

  override fun update(item: DownloadItem) {
    dslRW
      .update(di)
      .set(di.QUEUE_ID, item.queueId)
      .set(di.CHAPTER_NUMBER, item.chapterNumber)
      .set(di.CHAPTER_TITLE, item.chapterTitle)
      .set(di.CHAPTER_URL, item.chapterUrl)
      .set(di.STATUS, item.status.name)
      .set(di.FILE_SIZE_BYTES, item.fileSizeBytes)
      .set(di.DOWNLOADED_BYTES, item.downloadedBytes)
      .set(di.FILE_PATH, item.filePath)
      .set(di.SCANLATION_GROUP, item.scanlationGroup)
      .set(di.ERROR_MESSAGE, item.errorMessage)
      .set(di.STARTED_DATE, item.startedDate)
      .set(di.COMPLETED_DATE, item.completedDate)
      .set(di.LAST_MODIFIED_DATE, item.lastModifiedDate)
      .where(di.ID.eq(item.id))
      .execute()
  }

  override fun count(): Long = dslRO.fetchCount(di).toLong()

  override fun countByQueueId(queueId: String): Long =
    dslRO
      .fetchCount(di, di.QUEUE_ID.eq(queueId))
      .toLong()

  private fun DownloadItemRecord.toDomain() =
    DownloadItem(
      id = id,
      queueId = queueId,
      chapterNumber = chapterNumber,
      chapterTitle = chapterTitle,
      chapterUrl = chapterUrl,
      status = DownloadStatus.valueOf(status),
      fileSizeBytes = fileSizeBytes,
      downloadedBytes = downloadedBytes,
      filePath = filePath,
      scanlationGroup = scanlationGroup,
      errorMessage = errorMessage,
      startedDate = startedDate?.toCurrentTimeZone(),
      completedDate = completedDate?.toCurrentTimeZone(),
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
