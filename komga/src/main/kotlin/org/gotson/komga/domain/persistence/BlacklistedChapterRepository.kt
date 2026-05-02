package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.BlacklistedChapter

interface BlacklistedChapterRepository {
  fun findBySeriesId(seriesId: String): Collection<BlacklistedChapter>

  fun findUrlsBySeriesId(seriesId: String): Set<String>

  fun findAll(): Collection<BlacklistedChapter>

  fun existsByChapterUrl(url: String): Boolean

  fun insert(blacklistedChapter: BlacklistedChapter)

  fun deleteByChapterUrl(url: String)

  fun deleteById(id: String)

  fun countBySeriesId(seriesId: String): Long
}
