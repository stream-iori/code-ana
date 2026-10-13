// Ported from Anatomist dff2e395 FactIdentityPreparer.
pub const NODE_HASH: &str=r#"SELECT n.seq,n.id,n.symbol_id,n.domain,n.language,n.provider_id,n.entity_kind,
                       n.language_kind,n.label,n.kind,n.qualified_name,n.package,n.namespace,
                       n.source_file,n.source_location,n.begin_line,n.begin_column,n.end_line,
                       n.end_column,n.source_ordinal,n.module,n.scope,n.metadata,n.producer_id,
                       d.declaration_kind,d.type_kind,d.visibility,d.modifiers,d.declared_modifiers,
                       d.implicit_modifiers,d.declaring_type,d.namespace,d.source_location,d.begin_line,
                       d.begin_column,d.end_line,d.end_column,d.nesting_depth,d.direct_member,d.synthetic,
                       d.binding_resolved
                FROM stage_nodes n LEFT JOIN stage_declarations d
                  ON d.provider_id=n.provider_id AND d.symbol_id=n.symbol_id
                 AND d.module=n.module AND d.scope=n.scope AND d.source_file=n.source_file
                 AND d.producer_id=n.producer_id
                WHERE n.seq>? AND n.fact_hash IS NULL ORDER BY n.seq LIMIT ?"#;
pub const EDGE_HASH: &str=r#"SELECT seq,resolved_source,resolved_target,external_target_fqn,external_target_symbol,
                       external_target_language,external_target_provider_id,relation,semantic,mechanism,
                       language,provider_id,call_kind,confidence,resolution,context,is_external,source_file,
                       source_location,begin_line,begin_column,end_line,end_column,source_ordinal,
                       syntax_target,receiver_static_type,metadata,producer_id
                FROM stage_edges
                WHERE seq>? AND fact_hash IS NULL AND relation<>'CALLS' ORDER BY seq LIMIT ?"#;
pub const ANNOTATION_HASH: &str=r#"SELECT seq,resolved_node,annotation_fqn,raw_name,attributes,target_kind,target_path,
                       language,provider_id,mechanism,resolution_status,source_file,source_location,
                       begin_line,begin_column,end_line,end_column,producer_id
                FROM stage_annotations
                WHERE seq>? AND fact_hash IS NULL ORDER BY seq LIMIT ?"#;
