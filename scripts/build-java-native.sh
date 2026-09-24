#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to a GraalVM JDK 25 installation}"

"$JAVA_HOME/bin/java" -version
mvn -q -f "$repo_root/lang-adaptor/pom.xml" package
mkdir -p "$repo_root/lang-adaptor/target/native"
"$JAVA_HOME/bin/native-image" --shared \
  -cp "$repo_root/lang-adaptor/target/classes" \
  -o "$repo_root/lang-adaptor/target/native/libcodeana_java"
