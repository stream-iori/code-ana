-- Anatomist node_names uses an external-content FTS5 index. This index only
-- covers declarations/external symbols; original Fact IR payloads stay intact.
-- Trigrams preserve Rust search-code's literal substring contract.
CREATE VIEW IF NOT EXISTS symbol_search_content AS
    SELECT rowid,name,COALESCE(json_extract(body,'$.signature'),'') AS signature
    FROM facts WHERE kind IN ('declaration','external_symbol');
CREATE VIRTUAL TABLE IF NOT EXISTS symbol_names USING fts5(
    name,signature,content='symbol_search_content',content_rowid='rowid',
    tokenize='trigram case_sensitive 1'
);
CREATE TRIGGER IF NOT EXISTS facts_symbols_ai AFTER INSERT ON facts
WHEN new.kind IN ('declaration','external_symbol') BEGIN
    INSERT INTO symbol_names(rowid,name,signature)
        VALUES(new.rowid,new.name,COALESCE(json_extract(new.body,'$.signature'),''));
END;
CREATE TRIGGER IF NOT EXISTS facts_symbols_ad AFTER DELETE ON facts
WHEN old.kind IN ('declaration','external_symbol') BEGIN
    INSERT INTO symbol_names(symbol_names,rowid,name,signature)
        VALUES('delete',old.rowid,old.name,COALESCE(json_extract(old.body,'$.signature'),''));
END;
CREATE TRIGGER IF NOT EXISTS facts_symbols_au AFTER UPDATE ON facts
WHEN old.kind IN ('declaration','external_symbol') OR new.kind IN ('declaration','external_symbol') BEGIN
    INSERT INTO symbol_names(symbol_names,rowid,name,signature)
        SELECT 'delete',old.rowid,old.name,COALESCE(json_extract(old.body,'$.signature'),'')
        WHERE old.kind IN ('declaration','external_symbol');
    INSERT INTO symbol_names(rowid,name,signature)
        SELECT new.rowid,new.name,COALESCE(json_extract(new.body,'$.signature'),'')
        WHERE new.kind IN ('declaration','external_symbol');
END;
