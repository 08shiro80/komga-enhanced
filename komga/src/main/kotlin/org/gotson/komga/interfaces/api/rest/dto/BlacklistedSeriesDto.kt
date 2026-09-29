package org.gotson.komga.interfaces.api.rest.dto

import org.gotson.komga.domain.model.BlacklistedChapter
import java.time.LocalDateTime

data class BlacklistedSeriesDto(
  val seriesId: String,
  val seriesTitle: String,
  val chapters: List<BlacklistedChapterDto>,
)

data class BlacklistedChapterDto(
  val id: String,
  val chapterUrl: String,
  val chapterNumber: String?,
  val chapterTitle: String?,
  val createdDate: LocalDateTime,
) {
  companion object {
    fun from(chapter: BlacklistedChapter) =
      BlacklistedChapterDto(
        id = chapter.id,
        chapterUrl = chapter.chapterUrl,
        chapterNumber = chapter.chapterNumber,
        chapterTitle = chapter.chapterTitle,
        createdDate = chapter.createdDate,
      )
  }
}
