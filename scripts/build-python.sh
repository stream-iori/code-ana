#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
profile=debug
if [[ "${1:-}" == --release ]]; then profile=release; shift; fi
if (( $# )); then echo 'usage: build-python.sh [--release]' >&2; exit 2; fi
set -- --manifest-path lang-python-adaptor/Cargo.toml --locked -p codeana-python-worker
if [[ "$profile" == release ]]; then set -- "$@" --release; fi
cargo build "$@"
# Install atomically; never overwrite an inode that a running worker may use.
python3 - "$repo_root" "$profile" <<'PY'
import json, os, pathlib, shutil, sys, tempfile
root = pathlib.Path(sys.argv[1]); profile = sys.argv[2]
destination = root/'target'/profile
source = root/'lang-python-adaptor/target'/profile/'codeana-python-worker'
destination.mkdir(parents=True, exist_ok=True)
fd, temporary = tempfile.mkstemp(prefix='.python-worker-', dir=destination)
os.close(fd)
try:
    shutil.copyfile(source, temporary)
    os.chmod(temporary, 0o755)
    os.replace(temporary, destination/'codeana-python-worker')
finally:
    pathlib.Path(temporary).unlink(missing_ok=True)
PY
