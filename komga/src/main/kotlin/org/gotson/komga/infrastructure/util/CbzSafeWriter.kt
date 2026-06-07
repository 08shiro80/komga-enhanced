package org.gotson.komga.infrastructure.util

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

object CbzSafeWriter {
  private const val MIN_FREE_RATIO = 2L
  private const val RAM_BUILD_THRESHOLD = 100L * 1024 * 1024

  fun safelyReplace(
    target: Path,
    verifyContent: ((Path) -> Unit)? = null,
    write: (OutputStream) -> Unit,
  ) {
    val parent = target.parent ?: throw IOException("CbzSafeWriter: target $target has no parent")
    val originalSize = runCatching { Files.size(target) }.getOrDefault(0L)
    val useRam = originalSize in 1..RAM_BUILD_THRESHOLD || originalSize == 0L
    val tmp = parent.resolve(".${target.fileName}.tmp.${UUID.randomUUID()}")
    val originalExists = Files.exists(target)

    val writtenBytes =
      try {
        if (useRam) {
          val baos = ByteArrayOutputStream(estimateInitialSize(originalSize))
          write(baos)
          val bytes = baos.toByteArray()
          if (bytes.isEmpty()) throw IOException("CbzSafeWriter: RAM-build produced empty output")
          verifyBytes(bytes, target.fileName.toString())
          ensureDiskSpace(parent, bytes.size.toLong())
          Files.write(tmp, bytes)
          bytes.size.toLong()
        } else {
          ensureDiskSpace(parent, originalSize)
          Files.newOutputStream(tmp).use { write(it) }
          val size = Files.size(tmp)
          if (size == 0L) throw IOException("CbzSafeWriter: disk-build produced empty file")
          size
        }
      } catch (e: Exception) {
        Files.deleteIfExists(tmp)
        logger.warn(e) { "CbzSafeWriter: write failed for ${target.fileName} (original untouched)" }
        throw e
      }

    try {
      verifyFile(tmp)
    } catch (e: Exception) {
      Files.deleteIfExists(tmp)
      logger.warn(e) { "CbzSafeWriter: tmp verify failed for ${target.fileName} (original untouched)" }
      throw e
    }

    if (verifyContent != null) {
      try {
        verifyContent(tmp)
      } catch (e: Exception) {
        Files.deleteIfExists(tmp)
        logger.warn(e) { "CbzSafeWriter: content verify failed for ${target.fileName} (original untouched)" }
        throw e
      }
    }

    val bak =
      if (originalExists) {
        val bakPath = parent.resolve("${target.fileName}.bak.${UUID.randomUUID()}")
        try {
          try {
            Files.move(target, bakPath, StandardCopyOption.ATOMIC_MOVE)
          } catch (_: AtomicMoveNotSupportedException) {
            Files.move(target, bakPath, StandardCopyOption.REPLACE_EXISTING)
          }
          bakPath
        } catch (e: Exception) {
          Files.deleteIfExists(tmp)
          logger.warn(e) { "CbzSafeWriter: could not move original to .bak for ${target.fileName} (original untouched)" }
          throw e
        }
      } else {
        null
      }

    try {
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
      }
      verifyFile(target)
      bak?.let { Files.deleteIfExists(it) }
      logger.debug { "CbzSafeWriter: wrote ${target.fileName} ($writtenBytes bytes, ${if (useRam) "RAM" else "disk"}-build)" }
    } catch (e: Exception) {
      Files.deleteIfExists(tmp)
      if (bak != null && Files.exists(bak)) {
        runCatching {
          try {
            Files.move(bak, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          } catch (_: AtomicMoveNotSupportedException) {
            Files.move(bak, target, StandardCopyOption.REPLACE_EXISTING)
          }
        }.onFailure {
          logger.error(it) { "CbzSafeWriter: ROLLBACK FAILED for ${target.fileName} — original lost; .bak at $bak" }
          throw IOException("Move-rotate failed AND rollback failed for ${target.fileName}", e)
        }
        logger.warn(e) { "CbzSafeWriter: rolled back ${target.fileName} from .bak after move-rotate failure" }
      } else {
        logger.warn(e) { "CbzSafeWriter: move-rotate failed for ${target.fileName} (no backup to roll back)" }
      }
      throw e
    }
  }

  private fun ensureDiskSpace(
    parent: Path,
    expectedBytes: Long,
  ) {
    val free = runCatching { Files.getFileStore(parent).usableSpace }.getOrDefault(Long.MAX_VALUE)
    val needed = expectedBytes * MIN_FREE_RATIO
    if (free < needed) {
      throw IOException("CbzSafeWriter: insufficient disk space at $parent (need $needed, have $free)")
    }
  }

  private fun estimateInitialSize(originalSize: Long): Int = originalSize.coerceAtLeast(1024L * 1024).coerceAtMost(64L * 1024 * 1024).toInt()

  private fun verifyBytes(
    bytes: ByteArray,
    label: String,
  ) {
    try {
      ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
        var count = 0
        while (true) {
          val entry = zin.nextEntry ?: break
          val buf = ByteArray(8192)
          while (zin.read(buf) >= 0) Unit
          zin.closeEntry()
          count++
        }
        if (count == 0) throw ZipException("RAM-built archive has zero entries")
      }
    } catch (e: Exception) {
      throw IOException("CbzSafeWriter: RAM-built archive failed verification for $label: ${e.message}", e)
    }
  }

  private fun verifyFile(path: Path) {
    try {
      ZipFile(path.toFile()).use { zip ->
        var count = 0
        zip.entries().asSequence().forEach { entry ->
          zip.getInputStream(entry).use { stream ->
            val buf = ByteArray(8192)
            while (stream.read(buf) >= 0) Unit
          }
          count++
        }
        if (count == 0) throw ZipException("file has zero entries after write")
      }
    } catch (e: Exception) {
      throw IOException("CbzSafeWriter: post-write file verification failed for ${path.fileName}: ${e.message}", e)
    }
  }
}
