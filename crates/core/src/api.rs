//! The complete DNote HTTP API, written once as a pure async function over a
//! [`Store`]. Runtime adapters (axum, Cloudflare Workers) only translate their
//! native request/response types to [`Request`]/[`Response`].
//!
//! Endpoints (all JSON):
//! * `GET    /api/health`                                  – no auth
//! * `GET    /api/changes?since=<seq>`                     – change feed
//! * `GET    /api/file?path=<p>`                           – read a note
//! * `PUT    /api/file?path=<p>`  body `{base_version, content}` – write (409 on conflict)
//! * `DELETE /api/file?path=<p>&base_version=<v>`          – delete (409 on conflict)

use crate::protocol::*;
use crate::{content_hash, paths};
use serde::Serialize;

pub type StoreError = String;

pub struct Commit<'a> {
    pub path: &'a str,
    pub base_version: i64,
    /// `None` deletes the file (writes a tombstone).
    pub content: Option<&'a [u8]>,
    pub hash: String,
    pub size: i64,
    pub now_ms: i64,
}

pub enum CommitOutcome {
    Committed(FileMeta),
    Conflict(Option<FileMeta>),
}

/// Storage backend. Both implementations run [`crate::sql`] statements, so the
/// concurrency semantics are identical; they differ only in where note bodies live.
#[allow(async_fn_in_trait)]
pub trait Store {
    async fn changes_since(&self, since: i64, limit: i64) -> Result<Vec<FileMeta>, StoreError>;
    async fn meta(&self, path: &str) -> Result<Option<FileMeta>, StoreError>;
    async fn content(&self, meta: &FileMeta) -> Result<Option<Vec<u8>>, StoreError>;
    async fn commit(&self, commit: Commit<'_>) -> Result<CommitOutcome, StoreError>;
}

pub struct Config {
    /// Bearer token required for every endpoint except health.
    pub token: String,
    /// Shown in `/api/health`, e.g. "self-hosted" or "cloudflare".
    pub backend: String,
}

pub struct Request {
    pub method: String,
    pub path: String,
    pub query: String,
    pub authorization: Option<String>,
    pub body: Vec<u8>,
    pub now_ms: i64,
}

#[derive(Debug)]
pub struct Response {
    pub status: u16,
    pub body: Vec<u8>,
}

impl Response {
    fn json<T: Serialize>(status: u16, value: &T) -> Self {
        Response {
            status,
            body: serde_json::to_vec(value).unwrap_or_default(),
        }
    }
    fn error(status: u16, msg: impl Into<String>) -> Self {
        Self::json(status, &ErrorResponse { error: msg.into() })
    }
    pub const CONTENT_TYPE: &'static str = "application/json; charset=utf-8";
}

/// Read a row from the [`crate::sql`] column order. Shared by both adapters.
pub fn meta_from_row(
    path: String,
    version: i64,
    hash: String,
    size: i64,
    deleted: i64,
    seq: i64,
    updated_at: i64,
) -> FileMeta {
    FileMeta {
        path,
        version,
        hash,
        size,
        deleted: deleted != 0,
        seq,
        updated_at,
    }
}

fn query_param(query: &str, key: &str) -> Option<String> {
    form_urlencoded::parse(query.as_bytes())
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.into_owned())
}

