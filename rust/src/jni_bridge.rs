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
use crate::io_util::Limits;

fn throw(env: &mut JNIEnv, msg: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/RuntimeException", msg.as_ref());
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Result<String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|e| ArchiveError::backend(format!("invalid Java string: {e}")))
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
        Ok(Ok(())) => 0,
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
