//! Metadata-only lookups have different semantics from content reads and full
//! directory enumeration. Record the actual entry kind and recheck at sealing.
use super::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EntryKind {
    File,
    Directory,
    Other,
}

pub(super) struct KindSnapshot {
    pub root: usize,
    pub relative: PathBuf,
    pub entry: Option<EntryKind>,
}
fn absent(error: &io::Error) -> bool {
    matches!(
        error.kind(),
        io::ErrorKind::NotFound | io::ErrorKind::NotADirectory
    )
}

pub(super) fn anchored_kind(root: &Root, relative: &Path) -> Result<Option<EntryKind>> {
    let Some(name) = relative.file_name() else {
        root.open(relative, true)?;
        return Ok(Some(EntryKind::Directory));
    };
    let parent = match root.open(relative.parent().unwrap_or(Path::new("")), true) {
        Ok(parent) => parent,
        Err(error) if error.downcast_ref::<io::Error>().is_some_and(absent) => return Ok(None),
        Err(error) => return Err(error),
    };
    let name = std::ffi::CString::new(name.as_bytes()).context("CAPTURE_PATH_NUL")?;
    let mut metadata = std::mem::MaybeUninit::<libc::stat>::uninit();
    // SAFETY: live anchored parent FD, valid name and writable stat storage.
    if unsafe {
        libc::fstatat(
            parent.as_raw_fd(),
            name.as_ptr(),
            metadata.as_mut_ptr(),
            libc::AT_SYMLINK_NOFOLLOW,
        )
    } != 0
    {
        let error = io::Error::last_os_error();
        return if absent(&error) {
            Ok(None)
        } else {
            Err(error.into())
        };
    }
    let metadata = unsafe { metadata.assume_init() };
    Ok(Some(match metadata.st_mode & libc::S_IFMT {
        libc::S_IFREG => EntryKind::File,
        libc::S_IFDIR => EntryKind::Directory,
        libc::S_IFLNK => anyhow::bail!("CAPTURE_SYMLINK_UNDECLARED"),
        _ => EntryKind::Other,
    }))
}

impl CaptureReader {
    /// Permit one metadata lookup even when its parent does not exist. The
    /// nearest existing ancestor is anchored, but access remains exact-path.
    pub fn allow_lookup(&mut self, path: &Path) -> Result<()> {
        ensure!(
            path.is_absolute()
                && !path
                    .components()
                    .any(|part| matches!(part, std::path::Component::ParentDir)),
            "UNSAFE_CAPTURE_PATH"
        );
        self.checkpoint()?;
        for ancestor in path
            .ancestors()
            .skip(1)
            .chain(std::iter::once(Path::new("/")))
        {
            match std::fs::symlink_metadata(ancestor) {
                Ok(metadata) if metadata.is_dir() => {
                    return self
                        .allow_root(ancestor, Some(path.strip_prefix(ancestor)?.to_owned()));
                }
                Ok(_) => (),
                Err(error) if absent(&error) => (),
                Err(error) => return Err(error.into()),
            }
        }
        anyhow::bail!("CAPTURE_LOOKUP_ANCHOR_REQUIRED")
    }
    /// A false FileExists at a directory is not proof the path is absent.
    /// All metadata probes use the same roots, links, unit lease and budget.
    pub fn entry_kind(&mut self, path: &Path) -> Result<Option<EntryKind>> {
        self.checkpoint()?;
        self.observe_for_unit(path)?;
        let (root, relative) = self.resolve_link_path(path)?;
        if let Some(previous) = self.kinds.get(path) {
            self.check_cached_location(previous.root, &previous.relative, root, &relative)?;
            return Ok(previous.entry);
        }
        self.admit_metadata(1, path.as_os_str().len() * 2 + 256)?;
        let entry = anchored_kind(&self.roots[root], &relative)?;
        self.kinds.insert(
            path.into(),
            KindSnapshot {
                root,
                relative,
                entry,
            },
        );
        Ok(entry)
    }
    /// Fund a detached view, including the caller's comparison indexes, before
    /// cloning entry names. Counts stay on the same capture account.
    pub fn directory_copy(&mut self, path: &Path) -> Result<Vec<(String, String)>> {
        self.directory(path)?;
        let entries = &self
            .directories
            .get(path)
            .context("CAPTURE_DIRECTORY_REQUIRED")?
            .entries;
        let count = entries.len();
        let bytes = entries.iter().try_fold(0usize, |bytes, (name, _)| {
            bytes
                .checked_add(name.len() * 4 + 256)
                .context("CAPTURE_OBSERVATION_BUDGET")
        })?;
        self.admit_metadata(count + 1, bytes)?;
        Ok(self
            .directories
            .get(path)
            .context("CAPTURE_DIRECTORY_REQUIRED")?
            .entries
            .clone())
    }
    /// Borrow the task-owned enumeration. This does not read file bodies and
    /// does not charge for a second copy that the caller has not requested.
    pub fn directory_view(&mut self, path: &Path) -> Result<&[(String, String)]> {
        self.directory(path)?;
        Ok(&self
            .directories
            .get(path)
            .context("CAPTURE_DIRECTORY_REQUIRED")?
            .entries)
    }
}
