use anyhow::{Context, Result, ensure};
use libloading::Library;
use std::{
    ffi::c_void,
    path::Path,
    sync::atomic::{AtomicU8, Ordering},
};

// G1 keeps process-global state after teardown. Once claimed, this process may
// never create another isolate; recovery must spawn a new worker process.
static ISOLATE_HISTORY: AtomicU8 = AtomicU8::new(0);

fn claim_isolate(gc: &str, history: &AtomicU8) -> Result<()> {
    ensure!(
        matches!(gc, "serial" | "G1"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::NativeGcUnsupported, "")
    );
    history
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |previous| {
            if gc == "G1" {
                (previous == 0).then_some(2)
            } else {
                (previous != 2).then_some(1)
            }
        })
        .map_err(|_| {
            anyhow::anyhow!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::G1IsolateProcessRestartRequired,
                ""
            ))
        })?;
    Ok(())
}

fn manifest_gc(manifest: &codeana_contracts::bundle::BundleManifest) -> Result<&str> {
    let gc = manifest
        .native_gc
        .as_deref()
        .context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::BundleGcRequired,
            "",
        ))?;
    ensure!(
        matches!(gc, "serial" | "G1"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::BundleGcUnsupported, "")
    );
    Ok(gc)
}

// graal_isolate.h v5. The build-JDK header's layout is verified by the native acceptance probe.
#[repr(C)]
struct IsolateParams {
    version: i32,
    reserved_address_space_size: usize,
    auxiliary_image_path: *const std::ffi::c_char,
    auxiliary_image_reserved_space_size: usize,
    argc: i32,
    argv: *mut *mut std::ffi::c_char,
    pkey: i32,
    ignore_unrecognized_args: i8,
    reserved_4: i8,
    reserved_5: i8,
}
type HeapBytes = unsafe extern "C" fn(*mut c_void) -> u64;
type Create = unsafe extern "C" fn(*const c_void, *mut *mut c_void, *mut *mut c_void) -> i32;
type TearDown = unsafe extern "C" fn(*mut c_void) -> i32;
type Version = unsafe extern "C" fn(*mut c_void) -> i32;
type Session = unsafe extern "C" fn(*mut c_void) -> u64;
type Control = unsafe extern "C" fn(*mut c_void, u64) -> i32;
type Input = unsafe extern "C" fn(*mut c_void, u64, *const u8, i32) -> i32;
type Completion = unsafe extern "C" fn(*mut c_void, u64, i32) -> i32;
type Read = unsafe extern "C" fn(*mut c_void, u64, u64, *mut u8, i32) -> i32;

