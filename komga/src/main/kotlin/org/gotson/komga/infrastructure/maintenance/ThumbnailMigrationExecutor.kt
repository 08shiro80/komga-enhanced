package org.gotson.komga.infrastructure.maintenance

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.infrastructure.jooq.ThumbnailDataStore
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ThumbnailMigrationExecutor(
  @param:Qualifier("dslContextRW") private val dslRW: DSLContext,
  private val thumbnailDataStore: ThumbnailDataStore,
) {
  private val tb = Tables.THUMBNAIL_BOOK
  private val pht = Tables.PAGE_HASH_THUMBNAIL
  private val ph = Tables.PAGE_HASH

  fun migrateBookThumbnails(): Int {
    var migrated = 0
    while (true) {
      val batch =
        dslRW
          .select(tb.ID, tb.THUMBNAIL)
          .from(tb)
          .where(tb.THUMBNAIL.isNotNull)
          .limit(MIGRATION_BATCH_SIZE)
          .fetch()
      if (batch.isEmpty()) break
      batch.forEach { record ->
        val id = record[tb.ID]
        val bytes = record[tb.THUMBNAIL]
        if (bytes != null) {
          thumbnailDataStore.putBookThumbnail(id, bytes)
          dslRW
            .update(tb)
            .set(tb.THUMBNAIL, null as ByteArray?)
            .where(tb.ID.eq(id))
            .execute()
          migrated++
        }
      }
    }
    logger.info { "Migrated $migrated book thumbnails to the external store" }
    return migrated
  }

  fun migratePageHashThumbnails(): Int {
    var migrated = 0
    while (true) {
      val batch =
        dslRW
          .select(pht.HASH, pht.THUMBNAIL)
          .from(pht)
          .limit(MIGRATION_BATCH_SIZE)
          .fetch()
      if (batch.isEmpty()) break
      batch.forEach { record ->
        val hash = record[pht.HASH]
        val bytes = record[pht.THUMBNAIL]
        if (bytes != null) thumbnailDataStore.putPageHashThumbnail(hash, bytes)
        dslRW.deleteFrom(pht).where(pht.HASH.eq(hash)).execute()
        migrated++
      }
    }
    logger.info { "Migrated $migrated page-hash thumbnails to the external store" }
    return migrated
  }

  fun cleanupOrphans(): Int {
    val storedBookThumbnailIds = thumbnailDataStore.findAllBookThumbnailIds()
    val knownBookThumbnailIds = dslRW.select(tb.ID).from(tb).fetchSet(tb.ID)
    val orphanBookIds = storedBookThumbnailIds - knownBookThumbnailIds
    thumbnailDataStore.deleteBookThumbnails(orphanBookIds)

    val storedPageHashKeys = thumbnailDataStore.findAllPageHashKeys()
    val knownPageHashes = dslRW.select(ph.HASH).from(ph).fetchSet(ph.HASH)
    val orphanHashes = storedPageHashKeys - knownPageHashes
    thumbnailDataStore.deletePageHashThumbnails(orphanHashes)

    val total = orphanBookIds.size + orphanHashes.size
    logger.info { "Cleaned up $total orphaned thumbnails (${orphanBookIds.size} book, ${orphanHashes.size} page-hash)" }
    return total
  }

  fun vacuum() {
    logger.info { "Running VACUUM on the main database to reclaim space from externalized thumbnails" }
    dslRW.execute("VACUUM")
  }

  companion object {
    private const val MIGRATION_BATCH_SIZE = 100
  }
}
