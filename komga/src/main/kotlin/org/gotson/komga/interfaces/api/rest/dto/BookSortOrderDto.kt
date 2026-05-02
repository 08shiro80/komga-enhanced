package org.gotson.komga.interfaces.api.rest.dto

import org.gotson.komga.domain.model.Library

enum class BookSortOrderDto {
  ASC,
  DESC,
}

fun Library.BookSortOrder.toDto() =
  when (this) {
    Library.BookSortOrder.ASC -> BookSortOrderDto.ASC
    Library.BookSortOrder.DESC -> BookSortOrderDto.DESC
  }

fun BookSortOrderDto.toDomain() =
  when (this) {
    BookSortOrderDto.ASC -> Library.BookSortOrder.ASC
    BookSortOrderDto.DESC -> Library.BookSortOrder.DESC
  }
