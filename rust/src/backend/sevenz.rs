//! 7-Zip backend (pure-Rust `sevenz-rust2`).

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use sevenz_rust2::{ArchiveEntry, ArchiveReader, ArchiveWriter, Password};

use crate::backend::collect_sources;
use crate::error::{ArchiveError, LIMIT_MARKER, Result, classify_io};
use crate::io_util::{
    AtomicFile, LimitState, LimitedReader, Limits, create_dir_all_checked, create_output_file,
    log_warn, reject_symlink_ancestors, safe_join, set_file_mode,
};

fn map_err(e: sevenz_rust2::Error) -> ArchiveError {
    if let sevenz_rust2::Error::Io(io_err, _) = &e
        && io_err.to_string().contains(LIMIT_MARKER)
    {
        return ArchiveError::limit(e);
    }
    ArchiveError::backend(e)
}

/// Compress `sources` into a 7z archive at `dest` (atomic).
pub fn compress(sources: &[PathBuf], dest: &Path, limits: &Limits) -> Result<()> {
    let af = AtomicFile::new(dest)?;
    let entries = collect_sources(sources, &[dest, af.path()], limits)?;
    let mut state = LimitState::new(limits);
    let mut writer = ArchiveWriter::create(af.path()).map_err(map_err)?;

    for e in &entries {
        if e.is_dir {
            let entry = ArchiveEntry::new_directory(&e.name);
            writer
                .push_archive_entry::<io::Empty>(entry, None)
                .map_err(map_err)?;
            continue;
        }
        state.begin_entry(&e.name, Some(e.size))?;
        let entry = ArchiveEntry::from_path(&e.path, e.name.clone());
        let allowance = state.allowance(Some(e.size));
        let reader = LimitedReader::new(BufReader::new(File::open(&e.path)?), allowance);
        writer
            .push_archive_entry(entry, Some(reader))
            .map_err(map_err)?;
        state.finish_entry(&e.name, Some(e.size), e.size)?;
    }

    writer.finish()?;
    af.commit()
}

fn extract_entry(
    entry: &ArchiveEntry,
    data: &mut dyn Read,
    root: &Path,
    state: &mut LimitState,
) -> Result<()> {
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
    std::fs::create_dir_all(dest)?;
    let dest_root = std::fs::canonicalize(dest)?;
    let mut reader = ArchiveReader::open(archive, Password::empty()).map_err(map_err)?;
    let mut state = LimitState::new(limits);
    let mut warnings: Vec<String> = Vec::new();
    let mut fatal: Option<ArchiveError> = None;

    let result = reader.for_each_entries(|entry, data| {
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
                // Keep a solid stream aligned by draining the bounded entry.
                let _ = io::copy(&mut *data, &mut io::sink());
                Ok(true)
            }
        }
    });

    if let Some(e) = fatal {
        return Err(e);
    }
    result.map_err(map_err)?;

    for w in &warnings {
        log_warn(format!("7z entry skipped: {w}"));
    }
    Ok(())
}

/// List entry names inside a 7z archive.
pub fn list(archive: &Path) -> Result<Vec<String>> {
    let reader = ArchiveReader::open(archive, Password::empty()).map_err(map_err)?;
    Ok(reader
        .archive()
        .files
        .iter()
        .map(|f| f.name().to_string())
        .collect())
}
