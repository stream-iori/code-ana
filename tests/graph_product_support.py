"""Compare logical graph values without importing another unittest class."""
import contextlib
import json
import sqlite3

TABLES=('nodes','edges','annotations','annotation_meta_owned',
        'file_dependencies','symbol_dependencies','analysis_coverage','index_diagnostics','index_providers')

def canonical_graph(self,revision):
    with contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db')) as db:
        self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
        self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(),[])
        self.assertEqual(db.execute('PRAGMA page_size').fetchone()[0],4096)
        self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0],17)
        self.assertFalse(db.execute("SELECT 1 FROM sqlite_master WHERE name LIKE '%semantic_annotations%'").fetchall())
        self.assertEqual(db.execute("SELECT value FROM project_meta WHERE key='schema_version'").fetchone()[0],'36')
        self.assertFalse(db.execute("SELECT 1 FROM sqlite_master WHERE name='facts'").fetchall())
        graph={}
        for table in TABLES:
            cols=[r[1] for r in db.execute('PRAGMA table_info('+table+')')]
            cols=[c for c in cols if not (c=='id' and table!='nodes')]
            graph[table]=sorted(db.execute('SELECT '+','.join(cols)+' FROM '+table).fetchall(),key=repr)
        graph['call_sites']=sorted(db.execute('''SELECT hex(c.stable_hash),o.caller_id,o.source_file,c.begin_line,c.begin_column,c.end_line,c.end_column,c.ordinal,c.context,c.syntax_target,c.receiver_static_type,c.dispatch_kind,c.metadata,c.origin,c.resolution_status,c.producer_id,c.language,c.provider_id FROM call_sites c JOIN call_site_owners o USING(owner_pk)''').fetchall(),key=repr)
        graph['call_site_targets']=sorted(db.execute('''SELECT hex(c.stable_hash),t.target_id,t.external_target_fqn,t.external_target_symbol,t.external_target_language,t.external_target_provider_id,t.resolution_status,t.confidence,t.producer_id FROM call_site_targets t JOIN call_sites c ON c.site_pk=t.call_site_pk''').fetchall(),key=repr)
        # Compare structured JSON by value. javac diagnostic sample_details can
        # serialize maps in a different key order in distinct compiler processes;
        # production storage still retains the original bytes unchanged.
        def canonical_cell(value):
            if isinstance(value,str) and value.startswith(('{','[')):
                try:return json.dumps(json.loads(value),sort_keys=True,ensure_ascii=False)
                except ValueError:pass
            return value
        return {table:sorted([tuple(canonical_cell(v) for v in row) for row in rows],key=repr)
                for table,rows in graph.items()}

