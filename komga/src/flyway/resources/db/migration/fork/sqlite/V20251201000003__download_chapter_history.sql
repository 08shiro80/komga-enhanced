-- Add download_chapter_history table for tracking downloaded chapters
-- This replaces gallery-dl's external archive.txt with integrated database tracking

CREATE TABLE DOWNLOAD_CHAPTER_HISTORY
(
    DOWNLOAD_ID     varchar     NOT NULL,
    CHAPTER_URL     varchar(1024) NOT NULL,
    CHAPTER_NUMBER  varchar(50),
    DOWNLOADED_AT   datetime    NOT NULL,
    CBZ_FILENAME    varchar(255),
    PRIMARY KEY (DOWNLOAD_ID, CHAPTER_URL),
    FOREIGN KEY (DOWNLOAD_ID) REFERENCES DOWNLOAD_QUEUE(ID) ON DELETE CASCADE
);

-- Index for fast lookup by chapter URL (checking if already downloaded)
CREATE INDEX idx_download_chapter_history_chapter_url ON DOWNLOAD_CHAPTER_HISTORY (CHAPTER_URL);

-- Index for fast lookup by download ID (showing all chapters for a download)
CREATE INDEX idx_download_chapter_history_download_id ON DOWNLOAD_CHAPTER_HISTORY (DOWNLOAD_ID);

-- Index for chronological queries (finding recently downloaded chapters)
CREATE INDEX idx_download_chapter_history_downloaded_at ON DOWNLOAD_CHAPTER_HISTORY (DOWNLOADED_AT DESC);
