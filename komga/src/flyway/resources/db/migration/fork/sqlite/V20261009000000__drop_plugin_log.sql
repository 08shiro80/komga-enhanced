-- Plugin logging moved from the database to a dedicated plugins.log file (logback).
-- The PLUGIN_LOG table is no longer written to or read; drop it so it stops
-- inflating the database with logs the user cannot prune.
DROP TABLE IF EXISTS PLUGIN_LOG;
