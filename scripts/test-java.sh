#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adaptor"
sdk env
set -u
export ANATOMIST_HOME="${ANATOMIST_HOME:-$PWD/target/reference-test-home}"
exec "$repo_root/scripts/build-java-jvm.sh"
