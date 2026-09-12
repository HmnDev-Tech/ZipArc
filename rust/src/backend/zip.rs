//! ZIP read/write backend.

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use crate::backend::collect_sources;
use crate::error::{ArchiveError, Result, classify_io};
use crate::io_util::{
    AtomicFile, LimitState, LimitedReader, Limits, create_dir_all_checked, create_output_file,
    log_warn, reject_symlink_ancestors, safe_join, safe_link_target, set_file_mode,
};
use ::zip::write::SimpleFileOptions;
use ::zip::{CompressionMethod, ZipArchive, ZipWriter};

fn file_options() -> SimpleFileOptions {
    SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .unix_permissions(0o644)
        .large_file(true)
}

fn dir_options() -> SimpleFileOptions {
    SimpleFileOptions::default()
        .compression_method(CompressionMethod::Stored)
        .unix_permissions(0o755)
}

/// Compress `sources` into a ZIP file at `dest` (written atomically).
pub fn compress(sources: &[PathBuf], dest: &Path, limits: &Limits) -> Result<()> {
    let af = AtomicFile::new(dest)?;
    let entries = collect_sources(sources, &[dest, af.path()], limits)?;
    let file = File::options()
        .write(true)
        .create_new(true)
        .open(af.path())?;
    let mut writer = ZipWriter::new(BufWriter::new(file));
    let mut state = LimitState::new(limits);

    for e in &entries {
        if e.is_dir {
            writer
                .add_directory(e.name.clone(), dir_options())
                .map_err(ArchiveError::backend)?;
            continue;
        }
        state.begin_entry(&e.name, Some(e.size))?;
        writer
            .start_file(e.name.clone(), file_options())
            .map_err(ArchiveError::backend)?;
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
    std::fs::create_dir_all(dest)?;
    let dest_root = std::fs::canonicalize(dest)?;
    let file = File::open(archive)?;
    let mut zip = ZipArchive::new(BufReader::new(file)).map_err(ArchiveError::backend)?;
    let mut state = LimitState::new(limits);
    let mut warnings: Vec<String> = Vec::new();

    for i in 0..zip.len() {
        let mut entry = match zip.by_index(i) {
            Ok(e) => e,
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

/// List entry names in a ZIP.
pub fn list(archive: &Path) -> Result<Vec<String>> {
    let file = File::open(archive)?;
    let mut zip = ZipArchive::new(BufReader::new(file)).map_err(ArchiveError::backend)?;
    let mut out = Vec::with_capacity(zip.len());
    for i in 0..zip.len() {
        let entry = zip.by_index(i).map_err(ArchiveError::backend)?;
        out.push(entry.name().to_string());
    }
    Ok(out)
}
