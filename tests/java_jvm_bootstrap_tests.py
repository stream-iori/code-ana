"""Contract tests for the independent JVM distribution (never build native)."""
import hashlib
import io
import json
import pathlib
import struct
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUNDLE = ROOT / 'lang-java-adaptor/target/jvm'


class JvmBundleTests(unittest.TestCase):
    def test_manifest_binds_current_jvm_artifacts(self):
        manifest = json.loads((BUNDLE / 'manifest.json').read_text())
        self.assertEqual(manifest['backend'], 'jvm')
        self.assertEqual(manifest['analysis_request'], 10)
        self.assertEqual(manifest['target_java'], [8])
        self.assertIn('runner.jar', manifest['files'])
        self.assertIn('compiler-bridge.jar', manifest['files'])
        for name, expected in manifest['files'].items():
            path = BUNDLE / name
            self.assertTrue(path.resolve().is_relative_to(BUNDLE.resolve()))
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected)

    def test_packaged_runner_contains_java8_worker_without_native_api(self):
        with zipfile.ZipFile(BUNDLE / 'runner.jar') as runner:
            names = runner.namelist()
            self.assertIn('dev/codeana/runner/JvmRunner.class', names)
            self.assertFalse(any(name.startswith('org/graalvm/') for name in names))
            self.assertFalse(any(name.endswith('/NativeBridge.class') for name in names))
            with zipfile.ZipFile(io.BytesIO(runner.read('META-INF/anatomist/javac-worker.jar'))) as worker:
                classes = [name for name in worker.namelist() if name.endswith('.class')]
                self.assertTrue(classes)
                for name in classes:
                    self.assertEqual(struct.unpack('>H', worker.read(name)[6:8])[0], 52, name)


if __name__ == '__main__':
    unittest.main()
