import "./style.css";
import init, {
  sync as runSync,
  renderMarkdown,
  merge3,
  validatePath,
  relativeLink,
  resolveLink,
  tagColors,
  workspacePath,
  excerpt,
  nextUntitled,
  titleToName,
  Workspace,
} from "./wasm/dnote_wasm.js";
import { LocalStore, allNotes, isImage, isNote, type Settings } from "./store";
import {
  buildTree,
  findFolder,
  countNotes,
  parentOf,
  baseName,
  displayName,
  relativeTime,
  type FolderNode,
  type TreeNode,
} from "./tree";
import { createEditor, type Editor } from "./editor";

type Mode = "write" | "split" | "read";
type View =
  | { kind: "all" }
  | { kind: "folder"; path: string }
  | { kind: "tag"; id: string }
  | { kind: "note"; path: string }
  | { kind: "image"; path: string };
interface Tag {
  id: string;
  name: string;
  color: string;
}
interface SyncReport {
  conflicts: string[];
  skipped: string[];
}

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const SAVE_DELAY = 350;
const SYNC_AFTER_EDIT = 2500;
const SYNC_INTERVAL = 30_000;
const ATTACHMENTS = "Attachments";
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
const isPhone = () => window.matchMedia("(max-width: 760px)").matches;

let WS_PATH = ".dnote/workspace.json";
let store: LocalStore;
let editor: Editor;
let settings: Settings | undefined;
let ws: Workspace;
let files: string[] = [];
let notes: string[] = [];
let folders: string[] = [];
let tree: FolderNode = { kind: "folder", name: "", path: "", children: [] };
const previews = new Map<string, string>();
const imageUrls = new Map<string, string>();
const expanded = new Set<string>(JSON.parse(localStorage.getItem("dnote.expanded") ?? "[]"));

let view: View = { kind: "all" };
/** The list the user came from, for "back" from a note. */
let listView: View = { kind: "all" };
/** The note open in the editor and the content we last persisted for it. */
let open: { path: string; saved: string } | undefined;
let saveTimer: number | undefined;
let syncTimer: number | undefined;
let syncing = false;
let syncQueued = false;
let lastSync: number | undefined;
let lastError: string | undefined;

// ---------------------------------------------------------------------------
// Boot

async function boot() {
  await init();
  WS_PATH = workspacePath();
  store = await LocalStore.open();
  settings = await store.get<Settings>("settings");
  editor = createEditor($("editor"), onEdit, {
    // Images in the editor: links are relative to the open note; web URLs load as is.
    async imageUrl(src) {
      if (/^https?:/i.test(src)) return src;
      if (/^[a-z][a-z0-9+.-]*:/i.test(src) || !open) return undefined;
      const path = resolveLink(open.path, src);
      return path ? imageUrl(path) : undefined;
    },
  });
  setMode((localStorage.getItem("dnote.mode") as Mode) ?? "write", false);
  await refresh();
  wireUi();

  let initial: View = { kind: "all" };
  try {
    const saved = JSON.parse(localStorage.getItem("dnote.view") ?? "null") as View | null;
    if (saved && exists(saved)) initial = saved;
  } catch {
    /* ignore */
  }
  await go(initial, { animate: false });
  updateSyncLabel();

  void syncNow();
  setInterval(() => void syncNow(), SYNC_INTERVAL);
  window.addEventListener("focus", () => void syncNow());
  window.addEventListener("online", () => void syncNow());
  window.addEventListener("offline", updateSyncLabel);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") void flushSave();
  });
  window.addEventListener("pagehide", () => void flushSave());
  window.addEventListener("resize", () => positionPill(false));
  document.documentElement.dataset.ready = "1";
}

function exists(v: View): boolean {
  switch (v.kind) {
    case "all":
      return true;
    case "folder":
      return !!findFolder(tree, v.path);
    case "tag":
      return tags().some((t) => t.id === v.id);
    case "note":
    case "image":
      return files.includes(v.path);
  }
}

// ---------------------------------------------------------------------------
// Data

async function refresh() {
  files = (await store.listFiles()).sort();
  notes = files.filter(isNote);
  folders = await store.folders();
  tree = buildTree(files.filter((f) => isNote(f) || isImage(f)), folders);
  await loadWorkspace();
  previews.clear();
  for (const [p, text] of await allNotes(store)) previews.set(p, excerpt(text, 160));
  renderSidebar();
}

async function loadWorkspace() {
  ws?.free();
  ws = new Workspace((await store.readText(WS_PATH)) ?? undefined);
}

async function saveWorkspace() {
  const json = ws.toJson();
  if (json !== ((await store.readText(WS_PATH)) ?? "")) {
    await store.write(WS_PATH, json);
    scheduleSync(800);
  }
  renderSidebar();
  renderToolbar();
  if (view.kind !== "note" && view.kind !== "image") renderList(view);
}

function tags(): Tag[] {
  return ws.tags() as Tag[];
}

function tagCount(id: string) {
  return ws.pathsWithTag(id).filter((p) => files.includes(p) || !!findFolder(tree, p)).length;
}

const isFolderPath = (p: string) => !!p && !!findFolder(tree, p) && !files.includes(p);

// ---------------------------------------------------------------------------
// Navigation with view transitions

interface GoOptions {
  /** Element that morphs into the destination (a card or row). */
  from?: HTMLElement;
  direction?: "forward" | "back";
  animate?: boolean;
}

/** Path of the note/image that was opened by zooming out of a card (so "back" zooms into it again). */
let zoomedFrom: string | undefined;

async function go(next: View, opts: GoOptions = {}) {
  await flushSave();
  const prev = view;
  const animate = opts.animate !== false && !reduceMotion.matches && "startViewTransition" in document;
  if (!animate) {
    zoomedFrom = undefined;
    await applyView(next);
    return;
  }
  const isDoc = (v: View) => v.kind === "note" || v.kind === "image";
  const docView = (v: View) => $(v.kind === "note" ? "note-view" : "image-view");
  // Zoom in: a card grows into the full note (like iOS). Zoom out: the note shrinks back into its card.
  const zoomIn = !!opts.from && isDoc(next);
  const zoomOut = isDoc(prev) && !isDoc(next) && zoomedFrom === (prev as { path: string }).path && key(next) === key(listView);
  const nav = zoomIn ? "zoom-in" : zoomOut ? "zoom-out" : (opts.direction ?? (isDoc(next) ? "forward" : directionBetween(prev, next)));
  document.documentElement.dataset.nav = nav;
  if (zoomIn) opts.from!.style.viewTransitionName = "morph";
  if (zoomOut) docView(prev).style.viewTransitionName = "morph";

  const transition = (document as Document & { startViewTransition(cb: () => Promise<void>): { finished: Promise<void> } }).startViewTransition(async () => {
    if (zoomIn) opts.from!.style.viewTransitionName = "";
    if (zoomOut) docView(prev).style.viewTransitionName = "";
    await applyView(next);
    if (zoomIn) {
      docView(next).style.viewTransitionName = "morph";
      zoomedFrom = (next as { path: string }).path;
    } else if (zoomOut) {
      const card = $("cards").querySelector<HTMLElement>(`[data-path="${CSS.escape((prev as { path: string }).path)}"]`);
      if (card) {
        card.scrollIntoView({ block: "nearest" });
        card.style.viewTransitionName = "morph";
      }
      zoomedFrom = undefined;
    } else if (!isDoc(next) || !isDoc(prev)) {
      zoomedFrom = isDoc(next) ? zoomedFrom : undefined;
    }
  });
  try {
    await transition.finished;
  } finally {
    document.querySelectorAll<HTMLElement>("[style*='view-transition-name']").forEach((el) => (el.style.viewTransitionName = ""));
    delete document.documentElement.dataset.nav;
  }
}

