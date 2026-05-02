alter table library
    add column DEFAULT_BOOKS_SORT_FIELD varchar NOT NULL DEFAULT 'NUMBER';

alter table library
    add column DEFAULT_BOOKS_SORT_ORDER varchar NOT NULL DEFAULT 'ASC';
