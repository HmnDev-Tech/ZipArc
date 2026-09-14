//! ZIP read/write backend.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use crate::backend::collect_sources;
use crate::backend::{PreviewEntry, PreviewListing, TestFailure, TestReport};
use crate::error::{ArchiveError, Result, classify_io};
use crate::io_util::{
    AtomicFile, LimitState, LimitedReader, Limits, check_cancelled, create_dir_all_checked,
    create_output_file, log_warn, progress_reset, reject_symlink_ancestors, safe_join,
    safe_link_target, set_file_mode,
};
use ::zip::read::ZipFile;
use ::zip::result::ZipError;
use ::zip::write::{FileOptions, SimpleFileOptions};
use ::zip::{AesMode, CompressionMethod, ZipArchive, ZipReadOptions, ZipWriter};

/// Supported split naming: classic `base.z01, base.z02, ..., base.zip` and dotted `base.zip.001, base.zip.002, ...`; given any span path, resolves the ordered segment list, or None for a single-file archive.
fn resolve_split_segments(path: &Path) -> Result<Option<Vec<PathBuf>>> {
    let dir: PathBuf = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| PathBuf::from("."));
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_default();
    if let Some(base) = classic_split_base(&name) {
        return resolve_classic(&dir, &base);
    }
    if let Some(base) = dotted_split_base(&name) {
        return resolve_dotted(&dir, &base);
    }
    Ok(None)
}

fn classic_split_base(name: &str) -> Option<String> {
    if let Some(stem) = name.strip_suffix(".zip") {
        if stem.is_empty() {
            return None;
        }
        return Some(stem.to_string());
    }
    match name.rfind(".z") {
        Some(i) => {
            let base = &name[..i];
            let tail = &name[i + 2..];
            if base.is_empty() || tail.is_empty() || !tail.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            Some(base.to_string())
        }
        None => None,
    }
}

fn dotted_split_base(name: &str) -> Option<String> {
    match name.rfind(".zip.") {
        Some(i) => {
            let tail = &name[i + 5..];
            if tail.is_empty() || !tail.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            Some(name[..i + 4].to_string())
        }
        None => None,
    }
}

fn sibling_map(dir: &Path) -> HashMap<String, PathBuf> {
    let mut out = HashMap::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let lower = entry.file_name().to_string_lossy().to_ascii_lowercase();
            out.insert(lower, entry.path());
        }
    }
    out
}

fn resolve_classic(dir: &Path, base: &str) -> Result<Option<Vec<PathBuf>>> {
    let siblings = sibling_map(dir);
    let mut parts: HashMap<u32, PathBuf> = HashMap::new();
    for (lower, full) in &siblings {
        if lower.len() > base.len() && lower.starts_with(base) {
            let rest = &lower[base.len()..];
            if rest.starts_with(".z")
                && rest.len() > 2
                && rest[2..].bytes().all(|b| b.is_ascii_digit())
                && let Ok(v) = rest[2..].parse::<u32>()
            {
                parts.insert(v, full.clone());
            }
        }
    }
    if parts.is_empty() {
        return Ok(None);
    }
    let mut indices: Vec<u32> = parts.keys().copied().collect();
    indices.sort_unstable();
    if indices[0] != 1 {
        return Err(ArchiveError::invalid(format!(
            "missing split segment: {}",
            dir.join(format!("{base}.z01")).display()
        )));
    }
    for w in indices.windows(2) {
        if w[1] != w[0] + 1 {
            return Err(ArchiveError::invalid(format!(
                "missing split segment: {}",
                dir.join(format!("{base}.z{:02}", w[0] + 1)).display()
            )));
        }
    }
    let mut out: Vec<PathBuf> = indices.iter().map(|i| parts[i].clone()).collect();
    match siblings.get(&format!("{base}.zip")) {
        Some(last) => out.push(last.clone()),
        None => {
            return Err(ArchiveError::invalid(format!(
                "missing split segment: {}",
                dir.join(format!("{base}.zip")).display()
            )));
        }
    }
    Ok(Some(out))
}

