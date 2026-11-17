//! Explicit, unit-scoped symlink proofs. No ambient realpath traversal below an anchor.
use super::*;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeclaredLink {
    pub path: PathBuf,
    /// Exact bytes returned by readlink, rather than a canonical target.
    pub target: PathBuf,
}
#[derive(Default)]
pub(super) struct LinkStore {
    declarations: BTreeMap<Option<AnalysisUnitId>, BTreeMap<PathBuf, PathBuf>>,
    pub(super) observations: BTreeMap<PathBuf, LinkSnapshot>,
    pub(super) failed: bool,
}
pub(super) struct LinkSnapshot {
    root: usize,
    relative: PathBuf,
    pub(super) identity: FileIdentity,
    pub(super) target: PathBuf,
    pub(super) real: Option<PathBuf>,
}

fn normalized(path: &Path) -> Result<PathBuf> {
    ensure!(
        path.is_absolute() && path.as_os_str().len() <= 16 * 1024,
        "CAPTURE_PATH_BUDGET"
    );
    let mut result = PathBuf::from("/");
    for (count, part) in path.components().enumerate() {
        ensure!(count <= 64, "CAPTURE_PATH_BUDGET");
        match part {
            std::path::Component::RootDir | std::path::Component::CurDir => (),
            std::path::Component::Normal(name) => result.push(name),
            std::path::Component::ParentDir => ensure!(result.pop(), "UNSAFE_CAPTURE_PATH"),
            _ => anyhow::bail!("UNSAFE_CAPTURE_PATH"),
        }
    }
    Ok(result)
}

fn destination(parent: &Path, target: &Path) -> Result<PathBuf> {
    if target.is_absolute() {
        return Ok(target.into());
    }
    let mut base = parent.to_path_buf();
    let mut parts = target.components().peekable();
    while let Some(part) = parts.peek() {
        match part {
            std::path::Component::CurDir => (),
            std::path::Component::ParentDir => ensure!(base.pop(), "UNSAFE_CAPTURE_PATH"),
            _ => break,
        }
        parts.next();
    }
    for part in parts {
        base.push(part);
    }
    Ok(base)
}

/// Read and identify the link through its anchored parent, without following its leaf.
fn link_at(
    root: &Root,
    relative: &Path,
    budget: Option<&ResourceBudget>,
) -> Result<(FileIdentity, PathBuf)> {
    let parent = root.open(relative.parent().context("CAPTURE_LINK_PARENT")?, true)?;
    let name = std::ffi::CString::new(
        relative
            .file_name()
            .context("CAPTURE_LINK_NAME")?
            .as_bytes(),
    )?;
    let stat = || -> Result<libc::stat> {
        let mut value = std::mem::MaybeUninit::<libc::stat>::uninit();
        // SAFETY: a live parent FD, valid C string and writable stat storage.
        ensure!(
            unsafe {
                libc::fstatat(
                    parent.as_raw_fd(),
                    name.as_ptr(),
                    value.as_mut_ptr(),
                    libc::AT_SYMLINK_NOFOLLOW,
                )
            } == 0,
            io::Error::last_os_error()
        );
        Ok(unsafe { value.assume_init() })
    };
    let before = stat()?;
    ensure!(
        before.st_mode & libc::S_IFMT == libc::S_IFLNK,
        "CAPTURE_DECLARED_LINK_MISMATCH"
    );
    ensure!(
        (0..=16 * 1024).contains(&before.st_size),
        "CAPTURE_PATH_BUDGET"
    );
    if let Some(budget) = budget {
        budget.charge(1, before.st_size as u64 + 1)?;
    }
    let mut bytes = vec![0; before.st_size as usize + 1];
    // SAFETY: the destination buffer has the declared writable length.
    let length = unsafe {
        libc::readlinkat(
            parent.as_raw_fd(),
            name.as_ptr(),
            bytes.as_mut_ptr().cast(),
            bytes.len(),
        )
    };
    ensure!(length >= 0, io::Error::last_os_error());
    ensure!(
        length as usize == before.st_size as usize,
        "CAPTURE_LINK_CHANGED"
    );
    let after = stat()?;
    let identity = stat_identity(&before);
    ensure!(identity == stat_identity(&after), "CAPTURE_LINK_CHANGED");
    bytes.truncate(length as usize);
    Ok((identity, PathBuf::from(String::from_utf8(bytes)?)))
}
fn stat_identity(stat: &libc::stat) -> FileIdentity {
    FileIdentity {
        dev: stat.st_dev as u64,
        ino: stat.st_ino,
        bytes: stat.st_size as u64,
        modified: stat.st_mtime,
        modified_ns: stat.st_mtime_nsec,
        changed: stat.st_ctime,
        changed_ns: stat.st_ctime_nsec,
    }
}

