//! Task-local SQLite execution and I/O admission. No global allocator or quota.
mod vfs;
use super::*;
use rusqlite::{OpenFlags, ffi};
use std::{ffi::CString, marker::PhantomData, ops::Deref};

pub(crate) const CACHE_KIB: u64 = 1024;
const CACHE_BYTES: u64 = CACHE_KIB * 1024;
// ATTACH reads its schema before its own cache_size pragma. Admit the bundled
// default (-2000 KiB), rounded up, before xOpen; steady caches are then 1 MiB.
const PAGER_ADMISSION_BYTES: u64 = 2 << 20;
const SQL_WORKSPACE_BYTES: u64 = 4 << 20;
const SCHEMA_BYTES: u64 = 512 << 10;
pub(crate) const READ_BYTES: u64 = SQL_WORKSPACE_BYTES + PAGER_ADMISSION_BYTES + SCHEMA_BYTES;
// A host work unit admits up to 128 SQLite VM operations, including prepare.
const VM_OPS: i32 = 128;

/// Shared transformations use the first already-issued host account. Values are
/// inspected by reference before strings, maps, groups or JSON are allocated.
pub(crate) fn admit(context: Option<&GraphStagingContext>, work: u64, bytes: u64) -> Result<()> {
    if let Some(host) = context.and_then(|c| c.hosts.values().next()) {
        host.charge(work, 0, bytes).map_err(DomainError::analysis)?;
    }
    Ok(())
}
pub(crate) fn row(
    context: Option<&GraphStagingContext>,
    row: &rusqlite::Row<'_>,
) -> rusqlite::Result<()> {
    let mut bytes = 0u64;
    for column in 0..row.as_ref().column_count() {
        if let rusqlite::types::ValueRef::Text(value) | rusqlite::types::ValueRef::Blob(value) =
            row.get_ref(column)?
        {
            bytes = bytes.saturating_add(value.len() as u64);
        }
    }
    admit(
        context,
        1 + row.as_ref().column_count() as u64 + bytes.div_ceil(1024),
        bytes.saturating_mul(8).saturating_add(1024),
    )
    .map_err(|error| rusqlite::Error::ToSqlConversionFailure(error.into()))
}

#[derive(Default)]
struct State {
    next: u64,
    files: BTreeMap<u64, FileState>,
    error: Option<Arc<anyhow::Error>>,
    peak_hidden: u64,
    workspace_live: u64,
    workspace_funded: u64,
}
#[derive(Debug)]
struct SharedFailure(Arc<anyhow::Error>);
impl std::fmt::Display for SharedFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Display::fmt(self.0.as_ref(), f)
    }
}
impl std::error::Error for SharedFailure {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        self.0.source()
    }
}
struct FileState {
    path: Option<PathBuf>,
    size: u64,
    hidden: bool,
}
struct Owner<'a> {
    root: PathBuf,
    budget: Option<AnalysisContext>,
    cancellation: &'a dyn Fn() -> Result<()>,
    disk: &'a dyn Fn(&Path, u64) -> Result<()>,
    state: Mutex<State>,
}
impl Owner<'_> {
    fn state(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }
    fn first_error(&self) -> Option<anyhow::Error> {
        let error = self.state().error.clone()?;
        Some(if let Some(domain) = error.downcast_ref::<DomainError>() {
            anyhow::Error::new(domain.clone())
        } else {
            anyhow::Error::new(SharedFailure(error))
        })
    }
    fn charge(&self, work: u64, bytes: u64) -> Result<()> {
        if let Some(error) = self.first_error() {
            return Err(error);
        }
        (self.cancellation)()?;
        if let Some(budget) = &self.budget {
            budget
                .charge(work, 0, bytes)
                .map_err(DomainError::analysis)?;
        }
        Ok(())
    }
    fn workspace(&self, bytes: u64) -> Result<Workspace> {
        let (live, funded) = {
            let state = self.state();
            (
                state
                    .workspace_live
                    .checked_add(bytes)
                    .context("SQLITE_WORKSPACE_OVERFLOW")?,
                state.workspace_funded,
            )
        };
        self.charge(1, live.saturating_sub(funded))?;
        let mut state = self.state();
        state.workspace_live = live;
        state.workspace_funded = funded.max(live);
        Ok(Workspace {
            owner: self as *const Owner<'_> as *const std::ffi::c_void,
            bytes,
        })
    }
    fn fail(&self, error: anyhow::Error) {
        let mut state = self.state();
        if state.error.is_none() {
            state.error = Some(Arc::new(error));
        }
    }
    fn hidden(state: &State) -> u64 {
        state
            .files
            .values()
            .filter(|f| f.hidden)
            .fold(0u64, |n, f| n.saturating_add(f.size))
    }
    fn check(&self) -> Result<()> {
        self.charge(0, 0)?;
        let bytes = Self::hidden(&self.state());
        (self.disk)(&self.root, bytes)
    }
    fn register(&self, path: Option<PathBuf>, size: u64, hidden: bool) -> Result<u64> {
        self.charge(
            1,
            256 + path.as_ref().map_or(0, |p| p.as_os_str().len() as u64),
        )?;
        let mut state = self.state();
        state.next = state
            .next
            .checked_add(1)
            .context("SQLITE_FILE_ID_OVERFLOW")?;
        let id = state.next;
        state.files.insert(id, FileState { path, size, hidden });
        let live = Self::hidden(&state);
        drop(state);
        if let Err(error) = self.check() {
            self.state().files.remove(&id);
            return Err(error);
        }
        {
            let mut state = self.state();
            state.peak_hidden = state.peak_hidden.max(live);
        }
        Ok(id)
    }
    /// Reserve the prospective size before the OS can grow the file. Named
    /// bytes are already visible to the directory checker; only their growth
    /// and all still-open hidden files are extra.
    fn resize(&self, id: u64, size: u64) -> Result<u64> {
        self.charge(0, 0)?;
        let mut state = self.state();
        let file = state.files.get_mut(&id).context("SQLITE_FILE_NOT_OWNED")?;
        let old = file.size;
        let growth = if file.hidden {
            0
        } else {
            size.saturating_sub(old)
        };
        file.size = size;
        let live = Self::hidden(&state);
        let Some(additional) = live.checked_add(growth) else {
            state.files.get_mut(&id).expect("owned SQLite file").size = old;
            anyhow::bail!("SQLITE_FILE_SIZE_OVERFLOW");
        };
        drop(state);
        if let Err(error) = (self.cancellation)().and_then(|()| (self.disk)(&self.root, additional))
        {
            self.restore(id, old);
            return Err(error);
        }
        {
            let mut state = self.state();
            state.peak_hidden = state.peak_hidden.max(live);
        }
        Ok(old)
    }
    fn restore(&self, id: u64, size: u64) {
        if let Some(file) = self.state().files.get_mut(&id) {
            file.size = size;
        }
    }
    fn removed(&self, path: &Path) {
        let mut state = self.state();
        for file in state
            .files
            .values_mut()
            .filter(|f| f.path.as_deref() == Some(path))
        {
            file.hidden = true;
        }
        let live = Self::hidden(&state);
        state.peak_hidden = state.peak_hidden.max(live);
    }
}

