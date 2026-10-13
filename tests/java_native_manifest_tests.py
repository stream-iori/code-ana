"""Native bundle metadata only; these checks never load or accept native code."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]

class NativeManifest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        stage = pathlib.Path(self.temp.name)
        self.project = stage / 'lang-java-adapotr'
        for name in ['java-bridge/src/main/java/dev/codeana/adapter/AnalysisSession.java',
                     'analysis-core/src/main/java/com/anatomist/core/javac/JavacBackend.java',
                     'analysis-core/src/main/java/com/anatomist/core/javac/JavacBatchProtocol.java']:
            destination = self.project / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / 'lang-java-adapotr' / name, destination)
        destination = stage / 'crates/model/src/lib.rs'
        destination.parent.mkdir(parents=True)
        shutil.copy2(ROOT / 'crates/model/src/lib.rs', destination)
        self.target = self.project / 'target'
        (self.target / 'native').mkdir(parents=True)
        self.library = self.target / 'native' / ('libcodeana_java.dylib' if sys.platform == 'darwin' else 'libcodeana_java.so')
        for path in [self.library, self.target / 'compiler-bridge.jar', self.target / 'maven-input-worker.jar']:
            path.write_bytes(('fixture:' + path.name).encode())
        subprocess.run([sys.executable, str(ROOT / 'scripts/java-adapotr-manifest.py'), str(self.target)], check=True)
        self.manifest_path = self.target / 'native/manifest.json'
        self.manifest = json.loads(self.manifest_path.read_text())

    def probe(self):
        return subprocess.run([str(ROOT / 'target/debug/codeana-java-worker'), str(self.library), '--runtime-info'], capture_output=True, text=True, timeout=10)

    def test_shared_bridge_identity_and_all_distribution_resources(self):
        self.assertEqual(self.manifest['backend'], 'native')
        self.assertEqual(self.manifest['adapter'], 'anatomist-dff2e395-codeana-graph1')
        self.assertEqual(set(self.manifest['files']), {str(self.library.relative_to(self.target)), 'compiler-bridge.jar', 'maven-input-worker.jar'})
        for name, digest in self.manifest['files'].items():
            self.assertEqual(hashlib.sha256((self.target / name).read_bytes()).hexdigest(), digest)
        result = self.probe()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['worker_protocol'], self.manifest['worker_protocol'])

    def test_old_worker_policy_is_rejected_before_native_loading(self):
        self.manifest['worker_protocol'] -= 1
        self.manifest_path.write_text(json.dumps(self.manifest))
        result = self.probe()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BUNDLE_VERSION_MISMATCH', result.stderr)

    def test_maven_metadata_worker_checksum_is_required(self):
        (self.target / 'maven-input-worker.jar').write_bytes(b'changed')
        result = self.probe()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BUNDLE_HASH_MISMATCH', result.stderr)

if __name__ == '__main__':
    unittest.main()
