#!/usr/bin/env bash
# Build the browser core (wasm) into web/src/wasm.
set -euo pipefail
source "$(dirname "$0")/env.sh"
rustup target add wasm32-unknown-unknown >/dev/null
cargo build -p dnote-wasm --target wasm32-unknown-unknown --release
wasm-bindgen --target web --out-dir web/src/wasm target/wasm32-unknown-unknown/release/dnote_wasm.wasm
