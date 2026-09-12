use std::io::Write;

use karchiver_rs::backend;
use karchiver_rs::error::ArchiveError;
use karchiver_rs::format::Format;
use karchiver_rs::io_util::{Limits, clear_cancel, request_cancel};

#[test]
fn extract_zip_is_cancellable_mid_way() {
    clear_cancel();
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("big.zip");
    {
        let file = std::fs::File::create(&archive).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        let payload = vec![0xABu8; 256 * 1024];
        for i in 0..400 {
            zip.start_file(format!("file{i:04}.bin"), options).unwrap();
            zip.write_all(&payload).unwrap();
        }
        zip.finish().unwrap();
    }
    let out = dir.path().join("out");
    std::fs::create_dir_all(&out).unwrap();
    let out_clone = out.clone();
    let archive_clone = archive.clone();
    let handle = std::thread::spawn(move || {
        backend::extract(&archive_clone, &out_clone, Format::Zip, &Limits::default())
    });
    request_cancel();
    let result = handle.join().expect("extract thread panicked");
    clear_cancel();
    match result {
        Err(ArchiveError::Cancelled) => {}
        other => panic!("expected Cancelled, got {other:?}"),
    }
}

#[test]
fn preset_flag_aborts_compress() {
    clear_cancel();
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("src.txt");
    std::fs::write(&src, vec![1u8; 1024]).unwrap();
    let dest = dir.path().join("out.zip");
    request_cancel();
    let result = backend::compress(
        std::slice::from_ref(&src),
        &dest,
        Format::Zip,
        &Limits::default(),
    );
    clear_cancel();
    match result {
        Err(ArchiveError::Cancelled) => {}
        other => panic!("expected Cancelled, got {other:?}"),
    }
}
