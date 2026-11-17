//! Common worker terminal transitions. Inputs remain owned by the caller until workers stop.
use crate::worker::Worker;
use anyhow::{Result, ensure};
use codeana_config::{Capture, CaptureScope};
use codeana_model::{AnalysisBackend, AnalysisUnitId, Manifest};
use tokio_util::sync::CancellationToken;
pub(super) fn publication<'a>(
    capture: &Capture,
    backend: AnalysisBackend,
    previous: &'a [codeana_model::SourceUnit],
) -> crate::worker::Publication<'a> {
    let modules = capture
        .effective
        .config
        .modules
        .iter()
        .filter(|m| crate::index_plan::language(&m.language) == Some(backend))
        .map(|m| m.id.clone())
        .chain(
            capture
                .manifest
                .files
                .iter()
                .filter(|s| {
                    !s.auxiliary
                        && codeana_model::SourceLanguage::lookup(&s.language)
                            .is_some_and(|l| l.backend() == backend)
                })
                .map(|s| s.module.clone()),
        )
        .chain(
            capture
                .effective
                .python_inputs()
                .into_iter()
                .filter(|_| backend == AnalysisBackend::Python)
                .flat_map(|i| i.contexts.iter().map(|c| c.module.clone())),
        )
        .filter(|m| !m.is_empty())
        .collect::<std::collections::BTreeSet<_>>()
        .into_iter()
        .collect();
    crate::worker::Publication { previous, modules }
}

/// Reuse must close every selected analysis account before staging its empty delta.
pub(super) async fn retire(units: Vec<&mut Worker>) -> Result<()> {
    for worker in units {
        worker.retire_unused().await?;
    }
    Ok(())
}

/// Commit has already happened: acknowledgement failures are warnings, never publication errors.
/// Preserve the existing order and last-failure warning while attempting every acknowledgement.
pub(super) async fn acknowledge(units: Vec<&mut Worker>, revision: &str) -> Option<String> {
    let mut warning = None;
    for worker in units {
        if let Err(error) = worker.ack(revision).await {
            warning = Some(error.to_string());
        }
    }
    warning
}

/// Attempt every owned unit even when one cleanup reports an error.
pub(super) async fn shutdown(units: Vec<&mut Worker>) -> Option<String> {
    let mut warning = None;
    for worker in units {
        if let Err(error) = worker.shutdown().await {
            warning = Some(error.to_string());
        }
    }
    warning
}

/// Task inputs are retained by the running collection, including failed/partial execution.
pub(super) enum PreparedInput {
    Java(Box<crate::java::PreparedJava>),
    Rust(Box<crate::rust_inputs::RustInput>),
    Python(
        codeana_contracts::worker::PythonConfig,
        std::sync::Arc<tempfile::TempDir>,
    ),
}
impl PreparedInput {
    pub fn backend(&self) -> AnalysisBackend {
        match self {
            Self::Java(_) => AnalysisBackend::Java,
            Self::Rust(_) => AnalysisBackend::Rust,
            Self::Python(..) => AnalysisBackend::Python,
        }
    }
    pub fn language(&self) -> crate::worker::Language<'_> {
        match self {
            Self::Java(input) => crate::worker::Language::Java(Some(input)),
            Self::Rust(input) => crate::worker::Language::Rust(&input.descriptor),
            Self::Python(input, _) => crate::worker::Language::Python(input),
        }
    }
    pub fn verify(&self) -> Result<()> {
        if let Self::Java(input) = self {
            input.verify()?;
        }
        Ok(())
    }
}

enum Session {
    Prepared,
    Running(Box<Worker>),
}

