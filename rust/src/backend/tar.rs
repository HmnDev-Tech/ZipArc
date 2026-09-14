//! tar backend, with optional gzip / bzip2 / xz / zstd / lz4 wrapping.

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use bzip2::Compression as BzCompression;
use bzip2::read::MultiBzDecoder;
use bzip2::write::BzEncoder;
use flate2::Compression as GzCompression;
use flate2::read::MultiGzDecoder;
use flate2::write::GzEncoder;
use lz4_flex::frame::{FrameDecoder, FrameEncoder};
use lzma_rust2::{XzOptions, XzReader, XzWriter};
use tar::{Archive, Builder, EntryType, Header};
use zstd::stream::read::Decoder as ZstdDecoder;
use zstd::stream::write::Encoder as ZstdEncoder;

use crate::backend::{
    PreviewEntry, PreviewListing, SourceEntry, TestFailure, TestReport, collect_sources,
};
use crate::error::{ArchiveError, Result, classify_io};
use crate::format::Format;
use crate::io_util::{
    AtomicFile, LimitState, LimitedReader, Limits, check_cancelled, create_dir_all_checked,
    create_output_file, finish_bufwriter, log_warn, progress_reset, reject_symlink_ancestors,
    safe_join, safe_link_target, set_file_mode,
};

fn add_entry<W: Write>(
    builder: &mut Builder<W>,
    entry: &SourceEntry,
    state: &mut LimitState,
) -> Result<()> {
    check_cancelled()?;
    let mut header = Header::new_gnu();
    if entry.is_dir {
        header.set_entry_type(EntryType::Directory);
        header.set_mode(0o755);
        header.set_size(0);
        header.set_mtime(0);
        header.set_cksum();
        builder
            .append_data(&mut header, &entry.name, io::empty())
            .map_err(ArchiveError::backend)?;
        return Ok(());
    }

    header.set_entry_type(EntryType::Regular);
    header.set_mode(0o644);
    header.set_size(entry.size);
    header.set_mtime(0);
    header.set_cksum();

    state.begin_entry(&entry.name, Some(entry.size))?;
    let allowance = state.allowance(Some(entry.size));
    let reader = BufReader::new(File::open(&entry.path)?);
    let mut limited = LimitedReader::new(reader, allowance);
    builder
        .append_data(&mut header, &entry.name, &mut limited)
        .map_err(ArchiveError::backend)?;
    state.finish_entry(&entry.name, Some(entry.size), limited.count())
}

fn flush_buf(writer: BufWriter<File>) -> Result<()> {
    finish_bufwriter(writer).map(|_| ())
}

/// Compress `sources` into a tar (optionally wrapped) at `dest`.
pub fn compress(sources: &[PathBuf], dest: &Path, format: Format, limits: &Limits) -> Result<()> {
    let af = AtomicFile::new(dest)?;
    let entries = collect_sources(sources, &[dest, af.path()], limits)?;
    let mut state = LimitState::new(limits);
    let out = File::options()
        .write(true)
        .create_new(true)
        .open(af.path())?;
    let buf = BufWriter::new(out);

    match format {
        Format::Tar => {
            let mut builder = Builder::new(buf);
            for e in &entries {
                check_cancelled()?;
                add_entry(&mut builder, e, &mut state)?;
            }
            let inner = builder.into_inner().map_err(ArchiveError::backend)?;
            flush_buf(inner)?;
        }
        Format::TarGz => {
            let enc = GzEncoder::new(buf, GzCompression::default());
            let mut builder = Builder::new(enc);
            for e in &entries {
                check_cancelled()?;
                add_entry(&mut builder, e, &mut state)?;
            }
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish()?;
            flush_buf(inner)?;
        }
        Format::TarBz2 => {
            let enc = BzEncoder::new(buf, BzCompression::default());
            let mut builder = Builder::new(enc);
            for e in &entries {
                check_cancelled()?;
                add_entry(&mut builder, e, &mut state)?;
            }
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish()?;
            flush_buf(inner)?;
        }
        Format::TarXz => {
            let enc = XzWriter::new(buf, XzOptions::with_preset(6))?;
            let mut builder = Builder::new(enc);
            for e in &entries {
                check_cancelled()?;
                add_entry(&mut builder, e, &mut state)?;
            }
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish()?;
            flush_buf(inner)?;
        }
        Format::TarZst => {
            let enc = ZstdEncoder::new(buf, 3)?;
            let mut builder = Builder::new(enc);
            for e in &entries {
                check_cancelled()?;
                add_entry(&mut builder, e, &mut state)?;
            }
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish()?;
            flush_buf(inner)?;
        }
        Format::TarLz4 => {
            let enc = FrameEncoder::new(buf);
            let mut builder = Builder::new(enc);
            for e in &entries {
                check_cancelled()?;
                add_entry(&mut builder, e, &mut state)?;
            }
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish().map_err(ArchiveError::backend)?;
            flush_buf(inner)?;
        }
        other => {
            return Err(ArchiveError::Unsupported(format!(
                "{} is not a tar format",
                other.label()
            )));
        }
    }

    af.commit()
}

