//! Bounded protobuf row archives, shared by Java delivery and Rust staging.
use crate::graph::Row;
use anyhow::{Context, Result, ensure};
use prost::Message;
use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use std::{
    fs::File,
    io::{BufReader, BufWriter},
    path::{Path, PathBuf},
    sync::Arc,
};

pub const MAGIC: [u8; 4] = *b"GRA1";
pub const RECORD_LIMIT: usize = 8 << 20;

/// The checksum covers exact frame lengths and protobuf bytes, including order.
pub fn update_digest(digest: &mut Sha256, bytes: &[u8]) {
    digest.update((bytes.len() as u32).to_be_bytes());
    digest.update(bytes);
}
pub fn write_row(writer: &mut impl Write, row: &Row, digest: &mut Sha256) -> Result<usize> {
    let bytes = row.encode_to_vec();
    ensure!(
        !bytes.is_empty() && bytes.len() <= RECORD_LIMIT,
        "GRAPH_RECORD_BUDGET"
    );
    writer.write_all(&(bytes.len() as u32).to_be_bytes())?;
    writer.write_all(&bytes)?;
    update_digest(digest, &bytes);
    Ok(bytes.len() + 4)
}
pub fn read_row(reader: &mut impl Read, digest: &mut Sha256) -> Result<Option<Row>> {
    let mut length = [0; 4];
    let n = reader.read(&mut length[..1])?;
    if n == 0 {
        return Ok(None);
    }
    reader
        .read_exact(&mut length[1..])
        .context("GRAPH_TRUNCATED_LENGTH")?;
    let size = u32::from_be_bytes(length) as usize;
    ensure!(size > 0 && size <= RECORD_LIMIT, "GRAPH_RECORD_BUDGET");
    let mut bytes = vec![0; size];
    reader
        .read_exact(&mut bytes)
        .context("GRAPH_TRUNCATED_RECORD")?;
    crate::validate_graph_row_wire(&bytes)?;
    let row = Row::decode(bytes.as_slice())?;
    ensure!(row.table != 0, "GRAPH_TABLE_REQUIRED");
    update_digest(digest, &bytes);
    Ok(Some(row))
}

#[derive(Clone)]
pub struct GraphArchive {
    _directory: Arc<tempfile::TempDir>,
    path: PathBuf,
    pub count: u64,
    pub bytes: u64,
    pub checksum: String,
}
pub struct Builder {
    archive: GraphArchive,
    writer: BufWriter<File>,
    digest: Sha256,
    max_bytes: u64,
    max_rows: u64,
}
impl Builder {
    pub fn new(root: Option<&Path>, max_bytes: u64, max_rows: u64) -> Result<Self> {
        let directory = Arc::new(match root {
            Some(p) => tempfile::Builder::new()
                .prefix("codeana-rows-")
                .tempdir_in(p)?,
            None => tempfile::Builder::new().prefix("codeana-rows-").tempdir()?,
        });
        let path = directory.path().join("graph.rows");
        let mut writer = BufWriter::new(File::create(&path)?);
        writer.write_all(&MAGIC)?;
        let mut digest = Sha256::new();
        digest.update(MAGIC);
        Ok(Self {
            archive: GraphArchive {
                _directory: directory,
                path,
                count: 0,
                bytes: 4,
                checksum: String::new(),
            },
            writer,
            digest,
            max_bytes,
            max_rows,
        })
    }
    pub fn push(&mut self, row: &Row) -> Result<()> {
        let size = row.encoded_len() + 4;
        ensure!(
            self.archive.count < self.max_rows
                && self.archive.bytes + size as u64 <= self.max_bytes,
            "GRAPH_ARCHIVE_BUDGET"
        );
        write_row(&mut self.writer, row, &mut self.digest)?;
        self.archive.count += 1;
        self.archive.bytes += size as u64;
        Ok(())
    }
    pub fn count(&self) -> u64 {
        self.archive.count
    }
    pub fn seal(mut self) -> Result<GraphArchive> {
        self.writer.flush()?;
        self.writer.get_ref().sync_all()?;
        self.archive.checksum = format!("{:x}", self.digest.finalize());
        Ok(self.archive)
    }
}
pub struct Records {
    reader: BufReader<File>,
    digest: Sha256,
    count: u64,
    expected_count: u64,
    expected_digest: String,
    ended: bool,
}
impl Iterator for Records {
    type Item = Result<Row>;
    fn next(&mut self) -> Option<Self::Item> {
        if self.ended {
            return None;
        }
        match read_row(&mut self.reader, &mut self.digest) {
            Ok(Some(row)) => {
                self.count += 1;
                Some(Ok(row))
            }
            Ok(None) => {
                self.ended = true;
                let actual = format!("{:x}", self.digest.clone().finalize());
                if self.count != self.expected_count || actual != self.expected_digest {
                    Some(Err(anyhow::anyhow!("GRAPH_ARCHIVE_CHECKSUM")))
                } else {
                    None
                }
            }
            Err(error) => {
                self.ended = true;
                Some(Err(error))
            }
        }
    }
}
impl GraphArchive {
    pub fn path(&self) -> &Path {
        &self.path
    }
    pub fn records(&self) -> Result<Records> {
        let mut reader = BufReader::new(File::open(&self.path)?);
        let mut magic = [0; 4];
        reader.read_exact(&mut magic)?;
        ensure!(magic == MAGIC, "GRAPH_ARCHIVE_FORMAT");
        let mut digest = Sha256::new();
        digest.update(MAGIC);
        Ok(Records {
            reader,
            digest,
            count: 0,
            expected_count: self.count,
            expected_digest: self.checksum.clone(),
            ended: false,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::graph::{Cell, Table, cell::Value};
    #[test]
    fn null_empty_and_unicode_are_distinct_and_truncation_is_rejected() -> Result<()> {
        let row = Row {
            table: Table::Node as i32,
            cells: vec![
                Cell { value: None },
                Cell {
                    value: Some(Value::Text(String::new())),
                },
                Cell {
                    value: Some(Value::Text("中文😀".into())),
                },
            ],
        };
        let mut bytes = Vec::new();
        let mut encoded = Sha256::new();
        write_row(&mut bytes, &row, &mut encoded)?;
        let mut decoded = Sha256::new();
        assert_eq!(read_row(&mut bytes.as_slice(), &mut decoded)?, Some(row));
        assert_eq!(encoded.finalize(), decoded.finalize());
        for end in 1..bytes.len() {
            assert!(read_row(&mut &bytes[..end], &mut Sha256::new()).is_err());
        }
        assert!(read_row(&mut [].as_slice(), &mut Sha256::new())?.is_none());
        Ok(())
    }
}
