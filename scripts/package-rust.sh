#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
from_build=false
custom_output=false
package_dir="$repo_root/dist/codeana-rust-$(uname -s)-$(uname -m)"
while (( $# )); do
  case "$1" in
    --from-build) from_build=true; shift ;;
    --output) [[ $# -ge 2 ]] || { echo '--output needs an absolute new directory' >&2; exit 2; }; package_dir="$2"; custom_output=true; shift 2 ;;
    *) echo 'usage: package-rust.sh [--from-build] [--output /absolute/new/package]' >&2; exit 2 ;;
  esac
done
if [[ "$custom_output" == true ]]; then
  [[ "$package_dir" == /* && ! -e "$package_dir" ]] || { echo 'output must be an absolute new directory' >&2; exit 2; }
fi
if [[ "$from_build" == false ]]; then cargo build --release --locked --workspace; fi
if [[ "$custom_output" == false ]]; then rm -rf "$package_dir"; fi
mkdir -p "$package_dir/bin" "$package_dir/fixtures" "$package_dir/modules"
for binary in codeana-cli codeana-daemon codeana-mock-worker codeana-rust-worker; do
  cp "target/release/$binary" "$package_dir/bin/"
done
cp tests/fixtures/mock.json "$package_dir/fixtures/"
cp -R tests/fixtures/java "$package_dir/fixtures/"
cp scripts/export-rust-inputs.py "$package_dir/bin/codeana-export-rust-inputs"
cp -R fixtures/rust "$package_dir/fixtures/rust"
cp docs/rust-first.md "$package_dir/README.md"
cp docs/modules/rust-worker.md "$package_dir/modules/"
cp docs/modules/inputs.md "$package_dir/modules/"
python3 - "$repo_root" "$package_dir" "$(uname -s)" "$(uname -m)" <<'PY_MANIFEST'
import json
import pathlib
import re
import sys
root, package, platform, architecture = sys.argv[1:]
source = (pathlib.Path(root) / 'crates/model/src/lib.rs').read_text()
def version(name):
    match = re.search(r'pub const ' + re.escape(name) + r': u32 = (\d+);', source)
    if match is None:
        raise ValueError('MISSING_PROTOCOL_CONSTANT: ' + name)
    return int(match.group(1))
storage = (pathlib.Path(root)/'crates/storage/src/graph_store.rs').read_text()
def storage_version(name):
    return int(re.search(r'pub const '+name+r': i64 = (\d+)',storage)[1])
manifest = dict(package='codeana-rust', platform=platform, architecture=architecture,
                cli_daemon_protocol=version('DAEMON_PROTOCOL_VERSION'),
                worker_protocol=version('WORKER_PROTOCOL_VERSION'), fact_ir=version('VERSION'),
                physical_schema=storage_version('SCHEMA_VERSION'), logical_schema=storage_version('SOURCE_SCHEMA_VERSION'),
                java_adapter='mock-fixtures-only', rust_adapter=re.search(r'ADAPTER_VERSION: &str = \"([^\"]+)\"', (pathlib.Path(root) / 'lang-rust-adaptor/src/lib.rs').read_text()).group(1))
(pathlib.Path(package) / 'manifest.json').write_text(json.dumps(manifest) + '\n')
PY_MANIFEST

(cd "$package_dir" && {
  shasum -a 256 bin/* modules/* manifest.json README.md
  find fixtures -type f -print | LC_ALL=C sort | while IFS= read -r fixture; do
    shasum -a 256 "$fixture"
  done
} > SHA256SUMS)
printf '%s\n' "$package_dir"
