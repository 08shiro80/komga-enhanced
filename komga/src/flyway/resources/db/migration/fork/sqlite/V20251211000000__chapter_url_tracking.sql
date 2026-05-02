-- Chapter URL tracking for duplicate download prevention
-- This table stores all downloaded chapter URLs per series to enable
-- Komga to check if a chapter has already been downloaded before starting a new download.

CREATE TABLE IF NOT EXISTS CHAPTER_URL
(
    ID                varchar     NOT NULL PRIMARY KEY,
    SERIES_ID         varchar     NOT NULL,
    URL               varchar(2048) NOT NULL UNIQUE,
    CHAPTER           real        NOT NULL DEFAULT 0,
    VOLUME            integer,
    TITLE             varchar(512),
    LANG              varchar(10) NOT NULL DEFAULT 'en',
    DOWNLOADED_AT     datetime    NOT NULL,
    SOURCE            varchar(50) NOT NULL DEFAULT 'gallery-dl',
    CHAPTER_ID        varchar(100),
    SCANLATION_GROUP  varchar(255),
    CREATED_DATE      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE datetime   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (SERIES_ID) REFERENCES SERIES(ID) ON DELETE CASCADE
);

-- Index for quick URL lookups (most common query)
CREATE INDEX idx_chapter_url_url ON CHAPTER_URL (URL);

-- Index for series-based queries
CREATE INDEX idx_chapter_url_series ON CHAPTER_URL (SERIES_ID);

-- Composite index for series + language queries
CREATE INDEX idx_chapter_url_series_lang ON CHAPTER_URL (SERIES_ID, LANG);

-- Index for source filtering
CREATE INDEX idx_chapter_url_source ON CHAPTER_URL (SOURCE);

-- Index for chapter range queries
CREATE INDEX idx_chapter_url_series_chapter ON CHAPTER_URL (SERIES_ID, CHAPTER);
