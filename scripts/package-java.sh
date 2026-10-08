#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
from_build=false
package_dir="$repo_root/dist/codeana-java-$(uname -s)-$(uname -m)"
custom_output=false
while (($#)); do
  case "$1" in
    --from-build) from_build=true; shift ;;
    --output)
      if (($# < 2)); then echo "--output requires a new directory" >&2; exit 2; fi
      package_dir="$2"; custom_output=true; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
if [[ "$(uname -s)" != Darwin ]]; then
  echo "Java release packaging currently supports macOS only" >&2
  exit 1
fi
if [[ "$from_build" == false ]]; then
  cargo build --release --locked --workspace
  "$repo_root/scripts/build-java-native.sh"
fi
# Never package a mixed native/SDK8 build or old files left by a previous package.
python3 - "$repo_root/lang-java-adapotr/target" <<'VERIFY'
import hashlib,json,pathlib,subprocess,sys
root=pathlib.Path(sys.argv[1]).resolve()
manifest=json.loads((root/'native/manifest.json').read_text())
for name in ('native/libcodeana_java.dylib','compiler-bridge.jar'):
    path=root/name
    assert path.resolve().is_relative_to(root), 'NATIVE_ARTIFACT_PATH'
    assert hashlib.sha256(path.read_bytes()).hexdigest()==manifest['files'][name], 'NATIVE_BUILD_CHECKSUM: '+name
worker=root.parent.parent/'target/release/codeana-java-worker'
versions=json.loads(subprocess.check_output([str(worker),str(root/'native/libcodeana_java.dylib'),
                                            '--runtime-info'],text=True,timeout=15))
assert versions==dict(worker_protocol=14,fact_ir=1,adapter=manifest['adapter'],
                      analysis_request=8,abi=2), 'PACKAGE_WORKER_PROTOCOL_MISMATCH'
VERIFY
if [[ "$custom_output" == true && -e "$package_dir" ]]; then
  echo "Package output already exists: $package_dir" >&2; exit 2
fi
if [[ "$custom_output" == false ]]; then rm -rf "$package_dir"; fi
mkdir -p "$package_dir/bin" "$package_dir/lib/java/native" "$package_dir/include" "$package_dir/fixtures"
for binary in codeana-cli codeana-daemon codeana-java-worker; do
  cp "target/release/$binary" "$package_dir/bin/"
done
cp lang-java-adapotr/target/native/libcodeana_java.dylib lang-java-adapotr/target/native/manifest.json "$package_dir/lib/java/native/"
cp lang-java-adapotr/target/compiler-bridge.jar "$package_dir/lib/java/"
cp lang-java-adapotr/target/maven-input-worker.jar "$package_dir/lib/java/"
cp lang-java-adapotr/target/native/*.h "$package_dir/include/"
cat > "$package_dir/bin/codeana-export-java-inputs" <<'EXPORT'
#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$script_dir/codeana-cli" java-inputs export "$@"
EXPORT
chmod +x "$package_dir/bin/codeana-export-java-inputs"
mkdir -p "$package_dir/docs/implementation"
cp docs/java-package.md "$package_dir/README.md"
cp docs/java-implementation.md "$package_dir/docs/"
cp docs/implementation/java-validation.md docs/implementation/java-reuse.md "$package_dir/docs/implementation/"
cp -R tests/fixtures/java-native/base "$package_dir/fixtures/"
python3 - "$package_dir" <<'PY'
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1])
native=json.loads((root/'lib/java/native/manifest.json').read_text())
metadata={key:native[key] for key in ('abi','analysis_request','compiler_protocol','java_model',
    'type_graph_default','type_schema','binding_schema','target_java')}
metadata.update(package='codeana-java8',worker_protocol=14,fact_ir=1,
    toolchain='externally registered SDKMAN Java 8')
(root/'manifest.json').write_text(json.dumps(metadata,indent=2)+'\n')
files=sorted(p for p in root.rglob('*') if p.is_file() and p.name!='SHA256SUMS')
(root/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+str(p.relative_to(root))+'\n' for p in files))
PY
printf '%s\n' "$package_dir"
