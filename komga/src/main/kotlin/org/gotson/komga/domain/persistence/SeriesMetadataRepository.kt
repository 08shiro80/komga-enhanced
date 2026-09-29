package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.SeriesMetadata

interface SeriesMetadataRepository {
  fun findById(seriesId: String): SeriesMetadata

  fun findByIdOrNull(seriesId: String): SeriesMetadata?

  fun findTitlesByIds(seriesIds: Collection<String>): Map<String, String>

  fun findSeriesIdByLinkUrlContaining(
    libraryId: String,
    urlPart: String,
  ): String?

  fun findSeriesIdByLinkQueryParam(
    libraryId: String,
    param: String,
    value: String,
  ): String?

  fun insert(metadata: SeriesMetadata)

  fun update(metadata: SeriesMetadata)

  fun delete(seriesId: String)

  fun delete(seriesIds: Collection<String>)

  fun count(): Long
}
