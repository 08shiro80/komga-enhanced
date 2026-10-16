package org.gotson.komga.infrastructure.jooq.main

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.UserTracker
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.infrastructure.security.TrackerSecretCipher
import org.gotson.komga.infrastructure.security.TrackerSecretException
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.UserTrackerRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class UserTrackerDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  private val trackerSecretCipher: TrackerSecretCipher,
) : SplitDslDaoBase(dslRW, dslRO),
  UserTrackerRepository {
  private val ut = Tables.USER_TRACKER
  private val reportedUnreadable = ConcurrentHashMap.newKeySet<String>()

  override fun findByUserId(userId: String): Collection<UserTracker> =
    dslRO
      .selectFrom(ut)
      .where(ut.USER_ID.eq(userId))
      .fetch()
      .toDomain()

  override fun findByUserIdAndTracker(
    userId: String,
    tracker: String,
  ): UserTracker? =
    dslRO
      .selectFrom(ut)
      .where(ut.USER_ID.eq(userId))
      .and(ut.TRACKER.eq(tracker))
      .fetch()
      .toDomain()
      .firstOrNull()

  override fun exists(
    userId: String,
    tracker: String,
  ): Boolean =
    dslRO.fetchExists(
      dslRO
        .selectOne()
        .from(ut)
        .where(ut.USER_ID.eq(userId))
        .and(ut.TRACKER.eq(tracker)),
    )

  override fun findByTracker(tracker: String): Collection<UserTracker> =
    dslRO
      .selectFrom(ut)
      .where(ut.TRACKER.eq(tracker))
      .fetch()
      .toDomain()

  @Transactional
  override fun save(userTracker: UserTracker) {
    trackerSecretCipher.requireKey()
    val stored = userTracker.settings.mapValues { (key, value) -> storedValue(key, value) }
    dslRW
      .deleteFrom(ut)
      .where(ut.USER_ID.eq(userTracker.userId))
      .and(ut.TRACKER.eq(userTracker.tracker))
      .execute()
    stored.forEach { (key, value) ->
      dslRW
        .insertInto(ut, ut.USER_ID, ut.TRACKER, ut.CONFIG_KEY, ut.CONFIG_VALUE)
        .values(userTracker.userId, userTracker.tracker, key, value)
        .execute()
    }
  }

  @Transactional
  override fun updateRefreshedTokens(
    userId: String,
    tracker: String,
    usedAccessToken: String?,
    usedRefreshToken: String,
    accessToken: String,
    refreshToken: String?,
  ): Boolean {
    val stored =
      dslRW
        .select(ut.CONFIG_KEY, ut.CONFIG_VALUE)
        .from(ut)
        .where(ut.USER_ID.eq(userId))
        .and(ut.TRACKER.eq(tracker))
        .and(ut.CONFIG_KEY.`in`(UserTracker.KEY_ACCESS_TOKEN, UserTracker.KEY_REFRESH_TOKEN))
        .fetchMap(ut.CONFIG_KEY, ut.CONFIG_VALUE)
        .mapValues { (key, value) -> value?.let { readValue(userId, tracker, key, it) } }
    if (stored[UserTracker.KEY_REFRESH_TOKEN] != usedRefreshToken || stored[UserTracker.KEY_ACCESS_TOKEN] != usedAccessToken) return false
    updateValue(userId, tracker, UserTracker.KEY_ACCESS_TOKEN, storedValue(UserTracker.KEY_ACCESS_TOKEN, accessToken))
    if (refreshToken != null) updateValue(userId, tracker, UserTracker.KEY_REFRESH_TOKEN, storedValue(UserTracker.KEY_REFRESH_TOKEN, refreshToken))
    return true
  }

  @Transactional
  override fun updateStatus(
    userId: String,
    tracker: String,
    values: Map<String, String?>,
  ): Boolean {
    val exists =
      dslRW.fetchExists(
        dslRW
          .selectOne()
          .from(ut)
          .where(ut.USER_ID.eq(userId))
          .and(ut.TRACKER.eq(tracker))
          .and(ut.CONFIG_KEY.notIn(UserTracker.statusKeys + UserTracker.KEY_ENABLED)),
      )
    if (!exists) return false
    values.forEach { (key, value) ->
      dslRW
        .deleteFrom(ut)
        .where(ut.USER_ID.eq(userId))
        .and(ut.TRACKER.eq(tracker))
        .and(ut.CONFIG_KEY.eq(key))
        .execute()
      if (value != null) {
        dslRW
          .insertInto(ut, ut.USER_ID, ut.TRACKER, ut.CONFIG_KEY, ut.CONFIG_VALUE)
          .values(userId, tracker, key, value)
          .execute()
      }
    }
    return true
  }

  override fun countEncryptedSecrets(): Int =
    dslRO
      .select(ut.CONFIG_VALUE)
      .from(ut)
      .where(ut.CONFIG_KEY.`in`(UserTracker.secretKeys))
      .fetch(ut.CONFIG_VALUE)
      .count { it != null && trackerSecretCipher.isEncrypted(it) }

  @Transactional
  override fun encryptPlaintextSecrets(): Int {
    val plaintext =
      dslRW
        .selectFrom(ut)
        .where(ut.CONFIG_KEY.`in`(UserTracker.secretKeys))
        .and(ut.CONFIG_VALUE.isNotNull)
        .fetch()
        .filter { !it.configValue.isNullOrBlank() && !trackerSecretCipher.isEncrypted(it.configValue) }
    return plaintext.sumOf { record ->
      dslRW
        .update(ut)
        .set(ut.CONFIG_VALUE, trackerSecretCipher.encrypt(record.configValue))
        .set(ut.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
        .where(ut.USER_ID.eq(record.userId))
        .and(ut.TRACKER.eq(record.tracker))
        .and(ut.CONFIG_KEY.eq(record.configKey))
        .and(ut.CONFIG_VALUE.eq(record.configValue))
        .execute()
    }
  }

  private fun updateValue(
    userId: String,
    tracker: String,
    key: String,
    value: String,
  ) {
    dslRW
      .update(ut)
      .set(ut.CONFIG_VALUE, value)
      .set(ut.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(ut.USER_ID.eq(userId))
      .and(ut.TRACKER.eq(tracker))
      .and(ut.CONFIG_KEY.eq(key))
      .execute()
  }

  override fun delete(
    userId: String,
    tracker: String,
  ) {
    dslRW
      .deleteFrom(ut)
      .where(ut.USER_ID.eq(userId))
      .and(ut.TRACKER.eq(tracker))
      .execute()
  }

  private fun Collection<UserTrackerRecord>.toDomain(): List<UserTracker> =
    groupBy { it.userId to it.tracker }
      .map { (key, records) ->
        val values = records.filter { it.configValue != null }.associate { r -> r.configKey to readValue(r.userId, r.tracker, r.configKey, r.configValue) }
        UserTracker(
          userId = key.first,
          tracker = key.second,
          settings = values.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
          unreadableKeys = values.filterValues { it == null }.keys,
        )
      }

  private fun storedValue(
    key: String,
    value: String,
  ): String = if (key in UserTracker.secretKeys) trackerSecretCipher.encrypt(value) else value

  private fun readValue(
    userId: String,
    tracker: String,
    key: String,
    value: String,
  ): String? {
    if (key !in UserTracker.secretKeys) return value
    return try {
      trackerSecretCipher.decrypt(value)
    } catch (e: TrackerSecretException) {
      if (reportedUnreadable.add("$userId:$tracker:$key")) {
        logger.warn { "Secret '$key' of the $tracker account of user $userId cannot be read, the account must be linked again: ${e.message}" }
      }
      null
    }
  }
}
