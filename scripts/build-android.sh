#!/usr/bin/env bash
# Build the Rust core for Android and generate Kotlin bindings.
#   Output: android/app/src/main/jniLibs/<abi>/libdnote_ffi.so
#           android/app/src/main/java/uniffi/dnote_ffi/dnote_ffi.kt
set -euo pipefail
source "$(dirname "$0")/env.sh"

if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "$HOME/Library/Android/sdk" "/opt/homebrew/share/android-commandlinetools" "$HOME/Android/Sdk"; do
    if [ -d "$d" ]; then export ANDROID_HOME="$d"; break; fi
  done
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$(ls "$ANDROID_HOME/ndk" | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
command -v cargo-ndk >/dev/null || cargo install cargo-ndk

rustup target add aarch64-linux-android x86_64-linux-android >/dev/null
# arm64-v8a: phones and Apple-silicon emulators; x86_64: Intel emulators.
cargo ndk -t arm64-v8a -t x86_64 --platform 26 -o android/app/src/main/jniLibs build -p dnote-ffi --lib --release
cargo build -p dnote-ffi --lib   # host build, only used to read the UniFFI metadata
rm -rf android/app/src/main/java/uniffi
cargo run -q -p dnote-ffi --bin uniffi-bindgen -- generate \
  --library target/debug/libdnote_ffi.dylib --language kotlin --out-dir android/app/src/main/java
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
echo "Android core ready (ANDROID_HOME=$ANDROID_HOME)"
