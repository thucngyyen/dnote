/// The one synced file under the reserved `.dnote/` folder: pins and tags.
pub const WORKSPACE_PATH: &str = ".dnote/workspace.json";

/// Folder new images are saved to.
pub const ATTACHMENTS_DIR: &str = "Attachments";

/// Validate and normalise a vault path such as `Projects/DNote/ideas.md`.
///
/// Rules: relative, `/`-separated, no empty / `.` / `..` segments, no
/// backslashes or control characters, at most 1024 bytes. Segments starting with
/// `.dnote` are reserved for client metadata, except [`WORKSPACE_PATH`].
pub fn normalize(path: &str) -> Result<String, String> {
    let trimmed = path.trim().trim_matches('/');
    if trimmed == WORKSPACE_PATH {
        return Ok(WORKSPACE_PATH.to_string());
    }
    if trimmed.is_empty() {
        return Err("path is empty".into());
    }
    if trimmed.len() > 1024 {
        return Err("path is too long".into());
    }
    let mut parts = Vec::new();
    for seg in trimmed.split('/') {
        if seg.is_empty() || seg == "." || seg == ".." {
            return Err(format!("invalid path segment {seg:?}"));
        }
        if seg.starts_with(".dnote") {
            return Err("paths starting with .dnote are reserved".into());
        }
        if seg.chars().any(|c| c.is_control() || c == '\\' || c == ':') {
            return Err("path contains forbidden characters".into());
        }
        if seg.len() > 255 {
            return Err("path segment is too long".into());
        }
        parts.push(seg);
    }
    Ok(parts.join("/"))
}

fn ext(path: &str) -> String {
    let name = path.rsplit('/').next().unwrap_or(path);
    match name.rfind('.') {
        Some(i) if i > 0 => name[i + 1..].to_ascii_lowercase(),
        _ => String::new(),
    }
}

/// Markdown/text notes: merged line by line when edited on two devices.
pub fn is_note(path: &str) -> bool {
    matches!(ext(path).as_str(), "md" | "markdown" | "txt")
}

/// Images that can be embedded in notes. Synced as opaque bytes.
pub fn is_attachment(path: &str) -> bool {
    matches!(
        ext(path).as_str(),
        "png" | "jpg" | "jpeg" | "gif" | "webp" | "svg" | "avif" | "heic"
    )
}

/// Files whose content is UTF-8 text (notes and the workspace file).
pub fn is_text(path: &str) -> bool {
    is_note(path) || path == WORKSPACE_PATH
}

/// Everything the sync engine moves between devices.
pub fn is_synced(path: &str) -> bool {
    is_text(path) || is_attachment(path)
}

/// MIME type for an attachment, by extension.
pub fn mime_type(path: &str) -> &'static str {
    match ext(path).as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "svg" => "image/svg+xml",
        "avif" => "image/avif",
        "heic" => "image/heic",
        "md" | "markdown" => "text/markdown; charset=utf-8",
        _ => "application/octet-stream",
    }
}

/// Name for a new note in `folder` when none is given: "Untitled note (1)", "(2)", ...
/// (the lowest number not already taken). Returns the full path including ".md".
pub fn next_untitled(existing: &[String], folder: &str) -> String {
    let prefix = if folder.is_empty() {
        String::new()
    } else {
        format!("{folder}/")
    };
    let taken: std::collections::HashSet<String> =
        existing.iter().map(|p| p.to_lowercase()).collect();
    (1..)
        .map(|n| format!("{prefix}Untitled note ({n}).md"))
        .find(|p| !taken.contains(&p.to_lowercase()))
        .unwrap()
}

/// Turn what someone typed into a note's title field into a safe file name (no extension):
/// slashes and other characters that can't be in a path segment are replaced.
pub fn title_to_name(title: &str) -> String {
    let cleaned: String = title
        .trim()
        .chars()
        .map(|c| {
            if c == '/' || c == '\\' || c == ':' || c.is_control() {
                '-'
            } else {
                c
            }
        })
        .collect();
    let cleaned = cleaned.trim_start_matches('.').trim().to_string();
    cleaned.chars().take(200).collect()
}

/// Parent folder of a path ("" for the top level).
pub fn parent(path: &str) -> &str {
    path.rfind('/').map(|i| &path[..i]).unwrap_or("")
}

