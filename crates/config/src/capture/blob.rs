//! Captured source/artifact bytes own and pin their spool independently of discovery.
use super::*;
/// Immutable task-owned capture; clones pin its private spool until all consumers finish.
#[derive(Debug, Clone)]
pub struct CapturedBlob {
    path: PathBuf,
    _spool: std::sync::Arc<tempfile::TempDir>,
}
impl AsRef<Path> for CapturedBlob {
    fn as_ref(&self) -> &Path {
        &self.path
    }
}
impl CapturedBlob {
    pub fn read(&self, limit: usize) -> Result<Vec<u8>> {
        bounded_read(&self.path, limit)
    }
    pub(crate) fn bytes(spool: &std::sync::Arc<tempfile::TempDir>, bytes: &[u8]) -> Result<Self> {
        let path = spool.path().join(hash(bytes));
        if !path.exists() {
            std::fs::write(&path, bytes)?;
        }
        Ok(Self {
            path,
            _spool: spool.clone(),
        })
    }
    pub(crate) fn file(
        spool: &std::sync::Arc<tempfile::TempDir>,
        source: &Path,
        limit: usize,
    ) -> Result<(Self, String, usize)> {
        use std::io::Read;
        ensure!(
            source.metadata()?.len() <= limit as u64,
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaArtifactByteBudget, "")
        );
        let temporary = tempfile::NamedTempFile::new_in(spool.path())?;
        let mut output = temporary.reopen()?;
        let size = std::io::copy(
            &mut std::fs::File::open(source)?.take(limit as u64 + 1),
            &mut output,
        )?;
        ensure!(
            size <= limit as u64,
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaArtifactByteBudget, "")
        );
        let digest = hash_file(temporary.path())?;
        let path = spool.path().join(&digest);
        if !path.exists() {
            temporary.persist(&path)?;
        }
        Ok((
            Self {
                path,
                _spool: spool.clone(),
            },
            digest,
            size as usize,
        ))
    }
}
