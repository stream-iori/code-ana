-- Private ownership IDs. Zero is the explicit host / fixture bucket.
CREATE TABLE unit_catalog(unit_pk INTEGER PRIMARY KEY,unit_id TEXT NOT NULL UNIQUE,backend TEXT NOT NULL);
INSERT INTO unit_catalog VALUES(0,'','');
