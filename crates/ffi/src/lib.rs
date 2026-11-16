//! Native DNote core for iOS and Android.
//!
//! A [`Vault`] is a plain folder of Markdown files on the device: it works fully
//! offline, and [`Vault::sync`] optionally syncs it with a DNote server (self-hosted
//! or Cloudflare) using the shared engine in `dnote-core`. Sync metadata lives in
//! `<vault>/.dnote/`.

use dnote_core::paths;
use dnote_core::sync::{self, Http, HttpResponse, LocalFs, SyncConfig};
use dnote_core::workspace::Workspace;
use std::cell::RefCell;
use std::collections::BTreeSet;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

uniffi::setup_scaffolding!();

#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum DNoteError {
    Io(String),
    InvalidPath(String),
    AlreadyExists(String),
    NotFound(String),
    Sync(String),
}

impl std::fmt::Display for DNoteError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DNoteError::Io(m) => write!(f, "{m}"),
            DNoteError::InvalidPath(m) => write!(f, "Invalid name: {m}"),
            DNoteError::AlreadyExists(p) => write!(f, "“{p}” already exists"),
            DNoteError::NotFound(p) => write!(f, "“{p}” was not found"),
            DNoteError::Sync(m) => write!(f, "{m}"),
        }
    }
}

impl std::error::Error for DNoteError {}

impl From<std::io::Error> for DNoteError {
    fn from(e: std::io::Error) -> Self {
        DNoteError::Io(e.to_string())
    }
}

type Result<T> = std::result::Result<T, DNoteError>;

