use crate::sdk::Sdk;
use anyhow::{Context, Result, ensure};
use codeana_contracts::protobuf::typescript::v1 as p;
use libloading::Library;
use prost::Message;
use std::{marker::PhantomData, mem::ManuallyDrop, path::Path, rc::Rc};

const MAX_RESULT: usize = 1 << 20;
type Abi = unsafe extern "C" fn() -> u32;
type Identity = unsafe extern "C" fn(u64, *mut u8, u32, *mut u32) -> i32;
type Create = unsafe extern "C" fn(u64, u64, *mut u64) -> i32;
type Feed = unsafe extern "C" fn(u64, *mut u8, u32) -> i32;
type Unary = unsafe extern "C" fn(u64) -> i32;
type SetFd = unsafe extern "C" fn(u64, i32) -> i32;
type Poll = unsafe extern "C" fn(u64, *mut u32) -> i32;
type Read = unsafe extern "C" fn(u64, u64, *mut u8, u32, *mut u32) -> i32;
type Close = unsafe extern "C" fn(u64, u32) -> i32;

struct ProbeSession {
    handle: u64,
    close: Close,
}
impl Drop for ProbeSession {
    fn drop(&mut self) {
        if self.handle != 0 {
            unsafe {
                (self.close)(self.handle, 2000);
            }
        }
    }
}

pub struct Native {
    _library: ManuallyDrop<Library>,
    pub runtime: p::RuntimeIdentity,
    pub compiler: codeana_model::typescript::CompilerIdentity,
    create: Create,
    feed: Feed,
    seal: Unary,
    set_fd: SetFd,
    start: Unary,
    poll: Poll,
    read: Read,
    cancel: Unary,
    ack: Unary,
    close: Close,
    handle: u64,
    used: bool,
    _thread: PhantomData<Rc<()>>,
}

fn status(code: i32) -> Result<()> {
    ensure!(code == 0, "TYPESCRIPT_ABI_STATUS: {code}");
    Ok(())
}

fn chunks(mut read: impl FnMut(u64, &mut [u8], &mut u32) -> i32, limit: usize) -> Result<Vec<u8>> {
    let mut output = Vec::with_capacity(limit);
    let mut buffer = [0u8; 4096];
    loop {
        let mut written = 0;
        let code = read(output.len() as u64, &mut buffer, &mut written);
        ensure!(
            written as usize <= buffer.len(),
            "TYPESCRIPT_ABI_CHUNK_SIZE"
        );
        if code == 1 {
            ensure!(written == 0, "TYPESCRIPT_ABI_EOF");
            break;
        }
        status(code)?;
        ensure!(
            written > 0 && written as usize <= limit.saturating_sub(output.len()),
            "TYPESCRIPT_RESULT_BUDGET"
        );
        output.extend_from_slice(&buffer[..written as usize]);
    }
    Ok(output)
}

impl Native {
    /// Explicit ABI gate only; the production worker still executes one task.
    pub fn conformance(&mut self) -> Result<Vec<&'static str>> {
        let mut checks = Vec::new();
        macro_rules! checked {
            ($condition:expr,$label:literal) => {{
                ensure!($condition, $label);
                checks.push($label);
            }};
        }

