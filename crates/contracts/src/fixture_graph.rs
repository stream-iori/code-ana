//! Explicit mock-fixture conversion. The Java product never uses this adapter.
use crate::graph::{Cell, Row, Table, cell};
use codeana_model::{Fact, FactKind, Manifest};
use serde_json::{Value, json};

pub fn row(table: Table, values: Vec<Value>) -> Row {
    Row {
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
    f.attributes.get(key).cloned().unwrap_or(default)
}
pub fn rows(manifest: &Manifest, facts: &[Fact]) -> Vec<Row> {
    let mut output = Vec::new();
    let mut source_owners = std::collections::BTreeMap::<String, String>::new();
    for f in facts.iter().filter(|f| f.kind == FactKind::Declaration) {
        source_owners
            .entry(f.source.clone())
            .or_insert(f.id.clone());
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
            output.push(row(
                Table::Node,
                vec![
                    json!(id),
                    json!(id),
                    json!("language"),
                    json!("java"),
                    json!("java-core"),
                    json!("type"),
                    json!("CLASS"),
                    json!(f.source),
                    json!("CLASS"),
                    json!(id),
                    json!(""),
                    Value::Null,
                    json!(f.source),
                    Value::Null,
                    Value::Null,
                    Value::Null,
                    Value::Null,
                    Value::Null,
                    Value::Null,
                    json!(source.map(|s| s.module.as_str()).unwrap_or(".")),
                    json!(source.map(|s| s.scope.as_str()).unwrap_or("MAIN")),
                    Value::Null,
                    Value::Null,
                    json!("java-core"),
                ],
            ));
            source_owners.insert(f.source.clone(), id);
        }
    }
    for (position, f) in facts.iter().enumerate() {
        let source = manifest.files.iter().find(|s| s.path == f.source);
        let module = source.map(|s| s.module.as_str()).unwrap_or(".");
        let scope = source.map(|s| s.scope.as_str()).unwrap_or("MAIN");
        match f.kind {
            FactKind::Declaration | FactKind::ExternalSymbol => {
                let mut metadata = f.attributes.clone();
                if let Some(span) = &f.span {
                    metadata.insert("fixture_span".into(), json!(span));
                }
                metadata.insert("fixture_signature".into(), json!(f.signature));
                let kind = attr(f, "element_kind", json!("CLASS"));
                let symbol = if f.signature.is_empty() {
                    &f.id
                } else {
                    &f.signature
                };
                output.push(row(
                    Table::Node,
                    vec![
                        json!(f.id),
                        json!(symbol),
                        json!("language"),
                        json!("java"),
                        json!("java-core"),
                        attr(f, "entity_kind", json!("type")),
                        kind.clone(),
                        json!(f.name),
                        kind,
                        json!(f.signature),
                        attr(f, "package", json!("")),
                        Value::Null,
                        json!(f.source),
                        Value::Null,
                        Value::Null,
                        Value::Null,
                        Value::Null,
                        Value::Null,
                        Value::Null,
                        json!(module),
                        json!(scope),
                        json!(serde_json::to_string(&metadata).expect("fixture metadata")),
                        Value::Null,
                        json!("java-core"),
                    ],
                ));
                if !f.parent.is_empty() && f.kind == FactKind::Declaration {
                    output.push(edge(
                        f,
                        &f.parent,
                        &f.id,
                        "CONTAINS",
                        module,
                        scope,
                        Value::Null,
                    ));
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
                output.push(edge(
                    f,
                    parent,
                    &f.target,
                    relation,
                    module,
                    scope,
                    Value::Null,
                ));
            }
            FactKind::CallSite => {
                let mut metadata = f.attributes.clone();
                if let Some(span) = &f.span {
                    metadata.insert("fixture_span".into(), json!(span));
                }
                output.push(row(
                    Table::UnresolvedCall,
                    vec![
                        json!(f.parent),
                        json!(f.source),
                        json!(1),
                        json!(1),
                        json!(1),
                        json!(2),
                        json!(position as i64),
                        json!(f.name),
                        attr(f, "receiver_static_type", Value::Null),
                        attr(f, "dispatch", Value::Null),
                        Value::Null,
                        json!(metadata),
                        json!("java-core"),
                        json!(module),
                        json!(scope),
                        json!(f.parent),
                    ],
                ));
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
                    target.cells[22] = Cell {
                        value: Some(cell::Value::Integer(position as i64)),
                    };
                    let mut metadata = site.attributes.clone();
                    if let Some(span) = &site.span {
                        metadata.insert("fixture_span".into(), json!(span));
                    }
                    target.cells[25] = Cell {
                        value: Some(cell::Value::Text(
                            serde_json::to_string(&metadata).expect("fixture metadata"),
                        )),
                    };
                    output.push(target);
                }
            }
            FactKind::Facet if f.relation == "java.annotation" => {
                let name = attr(f, "annotation", attr(f, "name", json!(f.name)));
                output.push(row(
                    Table::Annotation,
                    vec![
                        json!(f.parent),
                        name.clone(),
                        name,
                        json!(f.attributes),
                        json!("DECLARATION"),
                        Value::Null,
                        json!("java"),
                        json!("java-core"),
                        json!("java.annotation"),
                        json!("exact"),
                        json!(f.source),
                        Value::Null,
                        Value::Null,
                        Value::Null,
                        Value::Null,
                        Value::Null,
                        json!(module),
                        json!(scope),
                        json!(1),
                        json!(f.parent),
                        json!("java-core"),
                    ],
                ));
            }
            FactKind::Coverage => output.push(row(
                Table::Coverage,
                vec![
                    json!(f.source),
                    json!(module),
                    json!(scope),
                    json!("java"),
                    json!("java-core"),
                    json!("SEMANTIC"),
                    json!(f.status),
                    json!(0),
                    json!(0),
                    json!("[]"),
                    json!("{}"),
                    json!(0),
                ],
            )),
            FactKind::Diagnostic => output.push(row(
                Table::Diagnostic,
                vec![
                    attr(f, "severity", json!("warning")),
                    attr(f, "code", json!(f.name)),
                    json!("MOCK"),
                    json!(f.source),
                    json!(module),
                    json!(scope),
                    Value::Null,
                    json!("java"),
                    json!("java-core"),
                    Value::Null,
                    Value::Null,
                    Value::Null,
                    attr(f, "count", json!(1)),
                    attr(f, "sample", Value::Null),
                ],
            )),
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
) -> Row {
    row(
        Table::Edge,
        vec![
            json!(parent),
            json!(target),
            Value::Null,
            Value::Null,
            Value::Null,
            Value::Null,
            json!(relation.to_ascii_uppercase()),
            Value::Null,
            Value::Null,
            json!("java"),
            json!("java-core"),
            Value::Null,
            json!(if f.status == "resolved" {
                "EXTRACTED"
            } else {
                "INFERRED"
            }),
            Value::Null,
            Value::Null,
            json!(0),
            json!(f.source),
            Value::Null,
            json!(1),
            json!(1),
            json!(1),
            json!(2),
            json!(0),
            syntax,
            attr(f, "receiver_static_type", Value::Null),
            json!(f.attributes),
            json!(module),
            json!(scope),
            json!(1),
            json!(1),
            json!(parent),
            json!(target),
            Value::Null,
            json!("java-core"),
        ],
    )
}
