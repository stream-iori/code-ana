#!/usr/bin/env python3
"""Describe the migrated ABI bundle using version constants from the compiled source tree."""
import hashlib, json, os, pathlib, platform, re, sys, tempfile
root=pathlib.Path(sys.argv[1]).resolve()
project=root.parent
javac=project/'analysis-core/src/main/java/com/anatomist/core/javac'
model=re.search(r'MODEL_VERSION = "([^"]+)"', (javac/'JavacBackend.java').read_text())[1]
batch=int(re.search(r'VERSION = (\d+)',(javac/'JavacBatchProtocol.java').read_text())[1])
adapter=re.search(r'IDENTITY\s*=\s*"([^"]+)"',(project/'java-bridge/src/main/java/dev/codeana/adapter/AnalysisSession.java').read_text())[1]
worker=int(re.search(r'WORKER_PROTOCOL_VERSION: u32 = (\d+)',(project.parent/'crates/model/src/lib.rs').read_text())[1])
suffix='dylib' if sys.platform=='darwin' else 'so'
files=[root/'native'/f'libcodeana_java.{suffix}',root/'compiler-bridge.jar',root/'maven-input-worker.jar']
manifest=dict(version=2,backend='native',worker_protocol=worker,fact_ir=1,abi=2,analysis_request=8,compiler_protocol=batch,compiler_batch_protocol=batch,java_model=model,
              adapter=adapter,type_graph_default=True,type_schema=3,binding_schema=4,target_java=[8],
              os=platform.system(),arch=platform.machine(),
              files={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
with tempfile.NamedTemporaryFile(mode='w',dir=root/'native',prefix='.manifest-',delete=False) as output:
    json.dump(manifest,output,indent=2)
    output.write('\n')
    output.flush()
    os.fsync(output.fileno())
    staged=pathlib.Path(output.name)
try:
    os.replace(staged,root/'native/manifest.json')
finally:
    staged.unlink(missing_ok=True)
