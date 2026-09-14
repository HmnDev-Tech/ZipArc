//! JNI entry points.
//!
//! Every exported function is wrapped in [`std::panic::catch_unwind`] so a
//! panic in Rust (or in a dependency) becomes a thrown
//! `java.lang.RuntimeException` instead of aborting the Android process.
//! The three symbol names/signatures below are part of the Kotlin ABI and must
//! not change.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::jint;
use serde::Serialize;

use crate::backend;
use crate::error::{ArchiveError, Result};
use crate::format;
use crate::io_util::{CODE_CANCELLED, CODE_OK, Limits, clear_cancel, request_cancel};

fn throw(env: &mut JNIEnv, msg: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/RuntimeException", msg.as_ref());
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Result<String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|e| ArchiveError::backend(format!("invalid Java string: {e}")))
}

fn wipe_password(password: String) {
    let mut owned = password.into_bytes();
    crate::io_util::wipe_bytes(&mut owned);
}

fn read_sources(env: &mut JNIEnv, array: &JObjectArray) -> Result<Vec<PathBuf>> {
    let len = env
        .get_array_length(array)
        .map_err(|e| ArchiveError::backend(format!("array length: {e}")))?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let element = env
            .get_object_array_element(array, i)
            .map_err(|e| ArchiveError::backend(format!("array element {i}: {e}")))?;
        if element.is_null() {
            continue;
        }
        let text = read_string(env, &JString::from(element))?;
        out.push(PathBuf::from(text));
    }
    Ok(out)
}

fn build_string_array<'local>(
    env: &mut JNIEnv<'local>,
    items: &[String],
) -> Result<JObjectArray<'local>> {
    let class = env
        .find_class("java/lang/String")
        .map_err(|e| ArchiveError::backend(format!("find String: {e}")))?;
    let array = env
        .new_object_array(items.len() as i32, &class, JObject::null())
        .map_err(|e| ArchiveError::backend(format!("new array: {e}")))?;
    for (i, item) in items.iter().enumerate() {
        let element = env
            .new_string(item)
            .map_err(|e| ArchiveError::backend(format!("new string: {e}")))?;
        env.set_object_array_element(&array, i as i32, element)
            .map_err(|e| ArchiveError::backend(format!("set element: {e}")))?;
    }
    Ok(array)
}

fn finish_int(env: &mut JNIEnv, op: &str, outcome: std::thread::Result<Result<()>>) -> jint {
    match outcome {
        Ok(Ok(())) => CODE_OK,
        Ok(Err(ArchiveError::Cancelled)) => {
            throw(env, format!("{op} cancelled"));
            CODE_CANCELLED
        }
        Ok(Err(e)) => {
            throw(env, format!("{op} failed: {e}"));
            -1
        }
        Err(_) => {
            throw(env, format!("{op} failed: internal panic"));
            -1
        }
    }
}

/// `compress(srcPaths: Array<String>, destPath: String): Int`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_compress(
    mut env: JNIEnv,
    _class: JClass,
    src_array: JObjectArray,
    dest_str: JString,
) -> jint {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        clear_cancel();
        let sources = read_sources(&mut env, &src_array)?;
        let dest = PathBuf::from(read_string(&mut env, &dest_str)?);
        let format = format::format_for_destination(&dest)?;
        backend::compress(&sources, &dest, format, &Limits::default())
    }));
    finish_int(&mut env, "compress", outcome)
}

/// `extract(archivePath: String, destDir: String): Int`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_extract(
    mut env: JNIEnv,
    _class: JClass,
    archive_str: JString,
    dest_str: JString,
) -> jint {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        clear_cancel();
        let archive = PathBuf::from(read_string(&mut env, &archive_str)?);
        let dest = PathBuf::from(read_string(&mut env, &dest_str)?);
        let format = format::detect(&archive)?;
        backend::extract(&archive, &dest, format, &Limits::default())
    }));
    finish_int(&mut env, "extract", outcome)
}

/// `listArchive(archivePath: String): Array<String>`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_listArchive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archive_str: JString<'local>,
) -> JObjectArray<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<String>> {
        let archive = PathBuf::from(read_string(&mut env, &archive_str)?);
        let format = format::detect(&archive)?;
        backend::list(&archive, format)
    }));
    match outcome {
        Ok(Ok(entries)) => match build_string_array(&mut env, &entries) {
            Ok(array) => array,
            Err(e) => {
                throw(&mut env, format!("listArchive failed: {e}"));
                JObjectArray::default()
            }
        },
        Ok(Err(e)) => {
            throw(&mut env, format!("listArchive failed: {e}"));
            JObjectArray::default()
        }
        Err(_) => {
            throw(&mut env, "listArchive failed: internal panic");
            JObjectArray::default()
        }
    }
}

