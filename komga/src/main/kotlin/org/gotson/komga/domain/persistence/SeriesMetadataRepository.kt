package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.SeriesMetadata

interface SeriesMetadataRepository {
  fun findById(seriesId: String): SeriesMetadata

  fun findByIdOrNull(seriesId: String): SeriesMetadata?

  fun findSeriesIdByLinkUrlContaining(
    libraryId: String,
    urlPart: String,
  ): String?

  fun insert(metadata: SeriesMetadata)

  fun update(metadata: SeriesMetadata)

  fun delete(seriesId: String)

  fun delete(seriesIds: Collection<String>)

  fun count(): Long
}
