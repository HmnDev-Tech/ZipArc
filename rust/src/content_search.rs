use std::io::{self, Read};

pub const DEFAULT_MAX_BYTES: u64 = 32 * 1024 * 1024;
const BINARY_PROBE: usize = 8192;
const MAX_LINE: usize = 256 * 1024;
const SNIPPET_MAX: usize = 160;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContentMatch {
    pub name: String,
    pub line: u64,
    pub snippet: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LineMatch {
    pub line: u64,
    pub snippet: String,
}

pub struct Scanner {
    needle: Vec<u8>,
    case_sensitive: bool,
    max_bytes: u64,
    consumed: u64,
    line: u64,
    buf: Vec<u8>,
    probe_len: usize,
    binary: bool,
    result: Option<LineMatch>,
    done: bool,
}

impl Scanner {
    pub fn new(needle: &str, case_sensitive: bool, max_bytes: u64) -> Option<Self> {
        if needle.is_empty() || max_bytes == 0 {
            return None;
        }
        let needle = if case_sensitive {
            needle.as_bytes().to_vec()
        } else {
            needle.to_ascii_lowercase().into_bytes()
        };
        Some(Self {
            needle,
            case_sensitive,
            max_bytes,
            consumed: 0,
            line: 1,
            buf: Vec::new(),
            probe_len: 0,
            binary: false,
            result: None,
            done: false,
        })
    }

    pub fn feed(&mut self, data: &[u8]) {
        if self.done || data.is_empty() {
            return;
        }
        if self.probe_len < BINARY_PROBE {
            let take = (BINARY_PROBE - self.probe_len).min(data.len());
            if data[..take].contains(&0) {
                self.binary = true;
                self.done = true;
                return;
            }
            self.probe_len += take;
        }
        let remaining = self.max_bytes.saturating_sub(self.consumed);
        if remaining == 0 {
            self.done = true;
            return;
        }
        let take = data.len().min(remaining as usize);
        self.consumed += take as u64;
        self.buf.extend_from_slice(&data[..take]);
        self.drain_lines(false);
        if self.consumed >= self.max_bytes {
            self.drain_lines(true);
        }
        if self.result.is_some() {
            self.done = true;
        }
    }

    pub fn is_binary(&self) -> bool {
        self.binary
    }

    pub fn is_done(&self) -> bool {
        self.done
    }

    pub fn finish(&mut self) -> Option<LineMatch> {
        if self.result.is_none() && !self.binary && !self.done {
            self.drain_lines(true);
        }
        self.result.take()
    }

    fn drain_lines(&mut self, at_end: bool) {
        while let Some(pos) = self.buf.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = self.buf.drain(..pos).collect();
            self.buf.drain(..1);
            if self.match_line(&line) {
                return;
            }
            self.line += 1;
        }
        if at_end {
            if !self.buf.is_empty() {
                let line = std::mem::take(&mut self.buf);
                self.match_line(&line);
            }
        } else if self.buf.len() > MAX_LINE {
            let line = std::mem::take(&mut self.buf);
            if self.match_line(&line) {
                return;
            }
            let keep = self.needle.len().saturating_sub(1);
            if line.len() > keep {
                self.buf.extend_from_slice(&line[line.len() - keep..]);
            } else {
                self.buf.extend_from_slice(&line);
            }
        }
    }

    fn match_line(&mut self, line: &[u8]) -> bool {
        let hay = if self.case_sensitive {
            line.to_vec()
        } else {
            line.to_ascii_lowercase()
        };
        if let Some(at) = find_subslice(&hay, &self.needle) {
            self.result = Some(LineMatch {
                line: self.line,
                snippet: snippet(line, at),
            });
            return true;
        }
        false
    }
}

pub fn find_subslice(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return None;
    }
    let limit = hay.len() - needle.len();
    for i in 0..=limit {
        if &hay[i..i + needle.len()] == needle {
            return Some(i);
        }
    }
    None
}

