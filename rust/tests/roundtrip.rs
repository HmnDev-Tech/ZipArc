use std::fs;
use std::io::Write;
use std::path::Path;

use karchiver_rs::backend;
use karchiver_rs::format::Format;
use karchiver_rs::io_util::Limits;
use tempfile::tempdir;

fn make_tree(root: &Path) {
    fs::create_dir_all(root.join("sub")).unwrap();
    fs::write(root.join("hello.txt"), b"hello karchiver").unwrap();
    fs::write(root.join("sub/nested.bin"), [0u8, 1, 2, 3, 4, 255]).unwrap();
    fs::write(root.join("sub/empty.txt"), b"").unwrap();
}

fn assert_roundtrip(format: Format, archive_name: &str) {
    let dir = tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);

    let dest = dir.path().join(archive_name);
    backend::compress(
        std::slice::from_ref(&src),
        &dest,
        format,
        &Limits::default(),
    )
    .unwrap();
    assert!(dest.exists(), "archive was not created");
    assert!(dest.metadata().unwrap().len() > 0, "archive is empty");

    let out = dir.path().join("out");
    backend::extract(&dest, &out, format, &Limits::default()).unwrap();

    let extracted = out.join("src");
    assert_eq!(
        fs::read_to_string(extracted.join("hello.txt")).unwrap(),
        "hello karchiver"
    );
    assert_eq!(
        fs::read(extracted.join("sub/nested.bin")).unwrap(),
        [0u8, 1, 2, 3, 4, 255]
    );
    assert_eq!(fs::read(extracted.join("sub/empty.txt")).unwrap(), b"");
}

#[test]
fn zip_roundtrip() {
    assert_roundtrip(Format::Zip, "out.zip");
}

#[test]
fn sevenz_roundtrip() {
    assert_roundtrip(Format::SevenZ, "out.7z");
}

#[test]
fn tar_roundtrip() {
    assert_roundtrip(Format::Tar, "out.tar");
}

#[test]
fn tar_gz_roundtrip() {
    assert_roundtrip(Format::TarGz, "out.tar.gz");
}

#[test]
fn tar_bz2_roundtrip() {
    assert_roundtrip(Format::TarBz2, "out.tar.bz2");
}

#[test]
fn tar_xz_roundtrip() {
    assert_roundtrip(Format::TarXz, "out.tar.xz");
}

#[test]
fn tar_zst_roundtrip() {
    assert_roundtrip(Format::TarZst, "out.tar.zst");
}

#[test]
fn list_archive_entries() {
    let dir = tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("out.zip");
    backend::compress(&[src], &dest, Format::Zip, &Limits::default()).unwrap();

    let entries = backend::list(&dest, Format::Zip).unwrap();
    assert!(entries.iter().any(|e| e.ends_with("hello.txt")));
    assert!(entries.iter().any(|e| e.ends_with("nested.bin")));
}

fn write_malicious_zip(path: &Path, entry_name: &str) {
    let file = fs::File::create(path).unwrap();
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default();
    zip.start_file(entry_name, options).unwrap();
    zip.write_all(b"pwned").unwrap();
    zip.finish().unwrap();
}

#[test]
fn extraction_rejects_parent_traversal() {
    let dir = tempdir().unwrap();
    let archive = dir.path().join("evil.zip");
    write_malicious_zip(&archive, "../evil.txt");

    let out = dir.path().join("out");
    let result = backend::extract(&archive, &out, Format::Zip, &Limits::default());
    assert!(result.is_err(), "traversal entry should be rejected");
    assert!(!dir.path().join("evil.txt").exists());
}

#[test]
fn extraction_rejects_absolute_path() {
    let dir = tempdir().unwrap();
    let archive = dir.path().join("abs.zip");
    write_malicious_zip(&archive, "/tmp/karchiver-abs-evil.txt");

    let out = dir.path().join("out");
    let result = backend::extract(&archive, &out, Format::Zip, &Limits::default());
    assert!(result.is_err(), "absolute entry should be rejected");
    assert!(!Path::new("/tmp/karchiver-abs-evil.txt").exists());
}

#[test]
fn extraction_enforces_entry_count_limit() {
    let dir = tempdir().unwrap();
    let archive = dir.path().join("many.zip");
    {
        let file = fs::File::create(&archive).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        for i in 0..20 {
            zip.start_file(format!("file{i}.txt"), options).unwrap();
            zip.write_all(b"x").unwrap();
        }
        zip.finish().unwrap();
    }

    let limits = Limits {
        max_entries: 10,
        ..Default::default()
    };
    let out = dir.path().join("out");
    let result = backend::extract(&archive, &out, Format::Zip, &limits);
    assert!(result.is_err(), "entry count limit should abort extraction");
}
