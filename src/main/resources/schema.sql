-- Quiet Reader schema. Every statement is idempotent; Db runs the whole file
-- on startup and records the version in meta.schema_version.

CREATE TABLE IF NOT EXISTS feed (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  url             TEXT    NOT NULL UNIQUE,
  title           TEXT,
  site_url        TEXT,
  etag            TEXT,
  last_modified   TEXT,
  last_fetched_at INTEGER,
  fetch_error     TEXT,
  fail_count      INTEGER NOT NULL DEFAULT 0,
  added_at        INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS item (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  feed_id       INTEGER NOT NULL REFERENCES feed(id) ON DELETE CASCADE,
  guid          TEXT    NOT NULL,
  url           TEXT,
  title         TEXT,
  author        TEXT,
  published_at  INTEGER,
  content_html  TEXT,
  excerpt       TEXT,
  fetched_at    INTEGER NOT NULL,
  read_at       INTEGER,
  saved_at      INTEGER,
  UNIQUE (feed_id, guid)
);

CREATE INDEX IF NOT EXISTS item_unread   ON item (read_at, published_at);
CREATE INDEX IF NOT EXISTS item_saved    ON item (saved_at);
CREATE INDEX IF NOT EXISTS item_by_feed  ON item (feed_id);

-- One finite sitting: a snapshot of posts taken when it began.
CREATE TABLE IF NOT EXISTS session (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  started_at  INTEGER NOT NULL,
  finished_at INTEGER,
  position    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS session_item (
  session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  item_id    INTEGER NOT NULL REFERENCES item(id) ON DELETE CASCADE,
  ordinal    INTEGER NOT NULL,
  PRIMARY KEY (session_id, ordinal)
);

CREATE INDEX IF NOT EXISTS session_item_by_item ON session_item (item_id);

CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
