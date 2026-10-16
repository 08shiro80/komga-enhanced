package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.ComicScrobble
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.ComicScrobbleRepository
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.language.toUTC
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class ComicScrobbleDao(
  private val dslRW: DSLContext,
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : ComicScrobbleRepository {
  private val ut = Tables.USER_TRACKER
  private val table = DSL.table("COMIC_SCROBBLE")
  private val userIdField = DSL.field("USER_ID", String::class.java)
  private val seriesIdField = DSL.field("SERIES_ID", String::class.java)
  private val issueNumberField = DSL.field("ISSUE_NUMBER", String::class.java)
  private val metronIssueIdField = DSL.field("METRON_ISSUE_ID", Int::class.java)
  private val bookIdField = DSL.field("BOOK_ID", String::class.java)
  private val dateReadField = DSL.field("DATE_READ", LocalDateTime::class.java)

  override fun findIssueNumbers(
    userId: String,
    seriesId: String,
  ): Set<String> =
    dslRO
      .select(issueNumberField)
      .from(table)
      .where(userIdField.eq(userId))
      .and(seriesIdField.eq(seriesId))
      .fetch(issueNumberField)
      .toSet()

  override fun insert(scrobble: ComicScrobble) {
    dslRW
      .insertInto(table)
      .columns(userIdField, seriesIdField, issueNumberField, metronIssueIdField, bookIdField, dateReadField)
      .select(
        DSL
          .select(
            DSL.`val`(scrobble.userId, userIdField),
            DSL.`val`(scrobble.seriesId, seriesIdField),
            DSL.`val`(scrobble.issueNumber, issueNumberField),
            DSL.`val`(scrobble.metronIssueId, metronIssueIdField),
            DSL.`val`(scrobble.bookId, bookIdField),
            DSL.`val`(scrobble.dateRead?.toUTC(), dateReadField),
          ).whereExists(
            DSL
              .selectOne()
              .from(ut)
              .where(ut.USER_ID.eq(scrobble.userId))
              .and(ut.TRACKER.eq(UserTracker.METRON)),
          ),
      ).onConflictDoNothing()
      .execute()
  }

  override fun deleteByUserId(userId: String) {
    dslRW
      .deleteFrom(table)
      .where(userIdField.eq(userId))
      .execute()
  }
}
