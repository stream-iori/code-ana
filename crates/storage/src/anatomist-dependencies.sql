-- Regenerable indexes retain the observation's host-assigned unit.
INSERT OR IGNORE INTO graph_file_dependencies(unit_pk,host_derived,source_provider_id,source_file,depends_on_provider_id,depends_on_file,world,target_world)
SELECT unit_pk,1,source_provider_id,(SELECT file_pk FROM source_files WHERE path=source_file),depends_on_provider_id,
 (SELECT file_pk FROM source_files WHERE path=depends_on_file),world,target_world FROM (
 SELECT g.unit_pk,sn.provider_id AS source_provider_id,sn.source_file,tn.provider_id AS depends_on_provider_id,tn.source_file AS depends_on_file,sn.world,tn.world AS target_world
 FROM edges e JOIN graph_edges g ON g.id=e.id JOIN nodes sn ON sn.id=e.source_id JOIN nodes tn ON tn.id=e.target_id
 WHERE e.is_external=0 AND coalesce(sn.source_file,'')<>'' AND coalesce(tn.source_file,'')<>'' AND (sn.source_file<>tn.source_file OR sn.world<>tn.world)
 UNION
 SELECT g.unit_pk,sn.provider_id,o.source_file,tn.provider_id,tn.source_file,o.world,tn.world
 FROM call_site_owners o JOIN graph_call_site_owners g USING(owner_pk) JOIN call_sites c USING(owner_pk)
 JOIN call_site_targets t ON t.call_site_pk=c.site_pk JOIN nodes sn ON sn.id=o.caller_id JOIN nodes tn ON tn.id=t.target_id
 WHERE coalesce(o.source_file,'')<>'' AND coalesce(tn.source_file,'')<>'' AND (o.source_file<>tn.source_file OR o.world<>tn.world)
 UNION
 SELECT g.unit_pk,a.provider_id,a.source_file,tn.provider_id,tn.source_file,a.world,tn.world
 FROM annotations a JOIN graph_annotations g ON g.id=a.id JOIN nodes tn ON tn.provider_id=a.provider_id AND tn.language=a.language AND tn.qualified_name=a.annotation_fqn
 WHERE coalesce(a.source_file,'')<>'' AND coalesce(tn.source_file,'')<>'' AND (a.source_file<>tn.source_file OR a.world<>tn.world) AND __VISIBLE_TARGET__
 UNION
 SELECT g.unit_pk,g.provider_id,g.source_file,tn.provider_id,tn.source_file,g.world,tn.world
 FROM annotation_meta_units g JOIN nodes tn ON tn.provider_id=g.provider_id AND tn.language=g.language AND tn.qualified_name=g.meta_annotation_fqn
 WHERE coalesce(g.source_file,'')<>'' AND coalesce(tn.source_file,'')<>'' AND (g.source_file<>tn.source_file OR g.world<>tn.world) AND __VISIBLE_TARGET__
);
