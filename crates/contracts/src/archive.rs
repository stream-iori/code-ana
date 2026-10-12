//! Owned record spools shared by worker transport and storage. No decoded graph is retained.
use crate::performance::{Metric, measure};
use anyhow::{Result, ensure};
use codeana_model::{AnalysisHeader, Fact, MAX_FRAME};
use prost::Message;
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{BufReader, BufWriter, Read, Write},
    path::{Path, PathBuf},
    sync::Arc,
};

const MAGIC: u32 = 0x43414631;

#[derive(Clone)]
pub struct FactArchive {
    _directory: Arc<tempfile::TempDir>,
    path: PathBuf,
    pub count: u64,
    pub bytes: u64,
    pub checksum: String,
}
pub struct FactArchiveBuilder {
    archive: FactArchive,
    writer: BufWriter<File>,
    digest: Sha256,
    max_bytes: u64,
    max_count: u64,
}
pub struct SpooledAnalysis {
    pub header: AnalysisHeader,
    pub records: FactArchive,
}
impl FactArchiveBuilder {
    pub fn new(max_bytes: u64, max_count: u64) -> Result<Self> {
        Self::create(max_bytes, max_count, None)
    }
    pub fn new_in(max_bytes: u64, max_count: u64, root: &Path) -> Result<Self> {
        Self::create(max_bytes, max_count, Some(root))
    }
    fn create(max_bytes: u64, max_count: u64, root: Option<&Path>) -> Result<Self> {
        ensure!(max_bytes >= 4, "FACT_ARCHIVE_BUDGET");
        let mut builder = tempfile::Builder::new();
        builder.prefix("codeana-facts-");
        let directory = Arc::new(match root {
            Some(path) => builder.tempdir_in(path)?,
            None => builder.tempdir()?,
        });
        let path = directory.path().join("records.bin");
        let mut writer = BufWriter::new(File::create(&path)?);
        writer.write_all(&MAGIC.to_be_bytes())?;
        let mut digest = Sha256::new();
        digest.update(b"[");
        Ok(Self {
            archive: FactArchive {
                _directory: directory,
                path,
                count: 0,
                bytes: 4,
                checksum: String::new(),
            },
            writer,
            digest,
            max_bytes,
            max_count,
        })
    }
    pub fn push(&mut self, fact: &Fact) -> Result<()> {
        let bytes = measure(Metric::ArchiveEncode, 0, true, || serde_json::to_vec(fact))?;
        ensure!(
            !bytes.is_empty() && bytes.len() <= MAX_FRAME,
            "FACT_FRAME_BUDGET"
        );
        ensure!(
            self.archive.count < self.max_count
                && bytes.len() as u64 + 4 <= self.max_bytes - self.archive.bytes,
            "FACT_ARCHIVE_BUDGET"
        );
        measure(
            Metric::ArchiveWrite,
            bytes.len() as u64 + 4,
            true,
            || -> Result<()> {
                self.writer.write_all(&(bytes.len() as u32).to_be_bytes())?;
                self.writer.write_all(&bytes)?;
                Ok(())
            },
        )?;
        if self.archive.count > 0 {
            self.digest.update(b",");
        }
        measure(Metric::ArchiveDigest, bytes.len() as u64, true, || {
            self.digest.update(&bytes)
        });
        self.archive.count += 1;
        self.archive.bytes += bytes.len() as u64 + 4;
        Ok(())
    }
    pub fn count(&self) -> u64 {
        self.archive.count
    }
    pub fn seal(mut self) -> Result<FactArchive> {
        measure(Metric::ArchiveFlush, self.archive.bytes, false, || {
            self.writer.flush()
        })?;
        measure(Metric::ArchiveSync, self.archive.bytes, false, || {
            self.writer.get_ref().sync_all()
        })?;
        self.digest.update(b"]");
        self.archive.checksum = format!("{:x}", self.digest.finalize());
        Ok(self.archive)
    }
}
impl FactArchive {
    pub fn path(&self) -> &Path {
        &self.path
    }
    pub fn file_hash(&self) -> Result<String> {
        Ok(codeana_model::hash_file(self.path())?)
    }
    pub fn iter(&self) -> Result<FactIterator> {
        let mut input = BufReader::new(File::open(self.path())?);
        let mut magic = [0u8; 4];
        input.read_exact(&mut magic)?;
        ensure!(u32::from_be_bytes(magic) == MAGIC, "FACT_ARCHIVE_VERSION");
        ensure!(
            input.get_ref().metadata()?.len() == self.bytes,
            "FACT_ARCHIVE_LENGTH"
        );
        if self.count == 0 {
            ensure!(
                self.bytes == 4 && self.checksum == codeana_model::digest(&Vec::<Fact>::new()),
                "FACT_ARCHIVE_CHECKSUM"
            );
        }
        let mut digest = Sha256::new();
        digest.update(b"[");
        Ok(FactIterator {
            input,
            remaining: self.count,
            digest,
            checksum: self.checksum.clone(),
            first: true,
        })
    }
}
/// Decoded fact and its verified, normalized CAF1 JSON. Produced only by owned archives.
pub struct FactRecord {
    fact: Fact,
    body: String,
}
impl FactRecord {
    pub fn into_parts(self) -> (Fact, String) {
        (self.fact, self.body)
    }
}
pub struct FactRecords(FactIterator);
impl Iterator for FactRecords {
    type Item = Result<FactRecord>;
    fn next(&mut self) -> Option<Self::Item> {
        self.0.next_record()
    }
}
impl FactArchive {
    pub fn records(&self) -> Result<FactRecords> {
        Ok(FactRecords(self.iter()?))
    }
}
pub struct FactIterator {
    input: BufReader<File>,
    remaining: u64,
    digest: Sha256,
    checksum: String,
    first: bool,
}
impl FactIterator {
    fn next_record(&mut self) -> Option<Result<FactRecord>> {
        if self.remaining == 0 {
            return None;
        }
        let result = (|| {
            let mut length = [0u8; 4];
            self.input.read_exact(&mut length)?;
            let length = u32::from_be_bytes(length) as usize;
            ensure!(length > 0 && length <= MAX_FRAME, "FACT_FRAME_BUDGET");
            let mut bytes = vec![0u8; length];
            measure(Metric::ArchiveRead, length as u64, true, || {
                self.input.read_exact(&mut bytes)
            })?;
            let fact = measure(Metric::ArchiveDecode, bytes.len() as u64, true, || {
                serde_json::from_slice(&bytes)
            })?;
            if !self.first {
                self.digest.update(b",");
            }
            self.first = false;
            measure(Metric::ArchiveDigest, bytes.len() as u64, true, || {
                self.digest.update(&bytes)
            });
            if self.remaining == 1 {
                let mut trailing = [0u8; 1];
                ensure!(self.input.read(&mut trailing)? == 0, "FACT_ARCHIVE_LENGTH");
                self.digest.update(b"]");
                ensure!(
                    format!("{:x}", self.digest.clone().finalize()) == self.checksum,
                    "FACT_ARCHIVE_CHECKSUM"
                );
            }
            Ok(FactRecord {
                fact,
                body: String::from_utf8(bytes)?,
            })
        })();
        self.remaining = if result.is_err() {
            0
        } else {
            self.remaining - 1
        };
        Some(result)
    }
}

