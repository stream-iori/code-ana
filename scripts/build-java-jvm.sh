#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adaptor"
sdk env
set -u
# Clean reactor modules, keeping the independently sealed native SDK in parent target/.
mvn -q -pl analysis-core,framework-spring,framework-sofa,java-bridge,jvm-runner -Djava8.home="${CODEANA_JAVA8_HOME:-$HOME/.sdkman/candidates/java/8.0.492-zulu}" "$@" clean package
"$repo_root/scripts/build-java-maven.sh" "$PWD/target/jvm"
python3 "$repo_root/scripts/java-jvm-manifest.py" "$PWD"
python3 "$repo_root/scripts/check-java-migration.py"
