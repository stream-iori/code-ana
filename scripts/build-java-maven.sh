#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adapotr"
sdk env
set -u
maven_executable="${CODEANA_MAVEN:-$(command -v mvn)}"
maven_root="$(python3 - "$maven_executable" <<'PY'
import pathlib,sys
print(pathlib.Path(sys.argv[1]).resolve().parent.parent)
PY
)"
[[ -d "$maven_root/lib" ]] || { echo "Maven 3.9.x distribution lib directory required" >&2; exit 1; }
output="$repo_root/lang-java-adapotr/target/maven-worker"
mkdir -p "$output/classes"
"$JAVA_HOME/bin/javac" --release 8 -cp "$maven_root/lib/*" -d "$output/classes" \
  "$repo_root/lang-java-adapotr/native-bridge/src/maven-worker/java/dev/codeana/maven/MavenInputsWorker.java"
cp -R "$repo_root/lang-java-adapotr/native-bridge/src/maven-worker/resources/." "$output/classes/"
"$JAVA_HOME/bin/jar" --create --file "$repo_root/lang-java-adapotr/target/maven-input-worker.jar" -C "$output/classes" .
