package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.gotson.komga.domain.model.IgnoredOversizedPage
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.IgnoredOversizedPageRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.BookPageEditor
import org.gotson.komga.domain.service.PageSplitter
import org.gotson.komga.domain.service.SplitMode
import org.gotson.komga.domain.service.SplitResult
import org.gotson.komga.infrastructure.openapi.OpenApiConfiguration
import org.gotson.komga.infrastructure.openapi.PageableAsQueryParam
import org.gotson.komga.interfaces.api.rest.dto.DeleteOversizedPageRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DeleteOversizedPagesRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DeletePagesResultDto
import org.gotson.komga.interfaces.api.rest.dto.IgnoreOversizedPageRequestDto
import org.gotson.komga.interfaces.api.rest.dto.IgnoreOversizedPagesRequestDto
import org.gotson.komga.interfaces.api.rest.dto.OversizedPageDto
import org.gotson.komga.interfaces.api.rest.dto.SplitRequestDto
import org.gotson.komga.interfaces.api.rest.dto.SplitResultDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
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
  private val ignoredOversizedPageRepository: IgnoredOversizedPageRepository,
  private val bookPageEditor: BookPageEditor,
) {
  @Operation(
    summary = "List oversized pages",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @GetMapping
  @PageableAsQueryParam
  fun getOversizedPages(
    @RequestParam(name = "minWidth", required = false) minWidth: Int?,
    @RequestParam(name = "minHeight", required = false) minHeight: Int?,
    @RequestParam(name = "minRatio", required = false) minRatio: Double?,
    @RequestParam(name = "mode", required = false, defaultValue = "tall") mode: String,
    @RequestParam(name = "includeIgnored", required = false, defaultValue = "false") includeIgnored: Boolean,
    @RequestParam(name = "search", required = false) search: String?,
    @Parameter(hidden = true) page: Pageable,
  ): Page<OversizedPageDto> {
    val useRatio = minRatio != null
    val effectiveMinWidth = minWidth ?: 4000
    val effectiveMinHeight = minHeight ?: 4000
    val wideMode = mode.equals("wide", ignoreCase = true)
    val normalizedMode = if (wideMode) "wide" else "tall"
    val searchTerm = search?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

    val ignoredKeys =
      if (includeIgnored) {
        emptySet()
      } else {
        ignoredOversizedPageRepository.findKeysByMode(normalizedMode)
      }

    val allBooks = bookRepository.findAll()
    val oversizedPages = mutableListOf<OversizedPageDto>()

    for (book in allBooks) {
      val media = mediaRepository.findByIdOrNull(book.id) ?: continue
      val series = seriesRepository.findByIdOrNull(book.seriesId)

      if (searchTerm != null) {
        val bookMatch = book.name.lowercase().contains(searchTerm)
        val seriesMatch = series?.name?.lowercase()?.contains(searchTerm) == true
        if (!bookMatch && !seriesMatch) continue
      }

      media.pages.forEachIndexed { index, bookPage ->
        val dimension = bookPage.dimension
        if (dimension != null && dimension.width > 0 && dimension.height > 0) {
          // Reject pathological strip images (webtoon dividers like 720x1, 1200x15, 1200x25)
          if (dimension.width < PageSplitter.MIN_VALID_DIMENSION || dimension.height < PageSplitter.MIN_VALID_DIMENSION) return@forEachIndexed
          val ratio =
            if (wideMode) {
              dimension.width.toDouble() / dimension.height.toDouble()
            } else {
              dimension.height.toDouble() / dimension.width.toDouble()
            }
          // In WIDE mode, anything beyond MAX_WIDE_RATIO is a divider/banner strip, not a double page
          if (wideMode && ratio > PageSplitter.MAX_WIDE_RATIO) return@forEachIndexed
          val matches =
            if (useRatio) {
              ratio >= minRatio!!
            } else if (wideMode) {
              dimension.width >= effectiveMinWidth && dimension.width > dimension.height
            } else {
              dimension.width >= effectiveMinWidth || dimension.height >= effectiveMinHeight
            }

          if (matches) {
            val pageNumber = index + 1
            if (!includeIgnored && Pair(book.id, pageNumber) in ignoredKeys) {
              return@forEachIndexed
            }
            oversizedPages.add(
              OversizedPageDto(
                bookId = book.id,
                bookName = book.name,
                seriesId = book.seriesId,
                seriesTitle = series?.name ?: "",
                pageNumber = pageNumber,
                width = dimension.width,
                height = dimension.height,
                ratio = Math.round(ratio * 100.0) / 100.0,
                fileSize = bookPage.fileSize ?: 0L,
                mediaType = bookPage.mediaType,
              ),
            )
          }
        }
      }
    }

    oversizedPages.sortByDescending { it.ratio }

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
    @RequestParam(name = "maxHeight", required = false) maxHeight: Int?,
    @RequestParam(name = "maxRatio", required = false) maxRatio: Double?,
    @RequestParam(name = "mode", required = false, defaultValue = "tall") mode: String,
    @RequestParam(name = "pageNumbers", required = false) pageNumbers: List<Int>?,
  ): SplitResultDto {
    val book =
      bookRepository.findByIdOrNull(bookId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found: $bookId")

    val splitMode = if (mode.equals("wide", ignoreCase = true)) SplitMode.WIDE else SplitMode.TALL
    val normalizedMode = if (splitMode == SplitMode.WIDE) "wide" else "tall"
    val effectiveMaxRatio =
      when {
        maxRatio != null -> maxRatio
        splitMode == SplitMode.WIDE -> 1.0
        maxHeight == null -> 1.5
        else -> null
      }
    val result =
      pageSplitter.splitTallPages(
        book,
        maxHeight = maxHeight,
        maxRatio = effectiveMaxRatio,
        mode = splitMode,
        pageNumbers = pageNumbers?.toSet()?.takeIf { it.isNotEmpty() },
      )
    if (result.success && result.pagesSplit > 0) {
      ignoredOversizedPageRepository.deleteByBookIdAndMode(bookId, normalizedMode)
    }
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

    val allBooks = bookRepository.findAll()

    val splitMode = if (request.mode?.equals("wide", ignoreCase = true) == true) SplitMode.WIDE else SplitMode.TALL
    val normalizedMode = if (splitMode == SplitMode.WIDE) "wide" else "tall"
    val effectiveMaxHeight = request.maxHeight
    val effectiveMaxRatio =
      when {
        request.maxRatio != null -> request.maxRatio
        splitMode == SplitMode.WIDE -> 1.0
        effectiveMaxHeight == null -> 1.5
        else -> null
      }

    for (book in allBooks) {
      val media = mediaRepository.findByIdOrNull(book.id) ?: continue

      val hasOversizedPages =
        media.pages.any { page ->
          val dimension = page.dimension
          if (dimension == null || dimension.width == 0 || dimension.height == 0) return@any false
          if (dimension.width < PageSplitter.MIN_VALID_DIMENSION || dimension.height < PageSplitter.MIN_VALID_DIMENSION) return@any false
          when {
            splitMode == SplitMode.WIDE -> {
              val aspectRatio = dimension.width.toDouble() / dimension.height.toDouble()
              aspectRatio <= PageSplitter.MAX_WIDE_RATIO && aspectRatio > (effectiveMaxRatio ?: 1.0)
            }
            effectiveMaxRatio != null ->
              dimension.height.toDouble() / dimension.width.toDouble() > effectiveMaxRatio
            else ->
              dimension.height > (effectiveMaxHeight ?: 2000)
          }
        }

      if (hasOversizedPages) {
        try {
          val result =
            pageSplitter.splitTallPages(
              book,
              maxHeight = effectiveMaxHeight,
              maxRatio = effectiveMaxRatio,
              mode = splitMode,
            )
          if (result.success && result.pagesSplit > 0) {
            ignoredOversizedPageRepository.deleteByBookIdAndMode(book.id, normalizedMode)
          }
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

  @Operation(
    summary = "Ignore an oversized page",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @PostMapping("ignore")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun ignoreOversizedPage(
    @RequestBody request: IgnoreOversizedPageRequestDto,
  ) {
    val normalizedMode = if (request.mode.equals("wide", ignoreCase = true)) "wide" else "tall"
    if (ignoredOversizedPageRepository.existsByKey(request.bookId, request.pageNumber, normalizedMode)) return
    ignoredOversizedPageRepository.insert(
      IgnoredOversizedPage(
        bookId = request.bookId,
        pageNumber = request.pageNumber,
        mode = normalizedMode,
      ),
    )
  }

  @Operation(
    summary = "Ignore multiple oversized pages",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @PostMapping("ignore-batch")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun ignoreOversizedPagesBatch(
    @RequestBody request: IgnoreOversizedPagesRequestDto,
  ) {
    val normalizedMode = if (request.mode.equals("wide", ignoreCase = true)) "wide" else "tall"
    for (key in request.pages) {
      if (ignoredOversizedPageRepository.existsByKey(key.bookId, key.pageNumber, normalizedMode)) continue
      ignoredOversizedPageRepository.insert(
        IgnoredOversizedPage(
          bookId = key.bookId,
          pageNumber = key.pageNumber,
          mode = normalizedMode,
        ),
      )
    }
  }

  @Operation(
    summary = "Remove an ignored oversized page",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @DeleteMapping("ignore")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun unignoreOversizedPage(
    @RequestBody request: IgnoreOversizedPageRequestDto,
  ) {
    val normalizedMode = if (request.mode.equals("wide", ignoreCase = true)) "wide" else "tall"
    ignoredOversizedPageRepository.delete(request.bookId, request.pageNumber, normalizedMode)
  }

  @Operation(
    summary = "Delete an oversized page from a book",
    description = "Removes a single page from the book archive. Use this for unwanted frames (e.g. divider strips) that should not be split.",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @PostMapping("delete-page")
  fun deleteOversizedPage(
    @RequestBody request: DeleteOversizedPageRequestDto,
  ): DeletePagesResultDto {
    val book =
      bookRepository.findByIdOrNull(request.bookId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found: ${request.bookId}")
    val normalizedMode = if (request.mode.equals("wide", ignoreCase = true)) "wide" else "tall"
    return try {
      bookPageEditor.removePagesByNumber(book, setOf(request.pageNumber))
      ignoredOversizedPageRepository.deleteByBookIdAndMode(book.id, normalizedMode)
      DeletePagesResultDto(bookId = book.id, deleted = 1, success = true, message = "Deleted page ${request.pageNumber}")
    } catch (e: Exception) {
      DeletePagesResultDto(bookId = book.id, deleted = 0, success = false, message = "Failed: ${e.message}")
    }
  }

  @Operation(
    summary = "Delete multiple oversized pages grouped by book",
    description = "Removes the given pages from each affected book. Ignored list for the affected books+mode is cleared afterwards since page numbers shift.",
    tags = [OpenApiConfiguration.TagNames.DUPLICATE_PAGES],
  )
  @PostMapping("delete-pages-batch")
  fun deleteOversizedPagesBatch(
    @RequestBody request: DeleteOversizedPagesRequestDto,
  ): List<DeletePagesResultDto> {
    val normalizedMode = if (request.mode.equals("wide", ignoreCase = true)) "wide" else "tall"
    val grouped = request.pages.groupBy({ it.bookId }, { it.pageNumber })
    val results = mutableListOf<DeletePagesResultDto>()
    for ((bookId, pageNumbers) in grouped) {
      val book = bookRepository.findByIdOrNull(bookId)
      if (book == null) {
        results.add(DeletePagesResultDto(bookId = bookId, deleted = 0, success = false, message = "Book not found"))
        continue
      }
      try {
        bookPageEditor.removePagesByNumber(book, pageNumbers.toSet())
        ignoredOversizedPageRepository.deleteByBookIdAndMode(book.id, normalizedMode)
        results.add(
          DeletePagesResultDto(
            bookId = book.id,
            deleted = pageNumbers.size,
            success = true,
            message = "Deleted ${pageNumbers.size} pages",
          ),
        )
      } catch (e: Exception) {
        results.add(DeletePagesResultDto(bookId = book.id, deleted = 0, success = false, message = "Failed: ${e.message}"))
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
