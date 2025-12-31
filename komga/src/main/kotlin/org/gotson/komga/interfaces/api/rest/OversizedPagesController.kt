package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.PageSplitter
import org.gotson.komga.domain.service.SplitResult
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration
import org.gotson.komga.infrastructure.openapi.PageableAsQueryParam
import org.gotson.komga.interfaces.api.rest.dto.OversizedPageDto
import org.gotson.komga.interfaces.api.rest.dto.SplitRequestDto
import org.gotson.komga.interfaces.api.rest.dto.SplitResultDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/v1/media-management/oversized-pages", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class OversizedPagesController(
  private val mediaRepository: MediaRepository,
  private val bookRepository: BookRepository,
  private val seriesRepository: SeriesRepository,
  private val pageSplitter: PageSplitter,
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

  @Operation(
    summary = "Split tall pages in a book",
    description = "Splits pages taller than the specified height into multiple pages",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @PostMapping("split/{bookId}")
  fun splitTallPages(
    @PathVariable bookId: String,
    @RequestParam(name = "maxHeight", defaultValue = "2000") maxHeight: Int,
  ): SplitResultDto {
    val book =
      bookRepository.findByIdOrNull(bookId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found: $bookId")

    val result = pageSplitter.splitTallPages(book, maxHeight)
    return result.toDto()
  }

  @Operation(
    summary = "Split tall pages in multiple books",
    description = "Splits pages taller than the specified height into multiple pages for all books with oversized pages",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @PostMapping("split-all")
  fun splitAllTallPages(
    @RequestBody request: SplitRequestDto,
  ): List<SplitResultDto> {
    val results = mutableListOf<SplitResultDto>()

    // Find all books with pages exceeding the height threshold
    val allBooks = bookRepository.findAll()

    for (book in allBooks) {
      val media = mediaRepository.findByIdOrNull(book.id) ?: continue

      val hasOversizedPages =
        media.pages.any { page ->
          val dimension = page.dimension
          dimension != null && dimension.height > request.maxHeight
        }

      if (hasOversizedPages) {
        try {
          val result = pageSplitter.splitTallPages(book, request.maxHeight)
          results.add(result.toDto())
        } catch (e: Exception) {
          results.add(
            SplitResultDto(
              bookId = book.id,
              bookName = book.name,
              pagesAnalyzed = 0,
              pagesSplit = 0,
              newPagesCreated = 0,
              success = false,
              message = "Error: ${e.message}",
            ),
          )
        }
      }
    }

    return results
  }
}

fun SplitResult.toDto() =
  SplitResultDto(
    bookId = bookId,
    bookName = bookName,
    pagesAnalyzed = pagesAnalyzed,
    pagesSplit = pagesSplit,
    newPagesCreated = newPagesCreated,
    success = success,
    message = message,
  )
