-- Fix foreign key in DOWNLOAD_CHAPTER_HISTORY table
-- The original migration referenced DOWNLOAD instead of DOWNLOAD_QUEUE

-- Drop the table and recreate with correct foreign key
DROP TABLE IF EXISTS DOWNLOAD_CHAPTER_HISTORY;

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

-- Recreate indexes
CREATE INDEX idx_download_chapter_history_chapter_url ON DOWNLOAD_CHAPTER_HISTORY (CHAPTER_URL);
CREATE INDEX idx_download_chapter_history_download_id ON DOWNLOAD_CHAPTER_HISTORY (DOWNLOAD_ID);
CREATE INDEX idx_download_chapter_history_downloaded_at ON DOWNLOAD_CHAPTER_HISTORY (DOWNLOADED_AT DESC);