fn open_tar_reader(archive: &Path, format: Format) -> Result<Box<dyn Read>> {
    let file = File::open(archive)?;
    let buffered = BufReader::new(file);
    let reader: Box<dyn Read> = match format {
        Format::Tar => Box::new(buffered),
        Format::TarGz => Box::new(MultiGzDecoder::new(buffered)),
        Format::TarBz2 => Box::new(MultiBzDecoder::new(buffered)),
        Format::TarXz => Box::new(XzReader::new(buffered, true)),
        Format::TarZst => Box::new(ZstdDecoder::new(buffered)?),
        Format::TarLz4 => Box::new(FrameDecoder::new(buffered)),
        other => {
            return Err(ArchiveError::Unsupported(format!(
                "{} is not a tar format",
                other.label()
            )));
        }
    };
    Ok(reader)
}

fn extract_entry<R: Read>(
    entry: &mut tar::Entry<'_, R>,
    root: &Path,
    state: &mut LimitState,
) -> Result<()> {
    check_cancelled()?;
    let entry_type = entry.header().entry_type();
    let name = entry
        .path()
        .map(|p| p.to_string_lossy().into_owned())
        .map_err(ArchiveError::backend)?;

    if entry_type == EntryType::Directory {
        let out = safe_join(root, &name)?;
        create_dir_all_checked(root, &out)?;
        return Ok(());
    }

    if entry_type == EntryType::Symlink {
        let out = safe_join(root, &name)?;
        let parent = out
            .parent()
            .ok_or_else(|| ArchiveError::invalid("symlink has no parent"))?;
        create_dir_all_checked(root, parent)?;
        reject_symlink_ancestors(root, &out)?;
        let target = entry
            .link_name()
            .map_err(ArchiveError::backend)?
            .ok_or_else(|| ArchiveError::invalid("symlink without target"))?;
        let target = target.to_string_lossy().into_owned();
        safe_link_target(root, &out, &target)?;
        #[cfg(unix)]
        std::os::unix::fs::symlink(&target, &out)?;
        #[cfg(not(unix))]
        log_warn(format!("skipping symlink {} -> {}", out.display(), target));
        return Ok(());
    }

    if entry_type == EntryType::Link {
        let out = safe_join(root, &name)?;
        let parent = out
            .parent()
            .ok_or_else(|| ArchiveError::invalid("hardlink has no parent"))?;
        create_dir_all_checked(root, parent)?;
        reject_symlink_ancestors(root, &out)?;
        let target = entry
            .link_name()
            .map_err(ArchiveError::backend)?
            .ok_or_else(|| ArchiveError::invalid("hardlink without target"))?;
        let target = safe_join(root, &target.to_string_lossy())?;
        std::fs::hard_link(&target, &out)?;
        return Ok(());
    }

    if !entry_type.is_file() {
        return Err(ArchiveError::invalid(format!(
            "unsupported tar entry type {entry_type:?} for {name}"
        )));
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
    let mut limited = LimitedReader::new(entry, allowance);
    io::copy(&mut limited, &mut writer).map_err(classify_io)?;
    writer.flush()?;
    drop(writer);
    set_file_mode(&out)?;
    state.finish_entry(&name, Some(declared), limited.count())
}

/// Extract a tar (or wrapped tar) into `dest`.
pub fn extract(archive: &Path, dest: &Path, format: Format, limits: &Limits) -> Result<()> {
    progress_reset(0);
    std::fs::create_dir_all(dest)?;
    let dest_root = std::fs::canonicalize(dest)?;
    let reader = open_tar_reader(archive, format)?;
    let mut tar = Archive::new(reader);
    let entries = tar.entries().map_err(ArchiveError::backend)?;
    let mut state = LimitState::new(limits);
    let mut warnings: Vec<String> = Vec::new();

    for entry in entries {
        check_cancelled()?;
        let mut entry = match entry {
            Ok(e) => e,
            Err(e) => {
                warnings.push(format!("tar entry: {e}"));
                continue;
            }
        };
        let result = extract_entry(&mut entry, &dest_root, &mut state);
        match result {
            Ok(()) => {}
            Err(e) if e.is_fatal() => return Err(e),
            Err(e) => warnings.push(format!("{e}")),
        }
    }

    for w in &warnings {
        log_warn(format!("tar entry skipped: {w}"));
    }
    Ok(())
}

/// List entry names inside a tar (or wrapped tar).
pub fn list(archive: &Path, format: Format) -> Result<Vec<String>> {
    let reader = open_tar_reader(archive, format)?;
    let mut tar = Archive::new(reader);
    let mut out = Vec::new();
    for entry in tar.entries().map_err(ArchiveError::backend)? {
        let entry = entry.map_err(ArchiveError::backend)?;
        let name = entry
            .path()
            .map(|p| p.to_string_lossy().into_owned())
            .map_err(ArchiveError::backend)?;
        out.push(name);
    }
    Ok(out)
}

pub fn list_detailed(archive: &Path, format: Format) -> Result<PreviewListing> {
    let reader = open_tar_reader(archive, format)?;
    let mut tar = Archive::new(reader);
    let mut out = Vec::new();
    for entry in tar.entries().map_err(ArchiveError::backend)? {
        check_cancelled()?;
        let entry = entry.map_err(ArchiveError::backend)?;
        let is_dir = entry.header().entry_type() == EntryType::Directory;
        let name = entry
            .path()
            .map(|p| p.to_string_lossy().into_owned())
            .map_err(ArchiveError::backend)?;
        let size = if is_dir { 0 } else { entry.size() };
        out.push(PreviewEntry {
            name,
            size,
            is_dir,
            encrypted: false,
        });
    }
    Ok(PreviewListing::new(out))
}

fn test_link_target(entry: &tar::Entry<'_, impl Read>, name: &str) -> Result<()> {
    let target = entry
        .link_name()
        .map_err(ArchiveError::backend)?
        .ok_or_else(|| ArchiveError::invalid(format!("link '{name}' without target")))?;
    let target = target.to_string_lossy().into_owned();
    if target.is_empty() {
        return Err(ArchiveError::invalid(format!(
            "link '{name}' has empty target"
        )));
    }
    if Path::new(&target).is_absolute() {
        return Err(ArchiveError::security(format!(
            "link '{name}' has absolute target"
        )));
    }
    Ok(())
}

pub fn test(archive: &Path, format: Format, limits: &Limits) -> Result<TestReport> {
    progress_reset(0);
    let reader = open_tar_reader(archive, format)?;
    let mut tar = Archive::new(reader);
    let entries = tar.entries().map_err(ArchiveError::backend)?;
    let mut state = LimitState::new(limits);
    let mut failures: Vec<TestFailure> = Vec::new();
    let mut total_size: u64 = 0;
    let mut count: usize = 0;

    for entry in entries {
        check_cancelled()?;
        let mut entry = match entry {
            Ok(e) => e,
            Err(e) => {
                failures.push(TestFailure {
                    name: "tar entry".to_string(),
                    reason: e.to_string(),
                });
                continue;
            }
        };
        let entry_type = entry.header().entry_type();
        let name = match entry.path().map(|p| p.to_string_lossy().into_owned()) {
            Ok(n) => n,
            Err(e) => {
                failures.push(TestFailure {
                    name: "tar entry".to_string(),
                    reason: e.to_string(),
                });
                continue;
            }
        };
        count += 1;
        if entry_type == EntryType::Directory {
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
        if entry_type == EntryType::Symlink || entry_type == EntryType::Link {
            match test_link_target(&entry, &name)
                .and_then(|_| crate::io_util::sanitize_entry_name(&name).map(|_| ()))
            {
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
        if !entry_type.is_file() {
            failures.push(TestFailure {
                name,
                reason: format!("unsupported tar entry type {entry_type:?}"),
            });
            continue;
        }
        if crate::io_util::sanitize_entry_name(&name).is_err() {
            failures.push(TestFailure {
                name,
                reason: "unsafe entry name".to_string(),
            });
            let mut sink = io::sink();
            let _ = io::copy(&mut entry, &mut sink);
            continue;
        }
        let declared = entry.size();
        match state.begin_entry(&name, Some(declared)) {
            Ok(()) => {}
            Err(e) if e.is_fatal() => return Err(e),
            Err(e) => {
                failures.push(TestFailure {
                    name,
                    reason: e.to_string(),
                });
                let mut sink = io::sink();
                let _ = io::copy(&mut entry, &mut sink);
                continue;
            }
        }
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
        entries: count,
        total_size,
        failures,
        password_required: false,
    })
}
