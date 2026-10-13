#!/usr/bin/env python3
"""Package a JVM-only bundle; runtime never reads a native manifest."""
import hashlib
import json
import pathlib
import platform
import re
import shutil
import sys

project = pathlib.Path(sys.argv[1]).resolve()
output = project / 'target/jvm'
output.mkdir(parents=True, exist_ok=True)
for source, name in [
    (project / 'jvm-runner/target/jvm-runner-0.1.0.jar', 'runner.jar'),
    (project / 'analysis-core/target/classes/META-INF/anatomist/javac-worker.jar', 'compiler-bridge.jar'),
    (output / 'maven-input-worker.jar', 'maven-input-worker.jar'),
]:
    if source.resolve() != (output / name).resolve():
        shutil.copy2(source, output / name)
adapter = project / 'java-bridge/src/main/java/dev/codeana/adapter/AnalysisSession.java'
protocol_source = (project.parent / 'crates/model/src/lib.rs').read_text()
protocol = int(re.search(r'WORKER_PROTOCOL_VERSION: u32 = (\d+)', protocol_source)[1])
manifest = dict(version=1, backend='jvm', analysis_request=9, worker_protocol=protocol, fact_ir=2,
                adapter=re.search(r'IDENTITY\s*=\s*"([^"]+)"', adapter.read_text())[1],
                compiler_batch_protocol=2, java_model='javac-model-v50', target_java=[8],
                os=platform.system(), arch={'aarch64':'arm64', 'AMD64':'x86_64'}.get(platform.machine(), platform.machine()),
                files={name: hashlib.sha256((output / name).read_bytes()).hexdigest()
                       for name in ['runner.jar', 'compiler-bridge.jar', 'maven-input-worker.jar']})
(output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest))
