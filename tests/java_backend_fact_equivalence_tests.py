import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('equivalence', ROOT / 'scripts/java-backend-fact-equivalence.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PublicationIdentityTests(unittest.TestCase):
    def test_wrong_backend_partial_drift_and_missing_manifest_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = {backend: pathlib.Path(temporary) / (backend + '.json') for backend in ['jvm', 'native']}
            baseline = dict(state='finished', publication_accepted=True,
                            unexpected_signal_observed=False, changed_artifacts=[],
                            changed_source_inputs=[], inputs_manifest_sha256='1' * 64)
            for backend, path in paths.items():
                path.write_text(json.dumps(dict(baseline, backend=backend)))
            gate.publications(paths)
            for change in [dict(backend='jvm'), dict(state='running'), dict(changed_artifacts=['lib']),
                           dict(changed_source_inputs=['A.java']), dict(inputs_manifest_sha256=None),
                           dict(inputs_manifest_sha256='2' * 64), dict(unexpected_signal_observed=True)]:
                paths['native'].write_text(json.dumps(dict(baseline, backend='native', **change))) if 'backend' not in change else paths['native'].write_text(json.dumps(dict(baseline, **change)))
                with self.assertRaises(ValueError):
                    gate.publications(paths)


if __name__ == '__main__':
    unittest.main(verbosity=2)
