//! 7-Zip backend (pure-Rust `sevenz-rust2`).

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use sevenz_rust2::encoder_options::{AesEncoderOptions, Lzma2Options};
use sevenz_rust2::{ArchiveEntry, ArchiveReader, ArchiveWriter, Password, SourceReader};

use crate::backend::collect_sources;
use crate::backend::{PreviewEntry, PreviewListing, TestFailure, TestReport};
use crate::error::{ArchiveError, CANCEL_MARKER, LIMIT_MARKER, Result, classify_io};
use crate::io_util::{
    AtomicFile, CancelReader, LimitState, LimitedReader, Limits, check_cancelled,
    create_dir_all_checked, create_output_file, is_cancelled, log_warn, progress_reset,
    reject_symlink_ancestors, safe_join, set_file_mode,
};

fn map_err(e: sevenz_rust2::Error) -> ArchiveError {
    if let sevenz_rust2::Error::Io(io_err, _) = &e {
        let msg = io_err.to_string();
        if msg.contains(CANCEL_MARKER) {
            return ArchiveError::Cancelled;
        }
        if msg.contains(LIMIT_MARKER) {
            return ArchiveError::limit(e);
        }
    }
    ArchiveError::backend(e)
}

fn map_open_err(e: sevenz_rust2::Error) -> ArchiveError {
    map_open_err_pw(e, false)
}

fn map_open_err_pw(e: sevenz_rust2::Error, have_password: bool) -> ArchiveError {
    match &e {
        sevenz_rust2::Error::PasswordRequired => {
            ArchiveError::password_required("archive header is encrypted")
        }
        sevenz_rust2::Error::MaybeBadPassword(_) => {
            if have_password {
                ArchiveError::wrong_password("incorrect password")
            } else {
                ArchiveError::password_required("archive requires a password")
            }
        }
        _ => map_err(e),
    }
}

fn password_of(password: &str) -> (Password, bool) {
    if password.is_empty() {
        (Password::empty(), false)
    } else {
        (Password::new(password), true)
    }
}

pub fn compress(sources: &[PathBuf], dest: &Path, limits: &Limits) -> Result<()> {
    compress_impl(sources, dest, limits, None)
}

pub fn compress_with_password(
    sources: &[PathBuf],
    dest: &Path,
    limits: &Limits,
    password: &str,
) -> Result<()> {
    if password.is_empty() {
        return compress_impl(sources, dest, limits, None);
    }
    compress_impl(sources, dest, limits, Some(password))
}

const SOLID_MAX_FILES: usize = 128;
const SOLID_MAX_BYTES: u64 = 16 * 1024 * 1024;
const READ_BUF_CAP: usize = 65536;
const MT_CHUNK_BYTES: u64 = 4 * 1024 * 1024;

fn lzma2_options() -> Lzma2Options {
    let threads = std::thread::available_parallelism()
        .map(|n| n.get() as u32)
        .unwrap_or(2)
        .clamp(1, 8);
    Lzma2Options::from_level_mt(5, threads, MT_CHUNK_BYTES)
}

fn flush_solid_batch(
    writer: &mut ArchiveWriter<File>,
    entries: &mut Vec<ArchiveEntry>,
    readers: &mut Vec<SourceReader<Box<dyn Read>>>,
    meta: &mut Vec<(String, u64)>,
    state: &mut LimitState,
) -> Result<()> {
    if entries.is_empty() {
        return Ok(());
    }
    writer
        .push_archive_entries(std::mem::take(entries), std::mem::take(readers))
        .map_err(map_err)?;
    for (name, declared) in std::mem::take(meta) {
        state.finish_entry(&name, Some(declared), declared)?;
    }
    Ok(())
}

fn compress_impl(
    sources: &[PathBuf],
    dest: &Path,
    limits: &Limits,
    password: Option<&str>,
) -> Result<()> {
    let af = AtomicFile::new(dest)?;
    let entries = collect_sources(sources, &[dest, af.path()], limits)?;
    let mut state = LimitState::new(limits);
    let mut writer = ArchiveWriter::create(af.path()).map_err(map_err)?;
    if let Some(pw) = password {
        writer.set_content_methods(vec![
            AesEncoderOptions::new(Password::new(pw)).into(),
            lzma2_options().into(),
        ]);
    } else {
        writer.set_content_methods(vec![lzma2_options().into()]);
    }

    let mut batch_entries: Vec<ArchiveEntry> = Vec::new();
    let mut batch_readers: Vec<SourceReader<Box<dyn Read>>> = Vec::new();
    let mut batch_meta: Vec<(String, u64)> = Vec::new();
    let mut batch_bytes: u64 = 0;

    for e in &entries {
        check_cancelled()?;
        if e.is_dir {
            flush_solid_batch(
                &mut writer,
                &mut batch_entries,
                &mut batch_readers,
                &mut batch_meta,
                &mut state,
            )?;
            let entry = ArchiveEntry::new_directory(&e.name);
            writer
                .push_archive_entry::<io::Empty>(entry, None)
                .map_err(map_err)?;
            continue;
        }
        state.begin_entry(&e.name, Some(e.size))?;
        let entry = ArchiveEntry::from_path(&e.path, e.name.clone());
        let allowance = state.allowance(Some(e.size));
        let reader: Box<dyn Read> = if e.size == 0 {
            Box::new(io::empty())
        } else {
            Box::new(LimitedReader::new(
                BufReader::with_capacity(READ_BUF_CAP, File::open(&e.path)?),
                allowance,
            ))
        };
        batch_bytes = batch_bytes.saturating_add(e.size);
        batch_entries.push(entry);
        batch_readers.push(SourceReader::new(reader));
        batch_meta.push((e.name.clone(), e.size));
        if batch_entries.len() >= SOLID_MAX_FILES || batch_bytes >= SOLID_MAX_BYTES {
            flush_solid_batch(
                &mut writer,
                &mut batch_entries,
                &mut batch_readers,
                &mut batch_meta,
                &mut state,
            )?;
            batch_bytes = 0;
        }
    }
    flush_solid_batch(
        &mut writer,
        &mut batch_entries,
        &mut batch_readers,
        &mut batch_meta,
        &mut state,
    )?;

    writer.finish()?;
    af.commit()
}

