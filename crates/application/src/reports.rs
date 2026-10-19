//! Typed persisted report summaries and comparison; no JSON keys drive business decisions.
use super::*;
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub(super) struct GlobalReport {
    pub measurements_available: bool,
    pub physical_files: usize,
    pub semantic_files: usize,
    pub analysis_instances: usize,
    pub auxiliary_instances: usize,
    pub language_instances: BTreeMap<String, usize>,
    pub units: Vec<UnitReport>,
    pub units_total: usize,
    pub units_omitted: usize,
    pub known_modules: usize,
    pub mode_counts: BTreeMap<String, usize>,
    pub next_steps: Vec<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(super) struct UnitReport {
    pub id: String,
    pub language: String,
    pub entries: Vec<String>,
    pub mode: codeana_config::inputs::InputMode,
    pub reason_code: Option<String>,
    pub log: Option<String>,
}
impl GlobalReport {
    pub(super) fn from_capture(capture: &Capture, measured: bool) -> Self {
        let files = &capture.manifest.files;
        let mut language_instances = BTreeMap::new();
        for file in files
            .iter()
            .filter(|f| !f.language.is_empty() && !f.auxiliary)
        {
            *language_instances.entry(file.language.clone()).or_default() += 1;
        }
        Self {
            measurements_available: measured,
            physical_files: files.iter().filter(|f| f.scope != "CONTROL").map(|f| &f.path).collect::<std::collections::BTreeSet<_>>().len(),
            semantic_files: files.iter().filter(|f| !f.language.is_empty() && !f.auxiliary).map(|f| &f.path).collect::<std::collections::BTreeSet<_>>().len(),
            analysis_instances: language_instances.values().sum(),
            auxiliary_instances: files.iter().filter(|f| f.auxiliary).count(),
            language_instances,
            units_total: capture.effective.input_units.len(),
            units_omitted: capture.effective.input_units.len().saturating_sub(20),
            known_modules: capture.effective.config.modules.len(),
            mode_counts: capture.effective.input_units.iter().fold(BTreeMap::new(), |mut counts, unit| {
                *counts.entry(format!("{}:{:?}", unit.prepared.language, unit.prepared.mode)).or_default() += 1; counts
            }),
            units: capture.effective.input_units.iter().take(20).map(|u| UnitReport {
                id: u.prepared.id.clone(), language: u.prepared.language.clone(), entries: u.prepared.entries.clone(), mode: u.prepared.mode,
                reason_code: u.prepared.reason.clone(), log: u.prepared.log.clone(),
            }).collect(),
            next_steps: vec!["doctor: inspect configuration and input readiness".into(), "inspect task events and referenced preparation logs; after repairing inputs, run index --prepare".into()],
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(super) struct ReportTiming {
    pub index_elapsed_ms: u64,
    pub analyze_ms: u64,
    pub publish_ms: u64,
    pub storage_phase_ms: BTreeMap<String, u64>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(super) struct ReportDiagnostics {
    pub total: u64,
    pub rows: u64,
    pub sampled_occurrences: u64,
    pub omitted_rows: u64,
    pub groups: BTreeMap<String, u64>,
    pub groups_omitted: u64,
    pub sampled: usize,
    pub omitted: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(super) struct ReportSummary {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub analysis_mode: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub classpath_status: Option<String>,
    pub facts: u64,
    pub files: usize,
    pub counts: BTreeMap<String, u64>,
    pub evidence: Evidence,
    pub profile: String,
    pub capture_digest: String,
    pub statistics: CandidateStatistics,
    pub timing: ReportTiming,
    pub diagnostics: ReportDiagnostics,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub(super) enum ReportConfig {
    Full(Box<codeana_config::Effective>),
    Basic {
        config_digest: String,
        origins: BTreeMap<String, String>,
    },
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(super) struct ReportDocument {
    #[serde(default = "legacy_schema")]
    pub schema_version: u32,
    pub id: String,
    pub created: u64,
    pub revision: String,
    pub level: String,
    pub summary: ReportSummary,
    pub result: TaskResult,
    pub effective: ReportConfig,
    pub context: BuildContext,
    pub diagnostics: Vec<Fact>,
    #[serde(default)]
    pub global: GlobalReport,
}
fn legacy_schema() -> u32 {
    1
}
#[derive(Serialize)]
struct ReportComparison<'a> {
    base: &'a str,
    target: &'a str,
    comparable: bool,
    delta: BTreeMap<String, i128>,
    base_summary: &'a ReportSummary,
    target_summary: &'a ReportSummary,
}
impl Engine {
    pub(super) fn save_unpublished_report(
        &self,
        id: &str,
        req: &Request,
        capture: Option<&Capture>,
        result: &mut TaskResult,
    ) -> Result<()> {
        if req.operation != Operation::Index
            || !matches!(result.report, codeana_model::ReportState::Off)
            || capture.is_some_and(|c| c.effective.config.observability.mode == "off")
        {
            return Ok(());
        }
        let (project, _) = codeana_config::identity(Path::new(&req.project));
        let global = capture
            .map(|c| GlobalReport::from_capture(c, false))
            .unwrap_or_default();
        let config_digest = capture
            .map(|c| c.effective.config_digest.clone())
            .unwrap_or_default();
        let profile = capture
            .map(|c| c.effective.semantic_digest.clone())
            .unwrap_or_default();
        let capture_digest = capture
            .map(|c| c.effective.capture_digest.clone())
            .unwrap_or_default();
        let revision = result.output.revision.clone();
        let mut persisted = result.clone();
        persisted.report = id.into();
        let report = ReportDocument {
            schema_version: 2,
            id: id.into(),
            created: now(),
            revision: revision.clone(),
            level: "basic".into(),
            summary: ReportSummary {
                analysis_mode: None,
                classpath_status: None,
                facts: 0,
                files: global.physical_files,
                counts: BTreeMap::new(),
                evidence: result.output.evidence.clone(),
                profile,
                capture_digest,
                statistics: CandidateStatistics::default(),
                timing: ReportTiming {
                    index_elapsed_ms: 0,
                    analyze_ms: 0,
                    publish_ms: 0,
                    storage_phase_ms: BTreeMap::new(),
                },
                diagnostics: ReportDiagnostics {
                    total: 0,
                    rows: 0,
                    sampled_occurrences: 0,
                    omitted_rows: 0,
                    groups: BTreeMap::new(),
                    groups_omitted: 0,
                    sampled: 0,
                    omitted: 0,
                },
            },
            result: persisted,
            effective: ReportConfig::Basic {
                config_digest,
                origins: BTreeMap::new(),
            },
            context: BuildContext {
                language: String::new(),
                modules: vec![],
                source_version: String::new(),
                classpath: vec![],
                producer: result.output.producer.clone(),
                capabilities: vec![],
            },
            diagnostics: vec![],
            global,
        };
        let references = if revision.is_empty() {
            vec![]
        } else {
            vec![revision]
        };
        ensure!(
            serde_json::to_vec(&report)?.len() <= 4 << 20,
            "REPORT_BUDGET"
        );
        self.store
            .put_object("report", id, &project, &report, &references)?;
        result.report = id.into();
        Ok(())
    }
    pub(super) fn report(&self, req: &Request, project: &str) -> Result<Output> {
        let value: ReportDocument = self.store.object("report", &req.selector, project)?;
        let record = if req.base.is_empty() {
            Record::display(&value)?
        } else {
            let base: ReportDocument = self.store.object("report", &req.base, project)?;
            let a = &base.summary;
            let b = &value.summary;
            ensure!(
                a.profile == b.profile
                    && a.capture_digest == b.capture_digest
                    && base.level == value.level,
                DomainError::new(
                    ErrorCode::ReportsNotComparable,
                    "profile, capture policy or level changed"
                )
            );
            let mut delta = BTreeMap::new();
            for (key, left, right) in [
                ("facts", a.facts, b.facts),
                ("files", a.files as u64, b.files as u64),
                (
                    "timing.index_elapsed_ms",
                    a.timing.index_elapsed_ms,
                    b.timing.index_elapsed_ms,
                ),
                (
                    "timing.analyze_ms",
                    a.timing.analyze_ms,
                    b.timing.analyze_ms,
                ),
                (
                    "timing.publish_ms",
                    a.timing.publish_ms,
                    b.timing.publish_ms,
                ),
                (
                    "diagnostics.total",
                    a.diagnostics.total,
                    b.diagnostics.total,
                ),
                (
                    "diagnostics.sampled",
                    a.diagnostics.sampled as u64,
                    b.diagnostics.sampled as u64,
                ),
                (
                    "diagnostics.omitted",
                    a.diagnostics.omitted,
                    b.diagnostics.omitted,
                ),
            ] {
                delta.insert(key.into(), i128::from(right) - i128::from(left));
            }
            for (prefix, left, right) in [
                ("kind", &a.counts, &b.counts),
                ("diagnostic", &a.diagnostics.groups, &b.diagnostics.groups),
            ] {
                for key in left.keys().chain(right.keys()) {
                    delta.insert(
                        format!("{prefix}:{key}"),
                        i128::from(*right.get(key).unwrap_or(&0))
                            - i128::from(*left.get(key).unwrap_or(&0)),
                    );
                }
            }
            Record::display(&ReportComparison {
                base: &req.base,
                target: &req.selector,
                comparable: true,
                delta,
                base_summary: a,
                target_summary: b,
            })?
        };
        Ok(Output {
            records: vec![record],
            evidence: Evidence::complete(),
            ..Default::default()
        })
    }
}

/// Presentation only; machine consumers continue reading the same JSON document.
pub fn report_markdown(value: serde_json::Value) -> Result<String> {
    let report: ReportDocument = serde_json::from_value(value)?;
    ensure!(
        matches!(report.schema_version, 1 | 2),
        "REPORT_SCHEMA_UNSUPPORTED"
    );
    let clean = |text: &str| text.replace(['\n', '\r', '|'], " ");
    let mut out = format!(
        "# Index report {}\n\n| Execution | Quality | Gate | Publication | Revision |\n|---|---|---|---|---|\n| {} | {} | {} | {} | {} |\n\n",
        clean(&report.id),
        report.result.execution,
        report.result.assessment,
        report.result.gate,
        report.result.publication,
        if report.revision.is_empty() {
            "none"
        } else {
            &report.revision
        }
    );
    if report.global.measurements_available {
        out.push_str(&format!(
            "Physical files: {}; semantic files: {}; analysis instances: {}; facts: {}.\n\n",
            report.global.physical_files,
            report.global.semantic_files,
            report.global.analysis_instances,
            report.summary.facts
        ));
    } else {
        out.push_str("Final analysis measurements are unavailable; zero counters do not mean zero issues.\n\n");
    }
    if !report.global.units.is_empty() {
        out.push_str(
            "| Entry | Language | Input mode | Reason | Preparation log |\n|---|---|---|---|---|\n",
        );
        for unit in &report.global.units {
            out.push_str(&format!(
                "| {} | {} | {:?} | {} | {} |\n",
                clean(&unit.id),
                clean(&unit.language),
                unit.mode,
                clean(unit.reason_code.as_deref().unwrap_or("none")),
                clean(unit.log.as_deref().unwrap_or("none"))
            ));
        }
        out.push_str("\nInput mode does not certify complete semantics.\n\n");
    }
    if !report.result.message.is_empty() {
        out.push_str(&format!("Result: {}\n\n", clean(&report.result.message)));
    }
    if !report.summary.diagnostics.groups.is_empty() {
        out.push_str(&format!(
            "Diagnostics: {} occurrences; {} additional groups omitted.\n\n",
            report.summary.diagnostics.total, report.summary.diagnostics.groups_omitted
        ));
        out.push_str("| Diagnostic group | Occurrences |\n|---|---:|\n");
        for (code, count) in &report.summary.diagnostics.groups {
            out.push_str(&format!("| {} | {count} |\n", clean(code)));
        }
        out.push('\n');
    }
    for reason in &report.summary.evidence.reasons {
        out.push_str(&format!("Coverage: {}\n\n", clean(reason)));
    }
    if report.global.units_omitted > 0 {
        out.push_str(&format!(
            "{} additional input units omitted; {} units in total.\n\n",
            report.global.units_omitted, report.global.units_total
        ));
    }
    for step in &report.global.next_steps {
        out.push_str(&format!("- {}\n", clean(step)));
    }
    Ok(out)
}
