use std::io::Write;

use ziparc_rs::backend;
use ziparc_rs::format::Format;
use ziparc_rs::io_util::Limits;

fn make_tree(root: &std::path::Path) {
    std::fs::create_dir_all(root.join("sub")).unwrap();
    std::fs::write(root.join("hello.txt"), b"hello ziparc").unwrap();
    std::fs::write(root.join("sub/nested.bin"), [0u8, 1, 2, 3, 4, 255]).unwrap();
}

#[test]
fn healthy_zip_passes() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("out.zip");
    backend::compress(&[src], &dest, Format::Zip, &Limits::default()).unwrap();
    let report = backend::test_archive(&dest, Format::Zip, &Limits::default()).unwrap();
    assert!(report.ok());
    assert!(report.failures.is_empty());
    assert!(!report.password_required);
    assert!(report.entries >= 3);
    assert!(report.total_size >= 21);
}

#[test]
fn corrupted_zip_entry_fails_with_name_and_reason() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("corrupt.zip");
    {
        let file = std::fs::File::create(&archive).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        zip.start_file("good.txt", options).unwrap();
        zip.write_all(b"good content here").unwrap();
        zip.start_file("bad.txt", options).unwrap();
        zip.write_all(b"BAD_ENTRY_MARKER_DATA_1234567890").unwrap();
        zip.finish().unwrap();
    }
    let mut bytes = std::fs::read(&archive).unwrap();
    let needle = b"BAD_ENTRY_MARKER_DATA_1234567890";
    let pos = bytes
        .windows(needle.len())
        .position(|w| w == needle)
        .expect("stored payload must be verbatim");
    bytes[pos + 3] ^= 0xFF;
    std::fs::write(&archive, &bytes).unwrap();

    let report = backend::test_archive(&archive, Format::Zip, &Limits::default()).unwrap();
    assert!(!report.ok());
    assert_eq!(report.entries, 2);
    assert_eq!(report.failures.len(), 1);
    assert!(report.failures[0].name.contains("bad.txt"));
    assert!(!report.failures[0].reason.is_empty());
}

#[test]
fn corrupted_gzip_fails() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("note.txt");
    std::fs::write(&src, b"some gzip payload data").unwrap();
    let dest = dir.path().join("note.txt.gz");
    backend::compress(
        std::slice::from_ref(&src),
        &dest,
        Format::Gzip,
        &Limits::default(),
    )
    .unwrap();
    let mut bytes = std::fs::read(&dest).unwrap();
    let mid = bytes.len() / 2;
    bytes[mid] ^= 0xFF;
    bytes[mid + 1] ^= 0xFF;
    std::fs::write(&dest, &bytes).unwrap();

    let report = backend::test_archive(&dest, Format::Gzip, &Limits::default()).unwrap();
    assert!(!report.ok());
    assert_eq!(report.failures.len(), 1);
    assert!(!report.failures[0].reason.is_empty());
}

#[test]
fn healthy_tar_gz_passes() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("out.tar.gz");
    backend::compress(&[src], &dest, Format::TarGz, &Limits::default()).unwrap();
    let report = backend::test_archive(&dest, Format::TarGz, &Limits::default()).unwrap();
    assert!(report.ok());
    assert!(report.failures.is_empty());
    assert!(!report.password_required);
    assert!(report.entries >= 3);
    assert!(report.total_size >= 21);
}

#[test]
fn healthy_sevenz_passes() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("out.7z");
    backend::compress(&[src], &dest, Format::SevenZ, &Limits::default()).unwrap();
    let report = backend::test_archive(&dest, Format::SevenZ, &Limits::default()).unwrap();
    assert!(report.ok());
    assert!(report.failures.is_empty());
    assert!(!report.password_required);
    assert!(report.entries >= 3);
}

#[test]
fn healthy_gzip_passes() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("note.txt");
    std::fs::write(&src, b"hello single stream").unwrap();
    let dest = dir.path().join("note.txt.gz");
    backend::compress(
        std::slice::from_ref(&src),
        &dest,
        Format::Gzip,
        &Limits::default(),
    )
    .unwrap();
    let report = backend::test_archive(&dest, Format::Gzip, &Limits::default()).unwrap();
    assert!(report.ok());
    assert!(report.failures.is_empty());
    assert_eq!(report.entries, 1);
    assert_eq!(report.total_size, 19);
}
