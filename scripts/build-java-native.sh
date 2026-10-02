#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The checked-in SDKMAN selection owns native builds; an inherited JAVA_HOME must not select JDK 8.
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-adaptor"
sdk env
set -u
"$JAVA_HOME/bin/java" -version
mvn -q clean package
"$repo_root/scripts/build-java-bridge.sh"
"$repo_root/scripts/build-java-maven.sh"
mkdir -p "$repo_root/lang-adaptor/target/native"
mvn -q dependency:build-classpath -Dmdep.outputFile="$repo_root/lang-adaptor/target/native-classpath.txt"
native_classpath="$(cat "$repo_root/lang-adaptor/target/native-classpath.txt")"
native_temp="$(mktemp -d "$repo_root/lang-adaptor/target/native/.build-XXXXXX")"
trap 'rm -rf "$native_temp"' EXIT
export TMPDIR="$native_temp"
# Graal's C query subprocess may replace TMPDIR; clang can keep its intermediates
# beside the query output in this task-owned directory.
"$JAVA_HOME/bin/native-image" --shared -R:MaxHeapSize=256m \
  "-J-Djava.io.tmpdir=$native_temp" "-H:TempDirectory=$native_temp" \
  --native-compiler-options=-save-temps=obj \
  -cp "$repo_root/lang-adaptor/target/classes:$native_classpath" \
  -o "$repo_root/lang-adaptor/target/native/libcodeana_java"
python3 "$repo_root/scripts/java-build-manifest.py" "$repo_root/lang-adaptor/target"
