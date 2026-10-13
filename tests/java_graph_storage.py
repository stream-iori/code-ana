#!/usr/bin/env python3
"""Production typed Anatomist graph regressions; uses the existing executable paths."""
import contextlib, json, sqlite3, unittest
from java_e2e import JavaProduct

TABLES=('nodes','edges','annotations','annotation_meta_relations','semantic_annotations',
        'file_dependencies','symbol_dependencies','analysis_coverage','index_diagnostics','index_providers')

class GraphStorage(JavaProduct):
    # Inherit lifecycle helpers, not the obsolete Fact-IR acceptance cases.
    def canonical(self,revision):
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db')) as db:
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
            self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(),[])
            self.assertEqual(db.execute('PRAGMA page_size').fetchone()[0],4096)
            self.assertEqual(db.execute("SELECT value FROM project_meta WHERE key='schema_version'").fetchone()[0],'32')
            self.assertFalse(db.execute("SELECT 1 FROM sqlite_master WHERE name='facts'").fetchall())
            graph={}
            for table in TABLES:
                cols=[r[1] for r in db.execute('PRAGMA table_info('+table+')')]
                cols=[c for c in cols if not (c=='id' and table!='nodes')]
                graph[table]=sorted(db.execute('SELECT '+','.join(cols)+' FROM '+table).fetchall(),key=repr)
            graph['call_sites']=sorted(db.execute('''SELECT hex(c.stable_hash),o.caller_id,o.source_file,c.begin_line,c.begin_column,c.end_line,c.end_column,c.ordinal,c.context,c.syntax_target,c.receiver_static_type,c.dispatch_kind,c.metadata,c.origin,c.resolution_status,c.producer_id,c.language,c.provider_id FROM call_sites c JOIN call_site_owners o USING(owner_pk)''').fetchall(),key=repr)
            graph['call_site_targets']=sorted(db.execute('''SELECT hex(c.stable_hash),t.target_id,t.external_target_fqn,t.external_target_symbol,t.external_target_language,t.external_target_provider_id,t.resolution_status,t.confidence,t.producer_id FROM call_site_targets t JOIN call_sites c ON c.site_pk=t.call_site_pk''').fetchall(),key=repr)
            return graph

    def test_graph_full_delta_delete_and_old_revision(self):
        (self.project/'src/p/Extra.java').write_text('package p; @Deprecated class Extra { String read(Service s){return s.greet("汉字🧪");} }')
        _,first=self.cli('index','--full',timeout=180)
        before=self.canonical(first['revision'])
        self.assertTrue(before['nodes']);self.assertTrue(before['call_sites']);self.assertTrue(before['annotations'])
        rows,_=self.cli('resolve','p.Service');self.assertTrue(rows)
        path=self.project/'src/p/Extra.java'
        path.write_text(path.read_text().replace('s.greet("汉字🧪")','s.identity("汉字🧪")'))
        _,delta=self.cli('index',timeout=180)
        changed=self.canonical(delta['revision'])
        _,full=self.cli('index','--full',timeout=180)
        self.assertEqual(changed,self.canonical(full['revision']))
        self.assertEqual(before,self.canonical(first['revision']))
        path.unlink()
        _,delta=self.cli('index',timeout=180)
        removed=self.canonical(delta['revision'])
        _,full=self.cli('index','--full',timeout=180)
        self.assertEqual(removed,self.canonical(full['revision']))
        self.assertFalse(any(r[12]=='src/p/Extra.java' for r in removed['nodes']))

    def test_search_calls_source_and_external_resolution(self):
        _,publication=self.cli('index','--full',timeout=180)
        rows,_=self.cli('resolve','p.Service#greet(java.lang.String)')
        self.assertEqual(len(rows),1);method=rows[0]['id']
        calls,_=self.cli('calls',method);self.assertTrue(calls)
        source,_=self.cli('source',method);self.assertTrue(source)
        external,_=self.cli('resolve','java.lang.String#trim()');self.assertTrue(external)
        self.assertEqual(external[0]['kind'],'external_symbol')
        found,_=self.cli('resolve','p.Service');self.assertTrue(found)
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/publication['revision']/'facts.db')) as db:
            self.assertTrue(db.execute("SELECT rowid FROM node_names WHERE node_names MATCH ?",('\"Service\"*',)).fetchall())

    def test_xml_resource_identity_and_incremental_equivalence(self):
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text().replace('[languages.java.extensions.spring]\nenabled = false','[languages.java.extensions.spring]\nenabled = true\nxml = true'))
        (self.project/'src/p/XmlChild.java').write_text('package p; class XmlBase { public void setName(String x){} } class XmlChild extends XmlBase {}')
        xml=self.project/'beans.xml';xml.write_text('<beans><bean id="service" class="p.XmlChild"><property name="name" value="one"/></bean></beans>')
        _,first=self.cli('index','--full',timeout=180)
        original=self.canonical(first['revision'])
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/first['revision']/'facts.db')) as db:
            resources=db.execute("SELECT module,scope FROM nodes WHERE source_file='beans.xml'").fetchall()
            self.assertTrue(resources);self.assertEqual(set(resources),{('app','MAIN')})
        xml.write_text('<beans><bean id="other" class="p.XmlChild"><property name="name" value="two"/></bean></beans>')
        _,delta=self.cli('index',timeout=180);changed=self.canonical(delta['revision'])
        _,full=self.cli('index','--full',timeout=180)
        self.assertEqual(changed,self.canonical(full['revision']))
        self.assertEqual(original,self.canonical(first['revision']))

    def test_unchanged_index_preserves_graph_and_fts(self):
        _,first=self.cli('index','--full',timeout=180);before=self.canonical(first['revision'])
        _,next=self.cli('index',timeout=180)
        self.assertEqual(before,self.canonical(next['revision']))
        rows,_=self.cli('resolve','p.Service');self.assertTrue(rows)

# Explicit suite avoids inheriting the legacy capability matrix.
def load_tests(loader,tests,pattern):
    return unittest.TestSuite(GraphStorage(name) for name in (
        'test_graph_full_delta_delete_and_old_revision',
        'test_search_calls_source_and_external_resolution',
        'test_xml_resource_identity_and_incremental_equivalence',
        'test_unchanged_index_preserves_graph_and_fts'))

if __name__=='__main__': unittest.main()
