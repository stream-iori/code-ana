//! A private VFS forwards OS behavior while admitting every file growth.
//! No Go/Java callbacks or compiler objects enter this boundary.
use super::*;
use std::{
    ffi::{CStr, c_char, c_int, c_void},
    panic::{AssertUnwindSafe, catch_unwind},
    ptr,
};

#[repr(C)]
pub(super) struct Registered {
    pub base: ffi::sqlite3_vfs,
    real: *mut ffi::sqlite3_vfs,
    owner: *const c_void,
}
#[repr(C)]
struct File {
    base: ffi::sqlite3_file,
    real: *mut ffi::sqlite3_file,
    methods: *const ffi::sqlite3_io_methods,
    owner: *const c_void,
    id: u64,
    shm: u64,
    workspace: Option<Workspace>,
    vfs: *mut ffi::sqlite3_vfs,
}

// SAFETY of all callbacks: SQLite receives these pointers only from register/
// open below. Registered is boxed and Scope outlives every Database and file.
// File.real has exactly the original VFS's allocation and method table. Forward
// calls always use the ORIGINAL VFS/file, never another VFS's pAppData/layout.
unsafe fn registered<'a>(vfs: *mut ffi::sqlite3_vfs) -> &'a Registered {
    unsafe { &*vfs.cast::<Registered>() }
}
unsafe fn file<'a>(p: *mut ffi::sqlite3_file) -> &'a mut File {
    unsafe { &mut *p.cast::<File>() }
}
unsafe fn owner<'a>(p: *const c_void) -> &'a Owner<'a> {
    unsafe { &*p.cast::<Owner<'a>>() }
}

fn guarded(owner: &Owner<'_>, operation: impl FnOnce() -> Result<()>) -> c_int {
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(())) => ffi::SQLITE_OK,
        Ok(Err(error)) => {
            owner.fail(error);
            ffi::SQLITE_FULL
        }
        Err(_) => {
            owner.fail(anyhow::anyhow!("SQLITE_RESOURCE_CALLBACK_PANIC"));
            ffi::SQLITE_IOERR
        }
    }
}

pub(super) fn register(name: &CStr, owner: &Owner<'_>) -> Result<Box<Registered>> {
    // SAFETY: SQLite initializes its builtin VFS registry before lookup. The
    // original builtin VFS lives for the process; this registration is private.
    unsafe {
        let real = ffi::sqlite3_vfs_find(ptr::null());
        ensure!(!real.is_null(), "SQLITE_DEFAULT_VFS_MISSING");
        ensure!(
            (*real).xOpen.is_some()
                && (*real).xDelete.is_some()
                && (*real).xAccess.is_some()
                && (*real).xFullPathname.is_some(),
            "SQLITE_DEFAULT_VFS_INCOMPLETE"
        );
        let mut base = *real;
        base.iVersion = 1;
        base.szOsFile = std::mem::size_of::<File>().try_into()?;
        base.pNext = ptr::null_mut();
        base.zName = name.as_ptr();
        base.xOpen = Some(open);
        base.xDelete = Some(delete);
        base.xAccess = Some(access);
        base.xFullPathname = Some(full_path);
        base.xDlOpen = Some(dl_open);
        base.xDlError = Some(dl_error);
        base.xDlSym = Some(dl_symbol);
        base.xDlClose = Some(dl_close);
        base.xRandomness = Some(randomness);
        base.xSleep = Some(sleep);
        base.xCurrentTime = Some(current_time);
        base.xGetLastError = Some(last_error);
        let mut registered = Box::new(Registered {
            base,
            real,
            owner: owner as *const Owner<'_> as *const c_void,
        });
        ensure!(
            ffi::sqlite3_vfs_register(&mut registered.base, 0) == ffi::SQLITE_OK,
            "SQLITE_VFS_REGISTER_FAILED"
        );
        Ok(registered)
    }
}

