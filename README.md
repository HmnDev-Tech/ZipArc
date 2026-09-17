<p align="center">
  <img src="app/src/main/res/raw/karchiver_expressive.svg" width="300" alt="KArchiver">
</p>

# KArchiver

Android file manager with full archive handling. Browse storage, open archives without extracting, edit them in place, and search inside files and archives. The UI is Jetpack Compose (Material 3 Expressive); archive work is done by a Rust core over JNI.

## Features

- Storage browser: internal / SD card / USB volumes, breadcrumbs, list and grid views, sorting, hidden files, multi-select, copy / cut / paste, favorites, navigation drawer, storage usage carousel.
- Archive explorer: browse ZIP, 7Z, TAR family and RAR (including nested folders) without extracting; in-place add, rename and delete for ZIP, TAR family and 7Z; extract single entries, selection or everything.
- Copy and extract conflict handling: replace, skip, or keep both (auto-renamed as `name (1).ext`).
- Background operations: foreground service with notification, speed and ETA, cancel, 5-minute stall watchdog, wake lock; operations continue with the screen off.
- Search: name, extension, date, size and type tokens. `content:"text"` searches file contents, `archive:"text"` searches entry names inside archives, and both together search entry contents. Deep search walks the current folder tree with progress and a result limit.
- Privileged access (optional): Shizuku or root engine for restricted paths (listing, read, write, delete, chmod).
- Storage access: uses All files access (`MANAGE_EXTERNAL_STORAGE`) when granted, otherwise SAF document trees, with automatic fallback.
- File properties: size, path, type, permissions (octal editing), rename, and modified date via a Material 3 date picker.
- History (optional): recently visited folders and files in a local Room database, with type filters and sorting.
- Appearance: dynamic color or one of the built-in seed palettes, system / light / dark / OLED themes.

## Formats

| Action | Formats |
| --- | --- |
| Browse and extract | ZIP, 7Z, RAR, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, TAR.ZST, TAR.LZ4, GZ, BZ2, XZ, ZST, LZ4 |
| Create | ZIP, 7Z, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, TAR.ZST, RAR (packing requires unlocked RAR write) |
| Edit in place | ZIP, TAR family, 7Z |
| Read only | RAR, single-stream archives (GZ, BZ2, XZ, ZST, LZ4) |
| Passwords | create and open ZIP, 7Z, RAR |

Split ZIP archives are read as a single archive.

## Requirements

- Android 8.0 (API 26) or newer.
- ABIs: arm64-v8a, armeabi-v7a, x86, x86_64.
- The app has no internet permission and contacts no server.

## Build

Prerequisites: JDK 17, Android SDK with `compileSdk 37` and build-tools 37.0.0, NDK 28.2.13676358, Rust 1.98.1 (pinned in `rust/rust-toolchain.toml`), and `cargo-ndk`.

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

cd rust
cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 --platform 26 -o ../app/src/main/jniLibs build --lib
cd ..
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Tests:

```bash
./gradlew :app:testDebugUnitTest
cd rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
```

## Signing

Gradle signs both build types. Signing material is read from environment variables, so nothing secret lives in the repository.

| Variable | Purpose |
| --- | --- |
| `KARCHIVER_KEYSTORE` | path to the release keystore |
| `KARCHIVER_STORE_PASSWORD` | release keystore password |
| `KARCHIVER_KEY_ALIAS` | release key alias |
| `KARCHIVER_KEY_PASSWORD` | release key password |
| `KARCHIVER_DEBUG_KEYSTORE` | path to an ephemeral debug keystore |
| `KARCHIVER_DEBUG_STORE_PASSWORD` | debug keystore password |
| `KARCHIVER_DEBUG_KEY_ALIAS` | debug key alias |
| `KARCHIVER_DEBUG_KEY_PASSWORD` | debug key password |

Without the debug variables, debug builds fall back to the standard Android debug keystore (local development). Without the release variables, release builds are left unsigned.

## CI

`.github/workflows/build.yml`, on pushes to `main`, on pull requests, and manually:

- Debug APK: signed with a throwaway keystore generated at the start of every run, so builds are never signed with a long-lived key. Debug APKs from different runs cannot be installed over each other; uninstall the previous one first.
- Release APK: only on pushes (never on pull requests). Signed with the release keystore stored in repository secrets.
- Signature scheme is verified in the run log, and the byte size plus SHA-256 of each APK is printed in the run summary.
- Artifacts: `KArchiver-debug`, `KArchiver-release`.

## License

- Application (`app/`): GPL-3.0-only. See [LICENSE](LICENSE).
- Rust core (`rust/`): Apache-2.0. See [rust/LICENSE](rust/LICENSE).

Common questions are answered in [QA.md](QA.md).
