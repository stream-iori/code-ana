//! Python facts mapped to the existing typed GRA1 contract.
use crate::{Declaration, Target, facade::Attribution, frozen::WorldAnalysis};
use anyhow::{Result, ensure};
use codeana_contracts::{graph::Row, graph_rows::*};
use codeana_model::{Manifest, key, python::PRODUCER};
use serde::Serialize;
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};

fn id(world: &str, declaration: &Declaration) -> String {
    key(
        "python-declaration",
        &[
            world,
            &declaration.path,
            &declaration.kind,
            &declaration.start.to_string(),
            &declaration.name,
        ],
    )
}

struct SourceText<'a> {
    text: &'a str,
    lines: Vec<usize>,
}

impl<'a> SourceText<'a> {
    fn new(text: &'a str) -> Self {
        let lines = std::iter::once(0)
            .chain(
                text.bytes()
                    .enumerate()
                    .filter_map(|(index, byte)| (byte == b'\n').then_some(index + 1)),
            )
            .collect();
        Self { text, lines }
    }

    fn position(&self, offset: u32) -> Result<(i64, i64)> {
        let offset = offset as usize;
        ensure!(
            offset <= self.text.len() && self.text.is_char_boundary(offset),
            "PYTHON_SOURCE_OFFSET"
        );
        let line = self.lines.partition_point(|start| *start <= offset) - 1;
        Ok((
            (line + 1).try_into()?,
            (self.text[self.lines[line]..offset].encode_utf16().count() + 1).try_into()?,
        ))
    }
}

struct Rows<'a> {
    input: &'a WorldAnalysis,
    world: String,
    declarations: BTreeMap<(&'a str, u32), (&'a Declaration, String)>,
    texts: BTreeMap<&'a str, SourceText<'a>>,
}

impl Rows<'_> {
    fn missing_internal_target(&self, target: &Target) -> bool {
        self.declarations
            .contains_key(&(target.path.as_str(), u32::MAX))
            && !self.declarations.contains_key(&(
                target.path.as_str(),
                if target.start == 0 && target.end == 0 {
                    u32::MAX
                } else {
                    target.start
                },
            ))
    }
    fn owner<'b>(
        &'b self,
        path: &'b str,
        start: u32,
        end: u32,
    ) -> Option<(&'b Declaration, &'b str)> {
        self.declarations
            .range((path, 0)..=(path, u32::MAX))
            .map(|(_, value)| value)
            .filter(|(d, _)| {
                matches!(d.kind.as_str(), "module" | "class" | "function")
                    && d.body_start <= start
                    && d.body_end >= end
            })
            .min_by_key(|(d, _)| d.body_end - d.body_start)
            .map(|(d, id)| (*d, id.as_str()))
    }

    fn edge(
        &self,
        location: (&str, &str, (u32, u32)),
        relation: &str,
        target: Option<&Target>,
        syntax: &str,
        possible: bool,
        reasons: &[String],
    ) -> Result<Row> {
        let (source, file, range) = location;
        let resolved = target.and_then(|t| {
            self.declarations.get(&(
                t.path.as_str(),
                if t.start == 0 && t.end == 0 {
                    u32::MAX
                } else {
                    t.start
                },
            ))
        });
        let target = target.filter(|t| {
            resolved.is_some() || !self.declarations.contains_key(&(t.path.as_str(), u32::MAX))
        });
        let external = target.filter(|_| resolved.is_none());
        let text = &self.texts[file];
        let (bl, bc) = text.position(range.0)?;
        let (el, ec) = text.position(range.1)?;
        Ok(EdgeRow {
            source_ref: Some(source.into()),
            target_ref: resolved.map(|(_, id)| id.clone()),
            external_target_fqn: external.map(|t| t.name.clone()),
            external_target_symbol: external.map(|t| {
                key(
                    "python-external",
                    &[&self.world, &t.path, &t.start.to_string(), &t.name],
                )
            }),
            external_target_language: external.map(|_| "python".into()),
            external_target_provider_id: external.map(|_| PRODUCER.into()),
            relation: Some(relation.into()),
            mechanism: Some("pyrefly".into()),
            language: Some("python".into()),
            provider_id: Some(PRODUCER.into()),
            call_kind: Some("DIRECT".into()),
            confidence: Some(
                if target.is_none() {
                    "UNRESOLVED"
                } else if possible {
                    "INFERRED"
                } else {
                    "EXTRACTED"
                }
                .into(),
            ),
            resolution: Some(
                if target.is_none() {
                    "unresolved"
                } else if possible {
                    "possible"
                } else {
                    "exact"
                }
                .into(),
            ),
            is_external: Some(i64::from(resolved.is_none())),
            source_file: Some(file.into()),
            source_location: Some(format!("{file}:{bl}:{bc}:{el}:{ec}")),
            begin_line: Some(bl),
            begin_column: Some(bc),
            end_line: Some(el),
            end_column: Some(ec),
            source_ordinal: Some(0),
            syntax_target: Some(syntax.into()),
            metadata: Some(json!({"python_unknown_reasons":reasons}).to_string()),
            source_module: Some(self.input.module(file)),
            source_scope: Some(self.input.context.scope.clone()),
            source_is_key: Some(1),
            target_is_key: Some(1),
            resolved_source: Some(source.into()),
            resolved_target: resolved.map(|(_, id)| id.clone()),
            producer_id: Some(PRODUCER.into()),
            ..Default::default()
        }
        .into_row(&self.world))
    }
}