        use std::{
            io::Read,
            os::{fd::AsRawFd, unix::net::UnixStream},
        };
        checked!(!self.used, "TYPESCRIPT_WORKER_RESTART_REQUIRED");
        self.used = true;
        unsafe {
            let identity = *self
                ._library
                .get::<Identity>(b"codeana_ts_runtime_identity")?;
            let mut canary = [0xcc; 3];
            let mut written = 99;
            checked!(
                identity(0, canary.as_mut_ptr().add(1), 1, &mut written) == 0
                    && written == 1
                    && canary[0] == 0xcc
                    && canary[2] == 0xcc,
                "TYPESCRIPT_ABI_IDENTITY_CANARY"
            );
            let mut state = 99;
            checked!(
                (self.poll)(u64::MAX, &mut state) == -1 && state == 0,
                "TYPESCRIPT_ABI_BAD_HANDLE"
            );
            let mut handle = 99;
            checked!(
                (self.create)(0, 4096, &mut handle) == -2 && handle == 0,
                "TYPESCRIPT_ABI_CREATE_BOUND"
            );
            status((self.create)(8, 4096, &mut handle))?;
            let mut probe = ProbeSession {
                handle,
                close: self.close,
            };
            checked!(
                (self.poll)(handle, std::ptr::null_mut()) == -2,
                "TYPESCRIPT_ABI_NULL_STATE"
            );
            checked!(
                (self.feed)(handle, std::ptr::null_mut(), 1) == -2,
                "TYPESCRIPT_ABI_NULL_INPUT"
            );
            checked!(
                (self.feed)(handle, canary.as_mut_ptr(), (4 << 20) + 1) == -2,
                "TYPESCRIPT_ABI_CHUNK_BOUND"
            );
            let mut data = [1, 2, 3, 4, 5];
            status((self.feed)(handle, data.as_mut_ptr(), 4))?;
            data.fill(0xff);
            checked!(
                (self.feed)(handle, data.as_mut_ptr(), 5) == -4,
                "TYPESCRIPT_ABI_INPUT_BOUND"
            );
            status((self.seal)(handle))?;
            checked!(
                (self.seal)(handle) == -3 && (self.start)(handle) == -3 && (self.ack)(handle) == -3,
                "TYPESCRIPT_ABI_STATE"
            );
            checked!((self.set_fd)(handle, -1) == -2, "TYPESCRIPT_ABI_BAD_FD");
            let (mut reader, writer) = UnixStream::pair()?;
            checked!(
                (self.set_fd)(handle, writer.as_raw_fd()) == -2,
                "TYPESCRIPT_ABI_BLOCKING_FD"
            );
            reader.set_nonblocking(true)?;
            writer.set_nonblocking(true)?;
            status((self.set_fd)(handle, writer.as_raw_fd()))?;
            checked!(
                (self.set_fd)(handle, writer.as_raw_fd()) == -3,
                "TYPESCRIPT_ABI_DUPLICATE_FD"
            );
            checked!(
                libc::fcntl(writer.as_raw_fd(), libc::F_GETFD) >= 0,
                "TYPESCRIPT_ABI_CALLER_FD_CLOSED"
            );
            drop(writer);
            written = 99;
            checked!(
                (self.read)(handle, 0, canary.as_mut_ptr().add(1), 1, &mut written) == 2
                    && written == 0,
                "TYPESCRIPT_ABI_PENDING"
            );
            status((self.cancel)(handle))?;
            status((self.cancel)(handle))?;
            let mut token = [0; 2];
            checked!(
                reader.read(&mut token)? == 1 && token[0] == 1 && reader.read(&mut token)? == 0,
                "TYPESCRIPT_ABI_NOTIFY_ONCE_EOF"
            );
            written = 99;
            canary.fill(0xcc);
            checked!(
                (self.read)(handle, 0, canary.as_mut_ptr().add(1), 1, &mut written) == 0
                    && written == 1
                    && canary[0] == 0xcc
                    && canary[2] == 0xcc,
                "TYPESCRIPT_ABI_RESULT_CANARY"
            );
            let result = chunks(
                |offset, buffer, count| {
                    (self.read)(
                        handle,
                        offset,
                        buffer.as_mut_ptr(),
                        buffer.len() as u32,
                        count,
                    )
                },
                4096,
            )?;
            let decoded = codeana_contracts::typescript::decode_result(&result)?;
            checked!(
                matches!(decoded.outcome,Some(p::result::Outcome::Failure(ref f))if f.code==p::FailureCode::Cancelled as i32),
                "TYPESCRIPT_ABI_CANCEL_RESULT"
            );
            written = 99;
            checked!(
                (self.read)(
                    handle,
                    result.len() as u64,
                    canary.as_mut_ptr().add(1),
                    1,
                    &mut written
                ) == 1
                    && written == 0,
                "TYPESCRIPT_ABI_RESULT_EOF"
            );
            checked!(
                (self.read)(
                    handle,
                    result.len() as u64 + 1,
                    canary.as_mut_ptr(),
                    1,
                    &mut written
                ) == -2
                    && written == 0,
                "TYPESCRIPT_ABI_RESULT_OFFSET"
            );
            status((self.close)(handle, 0))?;
            probe.handle = 0;
            checked!(
                (self.close)(handle, 0) == -1 && (self.poll)(handle, &mut state) == -1,
                "TYPESCRIPT_ABI_CLOSED_HANDLE"
            );
        }
        Ok(checks)
    }
    pub fn load(root: &Path) -> Result<Self> {
        Self::load_with_fixture(root, false)
    }
    pub fn load_with_fixture(root: &Path, allow_fixture: bool) -> Result<Self> {
        let sdk = Sdk::load_with_fixture(root, allow_fixture)?;
        // Pin immediately, including error paths after initialization. Go must
        // never be reset by dlclose; the worker process owns its final lifetime.
        let library = ManuallyDrop::new(unsafe {
            Library::new(sdk.root.join("libcodeana_typescript.dylib"))
        }?);
        unsafe {
            let abi = *library.get::<Abi>(b"codeana_ts_abi_version")?;
            ensure!(abi() == 1, "TYPESCRIPT_ABI_VERSION");
            let identity = *library.get::<Identity>(b"codeana_ts_runtime_identity")?;
            let bytes = chunks(
                |offset, buffer, written| {
                    identity(offset, buffer.as_mut_ptr(), buffer.len() as u32, written)
                },
                16 << 10,
            )?;
            let runtime = p::RuntimeIdentity::decode(bytes.as_slice())?;
            ensure!(
                runtime == sdk.runtime,
                "TYPESCRIPT_RUNTIME_IDENTITY_MISMATCH"
            );
            Ok(Self {
                create: *library.get(b"codeana_ts_session_create")?,
                feed: *library.get(b"codeana_ts_input_chunk")?,
                seal: *library.get(b"codeana_ts_seal_input")?,
                set_fd: *library.get(b"codeana_ts_set_completion_fd")?,
                start: *library.get(b"codeana_ts_analyze_start")?,
                poll: *library.get(b"codeana_ts_poll_event")?,
                read: *library.get(b"codeana_ts_read_result_chunk")?,
                cancel: *library.get(b"codeana_ts_cancel")?,
                ack: *library.get(b"codeana_ts_publish_ack")?,
                close: *library.get(b"codeana_ts_session_close")?,
                compiler: sdk.semantic_identity(),
                _library: library,
                runtime,
                handle: 0,
                used: false,
                _thread: PhantomData,
            })
        }
    }
    pub fn prepare(&mut self, mut input: Vec<u8>, fd: i32) -> Result<()> {
        ensure!(!self.used, "TYPESCRIPT_WORKER_RESTART_REQUIRED");
        self.used = true;
        unsafe {
            status((self.create)(
                input.len().max(1) as u64,
                MAX_RESULT as u64,
                &mut self.handle,
            ))?;
            ensure!(self.handle != 0, "TYPESCRIPT_HANDLE_REQUIRED");
            status((self.set_fd)(self.handle, fd))?;
            for chunk in input.chunks_mut(64 << 10) {
                status((self.feed)(
                    self.handle,
                    chunk.as_mut_ptr(),
                    chunk.len() as u32,
                ))?;
                // This deliberately proves that Go copied each caller buffer.
                chunk.fill(0xa5);
            }
            status((self.seal)(self.handle))?;
        }
        Ok(())
    }
    pub fn run(&mut self) -> Result<()> {
        ensure!(self.handle != 0, "TYPESCRIPT_SESSION_REQUIRED");
        unsafe { status((self.start)(self.handle)) }
    }
    pub fn fixture_phase(&mut self) -> Result<u32> {
        unsafe {
            let phase = *self._library.get::<Abi>(b"codeana_ts_fixture_phase")?;
            Ok(phase())
        }
    }
    pub fn fixture_release(&mut self) -> Result<()> {
        unsafe {
            let release = *self
                ._library
                .get::<unsafe extern "C" fn()>(b"codeana_ts_fixture_release")?;
            release();
        }
        Ok(())
    }
    pub fn try_close(&mut self) -> Result<i32> {
        ensure!(self.handle != 0, "TYPESCRIPT_SESSION_REQUIRED");
        Ok(unsafe { (self.close)(self.handle, 0) })
    }
    pub fn state(&mut self) -> Result<u32> {
        ensure!(self.handle != 0, "TYPESCRIPT_SESSION_REQUIRED");
        let mut state = 0;
        unsafe { status((self.poll)(self.handle, &mut state))? };
        Ok(state)
    }
    pub fn result(&mut self) -> Result<Vec<u8>> {
        ensure!(matches!(self.state()?, 5..=8), "TYPESCRIPT_RESULT_PENDING");
        unsafe {
            chunks(
                |offset, buffer, written| {
                    (self.read)(
                        self.handle,
                        offset,
                        buffer.as_mut_ptr(),
                        buffer.len() as u32,
                        written,
                    )
                },
                MAX_RESULT,
            )
        }
    }
    pub fn cancel(&mut self) -> Result<()> {
        ensure!(self.handle != 0, "TYPESCRIPT_SESSION_REQUIRED");
        unsafe { status((self.cancel)(self.handle)) }
    }
    pub fn published(&mut self) -> Result<()> {
        ensure!(self.handle != 0, "TYPESCRIPT_SESSION_REQUIRED");
        unsafe { status((self.ack)(self.handle)) }
    }
    pub fn close(&mut self) -> Result<()> {
        if self.handle != 0 {
            unsafe { status((self.close)(self.handle, 2000)) }
                .context("TYPESCRIPT_CLOSE_INCOMPLETE")?;
            self.handle = 0;
        }
        Ok(())
    }
}
impl Drop for Native {
    fn drop(&mut self) {
        let _ = self.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn result_chunk_protocol_cannot_overrun_the_buffer_or_certify_fake_eof() {
        for (status, written) in [(0, 0), (0, 4097), (1, 1), (-1, 0)] {
            assert!(
                chunks(
                    |_, _, count| {
                        *count = written;
                        status
                    },
                    4096
                )
                .is_err()
            );
        }
        let mut calls = 0;
        let data = chunks(
            |offset, buffer, count| {
                calls += 1;
                if offset == 0 {
                    buffer[0] = 7;
                    *count = 1;
                    0
                } else {
                    *count = 0;
                    1
                }
            },
            4096,
        )
        .unwrap();
        assert_eq!(data, vec![7]);
        assert_eq!(calls, 2);
    }
}
