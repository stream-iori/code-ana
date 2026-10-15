//! Opt-in, bounded diagnostics. Context follows async tasks, never OS-thread migration.
use serde::Serialize;
use std::{
    future::Future,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Instant,
};

#[derive(Clone, Copy)]
#[repr(usize)]
pub enum Metric {
    EngineLoad,
    EngineStart,
    EnginePoll,
    DescriptorRead,
    RawRead,
    RawDigest,
    RawDecode,
    RawValidate,
    RawIdentityHash,
    RawDedup,
    RawSeal,
    ArchiveRead,
    ArchiveDecode,
    ArchiveDigest,
    ArchiveEncode,
    ArchiveWrite,
    ArchiveFlush,
    ArchiveSync,
    FactToWire,
    FactFromWire,
    FrameEncode,
    FrameWriteWait,
    FrameReadWait,
    FrameValidate,
    FrameDecode,
    BlobCopy,
    BlobFileSync,
    DirectorySync,
    BlobHash,
    PublishBlobCheck,
    PublishFileSync,
    PublishMove,
    CatalogCommit,
    DedupNew,
    DedupSame,
    DedupCommit,
    DescriptorDecode,
    DescriptorBytes,
    BundleVerify,
}
const NAMES: [&str; 39] = [
    "engine_load",
    "engine_start",
    "engine_poll",
    "descriptor_read",
    "raw_read",
    "raw_digest",
    "raw_decode",
    "raw_validate",
    "raw_identity_hash",
    "raw_dedup",
    "raw_seal",
    "archive_read",
    "archive_decode",
    "archive_digest",
    "archive_encode",
    "archive_write",
    "archive_flush",
    "archive_sync",
    "fact_to_wire",
    "fact_from_wire",
    "frame_encode",
    "frame_write_wait",
    "frame_read_wait",
    "frame_validate",
    "frame_decode",
    "blob_copy",
    "blob_file_sync",
    "directory_sync",
    "blob_hash",
    "publish_blob_check",
    "publish_file_sync",
    "publish_move",
    "catalog_commit",
    "dedup_new",
    "dedup_same",
    "dedup_commit",
    "descriptor_decode",
    "descriptor_bytes",
    "bundle_verify",
];
#[derive(Clone, Copy, Default, Serialize)]
pub struct Counter {
    calls: u64,
    bytes: u64,
    samples: u64,
    measured_ns: u64,
    sampled_bytes: u64,
    sampling_stride: u64,
    measured_cpu_ns: u64,
    cpu_samples: u64,
    maximum_ns: u64,
}
type Counters = Arc<Mutex<[Counter; NAMES.len()]>>;
tokio::task_local! { static CURRENT: Counters; }
std::thread_local! { static SYNC: std::cell::RefCell<Option<Counters>> = const { std::cell::RefCell::new(None) }; }

#[derive(Clone, Default)]
pub struct Capture(Option<Counters>);
impl Capture {
    /// Propagate the current task/CPU-thread diagnostics into bounded child workers.
    pub fn current() -> Self {
        Self(
            CURRENT
                .try_with(Clone::clone)
                .ok()
                .or_else(|| SYNC.with(|c| c.borrow().clone())),
        )
    }