unsafe extern "C" fn open(
    vfs: *mut ffi::sqlite3_vfs,
    name: *const c_char,
    output: *mut ffi::sqlite3_file,
    flags: c_int,
    out_flags: *mut c_int,
) -> c_int {
    unsafe {
        let registered = registered(vfs);
        let owner = owner(registered.owner);
        let path_bytes = if name.is_null() {
            0
        } else {
            CStr::from_ptr(name).to_bytes().len() as u64
        };
        let admitted = guarded(owner, || {
            owner.charge(1, path_bytes.saturating_mul(2).saturating_add(512))
        });
        if admitted != ffi::SQLITE_OK {
            return admitted;
        }
        let path = if name.is_null() {
            None
        } else {
            #[cfg(unix)]
            {
                use std::os::unix::ffi::OsStrExt;
                Some(PathBuf::from(std::ffi::OsStr::from_bytes(
                    CStr::from_ptr(name).to_bytes(),
                )))
            }
            #[cfg(not(unix))]
            {
                Some(PathBuf::from(
                    CStr::from_ptr(name).to_string_lossy().as_ref(),
                ))
            }
        };
        // Immutable published baselines are not task scratch. Anonymous/temp
        // files remain charged even when produced by a read-only connection.
        let hidden = flags & ffi::SQLITE_OPEN_DELETEONCLOSE != 0
            || flags & ffi::SQLITE_OPEN_READONLY == 0
                && path.as_ref().is_none_or(|p| !p.starts_with(&owner.root));
        let database = flags
            & (ffi::SQLITE_OPEN_MAIN_DB | ffi::SQLITE_OPEN_TEMP_DB | ffi::SQLITE_OPEN_TRANSIENT_DB)
            != 0;
        let bytes = u64::try_from((*registered.real).szOsFile).unwrap_or(0);
        let mut workspace = None;
        let rc = guarded(owner, || {
            workspace = Some(owner.workspace(
                bytes
                    + if database {
                        PAGER_ADMISSION_BYTES + SCHEMA_BYTES
                    } else {
                        64 << 10
                    },
            )?);
            Ok(())
        });
        if rc != ffi::SQLITE_OK {
            return rc;
        }
        let real = ffi::sqlite3_malloc64(bytes).cast::<ffi::sqlite3_file>();
        if real.is_null() {
            return ffi::SQLITE_NOMEM;
        }
        ptr::write_bytes(real.cast::<u8>(), 0, bytes as usize);
        let rc = (*registered.real).xOpen.unwrap()(registered.real, name, real, flags, out_flags);
        if rc != ffi::SQLITE_OK {
            if !(*real).pMethods.is_null() {
                ((*(*real).pMethods).xClose.unwrap())(real);
            }
            ffi::sqlite3_free(real.cast());
            return rc;
        }
        let methods = (*real).pMethods;
        let mut size = 0;
        let rc = ((*methods).xFileSize.unwrap())(real, &mut size);
        let mut id = 0;
        let admitted = if rc == ffi::SQLITE_OK {
            guarded(owner, || {
                id = owner.register(path, u64::try_from(size)?, hidden)?;
                Ok(())
            })
        } else {
            rc
        };
        if admitted != ffi::SQLITE_OK {
            ((*methods).xClose.unwrap())(real);
            ffi::sqlite3_free(real.cast());
            return admitted;
        }
        ptr::write(
            output.cast::<File>(),
            File {
                base: ffi::sqlite3_file { pMethods: &METHODS },
                real,
                methods,
                owner: registered.owner,
                id,
                shm: 0,
                workspace,
                vfs,
            },
        );
        ffi::SQLITE_OK
    }
}

