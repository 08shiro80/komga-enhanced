package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.domain.persistence.BlacklistedChapterRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.interfaces.api.rest.dto.BlacklistedChapterDto
import org.gotson.komga.interfaces.api.rest.dto.BlacklistedSeriesDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/v1/blacklist", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class BlacklistController(
  private val blacklistedChapterRepository: BlacklistedChapterRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
) {
  @Operation(summary = "List blacklisted chapters across all series, grouped by series")
  @GetMapping
  fun getAllBlacklisted(): List<BlacklistedSeriesDto> {
    val bySeriesId = blacklistedChapterRepository.findAll().groupBy { it.seriesId }
    val titles = seriesMetadataRepository.findTitlesByIds(bySeriesId.keys)
    return bySeriesId
      .map { (seriesId, chapters) ->
        BlacklistedSeriesDto(
          seriesId = seriesId,
          seriesTitle = titles[seriesId] ?: seriesId,
          chapters =
            chapters
              .sortedBy { it.createdDate }
              .map { BlacklistedChapterDto.from(it) },
        )
      }.sortedBy { it.seriesTitle.lowercase() }
  }

  @Operation(summary = "Remove a blacklisted chapter by its id")
  @DeleteMapping("{blacklistId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun removeBlacklisted(
    @PathVariable blacklistId: String,
  ) {
    blacklistedChapterRepository.deleteById(blacklistId)
  }
}
