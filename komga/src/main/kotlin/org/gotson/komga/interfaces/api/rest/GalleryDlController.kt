package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.infrastructure.download.GalleryDlUpdateExecutor
import org.gotson.komga.infrastructure.download.GalleryDlUpdateStatus
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/v1/gallery-dl", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class GalleryDlController(
  private val galleryDlUpdateExecutor: GalleryDlUpdateExecutor,
) {
  @Operation(
    summary = "Update gallery-dl from a URL",
    description = "Installs the gallery-dl distribution at the given URL (e.g. official codeberg/PyPI) and re-applies the fork's Komga integration on top. Runs in the background; poll update/status.",
  )
  @PostMapping("update")
  fun startUpdate(
    @RequestBody body: Map<String, String?>,
  ): GalleryDlUpdateStatus {
    val url =
      body["url"]?.takeIf { it.isNotBlank() }
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required")
    galleryDlUpdateExecutor.startUpdate(url)
    return galleryDlUpdateExecutor.status()
  }

  @Operation(summary = "Status of the running or last gallery-dl update")
  @GetMapping("update/status")
  fun updateStatus(): GalleryDlUpdateStatus = galleryDlUpdateExecutor.status()
}
