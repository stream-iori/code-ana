"""SDK formats reject cross-engine fields and legacy/defaulted identities."""
import copy
import json
import pathlib
import sys
import tempfile
import unittest
from sdk_fixtures import java_sdk

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from sdk_manifest import java, load_manifest, validate_manifest, VERSION


class SdkContract(unittest.TestCase):
    def test_schema_version_matches_rust_contract(self):
        self.assertIn(f'pub const SCHEMA_VERSION: u32 = {VERSION};',
                      (ROOT / 'crates/contracts/src/bundle.rs').read_text())

    def test_java_runtime_fields_are_explicit_and_not_shared_with_typescript(self):
        native = java_sdk({'native/lib.dylib': '0' * 64}, 'native')
        jvm = java_sdk({'runner.jar': '0' * 64}, 'jvm')
        self.assertEqual(java(native, 'native')['runtime']['abi'], 4)
        self.assertNotIn('abi', java(jvm, 'jvm')['runtime'])
        with self.assertRaisesRegex(ValueError, 'BACKEND_MISMATCH'):
            java(jvm, 'native')
        ts = copy.deepcopy(jvm)
        ts['backend'] = dict(kind='typescript', abi=1, analysis_request=1,
            upstream_commit='a' * 40, typescript_commit='d' * 40, compiler_version='7.0.0',
            analysis_proto_sha256='e' * 64, graph_proto_sha256='f' * 64,
            go_version='go1.26.2', build_options=['-buildmode=c-shared'],
            standard_library={'lib.d.ts': 'b' * 64}, header_sha256='c' * 64)
        validate_manifest(ts)
        for key in ('typescript_commit', 'compiler_version', 'analysis_proto_sha256', 'graph_proto_sha256'):
            invalid = copy.deepcopy(ts)
            del invalid['backend'][key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                validate_manifest(invalid)
            invalid = copy.deepcopy(ts)
            invalid['backend'][key] = ''
            with self.subTest(empty=key), self.assertRaises(ValueError):
                validate_manifest(invalid)
        ts['backend']['target_java'] = [8, 17]
        with self.assertRaisesRegex(ValueError, 'MANIFEST_INVALID'):
            validate_manifest(ts)

    def test_missing_fields_and_old_shapes_never_receive_defaults(self):
        current = java_sdk({'runner.jar': '0' * 64}, 'jvm')
        for field in current:
            invalid = copy.deepcopy(current)
            del invalid[field]
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_manifest(invalid)
        with self.assertRaises(ValueError):
            validate_manifest(dict(version=2, backend='jvm', files=current['files']))
        invalid = copy.deepcopy(current)
        invalid['backend']['runtime']['gc'] = 'G1'
        with self.assertRaises(ValueError):
            validate_manifest(invalid)

    def test_duplicate_json_keys_are_not_silently_overwritten(self):
        current = java_sdk({'runner.jar': '0' * 64}, 'jvm')
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'manifest.json'
            path.write_text(json.dumps(current).replace('"version": 4', '"version": 4, "version": 4'))
            with self.assertRaisesRegex(ValueError, 'DUPLICATE_FIELD'):
                load_manifest(path)
            path.write_text(json.dumps(current).replace('"version": 4', '"producer_metadata":{"x":1,"\\u0078":2},"version":4'))
            with self.assertRaisesRegex(ValueError, 'DUPLICATE_FIELD'):
                load_manifest(path)
            current['producer_metadata'] = {'values': [0] * 65536}
            path.write_text(json.dumps(current))
            with self.assertRaisesRegex(ValueError, 'SDK_JSON_BUDGET'):
                load_manifest(path)


if __name__ == '__main__':
    unittest.main()
