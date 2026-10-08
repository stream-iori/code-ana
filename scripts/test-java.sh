#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adapotr"
sdk env
set -u
export ANATOMIST_HOME="${ANATOMIST_HOME:-$PWD/target/reference-test-home}"
mvn -q -Djava8.home="${CODEANA_JAVA8_HOME:-$HOME/.sdkman/candidates/java/8.0.492-zulu}" test
python3 "$repo_root/scripts/check-java-migration.py"