/// One row of the note tree, in display order (folders first, natural sort).
#[derive(Debug, Clone, uniffi::Record)]
pub struct Entry {
    pub path: String,
    pub name: String,
    pub parent: String,
    pub is_folder: bool,
    /// An image (png, jpg, ...) rather than a note.
    pub is_image: bool,
    pub depth: u32,
    /// Last modified, Unix milliseconds.
    pub modified_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TagInfo {
    pub id: String,
    pub name: String,
    /// One of `dnote_core::workspace::TAG_COLORS`.
    pub color: String,
    /// Notes and folders carrying this tag.
    pub count: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchHit {
    pub path: String,
    pub snippet: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncSummary {
    pub pulled: u32,
    pub pushed: u32,
    pub merged: u32,
    pub deleted_local: u32,
    pub deleted_remote: u32,
    pub conflicts: Vec<String>,
    pub skipped: Vec<String>,
    /// Notes the sync wrote or removed locally (the app reloads these if open).
    pub changed: Vec<String>,
}

#[derive(uniffi::Object)]
pub struct Vault {
    root: PathBuf,
    /// Serialises syncs; plain reads/writes don't need it.
    sync_lock: Mutex<()>,
    /// Serialises read-modify-write of the workspace file (pins, tags).
    ws_lock: Mutex<()>,
}

const META_DIR: &str = ".dnote";

fn check(path: &str) -> Result<String> {
    paths::normalize(path).map_err(DNoteError::InvalidPath)
}

fn natural_key(s: &str) -> Vec<(String, u64)> {
    // Split into (text, number) chunks so "Note 2" sorts before "Note 10".
    let mut out = Vec::new();
    let mut text = String::new();
    let mut num: Option<u64> = None;
    for c in s.chars() {
        if let Some(d) = c.to_digit(10) {
            num = Some(num.unwrap_or(0).saturating_mul(10).saturating_add(d as u64));
        } else {
            if let Some(n) = num.take() {
                out.push((std::mem::take(&mut text), n));
            }
            text.extend(c.to_lowercase());
        }
    }
    out.push((text, num.unwrap_or(0)));
    out
}

/// Atomic write: temp file in the same directory, then rename.
fn write_atomic(target: &Path, content: impl AsRef<[u8]>) -> std::io::Result<()> {
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = target.with_file_name(format!(
        ".{}.dnote-tmp",
        target
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("note")
    ));
    fs::write(&tmp, content)?;
    fs::rename(&tmp, target)
}

impl Vault {
    fn abs(&self, path: &str) -> PathBuf {
        self.root.join(path)
    }

    fn walk(&self, dir: &Path, rel: &str, depth: u32, out: &mut Vec<Entry>) -> std::io::Result<()> {
        let mut items: Vec<(bool, String, i64)> = Vec::new();
        for entry in fs::read_dir(dir)? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with('.') {
                continue;
            }
            let ft = entry.file_type()?;
            let modified = entry
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);
            if ft.is_dir() {
                items.push((true, name, modified));
            } else if ft.is_file() && (paths::is_note(&name) || paths::is_attachment(&name)) {
                items.push((false, name, modified));
            }
        }
        items.sort_by(|a, b| {
            b.0.cmp(&a.0)
                .then_with(|| natural_key(&a.1).cmp(&natural_key(&b.1)))
        });
        for (is_folder, name, modified_ms) in items {
            let path = if rel.is_empty() {
                name.clone()
            } else {
                format!("{rel}/{name}")
            };
            let is_image = !is_folder && paths::is_attachment(&name);
            out.push(Entry {
                path: path.clone(),
                name: name.clone(),
                parent: rel.to_string(),
                is_folder,
                is_image,
                depth,
                modified_ms,
            });
            if is_folder {
                self.walk(&dir.join(&name), &path, depth + 1, out)?;
            }
        }
        Ok(())
    }

    fn note_paths(&self) -> std::io::Result<Vec<String>> {
        let mut entries = Vec::new();
        self.walk(&self.root, "", 0, &mut entries)?;
        Ok(entries
            .into_iter()
            .filter(|e| !e.is_folder && !e.is_image)
            .map(|e| e.path)
            .collect())
    }

    /// Everything the sync engine moves: notes, images and the workspace file.
    fn synced_paths(&self) -> std::io::Result<Vec<String>> {
        let mut entries = Vec::new();
        self.walk(&self.root, "", 0, &mut entries)?;
        let mut out: Vec<String> = entries
            .into_iter()
            .filter(|e| !e.is_folder)
            .map(|e| e.path)
            .collect();
        if self.abs(paths::WORKSPACE_PATH).is_file() {
            out.push(paths::WORKSPACE_PATH.to_string());
        }
        Ok(out)
    }

    fn load_workspace(&self) -> Workspace {
        Workspace::parse(&fs::read_to_string(self.abs(paths::WORKSPACE_PATH)).unwrap_or_default())
    }

    fn edit_workspace<T>(&self, f: impl FnOnce(&mut Workspace) -> T) -> Result<T> {
        let _guard = self.ws_lock.lock().unwrap_or_else(|e| e.into_inner());
        let mut ws = self.load_workspace();
        let before = ws.to_json();
        let out = f(&mut ws);
        let after = ws.to_json();
        if after != before {
            write_atomic(&self.abs(paths::WORKSPACE_PATH), after)?;
        }
        Ok(out)
    }

    fn prune_empty_parents(&self, path: &str) {
        let mut dir = self.abs(path);
        while dir.pop() && dir != self.root && dir.starts_with(&self.root) {
            if fs::remove_dir(&dir).is_err() {
                break;
            }
        }
    }
}

#[uniffi::export]
impl Vault {
    /// Open (creating if needed) a vault rooted at `root`.
    #[uniffi::constructor]
    pub fn open(root: String) -> Result<Arc<Self>> {
        let root = PathBuf::from(root);
        fs::create_dir_all(root.join(META_DIR))?;
        Ok(Arc::new(Vault {
            root,
            sync_lock: Mutex::new(()),
            ws_lock: Mutex::new(()),
        }))
    }

    pub fn root(&self) -> String {
        self.root.to_string_lossy().to_string()
    }

    /// Whole tree, depth-first, folders before notes.
    pub fn entries(&self) -> Result<Vec<Entry>> {
        let mut out = Vec::new();
        self.walk(&self.root, "", 0, &mut out)?;
        Ok(out)
    }

