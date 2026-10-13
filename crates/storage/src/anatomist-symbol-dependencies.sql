-- Anatomist dff2e395 DataWriter compact dependency keys.
SELECT source_provider_id,source_file,target_value,dependency_kind FROM (
                  SELECT sn.provider_id source_provider_id,sn.source_file,e.target_id target_value,
                         'internal' dependency_kind
                  FROM edges e JOIN nodes sn ON sn.id=e.source_id
                  WHERE e.target_id IS NOT NULL AND sn.source_file IS NOT NULL
                  UNION
                  SELECT caller.provider_id,owner.source_file,target.target_id,'internal'
                  FROM call_site_targets target
                  JOIN call_sites site ON site.site_pk=target.call_site_pk
                  JOIN call_site_owners owner ON owner.owner_pk=site.owner_pk
                  JOIN nodes caller ON caller.id=owner.caller_id
                  WHERE target.target_id IS NOT NULL
                  UNION
                  SELECT annotation.provider_id,annotation.source_file,target.id,'annotation'
                  FROM annotations annotation JOIN nodes target
                    ON target.provider_id=annotation.provider_id
                   AND target.qualified_name=annotation.annotation_fqn
                  WHERE annotation.source_file IS NOT NULL
                  UNION
                  SELECT relation.provider_id,relation.source_file,target.id,'annotation-meta'
                  FROM annotation_meta_owned relation JOIN nodes target
                    ON target.provider_id=relation.provider_id
                   AND target.qualified_name=relation.meta_annotation_fqn
                  WHERE relation.source_file IS NOT NULL
                  UNION
                  SELECT sn.provider_id,sn.source_file,e.external_target_fqn,'external'
                  FROM edges e JOIN nodes sn ON sn.id=e.source_id
                  WHERE e.external_target_fqn IS NOT NULL AND sn.source_file IS NOT NULL
                  UNION
                  SELECT caller.provider_id,owner.source_file,target.external_target_fqn,'external'
                  FROM call_site_targets target
                  JOIN call_sites site ON site.site_pk=target.call_site_pk
                  JOIN call_site_owners owner ON owner.owner_pk=site.owner_pk
                  JOIN nodes caller ON caller.id=owner.caller_id
                  WHERE target.external_target_fqn IS NOT NULL
                );