/** Lists ordered as in the chip bar decide whether a list change slides left or right. */
function directionBetween(a: View, b: View): "forward" | "back" {
  const order = scopes().map((s) => key(s));
  const ia = order.indexOf(key(a));
  const ib = order.indexOf(key(b));
  if (ia >= 0 && ib >= 0) return ib >= ia ? "forward" : "back";
  if (a.kind === "folder" && b.kind === "folder") return b.path.startsWith(`${a.path}/`) ? "forward" : "back";
  return "forward";
}

const key = (v: View) => (v.kind === "all" ? "all" : v.kind === "tag" ? `tag:${v.id}` : v.kind === "folder" ? `folder:${v.path}` : `${v.kind}:${v.path}`);

async function applyView(next: View) {
  view = next;
  localStorage.setItem("dnote.view", JSON.stringify(next));
  for (const id of ["list-view", "note-view", "image-view", "empty"]) $(id).hidden = true;

  if (next.kind === "note") {
    const content = await store.readText(next.path);
    if (content === undefined) return applyView(listView);
    open = { path: next.path, saved: content };
    editor.setText(content, true);
    $("note-view").hidden = false;
    renderTitle();
    renderPreview();
    revealInTree(next.path);
    if (currentMode() !== "read" && !isPhone()) editor.focus();
  } else if (next.kind === "image") {
    open = undefined;
    $("image-view").hidden = false;
    const img = $<HTMLImageElement>("image-full");
    img.src = (await imageUrl(next.path)) ?? "";
    img.alt = baseName(next.path);
    $("image-caption").textContent = next.path;
  } else {
    open = undefined;
    listView = next;
    if (next.kind === "all" && notes.length === 0 && files.filter(isImage).length === 0) {
      $("empty").hidden = false;
    } else {
      $("list-view").hidden = false;
      renderList(next);
    }
  }
  renderCrumbs();
  renderToolbar();
  renderSidebar();
  closeSidebar();
}

function revealInTree(path: string) {
  let p = parentOf(path);
  while (p) {
    expanded.add(p);
    p = parentOf(p);
  }
}

// ---------------------------------------------------------------------------
// Sidebar

function renderSidebar() {
  $("count-all").textContent = String(notes.length);
  $("nav-all").classList.toggle("active", view.kind === "all");

  // Pinned
  const pins = ws.pins().filter((p) => files.includes(p) || isFolderPath(p));
  $("pinned-section").hidden = pins.length === 0;
  $("pinned").replaceChildren(
    ...pins.map((p) => {
      const kind = files.includes(p) ? (isImage(p) ? "image" : "note") : "folder";
      const row = navRow(kind === "folder" ? "#i-folder" : kind === "image" ? "#i-image" : "#i-note", kind === "folder" ? baseName(p) : displayName(baseName(p)));
      row.classList.toggle("active", (view.kind === "note" || view.kind === "image" || view.kind === "folder") && view.path === p);
      row.addEventListener("click", () => void go(kind === "folder" ? { kind: "folder", path: p } : { kind: kind as "note" | "image", path: p }));
      addMore(row, (a) => itemMenu(p, a));
      return row;
    }),
  );

  // Tags
  $("tags").replaceChildren(
    ...tags().map((t) => {
      const row = navRow("", t.name, tagCount(t.id));
      row.querySelector(".ico")!.replaceWith(tagGlyph(t));
      row.classList.toggle("active", view.kind === "tag" && view.id === t.id);
      row.addEventListener("click", () => void go({ kind: "tag", id: t.id }));
      addMore(row, (a) => tagMenu(t, a));
      return row;
    }),
  );

  // Folders
  const container = $("tree");
  container.replaceChildren(tree.children.length ? renderChildren(tree, 0) : emptyLine("No notes yet."));
}

function navRow(icon: string, label: string, count?: number): HTMLButtonElement {
  const row = document.createElement("button");
  row.type = "button";
  row.className = "nav-row";
  row.innerHTML = icon ? `<svg class="ico"><use href="${icon}"/></svg>` : `<span class="ico"></span>`;
  const l = document.createElement("span");
  l.className = "label";
  l.textContent = label;
  row.append(l);
  if (count !== undefined) {
    const c = document.createElement("span");
    c.className = "count";
    c.textContent = count ? String(count) : "";
    row.append(c);
  }
  return row;
}

function addMore(row: HTMLElement, open: (anchor: HTMLElement) => void) {
  const more = document.createElement("span");
  more.className = "row-more";
  more.setAttribute("role", "button");
  more.setAttribute("aria-label", "More actions");
  more.tabIndex = -1;
  more.innerHTML = `<svg width="16" height="16"><use href="#i-more"/></svg>`;
  more.addEventListener("click", (e) => {
    e.stopPropagation();
    open(more);
  });
  row.append(more);
  row.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    open(row);
  });
}

function tagGlyph(t: Tag): HTMLElement {
  const el = document.createElement("span");
  el.className = `ico tag-glyph c-${t.color}`;
  el.innerHTML = t.id === "favorite" ? `<svg width="14" height="14"><use href="#i-star"/></svg>` : `<i></i>`;
  return el;
}

function emptyLine(text: string) {
  const p = document.createElement("p");
  p.className = "tree-empty";
  p.textContent = text;
  return p;
}

function renderChildren(folder: FolderNode, depth: number): HTMLUListElement {
  const ul = document.createElement("ul");
  ul.setAttribute("role", depth === 0 ? "tree" : "group");
  for (const node of folder.children) ul.append(renderNode(node, depth));
  return ul;
}

function renderNode(node: TreeNode, depth: number): HTMLLIElement {
  const li = document.createElement("li");
  const row = document.createElement("div");
  row.className = `row ${node.kind}`;
  row.style.setProperty("--depth", String(depth));
  row.tabIndex = 0;
  row.setAttribute("role", "treeitem");
  row.dataset.path = node.path;
  const label = document.createElement("span");
  label.className = "label";

  if (node.kind === "folder") {
    const isOpen = expanded.has(node.path);
    row.setAttribute("aria-expanded", String(isOpen));
    const chev = document.createElement("span");
    chev.className = "chev";
    chev.innerHTML = `<svg width="14" height="14"><use href="#i-chev"/></svg>`;
    chev.addEventListener("click", (e) => {
      e.stopPropagation();
      toggleFolder(node.path, li, row);
    });
    label.append(chev);
    label.insertAdjacentHTML("beforeend", `<svg class="ico" width="16" height="16"><use href="#i-folder"/></svg>`);
    label.append(node.name);
    if (ws.isPinned(node.path)) label.insertAdjacentHTML("beforeend", `<svg class="pin-mark" width="12" height="12"><use href="#i-pin-fill"/></svg>`);
    const count = document.createElement("span");
    count.className = "count";
    count.textContent = String(countNotes(node) || "");
    row.classList.toggle("active", view.kind === "folder" && view.path === node.path);
    row.append(label, count);
    addMore(row, (a) => itemMenu(node.path, a));
    row.addEventListener("click", () => {
      if (!expanded.has(node.path)) toggleFolder(node.path, li, row);
      void go({ kind: "folder", path: node.path });
    });
    row.addEventListener("keydown", (e) => {
      if (e.key === "Enter") void go({ kind: "folder", path: node.path });
      if (e.key === "ArrowRight" && !expanded.has(node.path)) toggleFolder(node.path, li, row);
      if (e.key === "ArrowLeft" && expanded.has(node.path)) toggleFolder(node.path, li, row);
    });
    li.append(row);
    const kids = document.createElement("div");
    kids.className = "kids";
    kids.dataset.open = String(isOpen);
    const inner = document.createElement("div");
    if (node.children.length) inner.append(renderChildren(node, depth + 1));
    kids.append(inner);
    li.append(kids);
  } else {
    label.insertAdjacentHTML("beforeend", `<svg class="ico" width="16" height="16"><use href="${node.kind === "image" ? "#i-image" : "#i-note"}"/></svg>`);
    label.append(node.kind === "image" ? node.name : displayName(node.name));
    if (ws.isPinned(node.path)) label.insertAdjacentHTML("beforeend", `<svg class="pin-mark" width="12" height="12"><use href="#i-pin-fill"/></svg>`);
    const dots = tagDots(node.path);
    row.classList.toggle("active", (view.kind === "note" || view.kind === "image") && view.path === node.path);
    row.append(label, dots);
    addMore(row, (a) => itemMenu(node.path, a));
    const goTo = () => void go({ kind: node.kind as "note" | "image", path: node.path });
    row.addEventListener("click", goTo);
    row.addEventListener("keydown", (e) => e.key === "Enter" && goTo());
    li.append(row);
  }
  return li;
}