    pub fn read(&self, path: String) -> Result<String> {
        let path = check(&path)?;
        fs::read_to_string(self.abs(&path)).map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => DNoteError::NotFound(path),
            _ => e.into(),
        })
    }

    pub fn exists(&self, path: String) -> bool {
        check(&path).map(|p| self.abs(&p).exists()).unwrap_or(false)
    }

    pub fn write(&self, path: String, content: String) -> Result<()> {
        let path = check(&path)?;
        write_atomic(&self.abs(&path), &content)?;
        Ok(())
    }

    /// Create a note named `name` (".md" added if missing) inside `folder` ("" = top level).
    /// Returns its path.
    pub fn create_note(&self, folder: String, name: String) -> Result<String> {
        let name = paths::title_to_name(&name);
        let path = if name.is_empty() {
            // No name: "Untitled note (1)", "(2)", ... — renamed later from the title field.
            let existing = self.note_paths()?;
            check(&paths::next_untitled(&existing, &folder))?
        } else {
            let file = if paths::is_note(&name) {
                name
            } else {
                format!("{name}.md")
            };
            check(&if folder.is_empty() {
                file
            } else {
                format!("{folder}/{file}")
            })?
        };
        let abs = self.abs(&path);
        if abs.exists() {
            return Err(DNoteError::AlreadyExists(path));
        }
        // The title lives in the file name (edited from the title field), so the body starts empty.
        write_atomic(&abs, "")?;
        Ok(path)
    }

    /// Rename a note from its title field. Keeps it in the same folder; returns the new path
    /// (unchanged if the title is empty or the same).
    pub fn rename_note_title(&self, path: String, title: String) -> Result<String> {
        let path = check(&path)?;
        let name = paths::title_to_name(&title);
        if name.is_empty() {
            return Ok(path);
        }
        let parent = paths::parent(&path);
        let target = if parent.is_empty() {
            format!("{name}.md")
        } else {
            format!("{parent}/{name}.md")
        };
        if target == path {
            return Ok(path);
        }
        // Allow changing only the case of a name on case-insensitive file systems.
        if target.to_lowercase() == path.to_lowercase() {
            let tmp = format!("{path}.dnote-rename");
            fs::rename(self.abs(&path), self.abs(&tmp))?;
            fs::rename(self.abs(&tmp), self.abs(&target))?;
            self.edit_workspace(|ws| ws.rename_path(&path, &target))?;
            return Ok(target);
        }
        self.rename(path, target)
    }

    pub fn create_folder(&self, path: String) -> Result<String> {
        let path = check(&path)?;
        let abs = self.abs(&path);
        if abs.exists() {
            return Err(DNoteError::AlreadyExists(path));
        }
        fs::create_dir_all(abs)?;
        Ok(path)
    }

    /// Rename or move a note or folder. For notes, ".md" is added if `to` has no extension.
    pub fn rename(&self, from: String, to: String) -> Result<String> {
        let from = check(&from)?;
        let src = self.abs(&from);
        if !src.exists() {
            return Err(DNoteError::NotFound(from));
        }
        let mut to = to.trim().to_string();
        // Notes keep a note extension; images keep theirs (renaming photo.jpg to "cover" -> cover.jpg).
        if src.is_file() && paths::is_note(&from) && !paths::is_note(&to) {
            to.push_str(".md");
        } else if src.is_file() && paths::is_attachment(&from) && !paths::is_attachment(&to) {
            let ext = from.rsplit_once('.').map(|(_, e)| e).unwrap_or("jpg");
            to = format!("{to}.{ext}");
        }
        let to = check(&to)?;
        if to == from {
            return Ok(to);
        }
        if to.starts_with(&format!("{from}/")) {
            return Err(DNoteError::InvalidPath(
                "can't move a folder into itself".into(),
            ));
        }
        let dst = self.abs(&to);
        if dst.exists() {
            return Err(DNoteError::AlreadyExists(to));
        }
        if let Some(parent) = dst.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::rename(&src, &dst)?;
        self.edit_workspace(|ws| ws.rename_path(&from, &to))?;
        Ok(to)
    }

    /// Delete a note, or a folder with everything in it.
    pub fn delete(&self, path: String) -> Result<()> {
        let path = check(&path)?;
        let abs = self.abs(&path);
        if abs.is_dir() {
            fs::remove_dir_all(abs)?;
        } else if abs.exists() {
            fs::remove_file(abs)?;
        }
        self.edit_workspace(|ws| ws.remove_path(&path))?;
        Ok(())
    }

    /// First line of body text (skipping the title heading), for list previews.
    pub fn preview(&self, path: String) -> String {
        let Ok(path) = check(&path) else {
            return String::new();
        };
        let text = fs::read_to_string(self.abs(&path)).unwrap_or_default();
        dnote_core::markdown::excerpt(&text, 140)
    }

    // ---- Images ---------------------------------------------------------------

    pub fn read_bytes(&self, path: String) -> Result<Vec<u8>> {
        let path = check(&path)?;
        fs::read(self.abs(&path)).map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => DNoteError::NotFound(path),
            _ => e.into(),
        })
    }

    /// Save an image into `Attachments/` and return the Markdown to insert into the note at
    /// `note_path`, e.g. `![](../Attachments/photo-2.jpg)`.
    pub fn add_image(&self, note_path: String, file_name: String, data: Vec<u8>) -> Result<String> {
        let mut name: String = file_name
            .rsplit('/')
            .next()
            .unwrap_or("image")
            .chars()
            .filter(|c| !c.is_control() && !matches!(c, '\\' | ':' | '/' | '(' | ')' | '[' | ']'))
            .collect();
        if !paths::is_attachment(&name) {
            name = format!("{}.jpg", if name.is_empty() { "image" } else { &name });
        }
        let (stem, ext) = name
            .rsplit_once('.')
            .map(|(a, b)| (a.to_string(), b.to_string()))
            .unwrap_or((name.clone(), "jpg".into()));
        let mut path = format!("{}/{name}", paths::ATTACHMENTS_DIR);
        let mut n = 2;
        while self.abs(&path).exists() {
            path = format!("{}/{stem}-{n}.{ext}", paths::ATTACHMENTS_DIR);
            n += 1;
        }
        let path = check(&path)?;
        if data.len() > dnote_core::protocol::MAX_ATTACHMENT_BYTES {
            return Err(DNoteError::Io("Image is larger than 15 MB".into()));
        }
        write_atomic(&self.abs(&path), &data)?;
        Ok(format!("![]({})", paths::relative_link(&note_path, &path)))
    }

    // ---- Pins and tags ----------------------------------------------------------

    /// Pinned notes and folders that still exist, in pin order.
    pub fn pins(&self) -> Vec<String> {
        self.load_workspace()
            .pins
            .into_iter()
            .filter(|p| check(p).is_ok() && self.abs(p).exists())
            .collect()
    }

    pub fn is_pinned(&self, path: String) -> bool {
        self.load_workspace().is_pinned(&path)
    }

    /// Returns whether the path is now pinned.
    pub fn toggle_pin(&self, path: String) -> Result<bool> {
        let path = check(&path)?;
        self.edit_workspace(|ws| ws.toggle_pin(&path))
    }

    /// All tags, Favorite first, with how many existing items carry each.
    pub fn tags(&self) -> Vec<TagInfo> {
        let ws = self.load_workspace();
        ws.tags()
            .into_iter()
            .map(|t| {
                let count = ws
                    .paths_with_tag(&t.id)
                    .iter()
                    .filter(|p| self.abs(p).exists())
                    .count() as u32;
                TagInfo {
                    id: t.id,
                    name: t.name,
                    color: t.color,
                    count,
                }
            })
            .collect()
    }

    pub fn tags_of(&self, path: String) -> Vec<String> {
        self.load_workspace().tags_of(&path)
    }

    pub fn paths_with_tag(&self, tag_id: String) -> Vec<String> {
        let mut out: Vec<String> = self
            .load_workspace()
            .paths_with_tag(&tag_id)
            .into_iter()
            .filter(|p| self.abs(p).exists())
            .collect();
        out.sort_by_key(|a| natural_key(a));
        out
    }

    pub fn set_tag(&self, path: String, tag_id: String, on: bool) -> Result<()> {
        let path = check(&path)?;
        self.edit_workspace(|ws| ws.set_tag(&path, &tag_id, on))
    }

    pub fn create_tag(&self, name: String, color: String) -> Result<TagInfo> {
        let tag = self
            .edit_workspace(|ws| ws.create_tag(&name, &color))?
            .map_err(DNoteError::InvalidPath)?;
        Ok(TagInfo {
            id: tag.id,
            name: tag.name,
            color: tag.color,
            count: 0,
        })
    }

    pub fn update_tag(&self, id: String, name: String, color: String) -> Result<()> {
        self.edit_workspace(|ws| ws.update_tag(&id, &name, &color))
    }

    pub fn delete_tag(&self, id: String) -> Result<()> {
        self.edit_workspace(|ws| ws.delete_tag(&id))
    }

    /// Case-insensitive search over note names and contents.
    pub fn search(&self, query: String) -> Result<Vec<SearchHit>> {
        let q = query.trim().to_lowercase();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let mut hits = Vec::new();
        for path in self.note_paths()? {
            let content = fs::read_to_string(self.abs(&path)).unwrap_or_default();
            let lower = content.to_lowercase();
            let name_hit = path.to_lowercase().contains(&q);
            let snippet = match lower.find(&q) {
                // Lowercasing can shift byte offsets for some scripts; fall back to char-safe slicing.
                Some(i) if lower.len() == content.len() => {
                    let mut start = i.saturating_sub(30);
                    while !content.is_char_boundary(start) {
                        start -= 1;
                    }
                    let mut end = (i + q.len() + 60).min(content.len());
                    while !content.is_char_boundary(end) {
                        end += 1;
                    }
                    content[start..end]
                        .split_whitespace()
                        .collect::<Vec<_>>()
                        .join(" ")
                }
                Some(_) => content
                    .chars()
                    .take(80)
                    .collect::<String>()
                    .split_whitespace()
                    .collect::<Vec<_>>()
                    .join(" "),
                None if name_hit => String::new(),
                None => continue,
            };
            hits.push(SearchHit { path, snippet });
            if hits.len() >= 200 {
                break;
            }
        }
        Ok(hits)
    }

    /// Sync with a DNote server. Blocking: call it off the main thread.
    pub fn sync(&self, server: String, token: String) -> Result<SyncSummary> {
        let _guard = self.sync_lock.lock().unwrap_or_else(|e| e.into_inner());
        let fs = DiskFs {
            vault: self,
            changed: RefCell::new(BTreeSet::new()),
        };
        let report =
            pollster::block_on(sync::sync(&fs, &Ureq::new(), &SyncConfig { server, token }))
                .map_err(DNoteError::Sync)?;
        Ok(SyncSummary {
            pulled: report.pulled,
            pushed: report.pushed,
            merged: report.merged,
            deleted_local: report.deleted_local,
            deleted_remote: report.deleted_remote,
            conflicts: report.conflicts,
            skipped: report.skipped,
            changed: fs.changed.into_inner().into_iter().collect(),
        })
    }

    /// Forget sync history (e.g. after switching servers). Notes are kept.
    pub fn reset_sync(&self) -> Result<()> {
        let p = self.root.join(META_DIR).join("state.json");
        if p.exists() {
            fs::remove_file(p)?;
        }
        Ok(())
    }
}