/// Wire-compatible DTOs for the JSON strings consumed by Kotlin.
/// Field names (including `isDir`, `totalSize`, `passwordRequired`) are part
/// of the Kotlin ABI and must not change.
#[derive(Serialize)]
struct PreviewEntryDto<'a> {
    name: &'a str,
    size: u64,
    #[serde(rename = "isDir")]
    is_dir: bool,
    encrypted: bool,
}

#[derive(Serialize)]
struct PreviewDto<'a> {
    encrypted: bool,
    entries: Vec<PreviewEntryDto<'a>>,
}

#[derive(Serialize)]
struct TestFailureDto<'a> {
    name: &'a str,
    reason: &'a str,
}

#[derive(Serialize)]
struct TestReportDto<'a> {
    ok: bool,
    entries: usize,
    #[serde(rename = "totalSize")]
    total_size: u64,
    failures: Vec<TestFailureDto<'a>>,
    #[serde(rename = "passwordRequired")]
    password_required: bool,
}

fn preview_to_json(listing: &crate::backend::PreviewListing) -> Result<String> {
    let dto = PreviewDto {
        encrypted: listing.encrypted,
        entries: listing
            .entries
            .iter()
            .map(|e| PreviewEntryDto {
                name: &e.name,
                size: e.size,
                is_dir: e.is_dir,
                encrypted: e.encrypted,
            })
            .collect(),
    };
    serde_json::to_string(&dto).map_err(ArchiveError::backend)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_listArchiveDetailed<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archive_str: JString<'local>,
) -> JString<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        clear_cancel();
        let archive = PathBuf::from(read_string(&mut env, &archive_str)?);
        let format = format::detect(&archive)?;
        let listing = backend::list_detailed(&archive, format)?;
        preview_to_json(&listing)
    }));
    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, format!("listArchiveDetailed failed: {e}"));
                JString::default()
            }
        },
        Ok(Err(e)) => {
            throw(&mut env, format!("listArchiveDetailed failed: {e}"));
            JString::default()
        }
        Err(_) => {
            throw(&mut env, "listArchiveDetailed failed: internal panic");
            JString::default()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_cancel(
    _env: JNIEnv,
    _class: JClass,
) {
    request_cancel();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_compressWithPassword(
    mut env: JNIEnv,
    _class: JClass,
    src_array: JObjectArray,
    dest_str: JString,
    password_str: JString,
) -> jint {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        clear_cancel();
        let sources = read_sources(&mut env, &src_array)?;
        let dest = PathBuf::from(read_string(&mut env, &dest_str)?);
        let password = read_string(&mut env, &password_str)?;
        let format = format::format_for_destination(&dest)?;
        let result =
            backend::compress_with_password(&sources, &dest, format, &Limits::default(), &password);
        wipe_password(password);
        result
    }));
    finish_int(&mut env, "compressWithPassword", outcome)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_extractWithPassword(
    mut env: JNIEnv,
    _class: JClass,
    archive_str: JString,
    dest_str: JString,
    password_str: JString,
) -> jint {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        clear_cancel();
        let archive = PathBuf::from(read_string(&mut env, &archive_str)?);
        let dest = PathBuf::from(read_string(&mut env, &dest_str)?);
        let password = read_string(&mut env, &password_str)?;
        let format = format::detect(&archive)?;
        let result =
            backend::extract_with_password(&archive, &dest, format, &Limits::default(), &password);
        wipe_password(password);
        result
    }));
    finish_int(&mut env, "extractWithPassword", outcome)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_listArchiveDetailedWithPassword<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archive_str: JString<'local>,
    password_str: JString<'local>,
) -> JString<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        clear_cancel();
        let archive = PathBuf::from(read_string(&mut env, &archive_str)?);
        let password = read_string(&mut env, &password_str)?;
        let format = format::detect(&archive)?;
        let result = backend::list_detailed_with_password(&archive, format, &password)
            .and_then(|listing| preview_to_json(&listing));
        wipe_password(password);
        result
    }));
    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(s) => s,
            Err(e) => {
                throw(
                    &mut env,
                    format!("listArchiveDetailedWithPassword failed: {e}"),
                );
                JString::default()
            }
        },
        Ok(Err(e)) => {
            throw(
                &mut env,
                format!("listArchiveDetailedWithPassword failed: {e}"),
            );
            JString::default()
        }
        Err(_) => {
            throw(
                &mut env,
                "listArchiveDetailedWithPassword failed: internal panic",
            );
            JString::default()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_testArchiveWithPassword<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archive_str: JString<'local>,
    password_str: JString<'local>,
) -> JString<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        clear_cancel();
        let archive = PathBuf::from(read_string(&mut env, &archive_str)?);
        let password = read_string(&mut env, &password_str)?;
        let format = format::detect(&archive)?;
        let result = match backend::test_archive_with_password(
            &archive,
            format,
            &Limits::default(),
            &password,
        ) {
            Ok(report) => test_report_to_json(&report),
            Err(ArchiveError::PasswordRequired(_)) => password_required_json(),
            Err(e) => Err(e),
        };
        wipe_password(password);
        result
    }));
    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, format!("testArchiveWithPassword failed: {e}"));
                JString::default()
            }
        },
        Ok(Err(e)) => {
            throw(&mut env, format!("testArchiveWithPassword failed: {e}"));
            JString::default()
        }
        Err(_) => {
            throw(&mut env, "testArchiveWithPassword failed: internal panic");
            JString::default()
        }
    }
}

