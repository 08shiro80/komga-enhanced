-- Plugin system tables

-- Plugin registry table
create table PLUGIN
(
    ID                   varchar  not null primary key,
    NAME                 varchar  not null,
    VERSION              varchar  not null,
    AUTHOR               varchar,
    DESCRIPTION          text,
    ENABLED              boolean  not null default true,
    PLUGIN_TYPE          varchar  not null, -- METADATA, DOWNLOAD, TASK, etc.
    ENTRY_POINT          varchar  not null, -- class name or script path
    SOURCE_URL           varchar, -- where plugin was downloaded from
    INSTALLED_DATE       datetime not null default CURRENT_TIMESTAMP,
    LAST_UPDATED         datetime not null default CURRENT_TIMESTAMP,
    CONFIG_SCHEMA        text, -- JSON schema for plugin configuration
    DEPENDENCIES         text, -- JSON array of required dependencies
    CREATED_DATE         datetime not null default CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE   datetime not null default CURRENT_TIMESTAMP
);

create index idx__plugin__enabled on PLUGIN (ENABLED);
create index idx__plugin__type on PLUGIN (PLUGIN_TYPE);

-- Plugin permissions table
create table PLUGIN_PERMISSION
(
    ID                   varchar  not null primary key,
    PLUGIN_ID            varchar  not null,
    PERMISSION_TYPE      varchar  not null, -- API_ACCESS, FILESYSTEM, DATABASE, NETWORK, SYSTEM
    PERMISSION_DETAIL    varchar, -- specific API endpoint, file path, etc.
    GRANTED              boolean  not null default false,
    GRANTED_BY           varchar, -- user ID who granted permission
    GRANTED_DATE         datetime,
    CREATED_DATE         datetime not null default CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE   datetime not null default CURRENT_TIMESTAMP,

    foreign key (PLUGIN_ID) references PLUGIN (ID) on delete cascade
);

create index idx__plugin_permission__plugin_id on PLUGIN_PERMISSION (PLUGIN_ID);
create index idx__plugin_permission__type on PLUGIN_PERMISSION (PERMISSION_TYPE);

-- Plugin configuration table
create table PLUGIN_CONFIG
(
    ID                   varchar  not null primary key,
    PLUGIN_ID            varchar  not null,
    CONFIG_KEY           varchar  not null,
    CONFIG_VALUE         text,
    CREATED_DATE         datetime not null default CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE   datetime not null default CURRENT_TIMESTAMP,

    foreign key (PLUGIN_ID) references PLUGIN (ID) on delete cascade,
    unique (PLUGIN_ID, CONFIG_KEY)
);

create index idx__plugin_config__plugin_id on PLUGIN_CONFIG (PLUGIN_ID);

-- Plugin execution log table
create table PLUGIN_LOG
(
    ID                   varchar  not null primary key,
    PLUGIN_ID            varchar  not null,
    LOG_LEVEL            varchar  not null, -- DEBUG, INFO, WARN, ERROR
    MESSAGE              text     not null,
    EXCEPTION_TRACE      text,
    CREATED_DATE         datetime not null default CURRENT_TIMESTAMP,

    foreign key (PLUGIN_ID) references PLUGIN (ID) on delete cascade
);

create index idx__plugin_log__plugin_id on PLUGIN_LOG (PLUGIN_ID);
create index idx__plugin_log__created on PLUGIN_LOG (CREATED_DATE desc);
create index idx__plugin_log__level on PLUGIN_LOG (LOG_LEVEL);

-- Download queue table (for manga-py and other download plugins)
create table DOWNLOAD_QUEUE
(
    ID                   varchar  not null primary key,
    SOURCE_URL           varchar  not null,
    SOURCE_TYPE          varchar  not null, -- MANGA_SITE, DIRECT_URL, etc.
    TITLE                varchar,
    AUTHOR               varchar,
    STATUS               varchar  not null default 'PENDING', -- PENDING, DOWNLOADING, COMPLETED, FAILED, PAUSED, CANCELLED
    PROGRESS_PERCENT     integer  not null default 0,
    CURRENT_CHAPTER      integer,
    TOTAL_CHAPTERS       integer,
    LIBRARY_ID           varchar, -- target library
    DESTINATION_PATH     varchar,
    ERROR_MESSAGE        text,
    PLUGIN_ID            varchar, -- which plugin is handling this
    METADATA_JSON        text, -- additional metadata as JSON
    CREATED_BY           varchar  not null, -- user ID
    CREATED_DATE         datetime not null default CURRENT_TIMESTAMP,
    STARTED_DATE         datetime,
    COMPLETED_DATE       datetime,
    LAST_MODIFIED_DATE   datetime not null default CURRENT_TIMESTAMP,
    PRIORITY             integer  not null default 5, -- 1=highest, 10=lowest
    RETRY_COUNT          integer  not null default 0,
    MAX_RETRIES          integer  not null default 3,

    foreign key (PLUGIN_ID) references PLUGIN (ID) on delete set null,
    foreign key (LIBRARY_ID) references LIBRARY (ID) on delete set null
);

