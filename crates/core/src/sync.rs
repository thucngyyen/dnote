//! Client sync engine, shared by the mobile (UniFFI) and web (wasm) clients.
//!
//! Model: every device keeps, per note, the server version and content it last
//! synced ("base"). A sync round is:
//!
//! 1. **Pull** the change feed. Unmodified local notes are fast-forwarded; locally
//!    modified ones are 3-way merged (base, local, remote).
//! 2. **Push** local changes with `base_version`. The server applies them only if
//!    nobody else wrote in between (409 otherwise), so a lost race just triggers
//!    another round: pull → merge → push.
//!
//! I/O is abstracted behind [`LocalFs`] and [`Http`] so the same code runs on
//! native threads (blocking I/O wrapped in async) and in the browser.

use crate::merge::merge3;
use crate::protocol::*;
use crate::{content_hash, paths};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

pub struct HttpResponse {
    pub status: u16,
    pub body: String,
}

#[allow(async_fn_in_trait)]
pub trait Http {
    async fn send(
        &self,
        method: &str,
        url: &str,
        token: &str,
        body: Option<String>,
    ) -> Result<HttpResponse, String>;
}

/// The device's note folder. Paths are `/`-separated and relative to the vault root.
#[allow(async_fn_in_trait)]
pub trait LocalFs {
    /// Every synced file (see [`paths::is_synced`]): notes, images and the workspace file.
    async fn list_files(&self) -> Result<Vec<String>, String>;
    async fn read(&self, path: &str) -> Result<Option<Vec<u8>>, String>;
    /// Must create parent folders as needed.
    async fn write(&self, path: &str, content: &[u8]) -> Result<(), String>;
    async fn remove(&self, path: &str) -> Result<(), String>;
    async fn load_state(&self) -> Result<Option<String>, String>;
    async fn save_state(&self, json: &str) -> Result<(), String>;
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct BaseEntry {
    pub version: i64,
    pub hash: String,
    pub deleted: bool,
    /// Content at `version`, the common ancestor for 3-way merges (text files only).
    pub base: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SyncState {
    pub server: String,
    pub last_seq: i64,
    pub files: BTreeMap<String, BaseEntry>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SyncReport {
    pub pulled: u32,
    pub pushed: u32,
    pub merged: u32,
    pub deleted_local: u32,
    pub deleted_remote: u32,
    /// Notes whose merge left conflict markers for the user to resolve.
    pub conflicts: Vec<String>,
    /// Local files skipped because their path isn't valid on the server.
    pub skipped: Vec<String>,
}

pub struct SyncConfig {
    /// Base URL, e.g. `https://notes.example.com` (no trailing slash needed).
    pub server: String,
    pub token: String,
}

const MAX_ROUNDS: usize = 4;

pub async fn sync<F: LocalFs, H: Http>(
    fs: &F,
    http: &H,
    cfg: &SyncConfig,
) -> Result<SyncReport, String> {
    let server = cfg.server.trim_end_matches('/').to_string();
    let mut state: SyncState = match fs.load_state().await? {
        Some(json) => serde_json::from_str(&json).unwrap_or_default(),
        None => SyncState::default(),
    };
    if state.server != server {
        // Different server: forget sync history; local notes merge in as new.
        state = SyncState {
            server: server.clone(),
            ..Default::default()
        };
    }

    let client = Client {
        http,
        server: &server,
        token: &cfg.token,
    };
    let mut report = SyncReport::default();
    for _ in 0..MAX_ROUNDS {
        pull(fs, &client, &mut state, &mut report).await?;
        let rejected = push(fs, &client, &mut state, &mut report).await?;
        fs.save_state(&serde_json::to_string(&state).map_err(|e| e.to_string())?)
            .await?;
        if rejected == 0 {
            return Ok(report);
        }
    }
    Err("server kept rejecting changes; try again".into())
}

struct Client<'a, H: Http> {
    http: &'a H,
    server: &'a str,
    token: &'a str,
}

impl<H: Http> Client<'_, H> {
    fn file_url(&self, path: &str, extra: &str) -> String {
        let enc: String = form_urlencoded::byte_serialize(path.as_bytes()).collect();
        format!("{}/api/file?path={enc}{extra}", self.server)
    }

    async fn call(
        &self,
        method: &str,
        url: &str,
        body: Option<String>,
    ) -> Result<HttpResponse, String> {
        let resp = self.http.send(method, url, self.token, body).await?;
        match resp.status {
            401 => Err("unauthorized: check the access token".into()),
            s if s >= 500 => Err(format!("server error {s}: {}", resp.body)),
            _ => Ok(resp),
        }
    }
}

fn parse<T: for<'de> Deserialize<'de>>(body: &str) -> Result<T, String> {
    serde_json::from_str(body).map_err(|e| format!("bad server response: {e}"))
}

fn tombstone(version: i64) -> BaseEntry {
    BaseEntry {
        version,
        hash: String::new(),
        deleted: true,
        base: String::new(),
    }
}

async fn pull<F: LocalFs, H: Http>(
    fs: &F,
    client: &Client<'_, H>,
    state: &mut SyncState,
    report: &mut SyncReport,
) -> Result<(), String> {
    loop {
        let url = format!("{}/api/changes?since={}", client.server, state.last_seq);
        let resp = client.call("GET", &url, None).await?;
        if resp.status != 200 {
            return Err(format!("changes failed ({}): {}", resp.status, resp.body));
        }
        let changes: ChangesResponse = parse(&resp.body)?;
        for meta in &changes.files {
            apply_remote(fs, client, state, report, meta).await?;
        }
        state.last_seq = changes.seq;
        if !changes.more {
            return Ok(());
        }
    }
}

async fn apply_remote<F: LocalFs, H: Http>(
    fs: &F,
    client: &Client<'_, H>,
    state: &mut SyncState,
    report: &mut SyncReport,
    meta: &FileMeta,
) -> Result<(), String> {
    let entry = state.files.get(&meta.path).cloned();
    if entry.as_ref().is_some_and(|e| e.version >= meta.version) {
        return Ok(()); // Already have it (typically our own push echoing back).
    }
    let local = fs.read(&meta.path).await?;
    let dirty = match (&local, &entry) {
        (Some(c), Some(e)) => e.deleted || content_hash(c) != e.hash,
        (Some(_), None) => true,
        (None, _) => false,
    };

    if meta.deleted {
        if !dirty && local.is_some() {
            fs.remove(&meta.path).await?;
            report.deleted_local += 1;
        }
        // If dirty, the local edit survives and is re-created on push.
        state
            .files
            .insert(meta.path.clone(), tombstone(meta.version));
        return Ok(());
    }

    let resp = client
        .call("GET", &client.file_url(&meta.path, ""), None)
        .await?;
    if resp.status == 404 {
        return Ok(()); // Deleted since the feed was read; a later round picks it up.
    }
    if resp.status != 200 {
        return Err(format!(
            "download of {} failed ({})",
            meta.path, resp.status
        ));
    }
    let remote: FileResponse = parse(&resp.body)?;
    let remote_bytes = remote.bytes()?;
    let is_text = paths::is_text(&meta.path);

    match (&local, dirty) {
        (Some(local), true) => {
            let base = entry
                .as_ref()
                .filter(|e| !e.deleted)
                .map(|e| e.base.as_str());
            if meta.path == paths::WORKSPACE_PATH {
                // Pins and tags: set merge, never conflicts.
                let merged = crate::workspace::merge(
                    base.unwrap_or(""),
                    &String::from_utf8_lossy(local),
                    &String::from_utf8_lossy(&remote_bytes),
                );
                fs.write(&meta.path, merged.as_bytes()).await?;
            } else if is_text {
                let local_text = String::from_utf8_lossy(local);
                let remote_text = String::from_utf8_lossy(&remote_bytes);
                let merged = merge3(base.unwrap_or(""), &local_text, &remote_text);
                if merged.conflicted && base.is_none() {
                    // Created independently on two devices: there's no common ancestor, so
                    // conflict markers would just interleave two unrelated documents. Keep
                    // both as separate notes instead (the copy is pushed as a new note).
                    let copy = conflict_copy_path(fs, &meta.path).await?;
                    fs.write(&copy, local).await?;
                    fs.write(&meta.path, &remote_bytes).await?;
                    report.conflicts.push(copy);
                } else {
                    if merged.text != local_text {
                        fs.write(&meta.path, merged.text.as_bytes()).await?;
                    }
                    if merged.conflicted {
                        report.conflicts.push(meta.path.clone());
                    }
                }
            } else {
                // Images can't be merged: keep both.
                let copy = conflict_copy_path(fs, &meta.path).await?;
                fs.write(&copy, local).await?;
                fs.write(&meta.path, &remote_bytes).await?;
                report.conflicts.push(copy);
            }
            report.merged += 1;
        }
        _ => {
            // Unmodified locally, new to us, or deleted locally but edited remotely
            // (an edit wins over a delete).
            fs.write(&meta.path, &remote_bytes).await?;
            report.pulled += 1;
        }
    }
    let base = if is_text {
        String::from_utf8_lossy(&remote_bytes).into_owned()
    } else {
        String::new()
    };
    state.files.insert(
        meta.path.clone(),
        BaseEntry {
            version: remote.meta.version,
            hash: remote.meta.hash,
            deleted: false,
            base,
        },
    );
    Ok(())
}

/// `Notes/Plan.md` -> `Notes/Plan (conflict).md`, `Notes/Plan (conflict 2).md`, ...
async fn conflict_copy_path<F: LocalFs>(fs: &F, path: &str) -> Result<String, String> {
    let (stem, ext) = match path.rfind('.') {
        Some(i) if !path[i..].contains('/') => (&path[..i], &path[i..]),
        _ => (path, ""),
    };
    for n in 1..1000 {
        let candidate = if n == 1 {
            format!("{stem} (conflict){ext}")
        } else {
            format!("{stem} (conflict {n}){ext}")
        };
        if fs.read(&candidate).await?.is_none() {
            return Ok(candidate);
        }
    }
    Err(format!("too many conflict copies of {path}"))
}

/// Returns how many writes the server rejected as conflicts.
async fn push<F: LocalFs, H: Http>(
    fs: &F,
    client: &Client<'_, H>,
    state: &mut SyncState,
    report: &mut SyncReport,
) -> Result<u32, String> {
    let mut rejected = 0;
    let mut present = BTreeSet::new();
    let mut listed = fs.list_files().await?;
    listed.retain(|p| paths::is_synced(p));
    listed.sort();

    for path in listed {
        if paths::normalize(&path).as_deref() != Ok(path.as_str()) {
            if !report.skipped.contains(&path) {
                report.skipped.push(path);
            }
            continue;
        }
        let Some(content) = fs.read(&path).await? else {
            continue;
        };
        present.insert(path.clone());
        let hash = content_hash(&content);
        let entry = state.files.get(&path);
        if entry.is_some_and(|e| !e.deleted && e.hash == hash) {
            continue;
        }
        let base_version = entry.map(|e| e.version).unwrap_or(0);
        let body = serde_json::to_string(&PutRequest::new(&path, base_version, &content))
            .map_err(|e| e.to_string())?;
        let resp = client
            .call("PUT", &client.file_url(&path, ""), Some(body))
            .await?;
        match resp.status {
            200 => {
                let meta: FileMeta = parse(&resp.body)?;
                let base = if paths::is_text(&path) {
                    String::from_utf8_lossy(&content).into_owned()
                } else {
                    String::new()
                };
                state.files.insert(
                    path,
                    BaseEntry {
                        version: meta.version,
                        hash: meta.hash,
                        deleted: false,
                        base,
                    },
                );
                report.pushed += 1;
            }
            409 => rejected += 1,
            s => return Err(format!("upload of {path} failed ({s}): {}", resp.body)),
        }
    }

    let gone: Vec<(String, i64)> = state
        .files
        .iter()
        .filter(|(p, e)| !e.deleted && !present.contains(*p))
        .map(|(p, e)| (p.clone(), e.version))
        .collect();
    for (path, version) in gone {
        let resp = client
            .call(
                "DELETE",
                &client.file_url(&path, &format!("&base_version={version}")),
                None,
            )
            .await?;
        match resp.status {
            200 => {
                let meta: FileMeta = parse(&resp.body)?;
                state.files.insert(path, tombstone(meta.version));
                report.deleted_remote += 1;
            }
            404 => {
                state.files.remove(&path);
            }
            409 => rejected += 1,
            s => return Err(format!("delete of {path} failed ({s}): {}", resp.body)),
        }
    }
    Ok(rejected)
}

#[cfg(all(test, feature = "server"))]
mod tests {
    use super::*;
    use crate::api::{self, memory::MemoryStore};
    use std::cell::RefCell;

