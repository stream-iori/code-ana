-- Anatomist dff2e395 schema 32; semantic graph layout.
-- Anatomist graph, node-name FTS5 and Java annotations.

CREATE TABLE nodes (
    id TEXT PRIMARY KEY,
    symbol_id TEXT NOT NULL,
    domain TEXT NOT NULL DEFAULT 'language',
    language TEXT,
    provider_id TEXT NOT NULL DEFAULT 'java-core',
    entity_kind TEXT NOT NULL DEFAULT 'entity',
    language_kind TEXT,
    label TEXT NOT NULL,
    kind TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    package TEXT,
    namespace TEXT,
    source_file TEXT NOT NULL,
    source_location TEXT,
    begin_line INTEGER,
    begin_column INTEGER,
    end_line INTEGER,
    end_column INTEGER,
    source_ordinal INTEGER,
    module TEXT NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('MAIN','TEST','GENERATED')),
    metadata TEXT,
    producer_id TEXT NOT NULL DEFAULT 'java-core',
    world TEXT NOT NULL DEFAULT '',
    declaration_kind TEXT,
    type_kind TEXT,
    visibility TEXT,
    modifiers TEXT,
    declared_modifiers TEXT,
    implicit_modifiers TEXT,
    declaring_type TEXT,
    declaration_namespace TEXT,
    declaration_source_location TEXT,
    declaration_begin_line INTEGER,
    declaration_begin_column INTEGER,
    declaration_end_line INTEGER,
    declaration_end_column INTEGER,
    nesting_depth INTEGER,
    direct_member INTEGER,
    synthetic INTEGER,
    binding_resolved INTEGER,
    fact_hash BLOB CHECK (fact_hash IS NULL OR length(fact_hash) = 16),
    CHECK (
        (begin_line IS NULL AND begin_column IS NULL AND end_line IS NULL AND end_column IS NULL)
        OR
        (begin_line > 0 AND begin_column > 0 AND end_line > 0 AND end_column > 0
         AND (end_line > begin_line OR (end_line = begin_line AND end_column >= begin_column)))
    ),
    CHECK (
        (declaration_kind IS NULL
         AND type_kind IS NULL AND visibility IS NULL
         AND modifiers IS NULL AND declared_modifiers IS NULL AND implicit_modifiers IS NULL
         AND declaring_type IS NULL AND declaration_namespace IS NULL AND declaration_source_location IS NULL
         AND declaration_begin_line IS NULL AND declaration_begin_column IS NULL
         AND declaration_end_line IS NULL AND declaration_end_column IS NULL
         AND nesting_depth IS NULL AND direct_member IS NULL
         AND synthetic IS NULL AND binding_resolved IS NULL)
        OR
        ((declaration_kind IN ('type','method','constructor','field','enum_constant','record_component','initializer') OR language='rust' AND declaration_kind IN ('function','struct','enum','trait','union','type_alias','constant','static','module','variant','macro','impl') OR language='python' AND declaration_kind IN ('module','class','function','variable','parameter','import'))
         AND (type_kind IS NULL OR type_kind IN ('class','interface','enum','record','annotation'))
         AND (visibility IN ('public','protected','private','package') OR language='rust' AND visibility IN ('crate','restricted'))
         AND modifiers IS NOT NULL AND declared_modifiers IS NOT NULL AND implicit_modifiers IS NOT NULL
         AND nesting_depth IS NOT NULL
         AND direct_member IN (0,1) AND synthetic IN (0,1) AND binding_resolved IN (0,1)
         AND ((declaration_begin_line IS NULL AND declaration_begin_column IS NULL
               AND declaration_end_line IS NULL AND declaration_end_column IS NULL)
              OR (declaration_begin_line > 0 AND declaration_begin_column > 0
                  AND declaration_end_line > 0 AND declaration_end_column > 0
                  AND (declaration_end_line > declaration_begin_line
                       OR (declaration_end_line = declaration_begin_line
                           AND declaration_end_column >= declaration_begin_column)))))
    )
);

