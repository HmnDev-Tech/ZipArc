# KArchiver — QA

**Was this project built with AI?**
Yes. It was developed in [opencode](https://opencode.ai).

**Where do I get an APK, and why does the debug signature change every build?**
GitHub → Actions → the latest successful run on `main` → artifacts. `KArchiver-debug` exists for every run, `KArchiver-release` only for pushes to `main`. Debug APKs are signed with a throwaway keystore generated for that run, so a new debug APK cannot be installed over an older debug install: uninstall the previous build first. Release APKs are signed with a stable key and update normally. Each run summary lists the byte size and SHA-256 of every APK.

**Installation fails with "APK Signature Scheme v2: SHA-256 digest of contents did not verify" or `INSTALL_PARSE_FAILED_NO_CERTIFICATES`.**
The APK file was modified after it was built. Debug APKs are signed with v2 only, and v2 signs the whole file, so repacking, re-compression, "optimizing", an incomplete download or a copy to a failing SD card breaks it. Compare size and SHA-256 with the run summary, re-download the artifact, extract it with a plain extractor, keep it in internal storage and install again, or use `adb install -r app-debug.apk`.

**Does the app need the internet or send any data?**
No. The app does not request the `INTERNET` permission. There is no analytics, telemetry or ads, and history stays in a local database inside the app's private storage.

**Why does the app ask for "All files access"?**
To manage files outside the folders it owns. It is optional: if you deny it, the app falls back to SAF and asks you to grant a storage tree through the system picker, then works inside that tree.

**What are Shizuku and root used for?**
Restricted paths a normal app cannot touch, for example `/Android/data` and system directories. Both are optional (Settings → Elevation → Off / Shizuku / Root). Shizuku needs the Shizuku app installed and running; root needs a working `su`.

**How do I enable RAR?**
Settings → File manager → **RAR support**. It is read-only at first. To unlock creating RAR archives, press and hold the same row for 5 seconds until a haptic tick confirms; RAR packing is a deliberate speed bump because of RARLAB's licensing terms.

**Which archives can be edited in place?**
ZIP, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, TAR.ZST and 7Z support adding, renaming and deleting entries. RAR and single-stream archives (`.gz`, `.bz2`, `.xz`, `.zst`, `.lz4`) are read-only.

**Does extraction overwrite my files?**
Not without asking. When names collide you choose Replace, Skip or Keep both; "Keep both" writes a new name such as `report (1).pdf`.

**What can I type into the search field?**
Plain words match names, tokens refine the query, several tokens combine with AND, and invalid values fall back to a normal name match.

| Token | Meaning |
| --- | --- |
| `name:` / `n:` | name contains value |
| `ext:` / `format:` / `f:` | extension, for example `f:zip,pdf` |
| `date:` / `d:` | `today`, `yesterday`, `2026-09-17`, `2026-09`, `2026-09-01..2026-09-30`, `>=2026-09-01` |
| `size:` / `s:` | `>10MB`, `1MB..100MB`, `5M` |
| `type:` / `is:` | `file` or `dir` |
| `content:` / `text:` / `c:` | file or archive entry content contains value |
| `archive:` / `a:` | archive has an entry whose name contains value |

Quote values with spaces (`content:"hello world"`). Content and archive searches are configured in Settings → Search: enable content search, archive search, case sensitivity and the per-file scan limit. Such queries walk the current folder tree, show a "Scanned N files" counter, stop after 500 results or 20,000 scanned files, and are cancelled when the query changes; nothing is indexed in the background.

**What does History store, and how do I turn it off?**
Recent folders and opened files with type filters, kept in a local Room database in the app's private storage. Turn it off in Settings → Interface → **History**; nothing is recorded while it is off and the database can be cleared from the History screen.

**Why is there a notification during an operation?**
Long archive operations run in a foreground service, and Android requires a visible notification for those. It disappears when the operation finishes.

**Are the app and the Rust core licensed the same way?**
No. `app/` is GPL-3.0-only and `rust/` is Apache-2.0. See [LICENSE](LICENSE) and [rust/LICENSE](rust/LICENSE).
