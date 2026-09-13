//! Archive format identification by file extension and magic bytes.

use std::path::Path;

use crate::error::{ArchiveError, Result};
use crate::io_util::read_head;

/// Archive containers the engine understands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    /// ZIP (also `.cbz`).
    Zip,
    /// 7-Zip.
    SevenZ,
    /// Uncompressed POSIX tar.
    Tar,
    /// gzip-compressed tar.
    TarGz,
    /// bzip2-compressed tar.
    TarBz2,
    /// xz-compressed tar.
    TarXz,
    /// zstd-compressed tar.
    TarZst,
    /// lz4-compressed tar.
    TarLz4,
    /// Single-stream gzip.
    Gzip,
    /// Single-stream bzip2.
    Bzip2,
    /// Single-stream xz.
    Xz,
    /// Single-stream zstd.
    Zstd,
    /// Single-stream lz4.
    Lz4,
    /// RAR (recognised but unsupported on purpose).
    Rar,
}

impl Format {
    /// Human-readable label used in error messages.
    pub fn label(self) -> &'static str {
        match self {
            Format::Zip => "zip",
            Format::SevenZ => "7z",
            Format::Tar => "tar",
            Format::TarGz => "tar.gz",
            Format::TarBz2 => "tar.bz2",
            Format::TarXz => "tar.xz",
            Format::TarZst => "tar.zst",
            Format::TarLz4 => "tar.lz4",
            Format::Gzip => "gz",
            Format::Bzip2 => "bz2",
            Format::Xz => "xz",
            Format::Zstd => "zst",
            Format::Lz4 => "lz4",
            Format::Rar => "rar",
        }
    }

    /// True for the tar family (tar container, possibly compressed).
    pub fn is_tar(self) -> bool {
        matches!(
            self,
            Format::Tar
                | Format::TarGz
                | Format::TarBz2
                | Format::TarXz
                | Format::TarZst
                | Format::TarLz4
        )
    }

    /// True for single-stream compressors that are not tar containers.
    pub fn is_single_stream(self) -> bool {
        matches!(
            self,
            Format::Gzip | Format::Bzip2 | Format::Xz | Format::Zstd | Format::Lz4
        )
    }

    /// Identify by file name suffix. Compound suffixes are checked first.
    pub fn from_extension(name: &str) -> Option<Format> {
        let lower = name.to_ascii_lowercase();
        if is_split_zip_suffix(&lower) {
            return Some(Format::Zip);
        }
        // Ordered longest / most specific first.
        for (suffix, format) in [
            (".tar.gz", Format::TarGz),
            (".tar.bz2", Format::TarBz2),
            (".tar.xz", Format::TarXz),
            (".tar.zst", Format::TarZst),
            (".tar.lz4", Format::TarLz4),
            (".tgz", Format::TarGz),
            (".tbz2", Format::TarBz2),
            (".tbz", Format::TarBz2),
            (".txz", Format::TarXz),
            (".tzst", Format::TarZst),
            (".tlz4", Format::TarLz4),
            (".zip", Format::Zip),
            (".cbz", Format::Zip),
            (".7z", Format::SevenZ),
            (".rar", Format::Rar),
            (".cbr", Format::Rar),
            (".tar", Format::Tar),
            (".gz", Format::Gzip),
            (".bz2", Format::Bzip2),
            (".xz", Format::Xz),
            (".zst", Format::Zstd),
            (".lz4", Format::Lz4),
        ] {
            if lower.ends_with(suffix) {
                return Some(format);
            }
        }
        None
    }

    /// Identify by magic bytes from the start of the file.
    pub fn from_magic(head: &[u8]) -> Option<Format> {
        if head.starts_with(&[0x50, 0x4B, 0x03, 0x04])
            || head.starts_with(&[0x50, 0x4B, 0x05, 0x06])
            || head.starts_with(&[0x50, 0x4B, 0x07, 0x08])
        {
            return Some(Format::Zip);
        }
        if head.starts_with(&[0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C]) {
            return Some(Format::SevenZ);
        }
        if head.starts_with(&[0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00])
            || head.starts_with(&[0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00])
        {
            return Some(Format::Rar);
        }
        if head.starts_with(&[0x1F, 0x8B]) {
            return Some(Format::Gzip);
        }
        if head.starts_with(&[0x42, 0x5A, 0x68]) {
            return Some(Format::Bzip2);
        }
        if head.starts_with(&[0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00]) {
            return Some(Format::Xz);
        }
        if head.starts_with(&[0x28, 0xB5, 0x2F, 0xFD]) {
            return Some(Format::Zstd);
        }
        if head.starts_with(&[0x04, 0x22, 0x4D, 0x18]) {
            return Some(Format::Lz4);
        }
        if head.len() > 262 && &head[257..262] == b"ustar" {
            return Some(Format::Tar);
        }
        None
    }
}