impl Iterator for FactIterator {
    type Item = Result<Fact>;
    fn next(&mut self) -> Option<Self::Item> {
        self.next_record().map(|r| r.map(|record| record.fact))
    }
}

/// Split by encoded bytes as well as row count; never construct an oversized protobuf chunk.
pub struct FactChunks {
    records: FactIterator,
    pending: Option<crate::worker::Fact>,
    failed: bool,
}
impl FactArchive {
    pub fn chunks(&self) -> Result<FactChunks> {
        Ok(FactChunks {
            records: self.iter()?,
            pending: None,
            failed: false,
        })
    }
}
impl Iterator for FactChunks {
    type Item = Result<Vec<crate::worker::Fact>>;
    fn next(&mut self) -> Option<Self::Item> {
        if self.failed {
            return None;
        }
        let result = (|| {
            let mut chunk = Vec::with_capacity(64);
            let mut bytes = 0usize;
            while chunk.len() < 64 {
                let fact = if let Some(fact) = self.pending.take() {
                    fact
                } else if let Some(fact) = self.records.next() {
                    crate::fact_to_wire(&fact?)?
                } else {
                    break;
                };
                let length = fact.encoded_len() + 10;
                ensure!(length <= MAX_FRAME - 4096, "FACT_FRAME_BUDGET");
                if bytes + length > MAX_FRAME - 4096 {
                    self.pending = Some(fact);
                    break;
                }
                bytes += length;
                chunk.push(fact);
            }
            Ok(chunk)
        })();
        match result {
            Ok(chunk) if chunk.is_empty() => None,
            Ok(chunk) => Some(Ok(chunk)),
            Err(error) => {
                self.failed = true;
                Some(Err(error))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::{FactKind, Owner, digest};
    fn fact(id: &str) -> Fact {
        serde_json::from_value(serde_json::json!({"id":id,"kind":FactKind::Declaration,
            "owner":Owner{world:"w".into(),producer:"p".into(),kind:"source".into(),id:"A.java".into()},
            "origin":"javac","name":"中文😀"})).unwrap()
    }
    #[test]
    fn verified_records_preserve_canonical_body_and_reject_corruption() {
        let expected = fact("canonical");
        let mut builder = FactArchiveBuilder::new(10240, 3).unwrap();
        builder.push(&expected).unwrap();
        let mut archive = builder.seal().unwrap();
        let (decoded, body) = archive
            .records()
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
            .into_parts();
        assert_eq!(decoded, expected);
        assert_eq!(body, serde_json::to_string(&expected).unwrap());
        archive.checksum = "invalid".into();
        assert!(
            archive
                .records()
                .unwrap()
                .next()
                .unwrap()
                .err()
                .unwrap()
                .to_string()
                .contains("FACT_ARCHIVE_CHECKSUM")
        );
    }
    #[test]
    fn streaming_digest_preserves_order_and_pins_owned_records() {
        let facts = vec![fact("b"), fact("a"), fact("b")];
        let mut builder = FactArchiveBuilder::new(10240, 3).unwrap();
        for f in &facts {
            builder.push(f).unwrap();
        }
        assert!(builder.push(&fact("c")).is_err());
        let archive = builder.seal().unwrap();
        assert_eq!(archive.checksum, digest(&facts));
        let path = archive.path().to_owned();
        let pinned = archive.clone();
        drop(archive);
        assert!(path.is_file());
        assert_eq!(
            pinned.iter().unwrap().collect::<Result<Vec<_>>>().unwrap(),
            facts
        );
        drop(pinned);
        assert!(!path.exists());
    }
    #[test]
    fn same_length_tampering_is_detected_before_final_record_is_returned() {
        let mut builder = FactArchiveBuilder::new(10240, 1).unwrap();
        builder.push(&fact("a")).unwrap();
        let archive = builder.seal().unwrap();
        let mut bytes = std::fs::read(archive.path()).unwrap();
        let key = b"\"id\":\"a\"";
        let position = bytes.windows(key.len()).position(|s| s == key).unwrap();
        bytes[position + key.len() - 2] = b'z';
        std::fs::write(archive.path(), bytes).unwrap();
        assert!(archive.iter().unwrap().next().unwrap().is_err());
    }
    #[test]
    fn large_rows_split_by_wire_bytes_without_dropping_multiplicity() {
        let mut builder = FactArchiveBuilder::new(16 * 1024 * 1024, 3).unwrap();
        for id in ["a", "b", "b"] {
            let mut f = fact(id);
            f.attributes.insert(
                "payload".into(),
                serde_json::json!("x".repeat(3 * 1024 * 1024)),
            );
            builder.push(&f).unwrap();
        }
        let archive = builder.seal().unwrap();
        let chunks = archive
            .chunks()
            .unwrap()
            .collect::<Result<Vec<_>>>()
            .unwrap();
        assert_eq!(chunks.iter().map(Vec::len).collect::<Vec<_>>(), vec![2, 1]);
        for (seq, facts) in chunks.iter().enumerate() {
            let frame = crate::envelope(
                "attempt",
                crate::worker::envelope::Body::Chunk(crate::worker::Chunk {
                    seq: seq as u64,
                    facts: facts.clone(),
                }),
            );
            assert!(frame.encoded_len() <= MAX_FRAME);
        }
        assert_eq!(
            chunks
                .into_iter()
                .flatten()
                .map(|f| f.id)
                .collect::<Vec<_>>(),
            vec!["a", "b", "b"]
        );
    }
    #[test]
    fn truncated_and_oversized_records_are_rejected() {
        let mut builder = FactArchiveBuilder::new(10240, 2).unwrap();
        builder.push(&fact("a")).unwrap();
        let archive = builder.seal().unwrap();
        File::options()
            .write(true)
            .open(archive.path())
            .unwrap()
            .set_len(5)
            .unwrap();
        assert!(archive.iter().is_err());
        let mut builder = FactArchiveBuilder::new(5, 1).unwrap();
        assert!(builder.push(&fact("b")).is_err());
        assert_eq!(builder.seal().unwrap().count, 0);
    }
}
