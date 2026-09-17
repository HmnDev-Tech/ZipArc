use ziparc_rs::backend;
use ziparc_rs::format::Format;
use ziparc_rs::io_util::Limits;

fn make_tree(root: &std::path::Path) {
    std::fs::create_dir_all(root.join("sub")).unwrap();
    std::fs::write(root.join("b.txt"), b"bbb").unwrap();
    std::fs::write(root.join("a.txt"), b"a").unwrap();
    std::fs::write(root.join("sub/nested.bin"), [1u8, 2, 3]).unwrap();
}

#[test]
fn zip_detailed_is_sorted_with_sizes() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("out.zip");
    backend::compress(&[src], &dest, Format::Zip, &Limits::default()).unwrap();
    let listing = backend::list_detailed(&dest, Format::Zip).unwrap();
    assert!(!listing.entries.is_empty());
    assert!(!listing.encrypted);
    let names: Vec<&str> = listing.entries.iter().map(|e| e.name.as_str()).collect();
    let mut sorted = names.clone();
    sorted.sort();
    assert_eq!(names, sorted);
    let hello = listing
        .entries
        .iter()
        .find(|e| e.name.ends_with("a.txt"))
        .expect("a.txt listed");
    assert_eq!(hello.size, 1);
    assert!(!hello.is_dir);
    assert!(!hello.encrypted);
}

#[test]
fn tar_detailed_lists_entries() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("out.tar.gz");
    backend::compress(&[src], &dest, Format::TarGz, &Limits::default()).unwrap();
    let listing = backend::list_detailed(&dest, Format::TarGz).unwrap();
    assert!(!listing.entries.is_empty());
    assert!(!listing.encrypted);
    assert!(listing.entries.iter().any(|e| e.name.ends_with("b.txt")));
}

#[test]
fn single_stream_detailed_lists_one_file() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("note.txt");
    std::fs::write(&src, b"hello").unwrap();
    let dest = dir.path().join("note.txt.gz");
    backend::compress(
        std::slice::from_ref(&src),
        &dest,
        Format::Gzip,
        &Limits::default(),
    )
    .unwrap();
    let listing = backend::list_detailed(&dest, Format::Gzip).unwrap();
    assert_eq!(listing.entries.len(), 1);
    assert!(!listing.entries[0].is_dir);
    assert!(!listing.encrypted);
}

#[test]
fn sevenz_detailed_lists_entries() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src");
    make_tree(&src);
    let dest = dir.path().join("out.7z");
    backend::compress(&[src], &dest, Format::SevenZ, &Limits::default()).unwrap();
    let listing = backend::list_detailed(&dest, Format::SevenZ).unwrap();
    assert!(!listing.entries.is_empty());
    assert!(listing.entries.iter().any(|e| e.name.ends_with("a.txt")));
}

#[test]
fn corrupt_archive_detailed_fails() {
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("out.zip");
    std::fs::write(&dest, b"not a zip at all").unwrap();
    assert!(backend::list_detailed(&dest, Format::Zip).is_err());
}
