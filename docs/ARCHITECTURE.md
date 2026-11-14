# Architecture

DNote is one Rust core shared by two servers and three clients.

```
                       dnote-core (Rust, no I/O)
         protocol · HTTP API · sync engine · 3-way merge · markdown
              │                      │                       │
          servers                 web app               mobile apps
  dnote-server: axum,         dnote-wasm +           dnote-ffi (UniFFI) +
    SQLite + .md on disk      Vite/TS +              iOS: SwiftUI/TextKit
  dnote-worker: Workers,      CodeMirror 6           Android: Jetpack Compose
    D1 + R2
```

| Path | What |
|---|---|
| `crates/core` | Everything that isn't I/O: the wire protocol, the whole HTTP API as a pure function over a `Store` trait, the SQL shared by both servers, the client sync engine, merging, pins/tags (`workspace.rs`), Markdown → safe HTML. |
| `crates/server` | Self-hosted server (`dnote-server`). Metadata in SQLite, note bodies as real `.md` files under `data/notes/`. Serves the web app. |
| `crates/worker` | Cloudflare Worker. Same API and SQL on D1; file bodies in R2 (content-addressed). Web app via Workers Assets. |
| `crates/wasm` | The sync engine and helpers compiled for the browser. |
| `crates/ffi` | Native core for iOS and Android via UniFFI: the vault on disk, search, pins/tags, images, sync over HTTP. |
| `web/` | Web app (TypeScript, Vite, CodeMirror 6). Stores notes in IndexedDB and works offline. |
| `ios/` | SwiftUI app (`xcodegen` project). |
| `android/` | Jetpack Compose app. |
| `brand/` | Logo, icons and brand guide. |
| `docs/` | This documentation, the UI design spec and screenshots. |

## The HTTP API

All endpoints are JSON and need `Authorization: Bearer <DNOTE_TOKEN>`, except health.

| Method | Path | |
|---|---|---|
| `GET` | `/api/health` | Server name, version and backend (`self-hosted` / `cloudflare`). |
| `GET` | `/api/changes?since=<seq>` | Change feed, paged (1000 per page). |
| `GET` | `/api/file?path=<p>` | A file. Text as `content`, images as `content_base64`. |
| `PUT` | `/api/file?path=<p>` | Write `{base_version, content | content_base64}`. `409` if someone else wrote first. |
| `DELETE` | `/api/file?path=<p>&base_version=<v>` | Delete (kept as a tombstone so other devices learn about it). |

Only notes (`.md`, `.markdown`, `.txt`), images and `.dnote/workspace.json` are accepted.

## How sync works

Each device remembers, per file, the server version and content it last synced (the *base*).

1. **Pull** `/api/changes?since=<seq>`. Files unchanged locally are fast-forwarded. Notes changed on
   both sides are 3-way merged (base, local, remote); overlapping edits keep both sides between
   `<<<<<<<` / `>>>>>>>` markers. A note *created* independently on two devices is kept as
   `Name (conflict).md` instead.
2. **Push** local changes with `base_version`. The server applies a write only if the file is still at
   that version (one atomic SQL statement, identical on SQLite and D1); otherwise it answers `409` and
   the client does another pull → merge → push round.

Deletes are tombstones, so they propagate. Nothing is overwritten silently.

- **Images** are synced as bytes. If the same image changes on two devices, both are kept.
- **Pins and tags** live in `.dnote/workspace.json` and are merged as a set of facts ("X is pinned",
  "X has tag T"), so pinning and tagging on several devices never conflicts.
- **The self-hosted server** treats its `.md` files as the source of truth: on startup it indexes
  anything added, edited or deleted in `data/notes/` while it was down.

## Storage on each platform

| Where | Notes | Sync state |
|---|---|---|
| iOS | `Documents/Notes` (visible in the Files app) | `Notes/.dnote/state.json` |
| Android | app-private `files/Notes` | `Notes/.dnote/state.json` |
| Web | IndexedDB (`dnote` database) | IndexedDB |
| Self-hosted server | `data/notes/` + `data/dnote.db` | — |
| Cloudflare | R2 (`blobs/<sha256>`) + D1 | — |

## Current limits

- One vault and one shared token per deployment (personal use). Multi-user needs accounts.
- Sync is periodic (every 30 s, after edits, on focus/launch), not push. A Durable Object or
  server-sent events could add live updates.
- Empty folders stay local until they contain a note (the server only stores files).
- The self-hosted server picks up hand edits to `data/notes/` on restart, not live.
- Old R2 blobs aren't garbage-collected (they double as history).
- Android keeps the token in app-private storage; iOS uses the Keychain.
