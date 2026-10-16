package org.gotson.komga.domain.model

data class SeriesTrackerId(
  val seriesId: String,
  val tracker: String,
  val trackerId: String,
  val status: Status,
  val source: Source,
) {
  enum class Status {
    ACCEPTED,
    REJECTED,
  }

  enum class Source {
    MAPPING,
    LINK,
    MANGADEX,
    SEARCH,
    MANUAL,
  }
}
