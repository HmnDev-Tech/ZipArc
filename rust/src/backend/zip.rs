//! ZIP read/write backend.

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use crate::backend::collect_sources;
use crate::backend::{PreviewEntry, PreviewListing, TestFailure, TestReport};
use crate::error::{ArchiveError, Result, classify_io};
use crate::io_util::{
    AtomicFile, LimitState, LimitedReader, Limits, check_cancelled, create_dir_all_checked,
    create_output_file, log_warn, reject_symlink_ancestors, safe_join, safe_link_target,
    set_file_mode,
};
use ::zip::read::ZipFile;
use ::zip::result::ZipError;
use ::zip::write::{FileOptions, SimpleFileOptions};
use ::zip::{AesMode, CompressionMethod, ZipArchive, ZipReadOptions, ZipWriter};

fn map_open_err(e: ZipError) -> ArchiveError {
    match e {
        ZipError::InvalidPassword => {
            ArchiveError::wrong_password("incorrect password for archive entry")
        }
        ZipError::UnsupportedArchive(msg) if msg.contains("Password required") => {
            ArchiveError::password_required("archive requires a password")
        }
        other => ArchiveError::backend(other),
    }
}

fn file_options() -> SimpleFileOptions {
    SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .unix_permissions(0o644)
        .large_file(true)
}

fn file_options_encrypted(password: &[u8]) -> FileOptions<'_, ()> {
    SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .unix_permissions(0o644)
        .large_file(true)
        .with_aes_encryption_bytes(AesMode::Aes256, password)
}

fn dir_options() -> SimpleFileOptions {
    SimpleFileOptions::default()
        .compression_method(CompressionMethod::Stored)
        .unix_permissions(0o755)
}

/// Compress `sources` into a ZIP file at `dest` (written atomically).
pub fn compress(sources: &[PathBuf], dest: &Path, limits: &Limits) -> Result<()> {
    compress_impl(sources, dest, limits, None)
}

pub fn compress_with_password(
    sources: &[PathBuf],
    dest: &Path,
    limits: &Limits,
    password: &[u8],
) -> Result<()> {
    if password.is_empty() {
        return compress_impl(sources, dest, limits, None);
    }
    compress_impl(sources, dest, limits, Some(password))
}

fn compress_impl(
    sources: &[PathBuf],
    dest: &Path,
    limits: &Limits,
    password: Option<&[u8]>,
) -> Result<()> {
    let af = AtomicFile::new(dest)?;
    let entries = collect_sources(sources, &[dest, af.path()], limits)?;
    let file = File::options()
        .write(true)
        .create_new(true)
        .open(af.path())?;
    let mut writer = ZipWriter::new(BufWriter::new(file));
    let mut state = LimitState::new(limits);

    for e in &entries {
        check_cancelled()?;
        if e.is_dir {
            writer
                .add_directory(e.name.clone(), dir_options())
                .map_err(ArchiveError::backend)?;
            continue;
        }
        state.begin_entry(&e.name, Some(e.size))?;
        match password {
            Some(pw) => writer
                .start_file(e.name.clone(), file_options_encrypted(pw))
                .map_err(ArchiveError::backend)?,
            None => writer
                .start_file(e.name.clone(), file_options())
                .map_err(ArchiveError::backend)?,
        }
        let allowance = state.allowance(Some(e.size));
        let reader = BufReader::new(File::open(&e.path)?);
        let mut limited = LimitedReader::new(reader, allowance);
        io::copy(&mut limited, &mut writer).map_err(classify_io)?;
        state.finish_entry(&e.name, Some(e.size), limited.count())?;
    }

    let buffered = writer.finish().map_err(ArchiveError::backend)?;
    buffered
        .into_inner()
        .map_err(|e| ArchiveError::Io(e.into_error()))?;
    af.commit()
}

/// Extract a ZIP into `dest`, skipping per-entry failures and aborting only on
/// security / limit violations.
pub fn extract(archive: &Path, dest: &Path, limits: &Limits) -> Result<()> {
    extract_impl(archive, dest, limits, None)
}

pub fn extract_with_password(
    archive: &Path,
    dest: &Path,
    limits: &Limits,
    password: &[u8],
) -> Result<()> {
    if password.is_empty() {
        return extract_impl(archive, dest, limits, None);
    }
    extract_impl(archive, dest, limits, Some(password))
}