pub fn emit(
    analyses: &[WorldAnalysis],
    manifest: &Manifest,
    profile: &str,
    mut push: impl FnMut(Row) -> Result<()>,
) -> Result<()> {
    for input in analyses {
        let world = input.world.clone();
        if !input.context.gaps.is_empty() {
            push(coverage(
                input,
                "*",
                &input.context.gaps.iter().cloned().collect(),
            )?)?;
        }
        let published = manifest
            .files
            .iter()
            .filter(|s| s.language == "python" && s.world == world && !s.auxiliary)
            .map(|s| s.path.as_str())
            .collect::<BTreeSet<_>>();
        let declarations = input
            .analysis
            .declarations
            .iter()
            .filter(|d| published.contains(d.path.as_str()))
            .map(|d| {
                (
                    (
                        d.path.as_str(),
                        if d.kind == "module" {
                            u32::MAX
                        } else {
                            d.start
                        },
                    ),
                    (d, id(&world, d)),
                )
            })
            .collect();
        let rows = Rows {
            input,
            world: world.clone(),
            declarations,
            texts: input
                .modules
                .iter()
                .map(|m| (m.path.as_str(), SourceText::new(m.source.as_str())))
                .collect(),
        };
        for (declaration, id) in rows.declarations.values() {
            let d = declaration;
            let (bl, bc) = rows.texts[d.path.as_str()].position(d.start)?;
            let (el, ec) = rows.texts[d.path.as_str()].position(d.end)?;
            let location = format!("{}:{bl}:{bc}:{el}:{ec}", d.path);
            let kind = d.kind.to_uppercase();
            push(
                NodeRow {
                    id: Some(id.clone()),
                    symbol_id: Some(id.clone()),
                    domain: Some("language".into()),
                    language: Some("python".into()),
                    provider_id: Some(PRODUCER.into()),
                    entity_kind: Some("declaration".into()),
                    language_kind: Some(kind.clone()),
                    label: Some(d.name.clone()),
                    kind: Some(kind.clone()),
                    qualified_name: Some(d.qualified_name.clone()),
                    source_file: Some(d.path.clone()),
                    source_location: Some(location.clone()),
                    begin_line: Some(bl),
                    begin_column: Some(bc),
                    end_line: Some(el),
                    end_column: Some(ec),
                    source_ordinal: Some(0),
                    module: Some(input.module(&d.path)),
                    scope: Some(input.context.scope.clone()),
                    metadata: Some("{}".into()),
                    producer_id: Some(PRODUCER.into()),
                    ..Default::default()
                }
                .into_row(&world),
            )?;
            push(
                DeclarationRow {
                    symbol_id: Some(id.clone()),
                    domain: Some("language".into()),
                    language: Some("python".into()),
                    provider_id: Some(PRODUCER.into()),
                    entity_kind: Some("declaration".into()),
                    language_kind: Some(kind.clone()),
                    qualified_name: Some(d.qualified_name.clone()),
                    label: Some(d.name.clone()),
                    kind: Some(kind.clone()),
                    declaration_kind: Some(d.kind.clone()),
                    visibility: Some("public".into()),
                    modifiers: Some("[]".into()),
                    declared_modifiers: Some("[]".into()),
                    implicit_modifiers: Some("[]".into()),
                    source_file: Some(d.path.clone()),
                    source_location: Some(location),
                    begin_line: Some(bl),
                    begin_column: Some(bc),
                    end_line: Some(el),
                    end_column: Some(ec),
                    module: Some(input.module(&d.path)),
                    scope: Some(input.context.scope.clone()),
                    nesting_depth: Some(0),
                    direct_member: Some(1),
                    synthetic: Some(0),
                    binding_resolved: Some(1),
                    producer_id: Some(PRODUCER.into()),
                    ..Default::default()
                }
                .into_row(&world),
            )?;
        }
        for (declaration, child) in rows
            .declarations
            .values()
            .filter(|(d, _)| d.kind != "module")
        {
            if let Some((_, parent)) = rows
                .declarations
                .range((declaration.path.as_str(), 0)..=(declaration.path.as_str(), u32::MAX))
                .map(|(_, value)| value)
                .filter(|(p, _)| {
                    p.kind == "module"
                        || (matches!(p.kind.as_str(), "class" | "function")
                            && p.start != declaration.start
                            && p.body_start <= declaration.start
                            && p.body_end >= declaration.end)
                })
                .min_by_key(|(p, _)| p.body_end - p.body_start)
            {
                push(
                    EdgeRow {
                        source_ref: Some(parent.clone()),
                        target_ref: Some(child.clone()),
                        resolved_source: Some(parent.clone()),
                        resolved_target: Some(child.clone()),
                        source_is_key: Some(1),
                        target_is_key: Some(1),
                        relation: Some("CONTAINS".into()),
                        is_external: Some(0),
                        language: Some("python".into()),
                        provider_id: Some(PRODUCER.into()),
                        producer_id: Some(PRODUCER.into()),
                        source_file: Some(declaration.path.clone()),
                        source_module: Some(input.module(&declaration.path)),
                        source_scope: Some(input.context.scope.clone()),
                        resolution: Some("exact".into()),
                        confidence: Some("EXTRACTED".into()),
                        ..Default::default()
                    }
                    .into_row(&world),
                )?;
            }
        }
        for call in input
            .analysis
            .calls
            .iter()
            .filter(|c| published.contains(c.path.as_str()))
        {
            let (_, source) = rows
                .owner(&call.path, call.start, call.end)
                .ok_or_else(|| anyhow::anyhow!("PYTHON_CALL_OWNER_MISSING"))?;
            if call.targets.is_empty() {
                push(rows.edge(
                    (source, &call.path, (call.start, call.end)),
                    "CALLS",
                    None,
                    &call.syntax,
                    false,
                    &call.unknown_reasons,
                )?)?;
            } else {
                for target in &call.targets {
                    push(rows.edge(
                        (source, &call.path, (call.start, call.end)),
                        "CALLS",
                        Some(target),
                        &call.syntax,
                        call.targets.len() > 1 || !call.unknown_reasons.is_empty(),
                        &call.unknown_reasons,
                    )?)?;
                }
            }
        }
        for reference in input
            .analysis
            .references
            .iter()
            .filter(|r| published.contains(r.path.as_str()))
        {
            if let Some((_, source)) = rows.owner(&reference.path, reference.start, reference.end) {
                for target in &reference.targets {
                    // A known source file can contain definitions that this adapter
                    // does not extract. Keep that gap in coverage, not as an
                    // external edge without a target. Unresolved calls separately
                    // retain their call sites.
                    if rows.missing_internal_target(target) {
                        continue;
                    }
                    push(rows.edge(
                        (source, &reference.path, (reference.start, reference.end)),
                        &reference.relation,
                        Some(target),
                        &target.name,
                        reference.targets.len() > 1,
                        &[],
                    )?)?;
                }
            }
        }
        let mut samples = BTreeMap::<&str, Vec<ExtractionSample<'_>>>::new();
        for call in input
            .analysis
            .calls
            .iter()
            .filter(|c| published.contains(c.path.as_str()))
        {
            for reason in &call.unknown_reasons {
                let group = samples.entry(reason).or_default();
                if group.len() < 3 {
                    group.push(extraction_sample(
                        &call.path,
                        call.start,
                        call.end,
                        Some(&call.syntax),
                        &call.targets,
                    ));
                }
            }
        }
        for reference in input
            .analysis
            .references
            .iter()
            .filter(|r| published.contains(r.path.as_str()))
        {
            let reason = if reference.targets.is_empty() {
                Some("reference_target_unknown")
            } else if reference
                .targets
                .iter()
                .any(|t| rows.missing_internal_target(t))
            {
                Some("reference_target_not_extracted")
            } else {
                None
            };
            if let Some(reason) = reason {
                let group = samples.entry(reason).or_default();
                if group.len() < 3 {
                    group.push(extraction_sample(
                        &reference.path,
                        reference.start,
                        reference.end,
                        None,
                        &reference.targets,
                    ));
                }
            }
        }
        push(
            ProjectMetaRow {
                key: Some(format!("python.extraction_samples.{world}")),
                value: Some(serde_json::to_string(&samples)?),
            }
            .into_row(&world),
        )?;
        let mut context_gaps = BTreeSet::new();
        for diagnostic in &input.analysis.diagnostics {
            if semantic_error(&diagnostic.code) && diagnostic.attribution == Attribution::Unknown {
                context_gaps.insert("dependency_impact_unknown".into());
            }
            let diagnostic_id = key(
                "python-diagnostic",
                &[
                    &world,
                    &diagnostic.path,
                    &diagnostic.start.to_string(),
                    &diagnostic.end.to_string(),
                    &diagnostic.code,
                    &diagnostic.message,
                ],
            );
            let origin = if published.contains(diagnostic.path.as_str()) {
                "source"
            } else if manifest
                .files
                .iter()
                .any(|f| f.path == diagnostic.path && f.world == world)
            {
                "auxiliary"
            } else {
                "backend"
            };
            push(DiagnosticRow {
                severity: Some("warning".into()), code: Some(diagnostic.code.clone()),
                phase: Some("analyze".into()), source_file: (origin != "backend").then(|| diagnostic.path.clone()),
                module: Some(input.module(&diagnostic.path)), scope: Some(input.context.scope.clone()),
                language: Some("python".into()), provider_id: Some(PRODUCER.into()),
                reason_code: Some(diagnostic.code.clone()), occurrence_count: Some(1),
                sample: Some(diagnostic.message.clone()),
                sample_details: Some(json!({"diagnostic_id":diagnostic_id,"origin":origin,"source":diagnostic.path,
                    "byte_start":diagnostic.start,"byte_end":diagnostic.end,
                    "attribution":diagnostic.attribution,"affected_sources":diagnostic.affected_sources}).to_string()),
                ..Default::default()
            }.into_row(&world))?;
        }
        if !context_gaps.is_empty() {
            push(coverage(input, "*", &context_gaps)?)?;
        }
        for file in &published {
            let mut gaps = input
                .analysis
                .calls
                .iter()
                .filter(|c| c.path == *file)
                .flat_map(|c| c.unknown_reasons.iter().cloned())
                .collect::<BTreeSet<_>>();
            for diagnostic in &input.analysis.diagnostics {
                if semantic_error(&diagnostic.code) {
                    if diagnostic.path == *file {
                        gaps.insert(diagnostic.code.clone());
                    } else if diagnostic.affected_sources.contains(*file) {
                        gaps.insert(format!("dependency_{}", diagnostic.code));
                    }
                }
            }
            for reference in input.analysis.references.iter().filter(|r| r.path == *file) {
                if reference.targets.is_empty() {
                    gaps.insert("reference_target_unknown".into());
                }
                if reference
                    .targets
                    .iter()
                    .any(|t| rows.missing_internal_target(t))
                {
                    gaps.insert("reference_target_not_extracted".into());
                }
            }
            push(coverage(input, file, &gaps)?)?;
        }
    }
    push(
        ProviderRow {
            provider_id: Some(PRODUCER.into()),
            language: Some("python".into()),
            provider_version: Some(format!(
                "{}-{}",
                codeana_model::python::ADAPTER_VERSION,
                crate::BACKEND_COMMIT
            )),
            operations: Some("[\"declarations\",\"references\",\"calls\"]".into()),
            limitations: Some(
                "[\"static binding only\",\"dynamic and synthesized targets may be unknown\"]"
                    .into(),
            ),
            profile_hash: Some(profile.into()),
        }
        .into_row(""),
    )?;
    Ok(())
}

