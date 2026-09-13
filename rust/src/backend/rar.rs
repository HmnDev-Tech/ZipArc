//! RAR read-only backend built on the `rars` crate.
//!
//! Only listing, verification and extraction are implemented. Archive
//! creation paths intentionally stay on the `Unsupported` error so the
//! engine never packs RAR files.

use std::cell::RefCell;
use std::collections::{HashMap, VecDeque};
use std::io::{self, BufWriter, Write};
use std::path::Path;
use std::rc::Rc;

use rars::{ArchiveReadOptions, ArchiveReader, AttrSource, ExtractedEntryMeta};

use crate::backend::{PreviewEntry, PreviewListing, TestFailure, TestReport};
use crate::error::{ArchiveError, CANCEL_MARKER, LIMIT_MARKER, Result, classify_io};
use crate::io_util::{
    LimitState, Limits, check_cancelled, create_dir_all_checked, create_output_file, is_cancelled,
    log_warn, reject_symlink_ancestors, safe_join, sanitize_entry_name, set_file_mode,
};

type RarsError = rars::Error;
type RarsResult<T> = rars::Result<T>;

const CANCEL_CHECK_INTERVAL: u64 = 65536;

fn read_options<'a>(password: Option<&'a [u8]>, limits: &Limits) -> ArchiveReadOptions<'a> {
    ArchiveReadOptions::with_optional_password(password)
        .with_rar50_buffered_decode_limit(limits.max_total_size)
}

fn root_cause(e: &RarsError) -> &RarsError {
    match e {
        RarsError::AtEntry { source, .. }
        | RarsError::AtArchiveOffset { source, .. }
        | RarsError::InVolume { source, .. } => root_cause(source),
        other => other,
    }
}

fn map_err(e: RarsError, have_password: bool) -> ArchiveError {
    let msg = e.to_string();
    match root_cause(&e) {
        RarsError::NeedPassword => ArchiveError::password_required("archive requires a password"),
        RarsError::WrongPasswordOrCorruptData if have_password => {
            ArchiveError::wrong_password("incorrect password for archive entry")
        }
        RarsError::WrongPasswordOrCorruptData => {
            ArchiveError::password_required("archive requires a password")
        }
        RarsError::Cancelled => ArchiveError::Cancelled,
        RarsError::MemoryLimitExceeded { .. }
        | RarsError::Rar50BufferedDecodeLimitExceeded { .. } => ArchiveError::limit(msg),
        RarsError::CrcMismatch { .. }
        | RarsError::Crc32Mismatch { .. }
        | RarsError::HashMismatch { .. } => ArchiveError::backend(msg),
        RarsError::UnsupportedVersion(_)
        | RarsError::UnsupportedFeature { .. }
        | RarsError::UnsupportedWriterOption { .. }
        | RarsError::UnsupportedFamilyFeature { .. }
        | RarsError::UnsupportedCompression { .. }
        | RarsError::UnsupportedEncryption { .. } => ArchiveError::Unsupported(msg),
        RarsError::TooShort | RarsError::InvalidHeader(_) | RarsError::UnsupportedSignature => {
            ArchiveError::invalid(msg)
        }
        RarsError::Codec(_)
        | RarsError::Rar3Recovery(_)
        | RarsError::Rar5Recovery(_)
        | RarsError::Rar20Crypto(_)
        | RarsError::Rar30Crypto(_)
        | RarsError::Rar50Crypto(_) => ArchiveError::backend(msg),
        RarsError::Io(inner)
            if inner.message.contains(CANCEL_MARKER) || msg.contains(CANCEL_MARKER) =>
        {
            ArchiveError::Cancelled
        }
        RarsError::Io(inner)
            if inner.message.contains(LIMIT_MARKER) || msg.contains(LIMIT_MARKER) =>
        {
            classify_io(io::Error::other(msg))
        }
        RarsError::Io(inner) => ArchiveError::Io(io::Error::new(inner.kind, inner.message.clone())),
        _ => ArchiveError::backend(msg),
    }
}

fn open_archive(archive: &Path, password: Option<&[u8]>, limits: &Limits) -> Result<rars::Archive> {
    let have_password = password.is_some_and(|p| !p.is_empty());
    let clean = if have_password { password } else { None };
    ArchiveReader::read_path_with_options(archive, read_options(clean, limits))
        .map_err(|e| map_err(e, have_password))
}

fn is_symlink(meta: &ExtractedEntryMeta) -> bool {
    meta.attr_source == AttrSource::Unix && meta.file_attr & 0o170000 == 0o120000
}

#[derive(Default)]
struct Shared {
    abort: Option<ArchiveError>,
    records: Vec<EntryRecord>,
    written: HashMap<usize, u64>,
}

struct EntryRecord {
    name: String,
    declared: Option<u64>,
}

struct BudgetWriter<W: Write> {
    inner: W,
    allowance: u64,
    count: u64,
    since_cancel_check: u64,
    shared: Rc<RefCell<Shared>>,
    index: usize,
}