function toggleFolder(path: string, li: HTMLLIElement, row: HTMLElement) {
  const isOpen = !expanded.has(path);
  isOpen ? expanded.add(path) : expanded.delete(path);
  localStorage.setItem("dnote.expanded", JSON.stringify([...expanded]));
  row.setAttribute("aria-expanded", String(isOpen));
  const kids = li.querySelector<HTMLElement>(":scope > .kids");
  if (kids) kids.dataset.open = String(isOpen);
}

function tagDots(path: string): HTMLElement {
  const el = document.createElement("span");
  el.className = "dots";
  const all = tags();
  for (const id of ws.tagsOf(path)) {
    const t = all.find((x) => x.id === id);
    if (!t) continue;
    const d = document.createElement("i");
    d.className = `c-${t.color}`;
    d.title = t.name;
    el.append(d);
  }
  return el;
}

// ---------------------------------------------------------------------------
// List view (folder, tag, all notes)

function scopes(): View[] {
  const out: View[] = [{ kind: "all" }];
  for (const c of tree.children) if (c.kind === "folder") out.push({ kind: "folder", path: c.path });
  for (const t of tags()) out.push({ kind: "tag", id: t.id });
  return out;
}

function renderChips(active: View) {
  const bar = $("chips");
  const pill = $("chip-pill");
  bar.replaceChildren(pill);
  const all = tags();
  const activeKey = key(active.kind === "folder" ? { kind: "folder", path: active.path.split("/")[0] } : active);
  let sepAdded = false;
  for (const s of scopes()) {
    if (s.kind === "tag" && !sepAdded) {
      const sep = document.createElement("span");
      sep.className = "chip-sep";
      bar.append(sep);
      sepAdded = true;
    }
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "chip";
    chip.setAttribute("role", "tab");
    chip.dataset.key = key(s);
    if (s.kind === "all") chip.textContent = "All";
    else if (s.kind === "folder") chip.textContent = baseName(s.path);
    else if (s.kind === "tag") {
      const t = all.find((x) => x.id === s.id)!;
      chip.append(tagGlyph(t), t.name);
    }
    const selected = key(s) === activeKey;
    chip.setAttribute("aria-selected", String(selected));
    chip.addEventListener("click", () => {
      if (key(s) === key(view)) return;
      void go(s);
    });
    bar.append(chip);
  }
  requestAnimationFrame(() => positionPill(true));
}

let pillPlaced = false;
function positionPill(animate: boolean) {
  const bar = $("chips");
  const pill = $("chip-pill");
  const chip = bar.querySelector<HTMLElement>('.chip[aria-selected="true"]');
  if (!chip) {
    pill.style.opacity = "0";
    return;
  }
  pill.style.transition = animate && pillPlaced ? "" : "none";
  pill.style.opacity = "1";
  pill.style.transform = `translateX(${chip.offsetLeft}px)`;
  pill.style.width = `${chip.offsetWidth}px`;
  pillPlaced = true;
  const target = chip.offsetLeft - bar.clientWidth / 2 + chip.offsetWidth / 2;
  bar.scrollTo({ left: target, behavior: animate ? "smooth" : "auto" });
}

function renderList(v: View) {
  renderChips(v);
  const cards = $("cards");
  cards.replaceChildren();
  let title = "";
  let sub = "";
  let items: string[] = [];
  let subfolders: FolderNode[] = [];

  if (v.kind === "all") {
    title = "All notes";
    items = [...notes].sort((a, b) => store.mtime(b) - store.mtime(a));
    sub = `${notes.length} note${notes.length === 1 ? "" : "s"}`;
  } else if (v.kind === "folder") {
    const f = findFolder(tree, v.path);
    title = baseName(v.path);
    subfolders = (f?.children.filter((c) => c.kind === "folder") as FolderNode[]) ?? [];
    items = f?.children.filter((c) => c.kind !== "folder").map((c) => c.path) ?? [];
    const n = f ? countNotes(f) : 0;
    sub = `${n} note${n === 1 ? "" : "s"}${parentOf(v.path) ? ` · in ${parentOf(v.path)}` : ""}`;
  } else if (v.kind === "tag") {
    const t = tags().find((x) => x.id === v.id);
    title = t?.name ?? "Tag";
    const tagged = ws.pathsWithTag(v.id);
    subfolders = tagged.filter(isFolderPath).map((p) => findFolder(tree, p)!).filter(Boolean);
    items = tagged.filter((p) => files.includes(p));
    sub = `${tagged.length} item${tagged.length === 1 ? "" : "s"}`;
  }
  $("list-title").textContent = title;
  $("list-sub").textContent = sub;

  for (const f of subfolders) cards.append(folderCard(f));
  for (const p of items) cards.append(isImage(p) ? imageCard(p) : noteCard(p));
  if (!subfolders.length && !items.length) {
    const e = document.createElement("div");
    e.className = "cards-empty";
    e.innerHTML =
      v.kind === "tag"
        ? `<svg width="22" height="22"><use href="#i-tag"/></svg><p>Nothing tagged yet. Use the tag button on a note, or right-click any item.</p>`
        : `<svg width="22" height="22"><use href="#i-note"/></svg><p>This folder is empty.</p>`;
    cards.append(e);
  }
}

function noteCard(path: string): HTMLElement {
  const card = document.createElement("button");
  card.type = "button";
  card.className = "card note-card";
  card.dataset.path = path;
  const top = document.createElement("div");
  top.className = "card-top";
  const t = document.createElement("span");
  t.className = "card-title";
  t.textContent = displayName(baseName(path));
  top.append(t);
  if (ws.isPinned(path)) top.insertAdjacentHTML("beforeend", `<svg class="pin-mark" width="13" height="13"><use href="#i-pin-fill"/></svg>`);
  const p = document.createElement("p");
  p.className = "card-preview";
  p.textContent = previews.get(path) || "Empty note";
  const meta = document.createElement("div");
  meta.className = "card-meta";
  const where = document.createElement("span");
  where.textContent = [view.kind !== "folder" ? parentOf(path) : "", relativeTime(store.mtime(path))].filter(Boolean).join(" · ");
  meta.append(where, tagDots(path));
  card.append(top, p, meta);
  card.addEventListener("click", () => void go({ kind: "note", path }, { from: card }));
  addCardMenu(card, path);
  return card;
}

function folderCard(f: FolderNode): HTMLElement {
  const card = document.createElement("button");
  card.type = "button";
  card.className = "card folder-card";
  card.dataset.path = f.path;
  const n = countNotes(f);
  card.innerHTML = `<div class="card-top"><svg class="ico" width="18" height="18"><use href="#i-folder"/></svg><span class="card-title"></span>${ws.isPinned(f.path) ? `<svg class="pin-mark" width="13" height="13"><use href="#i-pin-fill"/></svg>` : ""}</div><div class="card-meta"><span>${n} note${n === 1 ? "" : "s"}</span></div>`;
  card.querySelector(".card-title")!.textContent = f.name;
  card.querySelector(".card-meta")!.append(tagDots(f.path));
  card.addEventListener("click", () => void go({ kind: "folder", path: f.path }, { direction: "forward" }));
  addCardMenu(card, f.path);
  return card;
}

