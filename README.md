<p align="center">
  <img src="app/src/main/res/raw/karchiver_expressive.svg" width="112" height="84" alt="KArchiver">
</p>

# KArchiver

Android file manager built around archives: browse storage, open archives without extracting, edit ZIP/TAR/7Z in place, and search inside files and archives. The UI is Jetpack Compose (Material 3 Expressive); all archive work is done by a Rust core over JNI. Android 8.0+ (API 26), arm64-v8a / armeabi-v7a / x86 / x86_64, no internet permission.

## Features

- Storage browser: volumes (internal / SD / USB), list and grid, sorting, hidden files, multi-select, copy / cut / paste, favorites, storage usage carousel.
- Archive explorer: browse ZIP, 7Z, TAR family and RAR without extracting; in-place add, rename and delete for ZIP, TAR family and 7Z.
- Copy and extract with conflict handling: replace, skip, or keep both (`name (1).ext`).
- Long operations run in a foreground service with progress, speed, ETA, cancel and a stall watchdog.
- Search by name, extension, date, size and type, plus `content:` for file or entry contents and `archive:` for entry names inside archives.
- File properties: permissions, rename, and modified date with a Material 3 date picker.
- Optional Shizuku or root engine for restricted paths, SAF fallback when All files access is denied.
- Optional history of visited folders and files in a local Room database.

## Formats

| Action | Formats |
| --- | --- |
| Browse and extract | ZIP, 7Z, RAR, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, TAR.ZST, TAR.LZ4, GZ, BZ2, XZ, ZST, LZ4 |
| Create | ZIP, 7Z, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, TAR.ZST, RAR (packing requires unlocked RAR write) |
| Edit in place | ZIP, TAR family, 7Z |
| Read only | RAR, single-stream archives (GZ, BZ2, XZ, ZST, LZ4) |
| Passwords | create and open ZIP, 7Z, RAR |

## Build

Needs JDK 17, Android SDK with `compileSdk 37` and build-tools 37.0.0, NDK 28.2.13676358, Rust 1.98.1 (`rust/rust-toolchain.toml`) and `cargo-ndk`.

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

cd rust
cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 --platform 26 -o ../app/src/main/jniLibs build --lib
cd ..
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Tests:

```bash
./gradlew :app:testDebugUnitTest
cd rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
```

## CI and signing

`.github/workflows/build.yml` builds on pushes to `main`, on pull requests, and on demand. Debug APKs are signed with a throwaway keystore generated for every run; release APKs are built only on pushes and signed with the keystore kept in repository secrets. Signing material is read from `KARCHIVER_*` environment variables, so nothing secret is committed, and pull requests never touch it. The run summary lists the size and SHA-256 of each APK.

## License

Application (`app/`): GPL-3.0-only, see [LICENSE](LICENSE). Rust core (`rust/`): Apache-2.0, see [rust/LICENSE](rust/LICENSE).

Questions and answers: [QA.md](QA.md).
