-- Authoritative Fact IR rows; language payloads remain lossless in body.
-- Ingestion needs only identity, owner replacement and source lookup indexes.
CREATE TABLE IF NOT EXISTS identity(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS facts(
    id TEXT PRIMARY KEY, kind TEXT NOT NULL, owner TEXT NOT NULL,
    parent TEXT NOT NULL, target TEXT NOT NULL, relation TEXT NOT NULL,
    name TEXT NOT NULL, source TEXT NOT NULL, world TEXT NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS fact_owner ON facts(owner);
CREATE INDEX IF NOT EXISTS fact_source ON facts(source,world);
-- Captured ownership metadata; refreshed atomically with every candidate.
CREATE TABLE IF NOT EXISTS source_units(
    path TEXT NOT NULL, world TEXT NOT NULL, module TEXT NOT NULL, scope TEXT NOT NULL,
    hash TEXT NOT NULL, auxiliary INTEGER NOT NULL, PRIMARY KEY(path,world)
);
CREATE INDEX IF NOT EXISTS source_units_scope ON source_units(module,scope,world,path);
-- Rebuildable recorded file dependency projection; world preserves MAIN/TEST identity.
CREATE TABLE IF NOT EXISTS file_dependencies(
    id TEXT PRIMARY KEY, source_world TEXT NOT NULL, source_file TEXT NOT NULL,
    target_world TEXT NOT NULL, target_file TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS file_dependencies_reverse ON file_dependencies(target_world,target_file,id);
-- Rebuildable symbol selectors, maintained in the same transaction as authoritative facts.
CREATE TABLE IF NOT EXISTS declaration_projection(
    id TEXT PRIMARY KEY, signature TEXT NOT NULL, language_kind TEXT NOT NULL,
    entity_kind TEXT NOT NULL, package TEXT NOT NULL, visibility TEXT NOT NULL,
    synthetic INTEGER NOT NULL, parent TEXT NOT NULL, source TEXT NOT NULL, world TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS declaration_signature ON declaration_projection(signature,id);
CREATE INDEX IF NOT EXISTS declaration_scope ON declaration_projection(package,entity_kind,visibility,synthetic,id);
CREATE VIEW IF NOT EXISTS declaration_rows AS
SELECT id, COALESCE(json_extract(body,'$.signature'),''),
       COALESCE(json_extract(body,'$.attributes.element_kind'),''),
       COALESCE(json_extract(body,'$.attributes.entity_kind'),
           CASE json_extract(body,'$.attributes.element_kind')
               WHEN 'CLASS' THEN 'type' WHEN 'INTERFACE' THEN 'type' WHEN 'ENUM' THEN 'type'
               WHEN 'ANNOTATION' THEN 'type' WHEN 'ANNOTATION_TYPE' THEN 'type' WHEN 'RECORD' THEN 'type'
               WHEN 'METHOD' THEN 'callable' WHEN 'CONSTRUCTOR' THEN 'callable'
               WHEN 'LAMBDA' THEN 'callable' WHEN 'METHOD_REF' THEN 'callable'
               WHEN 'FIELD' THEN 'value' WHEN 'ENUM_CONSTANT' THEN 'value'
               WHEN 'BEAN' THEN 'component' WHEN 'ARTIFACT' THEN 'artifact' ELSE 'entity' END),
       COALESCE(json_extract(body,'$.attributes.package'),json_extract(body,'$.attributes.namespace'),''),
       COALESCE(json_extract(body,'$.attributes.visibility'),''),
       COALESCE(json_extract(body,'$.attributes.synthetic'),0), parent, source, world
FROM facts WHERE kind IN ('declaration','external_symbol');
CREATE TRIGGER IF NOT EXISTS declaration_insert AFTER INSERT ON facts BEGIN
    INSERT INTO declaration_projection SELECT * FROM declaration_rows WHERE id=NEW.id;
END;
CREATE TRIGGER IF NOT EXISTS declaration_update AFTER UPDATE ON facts BEGIN
    DELETE FROM declaration_projection WHERE id=OLD.id;
    INSERT INTO declaration_projection SELECT * FROM declaration_rows WHERE id=NEW.id;
END;
CREATE TRIGGER IF NOT EXISTS declaration_delete AFTER DELETE ON facts BEGIN
    DELETE FROM declaration_projection WHERE id=OLD.id;
END;
