delete
from USER_TRACKER
where TRACKER = 'kitsu'
  and CONFIG_KEY in ('access_token', 'refresh_token', 'client_id', 'client_secret');

delete
from USER_TRACKER
where TRACKER = 'kitsu'
  and not exists (select 1
                  from USER_TRACKER t
                  where t.USER_ID = USER_TRACKER.USER_ID
                    and t.TRACKER = 'kitsu'
                    and t.CONFIG_KEY <> 'enabled');
