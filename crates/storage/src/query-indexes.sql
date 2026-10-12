-- Anatomist schema.sql uses directed source/target+relation indexes and a
-- partial declaration index. Fact IR uses parent/target and preserves worlds.
CREATE INDEX IF NOT EXISTS fact_kind ON facts(kind,relation,id);
CREATE INDEX IF NOT EXISTS fact_parent ON facts(parent,kind,relation,id);
CREATE INDEX IF NOT EXISTS fact_target ON facts(target,kind,relation,id);
CREATE INDEX IF NOT EXISTS fact_relation ON facts(relation,kind,id);
CREATE INDEX IF NOT EXISTS fact_declaration_identity
    ON facts(source,world,json_extract(body,'$.signature'),id)
    WHERE kind='declaration';
-- A syntax call site has zero or several static targets. Keep both directions
-- independently searchable, including unresolved sites with no targets.
CREATE INDEX IF NOT EXISTS fact_call_targets_site ON facts(parent,id)
    WHERE kind='call_target';
CREATE INDEX IF NOT EXISTS fact_call_targets_reverse ON facts(target,id)
    WHERE kind='call_target';
CREATE INDEX IF NOT EXISTS fact_call_sites_owner ON facts(parent,id)
    WHERE kind='call_site';
CREATE INDEX IF NOT EXISTS fact_unresolved_consumers ON facts(name,id,world,source)
    WHERE kind='call_site' AND json_extract(body,'$.status')='unresolved';
CREATE INDEX IF NOT EXISTS fact_annotation_meta_pair
    ON facts(json_extract(body,'$.attributes.annotation'),json_extract(body,'$.attributes.meta_annotation'),id)
    WHERE kind='facet' AND relation='java.annotation.meta';
