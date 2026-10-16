create table USER_TRACKER
(
    USER_ID            varchar  not null,
    TRACKER            varchar  not null,
    CONFIG_KEY         varchar  not null,
    CONFIG_VALUE       varchar,
    CREATED_DATE       datetime not null default CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE datetime not null default CURRENT_TIMESTAMP,
    primary key (USER_ID, TRACKER, CONFIG_KEY),
    foreign key (USER_ID) references USER (ID) on delete cascade
);

create index idx__user_tracker__tracker on USER_TRACKER (TRACKER);

with owner as (select coalesce(
                              (select u.ID
                               from USER u
                               where u.ID = (select CONFIG_VALUE
                                             from PLUGIN_CONFIG
                                             where PLUGIN_ID = 'manga-scrobbler'
                                               and CONFIG_KEY = 'sync_user_id')),
                              (select u.ID
                               from USER u
                                        join USER_ROLE r on r.USER_ID = u.ID
                               where r.ROLE = 'ADMIN'
                               order by u.CREATED_DATE
                               limit 1)
                      ) as USER_ID),
     keymap(OLD_KEY, TRACKER, NEW_KEY) as (values ('anilist_token', 'anilist', 'token'),
                                                  ('mal_access_token', 'mal', 'access_token'),
                                                  ('mal_refresh_token', 'mal', 'refresh_token'),
                                                  ('mal_client_id', 'mal', 'client_id'),
                                                  ('mal_client_secret', 'mal', 'client_secret'),
                                                  ('kitsu_token', 'kitsu', 'access_token'),
                                                  ('kitsu_refresh_token', 'kitsu', 'refresh_token'),
                                                  ('kitsu_client_id', 'kitsu', 'client_id'),
                                                  ('kitsu_client_secret', 'kitsu', 'client_secret'),
                                                  ('mangadex_username', 'mangadex', 'username'),
                                                  ('mangadex_password', 'mangadex', 'password'),
                                                  ('mangadex_client_id', 'mangadex', 'client_id'),
                                                  ('mangadex_client_secret', 'mangadex', 'client_secret'),
                                                  ('mangadex_max_content_rating', 'mangadex', 'max_content_rating'))
insert
into USER_TRACKER (USER_ID, TRACKER, CONFIG_KEY, CONFIG_VALUE)
select o.USER_ID, k.TRACKER, k.NEW_KEY, pc.CONFIG_VALUE
from PLUGIN_CONFIG pc
         join keymap k on k.OLD_KEY = pc.CONFIG_KEY
         cross join owner o
where pc.PLUGIN_ID = 'manga-scrobbler'
  and o.USER_ID is not null
  and trim(coalesce(pc.CONFIG_VALUE, '')) <> '';

delete
from USER_TRACKER
where TRACKER in ('mal', 'kitsu')
  and not exists (select 1
                  from USER_TRACKER t
                  where t.USER_ID = USER_TRACKER.USER_ID
                    and t.TRACKER = USER_TRACKER.TRACKER
                    and t.CONFIG_KEY = 'access_token');

delete
from USER_TRACKER
where TRACKER = 'mangadex'
  and (select count(*)
       from USER_TRACKER t
       where t.USER_ID = USER_TRACKER.USER_ID
         and t.TRACKER = 'mangadex'
         and t.CONFIG_KEY in ('username', 'password')) < 2;

with sel as (select coalesce((select CONFIG_VALUE
                              from PLUGIN_CONFIG
                              where PLUGIN_ID = 'manga-scrobbler'
                                and CONFIG_KEY = 'tracker'), 'both') as T)
insert
into USER_TRACKER (USER_ID, TRACKER, CONFIG_KEY, CONFIG_VALUE)
select distinct ut.USER_ID,
                ut.TRACKER,
                'enabled',
                case
                    when ut.TRACKER = 'anilist' and sel.T in ('anilist', 'both', 'both_kitsu', 'all') then 'true'
                    when ut.TRACKER = 'mal' and sel.T in ('mal', 'both', 'both_kitsu', 'all') then 'true'
                    when ut.TRACKER = 'kitsu' and sel.T in ('kitsu', 'both_kitsu', 'all') then 'true'
                    when ut.TRACKER = 'mangadex' and sel.T in ('mangadex', 'all') then 'true'
                    else 'false'
                    end
from USER_TRACKER ut
         cross join sel
where ut.TRACKER in ('anilist', 'mal', 'kitsu', 'mangadex');

with owner as (select coalesce(
                              (select u.ID
                               from USER u
                               where u.ID = (select CONFIG_VALUE
                                             from PLUGIN_CONFIG
                                             where PLUGIN_ID = 'comic-scrobbler'
                                               and CONFIG_KEY = 'sync_user_id')),
                              (select u.ID
                               from USER u
                                        join USER_ROLE r on r.USER_ID = u.ID
                               where r.ROLE = 'ADMIN'
                               order by u.CREATED_DATE
                               limit 1)
                      ) as USER_ID),
     keymap(OLD_KEY, TRACKER, NEW_KEY) as (values ('metron_username', 'metron', 'username'),
                                                  ('metron_password', 'metron', 'password'))
