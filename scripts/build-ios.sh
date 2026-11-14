#!/usr/bin/env bash
# Build the Rust core for iOS and generate Swift bindings.
#   Output: ios/Frameworks/DNoteFFI.xcframework + ios/Generated/dnote_ffi.swift
set -euo pipefail
source "$(dirname "$0")/env.sh"
export IPHONEOS_DEPLOYMENT_TARGET=16.0

rustup target add aarch64-apple-ios aarch64-apple-ios-sim >/dev/null
cargo build -p dnote-ffi --lib --release --target aarch64-apple-ios
cargo build -p dnote-ffi --lib --release --target aarch64-apple-ios-sim
cargo build -p dnote-ffi --lib   # host build, only used to read the UniFFI metadata

rm -rf ios/Generated ios/Frameworks build/ios
cargo run -q -p dnote-ffi --bin uniffi-bindgen -- generate \
  --library target/debug/libdnote_ffi.dylib --language swift --out-dir ios/Generated

mkdir -p build/ios/include
cp ios/Generated/dnote_ffiFFI.h build/ios/include/
cp ios/Generated/dnote_ffiFFI.modulemap build/ios/include/module.modulemap
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libdnote_ffi.a -headers build/ios/include \
  -library target/aarch64-apple-ios-sim/release/libdnote_ffi.a -headers build/ios/include \
  -output ios/Frameworks/DNoteFFI.xcframework >/dev/null
echo "iOS core ready: ios/Frameworks/DNoteFFI.xcframework"
