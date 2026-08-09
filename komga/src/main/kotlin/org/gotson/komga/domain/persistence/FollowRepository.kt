package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.Follow
import java.time.LocalDateTime

interface FollowRepository {
  fun findAllByLibraryId(libraryId: String): List<Follow>

  fun findAllBySeriesId(seriesId: String): List<Follow>

  fun findById(id: String): Follow?

  fun existsByLibraryIdAndUrl(
    libraryId: String,
    url: String,
  ): Boolean

  fun insert(follow: Follow)

  fun update(follow: Follow)

  fun delete(id: String)

  fun deleteByIds(ids: Collection<String>)

  fun clearSeriesId(seriesId: String)

  fun deleteAllByLibraryId(libraryId: String)

  fun updateLastChecked(
    id: String,
    checkedAt: LocalDateTime,
  )
}