fn resolve_dotted(dir: &Path, base: &str) -> Result<Option<Vec<PathBuf>>> {
    let siblings = sibling_map(dir);
    let mut parts: HashMap<u32, PathBuf> = HashMap::new();
    for (lower, full) in &siblings {
        if lower.len() > base.len() && lower.starts_with(base) {
            let rest = &lower[base.len()..];
            if rest.starts_with('.')
                && rest.len() > 1
                && rest[1..].bytes().all(|b| b.is_ascii_digit())
                && let Ok(v) = rest[1..].parse::<u32>()
            {
                parts.insert(v, full.clone());
            }
        }
    }
    if parts.is_empty() {
        return Ok(None);
    }
    let mut indices: Vec<u32> = parts.keys().copied().collect();
    indices.sort_unstable();
    if indices[0] != 1 {
        return Err(ArchiveError::invalid(format!(
            "missing split segment: {}",
            dir.join(format!("{base}.001")).display()
        )));
    }
    for w in indices.windows(2) {
        if w[1] != w[0] + 1 {
            return Err(ArchiveError::invalid(format!(
                "missing split segment: {}",
                dir.join(format!("{base}.{:03}", w[0] + 1)).display()
            )));
        }
    }
    Ok(Some(indices.iter().map(|i| parts[i].clone()).collect()))
}

struct ConcatReader {
    files: Vec<File>,
    sizes: Vec<u64>,
    pos: u64,
    total: u64,
}

impl ConcatReader {
    fn open(paths: &[PathBuf]) -> io::Result<Self> {
        let mut files = Vec::with_capacity(paths.len());
        let mut sizes = Vec::with_capacity(paths.len());
        let mut total = 0u64;
        for p in paths {
            let f = File::open(p)?;
            let len = f.metadata()?.len();
            total = total.saturating_add(len);
            files.push(f);
            sizes.push(len);
        }
        Ok(Self {
            files,
            sizes,
            pos: 0,
            total,
        })
    }

    fn locate(&self) -> (usize, u64) {
        let mut base = 0u64;
        for (i, s) in self.sizes.iter().enumerate() {
            if self.pos < base.saturating_add(*s) {
                return (i, self.pos.saturating_sub(base));
            }
            base = base.saturating_add(*s);
        }
        let last = self.files.len().saturating_sub(1);
        (last, *self.sizes.last().unwrap_or(&0))
    }
}

impl Read for ConcatReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() || self.pos >= self.total || self.files.is_empty() {
            return Ok(0);
        }
        let mut done = 0usize;
        while done < buf.len() && self.pos < self.total {
            let (idx, off) = self.locate();
            let end = self.sizes[idx];
            if off >= end {
                break;
            }
            self.files[idx].seek(SeekFrom::Start(off))?;
            let room = buf.len() - done;
            let cap = (end - off).min(room as u64) as usize;
            let n = self.files[idx].read(&mut buf[done..done + cap])?;
            if n == 0 {
                break;
            }
            done += n;
            self.pos = self.pos.saturating_add(n as u64);
        }
        Ok(done)
    }
}

impl Seek for ConcatReader {
    fn seek(&mut self, style: SeekFrom) -> io::Result<u64> {
        let target: i128 = match style {
            SeekFrom::Start(n) => n as i128,
            SeekFrom::End(n) => self.total as i128 + n as i128,
            SeekFrom::Current(n) => self.pos as i128 + n as i128,
        };
        if target < 0 || target > self.total as i128 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "seek outside split archive",
            ));
        }
        self.pos = target as u64;
        Ok(self.pos)
    }
}

fn open_single(archive: &Path) -> Result<ZipArchive<BufReader<File>>> {
    let file = File::open(archive)?;
    ZipArchive::new(BufReader::new(file)).map_err(ArchiveError::backend)
}

fn open_split(segments: &[PathBuf]) -> Result<ZipArchive<BufReader<ConcatReader>>> {
    let reader = ConcatReader::open(segments)?;
    ZipArchive::new(BufReader::new(reader)).map_err(ArchiveError::backend)
}

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

