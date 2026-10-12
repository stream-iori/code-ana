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
impl Engine {
    pub fn load_with_heap(path: &Path, heap: u64, backend: Backend) -> Result<Self> {
        Ok(match backend {
            Backend::Native => Self::Native(crate::native::Native::load_with_heap(path, heap)?),
            Backend::Jvm => Self::Jvm(crate::jvm::Jvm::load(path, heap)?),
        })
    }
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
    pub fn result_file_with_limit(
        &self,
        max: u64,
        directory: Option<&Path>,
    ) -> Result<tempfile::NamedTempFile> {
        match self {
            Self::Native(x) => x.result_file_with_limit(max, directory),
            Self::Jvm(x) => x.result_file_with_limit(max, directory),
        }
    }
    pub fn ack(&self) -> Result<()> {
        match self {
            Self::Native(x) => x.ack(),
            Self::Jvm(x) => x.ack(),
        }
    }
    pub fn finish(&mut self) -> Result<()> {
        match self {
            Self::Native(x) => x.finish(),
            Self::Jvm(x) => x.finish(),
        }
    }
}
