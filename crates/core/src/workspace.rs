//! Pins and tags, stored in the synced file [`crate::paths::WORKSPACE_PATH`].
//!
//! Kept out of the notes themselves so Markdown stays clean. Every device edits it,
//! so instead of merging it line by line it is merged as a set of facts
//! ("X is pinned", "tag T exists", "X has tag T"): a fact added on either side is
//! kept, a fact removed on either side is dropped. Two devices pinning, tagging and
//! creating tags at the same time never conflict.

use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

pub const FAVORITE: &str = "favorite";

/// Colours a tag can have (rendered by each client from its own palette).
pub const TAG_COLORS: [&str; 7] = ["amber", "blue", "green", "red", "violet", "pink", "slate"];

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Tag {
    pub id: String,
    pub name: String,
    pub color: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct Workspace {
    #[serde(default)]
    pub version: u32,
    /// Pinned notes and folders, in the order they were pinned.
    #[serde(default)]
    pub pins: Vec<String>,
    /// User tags. "Favorite" is implicit and always first (see [`Workspace::tags`]).
    #[serde(default)]
    pub tags: Vec<Tag>,
    /// path -> tag ids
    #[serde(default)]
    pub tagged: BTreeMap<String, BTreeSet<String>>,
}

impl Workspace {
    pub fn parse(json: &str) -> Workspace {
        if json.trim().is_empty() {
            return Workspace::default();
        }
        serde_json::from_str(json).unwrap_or_default()
    }

    /// Stable, pretty JSON (sorted maps), so unchanged data produces identical bytes.
    pub fn to_json(&self) -> String {
        let mut w = self.clone();
        w.version = 1;
        w.tagged.retain(|_, ids| !ids.is_empty());
        let mut out = serde_json::to_string_pretty(&w).unwrap_or_default();
        out.push('\n');
        out
    }

    pub fn favorite() -> Tag {
        Tag {
            id: FAVORITE.into(),
            name: "Favorite".into(),
            color: "amber".into(),
        }
    }

    /// All tags, Favorite first.
    pub fn tags(&self) -> Vec<Tag> {
        let mut out = vec![Self::favorite()];
        out.extend(self.tags.iter().filter(|t| t.id != FAVORITE).cloned());
        out
    }

    pub fn is_pinned(&self, path: &str) -> bool {
        self.pins.iter().any(|p| p == path)
    }

    /// Returns the new state.
    pub fn toggle_pin(&mut self, path: &str) -> bool {
        if self.is_pinned(path) {
            self.pins.retain(|p| p != path);
            false
        } else {
            self.pins.push(path.to_string());
            true
        }
    }

    pub fn tags_of(&self, path: &str) -> Vec<String> {
        self.tagged
            .get(path)
            .map(|s| s.iter().cloned().collect())
            .unwrap_or_default()
    }

    pub fn paths_with_tag(&self, tag_id: &str) -> Vec<String> {
        self.tagged
            .iter()
            .filter(|(_, ids)| ids.contains(tag_id))
            .map(|(p, _)| p.clone())
            .collect()
    }

    pub fn set_tag(&mut self, path: &str, tag_id: &str, on: bool) {
        let ids = self.tagged.entry(path.to_string()).or_default();
        if on {
            ids.insert(tag_id.to_string());
        } else {
            ids.remove(tag_id);
        }
        if ids.is_empty() {
            self.tagged.remove(path);
        }
    }

    /// Create a tag (or return the existing one with the same name).
    pub fn create_tag(&mut self, name: &str, color: &str) -> Result<Tag, String> {
        let name = name.trim();
        if name.is_empty() {
            return Err("tag name is empty".into());
        }
        if name.chars().count() > 40 {
            return Err("tag name is too long".into());
        }
        if let Some(t) = self
            .tags()
            .into_iter()
            .find(|t| t.name.eq_ignore_ascii_case(name))
        {
            return Ok(t);
        }
        let mut id: String = name
            .to_lowercase()
            .chars()
            .map(|c| if c.is_alphanumeric() { c } else { '-' })
            .collect::<String>()
            .split('-')
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join("-");
        if id.is_empty() {
            id = "tag".into();
        }
        let base = id.clone();
        let mut n = 2;
        while self.tags().iter().any(|t| t.id == id) {
            id = format!("{base}-{n}");
            n += 1;
        }
        let color = if TAG_COLORS.contains(&color) {
            color
        } else {
            TAG_COLORS[self.tags.len() % TAG_COLORS.len()]
        };
        let tag = Tag {
            id,
            name: name.to_string(),
            color: color.to_string(),
        };
        self.tags.push(tag.clone());
        Ok(tag)
    }

    pub fn update_tag(&mut self, id: &str, name: &str, color: &str) {
        if let Some(t) = self.tags.iter_mut().find(|t| t.id == id) {
            if !name.trim().is_empty() {
                t.name = name.trim().to_string();
            }
            if TAG_COLORS.contains(&color) {
                t.color = color.to_string();
            }
        }
    }

    pub fn delete_tag(&mut self, id: &str) {
        if id == FAVORITE {
            return;
        }
        self.tags.retain(|t| t.id != id);
        for ids in self.tagged.values_mut() {
            ids.remove(id);
        }
        self.tagged.retain(|_, ids| !ids.is_empty());
    }

    /// A note or folder moved: carry its pins and tags along (folders: everything inside).
    pub fn rename_path(&mut self, from: &str, to: &str) {
        let moved = |p: &str| -> Option<String> {
            if p == from {
                Some(to.to_string())
            } else {
                p.strip_prefix(&format!("{from}/"))
                    .map(|rest| format!("{to}/{rest}"))
            }
        };
        for p in self.pins.iter_mut() {
            if let Some(n) = moved(p) {
                *p = n;
            }
        }
        let entries: Vec<_> = self
            .tagged
            .keys()
            .filter_map(|p| moved(p).map(|n| (p.clone(), n)))
            .collect();
        for (old, new) in entries {
            if let Some(ids) = self.tagged.remove(&old) {
                self.tagged.entry(new).or_default().extend(ids);
            }
        }
    }

    /// A note or folder was deleted: drop its pins and tags.
    pub fn remove_path(&mut self, path: &str) {
        let inside = |p: &str| p == path || p.starts_with(&format!("{path}/"));
        self.pins.retain(|p| !inside(p));
        self.tagged.retain(|p, _| !inside(p));
    }

    fn facts(&self) -> BTreeSet<String> {
        let mut f = BTreeSet::new();
        for p in &self.pins {
            f.insert(format!("pin\u{0}{p}"));
        }
        for t in &self.tags {
            f.insert(format!("tag\u{0}{}\u{0}{}\u{0}{}", t.id, t.name, t.color));
        }
        for (p, ids) in &self.tagged {
            for id in ids {
                f.insert(format!("tagged\u{0}{p}\u{0}{id}"));
            }
        }
        f
    }
}

/// 3-way merge of workspace files (see module docs). Pin order: ours, then new pins from theirs.
pub fn merge(base: &str, ours: &str, theirs: &str) -> String {
    let (b, o, t) = (
        Workspace::parse(base),
        Workspace::parse(ours),
        Workspace::parse(theirs),
    );
    let (fb, fo, ft) = (b.facts(), o.facts(), t.facts());
    let keep = |fact: &String| -> bool {
        let in_base = fb.contains(fact);
        let (in_o, in_t) = (fo.contains(fact), ft.contains(fact));
        if in_base {
            in_o && in_t
        } else {
            in_o || in_t
        }
    };

    let mut out = Workspace::default();
    for p in o.pins.iter().chain(t.pins.iter()) {
        if keep(&format!("pin\u{0}{p}")) && !out.pins.contains(p) {
            out.pins.push(p.clone());
        }
    }
    // Tags are keyed by id; if both sides renamed/recoloured one, theirs wins.
    let mut tags: BTreeMap<String, Tag> = BTreeMap::new();
    for tag in o.tags.iter().chain(t.tags.iter()) {
        if keep(&format!(
            "tag\u{0}{}\u{0}{}\u{0}{}",
            tag.id, tag.name, tag.color
        )) {
            tags.insert(tag.id.clone(), tag.clone());
        }
    }
    let order: Vec<String> = o
        .tags
        .iter()
        .chain(t.tags.iter())
        .map(|t| t.id.clone())
        .collect();
    for id in order {
        if let Some(tag) = tags.remove(&id) {
            out.tags.push(tag);
        }
    }
    let live: BTreeSet<String> = out
        .tags
        .iter()
        .map(|t| t.id.clone())
        .chain([FAVORITE.to_string()])
        .collect();
    for ws in [&o, &t] {
        for (p, ids) in &ws.tagged {
            for id in ids {
                if live.contains(id) && keep(&format!("tagged\u{0}{p}\u{0}{id}")) {
                    out.tagged.entry(p.clone()).or_default().insert(id.clone());
                }
            }
        }
    }
    out.to_json()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn favorite_is_builtin() {
        let mut w = Workspace::default();
        assert_eq!(w.tags()[0].id, FAVORITE);
        w.delete_tag(FAVORITE);
        assert_eq!(w.tags().len(), 1);
        let t = w.create_tag("Work stuff!", "blue").unwrap();
        assert_eq!(t.id, "work-stuff");
        assert_eq!(w.create_tag("work STUFF!", "red").unwrap().id, "work-stuff");
    }

    #[test]
    fn rename_and_remove_follow_paths() {
        let mut w = Workspace::default();
        w.toggle_pin("Projects");
        w.set_tag("Projects/Plan.md", FAVORITE, true);
        w.rename_path("Projects", "Work/Projects");
        assert_eq!(w.pins, ["Work/Projects"]);
        assert_eq!(w.tags_of("Work/Projects/Plan.md"), [FAVORITE]);
        w.remove_path("Work");
        assert!(w.pins.is_empty() && w.tagged.is_empty());
    }

    #[test]
    fn concurrent_edits_merge() {
        let mut base = Workspace::default();
        base.toggle_pin("A.md");
        base.set_tag("A.md", FAVORITE, true);
        let b = base.to_json();

        let mut ours = base.clone();
        ours.toggle_pin("B.md");
        ours.set_tag("A.md", FAVORITE, false); // un-favorite on this device
        let work = ours.create_tag("Work", "blue").unwrap();
        ours.set_tag("B.md", &work.id, true);

        let mut theirs = base.clone();
        theirs.toggle_pin("A.md"); // unpin on the other device
        theirs.toggle_pin("C.md");
        theirs.set_tag("C.md", FAVORITE, true);

        let merged = Workspace::parse(&merge(&b, &ours.to_json(), &theirs.to_json()));
        assert_eq!(merged.pins, ["B.md", "C.md"]);
        assert!(merged.tags_of("A.md").is_empty());
        assert_eq!(merged.tags_of("B.md"), ["work"]);
        assert_eq!(merged.tags_of("C.md"), [FAVORITE]);
        assert_eq!(merged.tags.len(), 1);
    }

    #[test]
    fn deleting_a_tag_drops_its_uses_from_the_other_side() {
        let mut base = Workspace::default();
        let t = base.create_tag("Old", "red").unwrap();
        let b = base.to_json();
        let mut ours = base.clone();
        ours.delete_tag(&t.id);
        let mut theirs = base.clone();
        theirs.set_tag("X.md", &t.id, true);
        let merged = Workspace::parse(&merge(&b, &ours.to_json(), &theirs.to_json()));
        assert!(merged.tags.is_empty() && merged.tagged.is_empty());
    }
}
