-- Must match dnote_core::sql::SCHEMA (enforced by a test in dnote-core).
CREATE TABLE IF NOT EXISTS files (
    path       TEXT PRIMARY KEY NOT NULL,
    version    INTEGER NOT NULL,
    hash       TEXT NOT NULL,
    size       INTEGER NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0,
    seq        INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS files_seq ON files(seq);
