#!/usr/bin/env python3
"""Record the exact runtime bundle; no dependency on SDKMAN aliases at runtime."""
import hashlib, json, pathlib, platform, sys
root = pathlib.Path(sys.argv[1])
suffix = 'dylib' if sys.platform == 'darwin' else 'so'
files = [root/'native'/f'libcodeana_java.{suffix}', root/'compiler-bridge.jar']
manifest = dict(version=1, abi=2, analysis_request=4, compiler_protocol=7, java_model=6,
                target_java=[8], os=platform.system(), arch=platform.machine(),
                files={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
(root/'native'/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
