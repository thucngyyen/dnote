//! DNote core: everything that is not I/O.
//!
//! * [`protocol`]: wire types shared by servers and clients.
//! * [`paths`]: path validation (no traversal, no hidden control dirs) and link helpers.
//! * [`api`]: the whole HTTP API as a pure function over a [`api::Store`]; the
//!   self-hosted server and the Cloudflare Worker are thin adapters.
//! * [`sync`]: the client sync engine (pull, 3-way merge, push).
//! * [`workspace`]: pins and tags.

pub mod paths;
pub mod protocol;
#[cfg(feature = "client")]
pub mod workspace;

#[cfg(feature = "server")]
pub mod api;
#[cfg(feature = "server")]
pub mod sql;

#[cfg(feature = "client")]
pub mod markdown;
#[cfg(feature = "client")]
pub mod merge;
#[cfg(feature = "client")]
pub mod sync;

use sha2::{Digest, Sha256};

/// Hex-encoded SHA-256 of a file's bytes (for notes: the UTF-8 text). Used as the
/// content identity everywhere (change detection, content-addressed blobs on R2).
pub fn content_hash(content: impl AsRef<[u8]>) -> String {
    let digest = Sha256::digest(content.as_ref());
    let mut out = String::with_capacity(64);
    for b in digest {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

pub fn base64_encode(bytes: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

pub fn base64_decode(s: &str) -> Result<Vec<u8>, String> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD
        .decode(s.trim())
        .map_err(|e| format!("invalid base64: {e}"))
}

#[cfg(test)]
mod tests {
    #[test]
    fn hash_is_stable() {
        assert_eq!(
            super::content_hash(""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }
}
