#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
cargo build --release --locked --workspace
package_dir="$repo_root/dist/codeana-rust-$(uname -s)-$(uname -m)"
rm -rf "$package_dir"
mkdir -p "$package_dir/bin" "$package_dir/fixtures"
for binary in codeana-cli codeana-daemon codeana-mock-worker; do
  cp "target/release/$binary" "$package_dir/bin/"
done
cp tests/fixtures/mock.json "$package_dir/fixtures/"
cp -R tests/fixtures/java "$package_dir/fixtures/"
cp docs/rust-first.md "$package_dir/README.md"
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
manifest = dict(package='codeana-rust-mock', platform=platform, architecture=architecture,
                cli_daemon_protocol=version('DAEMON_PROTOCOL_VERSION'),
                worker_protocol=version('WORKER_PROTOCOL_VERSION'), fact_ir=version('VERSION'),
                java_adapter='mock-fixtures-only')
(pathlib.Path(package) / 'manifest.json').write_text(json.dumps(manifest) + '\n')
PY_MANIFEST

(cd "$package_dir" && {
  shasum -a 256 bin/* manifest.json README.md
  find fixtures -type f -print | LC_ALL=C sort | while IFS= read -r fixture; do
    shasum -a 256 "$fixture"
  done
} > SHA256SUMS)
printf '%s\n' "$package_dir"