unsafe extern "C" fn close(p: *mut ffi::sqlite3_file) -> c_int {
    unsafe {
        let f = file(p);
        let rc = ((*f.methods).xClose.unwrap())(f.real);
        owner(f.owner).state().files.remove(&f.id);
        owner(f.owner).state().files.remove(&f.shm);
        ffi::sqlite3_free(f.real.cast());
        drop(f.workspace.take());
        f.base.pMethods = ptr::null();
        rc
    }
}
unsafe extern "C" fn write(
    p: *mut ffi::sqlite3_file,
    bytes: *const c_void,
    amount: c_int,
    offset: i64,
) -> c_int {
    unsafe {
        let f = file(p);
        let owner = owner(f.owner);
        let mut old = 0;
        let rc = guarded(owner, || {
            owner.charge(u64::try_from(amount)?.div_ceil(4096), 0)?;
            let end = u64::try_from(offset)?
                .checked_add(u64::try_from(amount)?)
                .context("SQLITE_FILE_SIZE_OVERFLOW")?;
            let current = owner
                .state()
                .files
                .get(&f.id)
                .context("SQLITE_FILE_NOT_OWNED")?
                .size;
            old = owner.resize(f.id, end.max(current))?;
            Ok(())
        });
        if rc != ffi::SQLITE_OK {
            return rc;
        }
        let rc = ((*f.methods).xWrite.unwrap())(f.real, bytes, amount, offset);
        if rc != ffi::SQLITE_OK {
            owner.restore(f.id, old);
        }
        rc
    }
}
unsafe extern "C" fn truncate(p: *mut ffi::sqlite3_file, size: i64) -> c_int {
    unsafe {
        let f = file(p);
        let owner = owner(f.owner);
        let mut old = 0;
        let rc = guarded(owner, || {
            old = owner.resize(f.id, u64::try_from(size)?)?;
            Ok(())
        });
        if rc != ffi::SQLITE_OK {
            return rc;
        }
        let rc = ((*f.methods).xTruncate.unwrap())(f.real, size);
        if rc != ffi::SQLITE_OK {
            owner.restore(f.id, old);
        }
        rc
    }
}
unsafe extern "C" fn control(
    p: *mut ffi::sqlite3_file,
    operation: c_int,
    argument: *mut c_void,
) -> c_int {
    unsafe {
        let f = file(p);
        // A hint must not preallocate behind xWrite/xTruncate admission. Mmap and
        // WAL are disabled for these private, single-owner candidate databases.
        if operation == ffi::SQLITE_FCNTL_SIZE_HINT {
            return ffi::SQLITE_NOTFOUND;
        }
        if operation == ffi::SQLITE_FCNTL_CHUNK_SIZE {
            return ffi::SQLITE_NOTFOUND;
        }
        if operation == ffi::SQLITE_FCNTL_VFS_POINTER {
            *argument.cast::<*mut ffi::sqlite3_vfs>() = f.vfs;
            return ffi::SQLITE_OK;
        }
        if operation == ffi::SQLITE_FCNTL_FILE_POINTER {
            *argument.cast::<*mut ffi::sqlite3_file>() = p;
            return ffi::SQLITE_OK;
        }
        ((*f.methods).xFileControl.unwrap())(f.real, operation, argument)
    }
}
unsafe extern "C" fn delete(vfs: *mut ffi::sqlite3_vfs, name: *const c_char, sync: c_int) -> c_int {
    unsafe {
        let registered = registered(vfs);
        let rc = (*registered.real).xDelete.unwrap()(registered.real, name, sync);
        if rc == ffi::SQLITE_OK && !name.is_null() {
            #[cfg(unix)]
            {
                use std::os::unix::ffi::OsStrExt;
                owner(registered.owner).removed(Path::new(std::ffi::OsStr::from_bytes(
                    CStr::from_ptr(name).to_bytes(),
                )));
            }
            #[cfg(not(unix))]
            {
                owner(registered.owner)
                    .removed(Path::new(CStr::from_ptr(name).to_string_lossy().as_ref()));
            }
        }
        rc
    }
}
pub(super) unsafe extern "C" fn progress(data: *mut c_void) -> c_int {
    unsafe { i32::from(guarded(owner(data), || owner(data).charge(1, 0)) != ffi::SQLITE_OK) }
}

unsafe extern "C" fn shm_map(
    p: *mut ffi::sqlite3_file,
    region: c_int,
    size: c_int,
    extend: c_int,
    output: *mut *mut c_void,
) -> c_int {
    unsafe {
        let f = file(p);
        let owner = owner(f.owner);
        let Some(map) = (*f.methods).xShmMap else {
            return ffi::SQLITE_IOERR;
        };
        let mut old = 0;
        let rc = guarded(owner, || {
            let size = u64::try_from(size)?;
            let needed = u64::try_from(region)?
                .checked_add(1)
                .and_then(|n| n.checked_mul(size))
                .context("SQLITE_SHM_SIZE_OVERFLOW")?;
            if f.shm == 0 {
                let path = owner
                    .state()
                    .files
                    .get(&f.id)
                    .and_then(|file| file.path.clone())
                    .map(|p| {
                        let mut p = p.into_os_string();
                        p.push("-shm");
                        PathBuf::from(p)
                    });
                f.shm = owner.register(path, 0, true)?;
            }
            let current = owner.state().files[&f.shm].size;
            owner.charge(1, needed.saturating_sub(current))?;
            old = owner.resize(f.shm, needed.max(current))?;
            Ok(())
        });
        if rc != ffi::SQLITE_OK {
            return rc;
        }
        let rc = map(f.real, region, size, extend, output);
        if rc != ffi::SQLITE_OK || (*output).is_null() {
            owner.restore(f.shm, old);
        } else {
            let mut state = owner.state();
            if let Some(file) = state.files.get_mut(&f.shm) {
                file.hidden = file.path.as_ref().is_none_or(|p| !p.exists());
            }
        }
        rc
    }
}
unsafe extern "C" fn shm_lock(
    p: *mut ffi::sqlite3_file,
    offset: c_int,
    count: c_int,
    flags: c_int,
) -> c_int {
    unsafe {
        let f = file(p);
        (*f.methods)
            .xShmLock
            .map_or(ffi::SQLITE_IOERR, |lock| lock(f.real, offset, count, flags))
    }
}
unsafe extern "C" fn shm_barrier(p: *mut ffi::sqlite3_file) {
    unsafe {
        let f = file(p);
        if let Some(barrier) = (*f.methods).xShmBarrier {
            barrier(f.real);
        }
    }
}
unsafe extern "C" fn shm_unmap(p: *mut ffi::sqlite3_file, delete: c_int) -> c_int {
    unsafe {
        let f = file(p);
        let rc = (*f.methods)
            .xShmUnmap
            .map_or(ffi::SQLITE_OK, |unmap| unmap(f.real, delete));
        owner(f.owner).state().files.remove(&f.shm);
        f.shm = 0;
        rc
    }
}

