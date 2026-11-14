import { Annotation, EditorState } from "@codemirror/state";
import {
  EditorView,
  keymap,
  drawSelection,
  dropCursor,
  highlightActiveLine,
  placeholder,
} from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { markdown, markdownLanguage } from "@codemirror/lang-markdown";
import { languages } from "@codemirror/language-data";
import { HighlightStyle, syntaxHighlighting, indentOnInput, bracketMatching } from "@codemirror/language";
import { closeBrackets, closeBracketsKeymap } from "@codemirror/autocomplete";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { tags as t } from "@lezer/highlight";

/** Marks programmatic edits (sync merges) so they are not treated as typing. */
const external = Annotation.define<boolean>();

const markdownStyle = HighlightStyle.define([
  { tag: t.heading1, fontSize: "1.75em", fontWeight: "700", letterSpacing: "-0.03em", color: "var(--text)" },
  { tag: t.heading2, fontSize: "1.35em", fontWeight: "700", letterSpacing: "-0.02em", color: "var(--text)" },
  { tag: t.heading3, fontSize: "1.12em", fontWeight: "650", letterSpacing: "-0.01em", color: "var(--text)" },
  { tag: [t.heading4, t.heading5, t.heading6], fontWeight: "650", color: "var(--text)" },
  { tag: t.strong, fontWeight: "600", color: "var(--text)" },
  { tag: t.emphasis, fontStyle: "italic" },
  { tag: t.strikethrough, textDecoration: "line-through" },
  { tag: t.link, color: "var(--accent)" },
  { tag: t.url, color: "var(--muted)" },
  { tag: t.monospace, fontFamily: "var(--mono)", fontSize: "0.9em", color: "var(--code)" },
  { tag: t.quote, color: "var(--muted)" },
  { tag: [t.meta, t.contentSeparator], color: "var(--faint)" },
  // List/quote markers ("-", "1.", ">") are processingInstruction in lezer-markdown; keep item text plain.
  { tag: t.processingInstruction, color: "var(--accent)" },
  { tag: [t.keyword, t.operator], color: "var(--syn-keyword)" },
  { tag: [t.string, t.special(t.string)], color: "var(--syn-string)" },
  { tag: [t.number, t.bool, t.atom], color: "var(--syn-number)" },
  { tag: [t.comment], color: "var(--faint)", fontStyle: "italic" },
  { tag: [t.function(t.variableName), t.typeName, t.className], color: "var(--syn-fn)" },
]);

const theme = EditorView.theme({
  "&": { height: "100%", fontSize: "15px", backgroundColor: "transparent", color: "var(--text-2)" },
  ".cm-scroller": { fontFamily: "var(--sans)", lineHeight: "1.75", padding: "14px 0 40vh" },
  ".cm-content": { maxWidth: "720px", margin: "0 auto", padding: "0 40px", caretColor: "var(--accent)" },
  ".cm-line": { padding: "0" },
  "&.cm-focused": { outline: "none" },
  ".cm-activeLine": { backgroundColor: "transparent" },
  "&.cm-focused .cm-activeLine": { backgroundColor: "transparent" },
  ".cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection": {
    backgroundColor: "var(--selection) !important",
  },
  ".cm-cursor": { borderLeftColor: "var(--accent)", borderLeftWidth: "2px" },
  ".cm-placeholder": { color: "var(--faint)" },
  ".cm-panels": { backgroundColor: "var(--panel)", color: "var(--text)", borderColor: "var(--border)" },
  ".cm-searchMatch": { backgroundColor: "var(--search-match)" },
  "@media (max-width: 760px)": {
    ".cm-content": { padding: "0 20px" },
    ".cm-scroller": { padding: "10px 0 40vh" },
  },
});

export interface Editor {
  view: EditorView;
  getText(): string;
  /** Replace the whole document, keeping the cursor roughly in place. */
  setText(text: string, resetHistory?: boolean): void;
  focus(): void;
}

export function createEditor(parent: HTMLElement, onChange: (text: string) => void): Editor {
  const extensions = [
    history(),
    drawSelection(),
    dropCursor(),
    indentOnInput(),
    bracketMatching(),
    closeBrackets(),
    highlightActiveLine(),
    highlightSelectionMatches(),
    EditorView.lineWrapping,
    markdown({ base: markdownLanguage, codeLanguages: languages, addKeymap: true }),
    syntaxHighlighting(markdownStyle),
    placeholder("Start writing…"),
    keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap, indentWithTab]),
    theme,
    EditorView.contentAttributes.of({ spellcheck: "true", autocapitalize: "sentences", autocorrect: "on" }),
    EditorView.updateListener.of((u) => {
      if (u.docChanged && !u.transactions.some((tr) => tr.annotation(external))) onChange(u.state.doc.toString());
    }),
  ];
  const view = new EditorView({ parent, state: EditorState.create({ doc: "", extensions }) });

  return {
    view,
    getText: () => view.state.doc.toString(),
    setText(text, resetHistory = false) {
      if (resetHistory) {
        view.setState(EditorState.create({ doc: text, extensions }));
        return;
      }
      const head = Math.min(view.state.selection.main.head, text.length);
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: text },
        selection: { anchor: head },
        annotations: external.of(true),
      });
    },
    focus: () => view.focus(),
  };
}
