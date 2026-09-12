//! KArchiver Rust core.
//!
//! This crate is a thin, panic-safe layer over a set of archive backends. All
//! business logic lives in the modules below; [`lib.rs`](self) only wires them
//! together and re-exports the public surface.

pub mod backend;
pub mod error;
pub mod format;
pub mod io_util;

mod jni_bridge;

pub use error::{ArchiveError, Result};
pub use format::Format;
pub use io_util::Limits;