create index idx__download_queue__status on DOWNLOAD_QUEUE (STATUS);
create index idx__download_queue__created_by on DOWNLOAD_QUEUE (CREATED_BY);
create index idx__download_queue__library_id on DOWNLOAD_QUEUE (LIBRARY_ID);
create index idx__download_queue__priority on DOWNLOAD_QUEUE (PRIORITY, CREATED_DATE);

-- Download chapter tracking (for individual chapters/volumes)
create table DOWNLOAD_ITEM
(
    ID                   varchar  not null primary key,
    QUEUE_ID             varchar  not null,
    CHAPTER_NUMBER       varchar  not null,
    CHAPTER_TITLE        varchar,
    CHAPTER_URL          varchar,
    STATUS               varchar  not null default 'PENDING',
    FILE_SIZE_BYTES      integer,
    DOWNLOADED_BYTES     integer  not null default 0,
    FILE_PATH            varchar,
    ERROR_MESSAGE        text,
    CREATED_DATE         datetime not null default CURRENT_TIMESTAMP,
    STARTED_DATE         datetime,
    COMPLETED_DATE       datetime,
    LAST_MODIFIED_DATE   datetime not null default CURRENT_TIMESTAMP,

    foreign key (QUEUE_ID) references DOWNLOAD_QUEUE (ID) on delete cascade
);

create index idx__download_item__queue_id on DOWNLOAD_ITEM (QUEUE_ID);
create index idx__download_item__status on DOWNLOAD_ITEM (STATUS);

-- Update checker table (tracks available updates for series)
create table UPDATE_CHECK
(
    ID                   varchar  not null primary key,
    SERIES_ID            varchar  not null,
    SOURCE_URL           varchar  not null, -- original source URL
    LAST_CHECK_DATE      datetime not null default CURRENT_TIMESTAMP,
    LATEST_CHAPTER       varchar, -- latest chapter available
    NEW_CHAPTERS_COUNT   integer  not null default 0,
    CHECK_ENABLED        boolean  not null default true,
    CHECK_FREQUENCY      integer  not null default 24, -- hours
    PLUGIN_ID            varchar, -- which plugin is handling this
    METADATA_JSON        text, -- additional metadata
    CREATED_DATE         datetime not null default CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE   datetime not null default CURRENT_TIMESTAMP,

    foreign key (SERIES_ID) references SERIES (ID) on delete cascade,
    foreign key (PLUGIN_ID) references PLUGIN (ID) on delete set null,
    unique (SERIES_ID, SOURCE_URL)
);

create index idx__update_check__series_id on UPDATE_CHECK (SERIES_ID);
create index idx__update_check__last_check on UPDATE_CHECK (LAST_CHECK_DATE);
create index idx__update_check__enabled on UPDATE_CHECK (CHECK_ENABLED);

-- User blacklist table (for filtering unwanted content)
create table USER_BLACKLIST
(
    ID                   varchar  not null primary key,
    USER_ID              varchar  not null,
    BLACKLIST_TYPE       varchar  not null, -- TAG, GENRE, PUBLISHER, AUTHOR, AGE_RATING
    BLACKLIST_VALUE      varchar  not null, -- the actual tag/genre/etc to block
    CREATED_DATE         datetime not null default CURRENT_TIMESTAMP,

    foreign key (USER_ID) references USER (ID) on delete cascade,
    unique (USER_ID, BLACKLIST_TYPE, BLACKLIST_VALUE)
);

create index idx__user_blacklist__user_id on USER_BLACKLIST (USER_ID);
create index idx__user_blacklist__type on USER_BLACKLIST (BLACKLIST_TYPE);
