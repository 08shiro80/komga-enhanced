package org.gotson.komga.interfaces.api.rest.dto

import java.time.LocalDateTime

/**
 * DTO for metadata source information.
 * Shows which provider/plugin set each metadata field.
 */
data class MetadataSourceDto(
  val field: String,
  val source: String,
  val updatedAt: LocalDateTime?,
)

/**
 * DTO for series metadata sources response.
 */
data class SeriesMetadataSourcesDto(
  val seriesId: String,
  val sources: Map<String, MetadataSourceDto>,
)