impl<W: Write> BudgetWriter<W> {
    fn check(&mut self) -> io::Result<()> {
        if let Some(e) = self.shared.borrow().abort.as_ref() {
            return Err(marker_error(e));
        }
        if self.since_cancel_check >= CANCEL_CHECK_INTERVAL {
            self.since_cancel_check = 0;
            if is_cancelled() {
                return Err(io::Error::other(CANCEL_MARKER));
            }
        }
        Ok(())
    }
}

impl<W: Write> Write for BudgetWriter<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.check()?;
        let remaining = self.allowance.saturating_sub(self.count);
        if remaining == 0 && !buf.is_empty() {
            return Err(io::Error::other(format!(
                "{LIMIT_MARKER}: entry exceeds output allowance"
            )));
        }
        let n = self
            .inner
            .write(&buf[..buf.len().min(remaining as usize)])?;
        self.count += n as u64;
        self.since_cancel_check += n as u64;
        let mut shared = self.shared.borrow_mut();
        shared
            .written
            .entry(self.index)
            .and_modify(|v| *v = v.saturating_add(n as u64))
            .or_insert(n as u64);
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

struct Discard {
    shared: Rc<RefCell<Shared>>,
}

impl Write for Discard {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if let Some(e) = self.shared.borrow().abort.as_ref() {
            return Err(marker_error(e));
        }
        if is_cancelled() {
            return Err(io::Error::other(CANCEL_MARKER));
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn marker_error(e: &ArchiveError) -> io::Error {
    match e {
        ArchiveError::Cancelled => io::Error::other(CANCEL_MARKER),
        ArchiveError::LimitExceeded(_) => io::Error::other(format!("{LIMIT_MARKER}: {e}")),
        other => io::Error::other(other.to_string()),
    }
}

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
    extract_impl(archive, dest, limits, Some(password.as_bytes()))
}

fn extract_impl(
    archive: &Path,
    dest: &Path,
    limits: &Limits,
    password: Option<&[u8]>,
) -> Result<()> {
    std::fs::create_dir_all(dest)?;
    let dest_root = std::fs::canonicalize(dest)?;
    let parsed = open_archive(archive, password, limits)?;
    let mut sizes: HashMap<Vec<u8>, VecDeque<u64>> = HashMap::new();
    let mut members: usize = 0;
    let ratio_state = LimitState::new(limits);
    for member in parsed.members() {
        check_cancelled()?;
        members += 1;
        if members > limits.max_entries {
            return Err(ArchiveError::limit(format!(
                "archive has more than {} entries",
                limits.max_entries
            )));
        }
        if member.meta.is_directory {
            continue;
        }
        let Ok(name) = sanitize_entry_name(&member.meta.name_lossy()) else {
            continue;
        };
        ratio_state.check_ratio(&name, member.meta.packed_size, member.meta.unpacked_size)?;
        sizes
            .entry(member.meta.name.clone())
            .or_default()
            .push_back(member.meta.unpacked_size);
    }
    let mut state = LimitState::new(limits);
    let shared = Rc::new(RefCell::new(Shared::default()));
    let mut warnings: Vec<String> = Vec::new();
    let mut counter: usize = 0;

    let result = parsed.extract_to(password.filter(|p| !p.is_empty()), |meta| {
        open_entry(
            meta,
            &dest_root,
            &mut state,
            &shared,
            &mut sizes,
            &mut warnings,
            &mut counter,
        )
    });

    if let Some(fatal) = shared.borrow_mut().abort.take() {
        return Err(fatal);
    }
    match result {
        Ok(()) => {}
        Err(e) => {
            return Err(map_err(e, password.is_some_and(|p| !p.is_empty())));
        }
    }
    let shared_ref = shared.borrow();
    for (index, record) in shared_ref.records.iter().enumerate() {
        let written = shared_ref.written.get(&index).copied().unwrap_or(0);
        if let Err(e) = state.finish_entry(&record.name, record.declared, written) {
            if e.is_fatal() {
                return Err(e);
            }
            warnings.push(format!("{}: {e}", record.name));
        }
    }
    for w in &warnings {
        log_warn(format!("rar entry skipped: {w}"));
    }
    Ok(())
}

fn open_entry(
    meta: &ExtractedEntryMeta,
    dest_root: &Path,
    state: &mut LimitState,
    shared: &Rc<RefCell<Shared>>,
    sizes: &mut HashMap<Vec<u8>, VecDeque<u64>>,
    warnings: &mut Vec<String>,
    counter: &mut usize,
) -> RarsResult<Box<dyn Write>> {
    if is_cancelled() {
        shared.borrow_mut().abort = Some(ArchiveError::Cancelled);
        return Ok(Box::new(Discard {
            shared: Rc::clone(shared),
        }));
    }
    let declared = sizes.get_mut(&meta.name).and_then(VecDeque::pop_front);
    let name = match sanitize_entry_name(&meta.name_lossy()) {
        Ok(n) => n,
        Err(e) => {
            warnings.push(format!("unsafe entry name: {e}"));
            return Ok(Box::new(Discard {
                shared: Rc::clone(shared),
            }));
        }
    };
    if meta.is_directory {
        match safe_join(dest_root, &name).and_then(|out| create_dir_all_checked(dest_root, &out)) {
            Ok(()) => {}
            Err(e) => warnings.push(format!("{name}: {e}")),
        }
        return Ok(Box::new(Discard {
            shared: Rc::clone(shared),
        }));
    }
    if is_symlink(meta) {
        warnings.push(format!("{name}: symlinks are not extracted"));
        return Ok(Box::new(Discard {
            shared: Rc::clone(shared),
        }));
    }
    let out = match safe_join(dest_root, &name) {
        Ok(out) => out,
        Err(e) => {
            warnings.push(format!("{name}: {e}"));
            return Ok(Box::new(Discard {
                shared: Rc::clone(shared),
            }));
        }
    };
    if let Some(parent) = out.parent()
        && let Err(e) = create_dir_all_checked(dest_root, parent)
    {
        warnings.push(format!("{name}: {e}"));
        return Ok(Box::new(Discard {
            shared: Rc::clone(shared),
        }));
    }
    if let Err(e) = reject_symlink_ancestors(dest_root, &out) {
        warnings.push(format!("{name}: {e}"));
        return Ok(Box::new(Discard {
            shared: Rc::clone(shared),
        }));
    }
    if let Err(e) = state.begin_entry(&name, declared) {
        if e.is_fatal() {
            shared.borrow_mut().abort = Some(e);
        } else {
            warnings.push(format!("{name}: {e}"));
        }
        return Ok(Box::new(Discard {
            shared: Rc::clone(shared),
        }));
    }
    let allowance = state.allowance(declared);
    let file = match create_output_file(&out) {
        Ok(f) => f,
        Err(e) => {
            warnings.push(format!("{name}: {e}"));
            return Ok(Box::new(Discard {
                shared: Rc::clone(shared),
            }));
        }
    };
    if set_file_mode(&out).is_err() {
        // Non-fatal: permissions stay restrictive by default.
    }
    let index = *counter;
    *counter += 1;
    shared
        .borrow_mut()
        .records
        .push(EntryRecord { name, declared });
    Ok(Box::new(BudgetWriter {
        inner: BufWriter::new(file),
        allowance,
        count: 0,
        since_cancel_check: 0,
        shared: Rc::clone(shared),
        index,
    }))
}

pub fn list(archive: &Path) -> Result<Vec<String>> {
    let parsed = open_archive(archive, None, &Limits::default())?;
    let mut out = Vec::new();
    for member in parsed.members() {
        check_cancelled()?;
        out.push(member.meta.name_lossy());
    }
    Ok(out)
}

pub fn list_detailed(archive: &Path) -> Result<PreviewListing> {
    list_detailed_impl(archive, None)
}

pub fn list_detailed_with_password(archive: &Path, password: &str) -> Result<PreviewListing> {
    if password.is_empty() {
        return list_detailed_impl(archive, None);
    }
    list_detailed_impl(archive, Some(password.as_bytes()))
}

fn list_detailed_impl(archive: &Path, password: Option<&[u8]>) -> Result<PreviewListing> {
    let parsed = open_archive(archive, password, &Limits::default())?;
    let mut out = Vec::new();
    for member in parsed.members() {
        check_cancelled()?;
        let meta = &member.meta;
        let is_dir = meta.is_directory;
        out.push(PreviewEntry {
            name: meta.name_lossy(),
            size: if is_dir { 0 } else { meta.unpacked_size },
            is_dir,
            encrypted: meta.is_encrypted,
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
    test_impl(archive, limits, Some(password.as_bytes()))
}

fn test_impl(archive: &Path, limits: &Limits, password: Option<&[u8]>) -> Result<TestReport> {
    let parsed = open_archive(archive, password, limits)?;
    let mut entries = 0usize;
    let mut total_size = 0u64;
    for member in parsed.members() {
        check_cancelled()?;
        entries += 1;
        if entries > limits.max_entries {
            return Err(ArchiveError::limit(format!(
                "archive has more than {} entries",
                limits.max_entries
            )));
        }
        if !member.meta.is_directory {
            total_size = total_size.saturating_add(member.meta.unpacked_size);
        }
    }
    let clean = password.filter(|p| !p.is_empty());
    let have_password = clean.is_some();
    match parsed.test(clean) {
        Ok(()) => Ok(TestReport {
            entries,
            total_size,
            failures: Vec::new(),
            password_required: false,
        }),
        Err(e) => {
            let mapped = map_err(e, have_password);
            match mapped {
                ArchiveError::PasswordRequired(_) | ArchiveError::WrongPassword(_) => Err(mapped),
                other if other.is_fatal() => Err(other),
                other => Ok(TestReport {
                    entries,
                    total_size: 0,
                    failures: vec![TestFailure {
                        name: archive
                            .file_name()
                            .map(|n| n.to_string_lossy().into_owned())
                            .unwrap_or_else(|| "archive".to_string()),
                        reason: other.to_string(),
                    }],
                    password_required: false,
                }),
            }
        }
    }
}
