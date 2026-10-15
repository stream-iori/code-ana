#!/usr/bin/env python3
"""Production typed Anatomist graph regressions; uses the existing executable paths."""
import contextlib, json, os, sqlite3, unittest, subprocess, zipfile, sys
from product_support import JavaProductHarness, ROOT, BIN, JDK

TABLES=('nodes','edges','annotations','annotation_meta_owned','semantic_annotations',
        'file_dependencies','symbol_dependencies','analysis_coverage','index_diagnostics','index_providers')

class GraphStorage(JavaProductHarness):
    def test_maven_fallback_transitions_preserve_source_and_strict_head(self):
        repo=self.root/'repository';repo.mkdir()
        settings=self.root/'settings.xml'
        settings.write_text('<settings><localRepository>'+str(repo)+'</localRepository></settings>')
        dependency=self.root/'External.java'
        dependency.write_text('package dep; public class External {public static void work(){}}')
        classes=self.root/'classes';classes.mkdir()
        subprocess.run([str(JDK/'bin/javac'),'-d',str(classes),str(dependency)],check=True,capture_output=True)
        artifact=repo/'fixture/external/1';artifact.mkdir(parents=True)
        (artifact/'external-1.pom').write_text('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>external</artifactId><version>1</version></project>')
        jar=artifact/'external-1.jar'
        with zipfile.ZipFile(jar,'w') as out:out.write(classes/'dep/External.class','dep/External.class')
        original=jar.read_bytes()
        (self.project/'pom.xml').write_text('''<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><properties><maven.compiler.source>1.8</maven.compiler.source><maven.compiler.target>1.8</maven.compiler.target></properties><modules><module>lib</module><module>app</module></modules></project>''')
        parent='<parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version></parent>'
        for module in ('lib','app'):
            base=self.project/module;(base/'src/main/java'/module).mkdir(parents=True)
            deps='' if module=='lib' else '<dependencies><dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></dependency><dependency><groupId>fixture</groupId><artifactId>external</artifactId><version>1</version></dependency></dependencies>'
            (base/'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion>'+parent+'<artifactId>'+module+'</artifactId>'+deps+'</project>')
        library=self.project/'lib/src/main/java/lib/Library.java'
        library.write_text('package lib; public class Library {public static void work(){}}')
        caller=self.project/'app/src/main/java/app/App.java'
        caller.write_text('package app; class App {void run(){lib.Library.work();dep.External.work();"ok".trim();}}')
        (self.project/'.code-ana/config.toml').write_text('''schema_version=1
kind="project"
[scan]
scopes=["MAIN"]
[languages.java]
enabled=true
[languages.java.discovery]
build_system="maven"
inputs_manifest=".code-ana/inputs.json"
[languages.java.analysis]
source_version="8"
toolchain="java8"
[languages.java.extensions.spring]
enabled=false
[languages.java.extensions.sofa]
enabled=false
''')
        def export(selected=False):
            output=self.project/'.code-ana/next-inputs.json'
            args=[str(BIN/'codeana-cli'),'--project',str(self.project),'java-inputs','export','--output',str(output),'--settings',str(settings),'--offline','--scope','MAIN']
            if bundle:=os.environ.get('CODEANA_TEST_JAVA_BUNDLE'):
                # JVM's descriptor names its bundle directory; Native's names native/.
                extension=os.path.join(bundle,'..' if os.environ.get('CODEANA_JAVA_BACKEND')=='native' else '.', 'maven-input-worker.jar')
                self.assertTrue(os.path.isfile(extension),'sealed Maven extension missing: '+extension)
                args+=['--extension',extension]
            if selected:args+=['--module','app']
            process=subprocess.run(args,capture_output=True,text=True,timeout=90)
            self.assertEqual(process.returncode,0,process.stdout+process.stderr)
            manifest=json.loads(output.read_text())
            output.replace(self.project/'.code-ana/inputs.json')
            return manifest
        def targets(revision):
            with contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db')) as db:
                return {r[0] for r in db.execute('SELECT coalesce(n.symbol_id,t.external_target_symbol) FROM call_site_targets t LEFT JOIN nodes n ON n.id=t.target_id')}
        export();_,first=self.cli('index','--full',timeout=180)
        self.assertIn('dep.External#work()',targets(first['revision']))
        jar.rename(jar.with_suffix('.unavailable'))
        manifest=export()
        self.assertEqual(manifest['dependency_mode'],'source_only_fallback')
        records,degraded=self.cli('index',timeout=180)
        self.assertEqual(records[0]['mode'],'full')
        self.assertEqual(records[0]['analysis_mode'],'source_only')
        self.assertFalse(degraded['evidence']['semantic'])
        self.assertIn('CLASSPATH_UNAVAILABLE',degraded['evidence']['reasons'])
        report=self.cli('report',degraded['report'])[0][0]
        self.assertEqual(report['summary']['analysis_mode'],'source_only')
        self.assertEqual(report['summary']['classpath_status'],'unavailable')
        self.assertIn('lib.Library#work()',targets(degraded['revision']))
        self.assertNotIn('dep.External#work()',targets(degraded['revision']))
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/degraded['revision']/'facts.db')) as db:
            self.assertGreater(db.execute("SELECT count(*) FROM index_diagnostics WHERE code='CLASSPATH_UNAVAILABLE'").fetchone()[0],0)
        methods,_=self.cli('resolve','app.App#run()')
        self.assertTrue(methods)
        self.assertTrue(self.cli('source',methods[0]['id'])[0])
        self.assertTrue(self.cli('search-code','Library')[0])
        _,strict=self.cli('index','--strict',code=3,timeout=180)
        self.assertEqual(strict['publication'],'retained')
        self.assertEqual(self.cli('overview')[1]['revision'],degraded['revision'])
        # Delta and no-change paths retain exactly the same global warning counts.
        caller.write_text(caller.read_text().replace('"ok"','"changed"'))
        _,delta=self.cli('index',timeout=180)
        _,fresh=self.cli('index','--full',timeout=180)
        self.assertEqual(self.canonical(delta['revision']),self.canonical(fresh['revision']))
        records,unchanged=self.cli('index',timeout=180)
        self.assertEqual(records[0]['analysis_mode'],'source_only')
        self.assertFalse(unchanged['evidence']['semantic'])
        jar.write_bytes(original);export()
        records,recovered=self.cli('index',timeout=180)
        self.assertEqual(records[0]['mode'],'full')
        self.assertIn('dep.External#work()',targets(recovered['revision']))
        old_report=self.cli('report',degraded['report'])[0][0]
        self.assertEqual(old_report['summary']['analysis_mode'],'source_only')
        self.assertFalse(old_report['summary']['evidence']['semantic'])
        _,fresh=self.cli('index','--full',timeout=180)
        self.assertEqual(self.canonical(recovered['revision']),self.canonical(fresh['revision']))
        # Selected=false prerequisites remain semantic inputs in source-only mode.
        jar.rename(jar.with_suffix('.missing'));export(selected=True)
        _,selected=self.cli('index',timeout=180)
        self.assertIn('lib.Library#work()',targets(selected['revision']))
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/selected['revision']/'facts.db')) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM nodes WHERE source_file LIKE 'lib/%'").fetchone()[0],0)
        # Missing unused dependencies still fail the strict quality gate.
        caller.write_text('package app; class App {void run(){lib.Library.work();}}')
        self.cli('index','--strict',code=3,timeout=180)

    # Shared fixture helpers never contain legacy acceptance cases.
    def test_mixed_java_rust_delta_full_and_strict_preserve_both_languages(self):
        (self.project/'Cargo.toml').write_text('[package]\nname="mixed_fixture"\nversion="0.1.0"\nedition="2021"\n[lib]\npath="rustsrc/lib.rs"\n')
        (self.project/'rustsrc').mkdir()
        source=self.project/'rustsrc/lib.rs'
        source.write_text('pub fn rust_answer() -> i32 { 1 }\n')
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text().replace('[[modules]]', '[languages.rust]\nenabled=true\n[resources.index]\nworker_memory_bytes=1073741824\njava_native_heap_bytes=134217728\njava_compiler_heap_bytes=67108864\njava_parallelism=1\n[[modules]]', 1))
        subprocess.run(['cargo','generate-lockfile','--offline'],cwd=self.project,check=True,capture_output=True)
        subprocess.run([sys.executable,str(ROOT/'scripts/export-rust-inputs.py'),'--project',str(self.project),'--worker',str(BIN/'codeana-rust-worker')],check=True,capture_output=True,text=True)
        first=self.cli('index','--full',timeout=180)[1]['revision']
        def languages(revision):
            with sqlite3.connect(self.home/'revisions'/revision/'facts.db') as db:
                return dict(db.execute('SELECT language,count(*) FROM nodes GROUP BY language'))
        self.assertGreater(languages(first).get('java',0),0)
        self.assertGreater(languages(first).get('rust',0),0)
        self.assertTrue(self.cli('resolve','rust_answer')[0])
        source.write_text('pub fn rust_answer() -> i32 { 2 }\npub fn added() -> i32 { rust_answer() }\n')
        delta=self.cli('index',timeout=180)[1]['revision']
        full=self.cli('index','--full',timeout=180)[1]['revision']
        self.assertEqual(self.canonical(delta),self.canonical(full))
        self.assertEqual(languages(first).get('java'),languages(full).get('java'))
        for revision in (delta,full):
            with sqlite3.connect(self.home/'revisions'/revision/'facts.db') as db:
                self.assertGreater(db.execute('SELECT count(*) FROM rust_source_states').fetchone()[0],0)
        self.cli('index','--strict',code=3,timeout=180)
        self.assertEqual(self.cli('overview')[1]['revision'],full)

    def canonical(self,revision):
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/revision/'facts.db')) as db:
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
            self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(),[])
            self.assertEqual(db.execute('PRAGMA page_size').fetchone()[0],4096)
            self.assertEqual(db.execute("SELECT value FROM project_meta WHERE key='schema_version'").fetchone()[0],'33')
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

    def test_graph_full_delta_delete_and_old_revision(self):
        (self.project/'src/p/Extra.java').write_text('package p; @Deprecated class Extra { String read(Service s){return s.greet("汉字🧪");} }')
        _,first=self.cli('index','--full',timeout=180)
        before=self.canonical(first['revision'])
        worker=self.java_worker_pid();self.assertIsNotNone(worker)
        self.assertTrue(before['nodes']);self.assertTrue(before['call_sites']);self.assertTrue(before['annotations'])
        rows,_=self.cli('resolve','p.Service');self.assertTrue(rows)
        path=self.project/'src/p/Extra.java'
        path.write_text(path.read_text().replace('s.greet("汉字🧪")','s.identity("汉字🧪")'))
        _,delta=self.cli('index',timeout=180)
        changed=self.canonical(delta['revision'])
        self.assertEqual(self.java_worker_pid(),worker)
        _,full=self.cli('index','--full',timeout=180)
        self.assertEqual(changed,self.canonical(full['revision']))
        self.assertEqual(self.java_worker_pid(),worker)
        self.assertEqual(before,self.canonical(first['revision']))
        path.unlink()
        _,delta=self.cli('index',timeout=180)
        removed=self.canonical(delta['revision'])
        _,full=self.cli('index','--full',timeout=180)
        self.assertEqual(removed,self.canonical(full['revision']))
        self.assertFalse(any(r[12]=='src/p/Extra.java' for r in removed['nodes']))
        # The legacy-version guard must reject reads while allowing an explicit
        # full rebuild to replace the head without trying to read its graph.
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/full['revision']/'facts.db')) as db:
            db.execute('PRAGMA user_version=6');db.commit()
        _,rejected=self.cli('overview','--revision',full['revision'],code=1)
        self.assertIn('GRAPH_SCHEMA_REBUILD_REQUIRED',rejected['message'])
        _,rebuilt=self.cli('index','--full',timeout=180)
        self.assertEqual(removed,self.canonical(rebuilt['revision']))

    def test_search_calls_source_and_external_resolution(self):
        _,publication=self.cli('index','--full',timeout=180)
        rows,_=self.cli('resolve','p.Service#greet(java.lang.String)')
        self.assertEqual(len(rows),1);method=rows[0]['id']
        calls,_=self.cli('calls',method);self.assertTrue(calls)
        source,_=self.cli('source',method);self.assertTrue(source)
        external,_=self.cli('resolve','java.lang.String#trim()');self.assertTrue(external)
        self.assertEqual(external[0]['kind'],'external_symbol')
        found,_=self.cli('resolve','p.Service');self.assertTrue(found)
        searched,_=self.cli('search-code','Service');self.assertTrue(searched)
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

    def test_native_heap_exhaustion_preserves_head_and_restarts_worker(self):
        source=self.project/'src/p/App.java'
        original=source.read_text()
        _,first=self.cli('index','--full',timeout=180)
        graph=self.canonical(first['revision'])
        config=self.project/'.code-ana/config.toml'
        previous=config.read_text()
        config.write_text(previous+'\n[resources.index]\njava_native_heap_bytes=8388608\n')
        source.write_text('package p; class App {' + ''.join(
            'int m%d(){return %d;}'%(i,i) for i in range(5000)) + '}')
        _,failure=self.cli('index','--full',code=1,timeout=180)
        self.assertIn('NATIVE_HEAP_EXHAUSTED',failure['message'])
        self.assertEqual(self.cli('overview')[1]['revision'],first['revision'])
        config.write_text(previous)
        source.write_text(original)
        _,recovered=self.cli('index','--full',timeout=180)
        self.assertEqual(graph,self.canonical(recovered['revision']))
        self.assertEqual(graph,self.canonical(first['revision']))

    def test_worker_cancel_crash_and_restart_preserves_graph(self):
        source=self.project/'src/p/App.java'
        original=source.read_text()
        # Existing regression observes real javac children before cancellation/crash.
        self.assert_cancel_and_crash_preserve_head()
        before=self.cli('overview')[1]['revision']
        graph=self.canonical(before)
        self.assertIsNone(self.java_worker_pid())
        source.write_text(original)
        _,recovered=self.cli('index','--full',timeout=180)
        self.assertIsNotNone(self.java_worker_pid())
        self.assertEqual(graph,self.canonical(recovered['revision']))
        self.assertEqual(graph,self.canonical(before))
        worker=self.java_worker_pid()
        _,next=self.cli('index','--full',timeout=180)
        self.assertEqual(worker,self.java_worker_pid())
        self.assertEqual(graph,self.canonical(next['revision']))

# Explicit suite avoids inheriting the legacy capability matrix.
def load_tests(loader,tests,pattern):
    names = [
        'test_maven_fallback_transitions_preserve_source_and_strict_head',
        'test_mixed_java_rust_delta_full_and_strict_preserve_both_languages',
        'test_graph_full_delta_delete_and_old_revision',
        'test_search_calls_source_and_external_resolution',
        'test_xml_resource_identity_and_incremental_equivalence',
        'test_unchanged_index_preserves_graph_and_fts',
        'test_worker_cancel_crash_and_restart_preserves_graph']
    if os.environ.get('CODEANA_JAVA_BACKEND', 'native') == 'native':
        names.append('test_native_heap_exhaustion_preserves_head_and_restarts_worker')
    return unittest.TestSuite(GraphStorage(name) for name in names)

if __name__=='__main__': unittest.main()
