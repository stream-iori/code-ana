//! Bounded, read-only filesystem observations shared by preparation and capture.
use anyhow::{Context, Result, ensure};
use std::{
    ffi::{CStr, CString, OsStr},
    fs::File,
    io,
    os::{fd::AsRawFd, unix::ffi::OsStrExt},
};

fn stat(parent: &File, name: &CStr) -> io::Result<libc::stat> {
    let mut value = std::mem::MaybeUninit::<libc::stat>::uninit();
    // SAFETY: borrowed live directory, valid C string, writable stat storage.
    if unsafe {
        libc::fstatat(
            parent.as_raw_fd(),
            name.as_ptr(),
            value.as_mut_ptr(),
            libc::AT_SYMLINK_NOFOLLOW,
        )
    } != 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { value.assume_init() })
}
fn same(a: &libc::stat, b: &libc::stat) -> bool {
    a.st_dev == b.st_dev && a.st_ino == b.st_ino
}

/// Observe the volume containing an existing named entry. Unlike an executable
/// path probe, the caller's anchored parent is on the target filesystem. Two
/// differently cased hardlinks must not be mistaken for a case-insensitive FS.
pub fn case_sensitive(
    parent: &File,
    name: &OsStr,
    mut charge: impl FnMut(u64, u64) -> Result<()>,
) -> Result<bool> {
    let bytes = name.as_bytes();
    ensure!(
        bytes.len() <= 4096 && !bytes.contains(&b'/'),
        "INPUT_CASE_PROBE_NAME"
    );
    charge(1, bytes.len() as u64 * 4 + 512)?;
    let original = CString::new(bytes)?;
    let first = stat(parent, &original)?;
    let Some(offset) = bytes.iter().position(u8::is_ascii_alphabetic) else {
        anyhow::bail!("INPUT_CASE_PROBE_ASCII_ENTRY_REQUIRED")
    };
    let mut swapped = bytes.to_vec();
    swapped[offset] ^= 32;
    let swapped = CString::new(swapped)?;
    charge(1, 0)?;
    let candidate = match stat(parent, &swapped) {
        Ok(other) => other,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(true),
        Err(error) => return Err(error.into()),
    };
    if !same(&first, &candidate) {
        return Ok(true);
    }
    charge(1, 8192)?;
    // fdopendir owns only the duplicate; openat users retain the anchor.
    let duplicate = unsafe { libc::fcntl(parent.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
    ensure!(duplicate >= 0, io::Error::last_os_error());
    let stream = unsafe { libc::fdopendir(duplicate) };
    if stream.is_null() {
        let error = io::Error::last_os_error();
        unsafe { libc::close(duplicate) };
        return Err(error.into());
    }
    struct Stream(*mut libc::DIR);
    impl Drop for Stream {
        fn drop(&mut self) {
            unsafe {
                libc::closedir(self.0);
            }
        }
    }
    let stream = Stream(stream);
    unsafe {
        libc::rewinddir(stream.0);
    }
    let mut original_seen = false;
    let mut swapped_seen = false;
    for _ in 0..100_000 {
        // Fund the next bounded OS entry before inspecting/allocating its name.
        charge(1, 512)?;
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
            ensure!(original_seen, "INPUT_CASE_PROBE_CHANGED");
            charge(2, 0)?;
            ensure!(
                same(&first, &stat(parent, &original)?)
                    && same(&candidate, &stat(parent, &swapped)?),
                "INPUT_CASE_PROBE_CHANGED"
            );
            return Ok(swapped_seen);
        }
        let entry = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) };
        original_seen |= entry == original.as_c_str();
        swapped_seen |= entry == swapped.as_c_str();
    }
    anyhow::bail!("INPUT_CASE_PROBE_ENTRY_LIMIT")
}

pub fn case_sensitive_path(
    path: &std::path::Path,
    charge: impl FnMut(u64, u64) -> Result<()>,
) -> Result<bool> {
    use std::os::unix::fs::OpenOptionsExt;
    let parent = path.parent().context("INPUT_CASE_PROBE_PARENT")?;
    let parent = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(parent)?;
    case_sensitive(
        &parent,
        path.file_name().context("INPUT_CASE_PROBE_NAME")?,
        charge,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn probe_uses_target_volume_and_never_mistakes_two_hardlinks_for_case_folding() -> Result<()> {
        let root = tempfile::tempdir()?;
        let path = root.path().join("Case.json");
        std::fs::write(&path, b"{}")?;
        let initial = case_sensitive_path(&path, |_, _| Ok(()))?;
        let other = root.path().join("case.json");
        if initial {
            std::fs::hard_link(&path, &other)?;
            assert!(case_sensitive_path(&path, |_, _| Ok(()))?);
        } else {
            assert!(std::fs::hard_link(&path, &other).is_err());
            assert!(!case_sensitive_path(&path, |_, _| Ok(()))?);
        }
        Ok(())
    }
    #[test]
    fn metadata_work_is_refused_before_directory_enumeration() -> Result<()> {
        let root = tempfile::tempdir()?;
        let path = root.path().join("Case.json");
        std::fs::write(&path, b"{}")?;
        assert!(case_sensitive_path(&path, |_, _| anyhow::bail!("quota")).is_err());
        assert_eq!(std::fs::read(path)?, b"{}");
        Ok(())
    }
}
