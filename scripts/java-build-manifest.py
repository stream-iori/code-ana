#!/usr/bin/env python3
"""Record the exact runtime bundle; no dependency on SDKMAN aliases at runtime."""
import hashlib, json, pathlib, platform, sys
root = pathlib.Path(sys.argv[1])
suffix = 'dylib' if sys.platform == 'darwin' else 'so'
files = [root/'native'/f'libcodeana_java.{suffix}', root/'compiler-bridge.jar']
manifest = dict(version=1, abi=2, analysis_request=6, compiler_protocol=9, java_model=9,
                type_graph_default=True, type_schema=3, binding_schema=4,
                target_java=[8], os=platform.system(), arch=platform.machine(),
                files={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
(root/'native'/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
