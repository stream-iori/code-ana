//! Language-independent scratch accounting; callers select their diagnostic identity.
use anyhow::{Context, Result, ensure};
use std::{
    collections::{HashMap, HashSet},
    ffi::OsString,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::SystemTime,
};

/// Task scratch excludes published revisions and shared caches; reserves cache-write and blob-copy transients.
#[derive(Clone, Debug)]
pub struct DiskBudget {
    pub root: PathBuf,
    pub max_bytes: u64,
    captured_bytes: u64,
    input_bytes: u64,
    reserve_bytes: u64,
    directory_entries: Arc<Mutex<DirectoryEntries>>,
    budget_name: &'static str,
}
// Cache names only: every budget check still stats every live entry. Appending to a
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
    names: Arc<Vec<OsString>>,
    bytes: usize,
}
#[derive(Debug, Default)]
struct DirectoryEntries {
    listings: HashMap<PathBuf, DirectoryListing>,
    names: usize,
    bytes: usize,
    #[cfg(test)]
    reads: usize,
}
impl DirectoryEntries {
    fn remove(&mut self, path: &Path) {
        if let Some(old) = self.listings.remove(path) {
            self.names -= old.names.len();
            self.bytes -= old.bytes;
        }
    }
    fn names(&mut self, path: &Path, metadata: &std::fs::Metadata) -> Result<Arc<Vec<OsString>>> {
        let stamp = DirectoryStamp::of(metadata);
        if let Some(current) = &stamp
            && let Some(cached) = self.listings.get(path)
            && &cached.stamp == current
        {
            return Ok(cached.names.clone());
        }
        self.remove(path);
        #[cfg(test)]
        {
            self.reads += 1;
        }
        let directory = match std::fs::read_dir(path) {
            Ok(value) => value,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Arc::new(vec![]));
            }
            Err(error) => return Err(error.into()),
        };
        let mut names = vec![];
        for entry in directory {
            match entry {
                Ok(entry) => names.push(entry.file_name()),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
        }
        let bytes = path.as_os_str().len() + names.iter().map(|name| name.len()).sum::<usize>();
        let names = Arc::new(names);
        if let Some(stamp) = stamp
            && self.listings.len() < 4096
            && self.names + names.len() <= 65536
            && self.bytes + bytes <= 16 * 1024 * 1024
        {
            self.names += names.len();
            self.bytes += bytes;
            self.listings.insert(
                path.to_path_buf(),
                DirectoryListing {
                    stamp,
                    names: names.clone(),
                    bytes,
                },
            );
        }
        Ok(names)
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
        }
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
        let mut pending = vec![root.to_path_buf()];
        let mut visited = HashSet::new();
        let mut entries = self.directory_entries.lock().unwrap();
        while let Some(path) = pending.pop() {
            if skip == Some(path.as_path()) {
                continue;
            }
            let metadata = match std::fs::symlink_metadata(&path) {
                Ok(value) => value,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    entries.remove(&path);
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
                entries.remove(&path);
                total =
                    total
                        .checked_add(metadata.len())
                        .context(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::TaskScratchOverflow,
                            "",
                        ))?;
            } else if metadata.is_dir() {
                visited.insert(path.clone());
                let names = entries.names(&path, &metadata)?;
                pending.extend(names.iter().map(|name| path.join(name)));
            }
        }
        let stale: Vec<_> = entries
            .listings
            .keys()
            .filter(|path| path.starts_with(root) && !visited.contains(*path))
            .cloned()
            .collect();
        for path in stale {
            entries.remove(&path);
        }
        Ok(total)
    }
    pub fn check(&self) -> Result<()> {
        self.check_candidate(None)
    }
    pub fn check_candidate(&self, candidate: Option<&Path>) -> Result<()> {
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
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::TaskScratchOverflow,
                "",
            ))?;
        ensure!(
            total <= self.max_bytes,
            "{} bytes={total} limit={}",
            self.budget_name,
            self.max_bytes
        );
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
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
        };
        for index in 0..4112 {
            std::fs::create_dir(root.path().join(format!("dir-{index}"))).unwrap();
        }
        budget.check().unwrap();
        let uncached = {
            let cache = budget.directory_entries.lock().unwrap();
            assert!(
                cache.listings.len() <= 4096
                    && cache.names <= 65536
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
        assert_eq!(budget.directory_entries.lock().unwrap().names, 1);
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
