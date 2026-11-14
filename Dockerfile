# Self-hosted DNote: one small image with the server binary and the web app.
#   docker build -t dnote .
#   docker run -p 8787:8787 -e DNOTE_TOKEN=$(openssl rand -hex 24) -v dnote-data:/data dnote

FROM rust:1-bookworm AS rust
RUN rustup target add wasm32-unknown-unknown \
 && cargo install wasm-bindgen-cli --version 0.2.130 --locked
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY crates crates
RUN cargo build --release -p dnote-server \
 && cargo build --release -p dnote-wasm --target wasm32-unknown-unknown \
 && wasm-bindgen --target web --out-dir /wasm target/wasm32-unknown-unknown/release/dnote_wasm.wasm

FROM node:22-bookworm-slim AS web
RUN corepack enable
WORKDIR /web
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web .
COPY --from=rust /wasm src/wasm
RUN npx tsc --noEmit && npx vite build

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 dnote && mkdir /data && chown dnote /data
COPY --from=rust /src/target/release/dnote-server /usr/local/bin/dnote-server
COPY --from=web /web/dist /srv/web
USER dnote
ENV DNOTE_DATA=/data DNOTE_WEB=/srv/web DNOTE_ADDR=0.0.0.0:8787
VOLUME /data
EXPOSE 8787
CMD ["dnote-server"]