/// Constant-time comparison so the token can't be guessed byte by byte via timing.
fn token_matches(expected: &str, header: Option<&str>) -> bool {
    let Some(given) = header.and_then(|h| h.strip_prefix("Bearer ")) else {
        return false;
    };
    let (a, b) = (expected.as_bytes(), given.trim().as_bytes());
    if a.is_empty() || a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

pub async fn handle<S: Store>(store: &S, config: &Config, req: Request) -> Response {
    match route(store, config, req).await {
        Ok(resp) => resp,
        Err(e) => Response::error(500, format!("storage error: {e}")),
    }
}

async fn route<S: Store>(store: &S, config: &Config, req: Request) -> Result<Response, StoreError> {
    let method = req.method.to_ascii_uppercase();
    if req.path == "/api/health" && method == "GET" {
        return Ok(Response::json(
            200,
            &HealthResponse {
                ok: true,
                name: "dnote".into(),
                version: env!("CARGO_PKG_VERSION").into(),
                backend: config.backend.clone(),
            },
        ));
    }
    if !req.path.starts_with("/api/") {
        return Ok(Response::error(404, "not found"));
    }
    if !token_matches(&config.token, req.authorization.as_deref()) {
        return Ok(Response::error(401, "missing or invalid bearer token"));
    }

    match (method.as_str(), req.path.as_str()) {
        ("GET", "/api/changes") => {
            let since = query_param(&req.query, "since")
                .and_then(|s| s.parse().ok())
                .unwrap_or(0i64);
            let mut files = store.changes_since(since, CHANGES_PAGE + 1).await?;
            let more = files.len() as i64 > CHANGES_PAGE;
            files.truncate(CHANGES_PAGE as usize);
            let seq = files.last().map(|f| f.seq).unwrap_or(since);
            Ok(Response::json(200, &ChangesResponse { seq, files, more }))
        }
        (m, "/api/file") => {
            let raw = query_param(&req.query, "path").unwrap_or_default();
            let path = match paths::normalize(&raw) {
                Ok(p) => p,
                Err(e) => return Ok(Response::error(400, e)),
            };
            match m {
                "GET" => read_file(store, &path).await,
                "PUT" => put_file(store, &path, &req).await,
                "DELETE" => delete_file(store, &path, &req).await,
                _ => Ok(Response::error(405, "method not allowed")),
            }
        }
        _ => Ok(Response::error(404, "not found")),
    }
}

async fn read_file<S: Store>(store: &S, path: &str) -> Result<Response, StoreError> {
    let Some(meta) = store.meta(path).await? else {
        return Ok(Response::error(404, "no such note"));
    };
    if meta.deleted {
        return Ok(Response::error(404, "note was deleted"));
    }
    match store.content(&meta).await? {
        Some(bytes) => Ok(Response::json(200, &FileResponse::new(meta, bytes))),
        None => Err(format!("content missing for {path}")),
    }
}

async fn put_file<S: Store>(store: &S, path: &str, req: &Request) -> Result<Response, StoreError> {
    if !paths::is_synced(path) {
        return Ok(Response::error(
            415,
            "only notes (.md, .txt) and images can be stored",
        ));
    }
    if req.body.len() > MAX_BODY_BYTES {
        return Ok(Response::error(413, "file too large"));
    }
    let body: PutRequest = match serde_json::from_slice(&req.body) {
        Ok(b) => b,
        Err(e) => return Ok(Response::error(400, format!("invalid body: {e}"))),
    };
    let bytes = match body.bytes() {
        Ok(b) => b,
        Err(e) => return Ok(Response::error(400, e)),
    };
    let limit = if paths::is_text(path) {
        MAX_NOTE_BYTES
    } else {
        MAX_ATTACHMENT_BYTES
    };
    if bytes.len() > limit {
        return Ok(Response::error(413, "file too large"));
    }
    let commit = Commit {
        path,
        base_version: body.base_version,
        content: Some(&bytes),
        hash: content_hash(&bytes),
        size: bytes.len() as i64,
        now_ms: req.now_ms,
    };
    Ok(outcome(store.commit(commit).await?))
}

async fn delete_file<S: Store>(
    store: &S,
    path: &str,
    req: &Request,
) -> Result<Response, StoreError> {
    let Some(base_version) =
        query_param(&req.query, "base_version").and_then(|v| v.parse::<i64>().ok())
    else {
        return Ok(Response::error(400, "base_version is required"));
    };
    match store.meta(path).await? {
        None => return Ok(Response::error(404, "no such note")),
        Some(m) if m.deleted && m.version == base_version => return Ok(Response::json(200, &m)),
        _ => {}
    }
    let commit = Commit {
        path,
        base_version,
        content: None,
        hash: String::new(),
        size: 0,
        now_ms: req.now_ms,
    };
    Ok(outcome(store.commit(commit).await?))
}

fn outcome(o: CommitOutcome) -> Response {
    match o {
        CommitOutcome::Committed(meta) => Response::json(200, &meta),
        CommitOutcome::Conflict(current) => Response::json(
            409,
            &ConflictResponse {
                error: "version conflict: pull and merge first".into(),
                current,
            },
        ),
    }
}

/// In-memory store used by tests (and handy for experiments).
pub mod memory {
    use super::*;
    use std::cell::RefCell;
    use std::collections::BTreeMap;

    #[derive(Default)]
    pub struct MemoryStore {
        files: RefCell<BTreeMap<String, (FileMeta, Vec<u8>)>>,
    }

    impl Store for MemoryStore {
        async fn changes_since(&self, since: i64, limit: i64) -> Result<Vec<FileMeta>, StoreError> {
            let mut v: Vec<_> = self
                .files
                .borrow()
                .values()
                .map(|(m, _)| m.clone())
                .filter(|m| m.seq > since)
                .collect();
            v.sort_by_key(|m| m.seq);
            v.truncate(limit as usize);
            Ok(v)
        }
        async fn meta(&self, path: &str) -> Result<Option<FileMeta>, StoreError> {
            Ok(self.files.borrow().get(path).map(|(m, _)| m.clone()))
        }
        async fn content(&self, meta: &FileMeta) -> Result<Option<Vec<u8>>, StoreError> {
            Ok(self.files.borrow().get(&meta.path).map(|(_, c)| c.clone()))
        }
        async fn commit(&self, c: Commit<'_>) -> Result<CommitOutcome, StoreError> {
            let mut files = self.files.borrow_mut();
            let current = files.get(c.path).map(|(m, _)| m.clone());
            let ok = match &current {
                None => c.base_version == 0,
                Some(m) => m.version == c.base_version,
            };
            if !ok {
                return Ok(CommitOutcome::Conflict(current));
            }
            let seq = files.values().map(|(m, _)| m.seq).max().unwrap_or(0) + 1;
            let meta = FileMeta {
                path: c.path.to_string(),
                version: current.map(|m| m.version + 1).unwrap_or(1),
                hash: c.hash,
                size: c.size,
                deleted: c.content.is_none(),
                seq,
                updated_at: c.now_ms,
            };
            files.insert(
                c.path.to_string(),
                (meta.clone(), c.content.unwrap_or_default().to_vec()),
            );
            Ok(CommitOutcome::Committed(meta))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::memory::MemoryStore;
    use super::*;

    fn req(method: &str, path: &str, query: &str, body: &str) -> Request {
        Request {
            method: method.into(),
            path: path.into(),
            query: query.into(),
            authorization: Some("Bearer secret".into()),
            body: body.as_bytes().to_vec(),
            now_ms: 1,
        }
    }

    #[test]
    fn write_conflict_delete_flow() {
        pollster::block_on(async {
            let store = MemoryStore::default();
            let cfg = Config {
                token: "secret".into(),
                backend: "test".into(),
            };

            let mut unauth = req("GET", "/api/changes", "", "");
            unauth.authorization = Some("Bearer nope!".into());
            assert_eq!(handle(&store, &cfg, unauth).await.status, 401);

            let r = handle(
                &store,
                &cfg,
                req(
                    "PUT",
                    "/api/file",
                    "path=a%2Fb.md",
                    r#"{"base_version":0,"content":"hi"}"#,
                ),
            )
            .await;
            assert_eq!(r.status, 200);
            // Stale write is rejected.
            let r = handle(
                &store,
                &cfg,
                req(
                    "PUT",
                    "/api/file",
                    "path=a/b.md",
                    r#"{"base_version":0,"content":"yo"}"#,
                ),
            )
            .await;
            assert_eq!(r.status, 409);
            let r = handle(
                &store,
                &cfg,
                req(
                    "PUT",
                    "/api/file",
                    "path=a/b.md",
                    r#"{"base_version":1,"content":"yo"}"#,
                ),
            )
            .await;
            assert_eq!(r.status, 200);

            let r = handle(&store, &cfg, req("GET", "/api/changes", "since=0", "")).await;
            let ch: ChangesResponse = serde_json::from_slice(&r.body).unwrap();
            assert_eq!(ch.files.len(), 1);
            assert_eq!(ch.files[0].version, 2);

            let r = handle(
                &store,
                &cfg,
                req("DELETE", "/api/file", "path=a/b.md&base_version=2", ""),
            )
            .await;
            assert_eq!(r.status, 200);
            let r = handle(&store, &cfg, req("GET", "/api/file", "path=a/b.md", "")).await;
            assert_eq!(r.status, 404);
            let r = handle(
                &store,
                &cfg,
                req("GET", "/api/file", "path=../etc/passwd", ""),
            )
            .await;
            assert_eq!(r.status, 400);
        });
    }
}
