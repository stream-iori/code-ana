-- Anatomist schema 33 definitions and lossless file-owned observations.
CREATE TABLE annotation_meta_relations(id INTEGER PRIMARY KEY,
 annotation_fqn TEXT NOT NULL,meta_annotation_fqn TEXT,raw_name TEXT NOT NULL,
 language TEXT NOT NULL,provider_id TEXT NOT NULL DEFAULT 'java-core',
 mechanism TEXT NOT NULL,resolution_status TEXT NOT NULL CHECK(resolution_status IN('exact','heuristic','unresolved')),
 producer_id TEXT NOT NULL DEFAULT 'java-core');
CREATE UNIQUE INDEX idx_annotation_meta_identity ON annotation_meta_relations(
 provider_id,annotation_fqn,coalesce(meta_annotation_fqn,''),raw_name,language,mechanism,producer_id);
CREATE INDEX idx_annotation_meta_source ON annotation_meta_relations(annotation_fqn);
CREATE INDEX idx_annotation_meta_target ON annotation_meta_relations(meta_annotation_fqn);
CREATE TABLE annotation_meta_sources(id INTEGER PRIMARY KEY,
 source_file INTEGER REFERENCES source_files(file_pk),identity_file INTEGER NOT NULL UNIQUE);
CREATE TABLE annotation_meta_owners(relation_id INTEGER NOT NULL REFERENCES annotation_meta_relations(id),
 source_id INTEGER NOT NULL REFERENCES annotation_meta_sources(id),
 resolution_status TEXT NOT NULL CHECK(resolution_status IN('exact','heuristic','unresolved')),
 source_location TEXT,PRIMARY KEY(relation_id,source_id)) WITHOUT ROWID;
CREATE INDEX idx_annotation_meta_owner_source ON annotation_meta_owners(source_id,relation_id);
CREATE VIEW annotation_meta_owned AS SELECT r.id,r.annotation_fqn,r.meta_annotation_fqn,r.raw_name,r.language,
 r.provider_id,r.mechanism,o.resolution_status,f.path AS source_file,o.source_location,r.producer_id
 FROM annotation_meta_owners o JOIN annotation_meta_relations r ON r.id=o.relation_id
 JOIN annotation_meta_sources s ON s.id=o.source_id LEFT JOIN source_files f ON f.file_pk=s.source_file;
CREATE TRIGGER annotation_meta_owned_insert INSTEAD OF INSERT ON annotation_meta_owned BEGIN
 INSERT OR IGNORE INTO source_files(path) SELECT NEW.source_file WHERE NEW.source_file IS NOT NULL;
 INSERT OR IGNORE INTO annotation_meta_sources(source_file,identity_file)
 VALUES((SELECT file_pk FROM source_files WHERE path=NEW.source_file),
 CASE WHEN coalesce(NEW.source_file,'')='' THEN 0 ELSE (SELECT file_pk FROM source_files WHERE path=NEW.source_file) END);
 INSERT OR IGNORE INTO annotation_meta_relations(annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,producer_id)
 VALUES(NEW.annotation_fqn,NEW.meta_annotation_fqn,NEW.raw_name,NEW.language,NEW.provider_id,NEW.mechanism,NEW.resolution_status,NEW.producer_id);
 INSERT OR IGNORE INTO annotation_meta_owners(relation_id,source_id,resolution_status,source_location)
 SELECT r.id,s.id,NEW.resolution_status,NEW.source_location FROM annotation_meta_relations r,annotation_meta_sources s
 WHERE r.provider_id=NEW.provider_id AND r.annotation_fqn=NEW.annotation_fqn
 AND coalesce(r.meta_annotation_fqn,'')=coalesce(NEW.meta_annotation_fqn,'') AND r.raw_name=NEW.raw_name
 AND r.language=NEW.language AND r.mechanism=NEW.mechanism AND r.producer_id=NEW.producer_id
 AND s.identity_file=CASE WHEN coalesce(NEW.source_file,'')='' THEN 0 ELSE (SELECT file_pk FROM source_files WHERE path=NEW.source_file) END;
END;
CREATE TRIGGER annotation_meta_owned_delete INSTEAD OF DELETE ON annotation_meta_owned BEGIN
 DELETE FROM annotation_meta_owners WHERE relation_id=OLD.id AND source_id IN
 (SELECT s.id FROM annotation_meta_sources s LEFT JOIN source_files f ON f.file_pk=s.source_file WHERE f.path IS OLD.source_file);
END;
