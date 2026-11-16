/**
 * Live preview for the Markdown editor: the document stays Markdown, but it reads like the
 * rendered note. Syntax (`#`, `**`, `[`…`](url)`, `>`) is hidden on every line except the ones
 * holding the cursor, bullets are drawn as dots, task boxes are real checkboxes you can click,
 * images show inline and `---` becomes a rule.
 */
import { syntaxTree } from "@codemirror/language";
import { type EditorState, type Range, RangeSet } from "@codemirror/state";
import { Decoration, type DecorationSet, EditorView, ViewPlugin, type ViewUpdate, WidgetType } from "@codemirror/view";

export interface LivePreviewOptions {
  /** Object URL for an image link in the note (relative path or URL), or undefined if missing. */
  imageUrl(src: string): Promise<string | undefined>;
}

class Bullet extends WidgetType {
  eq() {
    return true;
  }
  toDOM() {
    const s = document.createElement("span");
    s.className = "cm-lp-bullet";
    s.setAttribute("aria-hidden", "true");
    return s;
  }
}

class Checkbox extends WidgetType {
  constructor(readonly checked: boolean) {
    super();
  }
  eq(o: Checkbox) {
    return o.checked === this.checked;
  }
  toDOM(view: EditorView) {
    const box = document.createElement("span");
    box.className = "cm-lp-check" + (this.checked ? " on" : "");
    box.setAttribute("role", "checkbox");
    box.setAttribute("aria-checked", String(this.checked));
    box.addEventListener("mousedown", (e) => {
      // Toggle without moving the cursor (or focusing the editor on touch devices).
      e.preventDefault();
      const pos = view.posAtDOM(box);
      const mark = view.state.sliceDoc(pos, pos + 3);
      if (!/^\[[ xX]\]$/.test(mark)) return;
      view.dispatch({ changes: { from: pos + 1, to: pos + 2, insert: this.checked ? " " : "x" }, userEvent: "input.toggle" });
    });
    return box;
  }
  ignoreEvent() {
    return true;
  }
}

class Rule extends WidgetType {
  eq() {
    return true;
  }
  toDOM() {
    const s = document.createElement("span");
    s.className = "cm-lp-hr";
    return s;
  }
}

class Image extends WidgetType {
  constructor(
    readonly src: string,
    readonly alt: string,
    readonly opts: LivePreviewOptions,
  ) {
    super();
  }
  eq(o: Image) {
    return o.src === this.src && o.alt === this.alt;
  }
  toDOM(view: EditorView) {
    const wrap = document.createElement("span");
    wrap.className = "cm-lp-img loading";
    const img = document.createElement("img");
    img.alt = this.alt;
    wrap.append(img);
    void this.opts.imageUrl(this.src).then((url) => {
      wrap.classList.remove("loading");
      if (!url) {
        wrap.classList.add("missing");
        wrap.textContent = this.alt || this.src;
        return;
      }
      img.onload = () => view.requestMeasure();
      img.src = url;
    });
    return wrap;
  }
  ignoreEvent() {
    return false;
  }
}

const hide = Decoration.replace({});
const bullet = Decoration.replace({ widget: new Bullet() });
const rule = Decoration.replace({ widget: new Rule() });
const done = Decoration.mark({ class: "cm-lp-done" });
const linkText = Decoration.mark({ class: "cm-lp-link" });
const quoteLine = Decoration.line({ class: "cm-lp-quote" });
const codeLine = Decoration.line({ class: "cm-lp-code" });
const codeFirst = Decoration.line({ class: "cm-lp-code cm-lp-code-first" });
const codeLast = Decoration.line({ class: "cm-lp-code cm-lp-code-last" });
const fenceMark = Decoration.mark({ class: "cm-lp-fence" });

/** Line numbers touched by a selection while the editor has focus: those stay raw Markdown. */
function activeLines(state: EditorState, focused: boolean): Set<number> {
  const lines = new Set<number>();
  if (!focused) return lines;
  for (const r of state.selection.ranges) {
    const a = state.doc.lineAt(r.from).number;
    const b = state.doc.lineAt(r.to).number;
    for (let n = a; n <= b; n++) lines.add(n);
  }
  return lines;
}

