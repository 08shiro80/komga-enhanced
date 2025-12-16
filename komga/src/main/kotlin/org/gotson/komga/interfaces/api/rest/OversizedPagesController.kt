package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration
import org.gotson.komga.infrastructure.openapi.PageableAsQueryParam
import org.gotson.komga.interfaces.api.rest.dto.OversizedPageDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/v1/media-management/oversized-pages", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class OversizedPagesController(
  private val mediaRepository: MediaRepository,
  private val bookRepository: BookRepository,
  private val seriesRepository: SeriesRepository,
) {
  @Operation(
    summary = "List oversized pages",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @GetMapping
  @PageableAsQueryParam
  fun getOversizedPages(
    @RequestParam(name = "minWidth", defaultValue = "4000") minWidth: Int,
    @RequestParam(name = "minHeight", defaultValue = "4000") minHeight: Int,
    @Parameter(hidden = true) page: Pageable,
  ): Page<OversizedPageDto> {
    // Get all books and check their pages for oversized dimensions
    val allBooks = bookRepository.findAll()

    val oversizedPages = mutableListOf<OversizedPageDto>()

    for (book in allBooks) {
      val media = mediaRepository.findByIdOrNull(book.id) ?: continue
      val series = seriesRepository.findByIdOrNull(book.seriesId)

      media.pages.forEachIndexed { index, bookPage ->
        val dimension = bookPage.dimension
        if (dimension != null && (dimension.width >= minWidth || dimension.height >= minHeight)) {
          oversizedPages.add(
            OversizedPageDto(
              bookId = book.id,
              bookName = book.name,
              seriesId = book.seriesId,
              seriesTitle = series?.name ?: "",
              pageNumber = index + 1,
              width = dimension.width,
              height = dimension.height,
              fileSize = bookPage.fileSize ?: 0L,
              mediaType = bookPage.mediaType,
            ),
          )
        }
      }
    }

    // Sort by dimensions (largest first)
    oversizedPages.sortByDescending { it.width * it.height }

    // Paginate results manually
    val start = (page.pageNumber * page.pageSize).coerceAtMost(oversizedPages.size)
    val end = ((page.pageNumber + 1) * page.pageSize).coerceAtMost(oversizedPages.size)
    val pageContent =
      if (start < oversizedPages.size) {
        oversizedPages.subList(start, end)
      } else {
        emptyList()
      }

    return PageImpl(pageContent, page, oversizedPages.size.toLong())
  }
}
