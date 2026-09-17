# KArchiver — QA

**Was this project built with AI?**
Yes. It was developed in [opencode](https://opencode.ai).

**What is KArchiver?**
An Android file manager focused on archives: browse storage, open archives without extracting, edit ZIP/TAR/7Z in place, and search inside files and archives.

**Which Android versions and architectures are supported?**
Android 8.0 (API 26) and newer. ABIs: arm64-v8a, armeabi-v7a, x86, x86_64.

**Where do I get an APK?**
GitHub → Actions → the latest successful run on `main` → artifacts. `KArchiver-debug` exists for every run; `KArchiver-release` exists for pushes to `main`. Each run summary lists the byte size and SHA-256 of every APK.

**Why is the debug APK signed with a different key every build?**
CI generates a throwaway keystore for each run, so no long-lived key is ever used for debug builds. As a result, a new debug APK cannot be installed over an older debug install: uninstall the previous build first, or use the release APK, which is signed with a stable key.

**Installation fails with "APK Signature Scheme v2: SHA-256 digest of contents did not verify" or `INSTALL_PARSE_FAILED_NO_CERTIFICATES`.**
The APK file was modified after it was built. Debug APKs are signed with v2 only, and v2 signs the whole file, so any repacking, re-compression, "optimizing", incomplete download, or a copy to a failing SD card breaks it. Compare the file size and SHA-256 with the values in the run summary, re-download the artifact, extract it with a plain extractor, keep it in internal storage, and install again (or use `adb install -r app-debug.apk`).

**Does the app need the internet?**
No. The app does not request the `INTERNET` permission and sends nothing anywhere. There is no analytics, no telemetry, no ads.

**Why does the app ask for "All files access"?**
To manage files outside of the folders it owns. It is optional: if you deny it, the app falls back to SAF and asks you to grant a storage tree through the system file picker, and then operates inside that tree.

**What are Shizuku and root used for?**
Restricted paths that a normal app cannot touch, for example `/Android/data` and system directories. Both are optional (Settings → Elevation → Off / Shizuku / Root). Shizuku requires the Shizuku app to be installed and running; root requires a working `su`.

**How do I enable RAR?**
Settings → File manager → enable **RAR support**. It is read-only at first. To unlock creating RAR archives, press and hold the same row for 5 seconds; a haptic tick confirms. This is a deliberate speed bump, since RAR packing is subject to RARLAB's licensing terms.

**Can KArchiver edit archives in place?**
Yes, for ZIP, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, TAR.ZST and 7Z: add files, rename entries and delete entries. RAR and single-stream archives (`.gz`, `.bz2`, `.xz`, `.zst`, `.lz4`) are read-only.

**Does extraction overwrite my files?**
Not without asking. When names collide you choose Replace, Skip or Keep both. "Keep both" writes a new name such as `report (1).pdf`.

**What can I type into the search field?**
Plain words match names. Supported tokens:

| Token | Meaning |
| --- | --- |
| `name:` / `n:` | name contains value |
| `ext:` / `format:` / `f:` | extension, e.g. `f:zip,pdf` |
| `date:` / `d:` | `today`, `yesterday`, `2026-09-17`, `2026-09`, `2026-09-01..2026-09-30`, `>=2026-09-01` |
| `size:` / `s:` | `>10MB`, `1MB..100MB`, `5M` |
| `type:` / `is:` | `file` or `dir` |
| `content:` / `text:` / `c:` | file content or archive entry content contains value |
| `archive:` / `a:` | archive has an entry whose name contains value |

Values can be quoted, tokens combine with AND, and an invalid value falls back to a normal name match. Content and archive searches are configured in Settings → Search (enable content search, archive search, case sensitivity, per-file scan limit).

**How does the deep search behave?**
When a query needs content or archive scanning, KArchiver walks the current folder tree, shows a "Scanned N files" counter, stops after 500 results or 20,000 scanned files, and cancels the previous search when the query changes. Searches run on demand; nothing is indexed in the background.

**Why is the debug APK so large?**
It ships native libraries for four ABIs and is not minified. Release builds are shrunk with R8 and resource shrinking, so they are much smaller.

**Why is there a permanent notification during an operation?**
Long-running archive work runs in a foreground service, and Android requires a visible notification for those. It disappears when the operation finishes.

**What does the History feature store, and how do I turn it off?**
Recent folders and opened files, with type filters and sorting, kept in a local Room database inside the app's private storage. Turn it off in Settings → Interface → **History**; nothing is recorded while it is off, and the database can be cleared from the History screen.

**How do I add favorites?**
Triple-tap a file or folder (triple tap window is 450 ms). Favorites appear in the navigation drawer.

**How do I make the drawer list my storage devices?**
Settings → Interface → **See devices in UI**. The drawer then shows internal storage, SD cards and USB drives with their usage.

**Why do copy and move sometimes go through a temporary file?**
Atomic renames are not available on some filesystems (for example FAT32/exFAT on removable media), so KArchiver writes `name.karchiver-part` and then renames, falling back to a plain rename when atomic move is not possible. This keeps a failed operation from destroying the original file.

**Are the app and the Rust core licensed the same way?**
No. `app/` is GPL-3.0-only and `rust/` is Apache-2.0. See [LICENSE](LICENSE) and [rust/LICENSE](rust/LICENSE).

**Where do I report a bug?**
Open an issue in this repository with the app version, Android version, device, and steps to reproduce.