fn is_split_zip_suffix(lower: &str) -> bool {
    if let Some(i) = lower.rfind(".zip.") {
        let tail = &lower[i + 5..];
        if !tail.is_empty() && tail.bytes().all(|b| b.is_ascii_digit()) {
            return true;
        }
    }
    if let Some(i) = lower.rfind(".z") {
        let tail = &lower[i + 2..];
        if !lower.ends_with(".zip")
            && !tail.is_empty()
            && tail.bytes().all(|b| b.is_ascii_digit())
            && !lower[..i].is_empty()
        {
            return true;
        }
    }
    false
}

/// Combine extension and magic detection.
///
/// Compound `.tar.*` names are trusted (their magic only identifies the outer
/// compressor). For everything else a definitive container magic (zip / 7z /
/// rar) wins over the extension; when magic is a bare compressor the extension
/// decides between e.g. `gz` and `tar.gz`.
pub fn detect_from(name: &str, head: &[u8]) -> Result<Format> {
    let by_ext = Format::from_extension(name);
    let by_magic = Format::from_magic(head);
    match (by_ext, by_magic) {
        (Some(ext), Some(magic)) => {
            if ext == magic || ext.is_tar() {
                Ok(ext)
            } else {
                Ok(magic)
            }
        }
        (Some(ext), None) => Ok(ext),
        (None, Some(magic)) => Ok(magic),
        (None, None) => Err(ArchiveError::Unsupported(format!(
            "cannot identify archive format (name={name:?})"
        ))),
    }
}

/// Detect an archive format from a real file.
pub fn detect(path: &Path) -> Result<Format> {
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let head = read_head(path)?;
    detect_from(&name, &head)
}

/// Determine the format to write from the destination extension only.
pub fn format_for_destination(path: &Path) -> Result<Format> {
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let format = Format::from_extension(&name).ok_or_else(|| {
        ArchiveError::Unsupported(format!("unsupported output format for {name:?}"))
    })?;
    Ok(format)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extension_detection() {
        assert_eq!(Format::from_extension("a.zip"), Some(Format::Zip));
        assert_eq!(Format::from_extension("a.tar.gz"), Some(Format::TarGz));
        assert_eq!(Format::from_extension("a.TGZ"), Some(Format::TarGz));
        assert_eq!(Format::from_extension("a.7z"), Some(Format::SevenZ));
        assert_eq!(Format::from_extension("a.gz"), Some(Format::Gzip));
        assert_eq!(Format::from_extension("a.unknown"), None);
    }

    #[test]
    fn magic_detection() {
        assert_eq!(Format::from_magic(b"PK\x03\x04rest"), Some(Format::Zip));
        assert_eq!(
            Format::from_magic(&[0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C]),
            Some(Format::SevenZ)
        );
        assert_eq!(Format::from_magic(&[0x1F, 0x8B, 0x08]), Some(Format::Gzip));
        assert_eq!(Format::from_magic(b"not an archive"), None);
    }

    #[test]
    fn magic_wins_over_wrong_extension() {
        let head = [0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C];
        assert_eq!(detect_from("fake.zip", &head).unwrap(), Format::SevenZ);
    }

    #[test]
    fn unknown_is_unsupported() {
        assert!(matches!(
            detect_from("file.bin", b"hello world"),
            Err(ArchiveError::Unsupported(_))
        ));
    }

    #[test]
    fn rar_destination_accepted() {
        assert_eq!(
            format_for_destination(Path::new("x.rar")).unwrap(),
            Format::Rar
        );
    }
}
