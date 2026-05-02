package org.gotson.komga.domain.service

/**
 * Interface for online metadata providers that support searching
 */
interface OnlineMetadataProvider {
  /**
   * Search for manga/series by title
   */
  fun search(query: String): List<MetadataSearchResult>

  /**
   * Get detailed metadata for a specific result
   */
  fun getMetadata(externalId: String): MetadataDetails?
}

data class MetadataSearchResult(
  val externalId: String,
  val title: String,
  val description: String?,
  val coverUrl: String?,
  val author: String?,
  val year: Int?,
  val status: String?,
  val tags: List<String> = emptyList(),
  val provider: String,
)

data class MetadataDetails(
  val title: String,
  val titleSort: String?,
  val summary: String?,
  val publisher: String?,
  val ageRating: Int?,
  val releaseDate: String?,
  val authors: List<Author> = emptyList(),
  val tags: List<String> = emptyList(),
  val genres: List<String> = emptyList(),
  val language: String?,
  val status: String?,
  val coverUrl: String?,
  /** Alternative titles in different languages, mapped as title -> language code */
  val alternativeTitles: Map<String, String> = emptyMap(),
)

data class Author(
  val name: String,
  val role: String,
)