function build(view: EditorView, opts: LivePreviewOptions): { decorations: DecorationSet; atomic: DecorationSet } {
  const { state } = view;
  const doc = state.doc;
  const active = activeLines(state, view.hasFocus);
  const isActive = (pos: number) => active.has(doc.lineAt(pos).number);
  const deco: Range<Decoration>[] = [];
  const atomic: Range<Decoration>[] = [];
  const head = state.selection.main.head;

  for (const { from, to } of view.visibleRanges) {
    syntaxTree(state).iterate({
      from,
      to,
      enter: (node) => {
        const n = node.name;
        if (/^ATXHeading\d$/.test(n)) {
          const mark = node.node.getChild("HeaderMark");
          if (mark && !isActive(node.from)) {
            const end = doc.sliceString(mark.to, mark.to + 1) === " " ? mark.to + 1 : mark.to;
            deco.push(hide.range(mark.from, end));
            // Closing hashes ("## Title ##").
            const close = node.node.lastChild;
            if (close && close.name === "HeaderMark" && close.from > mark.from) deco.push(hide.range(close.from, close.to));
          }
          return false;
        }
        if (n === "EmphasisMark" || n === "StrikethroughMark") {
          if (!isActive(node.from)) deco.push(hide.range(node.from, node.to));
          return;
        }
        if (n === "CodeMark") {
          const parent = node.node.parent?.name;
          if (parent === "InlineCode" && !isActive(node.from)) deco.push(hide.range(node.from, node.to));
          if (parent === "FencedCode") deco.push(fenceMark.range(node.from, node.to));
          return;
        }
        if (n === "CodeInfo") {
          deco.push(fenceMark.range(node.from, node.to));
          return;
        }
        if (n === "FencedCode") {
          const first = doc.lineAt(node.from).number;
          const last = doc.lineAt(node.to).number;
          for (let l = first; l <= last; l++) {
            const line = doc.line(l);
            deco.push((l === first ? codeFirst : l === last ? codeLast : codeLine).range(line.from));
          }
          return;
        }
        if (n === "Link") {
          const marks = node.node.getChildren("LinkMark");
          if (marks.length >= 2 && !isActive(node.from)) {
            const open = marks[0];
            const close = marks[1]; // "]"
            if (close.from > open.to) deco.push(linkText.range(open.to, close.from));
            deco.push(hide.range(open.from, open.to));
            deco.push(hide.range(close.from, node.to));
          }
          return false;
        }
        if (n === "Image") {
          if (isActive(node.from)) return false;
          const url = node.node.getChild("URL");
          if (!url) return false;
          const marks = node.node.getChildren("LinkMark");
          const alt = marks.length >= 2 ? doc.sliceString(marks[0].to, marks[1].from) : "";
          let src = doc.sliceString(url.from, url.to);
          if (src.startsWith("<") && src.endsWith(">")) src = src.slice(1, -1);
          deco.push(Decoration.replace({ widget: new Image(src, alt, opts) }).range(node.from, node.to));
          return false;
        }
        if (n === "Blockquote") {
          const first = doc.lineAt(node.from).number;
          const last = doc.lineAt(node.to).number;
          for (let l = first; l <= last; l++) deco.push(quoteLine.range(doc.line(l).from));
          return;
        }
        if (n === "QuoteMark") {
          if (!isActive(node.from)) {
            const end = doc.sliceString(node.to, node.to + 1) === " " ? node.to + 1 : node.to;
            deco.push(hide.range(node.from, end));
          }
          return;
        }
        if (n === "HorizontalRule") {
          if (!isActive(node.from)) deco.push(rule.range(node.from, node.to));
          return;
        }
        if (n === "ListItem") {
          const mark = node.node.getChild("ListMark");
          if (!mark) return;
          const bulleted = node.node.parent?.name === "BulletList";
          const task = node.node.getChild("Task");
          const taskMarker = task?.getChild("TaskMarker");
          const line = doc.lineAt(mark.from);
          if (task && taskMarker && taskMarker.from === mark.to + 1) {
            // "- [ ] text": the box replaces both the bullet and "[ ]". Show it raw only while the
            // cursor sits inside the marker itself, so it can still be edited.
            const textStart = Math.min(taskMarker.to + 1, line.to);
            const editing = head >= line.from && head < textStart && isActive(mark.from) && head <= taskMarker.to;
            if (!editing) {
              const checked = /x/i.test(doc.sliceString(taskMarker.from + 1, taskMarker.to - 1));
              deco.push(hide.range(mark.from, taskMarker.from));
              const box = Decoration.replace({ widget: new Checkbox(checked) }).range(taskMarker.from, taskMarker.to);
              deco.push(box);
              atomic.push(box);
              if (checked && line.to > textStart) deco.push(done.range(textStart, line.to));
            }
          } else if (bulleted) {
            const editing = isActive(mark.from) && head >= mark.from && head < mark.to;
            if (!editing) {
              const b = bullet.range(mark.from, mark.to);
              deco.push(b);
              atomic.push(b);
            }
          }
          return;
        }
      },
    });
  }
  return { decorations: Decoration.set(deco, true), atomic: Decoration.set(atomic, true) };
}

export function livePreview(opts: LivePreviewOptions) {
  const plugin = ViewPlugin.fromClass(
    class {
      decorations: DecorationSet;
      atomic: DecorationSet;
      constructor(view: EditorView) {
        ({ decorations: this.decorations, atomic: this.atomic } = build(view, opts));
      }
      update(u: ViewUpdate) {
        if (u.docChanged || u.viewportChanged || u.selectionSet || u.focusChanged || syntaxTree(u.state) !== syntaxTree(u.startState)) {
          ({ decorations: this.decorations, atomic: this.atomic } = build(u.view, opts));
        }
      }
    },
    { decorations: (v) => v.decorations },
  );
  return [
    plugin,
    EditorView.atomicRanges.of((view) => view.plugin(plugin)?.atomic ?? RangeSet.empty),
  ];
}
