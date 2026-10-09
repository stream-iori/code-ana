#!/usr/bin/env python3
"""Reject reference provenance drift without conflating a JAR with its database."""
from contextlib import closing
import json,pathlib,sqlite3,tempfile,unittest
from unittest.mock import patch
from java_reference_proof import validate_reference,COMMIT
from java_declaration_inventory_diff import digest

class ReferenceProofTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=pathlib.Path(self.tmp.name);self.project=self.root/'project';self.project.mkdir()
        (self.project/'src').mkdir();(self.project/'src/A.java').write_bytes(b'class A {}')
        (self.project/'src/Bad.java').write_bytes(b'\xff')
        (self.project/'resource.java').write_bytes(b'class Resource {}')
        self.jar=self.root/'ref.jar';self.jar.write_bytes(b'frozen jar')
        self.manifest=self.root/'inputs.json';self.manifest.write_text(json.dumps({'modules':[{'id':'.','source_roots':[{'path':'src','scope':'MAIN'}]}]}))
        self.maven=self.root/'maven.xml';self.maven.write_bytes(b'verified capture')
        self.db=self.root/'reference.db'
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('CREATE TABLE file_cache(source_file,hash,provider_id)')
            db.execute('INSERT INTO file_cache VALUES(?,?,?)',('src/A.java',digest(self.project/'src/A.java'),'java-core'))
            db.execute('INSERT INTO file_cache VALUES(?,?,?)',('beans.xml','xml-sha','java-core'))
        self.report=dict(kind='frozen_anatomist_database_generation',state='finished',exit_code=0,changed_inputs=[],commit=COMMIT,
            scopes=['MAIN','TEST'],xml=True,lombok='ast',reference_jar_sha256=digest(self.jar),database_sha256=digest(self.db),
            inputs_manifest_sha256=digest(self.manifest),maven_inputs_verified=True,maven_proofs={str(self.maven):digest(self.maven)},inputs={},
            source_sha256={str(p.relative_to(self.project)):digest(p) for p in self.project.rglob('*.java')},
            excluded_invalid_utf8={'src/Bad.java':digest(self.project/'src/Bad.java')})
        self.receipt=self.root/'receipt.json'
        mock=patch('java_reference_proof.REFERENCE_SHA',digest(self.jar));mock.start();self.addCleanup(mock.stop)
    def check(self):
        self.receipt.write_text(json.dumps(self.report))
        return validate_reference(self.jar,self.db,self.receipt,self.manifest,self.project)
    def test_configured_java_coverage_and_independent_database_digest(self):
        self.assertNotEqual(digest(self.jar),digest(self.db));self.check()
    def test_database_drift_and_missing_maven_proof_are_rejected(self):
        self.report['database_sha256']=digest(self.jar)
        with self.assertRaisesRegex(ValueError,'ARTIFACT_DRIFT'):self.check()
        self.report['database_sha256']=digest(self.db);self.report['maven_proofs']={}
        with self.assertRaisesRegex(ValueError,'MAVEN_PROOF_REQUIRED'):self.check()
    def test_valid_source_cannot_be_excluded(self):
        self.report['excluded_invalid_utf8']['src/A.java']=digest(self.project/'src/A.java')
        with self.assertRaisesRegex(ValueError,'EXCLUSION_NOT_INVALID'):self.check()
    def test_configured_missing_source_cannot_be_hidden(self):
        with closing(sqlite3.connect(self.db)) as db, db:db.execute('DELETE FROM file_cache')
        self.report['database_sha256']=digest(self.db)
        with self.assertRaisesRegex(ValueError,'SOURCE_COVERAGE'):self.check()
    def test_case_alias_requires_actual_filesystem_identity(self):
        alias=self.project/'src/a.java'
        if not alias.exists():self.skipTest('case sensitive filesystem')
        self.assertTrue(alias.samefile(self.project/'src/A.java'))
        with closing(sqlite3.connect(self.db)) as db, db:db.execute("UPDATE file_cache SET source_file='src/a.java' WHERE source_file='src/A.java'")
        self.report['database_sha256']=digest(self.db)
        self.assertEqual(self.check()['verified_source_coverage']['filesystem_identity_aliases'],{'src/a.java':'src/A.java'})
        with closing(sqlite3.connect(self.db)) as db, db:db.execute('INSERT INTO file_cache VALUES(?,?,?)',('src/A.java',digest(alias),'java-core'))
        self.report['database_sha256']=digest(self.db)
        with self.assertRaisesRegex(ValueError,'ALIAS_COLLISION'):self.check()

    def test_source_and_manifest_drift_are_rejected(self):
        (self.project/'src/A.java').write_bytes(b'class Other {}')
        with self.assertRaisesRegex(ValueError,'SOURCE_DRIFT'):self.check()
        self.manifest.write_text('{}')
        with self.assertRaisesRegex(ValueError,'MANIFEST_DRIFT'):self.check()

if __name__=='__main__':unittest.main()
