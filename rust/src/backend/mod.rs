//! Backend dispatch plus the shared source-tree walker.

pub mod rar;
pub mod sevenz;
pub mod single;
pub mod tar;
pub mod zip;

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use walkdir::WalkDir;

use crate::error::{ArchiveError, Result};
use crate::format::Format;
use crate::io_util::{Limits, check_cancelled, log_warn, progress_reset};

/// One file or directory selected for compression.
#[derive(Debug, Clone)]
pub struct SourceEntry {
    /// Absolute / caller-supplied path on disk.
    pub path: PathBuf,
    /// Sanitised archive-relative name.
    pub name: String,
    /// Whether this is a directory.
    pub is_dir: bool,
    /// Uncompressed size for regular files.
    pub size: u64,
}

#[derive(Debug, Clone)]
pub struct PreviewEntry {
    pub name: String,
    pub size: u64,
    pub is_dir: bool,
    pub encrypted: bool,
}

#[derive(Debug, Clone)]
pub struct PreviewListing {
    pub entries: Vec<PreviewEntry>,
    pub encrypted: bool,
}

#[derive(Debug, Clone)]
pub struct TestFailure {
    pub name: String,
    pub reason: String,
}

#[derive(Debug, Clone)]
pub struct TestReport {
    pub entries: usize,
    pub total_size: u64,
    pub failures: Vec<TestFailure>,
    pub password_required: bool,
}

impl TestReport {
    pub fn ok(&self) -> bool {
        self.failures.is_empty() && !self.password_required
    }
}

impl PreviewListing {
    pub fn new(mut entries: Vec<PreviewEntry>) -> Self {
        entries.sort_by(|a, b| a.name.cmp(&b.name));
        let encrypted = entries.iter().any(|e| e.encrypted);
        Self { entries, encrypted }
    }
}
fn skipped(path: &Path, exclude: &[&Path]) -> bool {
    exclude.contains(&path)
}

/// Recursively collect sources, refusing to follow symlinks and surfacing any
/// `WalkDir` error instead of silently dropping entries.
pub fn collect_sources(
    sources: &[PathBuf],
    exclude: &[&Path],
    limits: &Limits,
) -> Result<Vec<SourceEntry>> {
    let mut out: Vec<SourceEntry> = Vec::new();
    let mut total: u64 = 0;

    for src in sources {
        let md = fs::symlink_metadata(src)?;
        if md.file_type().is_symlink() {
            log_warn(format!("skipping symlink source: {}", src.display()));
            continue;
        }
        if md.is_dir() {
            let base = src.parent().unwrap_or_else(|| Path::new(""));
            for entry in WalkDir::new(src).follow_links(false).sort_by_file_name() {
                check_cancelled()?;
                let entry = entry.map_err(|e| {
                    let io_err = e
                        .into_io_error()
                        .unwrap_or_else(|| io::Error::other("walk error"));
                    ArchiveError::Io(io_err)
                })?;
                let path = entry.path();
                if skipped(path, exclude) {
                    continue;
                }
                let ft = entry.file_type();
                if ft.is_symlink() {
                    log_warn(format!("skipping symlink: {}", path.display()));
                    continue;
                }
                let rel = path.strip_prefix(base).map_err(|e| {
                    ArchiveError::invalid(format!("cannot relativise {}: {e}", path.display()))
                })?;
                let name = crate::io_util::sanitize_entry_name(&rel.to_string_lossy())?;
                let size = if ft.is_file() {
                    entry
                        .metadata()
                        .map_err(|e| {
                            ArchiveError::Io(
                                e.into_io_error()
                                    .unwrap_or_else(|| io::Error::other("metadata error")),
                            )
                        })?
                        .len()
                } else {
                    0
                };
                total = total.saturating_add(size);
                out.push(SourceEntry {
                    path: path.to_path_buf(),
                    name,
                    is_dir: ft.is_dir(),
                    size,
                });
            }
        } else if md.is_file() {
            if skipped(src, exclude) {
                continue;
            }
            let name = src
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .ok_or_else(|| ArchiveError::invalid("source has no file name"))?;
            let name = crate::io_util::sanitize_entry_name(&name)?;
            total = total.saturating_add(md.len());
            out.push(SourceEntry {
                path: src.clone(),
                name,
                is_dir: false,
                size: md.len(),
            });
        } else {
            log_warn(format!("skipping non-file source: {}", src.display()));
        }

        if out.len() > limits.max_entries {
            return Err(ArchiveError::limit(format!(
                "source tree has more than {} entries",
                limits.max_entries
            )));
        }
        if total > limits.max_total_size {
            return Err(ArchiveError::limit(
                "source tree exceeds total size limit".to_string(),
            ));
        }
    }
    progress_reset(total);
    Ok(out)
}

