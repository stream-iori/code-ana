import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('gate',ROOT/'scripts/java-performance-candidate-gate.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


class CandidateGateTests(unittest.TestCase):
    def report(self,rows):
        return dict(status='passed',expected_count=2,executed_count=2,cases=rows,
                    missing=[],duplicate=[],unexpected=[],expected_duplicate=[],artifact_drift=[])

    def test_duplicate_and_replaced_case_cannot_pass_coverage(self):
        r=self.report([dict(id='a',status='passed'),dict(id='a',status='passed')])
        with self.assertRaisesRegex(ValueError,'COMPLETE_CASE_SET_REQUIRED'):gate.validate_cases(r,{'a','b'})

    def test_failed_or_skipped_case_cannot_authorize_performance(self):
        for status in ('failed','skipped','known_issue'):
            r=self.report([dict(id='a',status='passed'),dict(id='b',status=status)])
            with self.assertRaisesRegex(ValueError,'COMPLETE_CASE_SET_REQUIRED'):gate.validate_cases(r,{'a','b'})

    def test_proof_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            p=(Path(temp)/'proof').resolve();p.write_text('original');proof={str(p):gate.sha256(p)}
            gate.verify_inputs(proof);p.write_text('modified')
            with self.assertRaisesRegex(ValueError,'PROOF_DRIFT'):gate.verify_inputs(proof)

    def test_historical_build_cannot_replace_current_release(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp);manifest=base/'source.json';manifest.write_text(json.dumps(dict(files={})))
            build=base/'build.json';build.write_text(json.dumps(dict(status='passed',build_profile='release',source_manifest_sha256='old-source')))
            with self.assertRaisesRegex(ValueError,'CURRENT_RELEASE_BUILD_REQUIRED'):gate.validate({},manifest.resolve(),build.resolve())


if __name__=='__main__':unittest.main()
