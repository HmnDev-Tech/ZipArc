//! JNI entry points.
//!
//! Every exported function is wrapped in [`std::panic::catch_unwind`] so a
//! panic in Rust (or in a dependency) becomes a thrown
//! `java.lang.RuntimeException` instead of aborting the Android process.
//! The three symbol names/signatures below are part of the Kotlin ABI and must
//! not change.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::jint;

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

fn escape_json_string(out: &mut String, value: &str) {
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
}

fn preview_to_json(listing: &crate::backend::PreviewListing) -> String {
    let mut out = String::from("{\"encrypted\":");
    out.push_str(if listing.encrypted { "true" } else { "false" });
    out.push_str(",\"entries\":[");
    for (i, e) in listing.entries.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"name\":\"");
        escape_json_string(&mut out, &e.name);
        out.push_str("\",\"size\":");
        out.push_str(&e.size.to_string());
        out.push_str(",\"isDir\":");
        out.push_str(if e.is_dir { "true" } else { "false" });
        out.push_str(",\"encrypted\":");
        out.push_str(if e.encrypted { "true" } else { "false" });
        out.push('}');
    }
    out.push_str("]}");
    out
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
        Ok(preview_to_json(&listing))
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
            .map(|listing| preview_to_json(&listing));
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
            Ok(report) => Ok(test_report_to_json(&report)),
            Err(ArchiveError::PasswordRequired(_)) => Ok("{\"ok\":false,\"entries\":0,\"totalSize\":0,\"failures\":[],\"passwordRequired\":true}".to_string()),
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

fn test_report_to_json(report: &crate::backend::TestReport) -> String {
    let mut out = String::from("{\"ok\":");
    out.push_str(if report.failures.is_empty() && !report.password_required {
        "true"
    } else {
        "false"
    });
    out.push_str(",\"entries\":");
    out.push_str(&report.entries.to_string());
    out.push_str(",\"totalSize\":");
    out.push_str(&report.total_size.to_string());
    out.push_str(",\"failures\":[");
    for (i, f) in report.failures.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"name\":\"");
        escape_json_string(&mut out, &f.name);
        out.push_str("\",\"reason\":\"");
        escape_json_string(&mut out, &f.reason);
        out.push_str("\"}");
    }
    out.push_str("],\"passwordRequired\":");
    out.push_str(if report.password_required {
        "true"
    } else {
        "false"
    });
    out.push('}');
    out
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
            Ok(report) => Ok(test_report_to_json(&report)),
            Err(ArchiveError::PasswordRequired(_)) => Ok("{\"ok\":false,\"entries\":0,\"totalSize\":0,\"failures\":[],\"passwordRequired\":true}".to_string()),
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
