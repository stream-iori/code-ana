//! Blobs persistence over the shared catalog coordination.
use super::*;
impl Store {
    pub fn put_blobs(&self, blobs: &BTreeMap<String, Vec<u8>>) -> Result<()> {
        let mut guard = self.coord.lock().unwrap();
        guard
            .staged_blobs
            .extend(blobs.keys().cloned().map(|h| (h, now())));
        for (h, b) in blobs {
            ensure!(
                hash(b) == *h,
                codeana_model::DomainError::new(codeana_model::ErrorCode::BlobHashMismatch, "")
            );
            let p = self.home.join("blobs").join(h);
            if p.exists() {
                ensure!(
                    hash(std::fs::read(p)?) == *h,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::CorruptBlob, "")
                );
            } else {
                durable_write(&p, b)?;
            }
        }
        // Reused files may have been renamed by a failed file-backed batch.
        if !blobs.is_empty() {
            sync_dir(&self.home.join("blobs"))?;
        }
        Ok(())
    }
    /// Persist captured files without materializing dependency archives in memory.
    pub fn put_blob_files<T: AsRef<Path>>(&self, blobs: &BTreeMap<String, T>) -> Result<()> {
        let mut guard = self.coord.lock().unwrap();
        guard
            .staged_blobs
            .extend(blobs.keys().cloned().map(|h| (h, now())));
        for (hash, source) in blobs {
            ensure!(
                hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit()),
                codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidBlobId, "")
            );
            let destination = self.home.join("blobs").join(hash);
            if destination.exists() {
                ensure!(
                    codeana_contracts::performance::measure(
                        codeana_contracts::performance::Metric::BlobHash,
                        std::fs::metadata(&destination)?.len(),
                        false,
                        || hash_file(&destination)
                    )? == *hash,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::CorruptBlob, "")
                );
                ensure!(
                    codeana_contracts::performance::measure(
                        codeana_contracts::performance::Metric::BlobHash,
                        std::fs::metadata(source.as_ref())?.len(),
                        false,
                        || hash_file(source.as_ref())
                    )? == *hash,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::BlobHashMismatch, "")
                );
                continue;
            }
            let temporary = destination.with_file_name(new_id("write"));
            let result = (|| -> Result<()> {
                let mut input = std::fs::File::open(source.as_ref())?;
                let mut output = std::fs::OpenOptions::new()
                    .create_new(true)
                    .write(true)
                    .open(&temporary)?;
                use codeana_contracts::performance::{Metric, measure};
                measure(Metric::BlobCopy, input.metadata()?.len(), false, || {
                    std::io::copy(&mut input, &mut output)
                })?;
                measure(
                    Metric::BlobFileSync,
                    output.metadata()?.len(),
                    false,
                    || output.sync_all(),
                )?;
                ensure!(
                    measure(Metric::BlobHash, output.metadata()?.len(), false, || {
                        hash_file(&temporary)
                    })? == *hash,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::BlobHashMismatch, "")
                );
                std::fs::rename(&temporary, &destination)?;
                Ok(())
            })();
            if result.is_err() {
                let _ = std::fs::remove_file(&temporary);
            }
            result?;
        }
        // Every file is durable before rename; persist the batch's directory
        // entries before a caller can publish references. Also sync on reuse:
        // an earlier failed batch may have left renamed, unreferenced files.
        if !blobs.is_empty() {
            sync_dir(&self.home.join("blobs"))?;
        }
        Ok(())
    }
    pub fn blob(&self, h: &str) -> Result<Vec<u8>> {
        ensure!(
            h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit()),
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidBlobId, "")
        );
        let b = std::fs::read(self.home.join("blobs").join(h))?;
        ensure!(
            hash(&b) == h,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CorruptBlob, "")
        );
        Ok(b)
    }
}
