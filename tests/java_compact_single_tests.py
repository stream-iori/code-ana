"""Gate receipts must describe current complete suites and unchanged inputs."""
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('compact_single',ROOT/'scripts/java-compact-storage-single.py')
single=importlib.util.module_from_spec(spec);spec.loader.exec_module(single)

class CurrentGates(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=pathlib.Path(self.tmp.name);self.source=self.root/'source';self.source.write_text('sealed')
        self.paths={}
        for name,count in [('graph',11),('lifecycle',9)]:
            p=self.root/(name+'.json');p.write_text(json.dumps(dict(status='passed',backend='native',group=name,
                complete_group_gate=True,executed_count=count,expected_count=count,total_group_cases=count,
                sources_and_artifacts={str(self.source):single.sha256(self.source)})));self.paths[name]=p
        p=self.root/'release.json';p.write_text(json.dumps(dict(all_ok=True,inputs={'source':single.sha256(self.source)})));self.paths['release']=p
    def verify(self):
        with patch.object(single,'ROOT',self.root):return single.verified_gates(**self.paths)
    def test_current_complete_counts_are_not_hardcoded(self):
        self.assertEqual(set(self.verify()),{'graph','lifecycle','release'})
    def test_partial_or_missing_cases_cannot_pass(self):
        for field,value in [('complete_group_gate',False),('executed_count',10),('missing',['case'])]:
            with self.subTest(field=field):
                p=self.paths['graph'];original=json.loads(p.read_text());changed=dict(original);changed[field]=value;p.write_text(json.dumps(changed))
                with self.assertRaisesRegex(ValueError,'CURRENT_COMPLETE_FIXTURE_GATE_REQUIRED'):self.verify()
                p.write_text(json.dumps(original))
    def test_changed_fixture_artifact_is_rejected(self):
        self.source.write_text('changed')
        with self.assertRaisesRegex(ValueError,'FIXTURE_GATE_ARTIFACT_DRIFT'):self.verify()
    def test_stale_release_sources_are_rejected(self):
        p=self.paths['release'];value=json.loads(p.read_text());value['inputs']['source']='0'*64;p.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError,'RELEASE_GATE_INPUT_DRIFT'):self.verify()

class QueryMultisets(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec=importlib.util.spec_from_file_location('performance_phase',ROOT/'scripts/java-performance-phase-single-v2.py')
        cls.phase=importlib.util.module_from_spec(spec);spec.loader.exec_module(cls.phase)
    def test_duplicate_records_and_source_coordinates_are_significant(self):
        row={'id':'public','span':{'start':1},'attributes':{'profile':'semantic'}}
        normalize=self.phase.canonical_query_records
        self.assertNotEqual(normalize([row,row],'resolve'),normalize([row],'resolve'))
        self.assertNotEqual(normalize([row],'resolve'),normalize([dict(row,span={'start':2})],'resolve'))
        self.assertNotEqual(normalize([row],'resolve'),normalize([dict(row,attributes={'profile':'changed'})],'resolve'))
    def test_only_overview_host_profile_is_execution_specific(self):
        normalize=self.phase.canonical_query_records
        self.assertEqual(normalize([{'profile':'jvm','counts':{'class':2}}],'overview'),normalize([{'profile':'native','counts':{'class':2}}],'overview'))
        self.assertNotEqual(normalize([{'profile':'jvm'}],'resolve'),normalize([{'profile':'native'}],'resolve'))


if __name__=='__main__':unittest.main()
