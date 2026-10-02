#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
if [[ "$(uname -s)" != Darwin ]]; then
  echo "Java release packaging currently supports macOS only" >&2
  exit 1
fi
cargo build --release --locked --workspace
"$repo_root/scripts/build-java-native.sh"
package_dir="$repo_root/dist/codeana-java-$(uname -s)-$(uname -m)"
mkdir -p "$package_dir/bin" "$package_dir/lib/java/native" "$package_dir/include" "$package_dir/fixtures"
for binary in codeana-cli codeana-daemon codeana-java-worker; do
  cp "target/release/$binary" "$package_dir/bin/"
done
cp lang-adaptor/target/native/libcodeana_java.dylib lang-adaptor/target/native/manifest.json "$package_dir/lib/java/native/"
cp lang-adaptor/target/compiler-bridge.jar "$package_dir/lib/java/"
cp lang-adaptor/target/maven-input-worker.jar "$package_dir/lib/java/"
cp lang-adaptor/target/native/*.h "$package_dir/include/"
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
(root/'manifest.json').write_text(json.dumps(dict(package='codeana-java8',worker_protocol=10,fact_ir=1,abi=2,analysis_request=4,compiler_protocol=7,java_model=6,target_java=[8],toolchain='externally registered SDKMAN Java 8'),indent=2)+'\n')
files=sorted(p for p in root.rglob('*') if p.is_file() and p.name!='SHA256SUMS')
(root/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+str(p.relative_to(root))+'\n' for p in files))
PY
printf '%s\n' "$package_dir"
