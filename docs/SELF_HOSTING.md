# Running your DNote server

You need one server for sync. Pick whichever suits you; the apps work the same with both and you
can switch later (the apps re-upload their notes to the new server).

## Option 1: Docker (any machine)

```sh
git clone https://github.com/thucngyyen/dnote && cd dnote
export DNOTE_TOKEN=$(openssl rand -hex 24)   # your access token; keep it secret
docker compose up -d
```

DNote is now at `http://<your-host>:8787`, web app included. Your notes are plain Markdown files in
`./data/notes/`.

Put it behind HTTPS before using it outside your home network, for example with
[Caddy](https://caddyserver.com) (`reverse_proxy localhost:8787`) or a Cloudflare Tunnel.

**Backups:** copy the `data/` folder. The `.md` files are the real content; `dnote.db` is an index that
the server rebuilds from the files if it goes missing.

### Without Docker

```sh
./scripts/build-wasm.sh && (cd web && pnpm install && pnpm build)
cargo build --release -p dnote-server
DNOTE_TOKEN=<token> DNOTE_DATA=/var/lib/dnote DNOTE_WEB=web/dist ./target/release/dnote-server
```

| Variable | Default | |
|---|---|---|
| `DNOTE_TOKEN` | (required) | Access token clients must send. At least 16 characters. |
| `DNOTE_DATA` | `./data` | Where notes (`notes/`) and the index (`dnote.db`) live. |
| `DNOTE_ADDR` | `0.0.0.0:8787` | Address to listen on. |
| `DNOTE_WEB` | `./web/dist` | The built web app, served at `/`. |

## Option 2: Cloudflare (free tier is plenty)

Runs on Workers, with D1 for the index and R2 for note contents. You need a Cloudflare account and
Node.js.

```sh
./scripts/build-wasm.sh && (cd web && pnpm install && pnpm build)
cd crates/worker
npx wrangler login
npx wrangler d1 create dnote              # copy the database_id into wrangler.toml
npx wrangler r2 bucket create dnote-notes
npx wrangler d1 migrations apply dnote --remote
npx wrangler secret put DNOTE_TOKEN       # paste a long random token
npx wrangler deploy
```

Wrangler prints your URL (`https://dnote.<you>.workers.dev`); the web app is served there too.
To use your own domain, add a custom domain to the Worker in the Cloudflare dashboard.

## Connecting the apps

In each app open **Settings → Sync server**, enter the server URL and your token, and tap
**Connect**. Notes you already wrote on that device are uploaded and merged.
