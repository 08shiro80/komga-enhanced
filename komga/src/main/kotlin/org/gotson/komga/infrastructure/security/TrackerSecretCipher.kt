package org.gotson.komga.infrastructure.security

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

private const val PREFIX = "enc:v1:"
private const val KEY_FILE_NAME = "tracker-secrets.key"
private const val KEY_SIZE_BYTES = 32
private const val IV_SIZE_BYTES = 12
private const val TAG_SIZE_BITS = 128
private const val TRANSFORMATION = "AES/GCM/NoPadding"

class TrackerSecretException(
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)

@Component
class TrackerSecretCipher(
  komgaProperties: KomgaProperties,
) {
  private val keyFile: Path = Paths.get(komgaProperties.configDir ?: "${System.getProperty("user.home")}/.komga", KEY_FILE_NAME)

  private val random = SecureRandom()

  @Volatile
  private var key: SecretKey? = null

  fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

  fun keyFileExists(): Boolean = Files.exists(keyFile)

  fun keyFileLocation(): Path = keyFile

  fun requireKey() {
    key(create = true)
  }

  fun encrypt(plain: String): String =
    try {
      val iv = ByteArray(IV_SIZE_BYTES).also { random.nextBytes(it) }
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key(create = true), GCMParameterSpec(TAG_SIZE_BITS, iv))
      val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
      PREFIX + Base64.getEncoder().encodeToString(iv + encrypted)
    } catch (e: GeneralSecurityException) {
      throw TrackerSecretException("Tracker secret could not be encrypted", e)
    }

  fun decrypt(stored: String): String {
    if (!isEncrypted(stored)) return stored
    val bytes =
      try {
        Base64.getDecoder().decode(stored.substring(PREFIX.length))
      } catch (e: IllegalArgumentException) {
        throw TrackerSecretException("Encrypted tracker secret is not valid base64", e)
      }
    if (bytes.size <= IV_SIZE_BYTES) throw TrackerSecretException("Encrypted tracker secret is truncated")
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_SIZE_BITS, bytes, 0, IV_SIZE_BYTES))
      String(cipher.doFinal(bytes, IV_SIZE_BYTES, bytes.size - IV_SIZE_BYTES), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
      throw TrackerSecretException("Tracker secret could not be decrypted with the key in $keyFile", e)
    }
  }

  private fun key(create: Boolean): SecretKey = key ?: synchronized(this) { key ?: loadOrCreateKey(create).also { key = it } }

  private fun loadOrCreateKey(create: Boolean): SecretKey =
    try {
      when {
        Files.exists(keyFile) -> readKey()
        create -> createKey()
        else -> throw TrackerSecretException("Tracker secret key file $keyFile is missing")
      }
    } catch (e: IOException) {
      throw TrackerSecretException("Tracker secret key file $keyFile is not accessible", e)
    }

  private fun readKey(): SecretKey {
    val bytes =
      try {
        Base64.getDecoder().decode(Files.readString(keyFile).trim())
      } catch (e: IllegalArgumentException) {
        throw TrackerSecretException("Tracker secret key file $keyFile is not valid base64", e)
      }
    if (bytes.size != KEY_SIZE_BYTES) throw TrackerSecretException("Tracker secret key file $keyFile does not contain a 256-bit key")
    return SecretKeySpec(bytes, "AES")
  }

  private fun createKey(): SecretKey {
    val bytes = ByteArray(KEY_SIZE_BYTES).also { random.nextBytes(it) }
    val directory = keyFile.toAbsolutePath().parent
    Files.createDirectories(directory)
    val tempFile = directory.resolve("$KEY_FILE_NAME.tmp")
    Files.deleteIfExists(tempFile)
    if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) {
      Files.createFile(tempFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
      Files.createFile(tempFile)
    }
    Files.writeString(tempFile, Base64.getEncoder().encodeToString(bytes))
    Files.move(tempFile, keyFile)
    logger.info { "Created tracker secret key file $keyFile, keep it together with the database backups" }
    return SecretKeySpec(bytes, "AES")
  }
}
