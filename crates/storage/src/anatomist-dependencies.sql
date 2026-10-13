-- Anatomist dff2e395 DataWriter reverse file dependencies.
INSERT OR IGNORE INTO file_dependencies(source_provider_id, source_file,
                                                     depends_on_provider_id, depends_on_file)
            SELECT DISTINCT sn.provider_id, sn.source_file, tn.provider_id, tn.source_file
            FROM edges e
            JOIN nodes sn ON e.source_id = sn.id
            JOIN nodes tn ON e.target_id = tn.id
            WHERE e.is_external=0
            AND sn.source_file IS NOT NULL AND tn.source_file IS NOT NULL
            AND sn.source_file <> tn.source_file
            UNION
            SELECT DISTINCT a.provider_id, a.source_file, tn.provider_id, tn.source_file
            FROM annotations a
            JOIN nodes tn ON tn.provider_id = a.provider_id
                         AND tn.qualified_name = a.annotation_fqn
            WHERE a.source_file IS NOT NULL AND tn.source_file IS NOT NULL
            AND a.source_file <> tn.source_file
            UNION
            SELECT DISTINCT am.provider_id, am.source_file, tn.provider_id, tn.source_file
            FROM annotation_meta_relations am
            JOIN nodes tn ON tn.provider_id = am.provider_id
                         AND tn.qualified_name = am.meta_annotation_fqn
            WHERE am.source_file IS NOT NULL AND tn.source_file IS NOT NULL
            AND am.source_file <> tn.source_file;
