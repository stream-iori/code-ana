//! Frozen unit availability and static reservations, including units that do not execute.
use super::*;
use std::collections::BTreeSet;

#[derive(Debug)]
pub(super) struct PlannedUnit {
    pub id: AnalysisUnitId,
    pub language: AnalysisBackend,
    pub availability: UnitAvailability,
    pub prepared_index: Option<usize>,
    pub previous: Option<UnitOwnership>,
}
pub(super) struct IndexPlan {
    pub baseline: RevisionId,
    pub fixture_sources: Vec<SourceUnit>,
    pub participants: Vec<AnalysisBackend>,
    pub units: Vec<PlannedUnit>,
}
pub(super) fn language(name: &str) -> Option<AnalysisBackend> {
    SourceLanguage::lookup(name).map(SourceLanguage::backend)
}
impl IndexPlan {
    pub fn previous_sources(&self, unit: &str) -> &[SourceUnit] {
        self.units
            .iter()
            .find(|u| u.id == unit)
            .and_then(|u| u.previous.as_ref())
            .map(|u| u.inputs.as_slice())
            .unwrap_or(&self.fixture_sources)
    }
    #[cfg(test)]
    pub fn executes(&self, backend: AnalysisBackend) -> bool {
        self.units
            .iter()
            .any(|u| u.language == backend && u.availability == UnitAvailability::Ready)
    }
    pub fn removed(&self) -> impl Iterator<Item = &UnitOwnership> {
        self.units
            .iter()
            .filter(|u| u.availability == UnitAvailability::Removed)
            .filter_map(|u| u.previous.as_ref())
    }
    pub fn configure(&self, coordinator: &mut AnalysisCoordinator) -> Result<()> {
        for backend in &self.participants {
            let ids = self
                .units
                .iter()
                .filter(|u| u.language == *backend)
                .map(|u| u.id.to_string())
                .collect::<Vec<_>>();
            coordinator
                .configure_instances(*backend, &ids)
                .map_err(DomainError::analysis)?;
        }
        for unit in self
            .units
            .iter()
            .filter(|u| u.availability != UnitAvailability::Ready)
        {
            coordinator
                .retire_reserved(unit.language, &unit.id)
                .map_err(DomainError::analysis)?;
        }
        Ok(())
    }
    #[cfg(test)]
    pub fn build(capture: &Capture, budget: Option<&AnalysisContext>) -> Result<Self> {
        Self::with_baseline(capture, None, budget)
    }
    pub fn with_baseline(
        capture: &Capture,
        baseline: Option<&UnitOwnershipLedger>,
        budget: Option<&AnalysisContext>,
    ) -> Result<Self> {
        let effective = &capture.effective;
        let admit = |id: &str, bytes: usize| -> Result<()> {
            ensure!(
                !id.is_empty() && id.len() <= 256,
                "ANALYSIS_UNIT_ID_REQUIRED"
            );
            if let Some(budget) = budget {
                budget
                    .charge(
                        1,
                        0,
                        (std::mem::size_of::<PlannedUnit>() + id.len() + bytes) as u64,
                    )
                    .map_err(DomainError::analysis)?;
            }
            Ok(())
        };
        let mut units: Vec<PlannedUnit> = Vec::new();
        if !effective.input_units.is_empty() {
            for (index, unit) in effective.input_units.iter().enumerate() {
                admit(&unit.prepared.id, 0)?;
                ensure!(
                    !units.iter().any(|u| u.id == unit.prepared.id),
                    "ANALYSIS_UNIT_ID_DUPLICATE"
                );
                units.push(PlannedUnit {
                    id: unit.prepared.id.as_str().into(),
                    language: unit.prepared.backend,
                    availability: if unit.context.is_some() {
                        UnitAvailability::Ready
                    } else {
                        UnitAvailability::FilesOnly
                    },
                    prepared_index: Some(index),
                    previous: None,
                });
            }
        } else {
            let mut backends: BTreeSet<_> = capture
                .manifest
                .files
                .iter()
                .map(|s| s.language.as_str())
                .chain(effective.config.modules.iter().map(|m| m.language.as_str()))
                .filter_map(language)
                .collect();
            backends.extend(effective.inputs.iter().map(|i| i.backend()));
            for backend in backends {
                let ready = effective
                    .inputs
                    .iter()
                    .any(|input| input.backend() == backend);
                if ready {
                    let mut found = false;
                    for view in capture
                        .unit_views
                        .values()
                        .filter(|view| view.backend == backend)
                    {
                        admit(&view.unit, 0)?;
                        units.push(PlannedUnit {
                            id: view.unit.clone(),
                            language: backend,
                            availability: UnitAvailability::Ready,
                            prepared_index: None,
                            previous: None,
                        });
                        found = true;
                    }
                    ensure!(found, "CAPTURE_UNIT_VIEW_MISSING");
                } else {
                    admit(backend.as_str(), 0)?;
                    units.push(PlannedUnit {
                        id: backend.as_str().into(),
                        language: backend,
                        availability: UnitAvailability::FilesOnly,
                        prepared_index: None,
                        previous: None,
                    });
                }
            }
        }
        if let Some(baseline) = baseline {
            for old in &baseline.units {
                if let Some(current) = units.iter_mut().find(|u| u.id == old.unit) {
                    ensure!(
                        current.language == old.backend,
                        "ANALYSIS_UNIT_BACKEND_CHANGED"
                    );
                    admit(&old.unit, codeana_model::retained_size(old)? as usize)?;
                    current.previous = Some(old.clone());
                    continue;
                }
                admit(&old.unit, codeana_model::retained_size(old)? as usize)?;
                units.push(PlannedUnit {
                    id: old.unit.clone(),
                    language: old.backend,
                    availability: UnitAvailability::Removed,
                    prepared_index: None,
                    previous: Some(old.clone()),
                });
            }
        }
        units.sort_by(|a, b| (a.language, &a.id).cmp(&(b.language, &b.id)));
        let participants = units
            .iter()
            .map(|u| u.language)
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        Ok(Self {
            baseline: RevisionId::default(),
            fixture_sources: vec![],
            participants,
            units,
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use codeana_config::inputs::{InputMode, PreparedUnit, ResolvedUnit};

    #[test]
    fn removed_and_files_only_units_reserve_static_shares_without_execution() {
        let mut c = capture();
        c.effective.input_units = vec![
            unit("ready", "python", Some(c.effective.clone())),
            unit("missing", "python", None),
        ];
        let old = UnitOwnership {
            inputs: vec![],
            scopes: vec!["MAIN".into()],
            unit: "deleted".into(),
            backend: AnalysisBackend::Java,
            availability: UnitAvailability::Ready,
            source_languages: vec![SourceLanguage::Java],
            modules: vec!["zero-output-module".into()],
            worlds: vec![],
            producer: "javac-v3".into(),
            semantic_profile: hash("profile"),
            input_digest: hash("input"),
            sources: vec![],
        };
        let ledger = UnitOwnershipLedger {
            version: UNIT_OWNERSHIP_VERSION,
            units: vec![old.clone()],
        };
        let plan = IndexPlan::with_baseline(&c, Some(&ledger), None).unwrap();
        assert_eq!(
            plan.units
                .iter()
                .map(|u| u.availability)
                .collect::<Vec<_>>(),
            [
                UnitAvailability::Removed,
                UnitAvailability::FilesOnly,
                UnitAvailability::Ready
            ]
        );
        assert_eq!(plan.removed().next(), Some(&old));
        assert_eq!(
            plan.participants,
            [AnalysisBackend::Java, AnalysisBackend::Python]
        );
        let mut coordinator = AnalysisCoordinator::new(
            "task",
            AnalysisLimits::default(),
            AnalysisUsage::default(),
            &plan.participants,
            None,
        )
        .unwrap();
        plan.configure(&mut coordinator).unwrap();
        assert!(
            coordinator
                .issue_instance(AnalysisBackend::Java, "deleted")
                .is_err()
        );
        assert!(
            coordinator
                .issue_instance(AnalysisBackend::Python, "missing")
                .is_err()
        );
        coordinator
            .issue_instance(AnalysisBackend::Python, "ready")
            .unwrap();
        assert_eq!(
            coordinator.snapshot().languages[0].workers[0]
                .usage
                .as_ref()
                .unwrap()
                .work,
            0
        );
    }

    fn capture() -> Capture {
        Capture {
            scope: codeana_config::CaptureScope::Task,
            root: PathBuf::new(),
            project: String::new(),
            checkout: String::new(),
            target: String::new(),
            manifest: Manifest::default(),
            blobs: BTreeMap::new(),
            observations: Default::default(),
            unit_views: Default::default(),
            effective: codeana_config::Effective::from_context(codeana_config::ResolvedContext {
                config: Default::default(),
                origins: BTreeMap::new(),
                config_digest: String::new(),
                input_hash: String::new(),
                semantic_digest: String::new(),
                capture_digest: String::new(),
                inputs: vec![],
            }),
        }
    }

    fn unit(
        id: &str,
        language: &str,
        effective: Option<codeana_config::Effective>,
    ) -> ResolvedUnit {
        ResolvedUnit {
            prepared: PreparedUnit {
                id: id.into(),
                backend: SourceLanguage::lookup(language).unwrap().backend(),
                source_languages: vec![SourceLanguage::lookup(language).unwrap()],
                entries: vec![],
                modules: vec![],
                roots: vec![],
                mode: if effective.is_some() {
                    InputMode::Semantic
                } else {
                    InputMode::FilesOnly
                },
                reason: None,
                log: None,
                config: None,
                artifacts: vec![],
            },
            context: effective.map(|value| value.context),
        }
    }

    #[test]
    fn ordinary_script_contexts_plan_actual_frozen_ids_in_one_backend() -> Result<()> {
        let project = tempfile::tempdir()?;
        let root = project.path().canonicalize()?;
        std::fs::create_dir(root.join(".code-ana"))?;
        let source = "export const a=1;";
        let compiler_config = r#"{"compilerOptions":{"noLib":true},"files":["a.ts"]}"#;
        std::fs::write(root.join("a.ts"), source)?;
        std::fs::write(root.join("tsconfig.json"), compiler_config)?;
        let files = vec![
            serde_json::json!({"path":root.join("a.ts"),"sha256":hash(source),"bytes":source.len(),"role":"publish","language":"typescript","module":"app","scope":"MAIN","world":"typescript/main"}),
            serde_json::json!({"path":root.join("tsconfig.json"),"sha256":hash(compiler_config),"bytes":compiler_config.len(),"role":"control","language":null,"module":null,"scope":null,"world":null}),
        ];
        let context = serde_json::json!({"unit":"typescript/app/main","root":root,"config_path":root.join("tsconfig.json"),"publish_languages":["typescript"],"case_sensitive":!root.join("TSCONFIG.JSON").exists(),"root_files":[root.join("a.ts")],"references":[],"files":files,"directories":[],"links":[],"absent":[]});
        let mut second = context.clone();
        second["unit"] = serde_json::json!("typescript/app/test");
        second["files"][0]["world"] = serde_json::json!("typescript/test");
        let metadata = serde_json::json!({"schema_version":1,"compiler":{"adapter":"synthetic-planning-test","upstream_commit":"a".repeat(40),"typescript_commit":"b".repeat(40),"compiler_version":"synthetic","go_version":"synthetic","library_sha256":"a".repeat(64),"header_sha256":"b".repeat(64),"standard_library_digest":"c".repeat(64),"analysis_proto_sha256":"d".repeat(64),"graph_proto_sha256":"e".repeat(64)},"contexts":[context,second]});
        std::fs::write(
            root.join(".code-ana/native.json"),
            serde_json::to_vec(&metadata)?,
        )?;
        std::fs::write(
            root.join(".code-ana/config.toml"),
            "schema_version=1\nkind='project'\n[languages.typescript]\nenabled=true\n[languages.typescript.discovery]\ninputs_manifest='.code-ana/native.json'\n[[modules]]\nid='app'\nlanguage='typescript'\nroot='.'\n",
        )?;
        let frozen = codeana_config::capture(&root, &Request::default())?;
        let plan = IndexPlan::build(&frozen, None)?;
        assert_eq!(plan.participants, [AnalysisBackend::TypeScript]);
        assert_eq!(
            plan.units
                .iter()
                .map(|unit| unit.id.as_str())
                .collect::<Vec<_>>(),
            ["typescript/app/main", "typescript/app/test"]
        );
        for unit in &plan.units {
            assert_eq!(unit.availability, UnitAvailability::Ready);
            let child = frozen.unit_capture(&unit.id)?;
            assert_eq!(
                child.effective.typescript_inputs().unwrap().contexts[0].unit,
                unit.id
            );
        }
        Ok(())
    }

    #[test]
    fn empty_and_deleted_modules_keep_their_resource_reservation() {
        let mut c = capture();
        for name in ["python", "java", "rust", "python"] {
            c.effective.config.modules.push(codeana_config::Module {
                language: name.into(),
                ..Default::default()
            });
        }
        let plan = IndexPlan::build(&c, None).unwrap();
        assert_eq!(plan.participants.len(), 3);
        assert_eq!(plan.units.len(), 3);
        assert!(!plan.executes(AnalysisBackend::Java));
        assert!(!plan.executes(AnalysisBackend::Rust));
        assert!(!plan.executes(AnalysisBackend::Python));
        assert!(plan.units.iter().all(|unit| unit.prepared_index.is_none()));
    }

    #[test]
    fn prepared_units_keep_unavailable_contexts_in_static_backend_and_instance_order() {
        let mut c = capture();
        c.effective.input_units = vec![
            unit("python-b", "python", Some(c.effective.clone())),
            unit("missing", "java", None),
            unit("rust-a", "rust", Some(c.effective.clone())),
            unit("python-a", "python", Some(c.effective.clone())),
        ];
        let plan = IndexPlan::build(&c, None).unwrap();
        assert_eq!(
            plan.units
                .iter()
                .map(|unit| unit.id.as_str())
                .collect::<Vec<_>>(),
            ["missing", "rust-a", "python-a", "python-b"]
        );
        assert_eq!(plan.units[0].availability, UnitAvailability::FilesOnly);
        assert!(
            plan.units[1..]
                .iter()
                .all(|unit| unit.availability == UnitAvailability::Ready)
        );
        assert_eq!(plan.units[1].language, AnalysisBackend::Rust);
        assert_eq!(plan.units[2].language, AnalysisBackend::Python);
        assert_eq!(plan.units[1].prepared_index, Some(2));
    }

    #[test]
    fn planned_unit_ids_ignore_contents_and_locations_but_reject_collisions() {
        let mut c = capture();
        c.effective.input_units = vec![unit("stable-entry", "python", Some(c.effective.clone()))];
        let first = IndexPlan::build(&c, None).unwrap();
        c.root = "/a/new/scratch".into();
        c.effective.semantic_digest = "new-source-content".into();
        let second = IndexPlan::build(&c, None).unwrap();
        assert_eq!(first.units[0].id, second.units[0].id);
        c.effective
            .input_units
            .push(unit("stable-entry", "java", Some(c.effective.clone())));
        assert!(IndexPlan::build(&c, None).is_err());
        c.effective.input_units.truncate(1);
        c.effective.input_units[0].prepared.id.clear();
        assert!(IndexPlan::build(&c, None).is_err());
    }

    #[test]
    fn a_files_only_unit_does_not_require_a_worker() {
        let mut c = capture();
        c.effective.input_units = vec![unit("external", "python", None)];
        let plan = IndexPlan::build(&c, None).unwrap();
        assert_eq!(plan.units[0].availability, UnitAvailability::FilesOnly);
        assert!(!plan.executes(AnalysisBackend::Python));
    }
    #[test]
    fn plan_allocation_uses_the_existing_sticky_task_account() {
        let mut c = capture();
        c.effective.config.modules.push(codeana_config::Module {
            language: "java".into(),
            ..Default::default()
        });
        let budget = AnalysisContext::new(
            AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        let error = IndexPlan::build(&c, Some(&budget)).err().unwrap();
        assert!(error.to_string().contains("ANALYSIS_RESOURCE_LIMIT"));
        assert!(budget.checkpoint().is_err());
    }
}
