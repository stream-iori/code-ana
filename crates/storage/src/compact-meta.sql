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

-- Rust incremental metadata: strings are interned, dependencies have one canonical copy.
CREATE TABLE rust_strings(string_pk INTEGER PRIMARY KEY,value TEXT NOT NULL UNIQUE);
CREATE TABLE graph_rust_source_states(
 source_file INTEGER NOT NULL REFERENCES source_files(file_pk),
 world INTEGER NOT NULL REFERENCES rust_strings(string_pk),state TEXT NOT NULL,
 PRIMARY KEY(source_file,world)) WITHOUT ROWID;
CREATE TABLE graph_rust_analysis_dependencies(
 source_file INTEGER NOT NULL REFERENCES source_files(file_pk),
 world INTEGER NOT NULL REFERENCES rust_strings(string_pk),
 kind INTEGER NOT NULL CHECK(kind BETWEEN 0 AND 4),
 target_world INTEGER NOT NULL REFERENCES rust_strings(string_pk),
 target_key INTEGER NOT NULL REFERENCES rust_strings(string_pk),
 PRIMARY KEY(source_file,world,kind,target_world,target_key),
 FOREIGN KEY(source_file,world) REFERENCES graph_rust_source_states(source_file,world)) WITHOUT ROWID;
CREATE VIEW rust_source_states AS SELECT f.path AS source_file,w.value AS world,s.state
 FROM graph_rust_source_states s JOIN source_files f ON f.file_pk=s.source_file
 JOIN rust_strings w ON w.string_pk=s.world;
CREATE VIEW rust_analysis_dependencies AS SELECT f.path AS source_file,w.value AS world,
 CASE d.kind WHEN 0 THEN 'file' WHEN 1 THEN 'symbol' WHEN 2 THEN 'lookup' WHEN 3 THEN 'macro' WHEN 4 THEN 'impl_set' END AS kind,
 t.value AS target_world,k.value AS target_key
 FROM graph_rust_analysis_dependencies d JOIN source_files f ON f.file_pk=d.source_file
 JOIN rust_strings w ON w.string_pk=d.world JOIN rust_strings t ON t.string_pk=d.target_world
 JOIN rust_strings k ON k.string_pk=d.target_key;
CREATE TRIGGER compact_rust_state_insert INSTEAD OF INSERT ON rust_source_states BEGIN
 INSERT INTO source_files(path) SELECT NEW.source_file WHERE NOT EXISTS(SELECT 1 FROM source_files WHERE path=NEW.source_file);
 INSERT INTO rust_strings(value) SELECT NEW.world WHERE NOT EXISTS(SELECT 1 FROM rust_strings WHERE value=NEW.world);
 INSERT INTO graph_rust_source_states VALUES(
 (SELECT file_pk FROM source_files WHERE path=NEW.source_file),
 (SELECT string_pk FROM rust_strings WHERE value=NEW.world),json_remove(NEW.state,'$.dependencies'))
 ON CONFLICT(source_file,world) DO UPDATE SET state=excluded.state;
END;
CREATE TRIGGER compact_rust_dependency_insert INSTEAD OF INSERT ON rust_analysis_dependencies BEGIN
 INSERT OR IGNORE INTO rust_strings(value) VALUES(NEW.target_world);
 INSERT OR IGNORE INTO rust_strings(value) VALUES(NEW.target_key);
 INSERT INTO graph_rust_analysis_dependencies VALUES(
 (SELECT file_pk FROM source_files WHERE path=NEW.source_file),
 (SELECT string_pk FROM rust_strings WHERE value=NEW.world),
 CASE NEW.kind WHEN 'file' THEN 0 WHEN 'symbol' THEN 1 WHEN 'lookup' THEN 2 WHEN 'macro' THEN 3 WHEN 'impl_set' THEN 4 END,
 (SELECT string_pk FROM rust_strings WHERE value=NEW.target_world),
 (SELECT string_pk FROM rust_strings WHERE value=NEW.target_key)) ON CONFLICT(source_file,world,kind,target_world,target_key) DO NOTHING;
END;
CREATE TRIGGER compact_rust_dependency_delete INSTEAD OF DELETE ON rust_analysis_dependencies BEGIN
 DELETE FROM graph_rust_analysis_dependencies WHERE
 source_file=(SELECT file_pk FROM source_files WHERE path=OLD.source_file)
 AND world=(SELECT string_pk FROM rust_strings WHERE value=OLD.world)
 AND kind=CASE OLD.kind WHEN 'file' THEN 0 WHEN 'symbol' THEN 1 WHEN 'lookup' THEN 2 WHEN 'macro' THEN 3 WHEN 'impl_set' THEN 4 END
 AND target_world=(SELECT string_pk FROM rust_strings WHERE value=OLD.target_world)
 AND target_key=(SELECT string_pk FROM rust_strings WHERE value=OLD.target_key);
