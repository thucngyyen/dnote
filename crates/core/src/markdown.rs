use pulldown_cmark::{html, CowStr, Event, Options, Parser, Tag, TagEnd};

/// Render CommonMark (+ tables, task lists, strikethrough, footnotes) to HTML.
///
/// Output is safe to inject into the web and mobile previews:
/// * raw HTML in notes is escaped rather than passed through;
/// * link/image URLs with a scheme other than http(s)/mailto/tel are neutralised,
///   so `[x](javascript:...)` can't run script.
pub fn to_html(markdown: &str) -> String {
    to_html_for(markdown, "")
}

/// Like [`to_html`], for the note at `note_path`: relative links and image sources
/// (`../Attachments/a.png`, `Other.md`) are rewritten to percent-encoded vault paths
/// (`Attachments/a.png`), so each client resolves them against its vault root.
pub fn to_html_for(markdown: &str, note_path: &str) -> String {
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_TABLES);
    opts.insert(Options::ENABLE_TASKLISTS);
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_FOOTNOTES);
    let parser = Parser::new_ext(markdown, opts).map(|ev| match ev {
        Event::Html(s) | Event::InlineHtml(s) => Event::Text(s),
        Event::Start(Tag::Link {
            link_type,
            dest_url,
            title,
            id,
        }) => Event::Start(Tag::Link {
            link_type,
            dest_url: vault_url(note_path, safe_url(dest_url)),
            title,
            id,
        }),
        Event::Start(Tag::Image {
            link_type,
            dest_url,
            title,
            id,
        }) => Event::Start(Tag::Image {
            link_type,
            dest_url: vault_url(note_path, safe_url(dest_url)),
            title,
            id,
        }),
        other => other,
    });
    let mut out = String::with_capacity(markdown.len() * 3 / 2);
    html::push_html(&mut out, parser);
    out
}

/// Plain-text excerpt for list previews: the first block of body text after the title,
/// with Markdown syntax (emphasis, links, code, task markers, images) removed.
pub fn excerpt(markdown: &str, max_chars: usize) -> String {
    let mut out = String::new();
    let mut in_heading = false;
    let mut in_image = false;
    let mut in_table = false;
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_TASKLISTS);
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_TABLES);
    for ev in Parser::new_ext(markdown, opts) {
        match ev {
            Event::Start(Tag::Heading { .. }) => in_heading = true,
            Event::End(TagEnd::Heading(_)) => in_heading = false,
            Event::Start(Tag::Image { .. }) => in_image = true,
            Event::End(TagEnd::Image) => in_image = false,
            // Tables don't read well as one line of text; skip them.
            Event::Start(Tag::Table(_)) => in_table = true,
            Event::End(TagEnd::Table) => in_table = false,
            Event::Text(t) | Event::Code(t) if !in_heading && !in_image && !in_table => {
                out.push_str(&t)
            }
            Event::SoftBreak | Event::HardBreak if !out.is_empty() => out.push(' '),
            Event::End(
                TagEnd::Paragraph | TagEnd::Item | TagEnd::CodeBlock | TagEnd::BlockQuote(_),
            ) if !out.is_empty() && !out.ends_with(" · ") => {
                if out.chars().count() >= max_chars / 2 {
                    break;
                }
                out.push_str(" · ");
            }
            _ => {}
        }
        if out.chars().count() >= max_chars {
            break;
        }
    }
    let text = out
        .trim()
        .trim_end_matches(" ·")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    text.chars().take(max_chars).collect()
}

fn vault_url<'a>(note_path: &str, url: CowStr<'a>) -> CowStr<'a> {
    match crate::paths::resolve_link(note_path, &url) {
        Some(path) => {
            let fragment = url
                .find('#')
                .map(|i| url[i..].to_string())
                .unwrap_or_default();
            let encoded: Vec<String> = path.split('/').map(encode_segment).collect();
            CowStr::Boxed(format!("{}{fragment}", encoded.join("/")).into_boxed_str())
        }
        None => url,
    }
}

