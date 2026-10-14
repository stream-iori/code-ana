import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('stable',ROOT/'scripts/java-performance-stable-runtime.py')
stable=importlib.util.module_from_spec(spec);spec.loader.exec_module(stable)


class StableRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.base=Path(self.temp.name).resolve();self.root=self.base/'facade';self.bins=self.base/'build/release'
        self.bins.mkdir(parents=True);(self.root/'target').mkdir(parents=True)
        (self.root/'target/debug').symlink_to(self.bins,target_is_directory=True)
        for name in ('codeana-cli','codeana-daemon','codeana-java-worker'):(self.bins/name).write_text(name)
        for backend in ('native','jvm'):
            source=self.root/'lang-java-adapotr/target';source=source/'jvm' if backend=='jvm' else source
            runtime='runner.jar' if backend=='jvm' else 'native/libcodeana_java.'+('dylib' if stable.sys.platform=='darwin' else 'so')
            files={}
            for name in (runtime,'compiler-bridge.jar','maven-input-worker.jar'):
                p=source/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(backend+name);files[name]=stable.sha256(p)
            manifest=source/('manifest.json' if backend=='jvm' else 'native/manifest.json')
            manifest.write_text(json.dumps(dict(files=files,worker_protocol=19,adapter='test',abi=4 if backend=='native' else None,fact_ir=2,analysis_request=10,native_gc='G1' if backend=='native' else None)))
        def probe(command,**kwargs):
            backend=command[command.index('--backend')+1]
            info=dict(backend=backend,worker_protocol=19,fact_ir=2,adapter='test',analysis_request=10)
            info['abi']=4 if backend=='native' else None
            self.assertEqual(Path(command[0]),self.bins/'codeana-java-worker')
            return SimpleNamespace(returncode=0,stdout=json.dumps(info),stderr='')
        mock=patch.object(stable.subprocess,'run',side_effect=probe);mock.start();self.addCleanup(mock.stop)

    def test_backend_and_evidence_changes_keep_application_paths_and_native_metadata(self):
        before={p.name:stable.sha256(p) for p in self.bins.iterdir()}
        first=stable.prepare(self.root,self.base/'evidence-one','native')
        stable.prepare(self.root,self.base/'evidence-two','jvm')
        third=stable.prepare(self.root,self.base/'evidence-three','native')
        self.assertEqual(first,third)
        self.assertEqual(first['binary_directory'],str(self.bins))
        self.assertEqual((self.base/'evidence-one/target/debug').resolve(),self.bins)
        self.assertEqual((self.base/'evidence-three/target/debug').resolve(),self.bins)
        self.assertEqual(before,{p.name:stable.sha256(p) for p in self.bins.iterdir()})
        self.assertFalse(first['relocated_binaries'])

    def test_existing_installation_is_never_silently_overwritten(self):
        info=stable.prepare(self.root,self.base/'one','native')
        library=next(Path(p) for p in info['installed_files'] if p.endswith(('.so','.dylib')))
        library.write_text('changed installed bytes')
        with self.assertRaisesRegex(ValueError,'STABLE_RUNTIME_ARTIFACT_DRIFT'):
            stable.prepare(self.root,self.base/'two','native')
        self.assertEqual(library.read_text(),'changed installed bytes')

    def test_invalid_bundle_is_rejected_before_installation(self):
        bridge=self.root/'lang-java-adapotr/target/compiler-bridge.jar';bridge.write_text('drift')
        with self.assertRaisesRegex(ValueError,'JAVA_RUNTIME_BUILD_CHECKSUM'):
            stable.prepare(self.root,self.base/'one','native')
        self.assertFalse((self.bins.parent/'lib').exists())


if __name__=='__main__':unittest.main()