/// A private scope keeps its admitted workspace capacity until teardown. Closing
/// a connection releases its live use, never the charged host capacity. Bulk and
/// sealing connections can reuse that capacity; simultaneous connections add up.
struct Workspace {
    owner: *const std::ffi::c_void,
    bytes: u64,
}
impl Drop for Workspace {
    fn drop(&mut self) {
        // SAFETY: only Database and registered VFS files own leases. Both close
        // before their Scope, including the callback-only ReadContext path.
        let owner = unsafe { &*self.owner.cast::<Owner<'_>>() };
        let mut state = owner.state();
        state.workspace_live = state
            .workspace_live
            .checked_sub(self.bytes)
            .expect("owned SQLite workspace");
    }
}

/// Registration and all borrowed callbacks outlive every connection/file opened
/// through it. Connections cannot be moved to another thread or detached from
/// the registration by the safe wrapper.
pub(crate) struct Scope<'a> {
    owner: Box<Owner<'a>>,
    registered: Box<vfs::Registered>,
    name: CString,
}
impl<'a> Scope<'a> {
    pub fn new(
        root: &Path,
        context: Option<&GraphStagingContext>,
        cancellation: &'a dyn Fn() -> Result<()>,
        disk: &'a dyn Fn(&Path, u64) -> Result<()>,
    ) -> Result<Self> {
        let budget = context.and_then(|c| c.hosts.values().next()).cloned();
        if let Some(budget) = &budget {
            budget.charge(1, 0, 4096).map_err(DomainError::analysis)?;
        }
        let owner = Box::new(Owner {
            root: root.canonicalize()?,
            budget,
            cancellation,
            disk,
            state: Mutex::new(State::default()),
        });
        let name = CString::new(new_id("task-sqlite"))?;
        let registered = vfs::register(&name, owner.as_ref())?;
        Ok(Self {
            owner,
            registered,
            name,
        })
    }
    pub fn check(&self) -> Result<()> {
        self.owner.check()
    }
    /// The callback can borrow the reader, but cannot move it out of this scope.
    /// This keeps its connection, VFS registration and callbacks alive in that order,
    /// including during unwinding. No task-owned SQL connection escapes.
    pub fn with_reader<T>(
        &self,
        store: &Store,
        id: &str,
        read: impl FnOnce(&ReadContext) -> Result<T>,
    ) -> Result<T> {
        let (revision, _pin) = store.pin_revision(id, self.owner.budget.as_ref())?;
        let path = store.home.join("revisions").join(id).join("facts.db");
        let result = (|| {
            let db = self.open_mode(&path, false, true)?;
            let reader = store.reader_from_connection(
                revision,
                db.inner,
                None,
                self.owner.budget.clone(),
            )?;
            read(&reader)
        })();
        self.result(result)
    }

