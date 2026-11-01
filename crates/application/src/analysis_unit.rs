//! Common worker terminal transitions. Inputs remain owned by the caller until workers stop.
use crate::worker::Worker;
use anyhow::Result;

/// Reuse must close every selected analysis account before staging its empty delta.
pub(super) async fn retire(units: Units<'_>) -> Result<()> {
    for worker in units {
        worker.retire_unused().await?;
    }
    Ok(())
}

/// Commit has already happened: acknowledgement failures are warnings, never publication errors.
/// Preserve the existing order and last-failure warning while attempting every acknowledgement.
pub(super) async fn acknowledge(units: Units<'_>, revision: &str) -> Option<String> {
    let mut warning = None;
    for worker in units {
        if let Err(error) = worker.ack(revision).await {
            warning = Some(error.to_string());
        }
    }
    warning
}

/// Attempt every owned unit even when one cleanup reports an error.
pub(super) async fn shutdown(units: Units<'_>) -> Option<String> {
    let mut warning = None;
    for worker in units {
        if let Err(error) = worker.shutdown().await {
            warning = Some(error.to_string());
        }
    }
    warning
}

pub(super) enum Units<'a> {
    One(Option<&'a mut Worker>),
    Sessions(std::slice::IterMut<'a, (Worker, codeana_model::Manifest)>),
    Pair(Option<&'a mut Worker>, Option<&'a mut Worker>),
}
impl<'a> Iterator for Units<'a> {
    type Item = &'a mut Worker;
    fn next(&mut self) -> Option<Self::Item> {
        match self {
            Self::One(worker) => worker.take(),
            Self::Sessions(sessions) => sessions.next().map(|session| &mut session.0),
            Self::Pair(first, second) => first.take().or_else(|| second.take()),
        }
    }
}

/// Task inputs are retained by the running collection, including failed/partial execution.
pub(super) enum PreparedInput {
    Java(Box<crate::java::PreparedJava>),
    Rust(Box<crate::rust_inputs::RustInput>),
    Python(codeana_contracts::worker::PythonConfig),
}
impl PreparedInput {
    pub fn language(&self) -> crate::worker::Language<'_> {
        match self {
            Self::Java(input) => crate::worker::Language::Java(Some(input)),
            Self::Rust(input) => crate::worker::Language::Rust(&input.descriptor),
            Self::Python(input) => crate::worker::Language::Python(input),
        }
    }
    pub fn verify(&self) -> Result<()> {
        if let Self::Java(input) = self {
            input.verify()?;
        }
        Ok(())
    }
}

pub(super) struct RunningUnits {
    // Drop workers before input guards, and those guards before the shared scratch directory.
    pub sessions: Vec<(Worker, codeana_model::Manifest)>,
    pub inputs: Vec<PreparedInput>,
    pub scratch: tempfile::TempDir,
}
impl std::ops::Deref for RunningUnits {
    type Target = Vec<(Worker, codeana_model::Manifest)>;
    fn deref(&self) -> &Self::Target {
        &self.sessions
    }
}
impl std::ops::DerefMut for RunningUnits {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.sessions
    }
}

/// A frozen unit's preparation request. Python needs its shared task directory;
/// Java and Rust retain their own existing scratch allocation policies.
pub(super) struct Preparation<'a> {
    pub task: &'a str,
    pub instance: &'a str,
    pub language: codeana_model::AnalysisLanguage,
    pub capture: &'a codeana_config::Capture,
    pub python_directory: &'a std::path::Path,
    pub python_mode: crate::python_inputs::InputMode,
}
