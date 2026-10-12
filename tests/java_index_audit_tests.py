import hashlib
from contextlib import closing
import importlib.util
import json
import pathlib
import sqlite3
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('audit',ROOT/'scripts/audit-java-index.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class Audit(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.path=pathlib.Path(self.directory.name)/'facts.db'
        (self.path.parent/'revision.json').write_text(json.dumps(dict(manifest=dict(files=[dict(path=p,world='main',hash='abc',size=100) for p in ['A.java','B.java']]))))
        with closing(sqlite3.connect(self.path)) as db, db:
            for name in ('schema.sql','query-indexes.sql','symbol-search.sql'):
                db.executescript((ROOT/'crates/storage/src'/name).read_text())
            db.execute('PRAGMA user_version=4')
            for identifier,path in [('Alpha','A.java'),('Beta','B.java')]:
                db.execute('INSERT INTO source_units VALUES(?,?,?,?,?,0)',[path,'main','app','MAIN','abc'])
                self.insert(db,identifier,'declaration',path,'','',identifier)
            self.insert(db,'edge','reference','A.java','Alpha','Beta','')
            db.executescript((ROOT/'crates/storage/src/file-dependencies.sql').read_text())
    def insert(self,db,id,kind,source,parent,target,signature):
        owner=dict(world='main',producer='fixture',kind='source',id=source)
        key=hashlib.sha256(json.dumps(owner,separators=(',',':')).encode()).hexdigest()
        fact=dict(id=id,kind=kind,owner=owner,source=source,parent=parent,target=target,
                  signature=signature,name=id,relation='',attributes={'element_kind':'CLASS'},origin='fixture')
        db.execute('INSERT INTO facts VALUES(?,?,?,?,?,?,?,?,?,?)',[id,kind,key,parent,target,'',id,source,'main',json.dumps(fact)])
    def broken(self,sql,code):
        with closing(sqlite3.connect(self.path)) as db, db:db.executescript(sql)
        result=module.audit(self.path)
        self.assertEqual(result['status'],'failed',result)
        self.assertIn(code,[row['code'] for row in result['errors']],result)
        self.assertEqual(result['before_sha256'],result['after_sha256'])
    def test_complete_and_read_only(self):
        result=module.audit(self.path)
        self.assertEqual(result['status'],'passed',result)
        self.assertEqual(result['before_sha256'],result['after_sha256'])
    def test_missing_capture_manifest(self):
        (self.path.parent/'revision.json').unlink()
        result=module.audit(self.path)
        self.assertIn('CAPTURE_MANIFEST_REQUIRED',[r['code'] for r in result['errors']])
    def test_capture_manifest_hash_drift(self):
        p=self.path.parent/'revision.json';d=json.loads(p.read_text());d['manifest']['files'][0]['hash']='wrong';p.write_text(json.dumps(d))
        result=module.audit(self.path)
        self.assertIn('CAPTURE_MANIFEST_IDENTITY',[r['code'] for r in result['errors']])
    def test_missing_index(self):self.broken('DROP INDEX fact_parent','SCHEMA_STRUCTURE')
    def test_wrong_index_order(self):self.broken('DROP INDEX fact_target;CREATE INDEX fact_target ON facts(id,target,relation,kind)','SCHEMA_STRUCTURE')
    def test_dangling_endpoint(self):self.broken("UPDATE facts SET target='absent',body=json_set(body,'$.target','absent') WHERE id='edge'",'DANGLING_TARGET')
    def test_owner_loss(self):self.broken("UPDATE facts SET owner='wrong' WHERE id='edge'",'OWNER_IDENTITY')
    def test_wrong_world(self):self.broken("UPDATE source_units SET world='test' WHERE path='A.java'",'SOURCE_WORLD')
    def test_stale_projection(self):self.broken("DELETE FROM declaration_projection WHERE id='Alpha'",'DECLARATION_PROJECTION_MISSING')
    def test_span_reversed(self):self.broken("UPDATE facts SET body=json_set(body,'$.span',json('{\"start\":5,\"end\":3}')) WHERE id='edge'",'SOURCE_SPAN')
    def test_parent_cycle(self):self.broken("UPDATE facts SET parent='Beta',body=json_set(body,'$.parent','Beta') WHERE id='Alpha';UPDATE facts SET parent='Alpha',body=json_set(body,'$.parent','Alpha') WHERE id='Beta'",'DECLARATION_PARENT_CYCLE')
    def test_dependency_loss(self):self.broken('DELETE FROM file_dependencies','FILE_DEPENDENCY_PROJECTION')
    def test_span_exceeds_capture(self):self.broken("UPDATE facts SET body=json_set(body,'$.span',json('{\"start\":3,\"end\":101}')) WHERE id='edge'",'SOURCE_SPAN_BOUNDS')
    def test_dependency_id_collision(self):self.broken("INSERT INTO file_dependencies SELECT 'duplicate',source_world,source_file,target_world,target_file FROM file_dependencies",'FILE_DEPENDENCY_IDENTITY')
    def test_fts_stale(self):self.broken("INSERT INTO symbol_names(symbol_names,rowid,name,signature) SELECT 'delete',rowid,name,json_extract(body,'$.signature') FROM facts WHERE id='Alpha'",'SYMBOL_FTS_INTEGRITY')

if __name__=='__main__':unittest.main(verbosity=2)
