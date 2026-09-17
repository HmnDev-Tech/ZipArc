use std::fs;

use ziparc_rs::backend;
use ziparc_rs::error::ArchiveError;
use ziparc_rs::format::Format;
use ziparc_rs::io_util::Limits;

const PASSWORD: &str = "correct-horse-7";
const WRONG: &str = "wrong-password-0";

fn make_tree(root: &std::path::Path) {
    fs::create_dir_all(root.join("sub")).unwrap();
    fs::write(root.join("hello.txt"), b"hello secret world").unwrap();
    fs::write(root.join("sub/nested.bin"), [9u8, 8, 7, 6, 5]).unwrap();
}

fn is_wrong_password(e: &ArchiveError) -> bool {
    matches!(e, ArchiveError::WrongPassword(_))
}

fn is_password_required(e: &ArchiveError) -> bool {
    matches!(e, ArchiveError::PasswordRequired(_))
}

#[test]
fn zip_aes_roundtrip_with_correct_password() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("secret.zip");
    backend::compress_with_password(
        std::slice::from_ref(&src),
        &dest,
        Format::Zip,
        &Limits::default(),
        PASSWORD,
    )
    .unwrap();

    let listing = backend::list_detailed(&dest, Format::Zip).unwrap();
    assert!(listing.encrypted);
    assert!(listing.entries.iter().any(|e| e.encrypted));

    let out = dir.path().join("out");
    backend::extract_with_password(&dest, &out, Format::Zip, &Limits::default(), PASSWORD).unwrap();
    assert_eq!(
        fs::read_to_string(out.join("src/hello.txt")).unwrap(),
        "hello secret world"
    );
    assert_eq!(
        fs::read(out.join("src/sub/nested.bin")).unwrap(),
        [9u8, 8, 7, 6, 5]
    );

    let report =
        backend::test_archive_with_password(&dest, Format::Zip, &Limits::default(), PASSWORD)
            .unwrap();
    assert!(report.ok());
    assert!(!report.password_required);
}

#[test]
fn zip_aes_wrong_password_is_typed() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("secret.zip");
    backend::compress_with_password(
        std::slice::from_ref(&src),
        &dest,
        Format::Zip,
        &Limits::default(),
        PASSWORD,
    )
    .unwrap();

    let out = dir.path().join("out");
    match backend::extract_with_password(&dest, &out, Format::Zip, &Limits::default(), WRONG) {
        Err(e) if is_wrong_password(&e) => {}
        other => panic!("expected WrongPassword, got {other:?}"),
    }

    match backend::test_archive_with_password(&dest, Format::Zip, &Limits::default(), WRONG) {
        Err(e) if is_wrong_password(&e) => {}
        other => panic!("expected WrongPassword, got {other:?}"),
    }
}

#[test]
fn zip_aes_without_password_requires_password() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("secret.zip");
    backend::compress_with_password(
        std::slice::from_ref(&src),
        &dest,
        Format::Zip,
        &Limits::default(),
        PASSWORD,
    )
    .unwrap();

    let out = dir.path().join("out");
    match backend::extract(&dest, &out, Format::Zip, &Limits::default()) {
        Err(e) if is_password_required(&e) => {}
        other => panic!("expected PasswordRequired, got {other:?}"),
    }

    match backend::test_archive(&dest, Format::Zip, &Limits::default()) {
        Err(e) if is_password_required(&e) => {}
        other => panic!("expected PasswordRequired, got {other:?}"),
    }
}

#[test]
fn sevenz_aes_roundtrip_with_correct_password() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("secret.7z");
    backend::compress_with_password(
        std::slice::from_ref(&src),
        &dest,
        Format::SevenZ,
        &Limits::default(),
        PASSWORD,
    )
    .unwrap();

    match backend::list_detailed(&dest, Format::SevenZ) {
        Err(e) if is_password_required(&e) => {}
        other => panic!("expected PasswordRequired, got {other:?}"),
    }

    let listing = backend::list_detailed_with_password(&dest, Format::SevenZ, PASSWORD).unwrap();
    assert!(!listing.entries.is_empty());
    assert!(listing.encrypted);

    let out = dir.path().join("out");
    backend::extract_with_password(&dest, &out, Format::SevenZ, &Limits::default(), PASSWORD)
        .unwrap();
    assert_eq!(
        fs::read_to_string(out.join("src/hello.txt")).unwrap(),
        "hello secret world"
    );

    let report =
        backend::test_archive_with_password(&dest, Format::SevenZ, &Limits::default(), PASSWORD)
            .unwrap();
    assert!(report.ok());
    assert!(!report.password_required);
}

#[test]
fn sevenz_aes_wrong_password_is_typed() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("secret.7z");
    backend::compress_with_password(
        std::slice::from_ref(&src),
        &dest,
        Format::SevenZ,
        &Limits::default(),
        PASSWORD,
    )
    .unwrap();

    match backend::list_detailed_with_password(&dest, Format::SevenZ, WRONG) {
        Err(e) if is_wrong_password(&e) => {}
        other => panic!("expected WrongPassword, got {other:?}"),
    }

    let out = dir.path().join("out");
    match backend::extract_with_password(&dest, &out, Format::SevenZ, &Limits::default(), WRONG) {
        Err(e) if is_wrong_password(&e) => {}
        other => panic!("expected WrongPassword, got {other:?}"),
    }

    match backend::test_archive_with_password(&dest, Format::SevenZ, &Limits::default(), WRONG) {
        Err(e) if is_wrong_password(&e) => {}
        other => panic!("expected WrongPassword, got {other:?}"),
    }
}

#[test]
fn empty_password_behaves_like_no_password() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("plain.zip");
    backend::compress_with_password(
        std::slice::from_ref(&src),
        &dest,
        Format::Zip,
        &Limits::default(),
        "",
    )
    .unwrap();
    let listing = backend::list_detailed(&dest, Format::Zip).unwrap();
    assert!(!listing.encrypted);
    let report =
        backend::test_archive_with_password(&dest, Format::Zip, &Limits::default(), "").unwrap();
    assert!(report.ok());
}

#[test]
fn password_for_tar_is_unsupported() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("out.tar.gz");
    match backend::compress_with_password(
        std::slice::from_ref(&src),
        &dest,
        Format::TarGz,
        &Limits::default(),
        PASSWORD,
    ) {
        Err(ArchiveError::Unsupported(_)) => {}
        other => panic!("expected Unsupported, got {other:?}"),
    }
}
