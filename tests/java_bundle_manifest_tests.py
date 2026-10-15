#!/usr/bin/env python3
"""Bundle metadata must follow the Rust handshake, including future model bumps."""
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]

class BundleVersions(unittest.TestCase):
    def test_both_bundle_generators_follow_model_and_worker_constants(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'target') as temporary:
            root=pathlib.Path(temporary)
            project=root/'lang-java-adaptor'
            sources={
                root/'crates/model/src/lib.rs':'pub const VERSION: u32 = 37;\npub const WORKER_PROTOCOL_VERSION: u32 = 99;\n',
                project/'java-bridge/src/main/java/dev/codeana/adapter/AnalysisSession.java':'IDENTITY = "fixture-adapter";',
                project/'analysis-core/src/main/java/com/anatomist/core/javac/JavacBackend.java':'MODEL_VERSION = "fixture-model";',
                project/'analysis-core/src/main/java/com/anatomist/core/javac/JavacBatchProtocol.java':'VERSION = 2;',
            }
            for path,text in sources.items():
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(text)
            output=project/'target'
            suffix='dylib' if sys.platform=='darwin' else 'so'
            for path in [output/'native'/('libcodeana_java.'+suffix),output/'compiler-bridge.jar',output/'maven-input-worker.jar',output/'jvm/maven-input-worker.jar',project/'jvm-runner/target/jvm-runner-0.1.0.jar',project/'analysis-core/target/classes/META-INF/anatomist/javac-worker.jar']:
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(b'fixture artifact')
            for script,argument,manifest in [('java-jvm-manifest.py',project,output/'jvm/manifest.json'),('java-adaptor-manifest.py',output,output/'native/manifest.json')]:
                subprocess.run([sys.executable,str(ROOT/'scripts'/script),str(argument)],check=True,capture_output=True)
                body=json.loads(manifest.read_text())
                self.assertEqual((body['worker_protocol'],body['fact_ir']),(99,37))
                for name,digest in body['files'].items():
                    self.assertEqual(digest,hashlib.sha256(((output/'jvm' if body['backend']=='jvm' else output)/name).read_bytes()).hexdigest())

if __name__=='__main__':
    unittest.main()
