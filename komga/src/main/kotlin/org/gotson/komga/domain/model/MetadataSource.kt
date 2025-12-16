package org.gotson.komga.domain.model

import java.time.LocalDateTime

/**
 * Tracks the source of a metadata field.
 * Used to display which provider/plugin set each field in the UI.
 */
data class MetadataSource(
  val field: String,
  val source: String, // e.g., "MangaDex", "ComicInfo", "series.json", "Manual"
  val updatedAt: LocalDateTime = LocalDateTime.now(),
) {
  companion object {
    const val SOURCE_MANGADEX = "MangaDex"
    const val SOURCE_COMICINFO = "ComicInfo"
    const val SOURCE_SERIES_JSON = "series.json"
    const val SOURCE_MYLAR = "Mylar"
    const val SOURCE_EPUB = "EPUB"
    const val SOURCE_MANUAL = "Manual"
    const val SOURCE_FILENAME = "Filename"
    const val SOURCE_GALLERY_DL = "gallery-dl"

    // Fields that can be tracked
    const val FIELD_TITLE = "title"
    const val FIELD_TITLE_SORT = "titleSort"
    const val FIELD_SUMMARY = "summary"
    const val FIELD_PUBLISHER = "publisher"
    const val FIELD_AGE_RATING = "ageRating"
    const val FIELD_LANGUAGE = "language"
    const val FIELD_GENRES = "genres"
    const val FIELD_TAGS = "tags"
    const val FIELD_STATUS = "status"
    const val FIELD_READING_DIRECTION = "readingDirection"
    const val FIELD_ALTERNATE_TITLES = "alternateTitles"
    const val FIELD_LINKS = "links"
    const val FIELD_TOTAL_BOOK_COUNT = "totalBookCount"
  }
}

/**
 * Container for tracking all metadata sources for a series.
 */
data class MetadataSourceInfo(
  val sources: Map<String, MetadataSource> = emptyMap(),
) {
  fun getSource(field: String): MetadataSource? = sources[field]

  fun getSourceName(field: String): String = sources[field]?.source ?: MetadataSource.SOURCE_MANUAL

  fun withSource(
    field: String,
    source: String,
  ): MetadataSourceInfo =
    MetadataSourceInfo(
      sources =
        sources +
          (
            field to
              MetadataSource(
                field = field,
                source = source,
              )
          ),
    )

  fun toMap(): Map<String, Any> =
    sources.mapValues { (_, v) ->
      mapOf(
        "source" to v.source,
        "updatedAt" to v.updatedAt.toString(),
      )
    }

  companion object {
    fun fromMap(map: Map<String, Any>?): MetadataSourceInfo {
      if (map == null) return MetadataSourceInfo()

      val sources =
        map.mapNotNull { (field, value) ->
          @Suppress("UNCHECKED_CAST")
          val valueMap = value as? Map<String, Any> ?: return@mapNotNull null
          val source = valueMap["source"] as? String ?: return@mapNotNull null
          val updatedAt =
            try {
              LocalDateTime.parse(valueMap["updatedAt"] as? String)
            } catch (e: Exception) {
              LocalDateTime.now()
            }

          field to
            MetadataSource(
              field = field,
              source = source,
              updatedAt = updatedAt,
            )
        }.toMap()

      return MetadataSourceInfo(sources)
    }
  }
}
