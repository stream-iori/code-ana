#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adapotr"
sdk env
set -u
"$repo_root/scripts/build-java-jvm.sh"
python3 "$repo_root/scripts/check-java-migration.py"
python3 "$repo_root/tests/java_anatomist_reference.py"
