//! SQLite-dialect statements shared by the self-hosted server (rusqlite) and the
//! Cloudflare Worker (D1). Keeping them in one place guarantees identical semantics.

pub const SCHEMA: &str = "CREATE TABLE IF NOT EXISTS files (
    path       TEXT PRIMARY KEY NOT NULL,
    version    INTEGER NOT NULL,
    hash       TEXT NOT NULL,
    size       INTEGER NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0,
    seq        INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS files_seq ON files(seq);";

pub const COLUMNS: &str = "path, version, hash, size, deleted, seq, updated_at";

/// Changes after `?1`, ordered by sequence, limited to `?2` rows.
pub const CHANGES: &str =
    "SELECT path, version, hash, size, deleted, seq, updated_at FROM files WHERE seq > ?1 ORDER BY seq LIMIT ?2";

pub const READ_META: &str =
    "SELECT path, version, hash, size, deleted, seq, updated_at FROM files WHERE path = ?1";

/// Optimistic-concurrency write in a single atomic statement.
///
/// Params: ?1 path, ?2 hash, ?3 size, ?4 deleted (0/1), ?5 updated_at, ?6 base_version.
///
/// * new path: inserted only when `base_version = 0`
/// * existing path: updated only when its version equals `base_version`
///
/// Returns the new row on success and no row on conflict. `seq` is the next
/// global sequence number, computed inside the same statement.
pub const COMMIT: &str = "INSERT INTO files (path, version, hash, size, deleted, seq, updated_at)
SELECT ?1, 1, ?2, ?3, ?4, COALESCE((SELECT MAX(seq) FROM files), 0) + 1, ?5
WHERE ?6 = 0 OR EXISTS (SELECT 1 FROM files WHERE path = ?1)
ON CONFLICT(path) DO UPDATE SET
    version = files.version + 1,
    hash = excluded.hash,
    size = excluded.size,
    deleted = excluded.deleted,
    seq = excluded.seq,
    updated_at = excluded.updated_at
WHERE files.version = ?6
RETURNING path, version, hash, size, deleted, seq, updated_at";

#[cfg(test)]
mod tests {
    /// The Cloudflare D1 migration must create exactly the schema the code expects.
    #[test]
    fn d1_migration_matches_schema() {
        let migration = include_str!("../../worker/migrations/0001_init.sql");
        let body: String = migration
            .lines()
            .filter(|l| !l.starts_with("--"))
            .collect::<Vec<_>>()
            .join("\n");
        assert_eq!(body.trim(), super::SCHEMA.trim());
    }
}
