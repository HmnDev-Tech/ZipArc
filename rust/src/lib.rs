use anyhow::{Context, Result};
use jni::objects::{JClass, JString, JObjectArray};
use jni::sys::jint;
use jni::JNIEnv;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use walkdir::WalkDir;
use zip::write::SimpleFileOptions;

fn src_files_from_jarray(env: &mut JNIEnv, arr: JObjectArray) -> Result<Vec<PathBuf>> {
    let len = env.get_array_length(&arr)? as usize;
    let mut out = Vec::with_capacity(len);
    for i in 0..len {
        let obj = env.get_object_array_element(&arr, i as i32)?;
        let s: JString = obj.into();
        let rust_str: String = env.get_string(&s)?.into();
        out.push(PathBuf::from(rust_str));
    }
    Ok(out)
}

fn jstring<'local>(env: &mut JNIEnv<'local>, s: &str) -> jni::errors::Result<JString<'local>> {
    env.new_string(s)
}

fn compress_zip(sources: &[PathBuf], dest: &Path) -> Result<()> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent)?;
    }
    let file = File::create(dest).context("create dest zip")?;
    let mut zip = zip::ZipWriter::new(file);
    let options = SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .unix_permissions(0o755);

    for src in sources {
        if src.is_dir() {
            for entry in WalkDir::new(src).into_iter().filter_map(|e| e.ok()) {
                let path = entry.path();
                let rel = path.strip_prefix(src.parent().unwrap_or(Path::new("")))?.to_path_buf();
                let name = rel.to_string_lossy().replace('\\', "/");
                if path.is_dir() {
                    if path.read_dir()?.next().is_none() {
                        zip.add_directory(format!("{}/", name), options)?;
                    }
                    continue;
                }
                zip.start_file(name, options)?;
                let mut f = File::open(path)?;
                let mut buf = Vec::new();
                f.read_to_end(&mut buf)?;
                zip.write_all(&buf)?;
            }
        } else {
            let name = src.file_name().unwrap().to_string_lossy().to_string();
            zip.start_file(name, options)?;
            let mut f = File::open(src)?;
            let mut buf = Vec::new();
            f.read_to_end(&mut buf)?;
            zip.write_all(&buf)?;
        }
    }
    zip.finish()?;
    Ok(())
}

fn extract_archive(archive: &Path, dest: &Path) -> Result<()> {
    fs::create_dir_all(dest)?;
    let ext = archive.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    let name = archive.file_name().and_then(|n| n.to_str()).unwrap_or("").to_lowercase();

    if name.ends_with(".tar.gz") || name.ends_with(".tgz") {
        return extract_tar_gz(archive, dest);
    }
    if name.ends_with(".tar.bz2") { return extract_tar_bz2(archive, dest); }
    if name.ends_with(".tar.xz") { return extract_tar_xz(archive, dest); }
    if name.ends_with(".tar.zst") { return extract_tar_zst(archive, dest); }

    match ext.as_str() {
        "zip" => extract_zip(archive, dest),
        "tar" => extract_tar(archive, dest),
        "gz" => extract_gz_single(archive, dest),
        "bz2" => extract_bz2_single(archive, dest),
        "xz" => extract_xz_single(archive, dest),
        "zst" => extract_zst_single(archive, dest),
        "7z" | "rar" => anyhow::bail!("7z/rar extract requires libarchive backend (stub)"),
        _ => extract_zip(archive, dest),
    }
}

fn extract_zip(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let mut zip = zip::ZipArchive::new(f)?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let out_path = dest.join(entry.name().replace('\\', "/"));
        if entry.is_dir() {
            fs::create_dir_all(&out_path)?;
        } else {
            if let Some(p) = out_path.parent() { fs::create_dir_all(p)?; }
            let mut out = File::create(&out_path)?;
            std::io::copy(&mut entry, &mut out)?;
        }
    }
    Ok(())
}