/// Confined to the calling OS thread. Library outlives every entry point and isolate.
pub struct Native {
    library: Library,
    thread: *mut c_void,
    task: u64,
    _not_send: std::marker::PhantomData<std::rc::Rc<()>>,
}
impl Native {
    pub fn load(path: &Path) -> Result<Self> {
        Self::create(path, None)
    }
    pub fn load_with_heap(path: &Path, heap: u64) -> Result<Self> {
        ensure!(
            (8 * 1024 * 1024..=2 * 1024 * 1024 * 1024).contains(&heap)
                && heap.is_multiple_of(1024 * 1024),
            codeana_model::DomainError::new(codeana_model::ErrorCode::JavaNativeHeapRange, "")
        );
        Self::create(path, Some(heap))
    }
    fn create(path: &Path, heap: Option<u64>) -> Result<Self> {
        ensure!(
            path.is_file(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::NativeLibraryMissing, "")
        );
        let manifest: codeana_contracts::bundle::BundleManifest =
            serde_json::from_slice(&std::fs::read(
                path.parent()
                    .context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::BundlePath,
                        "",
                    ))?
                    .join("manifest.json"),
            )?)?;
        let gc = manifest_gc(&manifest)?;
        // SAFETY: loaded only in the isolated worker, after bundle verification by the caller.
        unsafe {
            let library = Library::new(path)?;
            let create = *library.get::<Create>(b"graal_create_isolate")?;
            // Resolve the stable bootstrap before creating an isolate.
            let version = *library.get::<Version>(b"codeana_java_abi_version")?;
            let _ = library.get::<TearDown>(b"graal_tear_down_isolate")?;
            let mut isolate = std::ptr::null_mut();
            let mut thread = std::ptr::null_mut();
            let program = std::ffi::CString::new("codeana-java-worker")?;
            let option = std::ffi::CString::new(format!(
                "-Xmx{}m",
                heap.unwrap_or(256 * 1024 * 1024) / (1024 * 1024)
            ))?;
            // Observation is opt-in. macOS Java ignores TMPDIR for its default
            // temp directory; configure the isolate before TempFileHelper starts.
            let performance_temp = std::env::var_os("CODEANA_PERFORMANCE_TMPDIR")
                .map(|value| -> Result<_> {
                    let path = std::fs::canonicalize(value)?;
                    ensure!(
                        path.is_dir(),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::PerformanceTmpdirRequired,
                            ""
                        )
                    );
                    Ok(std::ffi::CString::new(format!(
                        "-Djava.io.tmpdir={}",
                        path.to_str().context(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::PerformanceTmpdirUtf8Required,
                            ""
                        ))?
                    ))?)
                })
                .transpose()?;
            let mut arguments = vec![program.as_ptr().cast_mut(), option.as_ptr().cast_mut()];
            if let Some(property) = &performance_temp {
                arguments.push(property.as_ptr().cast_mut());
            }
            let mut params: IsolateParams = std::mem::zeroed();
            params.version = 5;
            params.argc = arguments.len() as i32;
            params.argv = arguments.as_mut_ptr();
            let address = if heap.is_some() || performance_temp.is_some() {
                (&params as *const IsolateParams).cast()
            } else {
                std::ptr::null()
            };
            claim_isolate(gc, &ISOLATE_HISTORY)?;
            let status = create(address, &mut isolate, &mut thread);
            ensure!(
                status == 0,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::IsolateCreate,
                    format!("{status}")
                )
            );
            let native = Self {
                library,
                thread,
                task: 0,
                _not_send: std::marker::PhantomData,
            };
            ensure!(
                version(thread) == 4,
                codeana_model::DomainError::new(codeana_model::ErrorCode::AbiVersionMismatch, "")
            );
            let _ = native
                .library
                .get::<Completion>(b"codeana_java_set_completion_fd")?;
            if let Some(expected) = heap {
                let actual = native.heap_bytes()?;
                ensure!(
                    actual == expected,
                    "NATIVE_HEAP_MISMATCH expected={expected} actual={actual}"
                );
            }
            Ok(native)
        }
    }
    pub fn heap_bytes(&self) -> Result<u64> {
        unsafe {
            Ok(self.library.get::<HeapBytes>(b"codeana_java_heap_bytes")?(
                self.thread,
            ))
        }
    }
    fn control(&self, symbol: &[u8]) -> Result<i32> {
        // SAFETY: all ABI handles stay on this OS thread and task remains owned by self.
        unsafe { Ok(self.library.get::<Control>(symbol)?(self.thread, self.task)) }
    }
    pub fn start(&mut self, request: &[u8]) -> Result<()> {
        self.start_inner(request, None)
    }
    pub fn start_notified(&mut self, request: &[u8], fd: i32) -> Result<()> {
        self.start_inner(request, Some(fd))
    }
    fn start_inner(&mut self, request: &[u8], notification: Option<i32>) -> Result<()> {
        ensure!(
            self.task == 0 && request.len() <= codeana_model::MAX_RESULT,
            codeana_model::DomainError::new(codeana_model::ErrorCode::NativeInputBudget, "")
        );
        unsafe {
            self.task =
                self.library
                    .get::<Session>(b"codeana_java_session_create")?(self.thread);
            ensure!(
                self.task != 0,
                codeana_model::DomainError::new(codeana_model::ErrorCode::SessionCreate, "")
            );
            if let Some(fd) = notification {
                ensure!(
                    self.library
                        .get::<Completion>(b"codeana_java_set_completion_fd")?(
                        self.thread,
                        self.task,
                        fd
                    ) == 0,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::CompletionFdRegister,
                        ""
                    )
                );
            }
            let input = self.library.get::<Input>(b"codeana_java_input_chunk")?;
            for chunk in request.chunks(1024 * 1024) {
                ensure!(
                    input(self.thread, self.task, chunk.as_ptr(), chunk.len() as i32) == 0,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::InputChunk, "")
                );
            }
        }
        ensure!(
            self.control(b"codeana_java_seal_input")? == 0,
            codeana_model::DomainError::new(codeana_model::ErrorCode::SealInput, "")
        );
        ensure!(
            self.control(b"codeana_java_analyze_start")? == 0,
            codeana_model::DomainError::new(codeana_model::ErrorCode::AnalyzeStart, "")
        );
        Ok(())
    }
    pub fn poll(&self) -> Result<crate::state::RunState> {
        crate::state::decode(self.control(b"codeana_java_poll_event")?)
    }
    pub fn cancel(&self) -> Result<()> {
        ensure!(
            self.control(b"codeana_java_cancel")? == 0,
            codeana_model::DomainError::new(codeana_model::ErrorCode::Cancel, "")
        );
        Ok(())
    }
    pub fn result_file(&self) -> Result<tempfile::NamedTempFile> {
        self.result_file_with_limit(codeana_model::MAX_RESULT as u64, None)
    }
    pub fn result_file_with_limit(
        &self,
        max_bytes: u64,
        directory: Option<&Path>,
    ) -> Result<tempfile::NamedTempFile> {
        use std::io::Write;
        let mut out = match directory {
            Some(path) => tempfile::NamedTempFile::new_in(path)?,
            None => tempfile::NamedTempFile::new()?,
        };
        let mut buffer = vec![0u8; 64 * 1024];
        let mut offset = 0usize;
        loop {
            let n = unsafe {
                self.library
                    .get::<Read>(b"codeana_java_read_result_chunk")?(
                    self.thread,
                    self.task,
                    offset as u64,
                    buffer.as_mut_ptr(),
                    buffer.len() as i32,
                )
            };
            ensure!(
                n >= 0 && n as usize <= buffer.len(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::NativeRead, "")
            );
            if n == 0 {
                break;
            }
            ensure!(
                offset as u64 + n as u64 <= max_bytes,
                codeana_model::DomainError::new(codeana_model::ErrorCode::NativeResultBudget, "")
            );
            out.write_all(&buffer[..n as usize])?;
            offset += n as usize;
        }
        out.flush()?;
        Ok(out)
    }
    // Probe output is explicitly bounded; product transport uses result_file instead.
    pub fn result(&self) -> Result<Vec<u8>> {
        Ok(std::fs::read(self.result_file()?.path())?)
    }
    pub fn ack(&self) -> Result<()> {
        ensure!(
            self.control(b"codeana_java_publish_ack")? == 0,
            codeana_model::DomainError::new(codeana_model::ErrorCode::PublishAck, "")
        );
        Ok(())
    }
    pub fn finish(&mut self) -> Result<()> {
        ensure!(
            self.task != 0,
            codeana_model::DomainError::new(codeana_model::ErrorCode::SessionNotStarted, "")
        );
        ensure!(
            self.control(b"codeana_java_session_close")? == 0,
            codeana_model::DomainError::new(codeana_model::ErrorCode::SessionClose, "")
        );
        self.task = 0;
        Ok(())
    }
    pub fn hello(&self) -> Result<i32> {
        unsafe {
            Ok(self.library.get::<Version>(b"codeana_java_hello")?(
                self.thread,
            ))
        }
    }
}
impl Drop for Native {
    fn drop(&mut self) {
        if self.task != 0 && self.control(b"codeana_java_session_close").ok() != Some(0) {
            // Never unload code while a native analysis thread can still execute it.
            std::process::exit(70);
        }
        unsafe {
            if let Ok(teardown) = self.library.get::<TearDown>(b"graal_tear_down_isolate") {
                let _ = teardown(self.thread);
            }
        }
    }
}
pub fn verify_bundle(path: &Path) -> Result<std::path::PathBuf> {
    let parent = path.parent().context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::BundlePath,
        "",
    ))?;
    let root = parent.parent().context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::BundleRoot,
        "",
    ))?;
    let manifest: codeana_contracts::bundle::BundleManifest =
        serde_json::from_slice(&std::fs::read(parent.join("manifest.json"))?)?;
    ensure!(
        manifest.version == 2
            && manifest.backend == "native"
            && manifest.worker_protocol == codeana_model::WORKER_PROTOCOL_VERSION
            && manifest.fact_ir == codeana_model::VERSION
            && manifest.abi == 4
            && manifest.analysis_request == 10
            && manifest.compiler_protocol == 2
            && manifest.compiler_batch_protocol == 2
            && manifest.adapter == "anatomist-4a903272-codeana-graph2-maven-fallback1"
            && manifest.java_model == "javac-model-v50"
            && manifest.graph_schema == 33
            && manifest.graph_encoding == "GRA1-protobuf-row-v1",
        codeana_model::DomainError::new(codeana_model::ErrorCode::BundleVersionMismatch, "")
    );
    let os = if cfg!(target_os = "macos") {
        "Darwin"
    } else {
        "Linux"
    };
    let arch = if cfg!(target_arch = "aarch64") {
        "arm64"
    } else {
        "x86_64"
    };
    ensure!(
        manifest.os == os && manifest.arch == arch,
        codeana_model::DomainError::new(codeana_model::ErrorCode::BundlePlatformMismatch, "")
    );
    manifest_gc(&manifest)?;
    let files = &manifest.files;
    let library_key = format!(
        "native/{}",
        path.file_name()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::LibraryName,
                ""
            ))?
            .to_string_lossy()
    );
    ensure!(
        files.contains_key(&library_key)
            && files.contains_key("compiler-bridge.jar")
            && files.contains_key("maven-input-worker.jar"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::BundleFilesRequired, "")
    );
    for (name, expected) in files {
        ensure!(
            codeana_model::safe_path(name),
            codeana_model::DomainError::new(codeana_model::ErrorCode::BundleUnsafePath, "")
        );
        ensure!(
            codeana_model::hash(std::fs::read(root.join(name))?) == expected.as_str(),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::BundleHashMismatch,
                name.to_string()
            )
        );
    }
    Ok(root.join("compiler-bridge.jar"))
}

