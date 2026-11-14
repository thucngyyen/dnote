//! Browser bindings for the DNote client core.
//!
//! The web app supplies storage as a plain JS object (backed by IndexedDB) with
//! async methods `listNotes, read, write, remove, loadState, saveState`; HTTP goes
//! through `fetch`. All sync logic is the shared Rust engine.

use dnote_core::sync::{self, Http, HttpResponse, LocalFs, SyncConfig};
use js_sys::{Function, Promise, Reflect};
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;

fn js_err(v: JsValue) -> String {
    v.as_string()
        .or_else(|| {
            Reflect::get(&v, &"message".into())
                .ok()
                .and_then(|m| m.as_string())
        })
        .unwrap_or_else(|| format!("{v:?}"))
}

struct JsFs {
    obj: JsValue,
}

impl JsFs {
    async fn call(&self, name: &str, args: &[JsValue]) -> Result<JsValue, String> {
        let f: Function = Reflect::get(&self.obj, &name.into())
            .map_err(js_err)?
            .dyn_into()
            .map_err(|_| format!("storage.{name} is not a function"))?;
        let ret = match args {
            [] => f.call0(&self.obj),
            [a] => f.call1(&self.obj, a),
            [a, b] => f.call2(&self.obj, a, b),
            _ => unreachable!(),
        }
        .map_err(js_err)?;
        if ret.is_instance_of::<Promise>() {
            JsFuture::from(Promise::from(ret)).await.map_err(js_err)
        } else {
            Ok(ret)
        }
    }
}

impl LocalFs for JsFs {
    async fn list_files(&self) -> Result<Vec<String>, String> {
        let v = self.call("listFiles", &[]).await?;
        serde_wasm_bindgen::from_value(v).map_err(|e| e.to_string())
    }
    /// Text files come back from JS as strings, images as Uint8Array / ArrayBuffer / Blob.
    async fn read(&self, path: &str) -> Result<Option<Vec<u8>>, String> {
        let v = self.call("read", &[path.into()]).await?;
        if v.is_undefined() || v.is_null() {
            return Ok(None);
        }
        if let Some(s) = v.as_string() {
            return Ok(Some(s.into_bytes()));
        }
        if v.is_instance_of::<web_sys::Blob>() {
            let buf = JsFuture::from(v.unchecked_ref::<web_sys::Blob>().array_buffer())
                .await
                .map_err(js_err)?;
            return Ok(Some(js_sys::Uint8Array::new(&buf).to_vec()));
        }
        Ok(Some(js_sys::Uint8Array::new(&v).to_vec()))
    }
    /// Text files are handed to JS as strings, images as Uint8Array.
    async fn write(&self, path: &str, content: &[u8]) -> Result<(), String> {
        let value: JsValue = if dnote_core::paths::is_text(path) {
            String::from_utf8_lossy(content).into_owned().into()
        } else {
            js_sys::Uint8Array::from(content).into()
        };
        self.call("write", &[path.into(), value]).await.map(|_| ())
    }
    async fn remove(&self, path: &str) -> Result<(), String> {
        self.call("remove", &[path.into()]).await.map(|_| ())
    }
    async fn load_state(&self) -> Result<Option<String>, String> {
        Ok(self.call("loadState", &[]).await?.as_string())
    }
    async fn save_state(&self, json: &str) -> Result<(), String> {
        self.call("saveState", &[json.into()]).await.map(|_| ())
    }
}

struct Fetch;

#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(js_name = fetch)]
    fn global_fetch(req: &web_sys::Request) -> Promise;
}

impl Http for Fetch {
    async fn send(
        &self,
        method: &str,
        url: &str,
        token: &str,
        body: Option<String>,
    ) -> Result<HttpResponse, String> {
        let init = web_sys::RequestInit::new();
        init.set_method(method);
        init.set_mode(web_sys::RequestMode::Cors);
        let headers = web_sys::Headers::new().map_err(js_err)?;
        headers
            .set("Authorization", &format!("Bearer {token}"))
            .map_err(js_err)?;
        if let Some(b) = &body {
            headers
                .set("Content-Type", "application/json")
                .map_err(js_err)?;
            init.set_body(&JsValue::from_str(b));
        }
        init.set_headers(&headers);
        let req = web_sys::Request::new_with_str_and_init(url, &init).map_err(js_err)?;
        let resp: web_sys::Response = JsFuture::from(global_fetch(&req))
            .await
            .map_err(|e| format!("network error: {}", js_err(e)))?
            .dyn_into()
            .map_err(js_err)?;
        let text = JsFuture::from(resp.text().map_err(js_err)?)
            .await
            .map_err(js_err)?;
        Ok(HttpResponse {
            status: resp.status(),
            body: text.as_string().unwrap_or_default(),
        })
    }
}

/// Run a full sync. Resolves to a `SyncReport` object, rejects with an error string.
#[wasm_bindgen]
pub async fn sync(storage: JsValue, server: String, token: String) -> Result<JsValue, JsValue> {
    let fs = JsFs { obj: storage };
    let report = sync::sync(&fs, &Fetch, &SyncConfig { server, token })
        .await
        .map_err(|e| JsValue::from_str(&e))?;
    serde_wasm_bindgen::to_value(&report).map_err(|e| JsValue::from_str(&e.to_string()))
}

/// Render the note at `notePath`. Relative image/note links become percent-encoded vault paths.
#[wasm_bindgen(js_name = renderMarkdown)]
pub fn render_markdown(markdown: &str, note_path: &str) -> String {
    dnote_core::markdown::to_html_for(markdown, note_path)
}

