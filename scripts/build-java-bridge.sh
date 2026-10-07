#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
java8_home="${CODEANA_JAVA8_HOME:-$HOME/.sdkman/candidates/java/8.0.492-zulu}"
"$java8_home/bin/java" -version
python3 - "$java8_home/release" <<'CHECK'
import pathlib,sys
assert any(line.startswith('JAVA_VERSION="1.8.') for line in pathlib.Path(sys.argv[1]).read_text().splitlines()), 'SDKMAN Java 8 is required'
CHECK
rm -rf "$repo_root/lang-adaptor/target/compiler-classes"
mkdir -p "$repo_root/lang-adaptor/target/compiler-classes"
"$java8_home/bin/javac" -encoding UTF-8 -source 8 -target 8 \
  -cp "$java8_home/lib/tools.jar" -d "$repo_root/lang-adaptor/target/compiler-classes" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/Json.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/FactHeaders.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/RecordArchive.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/BinaryModelCodec.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/ModelArchive.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/ArchiveRows.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/ModelRows.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/ModelRowLease.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/UnitDeclarationRows.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/TypeGraph.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/TypeGraphArchive.java" \
  "$repo_root/lang-adaptor/src/main/java/dev/codeana/common/ModelGraphs.java" \
  "$repo_root/lang-adaptor/src/compiler/java/dev/codeana/compiler/CompilerTypeGraph.java" \
  "$repo_root/lang-adaptor/src/compiler/java/dev/codeana/compiler/WorkerSourceAnnotations.java" \
  "$repo_root/lang-adaptor/src/compiler/java/dev/codeana/compiler/WorkerWrittenAnnotations.java" \
  "$repo_root/lang-adaptor/src/compiler/java/dev/codeana/compiler/WorkerOverloadEvidence.java" \
  "$repo_root/lang-adaptor/src/compiler/java/dev/codeana/compiler/CompilerBridge.java"
"$java8_home/bin/jar" cf "$repo_root/lang-adaptor/target/compiler-bridge.jar" -C "$repo_root/lang-adaptor/target/compiler-classes" .
