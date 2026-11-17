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
    Fixture(std::path::PathBuf),
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
            Self::Fixture(_) | Self::Java(_) => AnalysisBackend::Java,
            Self::Rust(_) => AnalysisBackend::Rust,
            Self::Python(..) => AnalysisBackend::Python,
        }
    }
    pub fn language(&self) -> crate::worker::Language<'_> {
        match self {
            Self::Fixture(_) => crate::worker::Language::Java(None),
            Self::Java(input) => crate::worker::Language::Java(Some(input)),
            Self::Rust(input) => crate::worker::Language::Rust(&input.descriptor),
            Self::Python(input, _) => crate::worker::Language::Python(input),
        }
    }
    pub fn java(&self) -> Option<&crate::java::PreparedJava> {
        match self {
            Self::Java(input) => Some(input),
            _ => None,
        }
    }
    pub fn directories(&self) -> Vec<std::sync::Arc<tempfile::TempDir>> {
        match self {
            Self::Fixture(_) => vec![],
            Self::Java(input) => vec![input.scratch.clone()],
            Self::Rust(input) => vec![input.scratch.clone()],
            Self::Python(_, scratch) => vec![scratch.clone()],
        }
    }
    pub fn fixtures(&self) -> Option<&std::path::Path> {
        match self {
            Self::Fixture(path) => Some(path),
            _ => None,
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
    Running(Box<crate::worker_service::StartedUnit>),
    Released,
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
        service: &crate::worker_service::WorkerService,
        cancel: &CancellationToken,
        memory: u64,
        analysis: Option<crate::worker::AnalysisBinding>,
        previous: &[codeana_model::SourceUnit],
        baseline: &str,
    ) -> Result<()> {
        ensure!(
            matches!(self.session, Session::Prepared),
            "ANALYSIS_UNIT_ALREADY_STARTED"
        );
        ensure!(
            analysis
                .as_ref()
                .is_none_or(|binding| binding.language == self.backend()),
            "ANALYSIS_UNIT_BACKEND_GRANT_MISMATCH"
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
        let started = service
            .launch(crate::worker_service::UnitLaunch {
                capture: &self.capture,
                manifest: &self.manifest,
                input: &self.input,
                cancel,
                memory,
                analysis,
                previous,
                baseline,
            })
            .await?;
        self.session = Session::Running(Box::new(started));
        Ok(())
    }
    pub fn capture(&self) -> &Capture {
        &self.capture
    }
    pub async fn shutdown(&mut self) -> Result<()> {
        if let Session::Running(started) = &mut self.session {
            started.worker.shutdown().await?;
        }
        Ok(())
    }
    /// Only the publication caller may park an acknowledged or certified reused unit.
    pub fn park(&mut self, service: &crate::worker_service::WorkerService, revision: &str) {
        let eligible = service.can_keep_java()
            && match &mut self.session {
                Session::Running(started) => {
                    started.pool_key.is_some()
                        && started.worker.reusable()
                        && started.worker.alive().unwrap_or(false)
                }
                Session::Prepared | Session::Released => false,
            };
        if eligible
            && let Session::Running(started) =
                std::mem::replace(&mut self.session, Session::Released)
        {
            let started = *started;
            service.keep_java(
                started.pool_key.expect("checked pool key"),
                revision.to_owned(),
                started.worker,
            );
        }
    }
    pub fn worker(&self) -> Result<&Worker> {
        match &self.session {
            Session::Running(started) => Ok(&started.worker),
            Session::Prepared | Session::Released => {
                Err(anyhow::anyhow!("ANALYSIS_UNIT_NOT_STARTED"))
            }
        }
    }
    pub fn analysis(&mut self) -> Result<(&mut Worker, &Manifest, &PreparedInput, &Capture)> {
        match &mut self.session {
            Session::Running(started) => Ok((
                &mut started.worker,
                &self.manifest,
                &self.input,
                &self.capture,
            )),
            Session::Prepared | Session::Released => {
                Err(anyhow::anyhow!("ANALYSIS_UNIT_NOT_STARTED"))
            }
        }
    }
    pub fn stream(&mut self) -> Result<(&mut Worker, &Manifest)> {
        self.analysis()
            .map(|(worker, manifest, _, _)| (worker, manifest))
    }
}

pub(super) struct RunningUnits {
    // Drop each owned session and its guards before the shared task scratch.
    pub units: Vec<RunningUnit>,
    pub scratch: std::sync::Arc<tempfile::TempDir>,
}
impl RunningUnits {
    pub fn unit(&self, id: &str) -> Result<&RunningUnit> {
        self.units
            .iter()
            .find(|unit| unit.unit == id)
            .ok_or_else(|| anyhow::anyhow!("ANALYSIS_UNIT_MISSING {id}"))
    }
    pub fn unit_mut(&mut self, id: &str) -> Result<&mut RunningUnit> {
        self.units
            .iter_mut()
            .find(|unit| unit.unit == id)
            .ok_or_else(|| anyhow::anyhow!("ANALYSIS_UNIT_MISSING {id}"))
    }
    pub fn workers_mut(&mut self) -> Vec<&mut Worker> {
        fn running(u: &mut RunningUnit) -> Option<&mut Worker> {
            match &mut u.session {
                Session::Running(started) => Some(&mut started.worker),
                Session::Prepared | Session::Released => None,
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
        let service = crate::worker_service::WorkerService::new(
            std::env::temp_dir(),
            None,
            Default::default(),
            64 << 20,
        );
        let error = unit
            .start(&service, &CancellationToken::new(), 64 << 20, None, &[], "")
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
    #[tokio::test]
    async fn a_foreign_backend_binding_is_rejected_before_issuing_its_grant() -> Result<()> {
        use codeana_model::{AnalysisCoordinator, AnalysisLimits, AnalysisUsage};
        let (_project, task) = crate::python_inputs::tests::capture();
        let capture = task.backend_capture(AnalysisBackend::Python)?;
        let manifest = capture.manifest.clone();
        let mut unit = RunningUnit::prepared(descriptor(), capture, manifest)?;
        let mut coordinator = AnalysisCoordinator::new(
            "task",
            AnalysisLimits::default(),
            AnalysisUsage::default(),
            &[AnalysisBackend::Rust],
            None,
        )?;
        coordinator.configure_instances(AnalysisBackend::Rust, &["python".into()])?;
        let coordinator = std::sync::Arc::new(std::sync::Mutex::new(coordinator));
        let service = crate::worker_service::WorkerService::new(
            std::env::temp_dir(),
            None,
            Default::default(),
            64 << 20,
        );
        let error = unit
            .start(
                &service,
                &CancellationToken::new(),
                64 << 20,
                Some(crate::worker::AnalysisBinding {
                    coordinator: coordinator.clone(),
                    language: AnalysisBackend::Rust,
                    instance: "python".into(),
                }),
                &[],
                "",
            )
            .await
            .unwrap_err();
        assert!(
            error
                .to_string()
                .contains("ANALYSIS_UNIT_BACKEND_GRANT_MISMATCH")
        );
        assert!(unit.worker().is_err());
        assert!(
            coordinator
                .lock()
                .unwrap()
                .issue_instance(AnalysisBackend::Rust, "python")
                .is_ok()
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
