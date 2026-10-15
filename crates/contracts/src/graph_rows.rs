//! Named Rust producers for the frozen GRA1 column contract. Java rows remain raw protobuf.
use crate::graph::{Cell, Row, Table, cell};
pub trait GraphRow {
    fn into_row(self, world: &str) -> Row;
}
trait Column {
    fn into_cell(self) -> Cell;
}
impl Column for Option<String> {
    fn into_cell(self) -> Cell {
        Cell {
            value: self.map(cell::Value::Text),
        }
    }
}
impl Column for Option<i64> {
    fn into_cell(self) -> Cell {
        Cell {
            value: self.map(cell::Value::Integer),
        }
    }
}
macro_rules! graph_row {
 ($name:ident, $table:ident, {$($field:ident: $ty:ty),* $(,)?}) => {
  #[derive(Debug, Clone, Default)]
  pub struct $name {$(pub $field: $ty,)*}
  impl GraphRow for $name {fn into_row(self, world:&str)->Row {Row {table:Table::$table as i32,world:world.into(),cells:vec![$(self.$field.into_cell(),)*]}}}
 };
}
graph_row!(
    NodeRow, Node, {
        id: Option<String>,
        symbol_id: Option<String>,
        domain: Option<String>,
        language: Option<String>,
        provider_id: Option<String>,
        entity_kind: Option<String>,
        language_kind: Option<String>,
        label: Option<String>,
        kind: Option<String>,
        qualified_name: Option<String>,
        package: Option<String>,
        namespace: Option<String>,
        source_file: Option<String>,
        source_location: Option<String>,
        begin_line: Option<i64>,
        begin_column: Option<i64>,
        end_line: Option<i64>,
        end_column: Option<i64>,
        source_ordinal: Option<i64>,
        module: Option<String>,
        scope: Option<String>,
        metadata: Option<String>,
        arity_key: Option<String>,
        producer_id: Option<String>,
    }
);
graph_row!(
    EdgeRow, Edge, {
        source_ref: Option<String>,
        target_ref: Option<String>,
        external_target_fqn: Option<String>,
        external_target_symbol: Option<String>,
        external_target_language: Option<String>,
        external_target_provider_id: Option<String>,
        relation: Option<String>,
        semantic: Option<String>,
        mechanism: Option<String>,
        language: Option<String>,
        provider_id: Option<String>,
        call_kind: Option<String>,
        confidence: Option<String>,
        resolution: Option<String>,
        context: Option<String>,
        is_external: Option<i64>,
        source_file: Option<String>,
        source_location: Option<String>,
        begin_line: Option<i64>,
        begin_column: Option<i64>,
        end_line: Option<i64>,
        end_column: Option<i64>,
        source_ordinal: Option<i64>,
        syntax_target: Option<String>,
        receiver_static_type: Option<String>,
        metadata: Option<String>,
        source_module: Option<String>,
        source_scope: Option<String>,
        source_is_key: Option<i64>,
        target_is_key: Option<i64>,
        resolved_source: Option<String>,
        resolved_target: Option<String>,
        target_arity_key: Option<String>,
        producer_id: Option<String>,
    }
);
graph_row!(
    DeclarationRow, Declaration, {
        symbol_id: Option<String>,
        domain: Option<String>,
        language: Option<String>,
        provider_id: Option<String>,
        entity_kind: Option<String>,
        language_kind: Option<String>,
        qualified_name: Option<String>,
        label: Option<String>,
        kind: Option<String>,
        declaration_kind: Option<String>,
        type_kind: Option<String>,
        visibility: Option<String>,
        modifiers: Option<String>,
        declared_modifiers: Option<String>,
        implicit_modifiers: Option<String>,
        declaring_type: Option<String>,
        namespace: Option<String>,
        source_file: Option<String>,
        source_location: Option<String>,
        begin_line: Option<i64>,
        begin_column: Option<i64>,
        end_line: Option<i64>,
        end_column: Option<i64>,
        module: Option<String>,
        scope: Option<String>,
        nesting_depth: Option<i64>,
        direct_member: Option<i64>,
        synthetic: Option<i64>,
        binding_resolved: Option<i64>,
        producer_id: Option<String>,
    }
);
graph_row!(
    AnnotationRow, Annotation, {
        node_ref: Option<String>,
        annotation_fqn: Option<String>,
        raw_name: Option<String>,
        attributes: Option<String>,
        target_kind: Option<String>,
        target_path: Option<String>,
        language: Option<String>,
        provider_id: Option<String>,
        mechanism: Option<String>,
        resolution_status: Option<String>,
        source_file: Option<String>,
        source_location: Option<String>,
        begin_line: Option<i64>,
        begin_column: Option<i64>,
        end_line: Option<i64>,
        end_column: Option<i64>,
        source_module: Option<String>,
        source_scope: Option<String>,
        node_is_key: Option<i64>,
        resolved_node: Option<String>,
        producer_id: Option<String>,
    }
);
graph_row!(
    UnresolvedCallRow, UnresolvedCall, {
        source_ref: Option<String>,
        source_file: Option<String>,
        begin_line: Option<i64>,
        begin_column: Option<i64>,
        end_line: Option<i64>,
        end_column: Option<i64>,
        source_ordinal: Option<i64>,
        syntax_target: Option<String>,
        receiver_static_type: Option<String>,
        call_kind: Option<String>,
        context: Option<String>,
        metadata: Option<String>,
        producer_id: Option<String>,
        source_module: Option<String>,
        source_scope: Option<String>,
        resolved_source: Option<String>,
    }
);
graph_row!(
    CoverageRow, Coverage, {
        source_file: Option<String>,
        module: Option<String>,
        scope: Option<String>,
        language: Option<String>,
        provider_id: Option<String>,
        capability: Option<String>,
        status: Option<String>,
        occurrences: Option<i64>,
        groups_count: Option<i64>,
        codes: Option<String>,
        code_counts: Option<String>,
        details_truncated: Option<i64>,
    }
);
graph_row!(
    DiagnosticRow, Diagnostic, {
        severity: Option<String>,
        code: Option<String>,
        phase: Option<String>,
        source_file: Option<String>,
        module: Option<String>,
        scope: Option<String>,
        symbol: Option<String>,
        language: Option<String>,
        provider_id: Option<String>,
        provider_reason: Option<String>,
        reason_code: Option<String>,
        sample_details: Option<String>,
        occurrence_count: Option<i64>,
        sample: Option<String>,
    }
);
graph_row!(
    SourceStateRow, SourceState, {
        source_file: Option<String>,
        world: Option<String>,
        state: Option<String>,
    }
);
graph_row!(
    AnalysisDependencyRow, AnalysisDependency, {
        source_file: Option<String>,
        world: Option<String>,
        kind: Option<String>,
        target_world: Option<String>,
        target_key: Option<String>,
    }
);
graph_row!(
    ProviderRow, Provider, {
        provider_id: Option<String>,
        language: Option<String>,
        provider_version: Option<String>,
        operations: Option<String>,
        limitations: Option<String>,
        profile_hash: Option<String>,
    }
);
graph_row!(
    ProjectMetaRow, ProjectMeta, {
        key: Option<String>,
        value: Option<String>,
    }
);

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;
    use serde_json::{Value, json};

    #[test]
    fn named_node_matches_the_frozen_wire_columns_and_preserves_raw_metadata() {
        let metadata = "{\"z\":2, \"a\":1}";
        let row = NodeRow {
            id: Some("节点😀".into()),
            symbol_id: Some(String::new()),
            domain: Some("language".into()),
            language: Some("rust".into()),
            provider_id: Some("rust-core".into()),
            entity_kind: Some("declaration".into()),
            language_kind: Some("function".into()),
            label: Some("answer".into()),
            kind: Some("function".into()),
            qualified_name: Some("api::answer".into()),
            package: None,
            namespace: None,
            source_file: Some("src/lib.rs".into()),
            source_location: Some("src/lib.rs:1:2:3:4".into()),
            begin_line: Some(1),
            begin_column: Some(2),
            end_line: Some(3),
            end_column: Some(4),
            source_ordinal: Some(i64::MAX),
            module: Some("app".into()),
            scope: Some("MAIN".into()),
            metadata: Some(metadata.into()),
            arity_key: None,
            producer_id: Some("rust-core".into()),
        }
        .into_row("world");
        // Independent legacy fixture encoder: column order predates the named API.
        let mut legacy = crate::fixture_graph::row(
            Table::Node,
            vec![
                json!("节点😀"),
                json!(""),
                json!("language"),
                json!("rust"),
                json!("rust-core"),
                json!("declaration"),
                json!("function"),
                json!("answer"),
                json!("function"),
                json!("api::answer"),
                Value::Null,
                Value::Null,
                json!("src/lib.rs"),
                json!("src/lib.rs:1:2:3:4"),
                json!(1),
                json!(2),
                json!(3),
                json!(4),
                json!(i64::MAX),
                json!("app"),
                json!("MAIN"),
                json!(metadata),
                Value::Null,
                json!("rust-core"),
            ],
        );
        legacy.world = "world".into();
        assert_eq!(row.encode_to_vec(), legacy.encode_to_vec());
        assert_eq!(row.cells[1].value, Some(cell::Value::Text(String::new())));
        assert_eq!(row.cells[10].value, None);
    }
}
