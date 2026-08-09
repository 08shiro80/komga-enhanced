package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.SinglePageBookIgnoreRepository
import org.gotson.komga.interfaces.api.rest.dto.SinglePageBookDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/v1/media-management/single-page-books", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class SinglePageBooksController(
  private val mediaRepository: MediaRepository,
  private val singlePageBookIgnoreRepository: SinglePageBookIgnoreRepository,
) {
  @GetMapping
  @Operation(summary = "List books that contain only a single page")
  fun getSinglePageBooks(
    @RequestParam(name = "includeIgnored", required = false, defaultValue = "false") includeIgnored: Boolean,
    @RequestParam(name = "search", required = false) search: String?,
  ): List<SinglePageBookDto> {
    val ignoredIds = singlePageBookIgnoreRepository.findAllIgnoredIds()
    val searchTerm =
      search
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase()
    return mediaRepository
      .findAllSinglePageBookCandidates()
      .asSequence()
      .filter { includeIgnored || it.bookId !in ignoredIds }
      .map { c ->
        SinglePageBookDto(
          bookId = c.bookId,
          bookName = c.bookName,
          seriesId = c.seriesId,
          seriesTitle = c.seriesTitle?.takeIf { it.isNotBlank() } ?: c.seriesName,
          fileSize = c.fileSize,
          mediaType = c.mediaType,
          ignored = c.bookId in ignoredIds,
        )
      }.filter { dto ->
        searchTerm == null ||
          dto.bookName.lowercase().contains(searchTerm) ||
          dto.seriesTitle.lowercase().contains(searchTerm)
      }.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, SinglePageBookDto::seriesTitle)
          .thenBy(String.CASE_INSENSITIVE_ORDER, SinglePageBookDto::bookName),
      ).toList()
  }

  @PostMapping("{bookId}/ignore")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Mark a single-page book as ignored")
  fun ignore(
    @PathVariable bookId: String,
  ) {
    singlePageBookIgnoreRepository.ignore(bookId)
  }

  @DeleteMapping("{bookId}/ignore")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove a single-page book from the ignore list")
  fun unignore(
    @PathVariable bookId: String,
  ) {
    singlePageBookIgnoreRepository.unignore(bookId)
  }
}