#[wasm_bindgen(js_name = contentHash)]
pub fn content_hash(content: &str) -> String {
    dnote_core::content_hash(content)
}

/// Plain-text preview of a note for list views.
#[wasm_bindgen]
pub fn excerpt(markdown: &str, max_chars: usize) -> String {
    dnote_core::markdown::excerpt(markdown, max_chars)
}

/// Vault path for a link inside the note at `notePath`, or `undefined` for external links.
#[wasm_bindgen(js_name = resolveLink)]
pub fn resolve_link(note_path: &str, href: &str) -> Option<String> {
    dnote_core::paths::resolve_link(note_path, href)
}

/// Relative Markdown link from `notePath` to `target`.
#[wasm_bindgen(js_name = relativeLink)]
pub fn relative_link(note_path: &str, target: &str) -> String {
    dnote_core::paths::relative_link(note_path, target)
}

/// "Untitled note (n).md" path for a new note in `folder`, given the existing note paths.
#[wasm_bindgen(js_name = nextUntitled)]
pub fn next_untitled(existing: Vec<String>, folder: &str) -> String {
    dnote_core::paths::next_untitled(&existing, folder)
}

/// Safe file name (no extension) for what was typed in a note's title field.
#[wasm_bindgen(js_name = titleToName)]
pub fn title_to_name(title: &str) -> String {
    dnote_core::paths::title_to_name(title)
}

#[wasm_bindgen(js_name = isImage)]
pub fn is_image(path: &str) -> bool {
    dnote_core::paths::is_attachment(path)
}

#[wasm_bindgen(js_name = mimeType)]
pub fn mime_type(path: &str) -> String {
    dnote_core::paths::mime_type(path).to_string()
}

#[wasm_bindgen(js_name = workspacePath)]
pub fn workspace_path() -> String {
    dnote_core::paths::WORKSPACE_PATH.to_string()
}

#[wasm_bindgen(js_name = tagColors)]
pub fn tag_colors() -> Vec<String> {
    dnote_core::workspace::TAG_COLORS
        .iter()
        .map(|s| s.to_string())
        .collect()
}

/// Pins and tags (the synced `.dnote/workspace.json`).
#[wasm_bindgen]
pub struct Workspace(dnote_core::workspace::Workspace);

#[derive(serde::Serialize)]
struct TagJs {
    id: String,
    name: String,
    color: String,
}

#[wasm_bindgen]
impl Workspace {
    #[wasm_bindgen(constructor)]
    pub fn new(json: Option<String>) -> Workspace {
        Workspace(dnote_core::workspace::Workspace::parse(
            json.as_deref().unwrap_or(""),
        ))
    }
    #[wasm_bindgen(js_name = toJson)]
    pub fn to_json(&self) -> String {
        self.0.to_json()
    }
    pub fn pins(&self) -> Vec<String> {
        self.0.pins.clone()
    }
    #[wasm_bindgen(js_name = isPinned)]
    pub fn is_pinned(&self, path: &str) -> bool {
        self.0.is_pinned(path)
    }
    #[wasm_bindgen(js_name = togglePin)]
    pub fn toggle_pin(&mut self, path: &str) -> bool {
        self.0.toggle_pin(path)
    }
    /// `[{id, name, color}]`, Favorite first.
    pub fn tags(&self) -> JsValue {
        let tags: Vec<TagJs> = self
            .0
            .tags()
            .into_iter()
            .map(|t| TagJs {
                id: t.id,
                name: t.name,
                color: t.color,
            })
            .collect();
        serde_wasm_bindgen::to_value(&tags).unwrap_or(JsValue::NULL)
    }
    #[wasm_bindgen(js_name = tagsOf)]
    pub fn tags_of(&self, path: &str) -> Vec<String> {
        self.0.tags_of(path)
    }
    #[wasm_bindgen(js_name = pathsWithTag)]
    pub fn paths_with_tag(&self, tag_id: &str) -> Vec<String> {
        self.0.paths_with_tag(tag_id)
    }
    #[wasm_bindgen(js_name = setTag)]
    pub fn set_tag(&mut self, path: &str, tag_id: &str, on: bool) {
        self.0.set_tag(path, tag_id, on)
    }
    /// Returns the new tag's id.
    #[wasm_bindgen(js_name = createTag)]
    pub fn create_tag(&mut self, name: &str, color: &str) -> Result<String, JsValue> {
        self.0
            .create_tag(name, color)
            .map(|t| t.id)
            .map_err(|e| JsValue::from_str(&e))
    }
    #[wasm_bindgen(js_name = updateTag)]
    pub fn update_tag(&mut self, id: &str, name: &str, color: &str) {
        self.0.update_tag(id, name, color)
    }
    #[wasm_bindgen(js_name = deleteTag)]
    pub fn delete_tag(&mut self, id: &str) {
        self.0.delete_tag(id)
    }
    #[wasm_bindgen(js_name = renamePath)]
    pub fn rename_path(&mut self, from: &str, to: &str) {
        self.0.rename_path(from, to)
    }
    #[wasm_bindgen(js_name = removePath)]
    pub fn remove_path(&mut self, path: &str) {
        self.0.remove_path(path)
    }
}

/// 3-way merge; used when a sync changes the note that is open in the editor.
#[wasm_bindgen]
pub fn merge3(base: &str, ours: &str, theirs: &str) -> String {
    dnote_core::merge::merge3(base, ours, theirs).text
}

/// Returns an error message, or `undefined` if the path is valid.
#[wasm_bindgen(js_name = validatePath)]
pub fn validate_path(path: &str) -> Option<String> {
    dnote_core::paths::normalize(path).err()
}