fn extract_impl(
    archive: &Path,
    dest: &Path,
    limits: &Limits,
    password: Option<&[u8]>,
) -> Result<()> {
    std::fs::create_dir_all(dest)?;
    let dest_root = std::fs::canonicalize(dest)?;
    let file = File::open(archive)?;
    let mut zip = ZipArchive::new(BufReader::new(file)).map_err(ArchiveError::backend)?;
    let mut state = LimitState::new(limits);
    let mut warnings: Vec<String> = Vec::new();

    for i in 0..zip.len() {
        check_cancelled()?;
        let mut entry = match open_entry(&mut zip, i, password) {
            Ok(e) => e,
            Err(e) if e.is_fatal() => return Err(e),
            Err(e) => {
                warnings.push(format!("entry #{i}: {e}"));
                continue;
            }
        };
        let name = entry.name().to_string();
        let declared = entry.size();

        if entry.is_dir() {
            match safe_join(&dest_root, &name)
                .and_then(|out| create_dir_all_checked(&dest_root, &out))
            {
                Ok(()) => {}
                Err(e) if e.is_fatal() => return Err(e),
                Err(e) => warnings.push(format!("{name}: {e}")),
            }
            continue;
        }

        if entry.is_symlink() {
            match extract_symlink(&mut entry, &name, &dest_root) {
                Ok(()) => {}
                Err(e) if e.is_fatal() => return Err(e),
                Err(e) => warnings.push(format!("{name}: {e}")),
            }
            continue;
        }

        let result = (|| -> Result<()> {
            let out = safe_join(&dest_root, &name)?;
            let parent = out
                .parent()
                .ok_or_else(|| ArchiveError::invalid("entry has no parent"))?;
            create_dir_all_checked(&dest_root, parent)?;
            reject_symlink_ancestors(&dest_root, &out)?;
            state.begin_entry(&name, Some(declared))?;
            state.check_ratio(&name, entry.compressed_size(), declared)?;

            let mut writer = BufWriter::new(create_output_file(&out)?);
            let allowance = state.allowance(Some(declared));
            let mut limited = LimitedReader::new(&mut entry, allowance);
            io::copy(&mut limited, &mut writer).map_err(classify_io)?;
            writer.flush()?;
            drop(writer);
            set_file_mode(&out)?;
            state.finish_entry(&name, Some(declared), limited.count())
        })();

        match result {
            Ok(()) => {}
            Err(e) if e.is_fatal() => return Err(e),
            Err(e) => warnings.push(format!("{name}: {e}")),
        }
    }

    for w in &warnings {
        log_warn(format!("zip entry skipped: {w}"));
    }
    Ok(())
}

fn open_entry<'a, R: Read + std::io::Seek>(
    zip: &'a mut ZipArchive<R>,
    index: usize,
    password: Option<&[u8]>,
) -> Result<ZipFile<'a, R>> {
    zip.by_index_with_options(index, ZipReadOptions::new().password(password))
        .map_err(map_open_err)
}

fn extract_symlink<R: Read>(entry: &mut R, name: &str, root: &Path) -> Result<()> {
    let out = safe_join(root, name)?;
    let parent = out
        .parent()
        .ok_or_else(|| ArchiveError::invalid("symlink has no parent"))?;
    create_dir_all_checked(root, parent)?;
    reject_symlink_ancestors(root, &out)?;

    let mut limited = LimitedReader::new(entry, 4096);
    let mut target = Vec::new();
    limited.read_to_end(&mut target).map_err(classify_io)?;
    let target =
        String::from_utf8(target).map_err(|_| ArchiveError::invalid("non-UTF-8 symlink target"))?;
    safe_link_target(root, &out, &target)?;

    #[cfg(unix)]
    std::os::unix::fs::symlink(&target, &out)?;
    #[cfg(not(unix))]
    log_warn(format!("skipping symlink {} -> {}", out.display(), target));

    Ok(())
}

