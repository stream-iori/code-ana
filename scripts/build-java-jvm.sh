#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adaptor"
source "$repo_root/scripts/java-build-env.sh"
set -u
java8_home="$(CODEANA_JAVA8_HOME="${CODEANA_JAVA8_HOME:-$HOME/.sdkman/candidates/java/8.0.492-zulu}" python3 "$repo_root/scripts/resolve-sdkman-java.py" 8)"
java17_home="$(python3 "$repo_root/scripts/resolve-sdkman-java.py" 17)"
# Clean reactor modules, keeping the independently sealed native SDK in parent target/.
mvn -q -pl analysis-core,framework-lombok,framework-spring,framework-sofa,java-bridge,jvm-runner -Djava8.home="$java8_home" -Djava17.home="$java17_home" "$@" clean package
python3 "$repo_root/scripts/check-java-package.py" jvm
"$repo_root/scripts/build-java-maven.sh" "$PWD/target/jvm"
python3 "$repo_root/scripts/java-jvm-manifest.py" "$PWD"
python3 "$repo_root/scripts/check-java-migration.py"
