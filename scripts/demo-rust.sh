#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
cargo build --workspace --locked
run_dir="$(mktemp -d "${TMPDIR:-/tmp}/codeana-demo.XXXXXX")"
daemon_pid=""
cleanup() {
  if [[ -n "$daemon_pid" ]]; then
    kill "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" 2>/dev/null || true
  fi
  rm -rf "$run_dir"
}
trap cleanup EXIT
cp -R tests/fixtures/java/base "$run_dir/project"
./target/debug/codeana-daemon --home "$run_dir/home" --dev-mock-fixtures "$repo_root/tests/fixtures/mock.json" >"$run_dir/daemon.log" 2>&1 &
daemon_pid=$!
for _ in {1..100}; do
  if ./target/debug/codeana-cli --home "$run_dir/home" daemon status >/dev/null 2>&1; then break; fi
  sleep 0.05
done
cli() { ./target/debug/codeana-cli --home "$run_dir/home" --project "$run_dir/project" "$@"; }
cli index
cli calls main
cli call-sites main
cli snapshot before --name analysis
cp tests/fixtures/java/edited/src/App.java "$run_dir/project/src/App.java"
cli index
cli snapshot after --name analysis
cli diff --base before --target after --name relations
cli source greet --revision snapshot:before
cli gc
