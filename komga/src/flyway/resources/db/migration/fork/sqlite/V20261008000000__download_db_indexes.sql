-- Index tuning derived from EXPLAIN QUERY PLAN analysis against the live database.

-- CHAPTER_URL: by-date queries (count/find/delete over DOWNLOADED_AT) did a full table scan.
CREATE INDEX IF NOT EXISTS idx_chapter_url_downloaded_at ON CHAPTER_URL (DOWNLOADED_AT);

-- Drop CHAPTER_URL indexes the planner never chooses:
--   idx_chapter_url_url duplicates the UNIQUE(URL) auto-index
--   idx_chapter_url_series is covered by the leading column of the (SERIES_ID, ...) composites
DROP INDEX IF EXISTS idx_chapter_url_url;
DROP INDEX IF EXISTS idx_chapter_url_series;

-- DOWNLOAD_QUEUE: scheduling is WHERE STATUS = ? ORDER BY PRIORITY, CREATED_DATE.
-- A single composite satisfies the equality prefix plus the sort, removing the temp B-tree;
-- it supersedes the standalone status and (PRIORITY, CREATED_DATE) indexes.
CREATE INDEX IF NOT EXISTS idx__download_queue__status_priority ON DOWNLOAD_QUEUE (STATUS, PRIORITY, CREATED_DATE);
DROP INDEX IF EXISTS idx__download_queue__status;
DROP INDEX IF EXISTS idx__download_queue__priority;

-- DOWNLOAD_QUEUE: existsBySourceUrlAndStatusIn ran as a full scan on every enqueue.
CREATE INDEX IF NOT EXISTS idx__download_queue__source_url ON DOWNLOAD_QUEUE (SOURCE_URL);
