import { defineConfig } from "vite";

// In dev, /api is proxied to a local server (self-hosted on :8787 by default,
// or `wrangler dev` with DNOTE_API=http://127.0.0.1:8898).
export default defineConfig({
  server: {
    proxy: { "/api": process.env.DNOTE_API ?? "http://127.0.0.1:8787" },
  },
  build: { target: "es2022" },
});
