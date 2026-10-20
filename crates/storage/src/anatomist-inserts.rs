// Ported from Anatomist dff2e395 StagingSchema.
pub const NODE_INSERT: &str = r#"INSERT INTO stage_nodes(id, symbol_id, domain, language, provider_id, entity_kind, language_kind, label,
            kind, qualified_name, package, namespace, source_file, source_location, begin_line, begin_column,
            end_line, end_column, source_ordinal, module, scope, metadata, arity_key, producer_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id)
            DO UPDATE SET symbol_id=excluded.symbol_id, domain=excluded.domain, language=excluded.language,
            provider_id=excluded.provider_id, entity_kind=excluded.entity_kind, language_kind=excluded.language_kind,
            label=excluded.label, kind=excluded.kind, qualified_name=excluded.qualified_name,
            package=excluded.package, namespace=excluded.namespace, source_file=excluded.source_file,
            source_location=excluded.source_location, begin_line=excluded.begin_line,
            begin_column=excluded.begin_column, end_line=excluded.end_line, end_column=excluded.end_column,
            source_ordinal=excluded.source_ordinal, module=excluded.module, scope=excluded.scope,
            metadata=excluded.metadata, arity_key=excluded.arity_key, producer_id=excluded.producer_id"#;
pub const EDGE_INSERT: &str = r#"INSERT INTO stage_edges(source_ref, target_ref, external_target_fqn, external_target_symbol,
            external_target_language, external_target_provider_id, relation, semantic, mechanism, language,
            provider_id, call_kind, confidence, resolution, context, is_external, source_file, source_location,
            begin_line, begin_column, end_line, end_column, source_ordinal, syntax_target, receiver_static_type,
            metadata, source_module, source_scope, source_is_key, target_is_key, resolved_source, resolved_target,
            target_arity_key, producer_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"#;
pub const ANNOTATION_INSERT: &str = r#"INSERT INTO stage_annotations(node_ref, annotation_fqn, raw_name, attributes, target_kind, target_path,
            language, provider_id, mechanism, resolution_status, source_file, source_location, begin_line,
            begin_column, end_line, end_column, source_module, source_scope, node_is_key, resolved_node, producer_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"#;
pub const ANNOTATION_META_INSERT: &str = r#"INSERT INTO stage_annotation_meta(annotation_fqn, meta_annotation_fqn, raw_name, language, provider_id,
            mechanism, resolution_status, source_file, source_location, producer_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"#;
pub const DECLARATION_INSERT: &str = r#"INSERT OR REPLACE INTO stage_declarations(symbol_id, domain, language, provider_id, entity_kind,
            language_kind, qualified_name, label, kind, declaration_kind, type_kind, visibility, modifiers,
            declared_modifiers, implicit_modifiers, declaring_type, namespace, source_file, source_location,
            begin_line, begin_column, end_line, end_column, module, scope, nesting_depth, direct_member, synthetic,
            binding_resolved, producer_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"#;