struct DiskFs<'a> {
    vault: &'a Vault,
    changed: RefCell<BTreeSet<String>>,
}

impl LocalFs for DiskFs<'_> {
    async fn list_files(&self) -> std::result::Result<Vec<String>, String> {
        self.vault.synced_paths().map_err(|e| e.to_string())
    }
    async fn read(&self, path: &str) -> std::result::Result<Option<Vec<u8>>, String> {
        match fs::read(self.vault.abs(path)) {
            Ok(s) => Ok(Some(s)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e.to_string()),
        }
    }
    async fn write(&self, path: &str, content: &[u8]) -> std::result::Result<(), String> {
        let path = check(path).map_err(|e| e.to_string())?;
        self.changed.borrow_mut().insert(path.clone());
        write_atomic(&self.vault.abs(&path), content).map_err(|e| e.to_string())
    }
    async fn remove(&self, path: &str) -> std::result::Result<(), String> {
        let path = check(path).map_err(|e| e.to_string())?;
        self.changed.borrow_mut().insert(path.clone());
        match fs::remove_file(self.vault.abs(&path)) {
            Ok(()) => {
                self.vault.prune_empty_parents(&path);
                Ok(())
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }
    async fn load_state(&self) -> std::result::Result<Option<String>, String> {
        match fs::read_to_string(self.vault.root.join(META_DIR).join("state.json")) {
            Ok(s) => Ok(Some(s)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e.to_string()),
        }
    }
    async fn save_state(&self, json: &str) -> std::result::Result<(), String> {
        write_atomic(&self.vault.root.join(META_DIR).join("state.json"), json)
            .map_err(|e| e.to_string())
    }
}

struct Ureq(ureq::Agent);

impl Ureq {
    fn new() -> Self {
        let config = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(30)))
            .build();
        Ureq(config.into())
    }
}

