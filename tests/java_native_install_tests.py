"""SDK installation preserves executable bytes and rolls back failed replacement."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from java_product_runtime import install_jvm, sha

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('native_install', ROOT/'scripts/install-java-native.py')
install = importlib.util.module_from_spec(spec)
spec.loader.exec_module(install)


class NativeInstallation(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source = self.root/'lang-java-adapotr/target'
        self.target = self.root/'target/lib/java'
        self.bin = self.root/'target/debug'
        self.bin.mkdir(parents=True)
        for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):
            (self.bin/name).write_bytes(b'original application')
        library = 'native/libcodeana_java.' + ('dylib' if install.os.uname().sysname=='Darwin' else 'so')
        self.names = [library,'compiler-bridge.jar','maven-input-worker.jar']
        self.versions = dict(backend='native',worker_protocol=18,fact_ir=2,adapter='fixture',analysis_request=9,abi=4)
        for directory, prefix, gc in ((self.source,'new-','G1'),(self.target,'old-','serial')):
            for name in self.names:
                p=directory/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(prefix+name)
            manifest=dict(self.versions,native_gc=gc,files={name:install.sha(directory/name) for name in self.names})
            (directory/'native/manifest.json').write_text(json.dumps(manifest))
        self.before = {p:p.read_bytes() for p in self.target.rglob('*') if p.is_file()}
        self.apps = {p:p.read_bytes() for p in self.bin.iterdir()}
        mock=patch.object(install.subprocess,'run',return_value=subprocess.CompletedProcess([],0,json.dumps(self.versions),''))
        mock.start();self.addCleanup(mock.stop)

    def test_g1_install_archives_serial_and_preserves_application_paths(self):
        with patch.object(install.subprocess,'check_output',return_value=''):
            result=install.install(self.root,self.root/'archive')
        self.assertEqual(result['gc'],'G1')
        for p,body in self.before.items():
            self.assertEqual((self.root/'archive'/p.relative_to(self.root/'target')).read_bytes(),body)
        self.assertEqual(self.apps,{p:p.read_bytes() for p in self.apps})
        for name in self.names:self.assertEqual((self.target/name).read_bytes(),(self.source/name).read_bytes())

    def test_running_worker_prevents_installation(self):
        with patch.object(install.subprocess,'check_output',return_value='42 '+str(self.bin/'codeana-java-worker')):
            with self.assertRaisesRegex(ValueError,'STOP_RUNTIME_BEFORE_NATIVE_INSTALL'):
                install.install(self.root,self.root/'archive')
        self.assertEqual(self.before,{p:p.read_bytes() for p in self.before})
        self.assertFalse((self.root/'archive').exists())

    def test_partial_write_failure_restores_complete_old_bundle(self):
        original=install.replace;calls=0
        def failing(source,destination):
            nonlocal calls
            calls+=1
            if calls==2:raise OSError('injected write failure')
            original(source,destination)
        with patch.object(install.subprocess,'check_output',return_value=''),patch.object(install,'replace',side_effect=failing):
            with self.assertRaises(OSError):install.install(self.root,self.root/'archive')
        self.assertEqual(self.before,{p:p.read_bytes() for p in self.before})
        self.assertEqual(self.apps,{p:p.read_bytes() for p in self.apps})


class JvmInstallation(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source = self.root/'lang-java-adapotr/target/jvm'
        self.target = self.root/'target/lib/java/jvm'
        self.archive = self.root/'archive'
        self.source.mkdir(parents=True)
        self.target.mkdir(parents=True)
        (self.source/'runner.jar').write_bytes(b'new JVM')
        (self.source/'manifest.json').write_text(json.dumps(dict(
            backend='jvm', files={'runner.jar': sha(self.source/'runner.jar')})))
        (self.target/'runner.jar').write_bytes(b'old JVM')
        self.bin = self.root/'target/debug'
        self.bin.mkdir()
        (self.bin/'codeana-java-worker').write_bytes(b'original application')
        (self.target.parent/'compiler-bridge.jar').write_bytes(b'Native bridge')

    def test_install_archives_old_jvm_and_keeps_native_and_executable_bytes(self):
        with patch('java_product_runtime.subprocess.check_output', return_value=''):
            install_jvm(self.root, self.source, self.archive)
        self.assertEqual((self.archive/'previous-jvm/runner.jar').read_bytes(), b'old JVM')
        self.assertEqual((self.target/'runner.jar').read_bytes(), b'new JVM')
        self.assertEqual((self.bin/'codeana-java-worker').read_bytes(), b'original application')
        self.assertEqual((self.target.parent/'compiler-bridge.jar').read_bytes(), b'Native bridge')
        install_jvm(self.root, self.source, self.archive)  # Unchanged bundles need no replacement.

    def test_running_worker_prevents_replacement(self):
        with patch('java_product_runtime.subprocess.check_output',
                   return_value='42 '+str(self.bin/'codeana-java-worker')):
            with self.assertRaisesRegex(ValueError, 'STOP_RUNTIME_BEFORE_JVM_INSTALL'):
                install_jvm(self.root, self.source, self.archive)
        self.assertEqual((self.target/'runner.jar').read_bytes(), b'old JVM')
        self.assertFalse(self.archive.exists())

    def test_failed_directory_swap_restores_previous_bundle(self):
        rename = Path.rename
        def failing(path, destination):
            if path == self.archive/'new-jvm':
                raise OSError('injected install failure')
            return rename(path, destination)
        with patch('java_product_runtime.subprocess.check_output', return_value=''), \
             patch.object(Path, 'rename', failing):
            with self.assertRaises(OSError):
                install_jvm(self.root, self.source, self.archive)
        self.assertEqual((self.target/'runner.jar').read_bytes(), b'old JVM')

    def test_corrupt_build_is_rejected_before_modifying_installation(self):
        (self.source/'runner.jar').write_bytes(b'drift')
        with self.assertRaisesRegex(ValueError, 'JAVA_RUNTIME_BUILD_CHECKSUM'):
            install_jvm(self.root, self.source, self.archive)
        self.assertEqual((self.target/'runner.jar').read_bytes(), b'old JVM')
        self.assertFalse(self.archive.exists())


if __name__=='__main__':unittest.main()