CREATE INDEX idx_nodes_kind ON nodes(kind);
CREATE INDEX idx_nodes_symbol_identity ON nodes(symbol_id,module,scope,kind);
CREATE INDEX idx_nodes_qualified_name ON nodes(qualified_name);
CREATE INDEX idx_nodes_package ON nodes(package);
CREATE INDEX idx_nodes_source_file ON nodes(source_file,module,scope);
CREATE INDEX idx_nodes_module ON nodes(module);
CREATE INDEX idx_nodes_scope ON nodes(scope);
CREATE INDEX idx_nodes_producer_file ON nodes(producer_id, source_file);
CREATE INDEX idx_nodes_declaration_filters ON nodes(declaration_kind,visibility,synthetic)
    WHERE declaration_kind IS NOT NULL;
CREATE INDEX idx_nodes_declaring_type ON nodes(declaring_type)
    WHERE declaring_type IS NOT NULL;

CREATE TABLE edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id TEXT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    target_id TEXT REFERENCES nodes(id) ON DELETE CASCADE,
    external_target_fqn TEXT,
    external_target_symbol TEXT,
    external_target_language TEXT,
    external_target_provider_id TEXT,
    relation TEXT NOT NULL,
    semantic TEXT,
    mechanism TEXT,
    language TEXT,
    provider_id TEXT NOT NULL DEFAULT 'java-core',
    call_kind TEXT,
    confidence TEXT NOT NULL DEFAULT 'EXTRACTED',
    resolution TEXT,
    context TEXT,
    is_external INTEGER NOT NULL DEFAULT 0,
    source_file TEXT,
    source_location TEXT,
    begin_line INTEGER,
    begin_column INTEGER,
    end_line INTEGER,
    end_column INTEGER,
    source_ordinal INTEGER,
    syntax_target TEXT,
    receiver_static_type TEXT,
    metadata TEXT,
    producer_id TEXT NOT NULL DEFAULT 'java-core',
    fact_hash BLOB CHECK (fact_hash IS NULL OR length(fact_hash) = 16),
    fact_ordinal INTEGER NOT NULL DEFAULT 0,
    CHECK (
        (is_external = 0 AND target_id IS NOT NULL AND external_target_fqn IS NULL)
        OR
        (is_external = 1 AND target_id IS NULL AND external_target_fqn IS NOT NULL)
    )
);

CREATE INDEX idx_edges_source_file ON edges(source_file);
CREATE INDEX idx_edges_external_target_fqn ON edges(external_target_fqn);
CREATE INDEX idx_edges_target_relation ON edges(target_id, relation);
CREATE INDEX idx_edges_relation_external_target ON edges(relation, is_external, target_id);
CREATE INDEX idx_edges_relation_external_fqn ON edges(relation, is_external, external_target_fqn);
CREATE INDEX idx_edges_external_resolution ON edges(is_external, resolution);
CREATE INDEX idx_edges_source_relation_external ON edges(source_id, relation, is_external);
CREATE INDEX idx_edges_producer_file ON edges(producer_id, source_file);
-- Canonical source call sites. Targets are separate because one syntax site may
-- have several static resolution candidates.
CREATE TABLE call_site_owners (
    owner_pk INTEGER PRIMARY KEY,
    caller_id TEXT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    source_file TEXT NOT NULL,
    UNIQUE (caller_id, source_file)
);

CREATE INDEX idx_call_site_owners_source ON call_site_owners(source_file, caller_id);

CREATE TABLE call_sites (
    site_pk INTEGER PRIMARY KEY,
    stable_hash BLOB NOT NULL CHECK (length(stable_hash) = 32),
    owner_pk INTEGER NOT NULL REFERENCES call_site_owners(owner_pk) ON DELETE CASCADE,
    begin_line INTEGER NOT NULL,
    begin_column INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    end_column INTEGER NOT NULL,
    ordinal INTEGER NOT NULL DEFAULT 0,
    context TEXT,
    syntax_target TEXT,
    receiver_static_type TEXT,
    dispatch_kind TEXT,
    metadata TEXT,
    origin TEXT NOT NULL,
    resolution_status TEXT NOT NULL,
    producer_id TEXT NOT NULL,
    language TEXT,
    provider_id TEXT NOT NULL DEFAULT 'java-core'
);

