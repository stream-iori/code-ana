//! Language-independent scratch accounting; callers select their diagnostic identity.
use anyhow::{Context, Result, ensure};
use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::SystemTime,
};

/// Task scratch excludes published revisions and shared caches; reserves cache-write and blob-copy transients.
#[derive(Clone)]
pub struct DiskBudget {
    pub root: PathBuf,
    pub max_bytes: u64,
    captured_bytes: u64,
    input_bytes: u64,
    reserve_bytes: u64,
    directory_entries: Arc<Mutex<DirectoryEntries>>,
    budget_name: &'static str,
    analysis: Option<codeana_model::AnalysisContext>,
    scan_buffer: Arc<AtomicBool>,
}
impl std::fmt::Debug for DiskBudget {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DiskBudget")
            .field("root", &self.root)
            .field("max_bytes", &self.max_bytes)
            .field("budget_name", &self.budget_name)
            .finish_non_exhaustive()
    }
}
fn admit_scan(
    budget: Option<&codeana_model::AnalysisContext>,
    work: u64,
    bytes: u64,
) -> Result<()> {
    if let Some(budget) = budget {
        budget
            .charge(work, 0, bytes)
            .map_err(codeana_model::DomainError::analysis)?;
    }
    Ok(())
}
// Cache paths only: every budget check still stats every live entry. Appending to a
// file does not change its parent's mtime, so caching byte totals would be unsound.
#[derive(Debug, PartialEq, Eq)]
struct DirectoryStamp {
    modified: SystemTime,
    #[cfg(unix)]
    identity: (u64, u64, i64, i64),
}
impl DirectoryStamp {
    fn of(metadata: &std::fs::Metadata) -> Option<Self> {
        #[cfg(unix)]
        use std::os::unix::fs::MetadataExt;
        Some(Self {
            modified: metadata.modified().ok()?,
            #[cfg(unix)]
            identity: (
                metadata.dev(),
                metadata.ino(),
                metadata.ctime(),
                metadata.ctime_nsec(),
            ),
        })
    }
}
#[derive(Debug)]
struct DirectoryListing {
    stamp: DirectoryStamp,
    paths: Arc<Vec<PathBuf>>,
    bytes: usize,
}
#[derive(Debug, Default)]
struct DirectoryEntries {
    listings: HashMap<PathBuf, DirectoryListing>,
    paths: usize,
    bytes: usize,
    #[cfg(test)]
    reads: usize,
}
impl DirectoryEntries {
    fn remove(&mut self, path: &Path) {
        if let Some(old) = self.listings.remove(path) {
            self.paths -= old.paths.len();
            self.bytes -= old.bytes;
        }
    }
    fn paths(
        &mut self,
        path: &Path,
        metadata: &std::fs::Metadata,
        budget: Option<&codeana_model::AnalysisContext>,
        scan_buffer: &AtomicBool,
    ) -> Result<Arc<Vec<PathBuf>>> {
        let stamp = DirectoryStamp::of(metadata);
        if let Some(current) = &stamp
            && let Some(cached) = self.listings.get(path)
            && &cached.stamp == current
        {
            return Ok(cached.paths.clone());
        }
        self.remove(path);
        #[cfg(test)]
        {
            self.reads += 1;
        }
        if !scan_buffer.load(Ordering::Relaxed) {
            // Scans are serialized; each iterator closes before the next opens.
            // Keep its funded workspace capacity instead of returning quota.
            admit_scan(budget, 1, 64 << 10)?;
            scan_buffer.store(true, Ordering::Relaxed);
        }
        admit_scan(budget, 1, 256 + 2 * path.as_os_str().len() as u64)?;
        let directory = match std::fs::read_dir(path) {
            Ok(value) => value,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Arc::new(vec![]));
            }
            Err(error) => return Err(error.into()),
        };
        let mut paths = vec![];
        for entry in directory {
            admit_scan(budget, 1, 2048 + 2 * path.as_os_str().len() as u64)?;
            match entry {
                Ok(entry) => paths.push(entry.path()),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
        }
        let bytes = path.as_os_str().len()
            + paths
                .iter()
                .map(|path| path.as_os_str().len())
                .sum::<usize>();
        let paths = Arc::new(paths);
        if let Some(stamp) = stamp
            && self.listings.len() < 4096
            && self.paths + paths.len() <= 65536
            && self.bytes + bytes <= 16 * 1024 * 1024
        {
            self.paths += paths.len();
            self.bytes += bytes;
            self.listings.insert(
                path.to_path_buf(),
                DirectoryListing {
                    stamp,
                    paths: paths.clone(),
                    bytes,
                },
            );
        }
        Ok(paths)
    }
}
impl DiskBudget {
    pub fn new(
        root: PathBuf,
        max_bytes: u64,
        captured_bytes: u64,
        reserve_bytes: u64,
        budget_name: &'static str,
    ) -> Self {
        Self {
            root,
            max_bytes,
            captured_bytes,
            input_bytes: 0,
            reserve_bytes,
            directory_entries: Arc::default(),
            budget_name,
            analysis: None,
            scan_buffer: Arc::default(),
        }
    }
    pub fn with_analysis(mut self, budget: Option<codeana_model::AnalysisContext>) -> Self {
        self.analysis = budget;
        // A different account must fund its own buffer, even on a warm listing cache.
        self.scan_buffer = Arc::default();
        self
    }
    /// Reserve copied inputs before writing; the input subtree is excluded from live scans.
    pub fn include_input(&mut self, bytes: u64) -> Result<()> {
        self.input_bytes =
            self.input_bytes
                .checked_add(bytes)
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::TaskScratchOverflow,
                    "",
                ))?;
        self.check()
    }

    fn directory_bytes(&self, root: &Path, skip: Option<&Path>) -> Result<u64> {
        let mut total = 0u64;
        admit_scan(
            self.analysis.as_ref(),
            1,
            256 + 2 * root.as_os_str().len() as u64,
        )?;
        let mut pending = vec![(Arc::new(vec![root.to_path_buf()]), 0usize)];
        let mut visited = HashSet::new();
        let mut entries = self.directory_entries.lock().unwrap();
        while let Some((paths, index)) = pending.last_mut() {
            if *index == paths.len() {
                pending.pop();
                continue;
            }
            let path = &paths[*index];
            *index += 1;
            admit_scan(self.analysis.as_ref(), 1, 0)?;
            if skip == Some(path.as_path()) {
                continue;
            }
            let metadata = match std::fs::symlink_metadata(path) {
                Ok(value) => value,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    entries.remove(path);
                    continue;
                }
                Err(error) => return Err(error.into()),
            };
            if metadata.file_type().is_symlink() {
                anyhow::bail!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::TaskScratchSymlink,
                    ""
                ));
            }
            if metadata.is_file() {
                entries.remove(path);
                total =
                    total
                        .checked_add(metadata.len())
                        .context(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::TaskScratchOverflow,
                            "",
                        ))?;
            } else if metadata.is_dir() {
                admit_scan(
                    self.analysis.as_ref(),
                    1,
                    256 + 2 * path.as_os_str().len() as u64,
                )?;
                visited.insert(path.clone());
                let paths =
                    entries.paths(path, &metadata, self.analysis.as_ref(), &self.scan_buffer)?;
                // Borrow cached full paths through one Arc per directory. File
                // stats allocate no new path strings on each VFS quota check.
                admit_scan(self.analysis.as_ref(), 1, 128)?;
                pending.push((paths, 0));
            }
        }
        let mut stale = Vec::new();
        for path in entries.listings.keys() {
            admit_scan(self.analysis.as_ref(), 1, 0)?;
            if path.starts_with(root) && !visited.contains(path) {
                admit_scan(
                    self.analysis.as_ref(),
                    1,
                    256 + 2 * path.as_os_str().len() as u64,
                )?;
                stale.push(path.clone());
            }
        }
        for path in stale {
            entries.remove(&path);
        }
        Ok(total)
    }
    pub fn check(&self) -> Result<()> {
        self.check_candidate(None)
    }
    pub fn check_candidate(&self, candidate: Option<&Path>) -> Result<()> {
        self.check_candidate_with_extra(candidate, 0)
    }
    /// SQLite owns anonymous/unlinked files outside directory traversal, and
    /// reserves named-file growth before xWrite changes the visible file size.
    pub fn check_candidate_with_extra(&self, candidate: Option<&Path>, extra: u64) -> Result<()> {
        let input = self.root.join("input");
        let dynamic = self.directory_bytes(&self.root, Some(&input))?;
        let staged = candidate
            .map(|path| self.directory_bytes(path, None))
            .transpose()?
            .unwrap_or(0);
        let total = self
            .captured_bytes
            .checked_add(self.input_bytes)
            .and_then(|n| n.checked_add(self.reserve_bytes))
            .and_then(|n| n.checked_add(dynamic))
            .and_then(|n| n.checked_add(staged))
            .and_then(|n| n.checked_add(extra))
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::TaskScratchOverflow,
                "",
            ))?;
        ensure!(
            total <= self.max_bytes,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::from(self.budget_name),
                format!("bytes={total} limit={}", self.max_bytes),
            )
        );
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn repeated_disk_checks_reuse_paths_and_still_detect_file_growth() -> Result<()> {
        use codeana_model::{AnalysisContext, AnalysisLimits, DomainError, ErrorCode};
        let root = tempfile::tempdir()?;
        for index in 0..200 {
            std::fs::write(root.path().join(index.to_string()), b"x")?;
        }
        let host = AnalysisContext::new(
            AnalysisLimits {
                max_payload_bytes: 2 << 20,
                ..Default::default()
            },
            None,
        )?;
        let disk = DiskBudget::new(root.path().into(), 400, 0, 0, "HOST_SCRATCH_BUDGET")
            .with_analysis(Some(host.clone()));
        for _ in 0..50 {
            disk.check()?;
        }
        assert!(host.usage().failure.is_none());
        assert!(host.usage().payload_bytes < 2 << 20);
        assert_eq!(disk.directory_entries.lock().unwrap().reads, 1);
        std::fs::write(root.path().join("0"), vec![b'x'; 256])?;
        let error = disk.check().unwrap_err();
        assert_eq!(
            error.downcast_ref::<DomainError>().unwrap().code,
            ErrorCode::HostScratchBudget
        );
        assert!(host.usage().failure.is_none());
        Ok(())
    }
    #[test]
    fn disk_traversal_uses_the_original_host_budget_and_cancellation() -> Result<()> {
        use codeana_model::{AnalysisContext, AnalysisLimits, DomainError, ErrorCode};
        let root = tempfile::tempdir()?;
        for index in 0..32 {
            std::fs::write(root.path().join(index.to_string()), b"x")?;
        }
        let host = AnalysisContext::new(
            AnalysisLimits {
                max_payload_bytes: 32 << 10,
                ..Default::default()
            },
            None,
        )?;
        let disk = DiskBudget::new(root.path().into(), 1 << 20, 0, 0, "HOST_SCRATCH_BUDGET")
            .with_analysis(Some(host.clone()));
        let error = disk.check().unwrap_err();
        assert_eq!(
            error.downcast_ref::<DomainError>().unwrap().code,
            ErrorCode::AnalysisResourceLimit
        );
        assert!(host.usage().payload_bytes <= 32 << 10);
        assert!(disk.directory_entries.lock().unwrap().listings.is_empty());
        let host = AnalysisContext::new(AnalysisLimits::default(), None)?;
        host.cancel();
        let error = disk.with_analysis(Some(host)).check().unwrap_err();
        assert_eq!(
            error.downcast_ref::<DomainError>().unwrap().code,
            ErrorCode::Cancelled
        );
        Ok(())
    }
    #[test]
    fn task_disk_listing_cache_is_bounded_and_fallback_still_counts() {
        let root = tempfile::tempdir().unwrap();
        let budget = DiskBudget {
            root: root.path().to_path_buf(),
            max_bytes: 10,
            captured_bytes: 0,
            input_bytes: 0,
            reserve_bytes: 0,
            directory_entries: Arc::default(),
            budget_name: "JAVA_SCRATCH_BUDGET",
            analysis: None,
            scan_buffer: Arc::default(),
        };
        for index in 0..4112 {
            std::fs::create_dir(root.path().join(format!("dir-{index}"))).unwrap();
        }
        budget.check().unwrap();
        let uncached = {
            let cache = budget.directory_entries.lock().unwrap();
            assert!(
                cache.listings.len() <= 4096
                    && cache.paths <= 65536
                    && cache.bytes <= 16 * 1024 * 1024
            );
            (0..4112)
                .map(|index| root.path().join(format!("dir-{index}")))
                .find(|path| !cache.listings.contains_key(path))
                .unwrap()
        };
        let file = uncached.join("growth");
        std::fs::File::create(&file).unwrap().set_len(11).unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        std::fs::remove_file(file).unwrap();
        budget.check().unwrap();
    }

    #[test]
    fn task_disk_listing_cache_keeps_live_sizes_and_nested_changes() {
        let root = tempfile::tempdir().unwrap();
        let budget = DiskBudget {
            root: root.path().to_path_buf(),
            max_bytes: 10,
            captured_bytes: 0,
            input_bytes: 0,
            reserve_bytes: 0,
            directory_entries: Arc::default(),
            budget_name: "JAVA_SCRATCH_BUDGET",
            analysis: None,
            scan_buffer: Arc::default(),
        };
        let nested = root.path().join("nested");
        std::fs::create_dir(&nested).unwrap();
        let file = nested.join("data");
        std::fs::File::create(&file).unwrap().set_len(5).unwrap();
        budget.check().unwrap();
        let reads = budget.directory_entries.lock().unwrap().reads;
        for _ in 0..20 {
            budget.clone().check().unwrap();
        }
        assert_eq!(budget.directory_entries.lock().unwrap().reads, reads);
        // Appending an existing file leaves the directory listing unchanged.
        std::fs::OpenOptions::new()
            .write(true)
            .open(&file)
            .unwrap()
            .set_len(11)
            .unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        assert_eq!(budget.directory_entries.lock().unwrap().reads, reads);
        std::fs::OpenOptions::new()
            .write(true)
            .open(&file)
            .unwrap()
            .set_len(5)
            .unwrap();
        let deeper = nested.join("deeper");
        std::fs::create_dir(&deeper).unwrap();
        std::fs::File::create(deeper.join("more"))
            .unwrap()
            .set_len(6)
            .unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        std::fs::remove_dir_all(&deeper).unwrap();
        budget.check().unwrap();
        assert!(
            !budget
                .directory_entries
                .lock()
                .unwrap()
                .listings
                .contains_key(&deeper)
        );
        std::fs::remove_file(&file).unwrap();
        std::os::unix::fs::symlink(root.path(), &file).unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("TASK_SCRATCH_SYMLINK")
        );
    }

    #[test]
    fn task_disk_listing_cache_accounts_candidate_growth_with_shared_clones() {
        let root = tempfile::tempdir().unwrap();
        let candidate = tempfile::tempdir().unwrap();
        let budget = DiskBudget {
            root: root.path().to_path_buf(),
            max_bytes: 15,
            captured_bytes: 2,
            input_bytes: 0,
            reserve_bytes: 3,
            directory_entries: Arc::default(),
            budget_name: "JAVA_SCRATCH_BUDGET",
            analysis: None,
            scan_buffer: Arc::default(),
        };
        std::fs::File::create(root.path().join("result"))
            .unwrap()
            .set_len(5)
            .unwrap();
        let database = candidate.path().join("facts.db");
        std::fs::File::create(&database)
            .unwrap()
            .set_len(5)
            .unwrap();
        budget.check_candidate(Some(candidate.path())).unwrap();
        let before = budget.directory_entries.lock().unwrap().reads;
        budget
            .clone()
            .check_candidate(Some(candidate.path()))
            .unwrap();
        assert_eq!(budget.directory_entries.lock().unwrap().reads, before);
        std::fs::OpenOptions::new()
            .write(true)
            .open(&database)
            .unwrap()
            .set_len(6)
            .unwrap();
        assert!(
            budget
                .check_candidate(Some(candidate.path()))
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        std::fs::remove_file(&database).unwrap();
        budget.check_candidate(Some(candidate.path())).unwrap();
        assert_eq!(budget.directory_entries.lock().unwrap().paths, 1);
    }

    #[test]
    fn task_disk_budget_counts_captures_results_and_candidate_without_double_counting_input() {
        let root = tempfile::tempdir().unwrap();
        let candidate = tempfile::tempdir().unwrap();
        std::fs::create_dir(root.path().join("input")).unwrap();
        std::fs::File::create(root.path().join("input/frozen.jar"))
            .unwrap()
            .set_len(4096)
            .unwrap();
        let result = root.path().join("result.bin");
        std::fs::File::create(&result)
            .unwrap()
            .set_len(8192)
            .unwrap();
        let budget = DiskBudget {
            root: root.path().to_path_buf(),
            max_bytes: 2048 + 4096 + 8192 + 64,
            captured_bytes: 2048,
            input_bytes: 4096,
            reserve_bytes: 64,
            directory_entries: Arc::default(),
            budget_name: "JAVA_SCRATCH_BUDGET",
            analysis: None,
            scan_buffer: Arc::default(),
        };
        budget.check().unwrap();
        std::fs::File::create(candidate.path().join("facts.db"))
            .unwrap()
            .set_len(1)
            .unwrap();
        assert!(
            budget
                .check_candidate(Some(candidate.path()))
                .unwrap_err()
                .to_string()
                .contains("JAVA_SCRATCH_BUDGET")
        );
        std::fs::remove_file(result).unwrap();
        budget.check_candidate(Some(candidate.path())).unwrap();
        std::os::unix::fs::symlink(candidate.path(), root.path().join("unowned-link")).unwrap();
        assert!(
            budget
                .check()
                .unwrap_err()
                .to_string()
                .contains("TASK_SCRATCH_SYMLINK")
        );
    }
}
