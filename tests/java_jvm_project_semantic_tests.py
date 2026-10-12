"""Negative controls for the fresh JVM published-graph gate and known issue."""
import importlib.util
import json
import pathlib
import tempfile
import unittest
import copy

from acceptance_support import sha256

ROOT = pathlib.Path(__file__).resolve().parents[1]


def module(name, script):
    spec = importlib.util.spec_from_file_location(name, ROOT/'scripts'/script)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


gate = module('jvm_project_semantics', 'java-jvm-project-semantics.py')
issue = module('constructor_identity_issue', 'java-constructor-identity-issue.py')
inventory = module('jvm_model_inventory', 'java-jvm-model-gate.py')


class SemanticGateProof(unittest.TestCase):
    def test_model_inventory_rejects_missing_duplicate_false_green_and_signal_cases(self):
        categories = {name:dict(encoding='sorted-row-sha256-v2-graph-captures',rows=0,sha256='0'*64)
            for name in inventory.models.CATEGORIES}
        case = dict(id='raw:module@MAIN',status='passed',differences=[],
            results=dict(reference=copy.deepcopy(categories),migrated=copy.deepcopy(categories)),
            executions={label:dict(status='passed',exit_code=0,unexpected_signal=None)
                for label in ['reference','migrated']})
        reports = [dict(cases=[case])]; known = dict(collector_selected_cases={})
        self.assertEqual(inventory.check_cases(reports,[case['id']],known),[case])
        for values in [[],[case,case]]:
            with self.assertRaisesRegex(ValueError,'MODEL_CASE_COVERAGE'):
                inventory.check_cases([dict(cases=values)],[case['id']],known)
        changed = copy.deepcopy(case)
        changed['results']['migrated']['raw.syntax']['rows']=1
        changed['differences']=['raw.syntax']
        with self.assertRaisesRegex(ValueError,'MODEL_FALSE_GREEN'):
            inventory.check_cases([dict(cases=[changed])],[case['id']],known)
        changed = copy.deepcopy(case)
        changed['executions']['migrated']['unexpected_signal']='SIGKILL'
        with self.assertRaisesRegex(ValueError,'MODEL_EXECUTION_NOT_ACCEPTED'):
            inventory.check_cases([dict(cases=[changed])],[case['id']],known)
        changed = copy.deepcopy(case); changed['status']='failed'
        with self.assertRaisesRegex(ValueError,'UNEXPLAINED_MODEL_FAILURE'):
            inventory.check_cases([dict(cases=[changed])],[case['id']],known)
    def test_publication_gate_rejects_incomplete_runtime_and_input_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manifest = root/'inputs.json'; manifest.write_text('{}')
            runtime = root/'runtime'; runtime.mkdir()
            binary = runtime/'worker'; binary.write_bytes(b'captured worker')
            receipt = dict(backend='jvm', state='finished', publication_accepted=True,
                unexpected_signal_observed=False, changed_artifacts=[], changed_source_inputs=[],
                inputs_manifest_changed=False, config_restored=True,
                inputs_manifest_sha256=sha256(manifest), artifacts={'worker':sha256(binary)})
            self.assertTrue(gate.checked_publication(receipt, runtime, manifest))
            native = dict(receipt, backend='native')
            self.assertTrue(gate.checked_publication(native, runtime, manifest, 'native'))
            with self.assertRaises(ValueError):
                gate.checked_publication(receipt, runtime, manifest, 'native')
            for key, value in [('state','running'),('publication_accepted',False),
                    ('backend','native'),('config_restored',False),
                    ('changed_source_inputs',['Source.java']),('unexpected_signal_observed',True),
                    ('artifacts',{})]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    gate.checked_publication(dict(receipt, **{key:value}),runtime,manifest)
            binary.write_bytes(b'other worker')
            with self.assertRaisesRegex(ValueError,'PUBLICATION_RUNTIME_DRIFT'):
                gate.checked_publication(receipt,runtime,manifest)
            manifest.write_text('{"changed":true}')
            with self.assertRaisesRegex(ValueError,'PUBLICATION_MANIFEST_DRIFT'):
                gate.checked_publication(receipt,runtime,manifest)

    def test_known_constructor_issue_rejects_any_additional_difference(self):
        for domain, field in issue.FIELDS.items():
            before = {field:issue.OLD,'sourceFile':issue.SOURCE,'kind':'CONSTRUCTOR'}
            if domain=='facts.edges': before.update(relation='CONTAINS',sourceId=issue.TYPE)
            after = dict(before, **{field:issue.NEW})
            encode = lambda value: json.dumps(value,sort_keys=True)
            self.assertEqual(issue.compare_rows(domain,[encode(before)],[encode(after)])['field'],field)
            with self.subTest(domain=domain, extra='field'), self.assertRaisesRegex(ValueError,'ADDITIONAL_FIELD'):
                issue.compare_rows(domain,[encode(before)],[encode(dict(after,beginLine=1))])
            with self.subTest(domain=domain, extra='occurrence'), self.assertRaisesRegex(ValueError,'OCCURRENCE_COVERAGE'):
                issue.compare_rows(domain,[encode(before),encode(before)],[encode(after)])
            with self.subTest(domain=domain, extra='identity'), self.assertRaisesRegex(ValueError,'ISSUE_IDENTITY'):
                issue.compare_rows(domain,[encode(before)],[encode(dict(after,**{field:'unrelated'}))])


if __name__ == '__main__':
    unittest.main(verbosity=2)
