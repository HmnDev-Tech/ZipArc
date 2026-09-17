use std::fs;
use std::io::Write;
use std::path::Path;

use karchiver_rs::backend;
use karchiver_rs::content_search::scan_reader;
use karchiver_rs::format::Format;
use tempfile::tempdir;

fn build_zip(path: &Path) {
    let file = fs::File::create(path).unwrap();
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default();
    zip.start_file("notes.txt", options).unwrap();
    zip.write_all(b"first line\nsecond line has the needle here\nthird\n")
        .unwrap();
    zip.start_file("nested/data.txt", options).unwrap();
    zip.write_all(b"uppercase NEEDLE in nested entry").unwrap();
    zip.start_file("blob.bin", options).unwrap();
    zip.write_all(&[0u8, 1, 2, 3, 4, 5, b'n', b'e', b'e', b'd', b'l', b'e'])
        .unwrap();
    zip.finish().unwrap();
}

#[test]
fn finds_needle_with_line_and_snippet() {
    let dir = tempdir().unwrap();
    let archive = dir.path().join("scan.zip");
    build_zip(&archive);

    let matches =
        backend::search_content(&archive, Format::Zip, "needle", false, None, u64::MAX).unwrap();
    assert_eq!(matches.len(), 2);
    let notes = matches
        .iter()
        .find(|m| m.name == "notes.txt")
        .expect("notes.txt match");
    assert_eq!(notes.line, 2);
    assert!(notes.snippet.contains("needle"));
    assert!(matches.iter().any(|m| m.name == "nested/data.txt"));
    assert!(!matches.iter().any(|m| m.name == "blob.bin"));
}

#[test]
fn case_sensitive_search_is_respected() {
    let dir = tempdir().unwrap();
    let archive = dir.path().join("scan.zip");
    build_zip(&archive);

    let insensitive =
        backend::search_content(&archive, Format::Zip, "NEEDLE", false, None, u64::MAX).unwrap();
    assert_eq!(insensitive.len(), 2);

    let sensitive =
        backend::search_content(&archive, Format::Zip, "NEEDLE", true, None, u64::MAX).unwrap();
    assert_eq!(sensitive.len(), 1);
    assert_eq!(sensitive[0].name, "nested/data.txt");
}

#[test]
fn binary_entries_are_skipped() {
    let dir = tempdir().unwrap();
    let archive = dir.path().join("scan.zip");
    build_zip(&archive);

    let matches =
        backend::search_content(&archive, Format::Zip, "needle", false, None, u64::MAX).unwrap();
    assert!(!matches.iter().any(|m| m.name == "blob.bin"));
}

#[test]
fn missing_needle_returns_empty() {
    let dir = tempdir().unwrap();
    let archive = dir.path().join("scan.zip");
    build_zip(&archive);

    let matches =
        backend::search_content(&archive, Format::Zip, "absent", false, None, u64::MAX).unwrap();
    assert!(matches.is_empty());
}

#[test]
fn scanner_matches_across_chunk_boundaries() {
    let mut data = b"first\n".to_vec();
    data.extend(std::iter::repeat_n(b'a', 64 * 1024 - 6));
    data.extend_from_slice(b"boundary needle");
    let mut cursor = std::io::Cursor::new(data);
    let found = scan_reader(&mut cursor, "boundary needle", false, u64::MAX)
        .unwrap()
        .expect("match");
    assert_eq!(found.line, 2);
    assert!(found.snippet.contains("boundary needle"));
}

#[test]
fn scanner_respects_byte_cap() {
    let mut data = vec![b'a'; 4096];
    data.extend_from_slice(b"late needle");
    let mut cursor = std::io::Cursor::new(data);
    let found = scan_reader(&mut cursor, "late needle", false, 64).unwrap();
    assert!(found.is_none());
    cursor.set_position(0);
    let found = scan_reader(&mut cursor, "late needle", false, 8192).unwrap();
    assert!(found.is_some());
}
