package org.gotson.komga.interfaces.api.rest

import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.RandomAccessFile
import java.nio.file.Path

@RestController
@RequestMapping(value = ["api/v1/logs"])
@PreAuthorize("hasRole('ADMIN')")
class LogController(
  private val komgaProperties: KomgaProperties,
) {
  private fun logFile(): Path =
    Path
      .of(komgaProperties.configDir ?: "${System.getProperty("user.home")}/.komga")
      .resolve("logs/komga.log")

  @GetMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
  fun getLogs(
    @RequestParam(defaultValue = "500") lines: Int,
  ): String {
    val file = logFile().toFile()
    if (!file.exists()) return "Log file not found: ${file.absolutePath}"

    return tailLines(file, lines.coerceIn(1, 10000))
  }

  @GetMapping("/download")
  fun downloadLogs(): ResponseEntity<FileSystemResource> {
    val file = logFile().toFile()
    if (!file.exists()) {
      return ResponseEntity.notFound().build()
    }

    return ResponseEntity
      .ok()
      .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"komga.log\"")
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .body(FileSystemResource(file))
  }

  private fun tailLines(
    file: java.io.File,
    count: Int,
  ): String {
    if (file.length() == 0L) return ""

    val result = mutableListOf<String>()
    RandomAccessFile(file, "r").use { raf ->
      var pos = raf.length() - 1
      var lineCount = 0

      while (pos >= 0 && lineCount < count) {
        raf.seek(pos)
        val ch = raf.read()
        if (ch == '\n'.code && pos < raf.length() - 1) {
          lineCount++
        }
        pos--
      }

      val startPos = if (pos < 0) 0 else pos + 2
      raf.seek(startPos)

      var line = raf.readLine()
      while (line != null) {
        result.add(String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
        line = raf.readLine()
      }
    }
    return result.joinToString("\n")
  }
}
