use serde::{Deserialize, Serialize};

/// Server-side metadata for one note. Deleted notes are kept as tombstones so
/// other devices learn about the deletion.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileMeta {
    pub path: String,
    /// Monotonic per-file version, starts at 1. Writes must name the version they are based on.
    pub version: i64,
    /// SHA-256 of the content, empty for tombstones.
    pub hash: String,
    pub size: i64,
    pub deleted: bool,
    /// Global change sequence number; `GET /api/changes?since=` pages through it.
    pub seq: i64,
    /// Unix milliseconds.
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChangesResponse {
    /// Pass this back as `since` on the next call.
    pub seq: i64,
    pub files: Vec<FileMeta>,
    /// More changes are available; call again with `seq`.
    pub more: bool,
}

/// File body: text files (notes, workspace) travel as `content`, images as
/// `content_base64`. Exactly one is set.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileResponse {
    pub meta: FileMeta,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content_base64: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PutRequest {
    /// Version this edit is based on; 0 for a brand-new path.
    pub base_version: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content_base64: Option<String>,
}

impl PutRequest {
    pub fn new(path: &str, base_version: i64, bytes: &[u8]) -> Self {
        if crate::paths::is_text(path) {
            PutRequest {
                base_version,
                content: Some(String::from_utf8_lossy(bytes).into_owned()),
                content_base64: None,
            }
        } else {
            PutRequest {
                base_version,
                content: None,
                content_base64: Some(crate::base64_encode(bytes)),
            }
        }
    }

    pub fn bytes(&self) -> Result<Vec<u8>, String> {
        match (&self.content, &self.content_base64) {
            (Some(text), None) => Ok(text.clone().into_bytes()),
            (None, Some(b64)) => crate::base64_decode(b64),
            _ => Err("set exactly one of content / content_base64".into()),
        }
    }
}

impl FileResponse {
    pub fn new(meta: FileMeta, bytes: Vec<u8>) -> Self {
        if crate::paths::is_text(&meta.path) {
            let text = String::from_utf8(bytes)
                .unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned());
            FileResponse {
                meta,
                content: Some(text),
                content_base64: None,
            }
        } else {
            let b64 = crate::base64_encode(&bytes);
            FileResponse {
                meta,
                content: None,
                content_base64: Some(b64),
            }
        }
    }

    pub fn bytes(&self) -> Result<Vec<u8>, String> {
        match (&self.content, &self.content_base64) {
            (Some(text), _) => Ok(text.clone().into_bytes()),
            (None, Some(b64)) => crate::base64_decode(b64),
            (None, None) => Err("response has no content".into()),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConflictResponse {
    pub error: String,
    pub current: Option<FileMeta>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorResponse {
    pub error: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthResponse {
    pub ok: bool,
    pub name: String,
    pub version: String,
    pub backend: String,
}

/// Maximum note size accepted by the API (5 MiB).
pub const MAX_NOTE_BYTES: usize = 5 * 1024 * 1024;
/// Maximum image size accepted by the API (15 MiB).
pub const MAX_ATTACHMENT_BYTES: usize = 15 * 1024 * 1024;
/// Request body limit for servers (base64 of the largest attachment, plus JSON overhead).
pub const MAX_BODY_BYTES: usize = MAX_ATTACHMENT_BYTES / 3 * 4 + 64 * 1024;
/// Page size for `/api/changes`.
pub const CHANGES_PAGE: i64 = 1000;
