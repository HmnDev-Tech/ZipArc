//! Single-stream compressor backend (gzip / bzip2 / xz / zstd / lz4).

use std::fs::{self, File};
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
use zstd::stream::read::Decoder as ZstdDecoder;
use zstd::stream::write::Encoder as ZstdEncoder;

use crate::backend::{PreviewEntry, PreviewListing, TestFailure, TestReport};
use crate::error::{ArchiveError, Result, classify_io};
use crate::format::Format;
use crate::io_util::{
    AtomicFile, LimitState, LimitedReader, Limits, check_cancelled, create_output_file,
    finish_bufwriter, progress_reset, reject_symlink_ancestors, safe_join, set_file_mode,
};

/// Compress a single regular file as a raw compressed stream.
pub fn compress(sources: &[PathBuf], dest: &Path, format: Format, limits: &Limits) -> Result<()> {
    if sources.len() != 1 {
        return Err(ArchiveError::invalid(
            "single-stream formats require exactly one source file",
        ));
    }
    let src = &sources[0];
    let md = fs::symlink_metadata(src)?;
    if !md.is_file() || md.file_type().is_symlink() {
        return Err(ArchiveError::invalid(
            "single-stream source must be a regular file",
        ));
    }
    if md.len() > limits.max_entry_size || md.len() > limits.max_total_size {
        return Err(ArchiveError::limit(
            "source file exceeds configured size limits",
        ));
    }

    let name = src
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .ok_or_else(|| ArchiveError::invalid("source has no file name"))?;

    let af = AtomicFile::new(dest)?;
    let out = File::options()
        .write(true)
        .create_new(true)
        .open(af.path())?;
    let mut state = LimitState::new(limits);
    state.begin_entry(&name, Some(md.len()))?;
    progress_reset(md.len());
    check_cancelled()?;
    let allowance = state.allowance(Some(md.len()));
    let mut limited = LimitedReader::new(BufReader::new(File::open(src)?), allowance);
    let buf = BufWriter::new(out);

    match format {
        Format::Gzip => {
            let mut enc = GzEncoder::new(buf, GzCompression::default());
            io::copy(&mut limited, &mut enc).map_err(classify_io)?;
            finish_bufwriter(enc.finish()?)?;
        }
        Format::Bzip2 => {
            let mut enc = BzEncoder::new(buf, BzCompression::default());
            io::copy(&mut limited, &mut enc).map_err(classify_io)?;
            finish_bufwriter(enc.finish()?)?;
        }
        Format::Xz => {
            let mut enc = XzWriter::new(buf, XzOptions::with_preset(6))?;
            io::copy(&mut limited, &mut enc).map_err(classify_io)?;
            finish_bufwriter(enc.finish()?)?;
        }
        Format::Zstd => {
            let mut enc = ZstdEncoder::new(buf, 3)?;
            io::copy(&mut limited, &mut enc).map_err(classify_io)?;
            finish_bufwriter(enc.finish()?)?;
        }
        Format::Lz4 => {
            let mut enc = FrameEncoder::new(buf);
            io::copy(&mut limited, &mut enc).map_err(classify_io)?;
            let inner = enc.finish().map_err(ArchiveError::backend)?;
            finish_bufwriter(inner)?;
        }
        other => {
            return Err(ArchiveError::Unsupported(format!(
                "{} is not a single-stream format",
                other.label()
            )));
        }
    }

    state.finish_entry(&name, Some(md.len()), limited.count())?;
    af.commit()
}

fn open_decoder(archive: &Path, format: Format) -> Result<Box<dyn Read>> {
    let file = File::open(archive)?;
    let buffered = BufReader::new(file);
    let reader: Box<dyn Read> = match format {
        Format::Gzip => Box::new(MultiGzDecoder::new(buffered)),
        Format::Bzip2 => Box::new(MultiBzDecoder::new(buffered)),
        Format::Xz => Box::new(XzReader::new(buffered, true)),
        Format::Zstd => Box::new(ZstdDecoder::new(buffered)?),
        Format::Lz4 => Box::new(FrameDecoder::new(buffered)),
        other => {
            return Err(ArchiveError::Unsupported(format!(
                "{} is not a single-stream format",
                other.label()
            )));
        }
    };
    Ok(reader)
}

fn output_name(archive: &Path) -> Result<String> {
    let stem = archive
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| ArchiveError::invalid("archive has no usable file name"))?;
    crate::io_util::sanitize_entry_name(&stem)
}

/// Decompress a single stream into `dest`.
pub fn extract(archive: &Path, dest: &Path, format: Format, limits: &Limits) -> Result<()> {
    progress_reset(0);
    std::fs::create_dir_all(dest)?;
    let dest_root = std::fs::canonicalize(dest)?;
    let name = output_name(archive)?;
    let out = safe_join(&dest_root, &name)?;
    reject_symlink_ancestors(&dest_root, &out)?;

    let mut state = LimitState::new(limits);
    state.begin_entry(&name, None)?;
    check_cancelled()?;
    let allowance = state.allowance(None);
    let reader = open_decoder(archive, format)?;
    let mut limited = LimitedReader::new(reader, allowance);
    let mut writer = BufWriter::new(create_output_file(&out)?);
    io::copy(&mut limited, &mut writer).map_err(classify_io)?;
    writer.flush()?;
    drop(writer);
    set_file_mode(&out)?;
    state.finish_entry(&name, None, limited.count())?;
    Ok(())
}

/// A single-stream archive contains one logical file.
pub fn list(archive: &Path, _format: Format) -> Result<Vec<String>> {
    Ok(vec![output_name(archive)?])
}

pub fn list_detailed(archive: &Path, _format: Format) -> Result<PreviewListing> {
    check_cancelled()?;
    let name = output_name(archive)?;
    let size = std::fs::metadata(archive).map(|m| m.len()).unwrap_or(0);
    Ok(PreviewListing::new(vec![PreviewEntry {
        name,
        size,
        is_dir: false,
        encrypted: false,
    }]))
}

pub fn test(archive: &Path, format: Format, limits: &Limits) -> Result<TestReport> {
    progress_reset(0);
    check_cancelled()?;
    let name = output_name(archive)?;
    let mut state = LimitState::new(limits);
    state.begin_entry(&name, None)?;
    check_cancelled()?;
    let allowance = state.allowance(None);
    let reader = open_decoder(archive, format)?;
    let mut limited = LimitedReader::new(reader, allowance);
    let mut sink = io::sink();
    match io::copy(&mut limited, &mut sink).map_err(classify_io) {
        Ok(_) => {
            let written = limited.count();
            match state.finish_entry(&name, None, written) {
                Ok(()) => Ok(TestReport {
                    entries: 1,
                    total_size: written,
                    failures: Vec::new(),
                    password_required: false,
                }),
                Err(e) if e.is_fatal() => Err(e),
                Err(e) => Ok(TestReport {
                    entries: 1,
                    total_size: 0,
                    failures: vec![TestFailure {
                        name,
                        reason: e.to_string(),
                    }],
                    password_required: false,
                }),
            }
        }
        Err(e) if e.is_fatal() => Err(e),
        Err(e) => Ok(TestReport {
            entries: 1,
            total_size: 0,
            failures: vec![TestFailure {
                name,
                reason: e.to_string(),
            }],
            password_required: false,
        }),
    }
}
