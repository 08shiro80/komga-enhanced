package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.FollowConfig

interface FollowConfigRepository {
  fun findByIdOrNull(id: String): FollowConfig?

  fun findDefault(): FollowConfig?

  fun save(config: FollowConfig): FollowConfig

  fun delete(id: String)
}