function imageCard(path: string): HTMLElement {
  const card = document.createElement("button");
  card.type = "button";
  card.className = "card image-card";
  card.dataset.path = path;
  const img = document.createElement("img");
  img.alt = baseName(path);
  img.loading = "lazy";
  void imageUrl(path).then((u) => u && (img.src = u));
  const cap = document.createElement("span");
  cap.textContent = baseName(path);
  card.append(img, cap);
  card.addEventListener("click", () => void go({ kind: "image", path }, { from: card }));
  addCardMenu(card, path);
  return card;
}

function addCardMenu(card: HTMLElement, path: string) {
  card.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    itemMenu(path, card, e);
  });
}

// ---------------------------------------------------------------------------
// Toolbar and breadcrumbs

function renderCrumbs() {
  const crumbs = $("crumbs");
  crumbs.replaceChildren();
  const add = (text: string, target?: View, current = false) => {
    if (crumbs.childElementCount) {
      const sep = document.createElement("span");
      sep.className = "sep";
      sep.textContent = "/";
      crumbs.append(sep);
    }
    const el = document.createElement(target ? "button" : "span");
    el.className = current ? "crumb current" : "crumb";
    el.textContent = text;
    if (target) {
      (el as HTMLButtonElement).type = "button";
      el.addEventListener("click", () => void go(target, { direction: "back" }));
    }
    if (current && view.kind === "note") {
      el.title = "Rename";
      el.addEventListener("click", () => {
        const t = $<HTMLInputElement>("note-title");
        t.focus();
        t.select();
      });
    }
    crumbs.append(el);
  };

  if (view.kind === "all") return add("All notes", undefined, true);
  if (view.kind === "tag") {
    const id = view.id;
    add("Tags", { kind: "all" });
    return add(tags().find((t) => t.id === id)?.name ?? "", undefined, true);
  }
  const path = view.path;
  const parts = path.split("/");
  if (view.kind !== "folder" && listView.kind === "tag") {
    const t = tags().find((x) => x.id === (listView as { id: string }).id);
    add(t?.name ?? "Tag", listView);
  } else {
    add("Notes", { kind: "all" });
    parts.slice(0, -1).forEach((_, i) => add(parts[i], { kind: "folder", path: parts.slice(0, i + 1).join("/") }));
  }
  const last = parts[parts.length - 1];
  add(view.kind === "note" ? displayName(last) : last, undefined, true);
}

function renderToolbar() {
  const v = view;
  const onNote = v.kind === "note";
  $("note-actions").hidden = !onNote;
  $("list-actions").hidden = !(view.kind === "all" || view.kind === "folder" || view.kind === "tag");
  $("list-pin-btn").hidden = view.kind !== "folder";
  if (v.kind === "note" || v.kind === "folder") {
    const pinned = ws.isPinned(v.path);
    for (const id of ["pin-btn", "list-pin-btn"]) {
      const b = $(id);
      b.classList.toggle("on", pinned);
      b.title = pinned ? "Unpin" : "Pin";
      b.setAttribute("aria-label", b.title);
      b.querySelector("use")!.setAttribute("href", pinned ? "#i-pin-fill" : "#i-pin");
    }
  }
  if (v.kind === "note") $("tag-btn").classList.toggle("on", ws.tagsOf(v.path).length > 0);
}

// ---------------------------------------------------------------------------
// Editor and preview

function renderPreview() {
  if (currentMode() === "write" || !open) return;
  const preview = $("preview");
  preview.innerHTML = renderMarkdown(editor.getText(), open.path);
  preview.querySelectorAll<HTMLInputElement>('input[type="checkbox"]').forEach((b) => (b.disabled = false));
  preview.querySelectorAll<HTMLImageElement>("img").forEach((img) => {
    const src = img.getAttribute("src") ?? "";
    if (/^[a-z][a-z0-9+.-]*:/i.test(src)) return;
    const path = decodeURIComponent(src);
    img.removeAttribute("src");
    img.classList.add("loading");
    void imageUrl(path).then((u) => {
      img.classList.remove("loading");
      if (u) img.src = u;
      else img.classList.add("missing");
    });
  });
}

async function imageUrl(path: string): Promise<string | undefined> {
  const cached = imageUrls.get(path);
  if (cached) return cached;
  const blob = await store.readBlob(path);
  if (!blob) return undefined;
  const url = URL.createObjectURL(blob);
  imageUrls.set(path, url);
  return url;
}

function forgetImage(path: string) {
  const u = imageUrls.get(path);
  if (u) URL.revokeObjectURL(u);
  imageUrls.delete(path);
}

function onEdit() {
  window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => void flushSave(), SAVE_DELAY);
  if (currentMode() === "split") renderPreview();
  scheduleSync(SYNC_AFTER_EDIT);
}

async function flushSave() {
  window.clearTimeout(saveTimer);
  saveTimer = undefined;
  if (!open) return;
  const text = editor.getText();
  if (text === open.saved) return;
  await store.write(open.path, text);
  open.saved = text;
  previews.set(open.path, excerpt(text, 160));
}

