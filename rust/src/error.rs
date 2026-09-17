//! Unified error model for the archive engine.
//!
//! Every backend returns [`ArchiveError`]. At the JNI edge the error is turned
//! into a `java.lang.RuntimeException` message; the typed distinction is kept
//! so security violations and resource limits can be handled differently from
//! ordinary I/O failures.

use std::io;

/// Marker embedded into synthetic [`io::Error`] messages so that a streaming
/// budget violation produced deep inside `std::io::copy` can be recognised
/// again when it surfaces as an [`io::Error`].
pub const LIMIT_MARKER: &str = "ZIPARC_LIMIT";
pub const CANCEL_MARKER: &str = "ZIPARC_CANCELLED";
pub const PASSWORD_MARKER: &str = "password required";

/// Every failure mode the engine can produce.
#[derive(Debug, thiserror::Error)]
pub enum ArchiveError {
    /// The format is not supported (or not recognised at all).
    #[error("unsupported archive format: {0}")]
    Unsupported(String),
    /// The archive or an entry is malformed.
    #[error("invalid archive: {0}")]
    Invalid(String),
    /// A path traversal / escape / unsafe-entry rule was violated.
    #[error("security violation: {0}")]
    Security(String),
    /// A configured resource limit was exceeded (zip-bomb / abuse).
    #[error("limit exceeded: {0}")]
    LimitExceeded(String),
    /// Underlying I/O failure.
    #[error("i/o error: {0}")]
    Io(#[from] io::Error),
    /// A backend-specific failure that does not fit another category.
    #[error("archive error: {0}")]
    Backend(String),
    #[error("cancelled")]
    Cancelled,
    #[error("password required: {0}")]
    PasswordRequired(String),
    #[error("wrong password: {0}")]
    WrongPassword(String),
}

/// Convenience alias used throughout the crate.
pub type Result<T> = std::result::Result<T, ArchiveError>;

impl ArchiveError {
    /// Build a [`ArchiveError::Backend`] from any displayable error.
    pub fn backend(e: impl std::fmt::Display) -> Self {
        ArchiveError::Backend(e.to_string())
    }

    /// Build a [`ArchiveError::Invalid`] from any displayable error.
    pub fn invalid(e: impl std::fmt::Display) -> Self {
        ArchiveError::Invalid(e.to_string())
    }

    /// Build a [`ArchiveError::Security`] from any displayable error.
    pub fn security(e: impl std::fmt::Display) -> Self {
        ArchiveError::Security(e.to_string())
    }

    /// Build a [`ArchiveError::LimitExceeded`] from any displayable error.
    pub fn limit(e: impl std::fmt::Display) -> Self {
        ArchiveError::LimitExceeded(e.to_string())
    }

    pub fn password_required(e: impl std::fmt::Display) -> Self {
        ArchiveError::PasswordRequired(e.to_string())
    }

    pub fn wrong_password(e: impl std::fmt::Display) -> Self {
        ArchiveError::WrongPassword(e.to_string())
    }

    /// True for failures that must abort the whole operation instead of being
    /// collected and skipped.
    pub fn is_fatal(&self) -> bool {
        matches!(
            self,
            ArchiveError::Security(_)
                | ArchiveError::LimitExceeded(_)
                | ArchiveError::Cancelled
                | ArchiveError::PasswordRequired(_)
                | ArchiveError::WrongPassword(_)
        )
    }
}

/// Classify an [`io::Error`]. Streaming budget violations are reported as
/// [`ArchiveError::LimitExceeded`] so they abort the extraction.
pub fn classify_io(e: io::Error) -> ArchiveError {
    if e.kind() == io::ErrorKind::Other && e.to_string().contains(LIMIT_MARKER) {
        ArchiveError::limit(e)
    } else if e.to_string().contains(CANCEL_MARKER) {
        ArchiveError::Cancelled
    } else {
        ArchiveError::Io(e)
    }
}
