//! Validate exact archive bytes under an owned root. Language envelopes stay in their worker.
use crate::{archive, graph::Row};
use anyhow::{Context, Result, ensure};
use codeana_contracts::graph_delivery::{Descriptor, OutputLimits};
use codeana_model::{AnalysisContext, DomainError, ErrorCode};
use sha2::{Digest, Sha256};
use std::{
    ffi::CString,
    fs::File,
    io::{BufReader, Read},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::{ffi::OsStrExt, fs::MetadataExt},
    },
    path::{Component, Path},
};

/// Created only after EOF, declared count/size and the original-byte SHA all match.
#[derive(Debug)]
pub struct VerifiedArchive {
    count: u64,
    bytes: u64,
    checksum: String,
}
impl VerifiedArchive {
    pub fn count(&self) -> u64 {
        self.count
    }
    pub fn bytes(&self) -> u64 {
        self.bytes
    }
    pub fn checksum(&self) -> &str {
        &self.checksum
    }
}
struct OwnedFile {
    parent: File,
    name: CString,
    file: File,
}
impl OwnedFile {
    fn open(root: &Path, path: &Path) -> Result<Self> {
        let physical_root = root.canonicalize()?;
        ensure!(
            path.is_absolute(),
            DomainError::new(ErrorCode::GraphArchivePath, "absolute path required")
        );
        let relative = path
            .strip_prefix(root)
            .or_else(|_| path.strip_prefix(&physical_root))
            .context(DomainError::new(
                ErrorCode::GraphArchivePath,
                "outside owned root",
            ))?;
        ensure!(
            relative.components().count() <= 64,
            DomainError::new(ErrorCode::GraphArchivePath, "archive path depth exceeds 64")
        );
        let parts = relative
            .components()
            .map(|part| match part {
                Component::Normal(name) => Ok(name),
                _ => Err(anyhow::anyhow!(DomainError::new(
                    ErrorCode::GraphArchivePath,
                    "non-normal path"
                ))),
            })
            .collect::<Result<Vec<_>>>()?;
        ensure!(
            !parts.is_empty(),
            DomainError::new(ErrorCode::GraphArchivePath, "file required")
        );
        let mut parent = File::open(physical_root)?;
        for part in &parts[..parts.len() - 1] {
            let name = CString::new(part.as_bytes())?;
            // SAFETY: parent owns the directory FD and name is a NUL-terminated component.
            let fd = unsafe {
                libc::openat(
                    parent.as_raw_fd(),
                    name.as_ptr(),
                    libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                )
            };
            ensure!(
                fd >= 0,
                DomainError::new(
                    ErrorCode::GraphArchivePath,
                    std::io::Error::last_os_error().to_string()
                )
            );
            // SAFETY: successful openat transfers a new uniquely owned FD.
            parent = unsafe { File::from_raw_fd(fd) };
        }
        let name = CString::new(parts.last().unwrap().as_bytes())?;
        // NONBLOCK prevents a forged FIFO from hanging before its type can be checked.
        // SAFETY: the live parent FD anchors the final component; no symlink is followed.
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        };
        ensure!(
            fd >= 0,
            DomainError::new(
                ErrorCode::GraphArchivePath,
                std::io::Error::last_os_error().to_string()
            )
        );
        // SAFETY: successful openat returned an owned FD.
        let file = unsafe { File::from_raw_fd(fd) };
        ensure!(
            file.metadata()?.is_file(),
            DomainError::new(ErrorCode::GraphArchiveSize, "regular file required")
        );
        Ok(Self { parent, name, file })
    }
    fn remove(&self) -> Result<()> {
        // Open the anchored name again and compare normalized Rust metadata. This avoids
        // platform-specific dev_t/ino_t casts and refuses replacement files/symlinks.
        // SAFETY: parent/name are live; successful openat returns a uniquely owned FD.
        let fd = unsafe {
            libc::openat(
                self.parent.as_raw_fd(),
                self.name.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        };
        ensure!(
            fd >= 0,
            DomainError::new(
                ErrorCode::GraphArchiveRelease,
                std::io::Error::last_os_error().to_string()
            )
        );
        // SAFETY: openat succeeded and ownership is transferred exactly once.
        let current = unsafe { File::from_raw_fd(fd) };
        let current = current.metadata()?;
        let original = self.file.metadata()?;
        ensure!(
            current.is_file() && current.dev() == original.dev() && current.ino() == original.ino(),
            DomainError::new(ErrorCode::GraphArchiveRelease, "owned file changed")
        );
        // SAFETY: unlinkat is restricted to the anchored parent and checked final name.
        ensure!(
            unsafe { libc::unlinkat(self.parent.as_raw_fd(), self.name.as_ptr(), 0) } == 0,
            DomainError::new(
                ErrorCode::GraphArchiveRelease,
                std::io::Error::last_os_error().to_string()
            )
        );
        Ok(())
    }
}
#[allow(clippy::too_many_arguments)]
pub fn read_descriptor(
    descriptor: &Descriptor,
    limits: OutputLimits,
    root: &Path,
    consume: bool,
    budget: Option<&AnalysisContext>,
    begin: impl FnOnce() -> Result<()>,
    mut emit: impl FnMut(Row) -> Result<()>,
    check: impl Fn() -> Result<()>,
) -> Result<VerifiedArchive> {
    check()?;
    ensure!(
        descriptor.path.as_os_str().len() <= 16 * 1024,
        DomainError::new(ErrorCode::GraphArchivePath, "archive path byte budget")
    );
    ensure!(
        descriptor.format == "GRA1"
            && descriptor.count <= limits.facts
            && descriptor.bytes >= 4
            && descriptor.bytes <= limits.bytes,
        DomainError::new(ErrorCode::GraphArchiveBudget, "")
    );
    if let Some(budget) = budget {
        budget
            .charge(
                1,
                1,
                8192 + (descriptor.path.as_os_str().len() as u64).saturating_mul(2),
            )
            .map_err(DomainError::analysis)?;
    }
    let owned = OwnedFile::open(root, &descriptor.path)?;
    ensure!(
        owned.file.metadata()?.len() == descriptor.bytes,
        DomainError::new(ErrorCode::GraphArchiveSize, "")
    );
    let mut reader = BufReader::new(&owned.file);
    let mut magic = [0; 4];
    reader.read_exact(&mut magic)?;
    ensure!(
        magic == archive::MAGIC,
        DomainError::new(ErrorCode::GraphArchiveFormat, "")
    );
    let mut digest = Sha256::new();
    digest.update(magic);
    begin()?;
    for position in 0..descriptor.count {
        if position % 256 == 0 {
            check()?;
        }
        let row = archive::read_row_with_budget(&mut reader, &mut digest, budget)?
            .context(DomainError::new(ErrorCode::GraphArchiveTruncated, ""))?;
        emit(row)?;
    }
    let mut trailing = [0; 1];
    ensure!(
        reader.read(&mut trailing)? == 0,
        DomainError::new(ErrorCode::GraphArchiveEof, "")
    );
    let checksum = format!("{:x}", digest.finalize());
    ensure!(
        checksum == descriptor.sha256,
        DomainError::new(ErrorCode::GraphArchiveChecksum, "")
    );
    check()?;
    drop(reader);
    if consume {
        owned.remove()?;
    }
    Ok(VerifiedArchive {
        count: descriptor.count,
        bytes: descriptor.bytes,
        checksum,
    })
}

#[cfg(test)]
#[path = "reader_tests.rs"]
mod tests;
