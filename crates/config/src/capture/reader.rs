//! Task-owned live IO: file bytes are read once, observations are rechecked before sealing.
use super::*;
mod links;
pub use links::DeclaredLink;
use std::{
    collections::BTreeMap,
    fs::File,
    io,
    os::fd::{AsRawFd, FromRawFd},
    os::unix::{ffi::OsStrExt, fs::MetadataExt},
};

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct FileIdentity {
    dev: u64,
    ino: u64,
    bytes: u64,
    modified: i64,
    modified_ns: i64,
    changed: i64,
    changed_ns: i64,
}
impl FileIdentity {
    fn of(metadata: &std::fs::Metadata) -> Self {
        Self {
            dev: metadata.dev(),
            ino: metadata.ino(),
            bytes: metadata.len(),
            modified: metadata.mtime(),
            modified_ns: metadata.mtime_nsec(),
            changed: metadata.ctime(),
            changed_ns: metadata.ctime_nsec(),
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum ResolutionObservation {
    Link {
        path: PathBuf,
        target: PathBuf,
        real: Option<PathBuf>,
        identity: FileIdentity,
    },
    Root {
        path: PathBuf,
        real: PathBuf,
        exact: Option<PathBuf>,
    },
    File {
        path: PathBuf,
        sha256: String,
        captured: bool,
        identity: FileIdentity,
    },
    Directory {
        path: PathBuf,
        entries: Vec<(String, String)>,
    },
    Missing {
        path: PathBuf,
    },
}
struct Root {
    requested: PathBuf,
    real: PathBuf,
    directory: Option<File>,
    identity: FileIdentity,
    exact: Option<PathBuf>,
}
impl Root {
    fn open(&self, relative: &Path, directory: bool) -> Result<File> {
        use std::os::unix::fs::OpenOptionsExt;
        if let Some(anchor) = &self.directory {
            return Ok(open_relative(anchor, relative, directory)?);
        }
        // Exact external files do not pin one directory FD per dependency jar.
        let anchor = std::fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&self.real)?;
        let identity = FileIdentity::of(&anchor.metadata()?);
        ensure!(
            identity.dev == self.identity.dev && identity.ino == self.identity.ino,
            "CAPTURE_ROOT_CHANGED"
        );
        Ok(open_relative(&anchor, relative, directory)?)
    }
}
struct CachedFile {
    root: usize,
    relative: PathBuf,
    identity: FileIdentity,
    blob: Option<CapturedBlob>,
    digest: String,
    size: usize,
}
type KnownContent = (Option<CapturedBlob>, String, usize);
type DirectoryEntries = Vec<(String, String)>;
struct DirectorySnapshot {
    root: usize,
    relative: PathBuf,
    entries: DirectoryEntries,
}
pub struct SealedCapture {
    pub observations: Vec<ResolutionObservation>,
    pub blobs: Vec<(String, CapturedBlob, usize)>,
    pub(crate) unit_paths: BTreeMap<AnalysisUnitId, BTreeSet<PathBuf>>,
}
pub struct CaptureReader {
    links: links::LinkStore,
    active_unit: Option<AnalysisUnitId>,
    unit_paths: BTreeMap<AnalysisUnitId, BTreeSet<PathBuf>>,
    roots: Vec<Root>,
    files: BTreeMap<PathBuf, CachedFile>,
    by_identity: BTreeMap<FileIdentity, KnownContent>,
    directories: BTreeMap<PathBuf, DirectorySnapshot>,
    missing: BTreeMap<PathBuf, (usize, PathBuf)>,
    spool: std::sync::Arc<tempfile::TempDir>,
    started: std::time::Instant,
    deadline: std::time::Duration,
    budget: Option<ResourceBudget>,
    cancelled: Option<CaptureCancellation>,
    live_reads: usize,
    live_bytes: usize,
    metadata_nodes: usize,
    metadata_bytes: usize,
}
fn open_relative(root: &File, path: &Path, directory: bool) -> io::Result<File> {
    if path.as_os_str().len() > 16 * 1024 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "CAPTURE_PATH_BUDGET",
        ));
    }
    let parts = path.components().take(65).collect::<Vec<_>>();
    if parts.is_empty() && directory {
        return root.try_clone();
    }
    if parts.is_empty() || parts.len() > 64 || path.as_os_str().len() > 16 * 1024 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "CAPTURE_PATH_BUDGET",
        ));
    }
    let mut current = root.try_clone()?;
    for (index, part) in parts.iter().enumerate() {
        let std::path::Component::Normal(name) = part else {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "UNSAFE_CAPTURE_PATH",
            ));
        };
        let name = std::ffi::CString::new(name.as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "CAPTURE_PATH_NUL"))?;
        let is_directory = index + 1 < parts.len() || directory;
        let flags = libc::O_RDONLY
            | libc::O_CLOEXEC
            | libc::O_NOFOLLOW
            | libc::O_NONBLOCK
            | if is_directory { libc::O_DIRECTORY } else { 0 };
        // SAFETY: parent FD and NUL-terminated name remain live; ownership of the new FD transfers once.
        let fd = unsafe { libc::openat(current.as_raw_fd(), name.as_ptr(), flags) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        current = unsafe { File::from_raw_fd(fd) };
    }
    Ok(current)
}
fn directory_entries(
    directory: &File,
    max_nodes: usize,
    max_bytes: usize,
    mut check: impl FnMut(u64, u64) -> Result<()>,
) -> Result<Vec<(String, String)>> {
    // fdopendir owns its descriptor. Dup leaves the anchored root owned by the caller.
    let fd = unsafe { libc::fcntl(directory.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
    ensure!(fd >= 0, io::Error::last_os_error());
    let stream = unsafe { libc::fdopendir(fd) };
    if stream.is_null() {
        let error = io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(error.into());
    }
    struct Directory(*mut libc::DIR);
    impl Drop for Directory {
        fn drop(&mut self) {
            unsafe {
                libc::closedir(self.0);
            }
        }
    }
    let stream = Directory(stream);
    unsafe {
        libc::rewinddir(stream.0);
    }
    let mut entries = Vec::new();
    let mut retained = 0usize;
    loop {
        #[cfg(target_os = "macos")]
        unsafe {
            *libc::__error() = 0;
        }
        #[cfg(target_os = "linux")]
        unsafe {
            *libc::__errno_location() = 0;
        }
        let entry = unsafe { libc::readdir(stream.0) };
        if entry.is_null() {
            let error = io::Error::last_os_error();
            ensure!(error.raw_os_error() == Some(0), error);
            break;
        }
        let name = unsafe { std::ffi::CStr::from_ptr((*entry).d_name.as_ptr()) };
        if matches!(name.to_bytes(), b"." | b"..") {
            continue;
        }
        let bytes = name.to_bytes().len() + 128;
        retained = retained
            .checked_add(bytes)
            .context("CAPTURE_OBSERVATION_BUDGET")?;
        ensure!(
            entries.len() < max_nodes && retained <= max_bytes,
            "CAPTURE_OBSERVATION_BUDGET"
        );
        check(2, bytes as u64)?;
        let text = name.to_str().context("NON_UTF8_CAPTURE_PATH")?.to_owned();
        let mut metadata = std::mem::MaybeUninit::<libc::stat>::uninit();
        let result = unsafe {
            libc::fstatat(
                directory.as_raw_fd(),
                name.as_ptr(),
                metadata.as_mut_ptr(),
                libc::AT_SYMLINK_NOFOLLOW,
            )
        };
        ensure!(result == 0, io::Error::last_os_error());
        let metadata = unsafe { metadata.assume_init() };
        let kind = match metadata.st_mode & libc::S_IFMT {
            libc::S_IFREG => "file",
            libc::S_IFDIR => "directory",
            libc::S_IFLNK => "link",
            _ => "other",
        };
        entries.push((text, kind.into()));
    }
    entries.sort();
    Ok(entries)
}
impl CaptureReader {
    pub(crate) fn project_root(&self) -> &Path {
        &self.roots[0].requested
    }
    pub fn new(
        root: &Path,
        spool: std::sync::Arc<tempfile::TempDir>,
        started: std::time::Instant,
        deadline: std::time::Duration,
        budget: Option<ResourceBudget>,
        cancelled: Option<CaptureCancellation>,
    ) -> Result<Self> {
        let mut value = Self {
            links: links::LinkStore::default(),
            active_unit: None,
            unit_paths: BTreeMap::new(),
            roots: vec![],
            files: BTreeMap::new(),
            by_identity: BTreeMap::new(),
            directories: BTreeMap::new(),
            missing: BTreeMap::new(),
            spool,
            started,
            deadline,
            budget,
            cancelled,
            live_reads: 0,
            live_bytes: 0,
            metadata_nodes: 0,
            metadata_bytes: 0,
        };
        value.allow_root(root, None)?;
        Ok(value)
    }
    pub fn enter_unit(&mut self, unit: &AnalysisUnitId) -> Result<()> {
        self.checkpoint()?;
        ensure!(
            self.active_unit.is_none() && !unit.is_empty(),
            "CAPTURE_UNIT_SCOPE"
        );
        self.active_unit = Some(unit.clone());
        Ok(())
    }
    pub(crate) fn enter_backend(&mut self, backend: AnalysisBackend) -> Result<bool> {
        if self.active_unit.is_some() {
            return Ok(false);
        }
        self.enter_unit(&backend.as_str().into())?;
        Ok(true)
    }
    pub fn leave_unit(&mut self) {
        self.active_unit = None;
    }
    fn observe_for_unit(&mut self, path: &Path) -> Result<()> {
        let Some(unit) = self.active_unit.as_ref() else {
            return Ok(());
        };
        if self
            .unit_paths
            .get(unit)
            .is_some_and(|paths| paths.contains(path))
        {
            return Ok(());
        }
        let unit = unit.clone();
        self.admit_metadata(1, path.as_os_str().len() + unit.len() + 256)?;
        self.unit_paths
            .entry(unit)
            .or_default()
            .insert(path.to_owned());
        Ok(())
    }
    /// Plain contexts use the already enumerated workspace, with their own roots.
    pub(crate) fn share_directories(&mut self, roots: &[PathBuf]) -> Result<()> {
        use std::ops::Bound::{Excluded, Unbounded};
        let mut cursor: Option<PathBuf> = None;
        loop {
            self.checkpoint()?;
            let next = match &cursor {
                Some(path) => self
                    .directories
                    .range::<PathBuf, _>((Excluded(path), Unbounded))
                    .next(),
                None => self.directories.iter().next(),
            }
            .map(|(path, _)| path.clone());
            let Some(path) = next else {
                break;
            };
            if roots.iter().any(|root| path.starts_with(root)) {
                self.observe_for_unit(&path)?;
            }
            cursor = Some(path);
        }
        Ok(())
    }
    fn checkpoint(&self) -> Result<()> {
        ensure!(!self.links.failed, "CAPTURE_LINK_PROOF_FAILED");
        if let Some(cancelled) = &self.cancelled {
            ensure!(!cancelled(), DomainError::new(ErrorCode::Cancelled, ""));
        }
        ensure!(
            self.started.elapsed() < self.deadline,
            DomainError::new(ErrorCode::CaptureTimeout, "")
        );
        if let Some(budget) = &self.budget {
            budget.charge(1, 0)?;
        }
        Ok(())
    }
    fn admit_metadata(&mut self, nodes: usize, bytes: usize) -> Result<()> {
        let next_nodes = self
            .metadata_nodes
            .checked_add(nodes)
            .context("CAPTURE_OBSERVATION_BUDGET")?;
        let next_bytes = self
            .metadata_bytes
            .checked_add(bytes)
            .context("CAPTURE_OBSERVATION_BUDGET")?;
        ensure!(
            next_nodes <= 400_000 && next_bytes <= 64 * 1024 * 1024,
            "CAPTURE_OBSERVATION_BUDGET"
        );
        if let Some(budget) = &self.budget {
            budget.charge(nodes as u64, bytes as u64)?;
        }
        self.metadata_nodes = next_nodes;
        self.metadata_bytes = next_bytes;
        Ok(())
    }
    fn allow_root(&mut self, path: &Path, exact: Option<PathBuf>) -> Result<()> {
        use std::os::unix::fs::OpenOptionsExt;
        self.checkpoint()?;
        ensure!(path.as_os_str().len() <= 16 * 1024, "CAPTURE_PATH_BUDGET");
        if self
            .roots
            .iter()
            .any(|r| r.requested == path && r.exact == exact)
        {
            return Ok(());
        }
        let real = path.canonicalize()?;
        self.admit_metadata(1, path.as_os_str().len() + real.as_os_str().len() + 512)?;
        let directory = std::fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&real)?;
        let identity = FileIdentity::of(&directory.metadata()?);
        self.roots.push(Root {
            requested: path.into(),
            real,
            directory: if exact.is_none() {
                Some(directory)
            } else {
                None
            },
            identity,
            exact,
        });
        Ok(())
    }
    pub fn allow_directory(&mut self, path: &Path) -> Result<()> {
        self.allow_root(path, None)
    }
    pub fn allow_file(&mut self, path: &Path) -> Result<()> {
        let parent = path.parent().context("CAPTURE_INPUT_PARENT")?;
        self.allow_root(
            parent,
            Some(PathBuf::from(
                path.file_name().context("CAPTURE_INPUT_NAME")?,
            )),
        )
    }
    fn locate(&self, path: &Path) -> Result<(usize, PathBuf)> {
        ensure!(path.as_os_str().len() <= 32 * 1024, "CAPTURE_PATH_BUDGET");
        self.roots
            .iter()
            .enumerate()
            .filter_map(|(index, root)| {
                let relative = path
                    .strip_prefix(&root.requested)
                    .or_else(|_| path.strip_prefix(&root.real))
                    .ok()?;
                if root.exact.as_ref().is_some_and(|exact| exact != relative) {
                    return None;
                }
                Some((
                    root.requested.components().count(),
                    index,
                    relative.to_path_buf(),
                ))
            })
            .max_by_key(|(depth, index, _)| (*depth, *index))
            .map(|(_, index, path)| (index, path))
            .context("CAPTURE_UNDECLARED_ROOT")
    }
    pub fn file(&mut self, path: &Path, limit: usize) -> Result<(CapturedBlob, String, usize)> {
        self.checkpoint()?;
        self.observe_for_unit(path)?;
        let (root, relative) = self.resolve_link_path(path)?;
        if let Some(file) = self.files.get(path) {
            self.check_cached_location(file.root, &file.relative, root, &relative)?;
            ensure!(
                file.size <= limit,
                DomainError::new(ErrorCode::InputByteBudget, "")
            );
            let blob = file.blob.clone().context("CAPTURE_IDENTITY_ONLY_INPUT")?;
            return Ok((blob, file.digest.clone(), file.size));
        }
        self.admit_metadata(1, path.as_os_str().len() * 2 + 512)?;
        let mut input = self.roots[root].open(&relative, false)?;
        let before = input.metadata()?;
        ensure!(before.is_file(), "CAPTURE_REGULAR_FILE_REQUIRED");
        ensure!(
            before.len() <= limit as u64,
            DomainError::new(ErrorCode::InputByteBudget, "")
        );
        let identity = FileIdentity::of(&before);
        if let Some((blob, digest, size)) = self.by_identity.get(&identity).cloned() {
            let blob = blob.context("CAPTURE_IDENTITY_ONLY_INPUT")?;
            self.files.insert(
                path.into(),
                CachedFile {
                    root,
                    relative,
                    identity,
                    blob: Some(blob.clone()),
                    digest: digest.clone(),
                    size,
                },
            );
            return Ok((blob, digest, size));
        }
        let live_bytes = self
            .live_bytes
            .checked_add(usize::try_from(before.len())?.saturating_add(1))
            .context("CAPTURE_READ_BUDGET")?;
        ensure!(live_bytes <= MAX_CAPTURE_BYTES, "CAPTURE_READ_BUDGET");
        if let Some(budget) = &self.budget {
            budget.charge(1, before.len().saturating_add(1))?;
        }
        struct Checked<'a, R> {
            input: R,
            check: &'a dyn Fn() -> Result<()>,
        }
        impl<R: std::io::Read> std::io::Read for Checked<'_, R> {
            fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
                (self.check)().map_err(io::Error::other)?;
                let size = output.len().min(64 * 1024);
                self.input.read(&mut output[..size])
            }
        }
        let check = || self.checkpoint();
        let (blob, digest, size) = CapturedBlob::reader(
            &self.spool,
            Checked {
                input: &mut input,
                check: &check,
            },
            usize::try_from(before.len())?,
        )?;
        ensure!(size as u64 == before.len(), "CAPTURE_INPUT_CHANGED");
        ensure!(
            identity == FileIdentity::of(&input.metadata()?),
            "CAPTURE_INPUT_CHANGED"
        );
        self.live_bytes = live_bytes;
        self.live_reads += 1;
        self.by_identity
            .insert(identity.clone(), (Some(blob.clone()), digest.clone(), size));
        self.files.insert(
            path.into(),
            CachedFile {
                root,
                relative,
                identity,
                blob: Some(blob.clone()),
                digest: digest.clone(),
                size,
            },
        );
        Ok((blob, digest, size))
    }
    /// External credentials/tool identities are verified without retaining their contents.
    pub fn digest(&mut self, path: &Path, limit: usize) -> Result<String> {
        use sha2::{Digest, Sha256};
        use std::io::Read;
        self.checkpoint()?;
        self.observe_for_unit(path)?;
        let (root, relative) = self.resolve_link_path(path)?;
        if let Some(file) = self.files.get(path) {
            self.check_cached_location(file.root, &file.relative, root, &relative)?;
            ensure!(
                file.size <= limit,
                DomainError::new(ErrorCode::InputByteBudget, "")
            );
            return Ok(file.digest.clone());
        }
        self.admit_metadata(1, path.as_os_str().len() * 2 + 512)?;
        let mut input = self.roots[root].open(&relative, false)?;
        let before = input.metadata()?;
        ensure!(before.is_file(), "CAPTURE_REGULAR_FILE_REQUIRED");
        ensure!(
            before.len() <= limit as u64,
            DomainError::new(ErrorCode::InputByteBudget, "")
        );
        let identity = FileIdentity::of(&before);
        let (digest, size) = if let Some((_, digest, size)) = self.by_identity.get(&identity) {
            (digest.clone(), *size)
        } else {
            let live_bytes = self
                .live_bytes
                .checked_add(usize::try_from(before.len())?.saturating_add(1))
                .context("CAPTURE_READ_BUDGET")?;
            ensure!(live_bytes <= MAX_CAPTURE_BYTES, "CAPTURE_READ_BUDGET");
            if let Some(budget) = &self.budget {
                budget.charge(1, before.len().saturating_add(1))?;
            }
            let mut hasher = Sha256::new();
            let mut bytes = 0u64;
            let mut buffer = [0u8; 8192];
            loop {
                self.checkpoint()?;
                let capacity = (before.len() + 1 - bytes).min(buffer.len() as u64) as usize;
                let count = input.read(&mut buffer[..capacity])?;
                if count == 0 {
                    break;
                }
                bytes += count as u64;
                ensure!(
                    bytes <= before.len(),
                    DomainError::new(ErrorCode::InputByteBudget, "")
                );
                hasher.update(&buffer[..count]);
            }
            ensure!(
                bytes == before.len() && identity == FileIdentity::of(&input.metadata()?),
                "CAPTURE_INPUT_CHANGED"
            );
            self.live_bytes = live_bytes;
            self.live_reads += 1;
            let digest = format!("{:x}", hasher.finalize());
            self.by_identity
                .insert(identity.clone(), (None, digest.clone(), bytes as usize));
            (digest, bytes as usize)
        };
        self.files.insert(
            path.into(),
            CachedFile {
                root,
                relative,
                identity,
                blob: None,
                digest: digest.clone(),
                size,
            },
        );
        Ok(digest)
    }
    pub fn read(&mut self, path: &Path, limit: usize) -> Result<Vec<u8>> {
        self.file(path, limit)?.0.read(limit)
    }
    pub fn directory(&mut self, path: &Path) -> Result<()> {
        self.checkpoint()?;
        self.observe_for_unit(path)?;
        let (root, relative) = self.resolve_link_path(path)?;
        if let Some(previous) = self.directories.get(path) {
            self.check_cached_location(previous.root, &previous.relative, root, &relative)?;
            return Ok(());
        }
        let input = self.roots[root].open(&relative, true)?;
        self.admit_metadata(1, path.as_os_str().len() * 2 + 256)?;
        let entries = directory_entries(
            &input,
            400_000 - self.metadata_nodes,
            64 * 1024 * 1024 - self.metadata_bytes,
            |work, bytes| {
                self.checkpoint()?;
                if let Some(budget) = &self.budget {
                    budget.charge(work, bytes)?;
                }
                Ok(())
            },
        )?;
        self.metadata_nodes += entries.len();
        self.metadata_bytes += entries
            .iter()
            .map(|(name, _)| name.len() + 128)
            .sum::<usize>();
        self.directories.insert(
            path.into(),
            DirectorySnapshot {
                root,
                relative,
                entries,
            },
        );
        Ok(())
    }
    pub fn exists(&mut self, path: &Path) -> Result<bool> {
        self.checkpoint()?;
        self.observe_for_unit(path)?;
        let (root, relative) = self.resolve_link_path(path)?;
        match self.roots[root].open(&relative, false) {
            Ok(_) => Ok(true),
            Err(error)
                if error
                    .downcast_ref::<io::Error>()
                    .is_some_and(|e| e.kind() == io::ErrorKind::NotFound) =>
            {
                if !self.missing.contains_key(path) {
                    self.admit_metadata(1, path.as_os_str().len() * 2 + 256)?;
                    self.missing.insert(path.into(), (root, relative));
                }
                Ok(false)
            }
            Err(error) => Err(error),
        }
    }
    pub fn seal(self) -> Result<SealedCapture> {
        self.checkpoint()?;
        self.verify_links()?;
        let control = || -> Result<()> {
            if let Some(cancelled) = &self.cancelled {
                ensure!(!cancelled(), DomainError::new(ErrorCode::Cancelled, ""));
            }
            ensure!(
                self.started.elapsed() < self.deadline,
                DomainError::new(ErrorCode::CaptureTimeout, "")
            );
            if let Some(budget) = &self.budget {
                budget.charge(1, 0)?;
            }
            Ok(())
        };
        let mut observations = self
            .links
            .observations
            .iter()
            .map(|(path, link)| ResolutionObservation::Link {
                path: path.clone(),
                target: link.target.clone(),
                real: link.real.clone(),
                identity: link.identity.clone(),
            })
            .collect::<Vec<_>>();
        for root in &self.roots {
            control()?;
            ensure!(
                root.requested.canonicalize()? == root.real,
                "CAPTURE_ROOT_CHANGED"
            );
            let current = FileIdentity::of(&std::fs::metadata(&root.real)?);
            // Directory timestamps may change when unrelated files are written;
            // only the anchored root identity belongs to this check.
            ensure!(
                current.dev == root.identity.dev && current.ino == root.identity.ino,
                "CAPTURE_ROOT_CHANGED"
            );
        }
        for root in &self.roots {
            observations.push(ResolutionObservation::Root {
                path: root.requested.clone(),
                real: root.real.clone(),
                exact: root.exact.clone(),
            });
        }
        let mut blobs = Vec::new();
        for (path, file) in self.files {
            control()?;
            if let Some(budget) = &self.budget {
                budget.charge(1, 0)?;
            }
            let current = self.roots[file.root]
                .open(&file.relative, false)?
                .metadata()?;
            ensure!(
                FileIdentity::of(&current) == file.identity,
                "CAPTURE_INPUT_CHANGED: {}",
                path.display()
            );
            observations.push(ResolutionObservation::File {
                path,
                sha256: file.digest.clone(),
                captured: file.blob.is_some(),
                identity: file.identity,
            });
            if let Some(blob) = file.blob {
                blobs.push((file.digest, blob, file.size));
            }
        }
        for (
            path,
            DirectorySnapshot {
                root,
                relative,
                entries: expected,
            },
        ) in self.directories
        {
            control()?;
            let input = self.roots[root].open(&relative, true)?;
            ensure!(
                directory_entries(
                    &input,
                    expected.len() + 1,
                    64 * 1024 * 1024,
                    |work, bytes| {
                        control()?;
                        if let Some(budget) = &self.budget {
                            budget.charge(work, bytes)?;
                        }
                        Ok(())
                    }
                )? == expected,
                "CAPTURE_DIRECTORY_CHANGED: {}",
                path.display()
            );
            observations.push(ResolutionObservation::Directory {
                path,
                entries: expected,
            });
        }
        for (path, (root, relative)) in self.missing {
            control()?;
            if let Some(budget) = &self.budget {
                budget.charge(1, 0)?;
            }
            match self.roots[root].open(&relative, false) {
                Err(error)
                    if error
                        .downcast_ref::<io::Error>()
                        .is_some_and(|e| e.kind() == io::ErrorKind::NotFound) => {}
                _ => anyhow::bail!("CAPTURE_MISSING_LOOKUP_CHANGED: {}", path.display()),
            }
            observations.push(ResolutionObservation::Missing { path });
        }
        Ok(SealedCapture {
            observations,
            blobs,
            unit_paths: self.unit_paths,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Result<(tempfile::TempDir, CaptureReader)> {
        let root = tempfile::tempdir()?;
        let spool = std::sync::Arc::new(tempfile::tempdir()?);
        let reader = CaptureReader::new(
            root.path(),
            spool,
            std::time::Instant::now(),
            std::time::Duration::from_secs(10),
            None,
            None,
        )?;
        Ok((root, reader))
    }
    #[test]
    fn configuration_resolution_and_workspace_capture_share_the_live_read_transaction() -> Result<()>
    {
        let directory = tempfile::tempdir()?;
        let root = directory.path().canonicalize()?;
        std::fs::create_dir(root.join(".code-ana"))?;
        std::fs::create_dir(root.join("src"))?;
        let config = include_bytes!("../../../../tests/fixtures/java/base/.code-ana/config.toml");
        let config_path = root.join(".code-ana/config.toml");
        std::fs::write(&config_path, config)?;
        std::fs::write(root.join("src/A.java"), b"class A {}")?;
        let spool = std::sync::Arc::new(tempfile::tempdir()?);
        let reader = std::cell::RefCell::new(CaptureReader::new(
            &root,
            spool.clone(),
            std::time::Instant::now(),
            std::time::Duration::from_secs(10),
            None,
            None,
        )?);
        let effective =
            crate::resolution::resolve_using(&root, &Request::default(), true, &reader)?;
        assert_eq!(reader.borrow().live_reads, 1);
        let c = &effective.config;
        let policy = CaptureSelection {
            reader: &reader,
            budget: None,
            root: &root,
            effective: &effective,
            defer_ownership: false,
            includes: glob(&c.scan.include)?,
            excludes: glob(&c.scan.exclude)?,
            text_only: glob(&c.scan.text_only)?,
            text_include: glob(&c.search.text.include)?,
            text_search: false,
            files_only: false,
            started: std::time::Instant::now(),
            deadline: std::time::Duration::from_secs(10),
        };
        let mut accumulator = CaptureAccumulator {
            manifest: Manifest::default(),
            blobs: BTreeMap::new(),
            total: 0,
            scanned: 0,
            spool,
        };
        capture_workspace(&policy, &mut accumulator)?;
        assert_eq!(accumulator.manifest.files.len(), 1);
        assert_eq!(reader.borrow().live_reads, 2);
        assert_eq!(reader.borrow_mut().read(&config_path, 1024 * 1024)?, config);
        assert_eq!(reader.borrow().live_reads, 2);
        // Both the parsed config and source are part of the same seal validation.
        std::fs::write(&config_path, b"changed after resolution")?;
        assert_eq!(reader.borrow_mut().read(&config_path, 1024 * 1024)?, config);
        assert!(reader.into_inner().seal().is_err());
        Ok(())
    }
    #[test]
    fn native_resolution_and_capture_reuse_metadata_and_control_bytes() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let root = directory.path().canonicalize()?;
        std::fs::create_dir(root.join(".code-ana"))?;
        std::fs::create_dir(root.join("src"))?;
        let config = "schema_version=1\nkind='project'\n[languages.rust]\nenabled=true\n";
        std::fs::write(root.join(".code-ana/config.toml"), config)?;
        std::fs::write(root.join("Cargo.toml"), b"fixture")?;
        std::fs::write(root.join("src/lib.rs"), b"pub fn fixture() {}")?;
        let policy = crate::rust_inputs::policy(&crate::parse_project(config)?)?;
        let metadata = serde_json::json!({
            "schema_version": 2, "policy": policy, "toolchain": "fixture",
            "dependency_sources": {}, "omitted_dependencies": {},
            "controls": {"Cargo.toml": hash(b"fixture")},
            "crates": [{"id": "app", "name": "app", "module": "app",
                "world": "main", "scope": "MAIN", "root": "src/lib.rs",
                "directory": "src", "edition": "2024", "target": "fixture",
                "cfg": [], "dependencies": {}, "publish": true}]
        });
        std::fs::write(
            root.join(".code-ana/rust-inputs.json"),
            serde_json::to_vec(&metadata)?,
        )?;
        let spool = std::sync::Arc::new(tempfile::tempdir()?);
        let reader = std::cell::RefCell::new(CaptureReader::new(
            &root,
            spool.clone(),
            std::time::Instant::now(),
            std::time::Duration::from_secs(10),
            None,
            None,
        )?);
        let effective =
            crate::resolution::resolve_using(&root, &Request::default(), true, &reader)?;
        assert_eq!(reader.borrow().live_reads, 3); // config, inventory, Cargo control
        let mut manifest = Manifest::default();
        let mut blobs = BTreeMap::new();
        let mut total = 0;
        crate::rust_inputs::capture(
            &root,
            &effective,
            &mut manifest,
            &mut blobs,
            &mut total,
            &spool,
            &reader,
        )?;
        assert_eq!(reader.borrow().live_reads, 4); // only the source is new
        assert_eq!(manifest.files.len(), 3);
        assert_eq!(reader.into_inner().seal()?.blobs.len(), 4);
        Ok(())
    }
    #[test]
    fn repeated_reads_use_one_live_copy_and_sealed_blobs_survive_live_deletion() -> Result<()> {
        let (root, mut reader) = fixture()?;
        let path = root.path().join("source.py");
        std::fs::write(&path, b"original")?;
        assert_eq!(reader.read(&path, 32)?, b"original");
        assert_eq!(reader.read(&path, 8)?, b"original");
        assert_eq!(reader.live_reads, 1);
        let SealedCapture {
            observations,
            blobs,
            ..
        } = reader.seal()?;
        assert_eq!(
            observations
                .iter()
                .filter(|o| matches!(o, ResolutionObservation::File { .. }))
                .count(),
            1
        );
        std::fs::remove_file(path)?;
        assert_eq!(blobs[0].1.read(32)?, b"original");
        assert!(blobs[0].1.as_ref().metadata()?.permissions().readonly());
        Ok(())
    }
    #[test]
    fn edits_directory_changes_and_previously_missing_files_invalidate_sealing() -> Result<()> {
        for change in ["edit", "directory", "missing"] {
            let (root, mut reader) = fixture()?;
            let path = root.path().join("source.py");
            if change == "edit" {
                std::fs::write(&path, b"original")?;
                reader.read(&path, 32)?;
                std::fs::write(&path, b"changed")?;
            } else if change == "directory" {
                reader.directory(root.path())?;
                std::fs::write(&path, b"new")?;
            } else {
                assert!(!reader.exists(&path)?);
                std::fs::write(&path, b"new")?;
            }
            assert!(reader.seal().is_err(), "{change}");
        }
        Ok(())
    }
    #[test]
    fn anchored_reads_reject_links_special_files_and_undeclared_external_roots() -> Result<()> {
        let (root, mut reader) = fixture()?;
        let external = tempfile::tempdir()?;
        let secret = external.path().join("secret");
        std::fs::write(&secret, b"secret")?;
        assert!(reader.read(&secret, 32).is_err());
        std::os::unix::fs::symlink(external.path(), root.path().join("link"))?;
        assert!(reader.read(&root.path().join("link/secret"), 32).is_err());
        std::os::unix::fs::symlink(&secret, root.path().join("file-link"))?;
        assert!(reader.read(&root.path().join("file-link"), 32).is_err());
        assert!(reader.read(root.path(), 32).is_err());
        assert_eq!(reader.live_reads, 0);
        reader.allow_file(&secret)?;
        assert_eq!(reader.read(&secret, 32)?, b"secret");
        reader.seal()?;
        Ok(())
    }
    #[test]
    fn directory_checks_restart_the_fd_cursor_and_file_bounds_apply_to_cache_hits() -> Result<()> {
        let (root, mut reader) = fixture()?;
        let path = root.path().join("source.py");
        std::fs::write(&path, b"original")?;
        reader.directory(root.path())?;
        reader.directory(root.path())?;
        reader.read(&path, 8)?;
        assert!(reader.read(&path, 7).is_err());
        assert_eq!(reader.live_reads, 1);
        reader.seal()?;
        Ok(())
    }
}

#[cfg(test)]
mod control_tests {
    use super::*;
    #[test]
    fn a_large_file_copy_observes_cancellation_before_it_can_be_sealed() -> Result<()> {
        use std::sync::{
            Arc,
            atomic::{AtomicUsize, Ordering},
        };
        let root = tempfile::tempdir()?;
        let spool = Arc::new(tempfile::tempdir()?);
        let mut reader = CaptureReader::new(
            root.path(),
            spool.clone(),
            std::time::Instant::now(),
            std::time::Duration::from_secs(10),
            None,
            None,
        )?;
        let checks = Arc::new(AtomicUsize::new(0));
        let counter = checks.clone();
        reader.cancelled = Some(Arc::new(move || {
            counter.fetch_add(1, Ordering::SeqCst) >= 2
        }));
        let path = root.path().join("large.bin");
        std::fs::write(&path, vec![b'x'; 512 * 1024])?;
        assert!(reader.file(&path, 1024 * 1024).is_err());
        assert_eq!(reader.live_reads, 0);
        assert!(reader.files.is_empty());
        assert!(reader.seal().is_err());
        assert_eq!(std::fs::read_dir(spool.path())?.count(), 0);
        Ok(())
    }
    #[test]
    fn hardlink_views_share_the_live_read_but_keep_separate_file_observations() -> Result<()> {
        let root = tempfile::tempdir()?;
        let first = root.path().join("first");
        let second = root.path().join("second");
        std::fs::write(&first, b"shared")?;
        std::fs::hard_link(&first, &second)?;
        let spool = std::sync::Arc::new(tempfile::tempdir()?);
        let mut reader = CaptureReader::new(
            root.path(),
            spool,
            std::time::Instant::now(),
            std::time::Duration::from_secs(10),
            None,
            None,
        )?;
        assert_eq!(reader.read(&first, 32)?, reader.read(&second, 32)?);
        assert_eq!(reader.live_reads, 1);
        let observations = reader.seal()?.observations;
        assert_eq!(
            observations
                .iter()
                .filter(|o| matches!(o, ResolutionObservation::File { .. }))
                .count(),
            2
        );
        Ok(())
    }
}

#[cfg(test)]
mod identity_tests {
    use super::*;
    #[test]
    fn identity_only_controls_never_enter_spool_or_turn_into_captured_content() -> Result<()> {
        let project = tempfile::tempdir()?;
        let external = tempfile::tempdir()?;
        let source = external.path().join("settings.xml");
        let bytes = b"<settings>fixture-private-control</settings>";
        std::fs::write(&source, bytes)?;
        let spool = std::sync::Arc::new(tempfile::tempdir()?);
        let mut reader = CaptureReader::new(
            project.path(),
            spool.clone(),
            std::time::Instant::now(),
            std::time::Duration::from_secs(10),
            None,
            None,
        )?;
        reader.allow_file(&source)?;
        assert_eq!(reader.digest(&source, 4096)?, hash(bytes));
        assert_eq!(reader.digest(&source, 4096)?, hash(bytes));
        assert_eq!(reader.live_reads, 1);
        assert!(reader.read(&source, 4096).is_err());
        let sealed = reader.seal()?;
        assert!(sealed.blobs.is_empty());
        assert!(sealed.observations.iter().any(|o| matches!(
            o,
            ResolutionObservation::File {
                captured: false,
                ..
            }
        )));
        assert_eq!(std::fs::read_dir(spool.path())?.count(), 0);
        Ok(())
    }
}