fn test_report_to_json(report: &crate::backend::TestReport) -> Result<String> {
    let dto = TestReportDto {
        ok: report.ok(),
        entries: report.entries,
        total_size: report.total_size,
        failures: report
            .failures
            .iter()
            .map(|f| TestFailureDto {
                name: &f.name,
                reason: &f.reason,
            })
            .collect(),
        password_required: report.password_required,
    };
    serde_json::to_string(&dto).map_err(ArchiveError::backend)
}

fn password_required_json() -> Result<String> {
    let dto = TestReportDto {
        ok: false,
        entries: 0,
        total_size: 0,
        failures: Vec::new(),
        password_required: true,
    };
    serde_json::to_string(&dto).map_err(ArchiveError::backend)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_testArchive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archive_str: JString<'local>,
) -> JString<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        clear_cancel();
        let archive = PathBuf::from(read_string(&mut env, &archive_str)?);
        let format = format::detect(&archive)?;
        match backend::test_archive(&archive, format, &Limits::default()) {
            Ok(report) => test_report_to_json(&report),
            Err(ArchiveError::PasswordRequired(_)) => password_required_json(),
            Err(e) => Err(e),
        }
    }));
    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, format!("testArchive failed: {e}"));
                JString::default()
            }
        },
        Ok(Err(e)) => {
            throw(&mut env, format!("testArchive failed: {e}"));
            JString::default()
        }
        Err(_) => {
            throw(&mut env, "testArchive failed: internal panic");
            JString::default()
        }
    }
}

// ---------------------------------------------------------------------------
// File-descriptor variants.
//
// Archives living in protected directories (app-private /data/data,
// Android/data) cannot be opened by path (EACCES). Kotlin passes an INT fd
// (e.g. from a Shizuku ParcelFileDescriptor) and Rust opens the magic path
// `/proc/self/fd/<N>`. Opening that path creates an INDEPENDENT file
// description, so Rust never owns or closes the caller's fd; Kotlin keeps
// ownership and closes after the call. All I/O flows through the same
// `backend::*` calls, so the existing `Limits`/cancel machinery applies
// unchanged. An invalid fd surfaces as `ArchiveError::Io` (thrown as
// RuntimeException by the JNI layer) — never a panic/unwrap.
// ---------------------------------------------------------------------------

/// Map a caller-provided fd to its magic `/proc/self/fd/<N>` path.
#[allow(non_snake_case)]
fn fdPath(fd: jint) -> PathBuf {
    PathBuf::from(format!("/proc/self/fd/{fd}"))
}

/// Shared extract core for path and fd entry points.
fn do_extract(archive: &Path, dest: &Path, password: Option<&str>) -> Result<()> {
    // Explicit probe so an invalid fd surfaces as ArchiveError::Io here. The
    // opened File is an independent description dropped immediately; the
    // caller's fd is untouched.
    std::fs::File::open(archive).map(|_| ())?;
    let format = format::detect(archive)?;
    match password {
        Some(p) => backend::extract_with_password(archive, dest, format, &Limits::default(), p),
        None => backend::extract(archive, dest, format, &Limits::default()),
    }
}