fn encode_segment(seg: &str) -> String {
    let mut out = String::new();
    for b in seg.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn safe_url(url: CowStr<'_>) -> CowStr<'_> {
    let lower: String = url
        .trim()
        .chars()
        .filter(|c| !c.is_whitespace() && !c.is_control())
        .collect::<String>()
        .to_ascii_lowercase();
    let scheme_end = lower.find(':');
    let first_sep = lower.find(['/', '?', '#']);
    let has_scheme = match (scheme_end, first_sep) {
        (Some(c), Some(s)) => c < s,
        (Some(_), None) => true,
        _ => false,
    };
    if !has_scheme
        || ["http:", "https:", "mailto:", "tel:"]
            .iter()
            .any(|p| lower.starts_with(p))
    {
        url
    } else {
        CowStr::Borrowed("#")
    }
}

/// What Enter does inside a list or quote (see [`list_continuation`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ListEnter {
    /// Insert a newline followed by this prefix (`"- "`, `"  3. "`, `"- [ ] "`, `"> "`).
    Continue(String),
    /// The item is empty: replace the current line's text before the cursor with this
    /// (usually `""`; `"> "` when ending a list inside a quote) and don't add a newline.
    End(String),
}

/// What Enter should do, given the text of the current line up to the cursor; `None` means
/// a plain newline. Shared by the mobile editors so every app continues lists the same way.
pub fn list_continuation(line_before_cursor: &str) -> Option<ListEnter> {
    use ListEnter::{Continue, End};
    let line = line_before_cursor;
    let indent_len = line.len() - line.trim_start_matches([' ', '\t']).len();
    let (indent, rest) = line.split_at(indent_len);

    // Quotes ("> text"), possibly holding a list ("> - item").
    if let Some(inner) = rest.strip_prefix('>') {
        let inner = inner.strip_prefix(' ').unwrap_or(inner);
        if inner.trim().is_empty() {
            return Some(End(String::new()));
        }
        return Some(match list_continuation(inner) {
            // An empty item inside a quote ends the list but stays in the quote.
            Some(End(_)) => End(format!("{indent}> ")),
            Some(Continue(p)) => Continue(format!("{indent}> {p}")),
            None => Continue(format!("{indent}> ")),
        });
    }

    // Bullets: "- ", "* ", "+ ", optionally with a task box.
    let (marker, after) =
        if let Some(c) = rest.chars().next().filter(|c| matches!(c, '-' | '*' | '+')) {
            let after = rest[1..].strip_prefix(' ')?;
            (c.to_string(), after)
        } else {
            // Ordered: "1. " or "1) ".
            let digits = rest.len() - rest.trim_start_matches(|c: char| c.is_ascii_digit()).len();
            if digits == 0 || digits > 9 {
                return None;
            }
            let n: u64 = rest[..digits].parse().ok()?;
            let delim = rest[digits..]
                .chars()
                .next()
                .filter(|c| matches!(c, '.' | ')'))?;
            let after = rest[digits + 1..].strip_prefix(' ')?;
            if after.trim().is_empty() {
                return Some(End(String::new()));
            }
            return Some(Continue(format!("{indent}{}{delim} ", n + 1)));
        };
    let (task, text) = match after.get(..3) {
        Some("[ ]") | Some("[x]") | Some("[X]") => {
            (true, after[3..].strip_prefix(' ').unwrap_or(&after[3..]))
        }
        _ => (false, after),
    };
    if text.trim().is_empty() {
        return Some(End(String::new()));
    }
    Some(Continue(if task {
        format!("{indent}{marker} [ ] ")
    } else {
        format!("{indent}{marker} ")
    }))
}

#[cfg(test)]
mod tests {
    #[test]
    fn continues_lists() {
        use super::{list_continuation as c, ListEnter};
        let cont = |p: &str| Some(ListEnter::Continue(p.into()));
        let end = |p: &str| Some(ListEnter::End(p.into()));
        assert_eq!(c("plain text"), None);
        assert_eq!(c("-not a list"), None);
        assert_eq!(c("- milk"), cont("- "));
        assert_eq!(c("  * nested"), cont("  * "));
        assert_eq!(c("- [x] done"), cont("- [ ] "));
        assert_eq!(c("- [ ] todo"), cont("- [ ] "));
        assert_eq!(c("9. nine"), cont("10. "));
        assert_eq!(c("3) three"), cont("4) "));
        assert_eq!(c("2024. was a year"), cont("2025. "));
        assert_eq!(c("> quote"), cont("> "));
        assert_eq!(c("> - in quote"), cont("> - "));
        assert_eq!(c("> - [x] in quote"), cont("> - [ ] "));
        // Empty items end the list.
        for empty in ["- ", "- [ ] ", "1. ", "> ", "  - "] {
            assert_eq!(c(empty), end(""), "{empty:?}");
        }
        // ...and inside a quote, end the list but stay in the quote.
        assert_eq!(c("> - "), end("> "));
        assert_eq!(c("> 2. "), end("> "));
        assert_eq!(c("> - [ ] "), end("> "));
    }

    #[test]
    fn escapes_html() {
        let out = super::to_html("# Hi\n\n<script>alert(1)</script>\n\n- [x] done");
        assert!(out.contains("<h1>Hi</h1>"));
        assert!(!out.contains("<script>"));
        assert!(out.contains("checkbox"));
    }

    #[test]
    fn neutralises_script_urls() {
        let out = super::to_html("[a](javascript:alert(1)) [b](JaVa\tScript:x) ![c](data:text/html,x) [d](https://x.dev) [e](Notes/other.md)");
        assert!(!out.to_ascii_lowercase().contains("javascript"), "{out}");
        assert!(!out.contains("data:"), "{out}");
        assert!(out.contains("https://x.dev") && out.contains("Notes/other.md"));
    }

    #[test]
    fn excerpts_are_plain_text() {
        let md = "# Title\n\n![p](a.png)\n\nShip **sync** first, see [docs](x.md) and `code`.\n\nSecond para";
        assert_eq!(
            super::excerpt(md, 140),
            "Ship sync first, see docs and code. · Second para"
        );
        assert_eq!(super::excerpt("# Only a title\n", 140), "");
        assert_eq!(
            super::excerpt("- [ ] Milk from **Android**\n- [x] Eggs\n", 140),
            "Milk from Android · Eggs"
        );
        assert_eq!(
            super::excerpt(
                "Intro.\n\n![a](b.png)\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nAfter",
                140
            ),
            "Intro. · After"
        );
    }

    #[test]
    fn resolves_relative_images() {
        let out = super::to_html_for(
            "![p](../Attachments/my%20photo.jpg) [n](Sub/Other.md#top)",
            "Projects/Plan.md",
        );
        assert!(out.contains(r#"src="Attachments/my%20photo.jpg""#), "{out}");
        assert!(out.contains(r#"href="Projects/Sub/Other.md#top""#), "{out}");
    }
}
