-- PAGE_HASH <-> MEDIA_PAGE joins on FILE_HASH (known duplicate pages, page lookup by hash) had no index,
-- so SQLite built a temporary index over the whole MEDIA_PAGE table on every call (~4 s at 780k rows).
CREATE INDEX IF NOT EXISTS idx_media_page_file_hash ON MEDIA_PAGE (FILE_HASH);