CREATE INDEX idx_call_sites_caller_order ON call_sites(
    owner_pk,begin_line,begin_column,ordinal
);
CREATE TABLE call_site_targets (
    call_site_pk INTEGER NOT NULL REFERENCES call_sites(site_pk) ON DELETE CASCADE,
    target_id TEXT REFERENCES nodes(id) ON DELETE CASCADE,
    external_target_fqn TEXT,
    external_target_symbol TEXT,
    external_target_language TEXT,
    external_target_provider_id TEXT,
    resolution_status TEXT NOT NULL,
    confidence TEXT,
    producer_id TEXT NOT NULL,
    CHECK ((target_id IS NOT NULL) <> (external_target_fqn IS NOT NULL))
);

CREATE INDEX idx_call_site_targets_internal ON call_site_targets(target_id)
    WHERE target_id IS NOT NULL;
CREATE INDEX idx_call_site_targets_external ON call_site_targets(external_target_fqn)
    WHERE external_target_fqn IS NOT NULL;
CREATE INDEX idx_call_site_targets_site ON call_site_targets(call_site_pk);
CREATE TABLE annotations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_id TEXT NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    annotation_fqn TEXT,
    raw_name TEXT NOT NULL,
    attributes TEXT,
    target_kind TEXT NOT NULL,
    target_path TEXT,
    language TEXT NOT NULL,
    provider_id TEXT NOT NULL DEFAULT 'java-core',
    mechanism TEXT NOT NULL,
    resolution_status TEXT NOT NULL CHECK (resolution_status IN ('exact','heuristic','unresolved')),
    source_file TEXT,
    source_location TEXT,
    begin_line INTEGER,
    begin_column INTEGER,
    end_line INTEGER,
    end_column INTEGER,
    producer_id TEXT NOT NULL DEFAULT 'java-core',
    fact_hash BLOB CHECK (fact_hash IS NULL OR length(fact_hash) = 16),
    fact_ordinal INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_annotations_node_id ON annotations(node_id);
CREATE INDEX idx_annotations_fqn ON annotations(annotation_fqn);
CREATE INDEX idx_annotations_producer_file ON annotations(producer_id, source_file);
CREATE TABLE annotation_meta_relations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    annotation_fqn TEXT NOT NULL,
    meta_annotation_fqn TEXT,
    raw_name TEXT NOT NULL,
    language TEXT NOT NULL,
    provider_id TEXT NOT NULL DEFAULT 'java-core',
    mechanism TEXT NOT NULL,
    resolution_status TEXT NOT NULL CHECK (resolution_status IN ('exact','heuristic','unresolved')),
    source_file TEXT,
    source_location TEXT,
    producer_id TEXT NOT NULL DEFAULT 'java-core'
);

CREATE INDEX idx_annotation_meta_source ON annotation_meta_relations(annotation_fqn);
CREATE INDEX idx_annotation_meta_target ON annotation_meta_relations(meta_annotation_fqn);
CREATE INDEX idx_annotation_meta_producer_file ON annotation_meta_relations(producer_id, source_file);
CREATE UNIQUE INDEX idx_annotation_meta_identity ON annotation_meta_relations(
    provider_id,annotation_fqn,COALESCE(meta_annotation_fqn,''),raw_name,language,mechanism,
    COALESCE(source_file,''),producer_id
);

CREATE VIRTUAL TABLE node_names USING fts5(
    qualified_name,
    label,
    content='nodes',
    content_rowid='rowid'
);

CREATE TRIGGER nodes_ai AFTER INSERT ON nodes BEGIN
    INSERT INTO node_names(rowid, qualified_name, label)
    VALUES (new.rowid, new.qualified_name, new.label);
END;

CREATE TRIGGER nodes_ad AFTER DELETE ON nodes BEGIN
    INSERT INTO node_names(node_names, rowid, qualified_name, label)
    VALUES ('delete', old.rowid, old.qualified_name, old.label);
END;

CREATE TRIGGER nodes_au AFTER UPDATE OF qualified_name,label ON nodes
WHEN old.qualified_name IS NOT new.qualified_name
  OR old.label IS NOT new.label
