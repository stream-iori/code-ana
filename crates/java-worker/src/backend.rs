use anyhow::Result;
use std::path::Path;

#[derive(Clone, Copy, Debug, clap::ValueEnum)]
pub enum Backend {
    Native,
    Jvm,
}
impl Backend {
    pub fn started_phase(self) -> &'static str {
        match self {
            Self::Native => "native_started",
            Self::Jvm => "jvm_started",
        }
    }
    pub fn finished_phase(self) -> &'static str {
        match self {
            Self::Native => "native_finished",
            Self::Jvm => "jvm_finished",
        }
    }
    pub fn timeout_code(self) -> &'static str {
        match self {
            Self::Native => "NATIVE_TIMEOUT",
            Self::Jvm => "JVM_TIMEOUT",
        }
    }
    pub fn analysis_code(self) -> &'static str {
        match self {
            Self::Native => "NATIVE_ANALYSIS",
            Self::Jvm => "JVM_ANALYSIS",
        }
    }
}

pub fn verify_bundle(path: &Path, backend: Backend) -> Result<std::path::PathBuf> {
    match backend {
        Backend::Native => crate::native::verify_bundle(path),
        Backend::Jvm => crate::jvm::verify_bundle(path),
    }
}

pub enum Engine {
    Native(crate::native::Native),
    Jvm(crate::jvm::Jvm),
}

/// Product ownership is asynchronous; direct Engine remains for bounded ABI probes.
pub enum ProductEngine {
    Native(crate::actor::NativeActor),
    Jvm(crate::jvm::Jvm),
}
impl ProductEngine {
    pub async fn load(
        path: &Path,
        heap: u64,
        backend: Backend,
        capture: codeana_contracts::performance::Capture,
    ) -> Result<Self> {
        Ok(match backend {
            Backend::Native => {
                Self::Native(crate::actor::NativeActor::load(path, heap, capture).await?)
            }
            Backend::Jvm => Self::Jvm(codeana_contracts::performance::measure(
                codeana_contracts::performance::Metric::EngineLoad,
                0,
                false,
                || crate::jvm::Jvm::load(path, heap),
            )?),
        })
    }
    pub fn set_capture(&mut self, capture: codeana_contracts::performance::Capture) {
        if let Self::Native(native) = self {
            native.set_capture(capture);
        }
    }
    pub async fn heap_bytes(&self) -> Result<u64> {
        match self {
            Self::Native(n) => n.heap_bytes().await,
            Self::Jvm(j) => j.heap_bytes(),
        }
    }
    pub async fn start(&mut self, bytes: Vec<u8>) -> Result<()> {
        match self {
            Self::Native(n) => n.start(bytes).await,
            Self::Jvm(j) => codeana_contracts::performance::measure(
                codeana_contracts::performance::Metric::EngineStart,
                bytes.len() as u64,
                false,
                || j.start(&bytes),
            ),
        }
    }
    pub async fn ready(&mut self) -> Result<i32> {
        match self {
            Self::Native(n) => n.ready().await,
            Self::Jvm(j) => loop {
                let state = codeana_contracts::performance::measure(
                    codeana_contracts::performance::Metric::EnginePoll,
                    0,
                    false,
                    || j.poll(),
                )?;
                if state != 0 {
                    break Ok(state);
                }
                tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            },
        }
    }
    pub async fn result_file(&self, max: u64, directory: &Path) -> Result<tempfile::NamedTempFile> {
        match self {
            Self::Native(n) => n.result_file(max, directory.to_owned()).await,
            Self::Jvm(j) => codeana_contracts::performance::measure(
                codeana_contracts::performance::Metric::DescriptorRead,
                0,
                false,
                || j.result_file_with_limit(max, Some(directory)),
            ),
        }
    }
    pub fn cancel(&self) -> Result<()> {
        match self {
            Self::Native(n) => {
                n.cancel();
                Ok(())
            }
            Self::Jvm(j) => j.cancel(),
        }
    }
    pub async fn ack(&self) -> Result<()> {
        match self {
            Self::Native(n) => n.ack().await,
            Self::Jvm(j) => j.ack(),
        }
    }
    pub async fn finish(&mut self) -> Result<()> {
        match self {
            Self::Native(n) => n.finish().await,
            Self::Jvm(j) => j.finish(),
        }
    }
    pub async fn shutdown(&mut self) -> Result<()> {
        if let Self::Native(n) = self {
            n.shutdown().await?;
        }
        Ok(())
    }
}
impl Engine {
    pub fn heap_bytes(&self) -> Result<u64> {
        match self {
            Self::Native(x) => x.heap_bytes(),
            Self::Jvm(x) => x.heap_bytes(),
        }
    }
    pub fn start(&mut self, bytes: &[u8]) -> Result<()> {
        match self {
            Self::Native(x) => x.start(bytes),
            Self::Jvm(x) => x.start(bytes),
        }
    }
    pub fn poll(&self) -> Result<i32> {
        match self {
            Self::Native(x) => x.poll(),
            Self::Jvm(x) => x.poll(),
        }
    }
    pub fn cancel(&self) -> Result<()> {
        match self {
            Self::Native(x) => x.cancel(),
            Self::Jvm(x) => x.cancel(),
        }
    }
    pub fn result(&self) -> Result<Vec<u8>> {
        match self {
            Self::Native(x) => x.result(),
            Self::Jvm(x) => x.result(),
        }
    }
    pub fn ack(&self) -> Result<()> {
        match self {
            Self::Native(x) => x.ack(),
            Self::Jvm(x) => x.ack(),
        }
    }
}
