import copy
import importlib.util
from pathlib import Path
import unittest
import tempfile
import json
import hashlib

spec=importlib.util.spec_from_file_location('single_compare',Path(__file__).resolve().parents[1]/'scripts/java-performance-compare-single.py')
compare=importlib.util.module_from_spec(spec);spec.loader.exec_module(compare)
spec=importlib.util.spec_from_file_location('single_runner',Path(__file__).resolve().parents[1]/'scripts/java-performance-single.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)


def receipt(native=100,jvm=100):
    rows=[]
    for backend,seconds in [('native',native),('jvm',jvm)]:
        for temperature in compare.TEMPERATURES:
            rows.append(dict(backend=backend,temperature=temperature,index_seconds=seconds,monitor=dict(status='passed',foreign_analyzers=[]),phase=dict(status='passed',graph=dict(facts=100,checksum='same'),queries=[dict(id=str(i),seconds=1,records=1) for i in range(6)])))
    return dict(status='passed',build_profile='release',rounds_per_backend=1,budget=dict(memory=100),rounds=rows)


class SingleRoundTests(unittest.TestCase):
    def test_archived_correctness_requires_exact_original_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'live';root.mkdir()
            source=root/'source.rs';source.write_text('accepted source')
            digest=hashlib.sha256(source.read_bytes()).hexdigest()
            archived=Path(directory)/'archive';archived.mkdir()
            (archived/'source.rs').write_bytes(source.read_bytes())
            receipt=Path(directory)/'receipt.json'
            receipt.write_text(json.dumps(dict(status='passed',native_accepted=True,unexplained_failures=[],inputs={str(source):digest})))
            original=runner.ROOT
            try:
                runner.ROOT=root
                source.write_text('candidate changes')
                with self.assertRaisesRegex(ValueError,'CORRECTNESS_PROOF_DRIFT'): runner.accepted(receipt,'native_accepted')
                self.assertTrue(runner.accepted(receipt,'native_accepted',archived)['native_accepted'])
                (archived/'source.rs').write_text('archive tampered')
                with self.assertRaisesRegex(ValueError,'CORRECTNESS_PROOF_DRIFT'): runner.accepted(receipt,'native_accepted',archived)
            finally: runner.ROOT=original

    def test_both_native_temperatures_must_halve(self):
        self.assertTrue(compare.compare(receipt(),receipt(native=50))['optimization_accepted'])
        candidate=receipt(native=50);candidate['rounds'][1]['index_seconds']=50.1
        self.assertFalse(compare.compare(receipt(),candidate)['optimization_accepted'])

    def test_jvm_and_queries_have_independent_regression_gates(self):
        self.assertFalse(compare.compare(receipt(),receipt(native=40,jvm=106))['optimization_accepted'])
        candidate=receipt(native=40);candidate['rounds'][0]['phase']['queries'][0]['seconds']=1.06
        self.assertFalse(compare.compare(receipt(),candidate)['optimization_accepted'])

    def test_incomplete_duplicate_foreign_or_drifting_runs_are_rejected(self):
        baseline=receipt()
        variants=[]
        missing=receipt(native=40);missing['rounds'].pop();variants.append(missing)
        duplicate=receipt(native=40);duplicate['rounds'][1]=copy.deepcopy(duplicate['rounds'][0]);variants.append(duplicate)
        drift=receipt(native=40);drift['budget']['memory']=200;variants.append(drift)
        drift=receipt(native=40);drift['rounds'][0]['phase']['graph']['checksum']='changed';variants.append(drift)
        foreign=receipt(native=40);foreign['rounds'][0]['monitor']['foreign_analyzers']=['worker'];variants.append(foreign)
        for candidate in variants:
            with self.subTest(candidate=candidate),self.assertRaises(ValueError): compare.compare(baseline,candidate)

if __name__=='__main__':unittest.main()
