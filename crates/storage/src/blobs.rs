//! File I/O is bounded independently of the short GC/catalog coordination lock.
use super::*;
use std::sync::Condvar;
#[derive(Default)]
pub(super) struct IoLimit {
    active: Mutex<usize>,
    ready: Condvar,
}
struct IoPermit<'a>(&'a IoLimit);
impl IoLimit {
    fn acquire(&self) -> IoPermit<'_> {
        let mut n = self.active.lock().unwrap();
        while *n >= 2 {
            n = self.ready.wait(n).unwrap();
        }
        *n += 1;
        IoPermit(self)
    }
}
impl Drop for IoPermit<'_> {
    fn drop(&mut self) {
        *self.0.active.lock().unwrap() -= 1;
        self.0.ready.notify_one();
    }
}
pub struct BlobPins {
    hashes: Vec<String>,
    coord: Arc<Mutex<Coordination>>,
}
impl Drop for BlobPins {
    fn drop(&mut self) {
        let mut lock = self.coord.lock().unwrap();
        for hash in &self.hashes {
            if let Some(n) = lock.active_blobs.get_mut(hash) {
                *n -= 1;
                if *n == 0 {
                    lock.active_blobs.remove(hash);
                }
            }
        }
    }
}
impl Store {
    pub fn pin_blobs<'a>(&self, hashes: impl IntoIterator<Item = &'a String>) -> BlobPins {
        let hashes = hashes
            .into_iter()
            .cloned()
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect::<Vec<_>>();
        let mut lock = self.coord.lock().unwrap();
        for h in &hashes {
            *lock.active_blobs.entry(h.clone()).or_default() += 1;
        }
        BlobPins {
            hashes,
            coord: self.coord.clone(),
        }
    }
    pub fn put_blobs(&self, blobs: &BTreeMap<String, Vec<u8>>) -> Result<()> {
        let _pins = self.pin_blobs(blobs.keys());
        self.coord
            .lock()
            .unwrap()
            .staged_blobs
            .extend(blobs.keys().cloned().map(|h| (h, now())));
        for (h, b) in blobs {
            let _io = self.blob_io.acquire();
            ensure!(
                hash(b) == *h,
                DomainError::new(ErrorCode::BlobHashMismatch, "")
            );
            let p = self.home.join("blobs").join(h);
            if p.exists() {
                ensure!(
                    hash_file(&p)? == *h,
                    DomainError::new(ErrorCode::CorruptBlob, "")
                );
            } else {
                durable_write(&p, b)?;
            }
        }
        if !blobs.is_empty() {
            sync_dir(&self.home.join("blobs"))?;
        }
        Ok(())
    }
    pub fn put_blob_files<T: AsRef<Path> + Sync>(&self, blobs: &BTreeMap<String, T>) -> Result<()> {
        let _pins = self.pin_blobs(blobs.keys());
        self.coord
            .lock()
            .unwrap()
            .staged_blobs
            .extend(blobs.keys().cloned().map(|h| (h, now())));
        let entries = blobs.iter().collect::<Vec<_>>();
        let next = std::sync::atomic::AtomicUsize::new(0);
        let diagnostics = codeana_contracts::performance::Capture::current();
        std::thread::scope(|scope| -> Result<()> {
            let handles = (0..2.min(entries.len()))
                .map(|_| {
                    scope.spawn(|| {
                        diagnostics.sync(|| -> Result<()> {
                            while let Some((hash, source)) =
                                entries.get(next.fetch_add(1, std::sync::atomic::Ordering::Relaxed))
                            {
                                let _io = self.blob_io.acquire();
                                self.put_blob_file(hash, source.as_ref())?;
                            }
                            Ok(())
                        })
                    })
                })
                .collect::<Vec<_>>();
            let mut failure = None;
            for handle in handles {
                match handle.join() {
                    Ok(Ok(())) => {}
                    Ok(Err(e)) => {
                        failure.get_or_insert(e);
                    }
                    Err(_) => {
                        failure.get_or_insert_with(|| anyhow::anyhow!("BLOB_IO_PANIC"));
                    }
                }
            }
            if let Some(e) = failure {
                return Err(e);
            }
            Ok(())
        })?;
        if !blobs.is_empty() {
            sync_dir(&self.home.join("blobs"))?;
        }
        Ok(())
    }
    fn put_blob_file(&self, expected: &str, source: &Path) -> Result<()> {
        use codeana_contracts::performance::{Metric, measure};
        ensure!(
            expected.len() == 64 && expected.bytes().all(|b| b.is_ascii_hexdigit()),
            DomainError::new(ErrorCode::InvalidBlobId, "")
        );
        let destination = self.home.join("blobs").join(expected);
        if destination.exists() {
            ensure!(
                measure(
                    Metric::BlobHash,
                    std::fs::metadata(&destination)?.len(),
                    false,
                    || hash_file(&destination)
                )? == expected,
                DomainError::new(ErrorCode::CorruptBlob, "")
            );
            ensure!(
                measure(
                    Metric::BlobHash,
                    std::fs::metadata(source)?.len(),
                    false,
                    || hash_file(source)
                )? == expected,
                DomainError::new(ErrorCode::BlobHashMismatch, "")
            );
            return Ok(());
        }
        let temporary = destination.with_file_name(new_id("write"));
        let result = (|| -> Result<()> {
            use sha2::{Digest, Sha256};
            use std::io::{Read, Write};
            let mut input = std::fs::File::open(source)?;
            let mut output = std::fs::OpenOptions::new()
                .create_new(true)
                .write(true)
                .open(&temporary)?;
            let digest = measure(
                Metric::BlobCopy,
                input.metadata()?.len(),
                false,
                || -> Result<_> {
                    let mut digest = Sha256::new();
                    let mut buffer = [0u8; 65536];
                    loop {
                        let n = input.read(&mut buffer)?;
                        if n == 0 {
                            break;
                        }
                        digest.update(&buffer[..n]);
                        output.write_all(&buffer[..n])?;
                    }
                    Ok(format!("{:x}", digest.finalize()))
                },
            )?;
            ensure!(
                digest == expected,
                DomainError::new(ErrorCode::BlobHashMismatch, "")
            );
            measure(
                Metric::BlobFileSync,
                output.metadata()?.len(),
                false,
                || output.sync_all(),
            )?;
            // The source was hashed while copying. Publication independently rechecks durable bytes.
            std::fs::rename(&temporary, &destination)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(&temporary);
        }
        result
    }
    pub fn blob(&self, h: &str) -> Result<Vec<u8>> {
        ensure!(
            h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit()),
            DomainError::new(ErrorCode::InvalidBlobId, "")
        );
        let b = std::fs::read(self.home.join("blobs").join(h))?;
        ensure!(hash(&b) == h, DomainError::new(ErrorCode::CorruptBlob, ""));
        Ok(b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_hash_remains_pinned_until_last_owner_and_gc_can_run_during_io() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let store = Store::open(directory.path())?;
        let bytes = b"shared".to_vec();
        let hash = hash(&bytes);
        store.put_blobs(&BTreeMap::from([(hash.clone(), bytes)]))?;
        let first = store.pin_blobs([&hash]);
        let second = store.pin_blobs([&hash]);
        store.coord.lock().unwrap().staged_blobs.clear();
        std::thread::scope(|scope| scope.spawn(|| store.gc(true)).join().unwrap()).map(|_| ())?;
        drop(first);
        store.gc(true)?;
        assert!(store.home.join("blobs").join(&hash).exists());
        drop(second);
        store.gc(true)?;
        assert!(!store.home.join("blobs").join(&hash).exists());
        Ok(())
    }
    #[test]
    fn same_hash_file_batches_are_safe_under_concurrent_gc() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let store = Store::open(&directory.path().join("home"))?;
        let file = directory.path().join("source");
        let bytes = vec![42; 1 << 20];
        std::fs::write(&file, &bytes)?;
        let hash = hash(&bytes);
        let blobs = BTreeMap::from([(hash.clone(), file)]);
        let _pins = store.pin_blobs(blobs.keys());
        std::thread::scope(|scope| -> Result<()> {
            let one = scope.spawn(|| store.put_blob_files(&blobs));
            let two = scope.spawn(|| store.put_blob_files(&blobs));
            store.gc(true)?;
            one.join().unwrap()?;
            two.join().unwrap()?;
            Ok(())
        })?;
        assert_eq!(store.blob(&hash)?, bytes);
        Ok(())
    }
}