/// One unit owns its process, descriptor/scratch guard, manifest and frozen input lease.
/// Prepared units retain those guards even when starting a later unit fails.
pub(super) struct RunningUnit {
    session: Session,
    pub unit: AnalysisUnitId,
    pub manifest: Manifest,
    pub input: PreparedInput,
    capture: Capture,
}
impl RunningUnit {
    pub fn prepared(input: PreparedInput, capture: Capture, manifest: Manifest) -> Result<Self> {
        let CaptureScope::Unit(unit) = &capture.scope else {
            anyhow::bail!("CAPTURE_UNIT_REQUIRED");
        };
        ensure!(
            capture.effective.inputs.len() == 1
                && capture.effective.inputs[0].backend() == input.backend(),
            "CAPTURE_UNIT_BACKEND_MISMATCH"
        );
        ensure!(
            manifest
                .files
                .iter()
                .all(|s| capture.manifest.files.contains(s)),
            "CAPTURE_UNIT_MANIFEST_MISMATCH"
        );
        Ok(Self {
            session: Session::Prepared,
            unit: unit.clone(),
            manifest,
            input,
            capture,
        })
    }
    pub fn backend(&self) -> AnalysisBackend {
        self.input.backend()
    }
    pub async fn start(
        &mut self,
        cancel: &CancellationToken,
        memory: u64,
        analysis: Option<crate::worker::AnalysisBinding>,
        previous: &[codeana_model::SourceUnit],
    ) -> Result<()> {
        ensure!(
            matches!(self.session, Session::Prepared),
            "ANALYSIS_UNIT_ALREADY_STARTED"
        );
        let granted_unit = analysis
            .as_ref()
            .filter(|b| !b.instance.is_empty())
            .map(|b| b.instance.as_str())
            .unwrap_or(self.input.backend().as_str());
        ensure!(
            granted_unit == self.unit.as_str(),
            "ANALYSIS_UNIT_GRANT_MISMATCH"
        );
        let directory = match &self.input {
            PreparedInput::Java(input) => input.scratch.clone(),
            PreparedInput::Rust(input) => input.scratch.clone(),
            PreparedInput::Python(_, scratch) => scratch.clone(),
        };
        let worker = Worker::start_language(
            crate::worker::LaunchSpec {
                fixtures: None,
                language: self.input.language(),
                checkout: &self.capture.root,
                manifest: &self.manifest,
                inputs: crate::supervisor::InputLease::new(&self.capture, vec![directory]),
                publication: publication(&self.capture, self.backend(), previous),
            },
            cancel,
            memory,
            analysis,
        )
        .await?;
        self.session = Session::Running(Box::new(worker));
        Ok(())
    }
    pub fn worker(&self) -> Result<&Worker> {
        match &self.session {
            Session::Running(worker) => Ok(worker),
            Session::Prepared => Err(anyhow::anyhow!("ANALYSIS_UNIT_NOT_STARTED")),
        }
    }
    pub fn stream(&mut self) -> Result<(&mut Worker, &Manifest)> {
        match &mut self.session {
            Session::Running(worker) => Ok((worker, &self.manifest)),
            Session::Prepared => Err(anyhow::anyhow!("ANALYSIS_UNIT_NOT_STARTED")),
        }
    }
}

pub(super) struct RunningUnits {
    // Drop each owned session and its guards before the shared Python scratch.
    pub units: Vec<RunningUnit>,
    pub scratch: std::sync::Arc<tempfile::TempDir>,
}
impl RunningUnits {
    pub fn workers_mut(&mut self) -> Vec<&mut Worker> {
        fn running(u: &mut RunningUnit) -> Option<&mut Worker> {
            match &mut u.session {
                Session::Running(worker) => Some(worker.as_mut()),
                Session::Prepared => None,
            }
        }
        self.units.iter_mut().filter_map(running).collect()
    }
    pub fn contexts(&self) -> Result<Vec<codeana_model::BuildContext>> {
        self.units
            .iter()
            .map(|u| Ok(u.worker()?.context.clone()))
            .collect()
    }
}

/// A frozen unit's preparation request. Python needs its shared task directory;
/// Java and Rust retain their own existing scratch allocation policies.
pub(super) struct Preparation<'a> {
    pub task: &'a str,
    pub instance: &'a str,
    pub language: codeana_model::AnalysisBackend,
    pub capture: &'a codeana_config::Capture,
    pub python_directory: &'a std::path::Path,
    pub python_guard: std::sync::Arc<tempfile::TempDir>,
    pub python_mode: crate::python_inputs::InputMode,
}

#[cfg(test)]
mod tests {
    use super::*;
    fn descriptor() -> PreparedInput {
        PreparedInput::Python(
            Default::default(),
            std::sync::Arc::new(tempfile::tempdir().unwrap()),
        )
    }

    #[tokio::test]
    async fn an_unstarted_or_failed_unit_cannot_stream_and_retains_its_frozen_inputs() -> Result<()>
    {
        let (project, task) = crate::python_inputs::tests::capture();
        let capture = task.backend_capture(AnalysisBackend::Python)?;
        let manifest = capture.manifest.clone();
        let mut unit = RunningUnit::prepared(descriptor(), capture, manifest)?;
        drop(task);
        drop(project);
        assert!(unit.worker().is_err());
        assert!(unit.stream().is_err());
        let error = unit
            .start(&CancellationToken::new(), 64 << 20, None, &[])
            .await
            .unwrap_err();
        assert!(error.to_string().contains("ANALYSIS_GRANT_REQUIRED"));
        assert!(unit.worker().is_err());
        let source = unit
            .capture
            .manifest
            .files
            .iter()
            .find(|s| s.language == "python")
            .unwrap();
        assert_eq!(
            unit.capture.blobs[&source.hash].read(1024)?,
            b"def stock():\n    return 1\n"
        );
        Ok(())
    }
    #[test]
    fn runtime_ownership_rejects_a_task_capture_or_foreign_manifest() -> Result<()> {
        let (_project, task) = crate::python_inputs::tests::capture();
        let manifest = task.manifest.clone();
        assert!(
            RunningUnit::prepared(descriptor(), task.clone(), manifest)
                .err()
                .unwrap()
                .to_string()
                .contains("CAPTURE_UNIT_REQUIRED")
        );
        let capture = task.backend_capture(AnalysisBackend::Python)?;
        let mut foreign = capture.manifest.clone();
        foreign.files[0].path = "other-unit/source.py".into();
        assert!(
            RunningUnit::prepared(descriptor(), capture, foreign)
                .err()
                .unwrap()
                .to_string()
                .contains("CAPTURE_UNIT_MANIFEST_MISMATCH")
        );
        Ok(())
    }
}