END;
CREATE TRIGGER compact_rust_state_delete INSTEAD OF DELETE ON rust_source_states BEGIN
 DELETE FROM graph_rust_analysis_dependencies WHERE
 source_file=(SELECT file_pk FROM source_files WHERE path=OLD.source_file)
 AND world=(SELECT string_pk FROM rust_strings WHERE value=OLD.world);
 DELETE FROM graph_rust_source_states WHERE
 source_file=(SELECT file_pk FROM source_files WHERE path=OLD.source_file)
 AND world=(SELECT string_pk FROM rust_strings WHERE value=OLD.world);
END;

-- Independent source comments. Public IDs never depend on physical integer keys.
CREATE TABLE graph_comments(
 comment_pk INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,
 source_file INTEGER NOT NULL REFERENCES source_files(file_pk),source_hash TEXT NOT NULL,
 module TEXT NOT NULL,scope TEXT NOT NULL,world TEXT NOT NULL,language TEXT NOT NULL,
 provider_id TEXT NOT NULL,producer_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN('line','block','doc','doc_attribute','docstring')),
 byte_start INTEGER NOT NULL CHECK(byte_start>=0),byte_end INTEGER NOT NULL CHECK(byte_end>byte_start),
 begin_line INTEGER NOT NULL CHECK(begin_line>0),begin_column INTEGER NOT NULL CHECK(begin_column>0),
 end_line INTEGER NOT NULL CHECK(end_line>=begin_line),end_column INTEGER NOT NULL CHECK(end_column>0),
 body TEXT NOT NULL,body_map TEXT NOT NULL CHECK(json_valid(body_map)),rule_version TEXT NOT NULL,
 terms TEXT NOT NULL);
CREATE INDEX comments_owner ON graph_comments(source_file,module,scope,world,producer_id);
CREATE INDEX comments_order ON graph_comments(byte_start,byte_end,world,id);
CREATE TABLE graph_comment_links(
 link_pk INTEGER PRIMARY KEY,comment_pk INTEGER NOT NULL REFERENCES graph_comments(comment_pk) ON DELETE CASCADE,
 relation TEXT NOT NULL CHECK(relation IN('documents','enclosing_callable')),
 declaration_pk INTEGER REFERENCES graph_nodes(node_pk),
 byte_start INTEGER NOT NULL CHECK(byte_start>=0),byte_end INTEGER NOT NULL CHECK(byte_end>byte_start),
 begin_line INTEGER NOT NULL CHECK(begin_line>0),begin_column INTEGER NOT NULL CHECK(begin_column>0),
 end_line INTEGER NOT NULL CHECK(end_line>=begin_line),end_column INTEGER NOT NULL CHECK(end_column>0),
 basis TEXT NOT NULL CHECK(basis IN('language_rule')),rule_version TEXT NOT NULL);
CREATE UNIQUE INDEX comment_links_identity ON graph_comment_links(comment_pk,relation,coalesce(declaration_pk,0),byte_start,byte_end,basis,rule_version);
CREATE INDEX comment_links_declaration ON graph_comment_links(declaration_pk,comment_pk);
CREATE VIEW comments AS SELECT c.id,f.path AS source_file,c.source_hash,c.module,c.scope,c.world,c.language,
 c.provider_id,c.producer_id,c.kind,c.byte_start,c.byte_end,c.begin_line,c.begin_column,c.end_line,c.end_column,
 c.body,c.body_map,c.rule_version FROM graph_comments c JOIN source_files f ON f.file_pk=c.source_file;
CREATE VIEW comment_links AS SELECT c.id AS comment_id,l.relation,n.id AS declaration_id,
 f.path AS source_file,c.module,c.scope,c.world,l.byte_start,l.byte_end,l.begin_line,l.begin_column,l.end_line,l.end_column,l.basis,l.rule_version
 FROM graph_comment_links l JOIN graph_comments c ON c.comment_pk=l.comment_pk
 JOIN source_files f ON f.file_pk=c.source_file LEFT JOIN graph_nodes n ON n.node_pk=l.declaration_pk;
CREATE VIRTUAL TABLE comment_terms USING fts5(terms,content='graph_comments',content_rowid='comment_pk',tokenize="unicode61 remove_diacritics 0 tokenchars '_'");
CREATE TRIGGER comments_ai AFTER INSERT ON graph_comments BEGIN INSERT INTO comment_terms(rowid,terms) VALUES(new.comment_pk,new.terms); END;
CREATE TRIGGER comments_ad AFTER DELETE ON graph_comments BEGIN INSERT INTO comment_terms(comment_terms,rowid,terms) VALUES('delete',old.comment_pk,old.terms); END;
CREATE TRIGGER comments_au AFTER UPDATE OF terms ON graph_comments BEGIN
 INSERT INTO comment_terms(comment_terms,rowid,terms) VALUES('delete',old.comment_pk,old.terms);
 INSERT INTO comment_terms(rowid,terms) VALUES(new.comment_pk,new.terms); END;