fn extract_tar(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let mut ar = tar::Archive::new(f);
    ar.unpack(dest)?;
    Ok(())
}
fn extract_tar_gz(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let gz = flate2::read::GzDecoder::new(f);
    let mut ar = tar::Archive::new(gz);
    ar.unpack(dest)?; Ok(())
}
fn extract_tar_bz2(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let bz = bzip2::read::BzDecoder::new(f);
    let mut ar = tar::Archive::new(bz);
    ar.unpack(dest)?; Ok(())
}
fn extract_tar_xz(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let xz = xz2::read::XzDecoder::new(f);
    let mut ar = tar::Archive::new(xz);
    ar.unpack(dest)?; Ok(())
}
fn extract_tar_zst(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let zst = zstd::stream::read::Decoder::new(f)?;
    let mut ar = tar::Archive::new(zst);
    ar.unpack(dest)?; Ok(())
}
fn extract_gz_single(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let mut gz = flate2::read::GzDecoder::new(f);
    let stem = archive.file_stem().unwrap().to_string_lossy().to_string();
    let mut out = File::create(dest.join(stem))?;
    std::io::copy(&mut gz, &mut out)?; Ok(())
}
fn extract_bz2_single(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let mut bz = bzip2::read::BzDecoder::new(f);
    let stem = archive.file_stem().unwrap().to_string_lossy().to_string();
    let mut out = File::create(dest.join(stem))?;
    std::io::copy(&mut bz, &mut out)?; Ok(())
}
fn extract_xz_single(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let mut xz = xz2::read::XzDecoder::new(f);
    let stem = archive.file_stem().unwrap().to_string_lossy().to_string();
    let mut out = File::create(dest.join(stem))?;
    std::io::copy(&mut xz, &mut out)?; Ok(())
}
fn extract_zst_single(archive: &Path, dest: &Path) -> Result<()> {
    let f = File::open(archive)?;
    let mut zst = zstd::stream::read::Decoder::new(f)?;
    let stem = archive.file_stem().unwrap().to_string_lossy().to_string();
    let mut out = File::create(dest.join(stem))?;
    std::io::copy(&mut zst, &mut out)?; Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_compress(
    mut env: JNIEnv,
    _class: JClass,
    srcArray: JObjectArray,
    destStr: JString,
) -> jint {
    let res: Result<jint> = (|| {
        let sources = src_files_from_jarray(&mut env, srcArray)?;
        let dest: String = env.get_string(&destStr)?.into();
        compress_zip(&sources, Path::new(&dest))?;
        Ok(0)
    })();
    match res {
        Ok(v) => v,
        Err(e) => { let _ = env.throw_new("java/lang/RuntimeException", format!("compress failed: {e}")); -1 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_extract(
    mut env: JNIEnv,
    _class: JClass,
    archiveStr: JString,
    destStr: JString,
) -> jint {
    let res: Result<jint> = (|| {
        let archive: String = env.get_string(&archiveStr)?.into();
        let dest: String = env.get_string(&destStr)?.into();
        extract_archive(Path::new(&archive), Path::new(&dest))?;
        Ok(0)
    })();
    match res {
        Ok(v) => v,
        Err(e) => { let _ = env.throw_new("java/lang/RuntimeException", format!("extract failed: {e}")); -1 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kerneldroid_karchiver_data_RustBridge_listArchive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archiveStr: JString<'local>,
) -> JObjectArray<'local> {
    let archive: String = match env.get_string(&archiveStr) { Ok(s) => s.into(), Err(_) => "".to_string() };
    let entries: Vec<String> = (|| -> Result<Vec<String>> {
        let f = File::open(Path::new(&archive))?;
        let mut zip = zip::ZipArchive::new(f)?;
        let mut out = Vec::new();
        for i in 0..zip.len() {
            let e = zip.by_index(i)?;
            out.push(e.name().to_string());
        }
        Ok(out)
    })().unwrap_or_default();

    let string_class = env.find_class("java/lang/String").unwrap();
    let arr = env.new_object_array(entries.len() as i32, &string_class, JString::default()).unwrap();
    for (i, s) in entries.iter().enumerate() {
        let js = jstring(&mut env, s).unwrap();
        env.set_object_array_element(&arr, i as i32, js).unwrap();
    }
    arr
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::tempdir;

    #[test]
    fn zip_roundtrip() {
        let dir = tempdir().unwrap();
        let src = dir.path().join("hello.txt");
        File::create(&src).unwrap().write_all(b"hello karchiver").unwrap();
        let dest = dir.path().join("out.zip");
        compress_zip(&[src.clone()], &dest).unwrap();
        let out_dir = dir.path().join("out");
        extract_archive(&dest, &out_dir).unwrap();
        let content = std::fs::read_to_string(out_dir.join("hello.txt")).unwrap();
        assert_eq!(content, "hello karchiver");
    }
}
