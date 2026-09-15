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
    create_output_file, finish_bufwriter, log_warn, progress_add, progress_reset,
    reject_symlink_ancestors, safe_join, safe_link_target, sanitize_entry_name, set_file_mode,
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

fn trim_tar_name(value: &str) -> String {
    value.trim_end_matches('/').to_string()
}

fn tar_matches(entry: &str, target: &str) -> bool {
    let e = entry.trim_end_matches('/');
    let t = target.trim_end_matches('/');
    if t.is_empty() {
        return false;
    }
    e == t || e.starts_with(&format!("{t}/"))
}

fn normalize_tar_targets(names: &[String]) -> Result<Vec<String>> {
    let mut out = Vec::with_capacity(names.len());
    for n in names {
        let s = sanitize_entry_name(n)?;
        out.push(trim_tar_name(&s));
    }
    Ok(out)
}

fn normalize_tar_dest(dest_dir: &str) -> Result<String> {
    let t = dest_dir.trim().replace('\\', "/");
    let t = t.trim_matches('/').to_string();
    if t.is_empty() {
        return Ok(String::new());
    }
    Ok(trim_tar_name(&sanitize_entry_name(&t)?))
}

struct TarAddSource {
    name: String,
    path: PathBuf,
    is_dir: bool,
}

fn collect_tar_add_sources(sources: &[PathBuf], dest_dir: &str) -> Result<Vec<TarAddSource>> {
    let prefix = normalize_tar_dest(dest_dir)?;
    let mut out: Vec<TarAddSource> = Vec::new();
    for src in sources {
        check_cancelled()?;
        let md = std::fs::symlink_metadata(src)?;
        if md.file_type().is_symlink() {
            log_warn(format!("skipping symlink source: {}", src.display()));
            continue;
        }
        if md.is_dir() {
            let base = src.parent().unwrap_or_else(|| Path::new(""));
            for entry in walkdir::WalkDir::new(src)
                .follow_links(false)
                .sort_by_file_name()
            {
                check_cancelled()?;
                let entry = entry.map_err(|e| {
                    let io_err = e
                        .into_io_error()
                        .unwrap_or_else(|| io::Error::other("walk error"));
                    ArchiveError::Io(io_err)
                })?;
                let path = entry.path();
                if entry.file_type().is_symlink() {
                    log_warn(format!("skipping symlink: {}", path.display()));
                    continue;
                }
                let rel = path.strip_prefix(base).map_err(|e| {
                    ArchiveError::invalid(format!("cannot relativise {}: {e}", path.display()))
                })?;
                let rel_name = trim_tar_name(&sanitize_entry_name(&rel.to_string_lossy())?);
                let full = if prefix.is_empty() {
                    rel_name
                } else {
                    format!("{prefix}/{rel_name}")
                };
                out.push(TarAddSource {
                    name: full,
                    path: path.to_path_buf(),
                    is_dir: entry.file_type().is_dir(),
                });
            }
        } else if md.is_file() {
            let file_name = src
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .ok_or_else(|| ArchiveError::invalid("source has no file name"))?;
            let file_name = trim_tar_name(&sanitize_entry_name(&file_name)?);
            let full = if prefix.is_empty() {
                file_name
            } else {
                format!("{prefix}/{file_name}")
            };
            out.push(TarAddSource {
                name: full,
                path: src.clone(),
                is_dir: false,
            });
        } else {
            log_warn(format!("skipping non-file source: {}", src.display()));
        }
    }
    if out.is_empty() {
        return Err(ArchiveError::invalid("no files to add"));
    }
    Ok(out)
}

struct StagedTar {
    name: String,
    header: Header,
    data: Vec<u8>,
}