pub fn list_detailed(archive: &Path) -> Result<PreviewListing> {
    let file = File::open(archive)?;
    let mut zip = ZipArchive::new(BufReader::new(file)).map_err(ArchiveError::backend)?;
    let mut out = Vec::with_capacity(zip.len());
    for i in 0..zip.len() {
        check_cancelled()?;
        let entry = zip.by_index_raw(i).map_err(ArchiveError::backend)?;
        let is_dir = entry.is_dir();
        out.push(PreviewEntry {
            name: entry.name().to_string(),
            size: if is_dir { 0 } else { entry.size() },
            is_dir,
            encrypted: entry.encrypted(),
        });
    }
    Ok(PreviewListing::new(out))
}

pub fn list_detailed_with_password(archive: &Path, _password: &[u8]) -> Result<PreviewListing> {
    list_detailed(archive)
}

pub fn list(archive: &Path) -> Result<Vec<String>> {
    let file = File::open(archive)?;
    let mut zip = ZipArchive::new(BufReader::new(file)).map_err(ArchiveError::backend)?;
    let mut out = Vec::with_capacity(zip.len());
    for i in 0..zip.len() {
        let entry = zip.by_index_raw(i).map_err(ArchiveError::backend)?;
        out.push(entry.name().to_string());
    }
    Ok(out)
}

pub fn test(archive: &Path, limits: &Limits) -> Result<TestReport> {
    test_impl(archive, limits, None)
}

pub fn test_with_password(archive: &Path, limits: &Limits, password: &[u8]) -> Result<TestReport> {
    if password.is_empty() {
        return test_impl(archive, limits, None);
    }
    test_impl(archive, limits, Some(password))
}

fn test_impl(archive: &Path, limits: &Limits, password: Option<&[u8]>) -> Result<TestReport> {
    let file = File::open(archive)?;
    let mut zip = ZipArchive::new(BufReader::new(file)).map_err(ArchiveError::backend)?;
    let total = zip.len();
    let mut state = LimitState::new(limits);
    let mut failures: Vec<TestFailure> = Vec::new();
    let mut total_size: u64 = 0;

    for i in 0..total {
        check_cancelled()?;
        let mut entry = match open_entry(&mut zip, i, password) {
            Ok(e) => e,
            Err(e) if e.is_fatal() => return Err(e),
            Err(e) => {
                failures.push(TestFailure {
                    name: format!("entry #{i}"),
                    reason: e.to_string(),
                });
                continue;
            }
        };
        let name = entry.name().to_string();
        let declared = entry.size();
        let compressed = entry.compressed_size();
        if entry.encrypted() && password.is_none() {
            return Err(ArchiveError::password_required(format!(
                "entry '{name}' is encrypted"
            )));
        }
        if entry.is_dir() {
            match state.begin_entry(&name, Some(0)) {
                Ok(()) => {}
                Err(e) if e.is_fatal() => return Err(e),
                Err(e) => {
                    failures.push(TestFailure {
                        name,
                        reason: e.to_string(),
                    });
                }
            }
            continue;
        }
        if crate::io_util::sanitize_entry_name(&name).is_err() {
            failures.push(TestFailure {
                name,
                reason: "unsafe entry name".to_string(),
            });
            continue;
        }
        match state.begin_entry(&name, Some(declared)) {
            Ok(()) => {}
            Err(e) if e.is_fatal() => return Err(e),
            Err(e) => {
                failures.push(TestFailure {
                    name,
                    reason: e.to_string(),
                });
                continue;
            }
        }
        state.check_ratio(&name, compressed, declared)?;
        let allowance = state.allowance(Some(declared));
        let mut limited = LimitedReader::new(&mut entry, allowance);
        let mut sink = io::sink();
        match io::copy(&mut limited, &mut sink).map_err(classify_io) {
            Ok(_) => {
                let written = limited.count();
                match state.finish_entry(&name, Some(declared), written) {
                    Ok(()) => {
                        total_size = total_size.saturating_add(written);
                    }
                    Err(e) if e.is_fatal() => return Err(e),
                    Err(e) => {
                        failures.push(TestFailure {
                            name,
                            reason: e.to_string(),
                        });
                    }
                }
            }
            Err(e) if e.is_fatal() => return Err(e),
            Err(e) => {
                failures.push(TestFailure {
                    name,
                    reason: e.to_string(),
                });
            }
        }
    }

    Ok(TestReport {
        entries: total,
        total_size,
        failures,
        password_required: false,
    })
}
