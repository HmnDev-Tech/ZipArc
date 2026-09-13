use std::fs;
use std::path::{Path, PathBuf};

use karchiver_rs::backend;
use karchiver_rs::format::Format;
use karchiver_rs::io_util::Limits;
use tempfile::tempdir;

fn data(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("data")
        .join(name)
}

#[test]
fn rar_list_names() {
    let names = backend::list(&data("rar_plain.rar"), Format::Rar).unwrap();
    assert!(names.iter().any(|e| e.ends_with("hello.txt")));
    assert!(names.iter().any(|e| e.ends_with("nested.bin")));
    assert_eq!(names.len(), 4);
}

#[test]
fn rar_preview_marks_no_encryption() {
    let listing = backend::list_detailed(&data("rar_plain.rar"), Format::Rar).unwrap();
    assert!(!listing.encrypted);
    assert_eq!(listing.entries.len(), 4);
    let hello = listing
        .entries
        .iter()
        .find(|e| e.name.ends_with("hello.txt"))
        .unwrap();
    assert_eq!(hello.size, 15);
    assert!(!hello.is_dir);
}

#[test]
fn rar_test_healthy() {
    let report =
        backend::test_archive(&data("rar_plain.rar"), Format::Rar, &Limits::default()).unwrap();
    assert!(report.ok(), "failures: {:?}", report.failures);
    assert_eq!(report.entries, 4);
}

#[test]
fn rar_extract_roundtrip() {
    let dir = tempdir().unwrap();
    let out = dir.path().join("out");
    backend::extract(
        &data("rar_plain.rar"),
        &out,
        Format::Rar,
        &Limits::default(),
    )
    .unwrap();
    assert_eq!(
        fs::read_to_string(out.join("src/hello.txt")).unwrap(),
        "hello rar world"
    );
    assert_eq!(
        fs::read(out.join("src/sub/nested.bin")).unwrap(),
        b"nested-bytes-12345"
    );
    assert_eq!(fs::read(out.join("src/empty.txt")).unwrap(), b"");
}

#[test]
fn rar_encrypted_lists_but_needs_password() {
    let listing = backend::list_detailed(&data("rar_secret.rar"), Format::Rar).unwrap();
    assert!(listing.encrypted);
    let err = backend::test_archive(&data("rar_secret.rar"), Format::Rar, &Limits::default())
        .unwrap_err();
    assert!(
        err.to_string().contains("password"),
        "unexpected error: {err}"
    );
}

#[test]
fn rar_extract_with_password() {
    let dir = tempdir().unwrap();
    let out = dir.path().join("out");
    backend::extract_with_password(
        &data("rar_secret.rar"),
        &out,
        Format::Rar,
        &Limits::default(),
        "karchiver",
    )
    .unwrap();
    assert_eq!(
        fs::read_to_string(out.join("hello.txt")).unwrap(),
        "hello rar world"
    );
}

#[test]
fn rar_wrong_password_fails() {
    let dir = tempdir().unwrap();
    let err = backend::extract_with_password(
        &data("rar_secret.rar"),
        &dir.path().join("out"),
        Format::Rar,
        &Limits::default(),
        "wrong",
    )
    .unwrap_err();
    let msg = err.to_string().to_lowercase();
    assert!(
        msg.contains("password") || msg.contains("corrupt"),
        "unexpected error: {err}"
    );
}

#[test]
fn rar_pack_list_extract_roundtrip() {
    let dir = tempdir().unwrap();
    let src = dir.path().join("src");
    fs::create_dir_all(src.join("sub")).unwrap();
    fs::write(src.join("hello.txt"), b"packed hello").unwrap();
    fs::write(src.join("sub/nested.bin"), [1u8, 2, 3, 250]).unwrap();
    let dest = dir.path().join("packed.rar");
    backend::compress(&[src], &dest, Format::Rar, &Limits::default()).unwrap();
    let names = backend::list(&dest, Format::Rar).unwrap();
    assert!(names.iter().any(|e| e.ends_with("hello.txt")));
    assert!(names.iter().any(|e| e.ends_with("nested.bin")));
    let report = backend::test_archive(&dest, Format::Rar, &Limits::default()).unwrap();
    assert!(report.ok(), "failures: {:?}", report.failures);
    let out = dir.path().join("out");
    backend::extract(&dest, &out, Format::Rar, &Limits::default()).unwrap();
    assert_eq!(
        fs::read_to_string(out.join("src/hello.txt")).unwrap(),
        "packed hello"
    );
    assert_eq!(
        fs::read(out.join("src/sub/nested.bin")).unwrap(),
        [1u8, 2, 3, 250]
    );
}