/** Save images into Attachments/ and insert Markdown links at the cursor. */
async function insertImages(list: Iterable<File>) {
  if (!open) return;
  const notePath = open.path;
  const snippets: string[] = [];
  for (const file of list) {
    if (!file.type.startsWith("image/")) continue;
    const blob = await shrinkImage(file);
    const ext = blob.type === "image/jpeg" ? "jpg" : (file.name.split(".").pop() || "png").toLowerCase();
    const stem = (file.name.replace(/\.[^.]+$/, "") || "image").replace(/[\\/:()[\]#?]/g, "").trim() || "image";
    let path = `${ATTACHMENTS}/${stem}.${ext}`;
    for (let n = 2; files.includes(path); n++) path = `${ATTACHMENTS}/${stem}-${n}.${ext}`;
    await store.write(path, blob);
    files.push(path);
    snippets.push(`![${stem}](${relativeLink(notePath, path)})`);
  }
  if (!snippets.length) return;
  const { from, to } = editor.view.state.selection.main;
  const text = snippets.join("\n") + "\n";
  editor.view.dispatch({ changes: { from, to, insert: text }, selection: { anchor: from + text.length } });
  onEdit();
  await refresh();
  toast(snippets.length === 1 ? "Image added" : `${snippets.length} images added`);
}

/** Downscale big photos to at most 2560px / JPEG 85% so notes stay light to sync. */
async function shrinkImage(file: File): Promise<Blob> {
  if (!/image\/(jpeg|png|webp|heic)/.test(file.type) || file.size < 1_500_000) return file;
  try {
    const bmp = await createImageBitmap(file);
    const scale = Math.min(1, 2560 / Math.max(bmp.width, bmp.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(bmp.width * scale);
    canvas.height = Math.round(bmp.height * scale);
    canvas.getContext("2d")!.drawImage(bmp, 0, 0, canvas.width, canvas.height);
    const out = await new Promise<Blob | null>((r) => canvas.toBlob(r, "image/jpeg", 0.85));
    return out && out.size < file.size ? out : file;
  } catch {
    return file;
  }
}

// ---------------------------------------------------------------------------
// Create / rename / delete

const withExtension = (name: string) => (/\.(md|markdown|txt)$/i.test(name) ? name : `${name}.md`);
const joinPath = (folder: string, name: string) => (folder ? `${folder}/${name}` : name);

function currentFolder(): string {
  if (view.kind === "folder") return view.path;
  if (view.kind === "note" || view.kind === "image") return parentOf(view.path);
  return "";
}

/** New note, no name prompt: "Untitled note (n)", named later from the title field. */
async function newNote(folder = currentFolder()) {
  const path = nextUntitled(notes, folder);
  await store.write(path, "");
  if (view.kind === "tag") {
    ws.setTag(path, view.id, true);
    await saveWorkspace();
  }
  await refresh();
  await go({ kind: "note", path });
  const title = $<HTMLInputElement>("note-title");
  title.focus();
  scheduleSync(SYNC_AFTER_EDIT);
}

async function newFolder(parent = currentFolder()) {
  const name = await ask({
    title: "New folder",
    text: parent ? `In ${parent}/` : "",
    value: "",
    placeholder: "Folder name",
    validate: (v) => (v.trim() ? validatePath(joinPath(parent, v.trim())) ?? undefined : "Enter a name."),
  });
  if (!name) return;
  const path = joinPath(parent, name.trim());
  await store.setFolders([...folders, path]);
  expanded.add(path);
  if (parent) expanded.add(parent);
  await refresh();
  await go({ kind: "folder", path });
}

async function renameItem(path: string) {
  const folder = isFolderPath(path);
  const next = await ask({
    title: folder ? "Rename folder" : "Rename",
    text: "Change the name, or the folder part to move it.",
    value: folder ? path : path.replace(/\.md$/i, ""),
    validate: (v) => {
      const p = folder || isImage(path) ? v.trim() : withExtension(v.trim());
      if (!v.trim()) return "Enter a name.";
      if (p === path) return undefined;
      return validatePath(p) ?? (files.includes(p) || isFolderPath(p) ? "Something with that name already exists." : undefined);
    },
  });
  if (next === undefined) return;
  const target = folder || isImage(path) ? next.trim() : withExtension(next.trim());
  await moveItem(path, target);
}

/** Rename/move a note, image or folder (and everything inside), carrying pins and tags along. */
async function moveItem(path: string, target: string) {
  if (target === path) return;
  await flushSave();
  const moved = (p: string) => (p === path ? target : p.startsWith(`${path}/`) ? target + p.slice(path.length) : p);
  // Point the open note at its new path first, so anything typed while files move is saved there.
  const openOld = open?.path;
  if (open && moved(open.path) !== open.path) open = { path: moved(open.path), saved: open.saved };
  for (const f of files.filter((f) => f === path || f.startsWith(`${path}/`))) {
    const value = f === openOld && open ? editor.getText() : await store.read(f);
    if (value === undefined) continue;
    if (f === openOld && open) open.saved = value as string;
    await store.write(moved(f), value);
    await store.remove(f);
    forgetImage(f);
  }
  await store.setFolders(folders.map(moved));
  [...expanded].forEach((e) => {
    if (moved(e) !== e) {
      expanded.delete(e);
      expanded.add(moved(e));
    }
  });
  ws.renamePath(path, target);
  await saveWorkspace();
  // Anything typed during the move is saved to the new path by the normal autosave.
  if (open && editor.getText() !== open.saved) onEdit();
  await refresh();
  if (view.kind === "note" && moved(view.path) !== view.path) {
    // Same note, new name: keep the editor (and cursor) as is, just update where it lives.
    view = { kind: "note", path: moved(view.path) };
    localStorage.setItem("dnote.view", JSON.stringify(view));
    renderCrumbs();
    renderSidebar();
    renderToolbar();
  } else if (view.kind !== "all" && view.kind !== "tag" && moved(view.path) !== view.path) {
    await go({ ...view, path: moved(view.path) } as View, { animate: false });
  } else renderCrumbs();
  scheduleSync(500);
}

const isUntitled = (path: string) => displayName(baseName(path)).startsWith("Untitled note (");

/** Show the open note's name in the title field (empty for "Untitled note (n)", shown as placeholder). */
function renderTitle() {
  const input = $<HTMLInputElement>("note-title");
  if (!open) return;
  input.placeholder = displayName(baseName(open.path));
  if (document.activeElement !== input) input.value = isUntitled(open.path) ? "" : displayName(baseName(open.path));
}

/** Rename the open note from the title field. Empty or unchanged titles keep the current name. */
async function commitTitle() {
  if (!open) return;
  const input = $<HTMLInputElement>("note-title");
  const name = titleToName(input.value);
  const path = open.path;
  if (!name || name === displayName(baseName(path))) {
    renderTitle();
    return;
  }
  const target = joinPath(parentOf(path), `${name}.md`);
  if (target.toLowerCase() !== path.toLowerCase() && (files.includes(target) || isFolderPath(target))) {
    toast(`“${name}” already exists here`);
    input.value = isUntitled(path) ? "" : displayName(baseName(path));
    return;
  }
  const err = validatePath(target);
  if (err) {
    toast(err);
    return;
  }
  await moveItem(path, target);
  renderTitle();
}

async function deleteItem(path: string) {
  const folder = isFolderPath(path);
  const inside = files.filter((f) => f.startsWith(`${path}/`) && (isNote(f) || isImage(f)));
  const ok = await confirmDialog(
    folder ? "Delete folder?" : "Delete?",
    folder
      ? inside.length
        ? `“${path}” and the ${inside.length} file${inside.length === 1 ? "" : "s"} in it will be deleted on every synced device.`
        : `“${path}” is empty.`
      : `“${displayName(baseName(path))}” will be deleted on every synced device.`,
    "Delete",
  );
  if (!ok) return;
  for (const f of folder ? inside : [path]) {
    await store.remove(f);
    forgetImage(f);
  }
  if (folder) await store.setFolders(folders.filter((f) => f !== path && !f.startsWith(`${path}/`)));
  ws.removePath(path);
  await saveWorkspace();
  const gone = view.kind !== "all" && view.kind !== "tag" && (view.path === path || view.path.startsWith(`${path}/`));
  if (gone) open = undefined;
  await refresh();
  if (gone) await go(folder ? { kind: "all" } : listView.kind === "folder" && listView.path.startsWith(path) ? { kind: "all" } : listView, { direction: "back" });
  else if (view.kind !== "note" && view.kind !== "image") renderList(view);
  scheduleSync(500);
}

async function togglePin(path: string) {
  const on = ws.togglePin(path);
  await saveWorkspace();
  toast(on ? "Pinned" : "Unpinned");
}

// ---------------------------------------------------------------------------
// Tags UI

function showTagPopover(path: string, anchor: HTMLElement) {
  hideMenu();
  const pop = $("tag-pop");
  const render = () => {
    pop.replaceChildren();
    const head = document.createElement("div");
    head.className = "tp-head";
    head.textContent = "Tags";
    pop.append(head);
    const mine = new Set(ws.tagsOf(path));
    for (const t of tags()) {
      const row = document.createElement("button");
      row.type = "button";
      row.className = "tp-row";
      row.setAttribute("role", "menuitemcheckbox");
      row.setAttribute("aria-checked", String(mine.has(t.id)));
      const check = document.createElement("span");
      check.className = "tp-check";
      check.innerHTML = `<svg width="14" height="14"><use href="#i-check"/></svg>`;
      const name = document.createElement("span");
      name.textContent = t.name;
      row.append(check, tagGlyph(t), name);
      row.addEventListener("click", async () => {
        ws.setTag(path, t.id, !mine.has(t.id));
        await saveWorkspace();
        render();
      });
      pop.append(row);
    }
    const form = document.createElement("form");
    form.className = "tp-new";
    const input = document.createElement("input");
    input.placeholder = "New tag";
    input.maxLength = 40;
    const add = document.createElement("button");
    add.type = "submit";
    add.className = "icon-sm";
    add.setAttribute("aria-label", "Create tag");
    add.innerHTML = `<svg width="15" height="15"><use href="#i-plus"/></svg>`;
    form.append(input, add);
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (!input.value.trim()) return;
      try {
        const id = ws.createTag(input.value, "");
        ws.setTag(path, id, true);
        await saveWorkspace();
        render();
        (pop.querySelector(".tp-new input") as HTMLInputElement)?.focus();
      } catch (err) {
        toast(String(err));
      }
    });
    pop.append(form);
  };
  render();
  pop.hidden = false;
  placePopover(pop, anchor);
}

function placePopover(pop: HTMLElement, anchor: HTMLElement, at?: MouseEvent) {
  const r = anchor.getBoundingClientRect();
  const w = pop.offsetWidth;
  const h = pop.offsetHeight;
  const x = at ? at.clientX : r.right - w;
  const y = at ? at.clientY : r.bottom + 6;
  pop.style.left = `${Math.max(8, Math.min(x, window.innerWidth - w - 8))}px`;
  pop.style.top = `${Math.max(8, Math.min(y, window.innerHeight - h - 8))}px`;
}

async function createTag() {
  const result = await ask({ title: "New tag", value: "", placeholder: "Tag name", colors: true, validate: (v) => (v.trim() ? undefined : "Enter a name.") });
  if (!result) return;
  try {
    const id = ws.createTag(result, pickedColor);
    await saveWorkspace();
    await go({ kind: "tag", id });
  } catch (err) {
    toast(String(err));
  }
}

async function editTag(t: Tag) {
  pickedColor = t.color;
  const name = await ask({ title: "Edit tag", value: t.name, colors: true, validate: (v) => (v.trim() ? undefined : "Enter a name.") });
  if (name === undefined) return;
  ws.updateTag(t.id, name, pickedColor);
  await saveWorkspace();
  renderCrumbs();
}

function tagMenu(t: Tag, anchor: HTMLElement) {
  if (t.id === "favorite") {
    showMenu(anchor, [["Show favorites", () => void go({ kind: "tag", id: t.id })]]);
    return;
  }
  showMenu(anchor, [
    ["Rename or recolour…", () => void editTag(t)],
    [
      "Delete tag",
      async () => {
        if (!(await confirmDialog("Delete tag?", `“${t.name}” is removed from every note. The notes stay.`, "Delete"))) return;
        ws.deleteTag(t.id);
        await saveWorkspace();
        if (view.kind === "tag" && view.id === t.id) await go({ kind: "all" });
      },
      "danger",
    ],
  ]);
}

function itemMenu(path: string, anchor: HTMLElement, at?: MouseEvent) {
  const folder = isFolderPath(path);
  const pinned = ws.isPinned(path);
  const items: [string, () => void, string?][] = [];
  if (folder) items.push(["New note here", () => void newNote(path)], ["New folder here", () => void newFolder(path)]);
  items.push(
    [pinned ? "Unpin" : "Pin", () => void togglePin(path)],
    ["Tags…", () => showTagPopover(path, anchor)],
    ["Rename or move…", () => void renameItem(path)],
    ["Delete", () => void deleteItem(path), "danger"],
  );
  showMenu(anchor, items, at);
}

// ---------------------------------------------------------------------------
// Sync

function scheduleSync(delay: number) {
  if (!settings) return;
  window.clearTimeout(syncTimer);
  syncTimer = window.setTimeout(() => void syncNow(), delay);
}

async function syncNow() {
  if (!settings) return updateSyncLabel();
  if (!navigator.onLine) return updateSyncLabel();
  if (syncing) {
    syncQueued = true;
    return;
  }
  syncing = true;
  updateSyncLabel();
  await flushSave();

  const changed = new Set<string>();
  const fs = {
    listFiles: () => store.listFiles(),
    read: (p: string) => store.read(p),
    write: async (p: string, c: string | Uint8Array) => {
      changed.add(p);
      forgetImage(p);
      await store.write(p, c);
    },
    remove: async (p: string) => {
      changed.add(p);
      forgetImage(p);
      await store.remove(p);
    },
    loadState: () => store.loadState(),
    saveState: (s: string) => store.saveState(s),
  };

  try {
    const report = (await runSync(fs, settings.server, settings.token)) as SyncReport;
    lastSync = Date.now();
    lastError = undefined;
    showConflicts(report.conflicts);
    if (report.skipped.length) toast(`Not synced (invalid name): ${report.skipped.slice(0, 3).join(", ")}`);
  } catch (e) {
    lastError = String(e);
  } finally {
    syncing = false;
  }

  if (changed.size) {
    await refresh();
    if (open && changed.has(open.path)) await reconcileOpenNote();
    else if (view.kind === "image" && changed.has(view.path)) await applyView(view);
    else if (view.kind !== "note" && view.kind !== "image") await applyView(exists(view) ? view : { kind: "all" });
    renderToolbar();
  }
  updateSyncLabel();
  if (syncQueued) {
    syncQueued = false;
    void syncNow();
  }
}

/** A sync rewrote the open note: merge with anything typed while it ran. */
async function reconcileOpenNote() {
  if (!open) return;
  const stored = await store.readText(open.path);
  if (stored === undefined) {
    toast("This note was deleted on another device.");
    open = undefined;
    await go(listView, { direction: "back" });
    return;
  }
  const current = editor.getText();
  const next = current === open.saved ? stored : merge3(open.saved, current, stored);
  open.saved = stored;
  if (next !== current) editor.setText(next);
  if (next !== stored) onEdit();
  renderPreview();
}

function updateSyncLabel() {
  const btn = $("sync-btn");
  let state = "local";
  let text = "On this device";
  if (settings) {
    if (syncing) [state, text] = ["busy", "Syncing…"];
    else if (!navigator.onLine) [state, text] = ["offline", "Offline"];
    else if (lastError) [state, text] = ["error", "Sync failed"];
    else if (lastSync) [state, text] = ["ok", `Synced ${new Date(lastSync).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}`];
    else [state, text] = ["busy", "Connecting…"];
  }
  btn.dataset.state = state;
  $("sync-label").textContent = text;
  btn.title = lastError ?? (lastSync ? `Last synced ${new Date(lastSync).toLocaleTimeString()}` : "Sync now");
  $("settings-label").textContent = settings ? hostOf(settings.server) : "Connect a server";
}

function hostOf(url: string) {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

function showConflicts(paths: string[]) {
  const banner = $("banner");
  if (!paths.length) {
    banner.hidden = true;
    return;
  }
  banner.hidden = false;
  banner.textContent = "";
  const msg = document.createElement("span");
  msg.textContent = `Edits clashed in ${paths.length === 1 ? displayName(baseName(paths[0])) : `${paths.length} files`}. Both versions were kept.`;
  const link = document.createElement("button");
  link.type = "button";
  link.textContent = "Open";
  link.addEventListener("click", () => void go(isImage(paths[0]) ? { kind: "image", path: paths[0] } : { kind: "note", path: paths[0] }));
  const close = document.createElement("button");
  close.type = "button";
  close.textContent = "Dismiss";
  close.addEventListener("click", () => (banner.hidden = true));
  banner.append(msg, link, close);
}

// ---------------------------------------------------------------------------
// Settings

function openSettings() {
  ($("set-server") as HTMLInputElement).value = settings?.server ?? location.origin;
  ($("set-token") as HTMLInputElement).value = settings?.token ?? "";
  $("set-disconnect").hidden = !settings;
  setStatus("");
  $<HTMLDialogElement>("settings").showModal();
}

function setStatus(text: string, kind: "ok" | "error" | "" = "") {
  const el = $("set-status");
  el.hidden = !text;
  el.textContent = text;
  el.dataset.kind = kind;
}

async function saveSettings(e: Event) {
  e.preventDefault();
  const server = ($("set-server") as HTMLInputElement).value.trim().replace(/\/+$/, "");
  const token = ($("set-token") as HTMLInputElement).value.trim();
  if (!server || !token) return setStatus("Enter the server URL and token.", "error");
  setStatus("Checking connection…");
  try {
    const health = await fetch(`${server}/api/health`);
    if (!health.ok) throw new Error(`server answered ${health.status}`);
    const info = await health.json();
    const auth = await fetch(`${server}/api/changes?since=0`, { headers: { Authorization: `Bearer ${token}` } });
    if (auth.status === 401) throw new Error("the token was rejected");
    if (!auth.ok) throw new Error(`server answered ${auth.status}`);
    settings = { server, token };
    await store.set("settings", settings);
    setStatus(`Connected to ${info.backend ?? "server"}.`, "ok");
    $<HTMLDialogElement>("settings").close();
    closeSidebar();
    lastError = undefined;
    void syncNow();
  } catch (err) {
    setStatus(`Couldn't connect: ${err instanceof Error ? err.message : err}`, "error");
  }
}

async function disconnect() {
  settings = undefined;
  lastSync = undefined;
  lastError = undefined;
  await store.set("settings", undefined);
  $<HTMLDialogElement>("settings").close();
  updateSyncLabel();
  toast("Disconnected. Notes stay on this device.");
}

// ---------------------------------------------------------------------------
// Dialogs, menus, toasts

interface AskOptions {
  title: string;
  text?: string;
  value: string;
  placeholder?: string;
  colors?: boolean;
  validate?: (v: string) => string | undefined;
}

let pickedColor = "";

function renderSwatches() {
  const box = $("dialog-colors");
  box.replaceChildren();
  const colors = tagColors();
  if (!colors.includes(pickedColor)) pickedColor = colors[(tags().length - 1) % colors.length];
  for (const c of colors) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = `swatch c-${c}`;
    b.setAttribute("aria-label", c);
    b.setAttribute("aria-pressed", String(c === pickedColor));
    b.addEventListener("click", () => {
      pickedColor = c;
      renderSwatches();
    });
    box.append(b);
  }
}

function ask(opts: AskOptions): Promise<string | undefined> {
  const dlg = $<HTMLDialogElement>("dialog");
  const input = $<HTMLInputElement>("dialog-input");
  const error = $("dialog-error");
  $("dialog-title").textContent = opts.title;
  $("dialog-text").textContent = opts.text ?? "";
  $("dialog-text").hidden = !opts.text;
  $("dialog-ok").textContent = "OK";
  $("dialog-ok").classList.remove("danger-fill");
  $("dialog-colors").hidden = !opts.colors;
  if (opts.colors) renderSwatches();
  input.hidden = false;
  input.value = opts.value;
  input.placeholder = opts.placeholder ?? "";
  error.hidden = true;

  return new Promise((resolve) => {
    const form = $<HTMLFormElement>("dialog-form");
    const cleanup = (result: string | undefined) => {
      form.removeEventListener("submit", onSubmit);
      $("dialog-cancel").removeEventListener("click", onCancel);
      dlg.removeEventListener("cancel", onCancel);
      input.removeEventListener("input", onInput);
      dlg.close();
      resolve(result);
    };
    const onInput = () => (error.hidden = true);
    const onSubmit = (e: Event) => {
      e.preventDefault();
      const msg = opts.validate?.(input.value);
      if (msg) {
        error.textContent = msg;
        error.hidden = false;
        return;
      }
      cleanup(input.value);
    };
    const onCancel = (e: Event) => {
      e.preventDefault();
      cleanup(undefined);
    };
    form.addEventListener("submit", onSubmit);
    $("dialog-cancel").addEventListener("click", onCancel);
    dlg.addEventListener("cancel", onCancel);
    input.addEventListener("input", onInput);
    dlg.showModal();
    input.focus();
    const slash = input.value.lastIndexOf("/");
    input.setSelectionRange(slash + 1, input.value.length);
  });
}

function confirmDialog(title: string, text: string, okLabel: string): Promise<boolean> {
  const dlg = $<HTMLDialogElement>("dialog");
  $("dialog-title").textContent = title;
  $("dialog-text").textContent = text;
  $("dialog-text").hidden = false;
  $("dialog-input").hidden = true;
  $("dialog-colors").hidden = true;
  $("dialog-error").hidden = true;
  $("dialog-ok").textContent = okLabel;
  $("dialog-ok").classList.add("danger-fill");
  return new Promise((resolve) => {
    const form = $<HTMLFormElement>("dialog-form");
    const done = (v: boolean) => (e: Event) => {
      e.preventDefault();
      form.removeEventListener("submit", yes);
      $("dialog-cancel").removeEventListener("click", no);
      dlg.removeEventListener("cancel", no);
      dlg.close();
      resolve(v);
    };
    const yes = done(true);
    const no = done(false);
    form.addEventListener("submit", yes);
    $("dialog-cancel").addEventListener("click", no);
    dlg.addEventListener("cancel", no);
    dlg.showModal();
    $("dialog-ok").focus();
  });
}

function showMenu(anchor: HTMLElement, items: [string, () => void, string?][], at?: MouseEvent) {
  $("tag-pop").hidden = true;
  const menu = $("menu");
  menu.replaceChildren();
  for (const [label, action, cls] of items) {
    const b = document.createElement("button");
    b.type = "button";
    b.setAttribute("role", "menuitem");
    b.textContent = label;
    if (cls) b.className = cls;
    b.addEventListener("click", (e) => {
      e.stopPropagation();
      hideMenu();
      action();
    });
    menu.append(b);
  }
  menu.hidden = false;
  placePopover(menu, anchor, at);
  (menu.firstElementChild as HTMLElement | null)?.focus();
}

function hideMenu() {
  $("menu").hidden = true;
}

let toastTimer: number | undefined;
function toast(text: string) {
  const el = $("toast");
  el.textContent = text;
  el.hidden = false;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => (el.hidden = true), 2600);
}

// ---------------------------------------------------------------------------
// Layout

function currentMode(): Mode {
  return ($("panes").dataset.mode as Mode) ?? "write";
}

function setMode(mode: Mode, render = true) {
  if (mode === "split" && isPhone()) mode = "write";
  $("panes").dataset.mode = mode;
  localStorage.setItem("dnote.mode", mode);
  for (const b of $("mode").querySelectorAll<HTMLButtonElement>("button")) {
    b.setAttribute("aria-pressed", String(b.dataset.mode === mode));
  }
  if (render) renderPreview();
}

type Theme = "system" | "light" | "dark";
const THEME_ICONS: Record<Theme, string> = {
  system: `<svg viewBox="0 0 16 16" width="15" height="15"><circle cx="8" cy="8" r="5.75" fill="none" stroke="currentColor" stroke-width="1.3"/><path d="M8 2.25a5.75 5.75 0 0 1 0 11.5z" fill="currentColor"/></svg>`,
  light: `<svg viewBox="0 0 16 16" width="15" height="15"><circle cx="8" cy="8" r="3" fill="none" stroke="currentColor" stroke-width="1.3"/><path d="M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>`,
  dark: `<svg viewBox="0 0 16 16" width="15" height="15"><path d="M13.25 9.6A5.5 5.5 0 0 1 6.4 2.75a5.5 5.5 0 1 0 6.85 6.85z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>`,
};

function currentTheme(): Theme {
  const t = localStorage.getItem("dnote.theme");
  return t === "light" || t === "dark" ? t : "system";
}

function applyTheme(theme: Theme) {
  if (theme === "system") {
    delete document.documentElement.dataset.theme;
    localStorage.removeItem("dnote.theme");
  } else {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("dnote.theme", theme);
  }
  const btn = $("theme-btn");
  btn.innerHTML = THEME_ICONS[theme];
  const label = `Theme: ${theme === "system" ? "match system" : theme}`;
  btn.title = label;
  btn.setAttribute("aria-label", label);
}

const openSidebar = () => $("app").classList.add("sidebar-open");
const closeSidebar = () => $("app").classList.remove("sidebar-open");

// ---------------------------------------------------------------------------
// Search

async function runSearch() {
  const query = ($("search") as HTMLInputElement).value.trim();
  const results = $("search-results");
  const sections = document.querySelectorAll<HTMLElement>("#nav > .section, #nav-all");
  if (!query) {
    results.hidden = true;
    sections.forEach((s) => (s.hidden = false));
    $("pinned-section").hidden = ws.pins().length === 0;
    return;
  }
  const q = query.toLowerCase();
  const hits: { path: string; snippet: string }[] = [];
  for (const [path, content] of await allNotes(store)) {
    const nameHit = path.toLowerCase().includes(q);
    const idx = content.toLowerCase().indexOf(q);
    if (!nameHit && idx < 0) continue;
    hits.push({ path, snippet: idx >= 0 ? content.slice(Math.max(0, idx - 30), idx + q.length + 50).replace(/\s+/g, " ") : "" });
  }
  if (($("search") as HTMLInputElement).value.trim() !== query) return;
  sections.forEach((s) => (s.hidden = true));
  results.hidden = false;
  results.replaceChildren();
  if (!hits.length) return results.append(emptyLine("No matches."));
  for (const h of hits.slice(0, 200)) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "result";
    const t = document.createElement("span");
    t.className = "result-title";
    t.textContent = displayName(baseName(h.path));
    const m = document.createElement("span");
    m.className = "result-meta";
    m.textContent = h.snippet ? `…${h.snippet}…` : parentOf(h.path) || "Top level";
    row.append(t, m);
    row.addEventListener("click", () => void go({ kind: "note", path: h.path }));
    results.append(row);
  }
}