fn zip_unpacked_total<R: Read + Seek>(zip: &mut ZipArchive<R>) -> u64 {
    let mut total = 0u64;
    for i in 0..zip.len() {
        if let Ok(entry) = zip.by_index_raw(i) {
            total = total.saturating_add(entry.size());
        }
    }
    total
}

fn extract_impl(
    archive: &Path,
    dest: &Path,
    limits: &Limits,
    password: Option<&[u8]>,
) -> Result<()> {
    std::fs::create_dir_all(dest)?;
    let dest_root = std::fs::canonicalize(dest)?;
    match resolve_split_segments(archive)? {
        None => {
            let mut zip = open_single(archive)?;
            progress_reset(zip_unpacked_total(&mut zip));
            extract_entries(&mut zip, &dest_root, limits, password)
        }
        Some(segments) => {
            let mut zip = open_split(&segments)?;
            progress_reset(zip_unpacked_total(&mut zip));
            extract_entries(&mut zip, &dest_root, limits, password)
        }
    }
}

fn extract_entries<R: Read + Seek>(
    zip: &mut ZipArchive<R>,
    dest_root: &Path,
    limits: &Limits,
    password: Option<&[u8]>,
) -> Result<()> {
    let mut state = LimitState::new(limits);
    let mut warnings: Vec<String> = Vec::new();

    for i in 0..zip.len() {
        check_cancelled()?;
        let mut entry = match open_entry(zip, i, password) {
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
            match safe_join(dest_root, &name)
                .and_then(|out| create_dir_all_checked(dest_root, &out))
            {
                Ok(()) => {}
                Err(e) if e.is_fatal() => return Err(e),
                Err(e) => warnings.push(format!("{name}: {e}")),
            }
            continue;
        }

        if entry.is_symlink() {
            match extract_symlink(&mut entry, &name, dest_root) {
                Ok(()) => {}
                Err(e) if e.is_fatal() => return Err(e),
                Err(e) => warnings.push(format!("{name}: {e}")),
            }
            continue;
        }

        let result = (|| -> Result<()> {
            let out = safe_join(dest_root, &name)?;
            let parent = out
                .parent()
                .ok_or_else(|| ArchiveError::invalid("entry has no parent"))?;
            create_dir_all_checked(dest_root, parent)?;
            reject_symlink_ancestors(dest_root, &out)?;
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

fn open_entry<'a, R: Read + Seek>(
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
    match resolve_split_segments(archive)? {
        None => {
            let mut zip = open_single(archive)?;
            detailed_entries(&mut zip)
        }
        Some(segments) => {
            let mut zip = open_split(&segments)?;
            detailed_entries(&mut zip)
        }
    }
}

fn detailed_entries<R: Read + Seek>(zip: &mut ZipArchive<R>) -> Result<PreviewListing> {
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
    match resolve_split_segments(archive)? {
        None => {
            let mut zip = open_single(archive)?;
            list_entries(&mut zip)
        }
        Some(segments) => {
            let mut zip = open_split(&segments)?;
            list_entries(&mut zip)
        }
    }
}

fn list_entries<R: Read + Seek>(zip: &mut ZipArchive<R>) -> Result<Vec<String>> {
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
    progress_reset(0);
    match resolve_split_segments(archive)? {
        None => {
            let mut zip = open_single(archive)?;
            test_entries(&mut zip, limits, password)
        }
        Some(segments) => {
            let mut zip = open_split(&segments)?;
            test_entries(&mut zip, limits, password)
        }
    }
}

fn test_entries<R: Read + Seek>(
    zip: &mut ZipArchive<R>,
    limits: &Limits,
    password: Option<&[u8]>,
) -> Result<TestReport> {
    let total = zip.len();
    let mut state = LimitState::new(limits);
    let mut failures: Vec<TestFailure> = Vec::new();
    let mut total_size: u64 = 0;

    for i in 0..total {
        check_cancelled()?;
        let mut entry = match open_entry(zip, i, password) {
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
