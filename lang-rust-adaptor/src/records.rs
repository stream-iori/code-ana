//! Translate Rust facts into the shared typed graph archive contract.
use crate::{Analysis, incremental};
use anyhow::Result;
use codeana_contracts::{
    graph::Row,
    graph_archive::{Builder, GraphArchive},
    graph_rows::*,
};
use codeana_model::rust::{AnalysisConfig, PRODUCER, SourceState};
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};

/// One index per physical source; columns retain the public one-based UTF-16 contract.
struct LineIndex<'a> {
    text: &'a str,
    starts: Vec<usize>,
}
impl<'a> LineIndex<'a> {
    fn new(text: &'a str) -> Self {
        let starts = std::iter::once(0)
            .chain(
                text.bytes()
                    .enumerate()
                    .filter_map(|(i, b)| (b == b'\n').then_some(i + 1)),
            )
            .collect();
        Self { text, starts }
    }
    fn position(&self, offset: usize) -> (usize, usize) {
        let mut end = offset.min(self.text.len());
        while !self.text.is_char_boundary(end) {
            end -= 1;
        }
        let line = self.starts.partition_point(|start| *start <= end) - 1;
        (
            line + 1,
            self.text[self.starts[line]..end].encode_utf16().count() + 1,
        )
    }
}
pub struct Prepared {
    pub replacements: BTreeSet<(String, String)>,
    pub states: Vec<SourceState>,
}
pub fn prepare(
    config: &AnalysisConfig,
    analysis: &Analysis,
    baseline: Option<&[SourceState]>,
) -> Prepared {
    let states = incremental::states(config, analysis);
    let replacements = baseline
        .map(|old| incremental::dirty(old, &states))
        .unwrap_or_else(|| {
            states
                .iter()
                .map(|s| (s.file.clone(), s.world.clone()))
                .collect()
        });
    Prepared {
        states,
        replacements,
    }
}
pub struct Records {
    pub archive: GraphArchive,
    pub replacements: BTreeSet<(String, String)>,
    pub states: Vec<SourceState>,
}
pub fn records(
    config: &AnalysisConfig,
    analysis: &Analysis,
    baseline: Option<&[SourceState]>,
) -> Result<Records> {
    let prepared = prepare(config, analysis, baseline);
    let mut out = Builder::new(
        Some(&config.scratch),
        config.result_bytes,
        config.fact_limit,
    )?;
    emit(config, analysis, &prepared, |row| out.push(&row))?;
    Ok(Records {
        archive: out.seal()?,
        replacements: prepared.replacements,
        states: prepared.states,
    })
}
/// Stream rows to a bounded sink; archive and IPC share the same fact generation.
pub fn emit(
    config: &AnalysisConfig,
    analysis: &Analysis,
    prepared: &Prepared,
    mut push: impl FnMut(Row) -> Result<()>,
) -> Result<()> {
    let states = &prepared.states;
    let wanted =
        |file: &str, world: &str| prepared.replacements.contains(&(file.into(), world.into()));
    let lines = analysis
        .texts
        .iter()
        .map(|(path, text)| (path.as_str(), LineIndex::new(text)))
        .collect::<BTreeMap<_, _>>();
    let declarations = analysis
        .declarations
        .iter()
        .map(|d| (d.id.as_str(), d))
        .collect::<BTreeMap<_, _>>();
    for d in &analysis.declarations {
        if !wanted(&d.source, &d.world) {
            continue;
        }
        let text = &lines[d.source.as_str()];
        let (bl, bc) = text.position(d.start);
        let (el, ec) = text.position(d.end);
        let location = format!("{}:{bl}:{bc}:{el}:{ec}", d.source);
        let metadata =
            json!({"rust_kind":d.kind,"signature_hash":d.signature_hash,"generated":d.synthetic})
                .to_string();
        push(
            NodeRow {
                id: Some((d.id).to_string()),
                symbol_id: Some((d.id).to_string()),
                domain: Some(("language").to_string()),
                language: Some(("rust").to_string()),
                provider_id: Some((PRODUCER).to_string()),
                entity_kind: Some(("declaration").to_string()),
                language_kind: Some((d.kind).to_string()),
                label: Some((d.name).to_string()),
                kind: Some((d.kind).to_string()),
                qualified_name: Some((d.qualified_name).to_string()),
                package: None,
                namespace: None,
                source_file: Some((d.source).to_string()),
                source_location: Some((location).to_string()),
                begin_line: Some(i64::try_from(bl)?),
                begin_column: Some(i64::try_from(bc)?),
                end_line: Some(i64::try_from(el)?),
                end_column: Some(i64::try_from(ec)?),
                source_ordinal: Some(0),
                module: Some((d.module).to_string()),
                scope: Some((d.scope).to_string()),
                metadata: Some((metadata).to_string()),
                arity_key: None,
                producer_id: Some((PRODUCER).to_string()),
            }
            .into_row(&d.world),
        )?;
        push(
            DeclarationRow {
                symbol_id: Some((d.id).to_string()),
                domain: Some(("language").to_string()),
                language: Some(("rust").to_string()),
                provider_id: Some((PRODUCER).to_string()),
                entity_kind: Some(("declaration").to_string()),
                language_kind: Some((d.kind).to_string()),
                qualified_name: Some((d.qualified_name).to_string()),
                label: Some((d.name).to_string()),
                kind: Some((d.kind).to_string()),
                declaration_kind: Some((d.kind).to_string()),
                type_kind: None,
                visibility: Some((d.visibility).to_string()),
                modifiers: Some(("[]").to_string()),
                declared_modifiers: Some(("[]").to_string()),
                implicit_modifiers: Some(("[]").to_string()),
                declaring_type: d.parent.as_ref().map(|v| v.to_string()),
                namespace: None,
                source_file: Some((d.source).to_string()),
                source_location: Some((location).to_string()),
                begin_line: Some(i64::try_from(bl)?),
                begin_column: Some(i64::try_from(bc)?),
                end_line: Some(i64::try_from(el)?),
                end_column: Some(i64::try_from(ec)?),
                module: Some((d.module).to_string()),
                scope: Some((d.scope).to_string()),
                nesting_depth: Some(0),
                direct_member: Some(1),
                synthetic: Some(i64::from(d.synthetic)),
                binding_resolved: Some(i64::from(d.resolved)),
                producer_id: Some((PRODUCER).to_string()),
            }
            .into_row(&d.world),
        )?;
        if let Some(parent) = &d.parent {
            let r = crate::Relation {
                source: parent.clone(),
                target: Some(d.id.clone()),
                external: None,
                kind: codeana_model::rust::RustRelation::Contains,
                file: d.source.clone(),
                world: d.world.clone(),
                start: d.start,
                end: d.end,
                syntax: String::new(),
                resolution: codeana_model::rust::RustResolution::Exact,
            };
            push(edge(&r, &d.module, &d.scope, text)?)?;
        }
    }
    for r in &analysis.relations {
        if !wanted(&r.file, &r.world)
            || (r.kind != "CALLS" && r.target.is_none() && r.external.is_none())
        {
            continue;
        }
        if let Some(d) = declarations.get(r.source.as_str()) {
            push(edge(r, &d.module, &d.scope, &lines[r.file.as_str()])?)?;
        }
    }
    for ((file, world), gaps) in &analysis.gaps {
        if !wanted(file, world) {
            continue;
        }
        let input = config
            .inputs
            .crates
            .iter()
            .find(|c| &c.world == world)
            .unwrap();
        let counts = gaps.iter().map(|g| (g, 1)).collect::<BTreeMap<_, _>>();
        push(
            CoverageRow {
                source_file: Some((file).to_string()),
                module: Some((input.module).to_string()),
                scope: Some((input.scope).to_string()),
                language: Some(("rust").to_string()),
                provider_id: Some((PRODUCER).to_string()),
                capability: Some(("AGGREGATE").to_string()),
                status: Some(
                    (if gaps.is_empty() {
                        "complete"
                    } else {
                        "partial"
                    })
                    .to_string(),
                ),
                occurrences: Some(i64::try_from(gaps.len())?),
                groups_count: Some(i64::try_from(gaps.len())?),
                codes: Some((serde_json::to_string(gaps)?).to_string()),
                code_counts: Some((serde_json::to_string(&counts)?).to_string()),
                details_truncated: Some(0),
            }
            .into_row(world),
        )?;
        for gap in gaps {
            push(
                DiagnosticRow {
                    severity: Some(("warning").to_string()),
                    code: Some((gap).to_string()),
                    phase: Some(("analyze").to_string()),
                    source_file: Some((file).to_string()),
                    module: Some((input.module).to_string()),
                    scope: Some((input.scope).to_string()),
                    symbol: None,
                    language: Some(("rust").to_string()),
                    provider_id: Some((PRODUCER).to_string()),
                    provider_reason: None,
                    reason_code: Some((gap).to_string()),
                    sample_details: None,
                    occurrence_count: Some(1),
                    sample: Some((gap).to_string()),
                }
                .into_row(world),
            )?;
        }
    }
    for state in states {
        if !wanted(&state.file, &state.world) {
            continue;
        }
        push(
            SourceStateRow {
                source_file: Some((state.file).to_string()),
                world: Some((state.world).to_string()),
                state: Some((serde_json::to_string(state)?).to_string()),
            }
            .into_row(&state.world),
        )?;
        for dep in &state.dependencies {
            push(
                AnalysisDependencyRow {
                    source_file: Some((state.file).to_string()),
                    world: Some((state.world).to_string()),
                    kind: Some(dep.kind.as_str().into()),
                    target_world: Some((dep.world).to_string()),
                    target_key: Some((dep.key).to_string()),
                }
                .into_row(&state.world),
            )?;
        }
    }
    push(
        ProviderRow {
            provider_id: Some((PRODUCER).to_string()),
            language: Some(("rust").to_string()),
            provider_version: Some((crate::ADAPTER_VERSION).to_string()),
            operations: Some(("[\"declarations\",\"references\",\"calls\"]").to_string()),
            limitations: Some(
                ("[\"no proc-macro execution\",\"dynamic dispatch incomplete\"]").to_string(),
            ),
            profile_hash: Some((codeana_model::digest(&config.inputs)).to_string()),
        }
        .into_row(""),
    )?;
    push(
        ProjectMetaRow {
            key: Some(("rust.analysis_mode").to_string()),
            value: Some((config.mode).to_string()),
        }
        .into_row(""),
    )?;
    Ok(())
}

