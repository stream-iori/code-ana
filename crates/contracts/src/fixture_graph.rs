//! Explicit mock-fixture conversion. The Java product never uses this adapter.
use crate::graph::{Cell, Row, Table, cell};
use crate::graph_rows::*;
use codeana_model::{Fact, FactKind, Manifest};
use serde_json::{Value, json};

/// Raw malformed-row construction used by protocol fault tests, never ordinary fixture emission.
pub fn row(table: Table, values: Vec<Value>) -> Row {
    Row {
        world: String::new(),
        table: table as i32,
        cells: values
            .into_iter()
            .map(|v| Cell {
                value: match v {
                    Value::Null => None,
                    Value::Number(n) => Some(cell::Value::Integer(n.as_i64().unwrap_or_default())),
                    Value::Bool(b) => Some(cell::Value::Integer(i64::from(b))),
                    Value::String(s) => Some(cell::Value::Text(s)),
                    other => Some(cell::Value::Text(other.to_string())),
                },
            })
            .collect(),
    }
}
fn attr(f: &Fact, key: &str, default: Value) -> Value {
    f.attributes.wire_map().get(key).cloned().unwrap_or(default)
}
pub fn rows(manifest: &Manifest, facts: &[Fact]) -> Vec<Row> {
    let mut output = Vec::new();
    let mut source_owners = std::collections::BTreeMap::<String, String>::new();
    for f in facts.iter().filter(|f| f.kind == FactKind::Declaration) {
        source_owners
            .entry(f.source.clone())
            .or_insert(f.id.to_string());
    }
    for f in facts.iter().filter(|f| {
        matches!(
            f.kind,
            FactKind::Relation | FactKind::Reference | FactKind::Dependency
        ) && f.parent.is_empty()
    }) {
        if !source_owners.contains_key(&f.source) {
            let id = format!("fixture-source:{}", f.source);
            let source = manifest.files.iter().find(|s| s.path == f.source);
            output.push(
                NodeRow {
                    id: Some((id).to_string()),
                    symbol_id: Some((id).to_string()),
                    domain: Some(("language").to_string()),
                    language: Some(("java").to_string()),
                    provider_id: Some(("java-core").to_string()),
                    entity_kind: Some(("type").to_string()),
                    language_kind: Some(("CLASS").to_string()),
                    label: Some((f.source).to_string()),
                    kind: Some(("CLASS").to_string()),
                    qualified_name: Some((id).to_string()),
                    package: Some(("").to_string()),
                    namespace: None,
                    source_file: Some((f.source).to_string()),
                    source_location: None,
                    begin_line: None,
                    begin_column: None,
                    end_line: None,
                    end_column: None,
                    source_ordinal: None,
                    module: Some((source.map(|s| s.module.as_str()).unwrap_or(".")).to_string()),
                    scope: Some((source.map(|s| s.scope.as_str()).unwrap_or("MAIN")).to_string()),
                    metadata: None,
                    arity_key: None,
                    producer_id: Some(("java-core").to_string()),
                }
                .into_row(""),
            );
            source_owners.insert(f.source.clone(), id);
        }
    }
    for (position, f) in facts.iter().enumerate() {
        let source = manifest.files.iter().find(|s| s.path == f.source);
        let module = source.map(|s| s.module.as_str()).unwrap_or(".");
        let scope = source.map(|s| s.scope.as_str()).unwrap_or("MAIN");
        match f.kind {
            FactKind::Declaration | FactKind::ExternalSymbol => {
                let mut metadata = f.attributes.wire_map();
                if let Some(span) = &f.span {
                    metadata.insert("fixture_span".into(), json!(span));
                }
                metadata.insert("fixture_signature".into(), json!(f.signature));
                let kind = attr(f, "element_kind", json!("CLASS"));
                let symbol: &str = if f.signature.is_empty() {
                    &f.id
                } else {
                    &f.signature
                };
                output.push(
                    NodeRow {
                        id: Some((f.id).to_string()),
                        symbol_id: Some((symbol).to_string()),
                        domain: Some(("language").to_string()),
                        language: Some(("java").to_string()),
                        provider_id: Some(("java-core").to_string()),
                        entity_kind: text_value(attr(f, "entity_kind", json!("type"))),
                        language_kind: text_value(kind.clone()),
                        label: Some((f.name).to_string()),
                        kind: text_value(kind),
                        qualified_name: Some((f.signature).to_string()),
                        package: text_value(attr(f, "package", json!(""))),
                        namespace: None,
                        source_file: Some((f.source).to_string()),
                        source_location: None,
                        begin_line: None,
                        begin_column: None,
                        end_line: None,
                        end_column: None,
                        source_ordinal: None,
                        module: Some((module).to_string()),
                        scope: Some((scope).to_string()),
                        metadata: Some(
                            (serde_json::to_string(&metadata).expect("fixture metadata"))
                                .to_string(),
                        ),
                        arity_key: None,
                        producer_id: Some(("java-core").to_string()),
                    }
                    .into_row(""),
                );
                if !f.parent.is_empty() && f.kind == FactKind::Declaration {
                    output.push(
                        edge(f, &f.parent, &f.id, "CONTAINS", module, scope, Value::Null)
                            .into_row(""),
                    );
                }
            }
            FactKind::Relation | FactKind::Reference | FactKind::Dependency => {
                let relation = match f.relation.as_str() {
                    "extends" => "INHERITS",
                    "implements" => "IMPLEMENTS",
                    "injects_possible" => "INJECTS",
                    "wires_possible" => "WIRES",
                    "config" => "CONFIGURES",
                    other => return_relation(other),
                };
                let parent = if f.parent.is_empty() {
                    source_owners
                        .get(&f.source)
                        .map(String::as_str)
                        .unwrap_or("")
                } else {
                    &f.parent
                };
                output.push(
                    edge(f, parent, &f.target, relation, module, scope, Value::Null).into_row(""),
                );
            }
            FactKind::CallSite => {
                let mut metadata = f.attributes.wire_map();
                if let Some(span) = &f.span {
                    metadata.insert("fixture_span".into(), json!(span));
                }
                output.push(
                    UnresolvedCallRow {
                        source_ref: Some((f.parent).to_string()),
                        source_file: Some((f.source).to_string()),
                        begin_line: Some(1),
                        begin_column: Some(1),
                        end_line: Some(1),
                        end_column: Some(2),
                        source_ordinal: Some(position as i64),
                        syntax_target: Some((f.name).to_string()),
                        receiver_static_type: text_value(attr(
                            f,
                            "receiver_static_type",
                            Value::Null,
                        )),
                        call_kind: text_value(attr(f, "dispatch", Value::Null)),
                        context: None,
                        metadata: Some(serde_json::to_string(&metadata).expect("fixture metadata")),
                        producer_id: Some(("java-core").to_string()),
                        source_module: Some((module).to_string()),
                        source_scope: Some((scope).to_string()),
                        resolved_source: Some((f.parent).to_string()),
                    }
                    .into_row(""),
                );
            }
            FactKind::CallTarget => {
                if let Some((position, site)) =
                    facts.iter().enumerate().find(|(_, s)| s.id == f.parent)
                {
                    let mut target = edge(
                        f,
                        &site.parent,
                        &f.target,
                        "CALLS",
                        module,
                        scope,
                        json!(site.name),
                    );
                    target.source_ordinal = Some(position as i64);
                    let mut metadata = site.attributes.wire_map();
                    if let Some(span) = &site.span {
                        metadata.insert("fixture_span".into(), json!(span));
                    }
                    target.metadata =
                        Some(serde_json::to_string(&metadata).expect("fixture metadata"));
                    output.push(target.into_row(""));
                }
            }
            FactKind::Facet if f.relation == "java.annotation" => {
                let name = attr(f, "annotation", attr(f, "name", json!(f.name)));
                output.push(
                    AnnotationRow {
                        node_ref: Some((f.parent).to_string()),
                        annotation_fqn: text_value(name.clone()),
                        raw_name: text_value(name),
                        attributes: Some(
                            serde_json::to_string(&f.attributes.wire_map())
                                .expect("fixture attributes"),
                        ),
                        target_kind: Some(("DECLARATION").to_string()),
                        target_path: None,
                        language: Some(("java").to_string()),
                        provider_id: Some(("java-core").to_string()),
                        mechanism: Some(("java.annotation").to_string()),
                        resolution_status: Some(("exact").to_string()),
                        source_file: Some((f.source).to_string()),
                        source_location: None,
                        begin_line: None,
                        begin_column: None,
                        end_line: None,
                        end_column: None,
                        source_module: Some((module).to_string()),
                        source_scope: Some((scope).to_string()),
                        node_is_key: Some(1),
                        resolved_node: Some((f.parent).to_string()),
                        producer_id: Some(("java-core").to_string()),
                    }
                    .into_row(""),
                );
            }
            FactKind::Coverage => output.push(
                CoverageRow {
                    source_file: Some((f.source).to_string()),
                    module: Some((module).to_string()),
                    scope: Some((scope).to_string()),
                    language: Some(("java").to_string()),
                    provider_id: Some(("java-core").to_string()),
                    capability: Some(("SEMANTIC").to_string()),
                    status: Some((f.status).to_string()),
                    occurrences: Some(0),
                    groups_count: Some(0),
                    codes: Some(("[]").to_string()),
                    code_counts: Some(("{}").to_string()),
                    details_truncated: Some(0),
                }
                .into_row(""),
            ),
            FactKind::Diagnostic => output.push(
                DiagnosticRow {
                    severity: text_value(attr(f, "severity", json!("warning"))),
                    code: text_value(attr(f, "code", json!(f.name))),
                    phase: Some(("MOCK").to_string()),
                    source_file: Some((f.source).to_string()),
                    module: Some((module).to_string()),
                    scope: Some((scope).to_string()),
                    symbol: None,
                    language: Some(("java").to_string()),
                    provider_id: Some(("java-core").to_string()),
                    provider_reason: None,
                    reason_code: None,
                    sample_details: None,
                    occurrence_count: integer_value(attr(f, "count", json!(1))),
                    sample: text_value(attr(f, "sample", Value::Null)),
                }
                .into_row(""),
            ),
            // Fixtures for removed raw-model capabilities do not become product rows.
            _ => {}
        }
    }
    output
}
fn return_relation(raw: &str) -> &str {
    raw
}
fn edge(
    f: &Fact,
    parent: &str,
    target: &str,
    relation: &str,
    module: &str,
    scope: &str,
    syntax: Value,
) -> EdgeRow {
    EdgeRow {
        source_ref: Some((parent).to_string()),
        target_ref: Some((target).to_string()),
        external_target_fqn: None,
        external_target_symbol: None,
        external_target_language: None,
        external_target_provider_id: None,
        relation: Some((relation.to_ascii_uppercase()).to_string()),
        semantic: None,
        mechanism: None,
        language: Some(("java").to_string()),
        provider_id: Some(("java-core").to_string()),
        call_kind: None,
        confidence: Some(
            (if f.status == "resolved" {
                "EXTRACTED"
            } else {
                "INFERRED"
            })
            .to_string(),
        ),
        resolution: None,
        context: None,
        is_external: Some(0),
        source_file: Some((f.source).to_string()),
        source_location: None,
        begin_line: Some(1),
        begin_column: Some(1),
        end_line: Some(1),
        end_column: Some(2),
        source_ordinal: Some(0),
        syntax_target: text_value(syntax),
        receiver_static_type: text_value(attr(f, "receiver_static_type", Value::Null)),
        metadata: Some(
            serde_json::to_string(&f.attributes.wire_map()).expect("fixture attributes"),
        ),
        source_module: Some((module).to_string()),
        source_scope: Some((scope).to_string()),
        source_is_key: Some(1),
        target_is_key: Some(1),
        resolved_source: Some((parent).to_string()),
        resolved_target: Some((target).to_string()),
        target_arity_key: None,
        producer_id: Some(("java-core").to_string()),
    }
}

// Fixture attributes are decoded at this mock boundary; product constructors use typed fields.
fn text_value(value: Value) -> Option<String> {
    match value {
        Value::Null => None,
        Value::String(s) => Some(s),
        other => Some(other.to_string()),
    }
}
fn integer_value(value: Value) -> Option<i64> {
    match value {
        Value::Null => None,
        Value::Bool(v) => Some(i64::from(v)),
        v => Some(v.as_i64().expect("fixture integer")),
    }
}
