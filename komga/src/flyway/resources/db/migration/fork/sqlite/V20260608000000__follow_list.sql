CREATE TABLE IF NOT EXISTS FOLLOW (
    ID              varchar       NOT NULL PRIMARY KEY,
    LIBRARY_ID      varchar       NOT NULL,
    URL             varchar(2048) NOT NULL,
    TITLE           varchar(512),
    SERIES_ID       varchar,
    ENABLED         boolean       NOT NULL DEFAULT 1,
    ADDED_AT        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    LAST_CHECKED_AT datetime,
    UNIQUE (LIBRARY_ID, URL)
);
CREATE INDEX IF NOT EXISTS idx_follow_library ON FOLLOW (LIBRARY_ID);
CREATE INDEX IF NOT EXISTS idx_follow_series ON FOLLOW (SERIES_ID);