#[derive(Serialize)]
struct ExtractionSample<'a> {
    source: &'a str,
    byte_start: u32,
    byte_end: u32,
    syntax: Option<&'a str>,
    backend_targets: &'a [Target],
    backend_target_count: usize,
}

fn extraction_sample<'a>(
    source: &'a str,
    byte_start: u32,
    byte_end: u32,
    syntax: Option<&'a str>,
    targets: &'a [Target],
) -> ExtractionSample<'a> {
    ExtractionSample {
        source,
        byte_start,
        byte_end,
        syntax,
        backend_targets: &targets[..targets.len().min(8)],
        backend_target_count: targets.len(),
    }
}

fn semantic_error(code: &str) -> bool {
    matches!(code, "parse-error" | "missing-import" | "missing-source")
}

fn coverage(input: &WorldAnalysis, source: &str, gaps: &BTreeSet<String>) -> Result<Row> {
    let counts = gaps.iter().map(|g| (g, 1)).collect::<BTreeMap<_, _>>();
    Ok(CoverageRow {
        source_file: Some(source.into()),
        module: Some(input.module(source)),
        scope: Some(input.context.scope.clone()),
        language: Some("python".into()),
        provider_id: Some(PRODUCER.into()),
        capability: Some("AGGREGATE".into()),
        status: Some(
            if gaps.is_empty() {
                "complete"
            } else {
                "partial"
            }
            .into(),
        ),
        occurrences: Some(gaps.len().try_into()?),
        groups_count: Some(gaps.len().try_into()?),
        codes: Some(serde_json::to_string(gaps)?),
        code_counts: Some(serde_json::to_string(&counts)?),
        details_truncated: Some(0),
    }
    .into_row(&input.world))
}
