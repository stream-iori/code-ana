#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adapotr"
sdk env
set -u
mvn -q -DskipTests package
probe_root="$repo_root/target/java-migration-probe"
mkdir -p "$probe_root/classes"
probe_jar="$PWD/native-bridge/target/native-bridge-0.1.0.jar"
probe_classpath="$probe_root/classes:$PWD/native-bridge/target/test-classes:$probe_jar"
"$JAVA_HOME/bin/javac" -cp "$probe_classpath" -d "$probe_root/classes" "$repo_root"/tests/java-migration/*.java
"$JAVA_HOME/bin/java" -cp "$probe_classpath" ReflectionInventory "$probe_jar" "$probe_root/reflection.json"
probe_temp="$(mktemp -d "$probe_root/.build-XXXXXX")"
trap 'rm -rf "$probe_temp"' EXIT
export TMPDIR="$probe_temp"
"$JAVA_HOME/bin/native-image" --no-fallback -R:MaxHeapSize=1024m \
  "-J-Djava.io.tmpdir=$probe_temp" "-H:TempDirectory=$probe_temp" \
  --native-compiler-options=-save-temps=obj \
  -H:ReflectionConfigurationFiles="$probe_root/reflection.json" \
  -H:IncludeResources='META-INF/anatomist/javac-worker.jar' \
  -cp "$probe_classpath" -o "$probe_temp/native-model-witness" NativeModelWitness
mv "$probe_temp/native-model-witness" "$probe_root/native-model-witness"
python3 - "$repo_root" "$probe_root" <<'PY'
import hashlib,json,pathlib,sys
root,probe=map(pathlib.Path,sys.argv[1:])
paths=[root/'lang-java-adapotr/native-bridge/target/native-bridge-0.1.0.jar',
       probe/'native-model-witness',*sorted((root/'tests/java-migration').glob('*.java'))]
(probe/'build-provenance.json').write_text(json.dumps({str(p.resolve()):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},indent=2)+'\n')
PY
