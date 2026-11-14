# Contributing to DNote

Thanks for wanting to help! DNote is a small project, so every bug report, idea and pull request
makes a real difference. This guide explains how to get set up and what we look for.

## Ways to help

- **Report a bug.** Open an issue with what you did, what you expected and what happened. Say which
  app (web, iOS, Android) and which server (Docker or Cloudflare) you use. Screenshots help a lot.
- **Suggest a feature.** Open an issue describing the problem you want solved before writing code,
  so we can agree on the approach first.
- **Improve the docs.** Typos, unclear steps and missing explanations are all welcome fixes.
- **Write code.** Issues labelled `good first issue` are a good place to start. Comment on the issue
  so others know you're on it.

## Getting set up

Everything you need to build and run DNote locally is in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). The short version:

```sh
./scripts/build-wasm.sh && (cd web && pnpm install && pnpm build)
DNOTE_TOKEN=local-dev-token-change-me cargo run -p dnote-server   # http://localhost:8787
```

Before you change something big, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The most
important idea: **logic lives in the Rust core** (`crates/core`), and the servers and apps are thin
layers on top. If a change affects how notes are stored, synced, merged or rendered, it almost
certainly belongs in the core, so that the web, iOS and Android apps all get it at once.

## Project layout

| Path | Language | What |
|---|---|---|
| `crates/core` | Rust | Protocol, HTTP API, sync engine, merging, pins/tags, Markdown |
| `crates/server` | Rust | Self-hosted server (axum, SQLite, files on disk) |
| `crates/worker` | Rust | Cloudflare Worker (D1, R2) |
| `crates/wasm`, `crates/ffi` | Rust | Bindings for the web and for iOS/Android |
| `web/` | TypeScript | Web app |
| `ios/` | Swift | iOS app |
| `android/` | Kotlin | Android app |
| `docs/` | Markdown | Documentation, design spec, screenshots |
| `brand/` | SVG/PNG | Logo and icons (see `brand/BRAND.md` for usage rules) |

## Making a change

1. Fork the repository and create a branch from `main` (`fix/sync-conflict-copy`,
   `feat/backlinks`, ...).
2. Keep each pull request focused on one thing. Small PRs get reviewed and merged much faster.
3. Add or update tests for behaviour you change, especially in `crates/core`.
4. Run the checks below and make sure they pass.
5. Open a pull request that explains **what** changed and **why**. For UI changes, include
   screenshots (light and dark) for every platform you touched.

### Checks

```sh
cargo fmt --all
cargo clippy --workspace --all-features
cargo test -p dnote-core --all-features
cargo test -p dnote-ffi -p dnote-server
cargo check -p dnote-worker -p dnote-wasm --target wasm32-unknown-unknown
(cd web && pnpm typecheck)
```

If you changed `crates/core` or `crates/ffi`, rebuild the bindings (`./scripts/build-ios.sh`,
`./scripts/build-android.sh`) and check the mobile apps still build.

## Guidelines

- **Never lose a user's words.** Sync and storage changes must not drop or overwrite content
  silently. When in doubt, keep both versions. Add a test that two devices converge.
- **Keep notes portable.** Notes stay plain Markdown files. Don't add hidden metadata to note
  contents; app data belongs in `.dnote/`.
- **Keep the servers in step.** The self-hosted server and the Cloudflare Worker must behave
  identically. Shared behaviour goes in `crates/core` (including the SQL in `sql.rs`).
- **Follow the design.** UI changes should follow [docs/DESIGN.md](docs/DESIGN.md): one blue accent,
  ink and paper neutrals, and the same structure on every platform. If a feature exists on one app,
  it should land on the others too (or have an issue to track it).
- **Match the surrounding code.** Use the existing style in each language; `cargo fmt` for Rust.
- **No secrets in the repo.** Use placeholders like `local-dev-token-change-me` in examples.
  `.dev.vars`, `local.properties` and build outputs are git-ignored; keep it that way.

## Commit messages

Write a short summary line in the imperative ("Fix merge of notes created on two devices"), then a
blank line and any detail that helps a reviewer understand why.

## Reporting security issues

Please don't open a public issue for security problems. Use GitHub's private vulnerability reporting
(the **Security** tab of the repository → **Report a vulnerability**) and we'll get back to you.

## Code of conduct

Be kind and assume good intent. We want DNote to be a welcoming project for people of every
background and experience level. Harassment or disrespectful behaviour isn't tolerated.

## License

By contributing, you agree that your contributions are licensed under the project's
[MIT License](LICENSE).