fn edge(r: &crate::Relation, module: &str, scope: &str, text: &LineIndex<'_>) -> Result<Row> {
    let (bl, bc) = text.position(r.start);
    let (el, ec) = text.position(r.end);
    Ok(EdgeRow {
        source_ref: Some((r.source).to_string()),
        target_ref: r.target.as_ref().map(|v| v.to_string()),
        external_target_fqn: r.external.as_ref().map(|v| v.to_string()),
        external_target_symbol: None,
        external_target_language: r.external.as_ref().map(|_| "rust").map(str::to_owned),
        external_target_provider_id: r.external.as_ref().map(|_| PRODUCER).map(str::to_owned),
        relation: Some((r.kind).to_string()),
        semantic: None,
        mechanism: Some(("rust-analyzer").to_string()),
        language: Some(("rust").to_string()),
        provider_id: Some((PRODUCER).to_string()),
        call_kind: Some((if r.kind == "CALLS" { "DIRECT" } else { "" }).to_string()),
        confidence: Some(
            (if r.resolution == "exact" {
                "EXTRACTED"
            } else {
                "UNRESOLVED"
            })
            .to_string(),
        ),
        resolution: Some((r.resolution).to_string()),
        context: None,
        is_external: Some(i64::from(r.target.is_none())),
        source_file: Some((r.file).to_string()),
        source_location: Some((format!("{}:{bl}:{bc}:{el}:{ec}", r.file)).to_string()),
        begin_line: Some(i64::try_from(bl)?),
        begin_column: Some(i64::try_from(bc)?),
        end_line: Some(i64::try_from(el)?),
        end_column: Some(i64::try_from(ec)?),
        source_ordinal: Some(0),
        syntax_target: Some((r.syntax).to_string()),
        receiver_static_type: None,
        metadata: Some(("{}").to_string()),
        source_module: Some((module).to_string()),
        source_scope: Some((scope).to_string()),
        source_is_key: Some(1),
        target_is_key: Some(1),
        resolved_source: Some((r.source).to_string()),
        resolved_target: r.target.as_ref().map(|v| v.to_string()),
        target_arity_key: None,
        producer_id: Some((PRODUCER).to_string()),
    }
    .into_row(&r.world))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn indexed_positions_preserve_utf16_crlf_and_clamping() {
        for text in ["", "hello\r\n中文😀\nend", "😀"] {
            let lines = LineIndex::new(text);
            for offset in 0..=text.len() + 2 {
                let mut end = offset.min(text.len());
                while !text.is_char_boundary(end) {
                    end -= 1;
                }
                let prefix = &text[..end];
                assert_eq!(
                    lines.position(offset),
                    (
                        prefix.bytes().filter(|b| *b == b'\n').count() + 1,
                        prefix.rsplit('\n').next().unwrap().encode_utf16().count() + 1
                    )
                );
            }
        }
    }
}
