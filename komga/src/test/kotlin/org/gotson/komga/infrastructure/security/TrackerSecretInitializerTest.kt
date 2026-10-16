package org.gotson.komga.infrastructure.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatCode
import org.gotson.komga.domain.persistence.UserTrackerRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessResourceFailureException
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TrackerSecretInitializerTest {
  private val userTrackerRepository = mockk<UserTrackerRepository>()
  private val trackerSecretCipher = mockk<TrackerSecretCipher>()
  private val initializer = TrackerSecretInitializer(userTrackerRepository, trackerSecretCipher)

  @BeforeEach
  fun setup() {
    every { trackerSecretCipher.keyFileExists() } returns true
    every { trackerSecretCipher.keyFileLocation() } returns Path.of("/config/tracker-secrets.key")
  }

  @Test
  fun `given plaintext secrets when the application is ready then they are encrypted`() {
    every { userTrackerRepository.encryptPlaintextSecrets() } returns 3

    initializer.encryptPlaintextSecrets()

    verify(exactly = 1) { userTrackerRepository.encryptPlaintextSecrets() }
    verify(exactly = 0) { userTrackerRepository.countEncryptedSecrets() }
  }

  @Test
  fun `given a missing key file and encrypted secrets when the application is ready then the loss is reported and startup continues`() {
    every { trackerSecretCipher.keyFileExists() } returns false
    every { userTrackerRepository.countEncryptedSecrets() } returns 2
    every { userTrackerRepository.encryptPlaintextSecrets() } returns 0

    assertThatCode { initializer.encryptPlaintextSecrets() }.doesNotThrowAnyException()

    verify(exactly = 1) { userTrackerRepository.countEncryptedSecrets() }
  }

  @Test
  fun `given an unavailable key when the application is ready then startup continues`() {
    every { userTrackerRepository.encryptPlaintextSecrets() } throws TrackerSecretException("key file is not accessible")

    assertThatCode { initializer.encryptPlaintextSecrets() }.doesNotThrowAnyException()
  }

  @Test
  fun `given a database error when the application is ready then startup continues`() {
    every { userTrackerRepository.encryptPlaintextSecrets() } throws DataAccessResourceFailureException("database is locked")

    assertThatCode { initializer.encryptPlaintextSecrets() }.doesNotThrowAnyException()
  }
}
