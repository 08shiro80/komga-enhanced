package org.gotson.komga.infrastructure.jooq.main

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.gotson.komga.domain.model.FollowConfig
import org.gotson.komga.domain.persistence.FollowConfigRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

private const val FOLLOW_CONFIG_KEY = "follow_config"

@Component
class FollowConfigDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  private val objectMapper: ObjectMapper,
) : SplitDslDaoBase(dslRW, dslRO), FollowConfigRepository {
  private val s = Tables.SERVER_SETTINGS

  override fun findByIdOrNull(id: String): FollowConfig? = findDefault()

  override fun findDefault(): FollowConfig? =
    dslRO
      .select(s.VALUE)
      .from(s)
      .where(s.KEY.eq(FOLLOW_CONFIG_KEY))
      .fetchOne(s.VALUE)
      ?.let { json ->
        try {
          objectMapper.readValue<FollowConfig>(json)
        } catch (e: Exception) {
          null
        }
      }

  override fun save(config: FollowConfig): FollowConfig {
    val updatedConfig = config.copy(lastModifiedDate = LocalDateTime.now())
    val json = objectMapper.writeValueAsString(updatedConfig)

    dslRW
      .insertInto(s)
      .values(FOLLOW_CONFIG_KEY, json)
      .onDuplicateKeyUpdate()
      .set(s.VALUE, json)
      .execute()

    return updatedConfig
  }

  override fun delete(id: String) {
    dslRW.deleteFrom(s).where(s.KEY.eq(FOLLOW_CONFIG_KEY)).execute()
  }
}
