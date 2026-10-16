package org.gotson.komga.infrastructure.jooq

import org.gotson.komga.infrastructure.jooq.TempTable.Companion.withTempTable
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component

@Component
@DependsOn("flywayThumbnailsMigrationInitializer")
class ThumbnailDataStore(
  @param:Qualifier("thumbnailsDslContextRW") private val dslRW: DSLContext,
  @param:Qualifier("thumbnailsDslContextRO") private val dslRO: DSLContext,
  @param:Value("#{@komgaProperties.database.batchChunkSize}") private val batchSize: Int,
) {
  private val bookData = DSL.table("THUMBNAIL_BOOK_DATA")
  private val bookDataId = DSL.field("ID", String::class.java)
  private val bookDataBytes = DSL.field("DATA", ByteArray::class.java)
  private val pageHashData = DSL.table("PAGE_HASH_THUMBNAIL_DATA")
  private val pageHashDataHash = DSL.field("HASH", String::class.java)
  private val pageHashDataBytes = DSL.field("DATA", ByteArray::class.java)

  fun getBookThumbnail(id: String): ByteArray? =
    dslRO
      .select(bookDataBytes)
      .from(bookData)
      .where(bookDataId.eq(id))
      .fetchOne(bookDataBytes)

  fun putBookThumbnail(
    id: String,
    data: ByteArray,
  ) {
    dslRW
      .insertInto(bookData, bookDataId, bookDataBytes)
      .values(id, data)
      .onConflict(bookDataId)
      .doUpdate()
      .set(bookDataBytes, data)
      .execute()
  }

  fun deleteBookThumbnail(id: String) {
    dslRW.deleteFrom(bookData).where(bookDataId.eq(id)).execute()
  }

  fun deleteBookThumbnails(ids: Collection<String>) {
    if (ids.isEmpty()) return
    dslRW.withTempTable(batchSize, ids).use {
      dslRW.deleteFrom(bookData).where(bookDataId.`in`(it.selectTempStrings())).execute()
    }
  }

  fun findAllBookThumbnailIds(): Set<String> =
    dslRO
      .select(bookDataId)
      .from(bookData)
      .fetchSet(bookDataId)

  fun getPageHashThumbnail(hash: String): ByteArray? =
    dslRO
      .select(pageHashDataBytes)
      .from(pageHashData)
      .where(pageHashDataHash.eq(hash))
      .fetchOne(pageHashDataBytes)

  fun putPageHashThumbnail(
    hash: String,
    data: ByteArray,
  ) {
    dslRW
      .insertInto(pageHashData, pageHashDataHash, pageHashDataBytes)
      .values(hash, data)
      .onConflict(pageHashDataHash)
      .doUpdate()
      .set(pageHashDataBytes, data)
      .execute()
  }

  fun deletePageHashThumbnail(hash: String) {
    dslRW.deleteFrom(pageHashData).where(pageHashDataHash.eq(hash)).execute()
  }

  fun deleteAllPageHashThumbnails() {
    dslRW.deleteFrom(pageHashData).execute()
  }

  fun deletePageHashThumbnails(hashes: Collection<String>) {
    if (hashes.isEmpty()) return
    dslRW.withTempTable(batchSize, hashes).use {
      dslRW.deleteFrom(pageHashData).where(pageHashDataHash.`in`(it.selectTempStrings())).execute()
    }
  }

  fun findAllPageHashKeys(): Set<String> =
    dslRO
      .select(pageHashDataHash)
      .from(pageHashData)
      .fetchSet(pageHashDataHash)
}
