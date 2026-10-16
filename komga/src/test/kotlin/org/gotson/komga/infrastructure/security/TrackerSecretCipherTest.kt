package org.gotson.komga.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class TrackerSecretCipherTest {
  private fun cipher(configDir: Path) = TrackerSecretCipher(KomgaProperties().apply { this.configDir = configDir.toString() })

  @Test
  fun `given a secret when encrypting and decrypting then the original value is returned`(
    @TempDir dir: Path,
  ) {
    val cipher = cipher(dir)

    val encrypted = cipher.encrypt("my-secret")

    assertThat(encrypted).startsWith("enc:v1:").doesNotContain("my-secret")
    assertThat(cipher.isEncrypted(encrypted)).isTrue()
    assertThat(cipher.decrypt(encrypted)).isEqualTo("my-secret")
  }

  @Test
  fun `given the same secret when encrypting twice then the stored values differ`(
    @TempDir dir: Path,
  ) {
    val cipher = cipher(dir)

    assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"))
  }

  @Test
  fun `given a value without prefix when decrypting then it is returned as plaintext`(
    @TempDir dir: Path,
  ) {
    val cipher = cipher(dir)

    assertThat(cipher.isEncrypted("legacy-token")).isFalse()
    assertThat(cipher.decrypt("legacy-token")).isEqualTo("legacy-token")
  }

  @Test
  fun `given no key file when encrypting then a key file only readable by the owner is created and reused`(
    @TempDir dir: Path,
  ) {
    val encrypted = cipher(dir).encrypt("persisted")
    val keyFile = dir.resolve("tracker-secrets.key")

    assertThat(keyFile).exists()
    if (keyFile.fileSystem.supportedFileAttributeViews().contains("posix")) {
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile))).isEqualTo("rw-------")
    }
    assertThat(cipher(dir).decrypt(encrypted)).isEqualTo("persisted")
  }

  @Test
  fun `given a value encrypted with another key when decrypting then a secret exception is thrown`(
    @TempDir dir1: Path,
    @TempDir dir2: Path,
  ) {
    val encrypted = cipher(dir1).encrypt("other-key")

    assertThatThrownBy { cipher(dir2).decrypt(encrypted) }.isInstanceOf(TrackerSecretException::class.java)
    assertThatThrownBy { cipher(dir2).decrypt("enc:v1:not-base64!") }.isInstanceOf(TrackerSecretException::class.java)
  }

  @Test
  fun `given a missing key file when decrypting then no new key is created`(
    @TempDir dir1: Path,
    @TempDir dir2: Path,
  ) {
    val encrypted = cipher(dir1).encrypt("lost")
    val other = cipher(dir2)

    assertThat(other.keyFileExists()).isFalse()
    assertThatThrownBy { other.decrypt(encrypted) }.isInstanceOf(TrackerSecretException::class.java).hasMessageContaining("missing")
    assertThat(other.keyFileExists()).isFalse()
  }

  @Test
  fun `given an invalid key file when encrypting then a secret exception is thrown and the file is kept`(
    @TempDir dir: Path,
  ) {
    val keyFile = dir.resolve("tracker-secrets.key")
    Files.writeString(keyFile, "broken")

    assertThatThrownBy { cipher(dir).encrypt("x") }.isInstanceOf(TrackerSecretException::class.java)
    assertThat(Files.readString(keyFile)).isEqualTo("broken")
  }
}