#[cfg(test)]
mod bootstrap_tests {
    use super::*;
    #[test]
    fn g1_claim_is_process_lifetime_and_serial_cannot_follow_it() {
        let history = AtomicU8::new(0);
        claim_isolate("G1", &history).unwrap();
        assert!(
            claim_isolate("G1", &history)
                .unwrap_err()
                .to_string()
                .contains("PROCESS_RESTART_REQUIRED")
        );
        assert!(claim_isolate("serial", &history).is_err());
    }
    #[test]
    fn serial_can_repeat_but_cannot_switch_to_g1_in_process() {
        let history = AtomicU8::new(0);
        claim_isolate("serial", &history).unwrap();
        claim_isolate("serial", &history).unwrap();
        assert!(claim_isolate("G1", &history).is_err());
        let fresh = AtomicU8::new(0);
        assert!(claim_isolate("unknown", &fresh).is_err());
        claim_isolate("G1", &fresh).unwrap();
    }
    #[test]
    #[ignore = "requires an explicitly built G1 shared library"]
    fn real_g1_recreation_returns_error_after_teardown() {
        let path = std::env::var_os("CODEANA_TEST_G1_LIBRARY")
            .expect("CODEANA_TEST_G1_LIBRARY is required");
        let path = Path::new(&path);
        verify_bundle(path).unwrap();
        let native = Native::load_with_heap(path, 256 << 20).unwrap();
        assert_eq!(native.heap_bytes().unwrap(), 256 << 20);
        drop(native);
        let error = Native::load_with_heap(path, 256 << 20)
            .err()
            .expect("second isolate must fail before calling GraalVM");
        assert!(
            error
                .to_string()
                .contains("G1_ISOLATE_PROCESS_RESTART_REQUIRED")
        );
    }
    #[test]
    fn isolate_params_matches_64_bit_graal_v5_header() {
        assert_eq!(std::mem::size_of::<IsolateParams>(), 56);
        assert_eq!(std::mem::align_of::<IsolateParams>(), 8);
        assert_eq!(
            std::mem::offset_of!(IsolateParams, reserved_address_space_size),
            8
        );
        assert_eq!(std::mem::offset_of!(IsolateParams, argc), 32);
        assert_eq!(std::mem::offset_of!(IsolateParams, argv), 40);
        assert_eq!(std::mem::offset_of!(IsolateParams, pkey), 48);
        assert_eq!(std::mem::offset_of!(IsolateParams, reserved_5), 54);
    }
}
