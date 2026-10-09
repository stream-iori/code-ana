import copy
import json
import pathlib
import tempfile
import unittest
from java_layered_acceptance import digest, model_progress, semantic_progress

class LayeredEvidence(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=pathlib.Path(self.temp.name)
        raw=['raw.syntax','raw.bindings','raw.types','raw.declarations','raw.extensions','raw.source_hash','raw.diagnostics']
        extracted=['facts.nodes','facts.edges','facts.annotations','facts.annotationMetaRelations','facts.semanticAnnotations','facts.declarations','facts.unresolvedCallSites']
        value={name:dict(encoding='sorted-row-sha256-v2-graph-captures',rows=1,sha256='1'*64) for name in raw+extracted}
        proofs={}
        for name in ('reference','migrated_jvm','migrated_native'):
            model=copy.deepcopy(value)
            if name=='reference':model['facts.nodes']['sha256']='2'*64
            path=self.root/(name+'.json');path.write_text(json.dumps(model));proofs[str(path)]=digest(path)
        self.row=dict(mode='raw',context='app@MAIN',accepted=False,evidence=str(self.root),proofs=proofs,source_count=1,published_source_count=1)
        self.report=dict(state='running',expected_contexts=['app@MAIN'],contexts=[self.row])
    def test_raw_equivalence_does_not_promote_failed_extraction(self):
        report=model_progress(self.report)
        self.assertEqual((report['collected'],report['strict_passes'],report['raw_model_passes'],report['remaining']),(1,0,1,1))
        self.assertFalse(report['complete_model_api_inventory'])
    def test_duplicate_context_rejected(self):
        self.report['contexts'].append(copy.deepcopy(self.row))
        with self.assertRaisesRegex(ValueError,'CONTEXT_IDENTITY'):model_progress(self.report)
    def test_changed_proof_is_rejected_even_for_failed_context(self):
        (self.root/'migrated_native.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'PROOF_DRIFT'):model_progress(self.report)
    def test_backend_logs_are_sealed_without_becoming_model_json(self):
        path=self.root/'reference.log';path.write_text('original backend diagnostic')
        self.row['proofs'][str(path)]=digest(path)
        self.assertEqual(model_progress(self.report)['raw_model_passes'],1)
        path.write_text('changed diagnostic')
        with self.assertRaisesRegex(ValueError,'PROOF_DRIFT'):model_progress(self.report)
    def test_semantic_output_drift_cannot_appear_as_a_pass(self):
        output=self.root/'domain.json';output.write_text('{}');log=self.root/'domain.log';log.write_text('ok')
        domain=dict(name='xml',accepted=True,output=str(output),output_sha256=digest(output),log=str(log),log_sha256=digest(log))
        report=dict(state='finished',domains=[domain])
        self.assertEqual(semantic_progress(report)['passed'],1)
        output.write_text('{"corrupted":true}')
        with self.assertRaisesRegex(ValueError,'PROOF_DRIFT'):semantic_progress(report)

if __name__=='__main__':unittest.main()