#[test]
fn rar_pack_with_password_roundtrip() {
    let dir = tempdir().unwrap();
    let src = dir.path().join("secret.txt");
    fs::write(&src, b"packed secret").unwrap();
    let dest = dir.path().join("locked.rar");
    backend::compress_with_password(&[src], &dest, Format::Rar, &Limits::default(), "karchiver")
        .unwrap();
    let listing = backend::list_detailed(&dest, Format::Rar).unwrap();
    assert!(listing.encrypted);
    let out = dir.path().join("out");
    backend::extract_with_password(&dest, &out, Format::Rar, &Limits::default(), "karchiver")
        .unwrap();
    assert_eq!(
        fs::read_to_string(out.join("secret.txt")).unwrap(),
        "packed secret"
    );
}

#[test]
fn rar_pack_empty_dir_fails_cleanly() {
    let dir = tempdir().unwrap();
    let empty = dir.path().join("empty");
    fs::create_dir_all(&empty).unwrap();
    let err = backend::compress(
        &[empty],
        &dir.path().join("out.rar"),
        Format::Rar,
        &Limits::default(),
    )
    .unwrap_err();
    assert!(err.to_string().contains("no files"), "{err}");
}

#[test]
fn rar_unicode_roundtrip() {
    let names = backend::list(&data("rar_unicode.rar"), Format::Rar).unwrap();
    assert_eq!(names, vec!["grüße-日本語.txt".to_string()]);
    let listing = backend::list_detailed(&data("rar_unicode.rar"), Format::Rar).unwrap();
    assert!(!listing.encrypted);
    assert_eq!(listing.entries.len(), 1);
    assert_eq!(listing.entries[0].size, 2);
    assert!(!listing.entries[0].is_dir);
    let dir = tempdir().unwrap();
    let out = dir.path().join("out");
    backend::extract(
        &data("rar_unicode.rar"),
        &out,
        Format::Rar,
        &Limits::default(),
    )
    .unwrap();
    assert_eq!(fs::read(out.join("grüße-日本語.txt")).unwrap(), b"hi");
    let report =
        backend::test_archive(&data("rar_unicode.rar"), Format::Rar, &Limits::default()).unwrap();
    assert!(report.ok(), "failures: {:?}", report.failures);
    assert_eq!(report.entries, 1);
    assert_eq!(report.total_size, 2);
}

#[test]
fn rar_test_with_password_ok_and_wrong() {
    let report = backend::test_archive_with_password(
        &data("rar_secret.rar"),
        Format::Rar,
        &Limits::default(),
        "karchiver",
    )
    .unwrap();
    assert!(report.ok(), "failures: {:?}", report.failures);
    assert_eq!(report.entries, 1);
    let err = backend::test_archive_with_password(
        &data("rar_secret.rar"),
        Format::Rar,
        &Limits::default(),
        "wrong",
    )
    .unwrap_err();
    let msg = err.to_string().to_lowercase();
    assert!(
        msg.contains("password") || msg.contains("corrupt"),
        "unexpected error: {err}"
    );
}

#[test]
fn rar_list_detailed_with_password() {
    let listing =
        backend::list_detailed_with_password(&data("rar_secret.rar"), Format::Rar, "karchiver")
            .unwrap();
    assert!(listing.encrypted);
    assert_eq!(listing.entries.len(), 1);
    assert_eq!(listing.entries[0].name, "hello.txt");
}

#[test]
fn rar_corrupt_data_reports_failure_not_panic() {
    let dir = tempdir().unwrap();
    let mut bad = fs::read(data("rar_plain.rar")).unwrap();
    bad[165] ^= 0xFF;
    let path = dir.path().join("corrupt.bin");
    fs::write(&path, &bad).unwrap();
    let report = backend::test_archive(&path, Format::Rar, &Limits::default()).unwrap();
    assert!(!report.ok());
    assert!(!report.failures.is_empty(), "expected recorded failures");
    assert_eq!(report.entries, 4);
    let err = backend::extract(
        &path,
        &dir.path().join("out"),
        Format::Rar,
        &Limits::default(),
    )
    .unwrap_err();
    assert!(!err.to_string().is_empty(), "{err}");
}

#[test]
fn rar_truncated_tail_fails_cleanly() {
    let dir = tempdir().unwrap();
    let raw = fs::read(data("rar_plain.rar")).unwrap();
    let path = dir.path().join("cut.bin");
    fs::write(&path, &raw[..120]).unwrap();
    let test_err = backend::test_archive(&path, Format::Rar, &Limits::default()).unwrap_err();
    assert!(!test_err.to_string().is_empty(), "{test_err}");
    let list_err = backend::list(&path, Format::Rar).unwrap_err();
    assert!(!list_err.to_string().is_empty(), "{list_err}");
    let extract_err = backend::extract(
        &path,
        &dir.path().join("out"),
        Format::Rar,
        &Limits::default(),
    )
    .unwrap_err();
    assert!(!extract_err.to_string().is_empty(), "{extract_err}");
}