/// Compress `sources` into `dest` using the given format.
pub fn compress(sources: &[PathBuf], dest: &Path, format: Format, limits: &Limits) -> Result<()> {
    match format {
        Format::Zip => zip::compress(sources, dest, limits),
        Format::SevenZ => sevenz::compress(sources, dest, limits),
        f if f.is_tar() => tar::compress(sources, dest, f, limits),
        f if f.is_single_stream() => single::compress(sources, dest, f, limits),
        Format::Rar => rar::compress(sources, dest, limits),
        other => Err(ArchiveError::Unsupported(format!(
            "compression to {} is not supported",
            other.label()
        ))),
    }
}

/// Extract `archive` into `dest` using the given format.
pub fn extract(archive: &Path, dest: &Path, format: Format, limits: &Limits) -> Result<()> {
    match format {
        Format::Zip => zip::extract(archive, dest, limits),
        Format::SevenZ => sevenz::extract(archive, dest, limits),
        f if f.is_tar() => tar::extract(archive, dest, f, limits),
        f if f.is_single_stream() => single::extract(archive, dest, f, limits),
        Format::Rar => rar::extract(archive, dest, limits),
        other => Err(ArchiveError::Unsupported(format!(
            "extraction of {} is not supported",
            other.label()
        ))),
    }
}

pub fn list(archive: &Path, format: Format) -> Result<Vec<String>> {
    match format {
        Format::Zip => zip::list(archive),
        Format::SevenZ => sevenz::list(archive),
        f if f.is_tar() => tar::list(archive, f),
        f if f.is_single_stream() => single::list(archive, f),
        Format::Rar => rar::list(archive),
        other => Err(ArchiveError::Unsupported(format!(
            "listing of {} is not supported",
            other.label()
        ))),
    }
}

pub fn list_detailed(archive: &Path, format: Format) -> Result<PreviewListing> {
    match format {
        Format::Zip => zip::list_detailed(archive),
        Format::SevenZ => sevenz::list_detailed(archive),
        f if f.is_tar() => tar::list_detailed(archive, f),
        f if f.is_single_stream() => single::list_detailed(archive, f),
        Format::Rar => rar::list_detailed(archive),
        other => Err(ArchiveError::Unsupported(format!(
            "listing of {} is not supported",
            other.label()
        ))),
    }
}

pub fn test_archive(archive: &Path, format: Format, limits: &Limits) -> Result<TestReport> {
    match format {
        Format::Zip => zip::test(archive, limits),
        Format::SevenZ => sevenz::test(archive, limits),
        f if f.is_tar() => tar::test(archive, f, limits),
        f if f.is_single_stream() => single::test(archive, f, limits),
        Format::Rar => rar::test(archive, limits),
        other => Err(ArchiveError::Unsupported(format!(
            "verification of {} is not supported",
            other.label()
        ))),
    }
}

fn reject_password<T>(format: Format, op: &str) -> Result<T> {
    Err(ArchiveError::Unsupported(format!(
        "password protection for {op} of {} is not supported",
        format.label()
    )))
}

pub fn compress_with_password(
    sources: &[PathBuf],
    dest: &Path,
    format: Format,
    limits: &Limits,
    password: &str,
) -> Result<()> {
    if password.is_empty() {
        return compress(sources, dest, format, limits);
    }
    match format {
        Format::Zip => zip::compress_with_password(sources, dest, limits, password.as_bytes()),
        Format::SevenZ => sevenz::compress_with_password(sources, dest, limits, password),
        Format::Rar => rar::compress_with_password(sources, dest, limits, password.as_bytes()),
        other => reject_password(other, "compression"),
    }
}

pub fn extract_with_password(
    archive: &Path,
    dest: &Path,
    format: Format,
    limits: &Limits,
    password: &str,
) -> Result<()> {
    if password.is_empty() {
        return extract(archive, dest, format, limits);
    }
    match format {
        Format::Zip => zip::extract_with_password(archive, dest, limits, password.as_bytes()),
        Format::SevenZ => sevenz::extract_with_password(archive, dest, limits, password),
        Format::Rar => rar::extract_with_password(archive, dest, limits, password),
        other => reject_password(other, "extraction"),
    }
}

pub fn list_detailed_with_password(
    archive: &Path,
    format: Format,
    password: &str,
) -> Result<PreviewListing> {
    if password.is_empty() {
        return list_detailed(archive, format);
    }
    match format {
        Format::Zip => zip::list_detailed_with_password(archive, password.as_bytes()),
        Format::SevenZ => sevenz::list_detailed_with_password(archive, password),
        Format::Rar => rar::list_detailed_with_password(archive, password),
        other => reject_password(other, "listing"),
    }
}

pub fn test_archive_with_password(
    archive: &Path,
    format: Format,
    limits: &Limits,
    password: &str,
) -> Result<TestReport> {
    if password.is_empty() {
        return test_archive(archive, format, limits);
    }
    match format {
        Format::Zip => zip::test_with_password(archive, limits, password.as_bytes()),
        Format::SevenZ => sevenz::test_with_password(archive, limits, password),
        Format::Rar => rar::test_with_password(archive, limits, password),
        other => reject_password(other, "verification"),
    }
}