/// Shared preview core: identical JSON shape via [`preview_to_json`].
fn do_preview(archive: &Path, password: Option<&str>) -> Result<String> {
    std::fs::File::open(archive).map(|_| ())?;
    let format = format::detect(archive)?;
    let listing = match password {
        Some(p) => backend::list_detailed_with_password(archive, format, p)?,
        None => backend::list_detailed(archive, format)?,
    };
    preview_to_json(&listing)
}

/// Shared test core: identical JSON shape via [`test_report_to_json`] /
/// [`password_required_json`].
fn do_test(archive: &Path, password: Option<&str>) -> Result<String> {
    std::fs::File::open(archive).map(|_| ())?;
    let format = format::detect(archive)?;
    let report = match password {
        Some(p) => backend::test_archive_with_password(archive, format, &Limits::default(), p),
        None => backend::test_archive(archive, format, &Limits::default()),
    };
    match report {
        Ok(r) => test_report_to_json(&r),
        Err(ArchiveError::PasswordRequired(_)) => password_required_json(),
        Err(e) => Err(e),
    }
}

/// Shared JNI string-result finisher so fd and path variants throw identical
/// `RuntimeException` shapes with only the `op` label differing.
fn finish_json_string<'local>(
    env: &mut JNIEnv<'local>,
    op: &str,
    outcome: std::thread::Result<Result<String>>,
) -> JString<'local> {
    match outcome {
        Ok(Ok(json)) => match env.new_string(json) {
            Ok(s) => s,
            Err(e) => {
                throw(env, format!("{op} failed: {e}"));
                JString::default()
            }
        },
        Ok(Err(e)) => {
            throw(env, format!("{op} failed: {e}"));
            JString::default()
        }
        Err(_) => {
            throw(env, format!("{op} failed: internal panic"));
            JString::default()
        }
    }
}

/// `extractFd(fd: Int, destDir: String): Int`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_extractFd(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
    dest_str: JString,
) -> jint {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        clear_cancel();
        let archive = fdPath(fd);
        let dest = PathBuf::from(read_string(&mut env, &dest_str)?);
        do_extract(&archive, &dest, None)
    }));
    finish_int(&mut env, "extractFd", outcome)
}

/// `extractWithPasswordFd(fd: Int, destDir: String, password: String): Int`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_extractWithPasswordFd(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
    dest_str: JString,
    password_str: JString,
) -> jint {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<()> {
        clear_cancel();
        let archive = fdPath(fd);
        let dest = PathBuf::from(read_string(&mut env, &dest_str)?);
        let password = read_string(&mut env, &password_str)?;
        let result = do_extract(&archive, &dest, Some(&password));
        wipe_password(password);
        result
    }));
    finish_int(&mut env, "extractWithPasswordFd", outcome)
}

/// `listArchiveDetailedFd(fd: Int): String` (JSON preview)
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_listArchiveDetailedFd<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
) -> JString<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        clear_cancel();
        let archive = fdPath(fd);
        do_preview(&archive, None)
    }));
    finish_json_string(&mut env, "listArchiveDetailedFd", outcome)
}

/// `listArchiveDetailedWithPasswordFd(fd: Int, password: String): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_listArchiveDetailedWithPasswordFd<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    password_str: JString<'local>,
) -> JString<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        clear_cancel();
        let archive = fdPath(fd);
        let password = read_string(&mut env, &password_str)?;
        let result = do_preview(&archive, Some(&password));
        wipe_password(password);
        result
    }));
    finish_json_string(&mut env, "listArchiveDetailedWithPasswordFd", outcome)
}

/// `testArchiveFd(fd: Int): String` (JSON report)
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_testArchiveFd<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
) -> JString<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        clear_cancel();
        let archive = fdPath(fd);
        do_test(&archive, None)
    }));
    finish_json_string(&mut env, "testArchiveFd", outcome)
}

/// `testArchiveWithPasswordFd(fd: Int, password: String): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_testArchiveWithPasswordFd<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    password_str: JString<'local>,
) -> JString<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String> {
        clear_cancel();
        let archive = fdPath(fd);
        let password = read_string(&mut env, &password_str)?;
        let result = do_test(&archive, Some(&password));
        wipe_password(password);
        result
    }));
    finish_json_string(&mut env, "testArchiveWithPasswordFd", outcome)
}
