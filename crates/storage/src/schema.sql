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