    /// HTTP client wired straight into the real API handler.
    struct Loopback<'a>(&'a MemoryStore);

    impl Http for Loopback<'_> {
        async fn send(
            &self,
            method: &str,
            url: &str,
            token: &str,
            body: Option<String>,
        ) -> Result<HttpResponse, String> {
            let rest = url.strip_prefix("http://test").unwrap();
            let (path, query) = rest.split_once('?').unwrap_or((rest, ""));
            let cfg = api::Config {
                token: "t".into(),
                backend: "test".into(),
            };
            let resp = api::handle(
                self.0,
                &cfg,
                api::Request {
                    method: method.into(),
                    path: path.into(),
                    query: query.into(),
                    authorization: Some(format!("Bearer {token}")),
                    body: body.unwrap_or_default().into_bytes(),
                    now_ms: 0,
                },
            )
            .await;
            Ok(HttpResponse {
                status: resp.status,
                body: String::from_utf8(resp.body).unwrap(),
            })
        }
    }

    #[derive(Default)]
    struct MemFs {
        files: RefCell<BTreeMap<String, Vec<u8>>>,
        state: RefCell<Option<String>>,
    }

    impl LocalFs for MemFs {
        async fn list_files(&self) -> Result<Vec<String>, String> {
            Ok(self.files.borrow().keys().cloned().collect())
        }
        async fn read(&self, p: &str) -> Result<Option<Vec<u8>>, String> {
            Ok(self.files.borrow().get(p).cloned())
        }
        async fn write(&self, p: &str, c: &[u8]) -> Result<(), String> {
            self.files.borrow_mut().insert(p.into(), c.to_vec());
            Ok(())
        }
        async fn remove(&self, p: &str) -> Result<(), String> {
            self.files.borrow_mut().remove(p);
            Ok(())
        }
        async fn load_state(&self) -> Result<Option<String>, String> {
            Ok(self.state.borrow().clone())
        }
        async fn save_state(&self, s: &str) -> Result<(), String> {
            *self.state.borrow_mut() = Some(s.into());
            Ok(())
        }
    }

    impl MemFs {
        fn put(&self, p: &str, c: &str) {
            self.files
                .borrow_mut()
                .insert(p.into(), c.as_bytes().to_vec());
        }
        fn get(&self, p: &str) -> Option<String> {
            self.files
                .borrow()
                .get(p)
                .map(|b| String::from_utf8_lossy(b).into_owned())
        }
    }

    fn cfg() -> SyncConfig {
        SyncConfig {
            server: "http://test".into(),
            token: "t".into(),
        }
    }

    #[test]
    fn two_devices_converge() {
        pollster::block_on(async {
            let server = MemoryStore::default();
            let http = Loopback(&server);
            let (phone, laptop) = (MemFs::default(), MemFs::default());

            phone.put("Journal/today.md", "line 1\nline 2\nline 3\n");
            let r = sync(&phone, &http, &cfg()).await.unwrap();
            assert_eq!(r.pushed, 1);

            let r = sync(&laptop, &http, &cfg()).await.unwrap();
            assert_eq!(r.pulled, 1);
            assert_eq!(
                laptop.get("Journal/today.md").unwrap(),
                "line 1\nline 2\nline 3\n"
            );

            // Concurrent edits to different lines merge cleanly.
            phone.put("Journal/today.md", "LINE 1\nline 2\nline 3\n");
            laptop.put("Journal/today.md", "line 1\nline 2\nLINE 3\n");
            sync(&phone, &http, &cfg()).await.unwrap();
            let r = sync(&laptop, &http, &cfg()).await.unwrap();
            assert_eq!(r.merged, 1);
            assert!(r.conflicts.is_empty());
            sync(&phone, &http, &cfg()).await.unwrap();
            let expected = "LINE 1\nline 2\nLINE 3\n";
            assert_eq!(phone.get("Journal/today.md").unwrap(), expected);
            assert_eq!(laptop.get("Journal/today.md").unwrap(), expected);

            // Deletes propagate.
            phone.remove("Journal/today.md").await.unwrap();
            let r = sync(&phone, &http, &cfg()).await.unwrap();
            assert_eq!(r.deleted_remote, 1);
            let r = sync(&laptop, &http, &cfg()).await.unwrap();
            assert_eq!(r.deleted_local, 1);
            assert!(laptop.files.borrow().is_empty());

            // Re-creating a deleted path works.
            laptop.put("Journal/today.md", "again\n");
            assert_eq!(sync(&laptop, &http, &cfg()).await.unwrap().pushed, 1);
            assert_eq!(sync(&phone, &http, &cfg()).await.unwrap().pulled, 1);
        });
    }

    #[test]
    fn independently_created_notes_become_copies() {
        pollster::block_on(async {
            let server = MemoryStore::default();
            let http = Loopback(&server);
            let (a, b) = (MemFs::default(), MemFs::default());
            a.put("Ideas.md", "from a\n");
            b.put("Ideas.md", "something else from b\n");
            sync(&a, &http, &cfg()).await.unwrap();
            let r = sync(&b, &http, &cfg()).await.unwrap();
            assert_eq!(r.conflicts, vec!["Ideas (conflict).md".to_string()]);
            assert_eq!(b.get("Ideas.md").unwrap(), "from a\n");
            assert_eq!(
                b.get("Ideas (conflict).md").unwrap(),
                "something else from b\n"
            );
            // The copy reaches the other device; identical new notes merge silently.
            assert_eq!(sync(&a, &http, &cfg()).await.unwrap().pulled, 1);
            let (c, d) = (MemFs::default(), MemFs::default());
            let server2 = MemoryStore::default();
            let http2 = Loopback(&server2);
            c.put("Same.md", "same\n");
            d.put("Same.md", "same\n");
            sync(&c, &http2, &cfg()).await.unwrap();
            assert!(sync(&d, &http2, &cfg()).await.unwrap().conflicts.is_empty());
        });
    }

    #[test]
    fn images_and_workspace_sync() {
        use crate::paths::WORKSPACE_PATH;
        use crate::workspace::{Workspace, FAVORITE};
        pollster::block_on(async {
            let server = MemoryStore::default();
            let http = Loopback(&server);
            let (a, b) = (MemFs::default(), MemFs::default());
            let png: Vec<u8> = vec![0x89, b'P', b'N', b'G', 0, 1, 2, 255, 254];
            a.write("Attachments/pic.png", &png).await.unwrap();
            a.put("Ignored.exe", "nope");
            let mut ws = Workspace::default();
            ws.toggle_pin("Notes.md");
            a.put(WORKSPACE_PATH, &ws.to_json());
            assert_eq!(sync(&a, &http, &cfg()).await.unwrap().pushed, 2);
            sync(&b, &http, &cfg()).await.unwrap();
            assert_eq!(b.read("Attachments/pic.png").await.unwrap().unwrap(), png);
            assert!(b.read("Ignored.exe").await.unwrap().is_none());

            // Both devices edit the workspace: set merge, no conflict.
            let mut wa = Workspace::parse(&a.get(WORKSPACE_PATH).unwrap());
            wa.set_tag("Notes.md", FAVORITE, true);
            a.put(WORKSPACE_PATH, &wa.to_json());
            let mut wb = Workspace::parse(&b.get(WORKSPACE_PATH).unwrap());
            wb.toggle_pin("Other.md");
            b.put(WORKSPACE_PATH, &wb.to_json());
            sync(&a, &http, &cfg()).await.unwrap();
            let r = sync(&b, &http, &cfg()).await.unwrap();
            assert!(r.conflicts.is_empty());
            sync(&a, &http, &cfg()).await.unwrap();
            for dev in [&a, &b] {
                let w = Workspace::parse(&dev.get(WORKSPACE_PATH).unwrap());
                assert_eq!(w.pins, ["Notes.md", "Other.md"]);
                assert_eq!(w.tags_of("Notes.md"), [FAVORITE]);
            }

            // Conflicting image edits keep both files.
            a.write("Attachments/pic.png", b"version a").await.unwrap();
            b.write("Attachments/pic.png", b"version b").await.unwrap();
            sync(&a, &http, &cfg()).await.unwrap();
            let r = sync(&b, &http, &cfg()).await.unwrap();
            assert_eq!(r.conflicts, ["Attachments/pic (conflict).png"]);
            assert_eq!(b.get("Attachments/pic.png").unwrap(), "version a");
            assert_eq!(
                b.get("Attachments/pic (conflict).png").unwrap(),
                "version b"
            );
        });
    }

    #[test]
    fn conflicting_edits_keep_both_sides() {
        pollster::block_on(async {
            let server = MemoryStore::default();
            let http = Loopback(&server);
            let (a, b) = (MemFs::default(), MemFs::default());
            a.put("n.md", "hello\n");
            sync(&a, &http, &cfg()).await.unwrap();
            sync(&b, &http, &cfg()).await.unwrap();
            a.put("n.md", "hello from a\n");
            b.put("n.md", "hello from b\n");
            sync(&a, &http, &cfg()).await.unwrap();
            let r = sync(&b, &http, &cfg()).await.unwrap();
            assert_eq!(r.conflicts, vec!["n.md".to_string()]);
            let text = b.get("n.md").unwrap();
            assert!(text.contains("hello from a") && text.contains("hello from b"));
        });
    }
}