/// Resolve a link written inside the note at `note_path` (e.g. `../Attachments/a.png`)
/// to a vault path. `None` for external URLs, anchors, or links escaping the vault.
pub fn resolve_link(note_path: &str, href: &str) -> Option<String> {
    let href = href.split(['#', '?']).next().unwrap_or("");
    if href.is_empty()
        || href.contains("://")
        || href.starts_with("mailto:")
        || href.starts_with("tel:")
    {
        return None;
    }
    let decoded = percent_decode(href);
    let mut parts: Vec<&str> = if decoded.starts_with('/') {
        Vec::new()
    } else {
        parent(note_path)
            .split('/')
            .filter(|s| !s.is_empty())
            .collect()
    };
    for seg in decoded.split('/') {
        match seg {
            "" | "." => {}
            ".." => {
                parts.pop()?;
            }
            s => parts.push(s),
        }
    }
    normalize(&parts.join("/")).ok()
}

/// Relative link from the note at `note_path` to `target` (both vault paths), with spaces escaped
/// so it works in Markdown, e.g. `../Attachments/My%20photo.jpg`.
pub fn relative_link(note_path: &str, target: &str) -> String {
    let from: Vec<&str> = parent(note_path)
        .split('/')
        .filter(|s| !s.is_empty())
        .collect();
    let to: Vec<&str> = target.split('/').collect();
    let common = from.iter().zip(&to).take_while(|(a, b)| a == b).count();
    let mut out: Vec<String> = std::iter::repeat_n("..".to_string(), from.len() - common).collect();
    out.extend(to[common..].iter().map(|s| {
        s.replace('%', "%25")
            .replace(' ', "%20")
            .replace('(', "%28")
            .replace(')', "%29")
    }));
    out.join("/")
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_nested() {
        assert_eq!(normalize("/a/b c/note.md").unwrap(), "a/b c/note.md");
        assert_eq!(normalize(WORKSPACE_PATH).unwrap(), WORKSPACE_PATH);
    }

    #[test]
    fn rejects_traversal() {
        for bad in [
            "",
            "../x.md",
            "a/../b.md",
            "a//b.md",
            "a\\b.md",
            ".dnote/state.json",
            "C:/x.md",
        ] {
            assert!(normalize(bad).is_err(), "{bad} should be rejected");
        }
    }

    #[test]
    fn kinds() {
        assert!(is_note("a/B.MD") && is_text(WORKSPACE_PATH) && is_attachment("Attachments/x.JPG"));
        assert!(!is_synced("x.exe") && !is_synced("README"));
    }

    #[test]
    fn untitled_names() {
        let existing = vec![
            "Untitled note (1).md".to_string(),
            "Untitled note (3).md".to_string(),
            "A/Untitled note (1).md".to_string(),
        ];
        assert_eq!(next_untitled(&existing, ""), "Untitled note (2).md");
        assert_eq!(next_untitled(&existing, "A"), "A/Untitled note (2).md");
        assert_eq!(next_untitled(&[], "B"), "B/Untitled note (1).md");
        assert_eq!(title_to_name("  Plan: Q1/Q2  "), "Plan- Q1-Q2");
        assert_eq!(title_to_name(".hidden"), "hidden");
    }

    #[test]
    fn links() {
        assert_eq!(
            relative_link("Projects/Plan.md", "Attachments/my photo.jpg"),
            "../Attachments/my%20photo.jpg"
        );
        assert_eq!(
            relative_link("Plan.md", "Attachments/a.png"),
            "Attachments/a.png"
        );
        assert_eq!(
            resolve_link("Projects/Plan.md", "../Attachments/my%20photo.jpg").unwrap(),
            "Attachments/my photo.jpg"
        );
        assert_eq!(
            resolve_link("Projects/Plan.md", "Other.md#x").unwrap(),
            "Projects/Other.md"
        );
        assert_eq!(
            resolve_link("Plan.md", "/Journal/Day.md").unwrap(),
            "Journal/Day.md"
        );
        assert!(resolve_link("Plan.md", "../../x.md").is_none());
        assert!(resolve_link("Plan.md", "https://x.dev/a.png").is_none());
    }
}