impl Http for Ureq {
    async fn send(
        &self,
        method: &str,
        url: &str,
        token: &str,
        body: Option<String>,
    ) -> std::result::Result<HttpResponse, String> {
        let auth = format!("Bearer {token}");
        let result = match (method, body) {
            ("GET", _) => self.0.get(url).header("Authorization", &auth).call(),
            ("DELETE", _) => self.0.delete(url).header("Authorization", &auth).call(),
            ("PUT", Some(b)) => self
                .0
                .put(url)
                .header("Authorization", &auth)
                .header("Content-Type", "application/json")
                .send(b),
            (m, _) => return Err(format!("unsupported method {m}")),
        };
        let mut resp = result.map_err(|e| format!("Can't reach the server: {e}"))?;
        let status = resp.status().as_u16();
        let mut text = String::new();
        resp.body_mut()
            .with_config()
            .limit(16 * 1024 * 1024)
            .reader()
            .read_to_string(&mut text)
            .map_err(|e| e.to_string())?;
        Ok(HttpResponse { status, body: text })
    }
}

/// Check that a server is reachable and accepts the token. Returns the backend name.
#[uniffi::export]
pub fn check_server(server: String, token: String) -> Result<String> {
    let server = server.trim().trim_end_matches('/').to_string();
    let http = Ureq::new();
    let health =
        pollster::block_on(http.send("GET", &format!("{server}/api/health"), &token, None))
            .map_err(DNoteError::Sync)?;
    if health.status != 200 {
        return Err(DNoteError::Sync(format!(
            "Server answered {} (is this a DNote server?)",
            health.status
        )));
    }
    let backend = serde_json::from_str::<serde_json::Value>(&health.body)
        .ok()
        .and_then(|v| {
            v.get("backend")
                .and_then(|b| b.as_str())
                .map(str::to_string)
        })
        .ok_or_else(|| DNoteError::Sync("That URL is not a DNote server".into()))?;
    let auth = pollster::block_on(http.send(
        "GET",
        &format!("{server}/api/changes?since=0"),
        &token,
        None,
    ))
    .map_err(DNoteError::Sync)?;
    match auth.status {
        200 => Ok(backend),
        401 => Err(DNoteError::Sync("The access token was rejected".into())),
        s => Err(DNoteError::Sync(format!("Server answered {s}"))),
    }
}

