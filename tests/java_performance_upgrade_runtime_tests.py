import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('upgrade_runtime', ROOT / 'scripts/java-performance-upgrade-runtime.py')
upgrade = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upgrade)


class UpgradeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.binaries = self.root / 'build/release'
        self.binaries.mkdir(parents=True)
        self.bundle = self.root / 'bundle'
        self.source = self.bundle / 'lang-java-adapotr/target/jvm'
        self.source.mkdir(parents=True)
        self.installed = self.root / 'build/lib/java/jvm'
        self.installed.mkdir(parents=True)
        self.stable = self.root / 'build/runtime'
        self.stable.mkdir()
        for name in ('codeana-cli', 'codeana-daemon', 'codeana-java-worker'):
            (self.binaries / name).write_text(name)
        files = ('runner.jar', 'compiler-bridge.jar', 'maven-input-worker.jar')
        for name in files:
            (self.source / name).write_text('new-' + name)
            (self.installed / name).write_text('old-' + name)
        for directory in (self.source, self.installed):
            manifest = dict(worker_protocol=18, fact_ir=2, analysis_request=9, abi=None, adapter='example', files={name: upgrade.sha256(directory / name) for name in files})
            (directory / 'manifest.json').write_text(json.dumps(manifest))
        self.previous = upgrade.sha256(self.installed / 'manifest.json')
        self.versions = dict(backend='jvm', worker_protocol=18, fact_ir=2, adapter='example', analysis_request=9, abi=None)
        self.metadata = self.stable / 'runtime-jvm.json'
        value = dict(binary_directory=str(self.binaries), versions=self.versions, stable_runtime=str(self.stable), relocated_binaries=False,
                     installed_files={str(self.installed / name): upgrade.sha256(self.installed / name) for name in (*files, 'manifest.json')})
        self.metadata.write_text(json.dumps(value, indent=2) + '\n')
        self.before = {p: p.read_bytes() for p in [*self.installed.iterdir(), self.metadata, *self.binaries.iterdir()]}

    def run_command(self, command, **kwargs):
        return subprocess.CompletedProcess(command, 0, '' if command[0] == 'ps' else json.dumps(self.versions), '')

    def promote(self):
        return upgrade.upgrade(self.binaries, self.bundle, 'jvm', self.root / 'archive', self.previous)

    def test_upgrade_archives_old_bytes_and_preserves_application_paths_and_bytes(self):
        with patch.object(upgrade.subprocess, 'run', side_effect=self.run_command):
            result = self.promote()
        self.assertEqual('passed', result['status'])
        self.assertFalse(result['relocated_binaries'])
        for path, body in self.before.items():
            if path.parent == self.binaries:
                self.assertEqual(body, path.read_bytes())
            else:
                self.assertEqual(body, Path(result['old_files'][str(path)]['archive']).read_bytes())
        self.assertEqual((self.source / 'runner.jar').read_bytes(), (self.installed / 'runner.jar').read_bytes())

    def test_failed_replacement_restores_complete_old_runtime(self):
        original = upgrade.atomic_copy
        attempts = 0

        def failing_copy(source, destination):
            nonlocal attempts
            attempts += 1
            if attempts == 2:
                raise OSError('injected replacement failure')
            return original(source, destination)

        with patch.object(upgrade.subprocess, 'run', side_effect=self.run_command), patch.object(upgrade, 'atomic_copy', side_effect=failing_copy):
            with self.assertRaises(OSError):
                self.promote()
        for path, body in self.before.items():
            self.assertEqual(body, path.read_bytes())
        receipt = json.loads((self.root / 'archive/receipt.json').read_text())
        self.assertTrue(receipt['rolled_back'])

    def test_running_application_prevents_mutation(self):
        active = subprocess.CompletedProcess([], 0, '123 ' + str(self.binaries / 'codeana-daemon') + '\n', '')
        with patch.object(upgrade.subprocess, 'run', return_value=active):
            with self.assertRaisesRegex(ValueError, 'STOP_RUNTIME_BEFORE_UPGRADE'):
                self.promote()
        self.assertFalse((self.root / 'archive').exists())
        for path, body in self.before.items():
            self.assertEqual(body, path.read_bytes())


if __name__ == '__main__':
    unittest.main()