    pub fn from_env() -> Self {
        if std::env::var_os("CODEANA_PERFORMANCE_TIMINGS_DIR").is_some() {
            Self::enabled()
        } else {
            Self::default()
        }
    }
    pub fn enabled() -> Self {
        Self(Some(Arc::new(Mutex::new(
            [Counter::default(); NAMES.len()],
        ))))
    }
    pub async fn scope<F: Future>(&self, future: F) -> F::Output {
        match &self.0 {
            Some(c) => CURRENT.scope(c.clone(), future).await,
            None => future.await,
        }
    }
    pub fn sync<T>(&self, f: impl FnOnce() -> T) -> T {
        struct Restore(Option<Counters>);
        impl Drop for Restore {
            fn drop(&mut self) {
                SYNC.with(|c| *c.borrow_mut() = self.0.take());
            }
        }
        let _restore = Restore(SYNC.with(|c| c.replace(self.0.clone())));
        f()
    }
    pub fn snapshot(&self) -> Option<PerformanceSnapshot> {
        let counters = self.0.as_ref()?.lock().unwrap_or_else(|p| p.into_inner());
        Some(PerformanceSnapshot {
            schema_version: 1,
            sample_stride: 1024,
            metrics: NAMES
                .iter()
                .zip(counters.iter())
                .map(|(name, value)| ((*name).to_owned(), *value))
                .collect(),
            scope: "task-local intervals; sampled timings and nested intervals must not be summed as phase totals",
        })
    }
}
#[derive(Serialize)]
pub struct PerformanceSnapshot {
    pub schema_version: u32,
    pub sample_stride: u32,
    pub metrics: std::collections::BTreeMap<String, Counter>,
    pub scope: &'static str,
}
#[derive(Clone, serde::Serialize)]
pub struct PhaseTiming {
    pub phase: String,
    pub elapsed_ms: u128,
}
pub struct Timer {
    counter: Option<Counters>,
    metric: Metric,
    start: Option<Instant>,
    cpu_start: Option<u64>,
}
impl Timer {
    pub fn start(metric: Metric, bytes: u64, sampled: bool) -> Self {
        let counter = CURRENT
            .try_with(Clone::clone)
            .ok()
            .or_else(|| SYNC.with(|c| c.borrow().clone()));
        let mut start = None;
        if let Some(c) = &counter {
            let mut counters = c.lock().unwrap_or_else(|p| p.into_inner());
            let value = &mut counters[metric as usize];
            value.sampling_stride = if sampled { 1024 } else { 1 };
            let ordinal = value.calls;
            value.calls += 1;
            value.bytes += bytes;
            if !sampled || ordinal.is_multiple_of(1024) {
                value.samples += 1;
                value.sampled_bytes += bytes;
                start = Some(Instant::now());
            }
        }
        let cpu_start = if start.is_some()
            && !matches!(metric, Metric::FrameReadWait | Metric::FrameWriteWait)
        {
            thread_cpu()
        } else {
            None
        };
        Self {
            counter: if start.is_some() { counter } else { None },
            metric,
            start,
            cpu_start,
        }
    }
}
impl Drop for Timer {
    fn drop(&mut self) {
        if let (Some(c), Some(start)) = (&self.counter, self.start) {
            let elapsed = start.elapsed().as_nanos() as u64;
            let cpu = self
                .cpu_start
                .and_then(|before| thread_cpu()?.checked_sub(before));
            let mut counters = c.lock().unwrap_or_else(|p| p.into_inner());
            let value = &mut counters[self.metric as usize];
            value.measured_ns += elapsed;
            value.maximum_ns = value.maximum_ns.max(elapsed);
            if let Some(cpu) = cpu {
                value.measured_cpu_ns += cpu;
                value.cpu_samples += 1;
            }
        }
    }
}
fn thread_cpu() -> Option<u64> {
    let mut value = std::mem::MaybeUninit::<libc::timespec>::uninit();
    // SAFETY: successful clock_gettime initializes exactly one timespec. Only synchronous
    // intervals use this clock; async I/O may migrate between threads and stays wall-only.
    if unsafe { libc::clock_gettime(libc::CLOCK_THREAD_CPUTIME_ID, value.as_mut_ptr()) } != 0 {
        return None;
    }
    let value = unsafe { value.assume_init() };
    if value.tv_sec < 0 || value.tv_nsec < 0 {
        return None;
    }
    Some(value.tv_sec as u64 * 1_000_000_000 + value.tv_nsec as u64)
}

pub fn measure<T>(metric: Metric, bytes: u64, sampled: bool, f: impl FnOnce() -> T) -> T {
    let _timer = Timer::start(metric, bytes, sampled);
    f()
}

pub struct Report {
    pub capture: Capture,
    output: Option<PathBuf>,
}
impl Report {
    pub fn new(name: &str) -> Self {
        Self {
            capture: Capture::from_env(),
            output: std::env::var_os("CODEANA_PERFORMANCE_TIMINGS_DIR").map(|root| {
                PathBuf::from(root).join(format!("{name}-{}.json", codeana_model::new_id("trace")))
            }),
        }
    }
}
impl Drop for Report {
    fn drop(&mut self) {
        if let Some(path) = &self.output {
            let result = (|| -> std::io::Result<()> {
                use std::io::Write;
                std::fs::create_dir_all(path.parent().unwrap())?;
                let bytes = serde_json::to_vec(&self.capture.snapshot())?;
                if bytes.len() > 8 << 20 {
                    return Err(std::io::Error::other("DIAGNOSTIC_BYTE_LIMIT"));
                }
                std::fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(path)?
                    .write_all(&bytes)
            })();
            if let Err(error) = result {
                eprintln!("PERFORMANCE_TIMINGS_WRITE_FAILED: {error}");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn counters_follow_task_across_await_without_leaking_to_child() {
        let capture = Capture::enabled();
        capture
            .scope(async {
                for _ in 0..1025 {
                    measure(Metric::RawDecode, 7, true, || ());
                    tokio::task::yield_now().await;
                }
                tokio::spawn(async {
                    measure(Metric::RawDecode, 7, true, || ());
                })
                .await
                .unwrap();
            })
            .await;
        let value = serde_json::to_value(capture.snapshot()).unwrap();
        assert_eq!(value["metrics"]["raw_decode"]["calls"], 1025);
        assert_eq!(value["metrics"]["raw_decode"]["bytes"], 7175);
        assert_eq!(value["metrics"]["raw_decode"]["samples"], 2);
    }
    #[test]
    fn nested_sync_scope_restores_after_failure_and_disabled_scope_is_empty() {
        let outer = Capture::enabled();
        let inner = Capture::enabled();
        outer.sync(|| {
            let _ = std::panic::catch_unwind(|| {
                inner.sync(|| {
                    measure(Metric::RawRead, 11, false, || ());
                    panic!("fixture");
                })
            });
            measure(Metric::RawRead, 13, false, || ());
        });
        assert_eq!(
            serde_json::to_value(outer.snapshot()).unwrap()["metrics"]["raw_read"]["bytes"],
            13
        );
        assert_eq!(
            serde_json::to_value(inner.snapshot()).unwrap()["metrics"]["raw_read"]["bytes"],
            11
        );
        assert!(Capture::default().snapshot().is_none());
    }
}