fn extract_entry(
    entry: &ArchiveEntry,
    data: &mut dyn Read,
    root: &Path,
    state: &mut LimitState,
) -> Result<()> {
    check_cancelled()?;
    let name = entry.name().to_string();
    if entry.is_directory() {
        let out = safe_join(root, &name)?;
        create_dir_all_checked(root, &out)?;
        return Ok(());
    }

    let declared = entry.size();
    let out = safe_join(root, &name)?;
    let parent = out
        .parent()
        .ok_or_else(|| ArchiveError::invalid("entry has no parent"))?;
    create_dir_all_checked(root, parent)?;
    reject_symlink_ancestors(root, &out)?;

    state.begin_entry(&name, Some(declared))?;
    let mut writer = BufWriter::new(create_output_file(&out)?);
    let allowance = state.allowance(Some(declared));
    let mut limited = LimitedReader::new(data, allowance);
    io::copy(&mut limited, &mut writer).map_err(classify_io)?;
    writer.flush()?;
    drop(writer);
    set_file_mode(&out)?;
    state.finish_entry(&name, Some(declared), limited.count())
}

/// Extract a 7z archive into `dest`.
pub fn extract(archive: &Path, dest: &Path, limits: &Limits) -> Result<()> {
    extract_impl(archive, dest, limits, None)
}

pub fn extract_with_password(
    archive: &Path,
    dest: &Path,
    limits: &Limits,
    password: &str,
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
    password: Option<&str>,
) -> Result<()> {
    std::fs::create_dir_all(dest)?;
    let dest_root = std::fs::canonicalize(dest)?;
    let (pw, have_password) = match password {
        Some(p) => password_of(p),
        None => (Password::empty(), false),
    };
    let mut reader =
        ArchiveReader::open(archive, pw).map_err(|e| map_open_err_pw(e, have_password))?;
    let total = reader
        .archive()
        .files
        .iter()
        .filter(|f| !f.is_directory())
        .fold(0u64, |acc, f| acc.saturating_add(f.size()));
    progress_reset(total);
    let mut state = LimitState::new(limits);
    let mut warnings: Vec<String> = Vec::new();
    let mut fatal: Option<ArchiveError> = None;

    let result = reader.for_each_entries(|entry, data| {
        if is_cancelled() {
            fatal = Some(ArchiveError::Cancelled);
            return Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()));
        }
        if fatal.is_some() {
            return Ok(true);
        }
        let name = entry.name().to_string();
        match extract_entry(entry, &mut *data, &dest_root, &mut state) {
            Ok(()) => Ok(true),
            Err(e) if e.is_fatal() => {
                fatal = Some(e);
                Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()))
            }
            Err(e) => {
                warnings.push(format!("{name}: {e}"));
                let mut cancellable = CancelReader::new(&mut *data);
                let _ = io::copy(&mut cancellable, &mut io::sink());
                if is_cancelled() {
                    fatal = Some(ArchiveError::Cancelled);
                    return Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()));
                }
                Ok(true)
            }
        }
    });

    if let Some(e) = fatal {
        return Err(e);
    }
    result.map_err(|e| map_open_err_pw(e, have_password))?;

    for w in &warnings {
        log_warn(format!("7z entry skipped: {w}"));
    }
    Ok(())
}

/// List entry names inside a 7z archive.
pub fn list(archive: &Path) -> Result<Vec<String>> {
    let reader = ArchiveReader::open(archive, Password::empty()).map_err(map_open_err)?;
    Ok(reader
        .archive()
        .files
        .iter()
        .map(|f| f.name().to_string())
        .collect())
}

pub fn list_detailed(archive: &Path) -> Result<PreviewListing> {
    list_detailed_impl(archive, None)
}

