// Local vault storage on IndexedDB. Files are keyed by their full path
// ("Projects/DNote/ideas.md"): notes and the workspace file as strings, images as Blobs.
// The folder tree is derived from the paths plus explicitly created (possibly empty) folders.

const DB_NAME = "dnote";
const FILES = "notes";
const KV = "kv";

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

export interface Settings {
  server: string;
  token: string;
}

export type FileValue = string | Blob;

const NOTE_RE = /\.(md|markdown|txt)$/i;
const IMAGE_RE = /\.(png|jpe?g|gif|webp|svg|avif|heic)$/i;
export const isNote = (p: string) => NOTE_RE.test(p);
export const isImage = (p: string) => IMAGE_RE.test(p);

export class LocalStore {
  private mtimes: Record<string, number> = {};

  private constructor(private db: IDBDatabase) {}

  static async open(): Promise<LocalStore> {
    const open = indexedDB.open(DB_NAME, 1);
    open.onupgradeneeded = () => {
      const db = open.result;
      if (!db.objectStoreNames.contains(FILES)) db.createObjectStore(FILES);
      if (!db.objectStoreNames.contains(KV)) db.createObjectStore(KV);
    };
    const store = new LocalStore(await req(open));
    store.mtimes = (await store.get<Record<string, number>>("mtimes")) ?? {};
    return store;
  }

  private store(name: string, mode: IDBTransactionMode = "readonly") {
    return this.db.transaction(name, mode).objectStore(name);
  }

  // --- LocalFs interface consumed by the Rust sync engine -------------------
  async listFiles(): Promise<string[]> {
    return (await req(this.store(FILES).getAllKeys())) as string[];
  }
  async read(path: string): Promise<FileValue | undefined> {
    return (await req(this.store(FILES).get(path))) as FileValue | undefined;
  }
  async write(path: string, content: string | Uint8Array | Blob): Promise<void> {
    const value: FileValue =
      typeof content === "string" || content instanceof Blob
        ? content
        : new Blob([content as BlobPart], { type: mimeFor(path) });
    await req(this.store(FILES, "readwrite").put(value, path));
    this.touch(path);
  }
  async remove(path: string): Promise<void> {
    await req(this.store(FILES, "readwrite").delete(path));
    delete this.mtimes[path];
    void this.set("mtimes", this.mtimes);
  }
  async loadState(): Promise<string | undefined> {
    return this.get<string>("syncState");
  }
  async saveState(json: string): Promise<void> {
    await this.set("syncState", json);
  }

  // --- conveniences -----------------------------------------------------------
  async listNotes(): Promise<string[]> {
    return (await this.listFiles()).filter(isNote);
  }
  async listImages(): Promise<string[]> {
    return (await this.listFiles()).filter(isImage);
  }
  async readText(path: string): Promise<string | undefined> {
    const v = await this.read(path);
    if (v === undefined) return undefined;
    return typeof v === "string" ? v : await v.text();
  }
  async readBlob(path: string): Promise<Blob | undefined> {
    const v = await this.read(path);
    if (v === undefined) return undefined;
    return typeof v === "string" ? new Blob([v]) : v;
  }
  mtime(path: string): number {
    return this.mtimes[path] ?? 0;
  }
  private touch(path: string) {
    this.mtimes[path] = Date.now();
    void this.set("mtimes", this.mtimes);
  }

  // --- app data ---------------------------------------------------------------
  async get<T>(key: string): Promise<T | undefined> {
    return (await req(this.store(KV).get(key))) as T | undefined;
  }
  async set(key: string, value: unknown): Promise<void> {
    await req(this.store(KV, "readwrite").put(value, key));
  }
  async folders(): Promise<string[]> {
    return (await this.get<string[]>("folders")) ?? [];
  }
  async setFolders(folders: string[]): Promise<void> {
    await this.set("folders", [...new Set(folders)].sort());
  }
}

function mimeFor(path: string): string {
  const ext = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  return (
    { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", svg: "image/svg+xml", avif: "image/avif" }[ext] ??
    "application/octet-stream"
  );
}

/** All notes with content, for full-text search and previews. */
export async function allNotes(store: LocalStore): Promise<[string, string][]> {
  const out: [string, string][] = [];
  for (const p of await store.listNotes()) out.push([p, (await store.readText(p)) ?? ""]);
  return out;
}
