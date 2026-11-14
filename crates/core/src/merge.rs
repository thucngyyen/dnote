/// Result of a 3-way merge of a note edited on two devices.
pub struct Merged {
    pub text: String,
    /// True when overlapping edits produced git-style conflict markers.
    pub conflicted: bool,
}

/// Merge `ours` and `theirs`, both derived from `base`, line by line.
pub fn merge3(base: &str, ours: &str, theirs: &str) -> Merged {
    if ours == theirs {
        return Merged {
            text: ours.to_string(),
            conflicted: false,
        };
    }
    if ours == base {
        return Merged {
            text: theirs.to_string(),
            conflicted: false,
        };
    }
    if theirs == base {
        return Merged {
            text: ours.to_string(),
            conflicted: false,
        };
    }
    match diffy::merge(base, ours, theirs) {
        Ok(text) => Merged {
            text,
            conflicted: false,
        },
        Err(text) => Merged {
            text,
            conflicted: true,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clean_merge() {
        let m = merge3("a\nb\nc\n", "A\nb\nc\n", "a\nb\nC\n");
        assert!(!m.conflicted);
        assert_eq!(m.text, "A\nb\nC\n");
    }

    #[test]
    fn conflicting_merge_keeps_both() {
        let m = merge3("a\n", "ours\n", "theirs\n");
        assert!(m.conflicted);
        assert!(m.text.contains("ours") && m.text.contains("theirs"));
    }
}
