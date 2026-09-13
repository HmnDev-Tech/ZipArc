use std::fs;
use std::path::Path;

use karchiver_rs::backend;
use karchiver_rs::format::Format;
use karchiver_rs::io_util::Limits;
use tempfile::tempdir;

fn make_tree(root: &Path) {
    fs::create_dir_all(root.join("sub")).unwrap();
    fs::write(root.join("hello.txt"), b"hello split zip").unwrap();
    fs::write(root.join("sub/nested.bin"), [10u8, 20, 30, 40, 250]).unwrap();
    fs::write(root.join("sub/empty.txt"), b"").unwrap();
}

fn build_zip_bytes(dir: &Path) -> Vec<u8> {
    let src = dir.join("src");
    make_tree(&src);
    let dest = dir.join("orig.zip");
    backend::compress(&[src], &dest, Format::Zip, &Limits::default()).unwrap();
    fs::read(&dest).unwrap()
}

fn thirds(bytes: &[u8]) -> [&[u8]; 3] {
    let a = bytes.len() / 3;
    let b = 2 * bytes.len() / 3;
    [&bytes[..a], &bytes[a..b], &bytes[b..]]
}

fn assert_names(path: &Path) {
    let names = backend::list(path, Format::Zip).unwrap();
    assert!(names.iter().any(|e| e.ends_with("hello.txt")));
    assert!(names.iter().any(|e| e.ends_with("nested.bin")));
    let detailed = backend::list_detailed(path, Format::Zip).unwrap();
    assert_eq!(detailed.entries.len(), names.len());
    let report = backend::test_archive(path, Format::Zip, &Limits::default()).unwrap();
    assert!(report.ok(), "failures: {:?}", report.failures);
}

fn assert_tree(out: &Path) {
    let root = out.join("src");
    assert_eq!(
        fs::read_to_string(root.join("hello.txt")).unwrap(),
        "hello split zip"
    );
    assert_eq!(
        fs::read(root.join("sub/nested.bin")).unwrap(),
        [10u8, 20, 30, 40, 250]
    );
    assert_eq!(fs::read(root.join("sub/empty.txt")).unwrap(), b"");
}

#[test]
fn classic_split_list_test_extract_roundtrip() {
    let dir = tempdir().unwrap();
    let bytes = build_zip_bytes(dir.path());
    let [p1, p2, p3] = thirds(&bytes);
    fs::write(dir.path().join("arch.z01"), p1).unwrap();
    fs::write(dir.path().join("arch.z02"), p2).unwrap();
    fs::write(dir.path().join("arch.zip"), p3).unwrap();
    let via_final = dir.path().join("arch.zip");
    let via_first = dir.path().join("arch.z01");
    let via_middle = dir.path().join("arch.z02");
    assert_names(&via_final);
    assert_names(&via_first);
    assert_names(&via_middle);
    let out = dir.path().join("out");
    backend::extract(&via_first, &out, Format::Zip, &Limits::default()).unwrap();
    assert_tree(&out);
    let out2 = dir.path().join("out2");
    backend::extract(&via_final, &out2, Format::Zip, &Limits::default()).unwrap();
    assert_tree(&out2);
}

#[test]
fn dotted_split_list_test_extract_roundtrip() {
    let dir = tempdir().unwrap();
    let bytes = build_zip_bytes(dir.path());
    let [p1, p2, p3] = thirds(&bytes);
    fs::write(dir.path().join("arch.zip.001"), p1).unwrap();
    fs::write(dir.path().join("arch.zip.002"), p2).unwrap();
    fs::write(dir.path().join("arch.zip.003"), p3).unwrap();
    let via_first = dir.path().join("arch.zip.001");
    let via_last = dir.path().join("arch.zip.003");
    assert_names(&via_first);
    assert_names(&via_last);
    let out = dir.path().join("out");
    backend::extract(&via_first, &out, Format::Zip, &Limits::default()).unwrap();
    assert_tree(&out);
}

#[test]
fn classic_split_missing_middle_names_file() {
    let dir = tempdir().unwrap();
    let bytes = build_zip_bytes(dir.path());
    let [p1, _, p3] = thirds(&bytes);
    fs::write(dir.path().join("arch.z01"), p1).unwrap();
    fs::write(dir.path().join("arch.z03"), p3).unwrap();
    fs::write(dir.path().join("arch.zip"), b"tail").unwrap();
    let err = backend::list(&dir.path().join("arch.z01"), Format::Zip).unwrap_err();
    assert!(err.to_string().contains(".z02"), "unexpected error: {err}");
}

#[test]
fn classic_split_missing_final_names_file() {
    let dir = tempdir().unwrap();
    let bytes = build_zip_bytes(dir.path());
    let [p1, p2, _] = thirds(&bytes);
    fs::write(dir.path().join("arch.z01"), p1).unwrap();
    fs::write(dir.path().join("arch.z02"), p2).unwrap();
    let err = backend::list(&dir.path().join("arch.z01"), Format::Zip).unwrap_err();
    assert!(
        err.to_string().contains("arch.zip"),
        "unexpected error: {err}"
    );
}

#[test]
fn dotted_split_missing_middle_names_file() {
    let dir = tempdir().unwrap();
    let bytes = build_zip_bytes(dir.path());
    let [p1, _, p3] = thirds(&bytes);
    fs::write(dir.path().join("arch.zip.001"), p1).unwrap();
    fs::write(dir.path().join("arch.zip.003"), p3).unwrap();
    let err = backend::list(&dir.path().join("arch.zip.001"), Format::Zip).unwrap_err();
    assert!(err.to_string().contains(".002"), "unexpected error: {err}");
}

#[test]
fn split_extension_routes_to_zip() {
    assert_eq!(Format::from_extension("arch.z01"), Some(Format::Zip));
    assert_eq!(Format::from_extension("arch.z02"), Some(Format::Zip));
    assert_eq!(Format::from_extension("arch.zip.001"), Some(Format::Zip));
    assert_eq!(Format::from_extension("arch.zip"), Some(Format::Zip));
    assert_eq!(Format::from_extension("arch.7z"), Some(Format::SevenZ));
}