impl CaptureReader {
    /// Install the complete map before validation so declaration order is immaterial.
    /// A failed proof poisons the reader; it cannot subsequently seal partial inputs.
    pub fn declare_links(&mut self, declarations: &[DeclaredLink]) -> Result<()> {
        let result = self.install_links(declarations);
        if result.is_err() {
            self.links.failed = true;
        }
        result
    }
    fn install_links(&mut self, declarations: &[DeclaredLink]) -> Result<()> {
        self.checkpoint()?;
        ensure!(declarations.len() <= 4096, "CAPTURE_LINK_BUDGET");
        let mut keys = BTreeSet::new();
        for declaration in declarations {
            self.checkpoint()?;
            ensure!(
                declaration.path.as_os_str().len() <= 16 * 1024
                    && declaration.target.as_os_str().len() <= 16 * 1024,
                "CAPTURE_PATH_BUDGET"
            );
            let path = if declaration.path.is_absolute() {
                declaration.path.clone()
            } else {
                self.project_root().join(&declaration.path)
            };
            ensure!(
                !path
                    .components()
                    .any(|c| matches!(c, std::path::Component::ParentDir)),
                "UNSAFE_CAPTURE_PATH"
            );
            let path = normalized(&path)?;
            let (root, relative) = self.locate(&path)?;
            let key = self.roots[root].real.join(relative);
            ensure!(keys.insert(key.clone()), "CAPTURE_DUPLICATE_LINK");
            ensure!(
                declaration.target.as_os_str().len() <= 16 * 1024,
                "CAPTURE_PATH_BUDGET"
            );
            self.admit_metadata(
                1,
                key.as_os_str().len() + declaration.target.as_os_str().len() + 256,
            )?;
            let map = self
                .links
                .declarations
                .entry(self.active_unit.clone())
                .or_default();
            ensure!(map.len() < 4096, "CAPTURE_LINK_BUDGET");
            if let Some(previous) = map.get(&key) {
                ensure!(
                    previous == &declaration.target,
                    "CAPTURE_DECLARED_LINK_MISMATCH"
                );
            }
            map.insert(key, declaration.target.clone());
        }
        for key in keys {
            self.resolve_link_path(&key)?;
            ensure!(
                self.links.observations.contains_key(&key),
                "CAPTURE_DECLARED_LINK_MISMATCH"
            );
        }
        Ok(())
    }
    pub(super) fn check_cached_location(
        &self,
        old_root: usize,
        old: &Path,
        root: usize,
        relative: &Path,
    ) -> Result<()> {
        ensure!(
            self.roots[old_root].real.join(old) == self.roots[root].real.join(relative),
            "CAPTURE_RESOLUTION_CHANGED"
        );
        Ok(())
    }
    pub(super) fn resolve_link_path(&mut self, path: &Path) -> Result<(usize, PathBuf)> {
        if self
            .links
            .declarations
            .get(&self.active_unit)
            .is_none_or(|m| m.is_empty())
        {
            return self.locate(path);
        }
        let result = self.walk_links(path, &mut Vec::new(), &mut 0);
        if result.is_err() {
            self.links.failed = true;
        }
        result
    }
    fn walk_links(
        &mut self,
        path: &Path,
        stack: &mut Vec<PathBuf>,
        hops: &mut usize,
    ) -> Result<(usize, PathBuf)> {
        self.checkpoint()?;
        let (mut root, relative) = self.locate(path)?;
        ensure!(
            relative.components().count() <= 64 && relative.as_os_str().len() <= 16 * 1024,
            "CAPTURE_PATH_BUDGET"
        );
        let mut logical = self.roots[root].real.clone();
        let mut physical = PathBuf::new();
        for component in relative.components() {
            self.checkpoint()?;
            let name = match component {
                std::path::Component::CurDir => continue,
                std::path::Component::ParentDir => {
                    let current = self.roots[root].real.join(&physical);
                    // POSIX requires the preceding directory to exist, even when '..'
                    // removes it from the final spelling. Preserve that lookup for sealing.
                    self.directory(&current)?;
                    let parent = current.parent().context("UNSAFE_CAPTURE_PATH")?;
                    (root, physical) = self.locate(parent)?;
                    ensure!(logical.pop(), "UNSAFE_CAPTURE_PATH");
                    continue;
                }
                std::path::Component::Normal(name) => name,
                _ => anyhow::bail!("UNSAFE_CAPTURE_PATH"),
            };
            logical.push(name);
            physical.push(name);
            // Opening the parent follows no links: every previous alias was resolved above.
            let parent = self.roots[root].open(physical.parent().unwrap(), true);
            let parent = match parent {
                Ok(parent) => parent,
                Err(error)
                    if error
                        .downcast_ref::<io::Error>()
                        .is_some_and(|e| e.kind() == io::ErrorKind::NotFound) =>
                {
                    continue;
                }
                Err(error) => return Err(error),
            };
            let leaf = std::ffi::CString::new(name.as_bytes())?;
            let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
            let code = unsafe {
                libc::fstatat(
                    parent.as_raw_fd(),
                    leaf.as_ptr(),
                    stat.as_mut_ptr(),
                    libc::AT_SYMLINK_NOFOLLOW,
                )
            };
            if code != 0 {
                let error = io::Error::last_os_error();
                if error.kind() == io::ErrorKind::NotFound {
                    continue;
                }
                return Err(error.into());
            }
            let stat = unsafe { stat.assume_init() };
            if stat.st_mode & libc::S_IFMT != libc::S_IFLNK {
                continue;
            }
            *hops += 1;
            ensure!(*hops <= 40, "CAPTURE_LINK_BUDGET");
            let actual = self.roots[root].real.join(&physical);
            ensure!(!stack.contains(&actual), "CAPTURE_LINK_CYCLE");
            let map = &self.links.declarations[&self.active_unit];
            let (key, expected) = map
                .get_key_value(&logical)
                .or_else(|| map.get_key_value(&actual))
                .context("CAPTURE_UNDECLARED_LINK")?;
            let retained = if self.links.observations.contains_key(key) {
                0
            } else {
                key.as_os_str().len()
                    + physical.as_os_str().len()
                    + expected.as_os_str().len()
                    + 32 * 1024
                    + 256
            };
            let (key, expected) = (key.clone(), expected.clone());
            if retained != 0 {
                self.admit_metadata(1, retained)?;
            }
            let (identity, target) = link_at(&self.roots[root], &physical, self.budget.as_ref())?;
            ensure!(target == expected, "CAPTURE_DECLARED_LINK_MISMATCH");
            // Preserve '..' until traversal: a preceding link changes its physical parent.
            let destination = destination(actual.parent().unwrap(), &target)?;
            stack.push(actual);
            let (next_root, next_relative) = self.walk_links(&destination, stack, hops)?;
            stack.pop();
            let real = match self.roots[next_root]
                .open(&next_relative, next_relative.as_os_str().is_empty())
            {
                Ok(_) => {
                    let real = self.roots[next_root].real.join(&next_relative);
                    self.observe_for_unit(&real)?;
                    Some(real)
                }
                Err(error)
                    if error
                        .downcast_ref::<io::Error>()
                        .is_some_and(|e| e.kind() == io::ErrorKind::NotFound) =>
                {
                    let missing = self.roots[next_root].real.join(&next_relative);
                    self.observe_for_unit(&missing)?;
                    if !self.missing.contains_key(&missing) {
                        self.admit_metadata(1, missing.as_os_str().len() * 2 + 256)?;
                    }
                    self.missing
                        .entry(missing)
                        .or_insert((next_root, next_relative.clone()));
                    None
                }
                Err(error) => return Err(error),
            };
            self.observe_for_unit(&key)?;
            if let Some(old) = self.links.observations.get(&key) {
                ensure!(
                    old.identity == identity && old.target == target && old.real == real,
                    "CAPTURE_LINK_CHANGED"
                );
            } else {
                self.links.observations.insert(
                    key,
                    LinkSnapshot {
                        root,
                        relative: physical,
                        identity,
                        target,
                        real,
                    },
                );
            }
            root = next_root;
            physical = next_relative;
        }
        Ok((root, physical))
    }
    pub(super) fn verify_links(&self) -> Result<()> {
        for link in self.links.observations.values() {
            self.checkpoint()?;
            let (identity, target) =
                link_at(&self.roots[link.root], &link.relative, self.budget.as_ref())?;
            ensure!(
                identity == link.identity && target == link.target,
                "CAPTURE_LINK_CHANGED"
            );
            if let Some(real) = &link.real {
                let (root, relative) = self.locate(real)?;
                self.roots[root].open(&relative, relative.as_os_str().is_empty())?;
            }
        }
        Ok(())
    }
}
