package org.gotson.komga.infrastructure.security

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class TrackerSecretInitializer(
  private val userTrackerRepository: UserTrackerRepository,
  private val trackerSecretCipher: TrackerSecretCipher,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun encryptPlaintextSecrets() {
    try {
      if (!trackerSecretCipher.keyFileExists()) {
        val unreadable = userTrackerRepository.countEncryptedSecrets()
        if (unreadable > 0) {
          logger.warn {
            "Tracker secret key file ${trackerSecretCipher.keyFileLocation()} is missing: $unreadable stored tracker secrets cannot be decrypted, the affected users must link their trackers again"
          }
        }
      }
      val count = userTrackerRepository.encryptPlaintextSecrets()
      if (count > 0) logger.info { "Encrypted $count plaintext tracker secrets" }
    } catch (e: TrackerSecretException) {
      logger.warn { "Plaintext tracker secrets could not be encrypted: ${e.message}" }
    } catch (e: DataAccessException) {
      logger.warn(e) { "Plaintext tracker secrets could not be encrypted because of a database error" }
    }
  }
}
