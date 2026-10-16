-- Externalized thumbnail BLOB storage.
-- Only the raw image bytes live in this separate database; every metadata row
-- (id, book/series linkage, selected flag, type, dimensions, FK) stays in the main
-- database. Keys mirror the main-database primary keys so the bytes can be looked up
-- by the same id/hash. No foreign keys: the referenced tables live in another database.

CREATE TABLE IF NOT EXISTS THUMBNAIL_BOOK_DATA (
    ID   varchar NOT NULL PRIMARY KEY,
    DATA blob    NOT NULL
);

CREATE TABLE IF NOT EXISTS PAGE_HASH_THUMBNAIL_DATA (
    HASH varchar NOT NULL PRIMARY KEY,
    DATA blob    NOT NULL
);