pub fn list_detailed_with_password(archive: &Path, password: &str) -> Result<PreviewListing> {
    if password.is_empty() {
        return list_detailed_impl(archive, None);
    }
    list_detailed_impl(archive, Some(password))
}

fn list_detailed_impl(archive: &Path, password: Option<&str>) -> Result<PreviewListing> {
    let (pw, have_password) = match password {
        Some(p) => password_of(p),
        None => (Password::empty(), false),
    };
    let reader = ArchiveReader::open(archive, pw).map_err(|e| map_open_err_pw(e, have_password))?;
    let mut out = Vec::with_capacity(reader.archive().files.len());
    for f in &reader.archive().files {
        check_cancelled()?;
        let is_dir = f.is_directory();
        out.push(PreviewEntry {
            name: f.name().to_string(),
            size: if is_dir { 0 } else { f.size() },
            is_dir,
            encrypted: have_password,
        });
    }
    Ok(PreviewListing::new(out))
}

pub fn test(archive: &Path, limits: &Limits) -> Result<TestReport> {
    test_impl(archive, limits, None)
}

pub fn test_with_password(archive: &Path, limits: &Limits, password: &str) -> Result<TestReport> {
    if password.is_empty() {
        return test_impl(archive, limits, None);
    }
    test_impl(archive, limits, Some(password))
}

fn test_impl(archive: &Path, limits: &Limits, password: Option<&str>) -> Result<TestReport> {
    progress_reset(0);
    let (pw, have_password) = match password {
        Some(p) => password_of(p),
        None => (Password::empty(), false),
    };
    let mut reader =
        ArchiveReader::open(archive, pw).map_err(|e| map_open_err_pw(e, have_password))?;
    let mut state = LimitState::new(limits);
    let mut failures: Vec<TestFailure> = Vec::new();
    let mut total_size: u64 = 0;
    let mut count: usize = 0;
    let mut fatal: Option<ArchiveError> = None;

    let result = reader.for_each_entries(|entry, data| {
        if is_cancelled() {
            fatal = Some(ArchiveError::Cancelled);
            return Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()));
        }
        if fatal.is_some() {
            return Ok(true);
        }
        if check_cancelled().is_err() {
            fatal = Some(ArchiveError::Cancelled);
            return Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()));
        }
        let name = entry.name().to_string();
        count += 1;
        if entry.is_directory() {
            match state.begin_entry(&name, Some(0)) {
                Ok(()) => {}
                Err(e) if e.is_fatal() => {
                    fatal = Some(e);
                    return Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()));
                }
                Err(e) => {
                    failures.push(TestFailure {
                        name,
                        reason: e.to_string(),
                    });
                }
            }
            return Ok(true);
        }
        if crate::io_util::sanitize_entry_name(&name).is_err() {
            failures.push(TestFailure {
                name,
                reason: "unsafe entry name".to_string(),
            });
            let mut cancellable = CancelReader::new(&mut *data);
            let _ = io::copy(&mut cancellable, &mut io::sink());
            return Ok(true);
        }
        let declared = entry.size();
        match state.begin_entry(&name, Some(declared)) {
            Ok(()) => {}
            Err(e) if e.is_fatal() => {
                fatal = Some(e);
                return Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()));
            }
            Err(e) => {
                failures.push(TestFailure {
                    name,
                    reason: e.to_string(),
                });
                let mut cancellable = CancelReader::new(&mut *data);
                let _ = io::copy(&mut cancellable, &mut io::sink());
                return Ok(true);
            }
        }
        let allowance = state.allowance(Some(declared));
        let mut limited = LimitedReader::new(&mut *data, allowance);
        let mut sink = io::sink();
        match io::copy(&mut limited, &mut sink).map_err(classify_io) {
            Ok(_) => {
                let written = limited.count();
                match state.finish_entry(&name, Some(declared), written) {
                    Ok(()) => {
                        total_size = total_size.saturating_add(written);
                    }
                    Err(e) if e.is_fatal() => {
                        fatal = Some(e);
                        return Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()));
                    }
                    Err(e) => {
                        failures.push(TestFailure {
                            name,
                            reason: e.to_string(),
                        });
                    }
                }
                Ok(true)
            }
            Err(e) if e.is_fatal() => {
                fatal = Some(e);
                Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()))
            }
            Err(e) => {
                failures.push(TestFailure {
                    name,
                    reason: e.to_string(),
                });
                let mut cancellable = CancelReader::new(&mut *data);
                let _ = io::copy(&mut cancellable, &mut io::sink());
                if is_cancelled() {
                    fatal = Some(ArchiveError::Cancelled);
                    return Err(sevenz_rust2::Error::Unsupported("karchiver fatal".into()));
                }
                Ok(true)
            }
        }
    });

    if let Some(e) = fatal {
        return Err(e);
    }
    result.map_err(|e| map_open_err_pw(e, have_password))?;

    Ok(TestReport {
        entries: count,
        total_size,
        failures,
        password_required: false,
    })
}
