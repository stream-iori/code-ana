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
printf '{"package":"codeana-rust-mock","platform":"%s","architecture":"%s","cli_daemon_protocol":1,"worker_protocol":11,"fact_ir":1,"java_adapter":"mock-fixtures-only"}\n' "$(uname -s)" "$(uname -m)" > "$package_dir/manifest.json"
(cd "$package_dir" && {
  shasum -a 256 bin/* manifest.json README.md
  find fixtures -type f -print | LC_ALL=C sort | while IFS= read -r fixture; do
    shasum -a 256 "$fixture"
  done
} > SHA256SUMS)
printf '%s\n' "$package_dir"
