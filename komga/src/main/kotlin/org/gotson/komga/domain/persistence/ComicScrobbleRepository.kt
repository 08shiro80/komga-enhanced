package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.ComicScrobble

interface ComicScrobbleRepository {
  fun findIssueNumbers(
    userId: String,
    seriesId: String,
  ): Set<String>

  fun insert(scrobble: ComicScrobble)

  fun deleteByUserId(userId: String)
}