    pub fn result<T>(&self, result: Result<T>) -> Result<T> {
        let error = self.owner.first_error();
        match error {
            Some(error) => Err(error),
            None => {
                // Typed failures already identify the original cause. Only
                // opaque SQLite interruption/row-conversion errors need mapping
                // back to their sticky host budget failure.
                if result
                    .as_ref()
                    .err()
                    .is_some_and(|e| e.downcast_ref::<DomainError>().is_none())
                {
                    self.owner.charge(0, 0)?;
                }
                result
            }
        }
    }
    pub fn open<'s>(&'s self, path: &Path) -> Result<Database<'s>> {
        self.open_mode(path, true, false)
    }
    pub fn reopen_for_seal<'s>(&'s self, path: &Path) -> Result<Database<'s>> {
        self.open_mode(path, false, false)
    }
    fn open_mode<'s>(&'s self, path: &Path, bulk: bool, read_only: bool) -> Result<Database<'s>> {
        // SQL compiler/schema state and sorter buffers are admitted before open,
        // then each pager/file reserves its own bounded cache through xOpen.
        let workspace = self.owner.workspace(SQL_WORKSPACE_BYTES + CACHE_BYTES)?;
        let opened = Connection::open_with_flags_and_vfs(
            path,
            (if read_only {
                OpenFlags::SQLITE_OPEN_READ_ONLY
            } else {
                OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE
            }) | OpenFlags::SQLITE_OPEN_NO_MUTEX,
            self.name.as_c_str(),
        );
        let db = self.result(opened.map_err(Into::into))?;
        self.owner.charge(1, 0)?;
        // SAFETY: db is kept inside Database, which borrows this registration.
        // SQLite invokes the callback synchronously; worker threads are disabled.
        unsafe {
            ffi::sqlite3_progress_handler(
                db.handle(),
                VM_OPS,
                Some(vfs::progress),
                self.owner.as_ref() as *const Owner<'_> as *mut std::ffi::c_void,
            );
        }
        let settings = if read_only {
            format!(
                "PRAGMA temp_store=FILE; PRAGMA threads=0; PRAGMA mmap_size=0; PRAGMA main.cache_size=-{CACHE_KIB}; PRAGMA temp.cache_size=-{CACHE_KIB};"
            )
        } else if bulk {
            format!(
                "PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE; PRAGMA threads=0; PRAGMA mmap_size=0; PRAGMA main.cache_size=-{CACHE_KIB}; PRAGMA temp.cache_size=-{CACHE_KIB};"
            )
        } else {
            format!("PRAGMA threads=0; PRAGMA mmap_size=0; PRAGMA main.cache_size=-{CACHE_KIB};")
        };
        self.result(db.execute_batch(&settings).map_err(Into::into))?;
        Ok(Database {
            inner: db,
            _workspace: workspace,
            owner: self.owner.as_ref() as *const Owner<'_> as *const std::ffi::c_void,
            _scope: PhantomData,
        })
    }
}
impl Drop for Scope<'_> {
    fn drop(&mut self) {
        // SAFETY: safe Database lifetimes force every SQLite file to close first.
        unsafe {
            ffi::sqlite3_vfs_unregister(&mut self.registered.base);
        }
        debug_assert!(self.owner.state().files.is_empty());
        debug_assert_eq!(self.owner.state().workspace_live, 0);
    }
}

pub(crate) struct Database<'a> {
    inner: Connection,
    _workspace: Workspace,
    owner: *const std::ffi::c_void,
    // dyn Fn is deliberately not Sync: SQLite must stay on its owner thread.
    _scope: PhantomData<&'a dyn Fn()>,
}
impl Deref for Database<'_> {
    type Target = Connection;
    fn deref(&self) -> &Connection {
        &self.inner
    }
}
impl Database<'_> {
    pub fn backup_from(
        &mut self,
        source: &Connection,
        check: &dyn Fn() -> Result<()>,
    ) -> Result<()> {
        // SAFETY: Database borrows the Scope owning this pointer.
        let owner = unsafe { &*self.owner.cast::<Owner<'_>>() };
        let backup = rusqlite::backup::Backup::new(source, &mut self.inner)?;
        loop {
            check()?;
            owner.charge(128, 0)?; // one host unit per copied page; never reset on retry
            match backup.step(128)? {
                rusqlite::backup::StepResult::Done => return Ok(()),
                rusqlite::backup::StepResult::More => {}
                rusqlite::backup::StepResult::Busy | rusqlite::backup::StepResult::Locked => {
                    std::thread::sleep(Duration::from_millis(1))
                }
                _ => anyhow::bail!(DomainError::new(ErrorCode::SqliteBackupStep, "")),
            }
        }
    }
}
