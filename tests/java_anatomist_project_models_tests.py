#!/usr/bin/env python3
"""Only complete, unchanged model evidence can be resumed."""
import copy,json,pathlib,tempfile,unittest
from java_anatomist_project_models import verified_resume_rows,digest,model_result_summary,lombok_resources

class LombokCapture(unittest.TestCase):
    def test_ancestor_controls_exclude_unrelated_modules(self):
        with tempfile.TemporaryDirectory() as folder:
            parent=pathlib.Path(folder);root=parent/'project';directory=root/'module/src';directory.mkdir(parents=True)
            source=directory/'A.java';source.write_text('class A {}')
            for path in [parent/'lombok.config',root/'lombok.config',directory/'lombok.config']:
                path.write_text('lombok.addJavaxGeneratedAnnotation=true')
            unrelated=root/'other';unrelated.mkdir();(unrelated/'lombok.config').write_text('unrelated=true')
            rows=lombok_resources(root,[{'input':str(source)}]);units={r['unit']['path']:r['unit'] for r in rows}
            self.assertEqual(set(units),{'lombok.config','module/src/lombok.config','.code-ana/lombok-ancestors/000001/lombok.config'})
            for row in rows:
                self.assertEqual(row['unit']['hash'],digest(pathlib.Path(row['input'])))
                self.assertEqual((row['unit']['module'],row['unit']['world'],row['unit']['scope']),('','files','JAVA_CONFIG'))

    def test_outside_source_and_oversized_config_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=pathlib.Path(folder)/'project';root.mkdir()
            with self.assertRaisesRegex(ValueError,'SOURCE_OUTSIDE'):
                lombok_resources(root,[{'input':str(root.parent/'A.java')}])
            (root/'lombok.config').write_bytes(b'#'*(1024*1024+1))
            with self.assertRaisesRegex(ValueError,'BYTE_BUDGET'):
                lombok_resources(root,[{'input':str(root/'A.java')}])

class ModelResume(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=pathlib.Path(self.tmp.name)
        self.current=dict(kind='models',frozen_project='frozen',reference_sha256='jar',inputs={'jar':'sha'},source_inputs={'source':'sha'},
            inputs_manifest_sha256='manifest',native_shared_library_sha256='library',compiler_bridge_sha256='bridge',
            signature_cache_policy='shared',expected_contexts=['app@MAIN'],run_options={'mode':'both','context':None,'row_evidence':True})
        domains=['raw.syntax','raw.bindings','raw.types','raw.declarations','raw.extensions','raw.source_hash','raw.diagnostics',
            'facts.nodes','facts.edges','facts.annotations','facts.annotationMetaRelations','facts.semanticAnnotations','facts.declarations','facts.unresolvedCallSites']
        model={d:dict(encoding='sorted-row-sha256-v2-graph-captures',rows=0,sha256='0'*64) for d in domains}
        payloads={'reference.json':model,'migrated_jvm.json':model,'migrated_native.json':model,
            'native-request.json':{},'context.json':{'contexts':[{'module':'app','scope':'MAIN','sources':[{'publish':True}]}]}}
        for name,value in payloads.items():(self.root/name).write_text(json.dumps(value))
        self.row=dict(mode='raw',context='app@MAIN',accepted=True,differences={'migrated_jvm':[],'migrated_native':[]},
            source_count=1,published_source_count=1,proofs={str(self.root/n):digest(self.root/n) for n in payloads})
        self.previous=copy.deepcopy(self.current);self.previous['contexts']=[self.row]
    def test_only_verified_passes_resume(self):
        self.previous['contexts'].append(dict(accepted=False))
        self.assertEqual(verified_resume_rows(self.previous,self.current),[self.row])
    def test_runtime_and_source_drift_rejected(self):
        for key in ('inputs','source_inputs','native_shared_library_sha256','run_options','configuration_inputs'):
            previous=copy.deepcopy(self.previous);previous[key]='changed'
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'INPUT_DRIFT'):verified_resume_rows(previous,self.current)
    def test_missing_or_modified_proof_rejected(self):
        missing=copy.deepcopy(self.previous);missing['contexts'][0]['proofs'].pop(str(self.root/'native-request.json'))
        with self.assertRaisesRegex(ValueError,'PROOF_MISSING'):verified_resume_rows(missing,self.current)
        (self.root/'reference.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'PROOF_DRIFT'):verified_resume_rows(self.previous,self.current)
    def test_duplicate_context_and_incomplete_categories_rejected(self):
        previous=copy.deepcopy(self.previous);previous['contexts']*=2
        with self.assertRaisesRegex(ValueError,'CONTEXT_IDENTITY'):verified_resume_rows(previous,self.current)
        for name in ('reference.json','migrated_jvm.json','migrated_native.json'):
            p=self.root/name;p.write_text('{}');self.row['proofs'][str(p)]=digest(p)
        with self.assertRaisesRegex(ValueError,'CATEGORY_PROOF'):verified_resume_rows(self.previous,self.current)
    def test_coverage_counts_cannot_be_forged(self):
        self.row['published_source_count']=0
        with self.assertRaisesRegex(ValueError,'SOURCE_COVERAGE'):verified_resume_rows(self.previous,self.current)
class IndependentBackendEvidence(unittest.TestCase):
    def test_reference_failure_cannot_become_acceptance(self):
        row=model_result_summary({'migrated_jvm':{'raw.types':'same'},'migrated_native':{'raw.types':'same'}})
        self.assertTrue(row['migrated_backends_equal'])
        self.assertFalse(row['comparison_available'])
        self.assertFalse(row['accepted'])
    def test_native_loss_and_category_loss_are_visible(self):
        row=model_result_summary({'reference':{'raw.types':'a','raw.bindings':'b'},
            'migrated_jvm':{'raw.types':'a','raw.bindings':'b'},'migrated_native':{'raw.types':'changed'}})
        self.assertEqual(row['differences']['migrated_native'],['raw.bindings','raw.types'])
        self.assertFalse(row['accepted'])
        self.assertFalse(row['migrated_backends_equal'])
    def test_extraction_defect_does_not_hide_or_invent_raw_model_proof(self):
        names = ['raw.syntax','raw.bindings','raw.types','raw.declarations','raw.extensions','raw.source_hash','raw.diagnostics']
        raw = {name:'same' for name in names}
        row = model_result_summary({'reference':dict(raw, **{'facts.nodes':'old'}),
            'migrated_jvm':dict(raw, **{'facts.nodes':'fixed'}), 'migrated_native':dict(raw, **{'facts.nodes':'fixed'})})
        self.assertTrue(row['raw_models_equal']);self.assertFalse(row['accepted'])
        del raw['raw.bindings']
        row = model_result_summary({name:dict(raw) for name in ('reference','migrated_jvm','migrated_native')})
        self.assertFalse(row['raw_model_comparison_available'])
        self.assertFalse(row['raw_models_equal'])
    def test_missing_migrated_backend_has_no_equivalence_proof(self):
        row=model_result_summary({'reference':{'raw.types':'a'},'migrated_jvm':{'raw.types':'a'}})
        self.assertFalse(row['migrated_backend_comparison_available'])
        self.assertFalse(row['accepted'])

if __name__=='__main__':unittest.main()
