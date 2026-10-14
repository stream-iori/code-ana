//! Typed persisted report summaries and comparison; no JSON keys drive business decisions.
use super::*;
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
    pub statistics: codeana_storage::CandidateStatistics,
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
    pub id: String,
    pub created: u64,
    pub revision: String,
    pub level: String,
    pub summary: ReportSummary,
    pub result: TaskResult,
    pub effective: ReportConfig,
    pub context: BuildContext,
    pub diagnostics: Vec<Fact>,
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
