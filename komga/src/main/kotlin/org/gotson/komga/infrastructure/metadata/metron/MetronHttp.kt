package org.gotson.komga.infrastructure.metadata.metron

import org.gotson.komga.domain.model.UserTracker
import java.util.Base64

object MetronHttp {
  fun authorization(account: UserTracker): String? {
    account[UserTracker.KEY_TOKEN]?.let { return "Bearer $it" }
    val username = account[UserTracker.KEY_USERNAME] ?: return null
    val password = account[UserTracker.KEY_PASSWORD] ?: return null
    return "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray())}"
  }
}
