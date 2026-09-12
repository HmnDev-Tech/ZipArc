//! Backend dispatch plus the shared source-tree walker.

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
use crate::io_util::{Limits, log_warn};

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
    Ok(out)
}

/// Compress `sources` into `dest` using the given format.
pub fn compress(sources: &[PathBuf], dest: &Path, format: Format, limits: &Limits) -> Result<()> {
    match format {
        Format::Zip => zip::compress(sources, dest, limits),
        Format::SevenZ => sevenz::compress(sources, dest, limits),
        f if f.is_tar() => tar::compress(sources, dest, f, limits),
        f if f.is_single_stream() => single::compress(sources, dest, f, limits),
        Format::Rar => Err(ArchiveError::Unsupported(
            "RAR compression is not supported".to_string(),
        )),
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
        Format::Rar => Err(ArchiveError::Unsupported(
            "RAR extraction is not supported by this engine".to_string(),
        )),
        other => Err(ArchiveError::Unsupported(format!(
            "extraction of {} is not supported",
            other.label()
        ))),
    }
}

/// List entry names inside `archive`.
pub fn list(archive: &Path, format: Format) -> Result<Vec<String>> {
    match format {
        Format::Zip => zip::list(archive),
        Format::SevenZ => sevenz::list(archive),
        f if f.is_tar() => tar::list(archive, f),
        f if f.is_single_stream() => single::list(archive, f),
        Format::Rar => Err(ArchiveError::Unsupported(
            "RAR listing is not supported by this engine".to_string(),
        )),
        other => Err(ArchiveError::Unsupported(format!(
            "listing of {} is not supported",
            other.label()
        ))),
    }
}