fn read_all_tar(archive: &Path, format: Format) -> Result<Vec<StagedTar>> {
    let reader = open_tar_reader(archive, format)?;
    let mut tar = Archive::new(reader);
    let mut out = Vec::new();
    let limits = Limits::default();
    for entry in tar.entries().map_err(ArchiveError::backend)? {
        check_cancelled()?;
        let mut entry = entry.map_err(ArchiveError::backend)?;
        let name = entry
            .path()
            .map(|p| p.to_string_lossy().into_owned())
            .map_err(ArchiveError::backend)?;
        let header = entry.header().clone();
        let et = header.entry_type();
        let mut data = Vec::new();
        if et.is_file() {
            let declared = entry.size();
            if declared > limits.max_entry_size {
                return Err(ArchiveError::limit(format!(
                    "entry '{name}' exceeds per-entry limit"
                )));
            }
            let allowance = limits.max_entry_size.min(declared.saturating_add(1));
            let mut limited = LimitedReader::new(&mut entry, allowance);
            limited.read_to_end(&mut data).map_err(classify_io)?;
        }
        out.push(StagedTar { name, header, data });
    }
    Ok(out)
}

fn write_staged_tar(
    staged: &[StagedTar],
    additions: &[TarAddSource],
    dest: &Path,
    format: Format,
) -> Result<()> {
    let af = AtomicFile::new(dest)?;
    let out = File::options()
        .write(true)
        .create_new(true)
        .open(af.path())?;
    let buf = BufWriter::new(out);
    match format {
        Format::Tar => {
            let mut builder = Builder::new(buf);
            write_staged_inner(&mut builder, staged, additions)?;
            let inner = builder.into_inner().map_err(ArchiveError::backend)?;
            finish_bufwriter(inner)?;
        }
        Format::TarGz => {
            let enc = GzEncoder::new(buf, GzCompression::default());
            let mut builder = Builder::new(enc);
            write_staged_inner(&mut builder, staged, additions)?;
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish()?;
            finish_bufwriter(inner)?;
        }
        Format::TarBz2 => {
            let enc = BzEncoder::new(buf, BzCompression::default());
            let mut builder = Builder::new(enc);
            write_staged_inner(&mut builder, staged, additions)?;
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish()?;
            finish_bufwriter(inner)?;
        }
        Format::TarXz => {
            let enc = XzWriter::new(buf, XzOptions::with_preset(6))?;
            let mut builder = Builder::new(enc);
            write_staged_inner(&mut builder, staged, additions)?;
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish()?;
            finish_bufwriter(inner)?;
        }
        Format::TarZst => {
            let enc = ZstdEncoder::new(buf, 3)?;
            let mut builder = Builder::new(enc);
            write_staged_inner(&mut builder, staged, additions)?;
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish()?;
            finish_bufwriter(inner)?;
        }
        Format::TarLz4 => {
            let enc = FrameEncoder::new(buf);
            let mut builder = Builder::new(enc);
            write_staged_inner(&mut builder, staged, additions)?;
            let enc = builder.into_inner().map_err(ArchiveError::backend)?;
            let inner = enc.finish().map_err(ArchiveError::backend)?;
            finish_bufwriter(inner)?;
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

fn write_staged_inner<W: Write>(
    builder: &mut Builder<W>,
    staged: &[StagedTar],
    additions: &[TarAddSource],
) -> Result<()> {
    for s in staged {
        check_cancelled()?;
        let mut header = s.header.clone();
        header
            .set_path(s.name.clone())
            .map_err(ArchiveError::backend)?;
        header.set_cksum();
        builder
            .append_data(&mut header, s.name.clone(), &s.data[..])
            .map_err(ArchiveError::backend)?;
        progress_add(1);
    }
    let mut ordered: Vec<&TarAddSource> = additions.iter().collect();
    ordered.sort_by(|a, b| a.name.cmp(&b.name));
    for a in ordered {
        check_cancelled()?;
        if a.is_dir {
            let mut header = Header::new_gnu();
            header.set_entry_type(EntryType::Directory);
            header.set_mode(0o755);
            header.set_size(0);
            header.set_mtime(0);
            header.set_cksum();
            builder
                .append_data(&mut header, a.name.clone(), io::empty())
                .map_err(ArchiveError::backend)?;
        } else {
            let size = std::fs::metadata(&a.path)?.len();
            let mut header = Header::new_gnu();
            header.set_entry_type(EntryType::Regular);
            header.set_mode(0o644);
            header.set_size(size);
            header.set_mtime(0);
            header.set_cksum();
            let file = File::open(&a.path)?;
            let mut reader = BufReader::new(file);
            builder
                .append_data(&mut header, a.name.clone(), &mut reader)
                .map_err(ArchiveError::backend)?;
        }
        progress_add(1);
    }
    Ok(())
}

pub fn delete_entries(archive: &Path, format: Format, names: &[String]) -> Result<()> {
    let targets = normalize_tar_targets(names)?;
    if targets.is_empty() {
        return Ok(());
    }
    let all = read_all_tar(archive, format)?;
    progress_reset(all.len() as u64);
    let kept: Vec<StagedTar> = all
        .into_iter()
        .filter(|s| !targets.iter().any(|t| tar_matches(&s.name, t)))
        .map(|mut s| {
            s.name = sanitize_entry_name(&s.name).unwrap_or(s.name);
            s
        })
        .collect();
    write_staged_tar(&kept, &[], archive, format)
}

pub fn rename_entry(archive: &Path, format: Format, from: &str, to: &str) -> Result<()> {
    let from_s = trim_tar_name(&sanitize_entry_name(from)?);
    let to_s = trim_tar_name(&sanitize_entry_name(to)?);
    if from_s.is_empty() || to_s.is_empty() {
        return Err(ArchiveError::invalid("empty entry name"));
    }
    if from_s == to_s {
        return Ok(());
    }
    if to_s.starts_with(&format!("{from_s}/")) {
        return Err(ArchiveError::invalid(format!(
            "cannot rename '{from_s}' onto '{to_s}'"
        )));
    }
    let all = read_all_tar(archive, format)?;
    let mut found = false;
    for s in &all {
        if tar_matches(&s.name, &from_s) {
            found = true;
            break;
        }
    }
    if !found {
        return Err(ArchiveError::invalid(format!("entry '{from_s}' not found")));
    }
    let outside: std::collections::HashSet<String> = all
        .iter()
        .filter(|s| !tar_matches(&s.name, &from_s))
        .map(|s| trim_tar_name(&s.name))
        .collect();
    for s in &all {
        if tar_matches(&s.name, &from_s) {
            let trimmed = trim_tar_name(&s.name);
            let rest = trimmed[from_s.len()..].to_string();
            let candidate = format!("{to_s}{rest}");
            if outside.contains(&candidate) {
                return Err(ArchiveError::invalid(format!(
                    "entry '{candidate}' already exists"
                )));
            }
        }
    }
    if outside.contains(&to_s) {
        return Err(ArchiveError::invalid(format!(
            "entry '{to_s}' already exists"
        )));
    }
    progress_reset(all.len() as u64);
    let mut renamed: Vec<StagedTar> = Vec::with_capacity(all.len());
    for s in all {
        check_cancelled()?;
        let new_name = if tar_matches(&s.name, &from_s) {
            let trimmed = trim_tar_name(&s.name);
            let rest = trimmed[from_s.len()..].to_string();
            format!("{to_s}{rest}")
        } else {
            trim_tar_name(&s.name)
        };
        renamed.push(StagedTar {
            name: new_name,
            header: s.header,
            data: s.data,
        });
    }
    write_staged_tar(&renamed, &[], archive, format)
}

pub fn add_files(
    archive: &Path,
    format: Format,
    sources: &[PathBuf],
    dest_dir: &str,
) -> Result<()> {
    let additions = collect_tar_add_sources(sources, dest_dir)?;
    let all = read_all_tar(archive, format)?;
    let add_set: std::collections::HashSet<String> =
        additions.iter().map(|a| trim_tar_name(&a.name)).collect();
    progress_reset((all.len() + additions.len()) as u64);
    let kept: Vec<StagedTar> = all
        .into_iter()
        .filter(|s| !add_set.contains(&trim_tar_name(&s.name)))
        .map(|s| StagedTar {
            name: trim_tar_name(&s.name),
            header: s.header,
            data: s.data,
        })
        .collect();
    write_staged_tar(&kept, &additions, archive, format)
}

#[cfg(test)]
mod edit_tests {
    use super::*;
    use crate::backend::list_detailed;
    use tempfile::tempdir;

    fn make_tar(dir: &Path) -> PathBuf {
        let src = dir.join("src");
        std::fs::create_dir_all(src.join("mydir/sub")).unwrap();
        std::fs::write(src.join("a.txt"), b"aaa").unwrap();
        std::fs::write(src.join("mydir/b.txt"), b"bbb").unwrap();
        std::fs::write(src.join("mydir/sub/c.txt"), b"ccc").unwrap();
        let dest = dir.join("t.tar");
        crate::backend::compress(
            std::slice::from_ref(&src),
            &dest,
            Format::Tar,
            &Limits::default(),
        )
        .unwrap();
        dest
    }

    fn has_suffix(archive: &Path, suffix: &str) -> bool {
        let listing = list_detailed(archive, Format::Tar).unwrap();
        listing.entries.iter().any(|e| e.name.ends_with(suffix))
    }

    #[test]
    fn tar_delete_file() {
        let dir = tempdir().unwrap();
        let archive = make_tar(dir.path());
        crate::backend::delete_entries(&archive, Format::Tar, &["src/a.txt".to_string()]).unwrap();
        assert!(!has_suffix(&archive, "a.txt"));
        assert!(has_suffix(&archive, "b.txt"));
    }

    #[test]
    fn tar_delete_dir() {
        let dir = tempdir().unwrap();
        let archive = make_tar(dir.path());
        crate::backend::delete_entries(&archive, Format::Tar, &["src/mydir".to_string()]).unwrap();
        let listing = list_detailed(&archive, Format::Tar).unwrap();
        assert!(listing.entries.iter().all(|e| !e.name.contains("mydir")));
    }

    #[test]
    fn tar_rename_file() {
        let dir = tempdir().unwrap();
        let archive = make_tar(dir.path());
        crate::backend::rename_entry(&archive, Format::Tar, "src/a.txt", "src/z.txt").unwrap();
        assert!(!has_suffix(&archive, "a.txt"));
        assert!(has_suffix(&archive, "z.txt"));
    }

    #[test]
    fn tar_rename_dir() {
        let dir = tempdir().unwrap();
        let archive = make_tar(dir.path());
        crate::backend::rename_entry(&archive, Format::Tar, "src/mydir", "src/renamed").unwrap();
        assert!(has_suffix(&archive, "renamed/b.txt"));
        assert!(
            !list_detailed(&archive, Format::Tar)
                .unwrap()
                .entries
                .iter()
                .any(|e| e.name.contains("mydir"))
        );
    }

    #[test]
    fn tar_add_files() {
        let dir = tempdir().unwrap();
        let archive = make_tar(dir.path());
        let extra = dir.path().join("extra.txt");
        std::fs::write(&extra, b"new").unwrap();
        crate::backend::add_files(&archive, Format::Tar, &[extra], "added").unwrap();
        assert!(has_suffix(&archive, "added/extra.txt"));
        assert!(has_suffix(&archive, "a.txt"));
    }

    #[test]
    fn tar_password_unsupported() {
        let dir = tempdir().unwrap();
        let archive = make_tar(dir.path());
        let r = crate::backend::delete_entries_with_password(
            &archive,
            Format::Tar,
            &["a".to_string()],
            b"pw",
        );
        assert!(matches!(r, Err(ArchiveError::Unsupported(_))));
    }
}