insert
into USER_TRACKER (USER_ID, TRACKER, CONFIG_KEY, CONFIG_VALUE)
select o.USER_ID, k.TRACKER, k.NEW_KEY, pc.CONFIG_VALUE
from PLUGIN_CONFIG pc
         join keymap k on k.OLD_KEY = pc.CONFIG_KEY
         cross join owner o
where pc.PLUGIN_ID = 'comic-scrobbler'
  and o.USER_ID is not null
  and trim(coalesce(pc.CONFIG_VALUE, '')) <> '';

insert into USER_TRACKER (USER_ID, TRACKER, CONFIG_KEY, CONFIG_VALUE)
select distinct USER_ID, 'metron', 'enabled', 'true'
from USER_TRACKER
where TRACKER = 'metron';

insert into PLUGIN_CONFIG (ID, PLUGIN_ID, CONFIG_KEY, CONFIG_VALUE)
select lower(hex(randomblob(16))), 'auto-metadata', 'mal_client_id', pc.CONFIG_VALUE
from PLUGIN_CONFIG pc
where pc.PLUGIN_ID = 'manga-scrobbler'
  and pc.CONFIG_KEY = 'mal_client_id'
  and trim(coalesce(pc.CONFIG_VALUE, '')) <> ''
  and exists (select 1 from PLUGIN where ID = 'auto-metadata')
  and not exists (select 1
                  from PLUGIN_CONFIG x
                  where x.PLUGIN_ID = 'auto-metadata'
                    and x.CONFIG_KEY = 'mal_client_id');

create table SYNC_STATE_NEW
(
    ID                    varchar  not null primary key,
    USER_ID               varchar  not null,
    BOOK_ID               varchar,
    SERIES_ID             varchar  not null,
    TRACKER               varchar  not null,
    PROGRESS              integer  not null default 0,
    STATUS                varchar,
    SCORE                 integer,
    LAST_SYNC_TIMESTAMP   datetime,
    LAST_UPDATE_TIMESTAMP datetime,
    CREATED_DATE          datetime not null default CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE    datetime not null default CURRENT_TIMESTAMP,

    foreign key (USER_ID) references USER (ID) on delete cascade,
    foreign key (BOOK_ID) references BOOK (ID) on delete set null,
    foreign key (SERIES_ID) references SERIES (ID) on delete cascade
);

insert into SYNC_STATE_NEW (ID, USER_ID, BOOK_ID, SERIES_ID, TRACKER, PROGRESS, STATUS, SCORE, LAST_SYNC_TIMESTAMP,
                            LAST_UPDATE_TIMESTAMP, CREATED_DATE, LAST_MODIFIED_DATE)
select ID,
       OWNER,
       BOOK_ID,
       SERIES_ID,
       TRACKER,
       PROGRESS,
       STATUS,
       SCORE,
       LAST_SYNC_TIMESTAMP,
       LAST_UPDATE_TIMESTAMP,
       CREATED_DATE,
       LAST_MODIFIED_DATE
from (select s.*,
             coalesce(
                     (select u.ID
                      from USER u
                      where u.ID = (select CONFIG_VALUE
                                    from PLUGIN_CONFIG
                                    where PLUGIN_ID = case when s.TRACKER = 'metron' then 'comic-scrobbler' else 'manga-scrobbler' end
                                      and CONFIG_KEY = 'sync_user_id')),
                     (select u.ID
                      from USER u
                               join USER_ROLE r on r.USER_ID = u.ID
                      where r.ROLE = 'ADMIN'
                      order by u.CREATED_DATE
                      limit 1)
             ) as OWNER
      from SYNC_STATE s)
where OWNER is not null;

drop table SYNC_STATE;
alter table SYNC_STATE_NEW rename to SYNC_STATE;

create unique index idx__sync_state__user_series_tracker on SYNC_STATE (USER_ID, SERIES_ID, TRACKER);
create index idx__sync_state__tracker on SYNC_STATE (TRACKER);
create index idx__sync_state__last_update on SYNC_STATE (LAST_UPDATE_TIMESTAMP);

delete
from PLUGIN_CONFIG
where PLUGIN_ID = 'manga-scrobbler'
  and CONFIG_KEY in ('tracker', 'anilist_token', 'mal_access_token', 'mal_refresh_token', 'mal_client_id',
                     'mal_client_secret', 'kitsu_token', 'kitsu_refresh_token', 'kitsu_client_id',
                     'kitsu_client_secret', 'mangadex_username', 'mangadex_password', 'mangadex_client_id',
                     'mangadex_client_secret', 'mangadex_max_content_rating', 'sync_user_id');

delete
from PLUGIN_CONFIG
where PLUGIN_ID = 'comic-scrobbler'
  and CONFIG_KEY in ('metron_username', 'metron_password', 'sync_user_id');
