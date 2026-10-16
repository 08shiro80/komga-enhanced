create table SERIES_TRACKER_ID
(
    SERIES_ID          varchar  not null,
    TRACKER            varchar  not null,
    TRACKER_ID         varchar  not null,
    STATUS             varchar  not null,
    SOURCE             varchar  not null,
    CREATED_DATE       datetime not null default CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE datetime not null default CURRENT_TIMESTAMP,
    primary key (SERIES_ID, TRACKER, TRACKER_ID),
    foreign key (SERIES_ID) references SERIES (ID) on delete cascade
);

create index idx__series_tracker_id__tracker_tracker_id on SERIES_TRACKER_ID (TRACKER, TRACKER_ID);

create table COMIC_SCROBBLE
(
    USER_ID            varchar  not null,
    SERIES_ID          varchar  not null,
    ISSUE_NUMBER       varchar  not null,
    METRON_ISSUE_ID    integer  not null,
    BOOK_ID            varchar,
    DATE_READ          datetime,
    CREATED_DATE       datetime not null default CURRENT_TIMESTAMP,
    primary key (USER_ID, SERIES_ID, ISSUE_NUMBER),
    foreign key (USER_ID) references USER (ID) on delete cascade,
    foreign key (SERIES_ID) references SERIES (ID) on delete cascade,
    foreign key (BOOK_ID) references BOOK (ID) on delete set null
);

insert into PLUGIN_CONFIG (ID, PLUGIN_ID, CONFIG_KEY, CONFIG_VALUE)
select lower(hex(randomblob(16))), pc.PLUGIN_ID, 'discover_ids', pc.CONFIG_VALUE
from PLUGIN_CONFIG pc
where pc.PLUGIN_ID in ('manga-scrobbler', 'comic-scrobbler')
  and pc.CONFIG_KEY = 'auto_detect_links'
  and trim(coalesce(pc.CONFIG_VALUE, '')) <> ''
  and not exists (select 1
                  from PLUGIN_CONFIG x
                  where x.PLUGIN_ID = pc.PLUGIN_ID
                    and x.CONFIG_KEY = 'discover_ids');

delete
from PLUGIN_CONFIG
where PLUGIN_ID in ('manga-scrobbler', 'comic-scrobbler')
  and CONFIG_KEY = 'auto_detect_links';
