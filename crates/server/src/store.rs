//! Self-hosted storage: metadata in SQLite, note bodies as plain files under
//! `<data>/notes/<path>`, so the vault stays browsable, greppable and easy to back up.

use dnote_core::api::{meta_from_row, Commit, CommitOutcome, Store, StoreError};
use dnote_core::protocol::FileMeta;
use dnote_core::sql;
use rusqlite::{params, Connection, OptionalExtension, Row};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

pub struct FsStore {
    db: Mutex<Connection>,
    notes: PathBuf,
    tmp: PathBuf,
    counter: AtomicU64,
}

fn row_to_meta(r: &Row) -> rusqlite::Result<FileMeta> {
    Ok(meta_from_row(
        r.get(0)?,
        r.get(1)?,
        r.get(2)?,
        r.get(3)?,
        r.get(4)?,
        r.get(5)?,
        r.get(6)?,
    ))
}

fn err<E: std::fmt::Display>(e: E) -> StoreError {
    e.to_string()
}

/// Relative paths of note files under `dir`, skipping hidden entries (like `.dnote-tmp`)
/// and anything whose path the API would reject.
fn collect_notes(dir: &Path, rel: &str, out: &mut Vec<String>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let path = if rel.is_empty() {
            name.clone()
        } else {
            format!("{rel}/{name}")
        };
        if name.starts_with('.') {
            // Hidden entries are skipped, except the synced workspace file.
            if rel.is_empty() && name == ".dnote" && entry.path().join("workspace.json").is_file() {
                out.push(dnote_core::paths::WORKSPACE_PATH.to_string());
            }
            continue;
        }
        match entry.file_type() {
            Ok(t) if t.is_dir() => collect_notes(&entry.path(), &path, out),
            Ok(t)
                if t.is_file()
                    && dnote_core::paths::is_synced(&name)
                    && dnote_core::paths::normalize(&path).as_deref() == Ok(path.as_str()) =>
            {
                out.push(path);
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn startup_indexes_files_changed_on_disk() {
        let dir = tempfile::tempdir().unwrap();
        let notes = dir.path().join("notes");
        std::fs::create_dir_all(notes.join("Projects")).unwrap();
        std::fs::write(notes.join("Projects/Plan.md"), "# Plan\n").unwrap();
        std::fs::write(notes.join("Gone.md"), "bye\n").unwrap();

        let store = FsStore::open(dir.path()).unwrap();
        let all = pollster::block_on(store.changes_since(0, 100)).unwrap();
        assert_eq!(all.len(), 2);
        drop(store);

        // Edit one file, delete another, add a third while the server is down.
        std::fs::write(notes.join("Projects/Plan.md"), "# Plan v2\n").unwrap();
        std::fs::remove_file(notes.join("Gone.md")).unwrap();
        std::fs::write(notes.join("New.md"), "new\n").unwrap();
        let store = FsStore::open(dir.path()).unwrap();
        let plan = pollster::block_on(store.meta("Projects/Plan.md"))
            .unwrap()
            .unwrap();
        assert_eq!(plan.version, 2);
        assert_eq!(plan.hash, dnote_core::content_hash("# Plan v2\n"));
        assert!(
            pollster::block_on(store.meta("Gone.md"))
                .unwrap()
                .unwrap()
                .deleted
        );
        assert!(
            !pollster::block_on(store.meta("New.md"))
                .unwrap()
                .unwrap()
                .deleted
        );

        // A clean restart changes nothing.
        let seq = pollster::block_on(store.changes_since(0, 100))
            .unwrap()
            .last()
            .unwrap()
            .seq;
        drop(store);
        let store = FsStore::open(dir.path()).unwrap();
        assert_eq!(
            pollster::block_on(store.changes_since(seq, 100))
                .unwrap()
                .len(),
            0
        );
    }
}

impl FsStore {
    pub fn open(data_dir: &Path) -> Result<Self, StoreError> {
        let notes = data_dir.join("notes");
        let tmp = notes.join(".dnote-tmp");
        std::fs::create_dir_all(&tmp).map_err(err)?;
        let db = Connection::open(data_dir.join("dnote.db")).map_err(err)?;
        db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;")
            .map_err(err)?;
        db.execute_batch(sql::SCHEMA).map_err(err)?;
        let store = FsStore {
            db: Mutex::new(db),
            notes,
            tmp,
            counter: AtomicU64::new(0),
        };
        store.reconcile_disk()?;
        Ok(store)
    }

    /// The `.md` files are the source of truth. At startup, record anything changed on disk
    /// behind the server's back (hand edits, restored backups, a lost database) as new
    /// versions, so clients pick it up through the normal change feed.
    fn reconcile_disk(&self) -> Result<(), StoreError> {
        let mut on_disk = Vec::new();
        collect_notes(&self.notes, "", &mut on_disk);
        let db = self.db.lock().unwrap();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);
        let (mut added, mut changed, mut removed) = (0, 0, 0);
        let mut seen = std::collections::HashSet::new();

        for path in on_disk {
            let Ok(content) = std::fs::read(self.notes.join(&path)) else {
                continue;
            };
            seen.insert(path.clone());
            let hash = dnote_core::content_hash(&content);
            let current = db
                .query_row(sql::READ_META, params![path], row_to_meta)
                .optional()
                .map_err(err)?;
            if current
                .as_ref()
                .is_some_and(|m| !m.deleted && m.hash == hash)
            {
                continue;
            }
            let base = current.as_ref().map(|m| m.version).unwrap_or(0);
            db.query_row(
                sql::COMMIT,
                params![path, hash, content.len() as i64, 0, now, base],
                row_to_meta,
            )
            .map_err(err)?;
            if current.is_some_and(|m| !m.deleted) {
                changed += 1
            } else {
                added += 1
            }
        }

        let mut stmt = db
            .prepare("SELECT path, version FROM files WHERE deleted = 0")
            .map_err(err)?;
        let live: Vec<(String, i64)> = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
            .map_err(err)?
            .collect::<Result<_, _>>()
            .map_err(err)?;
        drop(stmt);
        for (path, version) in live {
            if !seen.contains(&path) {
                db.query_row(
                    sql::COMMIT,
                    params![path, "", 0, 1, now, version],
                    row_to_meta,
                )
                .map_err(err)?;
                removed += 1;
            }
        }
        if added + changed + removed > 0 {
            tracing::info!(
                "indexed notes folder: {added} new, {changed} changed, {removed} removed"
            );
        }
        Ok(())
    }

    fn file_path(&self, path: &str) -> PathBuf {
        // `path` was validated by dnote_core::paths::normalize (no `..`, no absolute paths).
        self.notes.join(path)
    }

    fn prune_empty_dirs(&self, mut dir: &Path) {
        while dir != self.notes && dir.starts_with(&self.notes) {
            if std::fs::remove_dir(dir).is_err() {
                break; // not empty
            }
            match dir.parent() {
                Some(p) => dir = p,
                None => break,
            }
        }
    }
}

impl Store for FsStore {
    async fn changes_since(&self, since: i64, limit: i64) -> Result<Vec<FileMeta>, StoreError> {
        let db = self.db.lock().unwrap();
        let mut stmt = db.prepare_cached(sql::CHANGES).map_err(err)?;
        let rows = stmt
            .query_map(params![since, limit], row_to_meta)
            .map_err(err)?;
        rows.collect::<Result<_, _>>().map_err(err)
    }

    async fn meta(&self, path: &str) -> Result<Option<FileMeta>, StoreError> {
        let db = self.db.lock().unwrap();
        db.query_row(sql::READ_META, params![path], row_to_meta)
            .optional()
            .map_err(err)
    }

    async fn content(&self, meta: &FileMeta) -> Result<Option<Vec<u8>>, StoreError> {
        match std::fs::read(self.file_path(&meta.path)) {
            Ok(s) => Ok(Some(s)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(err(e)),
        }
    }

    async fn commit(&self, c: Commit<'_>) -> Result<CommitOutcome, StoreError> {
        // Writes are serialised by the DB mutex, so the file system always reflects
        // the latest committed version. The body is staged first and only moved into
        // place once the conditional SQL write succeeds.
        let db = self.db.lock().unwrap();
        let staged = match c.content {
            Some(text) => {
                let n = self.counter.fetch_add(1, Ordering::Relaxed);
                let tmp = self.tmp.join(format!("{}-{n}", std::process::id()));
                std::fs::write(&tmp, text).map_err(err)?;
                Some(tmp)
            }
            None => None,
        };
        let deleted = c.content.is_none() as i64;
        let row = db
            .query_row(
                sql::COMMIT,
                params![c.path, c.hash, c.size, deleted, c.now_ms, c.base_version],
                row_to_meta,
            )
            .optional();
        let row = match row {
            Ok(r) => r,
            Err(e) => {
                if let Some(t) = &staged {
                    let _ = std::fs::remove_file(t);
                }
                return Err(err(e));
            }
        };
        let Some(meta) = row else {
            if let Some(t) = &staged {
                let _ = std::fs::remove_file(t);
            }
            let current = db
                .query_row(sql::READ_META, params![c.path], row_to_meta)
                .optional()
                .map_err(err)?;
            return Ok(CommitOutcome::Conflict(current));
        };
        let target = self.file_path(c.path);
        match staged {
            Some(tmp) => {
                if let Some(parent) = target.parent() {
                    std::fs::create_dir_all(parent).map_err(err)?;
                }
                std::fs::rename(&tmp, &target).map_err(err)?;
            }
            None => {
                let _ = std::fs::remove_file(&target);
                if let Some(parent) = target.parent() {
                    self.prune_empty_dirs(parent);
                }
            }
        }
        Ok(CommitOutcome::Committed(meta))
    }
}
