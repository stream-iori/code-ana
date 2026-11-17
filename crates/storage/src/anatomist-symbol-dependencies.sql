-- Target identities feed conservative invalidation, never semantic completion.
SELECT source_provider_id,source_file,target_value,dependency_kind,world,unit_pk FROM (
 SELECT sn.provider_id AS source_provider_id,sn.source_file,e.target_id AS target_value,'internal' AS dependency_kind,sn.world,g.unit_pk
 FROM edges e JOIN graph_edges g ON g.id=e.id JOIN nodes sn ON sn.id=e.source_id WHERE e.target_id IS NOT NULL AND coalesce(sn.source_file,'')<>''
 UNION
 SELECT sn.provider_id,o.source_file,t.target_id,'internal',o.world,g.unit_pk
 FROM call_site_targets t JOIN call_sites c ON c.site_pk=t.call_site_pk JOIN call_site_owners o USING(owner_pk)
 JOIN graph_call_site_owners g USING(owner_pk) JOIN nodes sn ON sn.id=o.caller_id WHERE t.target_id IS NOT NULL AND coalesce(o.source_file,'')<>''
 UNION
 SELECT a.provider_id,a.source_file,tn.id,'annotation',a.world,g.unit_pk
 FROM annotations a JOIN graph_annotations g ON g.id=a.id JOIN nodes tn ON tn.provider_id=a.provider_id AND tn.language=a.language AND tn.qualified_name=a.annotation_fqn
 WHERE coalesce(a.source_file,'')<>'' AND __VISIBLE_TARGET__
 UNION
 SELECT g.provider_id,g.source_file,tn.id,'annotation-meta',g.world,g.unit_pk
 FROM annotation_meta_units g JOIN nodes tn ON tn.provider_id=g.provider_id AND tn.language=g.language AND tn.qualified_name=g.meta_annotation_fqn
 WHERE coalesce(g.source_file,'')<>'' AND __VISIBLE_TARGET__
 UNION
 SELECT sn.provider_id,sn.source_file,e.external_target_fqn,'external',sn.world,g.unit_pk
 FROM edges e JOIN graph_edges g ON g.id=e.id JOIN nodes sn ON sn.id=e.source_id WHERE e.external_target_fqn IS NOT NULL AND coalesce(sn.source_file,'')<>''
 UNION
 SELECT sn.provider_id,o.source_file,t.external_target_fqn,'external',o.world,g.unit_pk
 FROM call_site_targets t JOIN call_sites c ON c.site_pk=t.call_site_pk JOIN call_site_owners o USING(owner_pk)
 JOIN graph_call_site_owners g USING(owner_pk) JOIN nodes sn ON sn.id=o.caller_id WHERE t.external_target_fqn IS NOT NULL AND coalesce(o.source_file,'')<>''
);