macro_rules! file_forward {
    ($name:ident, $method:ident, ($($arg:ident:$ty:ty),*) -> $ret:ty) => {
        unsafe extern "C" fn $name(p: *mut ffi::sqlite3_file, $($arg:$ty),*) -> $ret {
            unsafe { let f = file(p); ((*f.methods).$method.unwrap())(f.real, $($arg),*) }
        }
    };
}
unsafe extern "C" fn read(
    p: *mut ffi::sqlite3_file,
    buffer: *mut c_void,
    amount: c_int,
    offset: i64,
) -> c_int {
    unsafe {
        let f = file(p);
        let owner = owner(f.owner);
        let rc = guarded(owner, || {
            owner.charge(u64::try_from(amount)?.div_ceil(4096), 0)
        });
        if rc != ffi::SQLITE_OK {
            return rc;
        }
        ((*f.methods).xRead.unwrap())(f.real, buffer, amount, offset)
    }
}
file_forward!(sync, xSync, (flags:c_int) -> c_int);
file_forward!(size, xFileSize, (size:*mut i64) -> c_int);
file_forward!(lock, xLock, (level:c_int) -> c_int);
file_forward!(unlock, xUnlock, (level:c_int) -> c_int);
file_forward!(reserved, xCheckReservedLock, (result:*mut c_int) -> c_int);
file_forward!(sector, xSectorSize, () -> c_int);
file_forward!(characteristics, xDeviceCharacteristics, () -> c_int);
static METHODS: ffi::sqlite3_io_methods = ffi::sqlite3_io_methods {
    iVersion: 2,
    xClose: Some(close),
    xRead: Some(read),
    xWrite: Some(write),
    xTruncate: Some(truncate),
    xSync: Some(sync),
    xFileSize: Some(size),
    xLock: Some(lock),
    xUnlock: Some(unlock),
    xCheckReservedLock: Some(reserved),
    xFileControl: Some(control),
    xSectorSize: Some(sector),
    xDeviceCharacteristics: Some(characteristics),
    xShmMap: Some(shm_map),
    xShmLock: Some(shm_lock),
    xShmBarrier: Some(shm_barrier),
    xShmUnmap: Some(shm_unmap),
    xFetch: None,
    xUnfetch: None,
};

macro_rules! vfs_forward {
    ($name:ident, $method:ident, ($($arg:ident:$ty:ty),*) -> $ret:ty, $missing:expr) => {
        unsafe extern "C" fn $name(p: *mut ffi::sqlite3_vfs, $($arg:$ty),*) -> $ret {
            unsafe { let v = registered(p); (*v.real).$method.map_or_else(|| $missing, |f| f(v.real, $($arg),*)) }
        }
    };
}
type DlSymbol = Option<unsafe extern "C" fn(*mut ffi::sqlite3_vfs, *mut c_void, *const c_char)>;
vfs_forward!(access, xAccess, (name:*const c_char, flags:c_int, result:*mut c_int) -> c_int, ffi::SQLITE_IOERR);
vfs_forward!(full_path, xFullPathname, (name:*const c_char, size:c_int, result:*mut c_char) -> c_int, ffi::SQLITE_IOERR);
vfs_forward!(dl_open, xDlOpen, (name:*const c_char) -> *mut c_void, ptr::null_mut());
vfs_forward!(dl_error, xDlError, (size:c_int, result:*mut c_char) -> (), ());
vfs_forward!(dl_symbol, xDlSym, (library:*mut c_void, name:*const c_char) -> DlSymbol, None);
vfs_forward!(dl_close, xDlClose, (library:*mut c_void) -> (), ());
vfs_forward!(randomness, xRandomness, (bytes:c_int, result:*mut c_char) -> c_int, 0);
vfs_forward!(sleep, xSleep, (micros:c_int) -> c_int, 0);
vfs_forward!(current_time, xCurrentTime, (time:*mut f64) -> c_int, ffi::SQLITE_IOERR);
vfs_forward!(last_error, xGetLastError, (bytes:c_int, result:*mut c_char) -> c_int, 0);
