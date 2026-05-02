package org.gotson.komga.interfaces.api.rest.dto

import org.gotson.komga.domain.model.Library

enum class BookSortFieldDto {
  NUMBER,
  DATE_ADDED,
  DATE_UPDATED,
  RELEASE_DATE,
  FILE_SIZE,
  FILE_NAME,
  PAGE_COUNT,
}

fun Library.BookSortField.toDto() =
  when (this) {
    Library.BookSortField.NUMBER -> BookSortFieldDto.NUMBER
    Library.BookSortField.DATE_ADDED -> BookSortFieldDto.DATE_ADDED
    Library.BookSortField.DATE_UPDATED -> BookSortFieldDto.DATE_UPDATED
    Library.BookSortField.RELEASE_DATE -> BookSortFieldDto.RELEASE_DATE
    Library.BookSortField.FILE_SIZE -> BookSortFieldDto.FILE_SIZE
    Library.BookSortField.FILE_NAME -> BookSortFieldDto.FILE_NAME
    Library.BookSortField.PAGE_COUNT -> BookSortFieldDto.PAGE_COUNT
  }

fun BookSortFieldDto.toDomain() =
  when (this) {
    BookSortFieldDto.NUMBER -> Library.BookSortField.NUMBER
    BookSortFieldDto.DATE_ADDED -> Library.BookSortField.DATE_ADDED
    BookSortFieldDto.DATE_UPDATED -> Library.BookSortField.DATE_UPDATED
    BookSortFieldDto.RELEASE_DATE -> Library.BookSortField.RELEASE_DATE
    BookSortFieldDto.FILE_SIZE -> Library.BookSortField.FILE_SIZE
    BookSortFieldDto.FILE_NAME -> Library.BookSortField.FILE_NAME
    BookSortFieldDto.PAGE_COUNT -> Library.BookSortField.PAGE_COUNT
  }