pub fn snippet(line: &[u8], at: usize) -> String {
    let mut start = at.saturating_sub(SNIPPET_MAX / 3);
    let mut end = (start + SNIPPET_MAX).min(line.len());
    if end - start < SNIPPET_MAX {
        start = end.saturating_sub(SNIPPET_MAX);
        end = (start + SNIPPET_MAX).min(line.len());
    }
    let prefix = if start > 0 { "…" } else { "" };
    let suffix = if end < line.len() { "…" } else { "" };
    let slice = String::from_utf8_lossy(&line[start..end]);
    format!("{prefix}{}{suffix}", slice.trim())
}

pub fn scan_reader<R: Read>(
    reader: &mut R,
    needle: &str,
    case_sensitive: bool,
    max_bytes: u64,
) -> io::Result<Option<LineMatch>> {
    let Some(mut scanner) = Scanner::new(needle, case_sensitive, max_bytes) else {
        return Ok(None);
    };
    let mut buf = vec![0u8; 64 * 1024];
    while !scanner.is_done() {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        scanner.feed(&buf[..n]);
    }
    Ok(scanner.finish())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scan_text(text: &str, needle: &str, case_sensitive: bool) -> Option<LineMatch> {
        let mut cursor = io::Cursor::new(text.as_bytes().to_vec());
        scan_reader(&mut cursor, needle, case_sensitive, DEFAULT_MAX_BYTES).unwrap()
    }

    #[test]
    fn finds_needle_on_first_line() {
        let m = scan_text("hello world\nsecond", "world", false).unwrap();
        assert_eq!(m.line, 1);
        assert_eq!(m.snippet, "hello world");
    }

    #[test]
    fn reports_one_based_line_number() {
        let m = scan_text("alpha\nbeta\ngamma needle", "needle", false).unwrap();
        assert_eq!(m.line, 3);
    }

    #[test]
    fn case_insensitive_by_default() {
        assert!(scan_text("Hello World", "hello", false).is_some());
        assert!(scan_text("Hello World", "HELLO", true).is_none());
        assert!(scan_text("Hello World", "Hello", true).is_some());
    }

    #[test]
    fn binary_is_skipped() {
        let mut data = vec![b'a', 0, b'b'];
        data.extend_from_slice(b"needle");
        let mut cursor = io::Cursor::new(data);
        let found = scan_reader(&mut cursor, "needle", false, DEFAULT_MAX_BYTES).unwrap();
        assert!(found.is_none());
    }

    #[test]
    fn size_cap_stops_scanning() {
        let text = format!("{}needle", "x".repeat(4096));
        let mut cursor = io::Cursor::new(text.into_bytes());
        let found = scan_reader(&mut cursor, "needle", false, 64).unwrap();
        assert!(found.is_none());
    }

    #[test]
    fn matches_across_small_reads() {
        struct Tiny<R> {
            inner: R,
        }
        impl<R: Read> Read for Tiny<R> {
            fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
                if buf.is_empty() {
                    return Ok(0);
                }
                self.inner.read(&mut buf[..1])
            }
        }
        let mut reader = Tiny {
            inner: io::Cursor::new(b"the complete needle is here".to_vec()),
        };
        let found = scan_reader(&mut reader, "complete needle", false, DEFAULT_MAX_BYTES).unwrap();
        assert!(found.is_some());
        assert_eq!(found.unwrap().line, 1);
    }

    #[test]
    fn snippet_is_trimmed_and_capped() {
        let long = format!("   {}   ", "a".repeat(400));
        let m = scan_text(&long, "aaa", false).unwrap();
        assert!(m.snippet.contains("aaa"));
        assert!(m.snippet.len() <= SNIPPET_MAX + 2);
        assert!(!m.snippet.starts_with(' '));
        assert!(!m.snippet.ends_with(' '));
    }

    #[test]
    fn empty_needle_is_ignored() {
        assert!(scan_text("anything", "", false).is_none());
    }

    #[test]
    fn scanner_detects_binary_flag() {
        let mut scanner = Scanner::new("x", false, DEFAULT_MAX_BYTES).unwrap();
        scanner.feed(&[0u8, 1, 2]);
        assert!(scanner.is_binary());
        assert!(scanner.finish().is_none());
    }
}
