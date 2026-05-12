-- Sync state table for two-way scrobbler sync
-- Tracks the last known reading progress and status for each (series, tracker) pair.
-- Used by MangaSyncPullerPlugin to detect changes on the tracker side
-- and by the push scrobbler to record successful syncs.

create table SYNC_STATE
(
    ID                    varchar  not null primary key,
    BOOK_ID               varchar,
    SERIES_ID             varchar  not null,
    TRACKER               varchar  not null, -- anilist, mal, kitsu, mangadex
    PROGRESS              integer  not null default 0,
    STATUS                varchar,           -- current, completed, dropped, paused, planning, rereading
    SCORE                 integer,           -- 0-10 (normalized across trackers)
    LAST_SYNC_TIMESTAMP   datetime,          -- when we last pushed progress to the tracker
    LAST_UPDATE_TIMESTAMP datetime,          -- when the tracker last reported a change (for pull detection)
    CREATED_DATE          datetime not null default CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE    datetime not null default CURRENT_TIMESTAMP,

    foreign key (BOOK_ID) references BOOK (ID) on delete set null,
    foreign key (SERIES_ID) references SERIES (ID) on delete cascade
);

create unique index idx__sync_state__series_tracker on SYNC_STATE (SERIES_ID, TRACKER);
create index idx__sync_state__tracker on SYNC_STATE (TRACKER);
create index idx__sync_state__last_update on SYNC_STATE (LAST_UPDATE_TIMESTAMP);
