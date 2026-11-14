export interface FolderNode {
  kind: "folder";
  name: string;
  path: string;
  children: TreeNode[];
}
export interface FileNode {
  kind: "note" | "image";
  name: string;
  path: string;
}
export type TreeNode = FolderNode | FileNode;

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });
const IMAGE_RE = /\.(png|jpe?g|gif|webp|svg|avif|heic)$/i;

/** Build a sorted folder tree from file paths (notes and images) and explicit folder paths. */
export function buildTree(files: string[], folders: string[]): FolderNode {
  const root: FolderNode = { kind: "folder", name: "", path: "", children: [] };
  const index = new Map<string, FolderNode>([["", root]]);

  const folder = (path: string): FolderNode => {
    const existing = index.get(path);
    if (existing) return existing;
    const slash = path.lastIndexOf("/");
    const parent = folder(slash < 0 ? "" : path.slice(0, slash));
    const node: FolderNode = { kind: "folder", name: path.slice(slash + 1), path, children: [] };
    parent.children.push(node);
    index.set(path, node);
    return node;
  };

  for (const f of folders) folder(f);
  for (const n of files) {
    if (n.startsWith(".")) continue;
    const slash = n.lastIndexOf("/");
    folder(slash < 0 ? "" : n.slice(0, slash)).children.push({
      kind: IMAGE_RE.test(n) ? "image" : "note",
      name: n.slice(slash + 1),
      path: n,
    });
  }

  const rank = (k: TreeNode["kind"]) => (k === "folder" ? 0 : k === "note" ? 1 : 2);
  const sort = (node: FolderNode) => {
    node.children.sort((a, b) => rank(a.kind) - rank(b.kind) || collator.compare(a.name, b.name));
    node.children.forEach((c) => c.kind === "folder" && sort(c));
  };
  sort(root);
  return root;
}

export function findFolder(root: FolderNode, path: string): FolderNode | undefined {
  if (!path) return root;
  for (const c of root.children) {
    if (c.kind !== "folder") continue;
    if (c.path === path) return c;
    if (path.startsWith(`${c.path}/`)) return findFolder(c, path);
  }
  return undefined;
}

export function countNotes(folder: FolderNode): number {
  return folder.children.reduce((n, c) => n + (c.kind === "note" ? 1 : c.kind === "folder" ? countNotes(c) : 0), 0);
}

export const parentOf = (path: string) => (path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "");
export const baseName = (path: string) => path.slice(path.lastIndexOf("/") + 1);
export const displayName = (name: string) => name.replace(/\.(md|markdown|txt)$/i, "");

export function relativeTime(ms: number): string {
  if (!ms) return "";
  const diff = Date.now() - ms;
  const min = Math.round(diff / 60000);
  if (min < 1) return "Just now";
  if (min < 60) return `${min}m ago`;
  const h = Math.round(min / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 7) return `${d}d ago`;
  return new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}
