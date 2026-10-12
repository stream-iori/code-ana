-- Same recorded-edge/annotation joins as Anatomist DataWriter; no source inference.
DELETE FROM file_dependencies;
INSERT OR IGNORE INTO file_dependencies
SELECT json_array(source_world,source_file,target_world,target_file),
       source_world,source_file,target_world,target_file FROM (
    SELECT e.world source_world,e.source source_file,t.world target_world,t.source target_file
    FROM facts e JOIN facts t ON t.id=e.target
    WHERE e.kind IN ('dependency','reference','relation','call_target')
      AND t.kind IN ('declaration','facet')
    UNION
    SELECT a.world,a.source,t.world,t.source
    FROM facts a JOIN declaration_projection p ON p.signature=
        CASE a.relation WHEN 'java.annotation' THEN json_extract(a.body,'$.attributes.annotation')
                        ELSE json_extract(a.body,'$.attributes.meta_annotation') END
    JOIN facts t ON t.id=p.id
    WHERE a.kind='facet' AND a.relation IN ('java.annotation','java.annotation.meta')
      AND t.kind='declaration'
      AND json_extract(a.body,'$.attributes.provider_id')=
          json_extract(t.body,'$.attributes.provider_id')
) WHERE source_file<>'' AND target_file<>'' AND source_file<>target_file;