/// Render the note at `note_path` to safe HTML. Relative image and note links come back as
/// percent-encoded vault paths, so a web view whose base URL is the vault folder shows images.
#[uniffi::export]
pub fn render_markdown(markdown: String, note_path: String) -> String {
    dnote_core::markdown::to_html_for(&markdown, &note_path)
}

/// What Enter does in a list (see [`list_continuation`]).
#[derive(uniffi::Record)]
pub struct ListEnter {
    /// true: the item was empty, so replace the line's text before the cursor with `prefix`
    /// and don't add a newline. false: insert a newline followed by `prefix`.
    pub end_list: bool,
    pub prefix: String,
}

/// What Enter does in a list, from the current line's text before the cursor: `None` for a
/// plain newline, otherwise how to continue (`"- "`, `"3. "`, `"- [ ] "`) or end the list.
#[uniffi::export]
pub fn list_continuation(line_before_cursor: String) -> Option<ListEnter> {
    use dnote_core::markdown::ListEnter as E;
    dnote_core::markdown::list_continuation(&line_before_cursor).map(|e| match e {
        E::Continue(prefix) => ListEnter {
            end_list: false,
            prefix,
        },
        E::End(prefix) => ListEnter {
            end_list: true,
            prefix,
        },
    })
}

/// Turn a link from rendered HTML (a vault path, possibly percent-encoded) into a vault path.
#[uniffi::export]
pub fn resolve_link(note_path: String, href: String) -> Option<String> {
    dnote_core::paths::resolve_link(&note_path, &href)
}

/// Colours a tag can have.
#[uniffi::export]
pub fn tag_colors() -> Vec<String> {
    dnote_core::workspace::TAG_COLORS
        .iter()
        .map(|s| s.to_string())
        .collect()
}

