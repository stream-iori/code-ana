//! Translate Rust facts into the shared typed graph archive contract.
use crate::{Analysis, incremental};
use anyhow::Result;
use codeana_contracts::{
    graph::{Cell, Row, Table, cell},
    graph_archive::{Builder, GraphArchive},
};
use codeana_model::rust::{AnalysisConfig, PRODUCER, SourceState};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};

fn row(table: Table, world: &str, values: Vec<Value>) -> Row {
    Row {
        table: table as i32,
        world: world.into(),
        cells: values
            .into_iter()
            .map(|v| Cell {
                value: match v {
                    Value::Null => None,
                    Value::String(s) => Some(cell::Value::Text(s)),
                    Value::Number(n) => {
                        Some(cell::Value::Integer(n.as_i64().expect("signed cell")))
                    }
                    _ => unreachable!("typed graph cells"),
                },
            })
            .collect(),
    }
}
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
        push(row(
            Table::Node,
            &d.world,
            vec![
                json!(d.id),
                json!(d.id),
                json!("language"),
                json!("rust"),
                json!(PRODUCER),
                json!("declaration"),
                json!(d.kind),
                json!(d.name),
                json!(d.kind),
                json!(d.qualified_name),
                Value::Null,
                Value::Null,
                json!(d.source),
                json!(location),
                json!(bl),
                json!(bc),
                json!(el),
                json!(ec),
                json!(0),
                json!(d.module),
                json!(d.scope),
                json!(metadata),
                Value::Null,
                json!(PRODUCER),
            ],
        ))?;
        push(row(
            Table::Declaration,
            &d.world,
            vec![
                json!(d.id),
                json!("language"),
                json!("rust"),
                json!(PRODUCER),
                json!("declaration"),
                json!(d.kind),
                json!(d.qualified_name),
                json!(d.name),
                json!(d.kind),
                json!(d.kind),
                Value::Null,
                json!(d.visibility),
                json!("[]"),
                json!("[]"),
                json!("[]"),
                json!(d.parent),
                Value::Null,
                json!(d.source),
                json!(location),
                json!(bl),
                json!(bc),
                json!(el),
                json!(ec),
                json!(d.module),
                json!(d.scope),
                json!(0),
                json!(1),
                json!(i64::from(d.synthetic)),
                json!(i64::from(d.resolved)),
                json!(PRODUCER),
            ],
        ))?;
        if let Some(parent) = &d.parent {
            let r = crate::Relation {
                source: parent.clone(),
                target: Some(d.id.clone()),
                external: None,
                kind: "CONTAINS".into(),
                file: d.source.clone(),
                world: d.world.clone(),
                start: d.start,
                end: d.end,
                syntax: String::new(),
                resolution: "exact".into(),
            };
            push(edge(&r, &d.module, &d.scope, text))?;
        }
    }
    for r in &analysis.relations {
        if !wanted(&r.file, &r.world)
            || (r.kind != "CALLS" && r.target.is_none() && r.external.is_none())
        {
            continue;
        }
        if let Some(d) = declarations.get(r.source.as_str()) {
            push(edge(r, &d.module, &d.scope, &lines[r.file.as_str()]))?;
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
        push(row(
            Table::Coverage,
            world,
            vec![
                json!(file),
                json!(input.module),
                json!(input.scope),
                json!("rust"),
                json!(PRODUCER),
                json!("AGGREGATE"),
                json!(if gaps.is_empty() {
                    "complete"
                } else {
                    "partial"
                }),
                json!(gaps.len()),
                json!(gaps.len()),
                json!(serde_json::to_string(gaps)?),
                json!(serde_json::to_string(&counts)?),
                json!(0),
            ],
        ))?;
        for gap in gaps {
            push(row(
                Table::Diagnostic,
                world,
                vec![
                    json!("warning"),
                    json!(gap),
                    json!("analyze"),
                    json!(file),
                    json!(input.module),
                    json!(input.scope),
                    Value::Null,
                    json!("rust"),
                    json!(PRODUCER),
                    Value::Null,
                    json!(gap),
                    Value::Null,
                    json!(1),
                    json!(gap),
                ],
            ))?;
        }
    }
    for state in states {
        if !wanted(&state.file, &state.world) {
            continue;
        }
        push(row(
            Table::SourceState,
            &state.world,
            vec![
                json!(state.file),
                json!(state.world),
                json!(serde_json::to_string(state)?),
            ],
        ))?;
        for dep in &state.dependencies {
            push(row(
                Table::AnalysisDependency,
                &state.world,
                vec![
                    json!(state.file),
                    json!(state.world),
                    json!(serde_json::to_value(&dep.kind)?.as_str().unwrap()),
                    json!(dep.world),
                    json!(dep.key),
                ],
            ))?;
        }
    }
    push(row(
        Table::Provider,
        "",
        vec![
            json!(PRODUCER),
            json!("rust"),
            json!(crate::ADAPTER_VERSION),
            json!("[\"declarations\",\"references\",\"calls\"]"),
            json!("[\"no proc-macro execution\",\"dynamic dispatch incomplete\"]"),
            json!(codeana_model::digest(&config.inputs)),
        ],
    ))?;
    push(row(
        Table::ProjectMeta,
        "",
        vec![json!("rust.analysis_mode"), json!(config.mode)],
    ))?;
    Ok(())
}

fn edge(r: &crate::Relation, module: &str, scope: &str, text: &LineIndex<'_>) -> Row {
    let (bl, bc) = text.position(r.start);
    let (el, ec) = text.position(r.end);
    row(
        Table::Edge,
        &r.world,
        vec![
            json!(r.source),
            json!(r.target),
            json!(r.external),
            Value::Null,
            json!(r.external.as_ref().map(|_| "rust")),
            json!(r.external.as_ref().map(|_| PRODUCER)),
            json!(r.kind),
            Value::Null,
            json!("rust-analyzer"),
            json!("rust"),
            json!(PRODUCER),
            json!(if r.kind == "CALLS" { "DIRECT" } else { "" }),
            json!(if r.resolution == "exact" {
                "EXTRACTED"
            } else {
                "UNRESOLVED"
            }),
            json!(r.resolution),
            Value::Null,
            json!(i64::from(r.target.is_none())),
            json!(r.file),
            json!(format!("{}:{bl}:{bc}:{el}:{ec}", r.file)),
            json!(bl),
            json!(bc),
            json!(el),
            json!(ec),
            json!(0),
            json!(r.syntax),
            Value::Null,
            json!("{}"),
            json!(module),
            json!(scope),
            json!(1),
            json!(1),
            json!(r.source),
            json!(r.target),
            Value::Null,
            json!(PRODUCER),
        ],
    )
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