// ---------------------------------------------------------------------------
// Wiring

function wireUi() {
  applyTheme(currentTheme());
  $("theme-btn").addEventListener("click", () => {
    const order: Theme[] = ["system", "light", "dark"];
    applyTheme(order[(order.indexOf(currentTheme()) + 1) % order.length]);
  });
  $("home-link").addEventListener("click", (e) => {
    e.preventDefault();
    void go({ kind: "all" }, { direction: "back" });
  });
  $("nav-all").addEventListener("click", () => void go({ kind: "all" }));
  $("new-note").addEventListener("click", () => void newNote());
  $("list-new").addEventListener("click", () => void newNote());
  $("empty-new").addEventListener("click", () => void newNote(""));
  $("new-folder").addEventListener("click", () => void newFolder());
  $("new-tag").addEventListener("click", () => {
    pickedColor = "";
    void createTag();
  });
  $("open-settings").addEventListener("click", openSettings);
  $("empty-settings").addEventListener("click", openSettings);
  $("sync-btn").addEventListener("click", () => (settings ? void syncNow() : openSettings()));
  $("settings-form").addEventListener("submit", (e) => void saveSettings(e));
  $("set-cancel").addEventListener("click", () => $<HTMLDialogElement>("settings").close());
  $("set-disconnect").addEventListener("click", () => void disconnect());
  $("search").addEventListener("input", () => void runSearch());
  $("menu-btn").addEventListener("click", openSidebar);
  $("scrim").addEventListener("click", closeSidebar);
  $("note-menu").addEventListener("click", (e) => {
    if (!open) return;
    const path = open.path;
    showMenu(e.currentTarget as HTMLElement, [
      ["Rename or move…", () => void renameItem(path)],
      ["Copy Markdown", () => void navigator.clipboard.writeText(editor.getText()).then(() => toast("Copied"))],
      ["Delete", () => void deleteItem(path), "danger"],
    ]);
  });
  const titleInput = $<HTMLInputElement>("note-title");
  titleInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || (e.key === "ArrowDown" && titleInput.selectionStart === titleInput.value.length)) {
      e.preventDefault();
      // Enter: keep the name and continue in the body.
      editor.focus();
    }
    if (e.key === "Escape") {
      e.stopPropagation();
      if (open) titleInput.value = isUntitled(open.path) ? "" : displayName(baseName(open.path));
      editor.focus();
    }
  });
  titleInput.addEventListener("blur", () => void commitTitle());
  $("pin-btn").addEventListener("click", () => open && void togglePin(open.path));
  $("list-pin-btn").addEventListener("click", () => view.kind === "folder" && void togglePin(view.path));
  $("tag-btn").addEventListener("click", (e) => open && showTagPopover(open.path, e.currentTarget as HTMLElement));
  $("image-btn").addEventListener("click", () => $<HTMLInputElement>("image-input").click());
  $<HTMLInputElement>("image-input").addEventListener("change", (e) => {
    const input = e.target as HTMLInputElement;
    if (input.files) void insertImages([...input.files]);
    input.value = "";
  });
  for (const b of $("mode").querySelectorAll<HTMLButtonElement>("button")) {
    b.addEventListener("click", () => setMode(b.dataset.mode as Mode));
  }

  // Paste or drop images into the editor.
  const dom = editor.view.dom;
  dom.addEventListener("paste", (e) => {
    const imgs = [...(e.clipboardData?.files ?? [])].filter((f) => f.type.startsWith("image/"));
    if (imgs.length) {
      e.preventDefault();
      void insertImages(imgs);
    }
  });
  dom.addEventListener("dragover", (e) => {
    if ([...(e.dataTransfer?.items ?? [])].some((i) => i.type.startsWith("image/"))) {
      e.preventDefault();
      $("note-view").classList.add("dropping");
    }
  });
  dom.addEventListener("dragleave", () => $("note-view").classList.remove("dropping"));
  dom.addEventListener("drop", (e) => {
    $("note-view").classList.remove("dropping");
    const imgs = [...(e.dataTransfer?.files ?? [])].filter((f) => f.type.startsWith("image/"));
    if (imgs.length) {
      e.preventDefault();
      const pos = editor.view.posAtCoords({ x: e.clientX, y: e.clientY });
      if (pos !== null) editor.view.dispatch({ selection: { anchor: pos } });
      void insertImages(imgs);
    }
  });

  // Links in the preview: vault paths open inside the app.
  $("preview").addEventListener("click", (e) => {
    const a = (e.target as HTMLElement).closest("a");
    if (!a || !open) return;
    const href = a.getAttribute("href") ?? "";
    if (href.startsWith("#")) return;
    if (/^[a-z][a-z0-9+.-]*:/i.test(href)) {
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      return;
    }
    e.preventDefault();
    const target = resolveLink("", href);
    if (target && notes.includes(target)) void go({ kind: "note", path: target });
    else if (target && isImage(target) && files.includes(target)) void go({ kind: "image", path: target });
    else toast(`Nothing at ${target ?? href}`);
  });
  $("preview").addEventListener("change", (e) => {
    const box = e.target as HTMLInputElement;
    if (box.type !== "checkbox") return;
    const boxes = [...$("preview").querySelectorAll('input[type="checkbox"]')];
    toggleTask(boxes.indexOf(box));
  });

  document.addEventListener("click", (e) => {
    const t = e.target as HTMLElement;
    if (!$("menu").hidden && !t.closest("#menu, .row-more")) hideMenu();
    if (!$("tag-pop").hidden && !t.closest("#tag-pop, #tag-btn, #menu")) $("tag-pop").hidden = true;
  });
  document.addEventListener("keydown", (e) => {
    const mod = e.metaKey || e.ctrlKey;
    if (e.key === "Escape") {
      const menuOpen = !$("menu").hidden || !$("tag-pop").hidden || !!document.querySelector("dialog[open]");
      hideMenu();
      $("tag-pop").hidden = true;
      // Esc closes a note/image back into the list it was opened from.
      if (!menuOpen && (view.kind === "note" || view.kind === "image")) void go(listView, { direction: "back" });
    }
    if (mod && e.key.toLowerCase() === "s") {
      e.preventDefault();
      void flushSave().then(() => syncNow());
    }
    if (mod && e.key.toLowerCase() === "k") {
      e.preventDefault();
      openSidebar();
      ($("search") as HTMLInputElement).focus();
      ($("search") as HTMLInputElement).select();
    }
    if (mod && e.altKey && e.key.toLowerCase() === "n") {
      e.preventDefault();
      void newNote();
    }
    if (mod && e.key.toLowerCase() === "e" && view.kind === "note") {
      e.preventDefault();
      setMode(currentMode() === "read" ? "write" : "read");
    }
  });
}

function toggleTask(index: number) {
  if (index < 0) return;
  const text = editor.getText();
  const re = /^(\s*(?:[-*+]|\d+[.)])\s+\[)([ xX])(\])/gm;
  let i = 0;
  const next = text.replace(re, (m, a: string, mark: string, b: string) => (i++ === index ? `${a}${mark === " " ? "x" : " "}${b}` : m));
  if (next !== text) {
    editor.setText(next);
    onEdit();
    renderPreview();
  }
}

void boot();