BEGIN
    INSERT INTO node_names(node_names, rowid, qualified_name, label)
    VALUES ('delete', old.rowid, old.qualified_name, old.label);
    INSERT INTO node_names(rowid, qualified_name, label)
    VALUES (new.rowid, new.qualified_name, new.label);
END;

CREATE TABLE file_cache (
    provider_id TEXT NOT NULL DEFAULT 'java-core',
    source_file TEXT NOT NULL,
    hash TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    last_indexed TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    node_count INTEGER NOT NULL DEFAULT 0,
    edge_count INTEGER NOT NULL DEFAULT 0,
    file_size INTEGER NOT NULL DEFAULT -1,
    file_mtime_ns INTEGER NOT NULL DEFAULT -1,
    contract_hash TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (provider_id, source_file)
);

CREATE INDEX idx_file_cache_schema_version ON file_cache(schema_version);

CREATE TABLE project_meta (
    key TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE index_providers (
    provider_id TEXT PRIMARY KEY,
    language TEXT NOT NULL,
    provider_version TEXT NOT NULL,
    operations TEXT NOT NULL,
    limitations TEXT NOT NULL,
    profile_hash TEXT NOT NULL
);

CREATE INDEX idx_index_providers_language ON index_providers(language);

CREATE TABLE index_diagnostics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    severity TEXT NOT NULL CHECK (severity IN ('info','warning','error')),
    code TEXT NOT NULL,
    phase TEXT NOT NULL,
    source_file TEXT,
    module TEXT,
    scope TEXT,
    symbol TEXT,
    language TEXT,
    provider_id TEXT,
    provider_reason TEXT,
    reason_code TEXT,
    sample_details TEXT,
    occurrence_count INTEGER NOT NULL,
    sample TEXT,
    world TEXT NOT NULL DEFAULT ''
);

CREATE INDEX idx_diagnostics_severity ON index_diagnostics(severity);
CREATE INDEX idx_diagnostics_code ON index_diagnostics(code);
CREATE INDEX idx_diagnostics_source_file ON index_diagnostics(source_file);

CREATE TABLE analysis_coverage (
    source_file TEXT NOT NULL,
    module TEXT NOT NULL,
    scope TEXT NOT NULL,
    language TEXT,
    provider_id TEXT NOT NULL DEFAULT 'java-core',
    capability TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('complete','partial','failed')),
    occurrences INTEGER NOT NULL DEFAULT 0,
    groups_count INTEGER NOT NULL DEFAULT 0,
    codes TEXT NOT NULL,
    code_counts TEXT NOT NULL,
    details_truncated INTEGER NOT NULL DEFAULT 0,
    world TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (provider_id, source_file, module, scope, capability, world)
);

CREATE INDEX idx_analysis_coverage_capability
    ON analysis_coverage(capability, module, scope);
CREATE INDEX idx_analysis_coverage_source_file
    ON analysis_coverage(source_file);

CREATE TABLE file_dependencies (
    source_provider_id TEXT NOT NULL DEFAULT 'java-core',
    source_file TEXT NOT NULL,
    depends_on_provider_id TEXT NOT NULL DEFAULT 'java-core',
    depends_on_file TEXT NOT NULL,
    PRIMARY KEY (source_provider_id, source_file, depends_on_provider_id, depends_on_file)
);

CREATE INDEX idx_file_deps_target ON file_dependencies(depends_on_file);

-- Compact symbol-level reverse dependencies used by incremental invalidation.
-- target_key is a 64-bit SHA-256 prefix. Collisions only over-invalidate.
CREATE TABLE symbol_dependencies (
    target_key BLOB NOT NULL CHECK (length(target_key) = 8),
    source_file TEXT NOT NULL,
    PRIMARY KEY (target_key, source_file)
) WITHOUT ROWID;

-- Persistent Rust invalidation inputs; independent of Java's derived dependencies.
CREATE TABLE rust_source_states(source_file TEXT NOT NULL,world TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(source_file,world));
CREATE TABLE rust_analysis_dependencies(source_file TEXT NOT NULL,world TEXT NOT NULL,kind TEXT NOT NULL,target_world TEXT NOT NULL,target_key TEXT NOT NULL,PRIMARY KEY(source_file,world,kind,target_world,target_key));
