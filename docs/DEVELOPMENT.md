# Development

## Prerequisites

| For | You need |
|---|---|
| Everything | Rust via [rustup](https://rustup.rs) (the build scripts add the cross-compile targets) |
| Web | Node.js 20+, [pnpm](https://pnpm.io), `wasm-bindgen-cli 0.2.130` (`cargo install wasm-bindgen-cli --version 0.2.130`) |
| iOS | macOS, Xcode 16+, [XcodeGen](https://github.com/yonaskolb/XcodeGen) |
| Android | JDK 17, Android SDK + NDK, `cargo-ndk` (`cargo install cargo-ndk`) |
| Cloudflare | Node.js (`npx wrangler`) and `worker-build` (`cargo install worker-build`) |

Generated files (wasm, Swift/Kotlin bindings, native libraries, the Xcode project) are not committed;
the scripts in `scripts/` produce them.

## Run a server and the web app

```sh
./scripts/build-wasm.sh
(cd web && pnpm install && pnpm build)
DNOTE_TOKEN=local-dev-token-change-me cargo run -p dnote-server   # http://localhost:8787
```

For hot reload, keep the server running and start Vite (it proxies `/api` to `:8787`):

```sh
cd web && pnpm dev
```

### The Cloudflare flavour, locally

`wrangler dev` runs the Worker in workerd with a local D1 and R2:

```sh
cd crates/worker
echo 'DNOTE_TOKEN=local-dev-token-change-me' > .dev.vars   # git-ignored
npx wrangler d1 migrations apply dnote --local
npx wrangler dev --port 8898 --ip 0.0.0.0
```

## iOS

```sh
./scripts/build-ios.sh          # Rust → ios/Frameworks/DNoteFFI.xcframework + Swift bindings
cd ios && xcodegen generate && open DNote.xcodeproj
```

## Android

```sh
./scripts/build-android.sh      # Rust → jniLibs/*.so + Kotlin bindings
cd android && ./gradlew installDebug
```

Re-run the build script whenever `crates/core` or `crates/ffi` changes.

### Release APKs

```sh
cd android && ./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the key described in `android/keystore.properties` (git-ignored):

```properties
storeFile=keystore/dnote-release.jks
storePassword=…
keyAlias=dnote
keyPassword=…
```

Without that file the APK is signed with the debug key and the build prints a warning;
`bundleRelease` refuses to run. Keep the keystore backed up: updates must be signed with the same key
to install over an existing copy.

Only 64-bit native libraries (`arm64-v8a`, plus `x86_64` for emulators) are built, so DNote doesn't
install on 32-bit-only Android phones.

## Mobile apps against a local server

The apps work fully offline; to test sync point them at your machine:

| Client | Server URL |
|---|---|
| iOS Simulator | `http://localhost:8787` (wrangler: `:8898`) |
| Android emulator | `http://10.0.2.2:8787` (wrangler: `:8898`) |
| Real Android phone | `adb reverse tcp:8787 tcp:8787`, then `http://localhost:8787` |
| Real phone on Wi-Fi | `http://<your-computer's-LAN-IP>:8787` |

Both settings screens have one-tap buttons for the simulator/emulator URLs.

## Tests and checks

```sh
cargo test -p dnote-core --all-features   # API, SQL/migration parity, merging, multi-device sync, workspace
cargo test -p dnote-ffi -p dnote-server   # vault operations, server disk indexing
cargo check -p dnote-worker -p dnote-wasm --target wasm32-unknown-unknown
(cd web && pnpm typecheck)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit, and [DESIGN.md](DESIGN.md) for the UI
rules all three apps follow.