/// 3-way merge, used when a sync changes the note that's open in the editor.
#[uniffi::export]
pub fn merge3(base: String, ours: String, theirs: String) -> String {
    dnote_core::merge::merge3(&base, &ours, &theirs).text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vault_operations() {
        let dir = tempfile::tempdir().unwrap();
        let v = Vault::open(dir.path().to_string_lossy().into()).unwrap();
        v.create_folder("Projects".into()).unwrap();
        let p = v.create_note("Projects".into(), "Plan".into()).unwrap();
        assert_eq!(p, "Projects/Plan.md");
        assert_eq!(
            v.create_note("".into(), "".into()).unwrap(),
            "Untitled note (1).md"
        );
        assert_eq!(
            v.create_note("".into(), "  ".into()).unwrap(),
            "Untitled note (2).md"
        );
        assert_eq!(
            v.rename_note_title("Untitled note (2).md".into(), "Ideas / later".into())
                .unwrap(),
            "Ideas - later.md"
        );
        assert_eq!(
            v.rename_note_title("Ideas - later.md".into(), "".into())
                .unwrap(),
            "Ideas - later.md"
        );
        v.delete("Untitled note (1).md".into()).unwrap();
        v.delete("Ideas - later.md".into()).unwrap();
        v.create_note("".into(), "Note 10".into()).unwrap();
        v.create_note("".into(), "Note 2".into()).unwrap();
        assert!(matches!(
            v.create_note("".into(), "Note 2".into()),
            Err(DNoteError::AlreadyExists(_))
        ));
        assert!(matches!(
            v.write("../escape.md".into(), "x".into()),
            Err(DNoteError::InvalidPath(_))
        ));
        let names: Vec<_> = v.entries().unwrap().into_iter().map(|e| e.path).collect();
        // Pins and tags follow renames and deletes.
        v.toggle_pin("Projects".into()).unwrap();
        v.set_tag("Projects/Plan.md".into(), "favorite".into(), true)
            .unwrap();
        let work = v.create_tag("Work".into(), "blue".into()).unwrap();
        v.set_tag("Note 2.md".into(), work.id.clone(), true)
            .unwrap();
        assert_eq!(v.tags().iter().map(|t| t.count).collect::<Vec<_>>(), [1, 1]);
        let link = v
            .add_image(
                "Projects/Plan.md".into(),
                "My photo.png".into(),
                vec![1, 2, 3],
            )
            .unwrap();
        assert_eq!(link, "![](../Attachments/My%20photo.png)");
        assert_eq!(
            v.read_bytes("Attachments/My photo.png".into()).unwrap(),
            [1, 2, 3]
        );
        assert!(v.entries().unwrap().iter().any(|e| e.is_image));
        assert_eq!(
            v.rename(
                "Attachments/My photo.png".into(),
                "Attachments/cover".into()
            )
            .unwrap(),
            "Attachments/cover.png"
        );
        v.write(
            "Projects/Other.md".into(),
            "# Other\n\nShip **sync** [now](x.md)\n".into(),
        )
        .unwrap();
        assert_eq!(v.preview("Projects/Other.md".into()), "Ship sync now");
        v.delete("Projects/Other.md".into()).unwrap();
        v.delete("Attachments".into()).unwrap();
        assert_eq!(
            names,
            ["Projects", "Projects/Plan.md", "Note 2.md", "Note 10.md"]
        );
        let moved = v.rename("Projects".into(), "Work/Projects".into()).unwrap();
        assert_eq!(v.pins(), ["Work/Projects"]);
        assert_eq!(
            v.paths_with_tag("favorite".into()),
            ["Work/Projects/Plan.md"]
        );
        assert_eq!(v.read(format!("{moved}/Plan.md")).unwrap(), "");
        assert_eq!(v.search("plan".into()).unwrap().len(), 1);
        v.delete("Work".into()).unwrap();
        assert_eq!(v.entries().unwrap().len(), 2);
        assert!(v.pins().is_empty());
        assert_eq!(v.tags()[0].count, 0);
    }
}

/// First-run note. Identical on every platform, so two fresh devices merge cleanly.
#[uniffi::export]
pub fn welcome_note() -> String {
    "# Welcome to DNote

Notes are plain **Markdown** files in folders, stored on this device first.

- Create notes and folders with the **+** button
- Switch between *Write* and *Read* at the top of a note
- [ ] Connect a server in Settings to sync with your other devices

Sync is optional: DNote works fully offline.
"
    .to_string()
}
