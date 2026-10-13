#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$repo_root/scripts/check-java-jvm-acceptance.py"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adapotr"
sdk env
set -u
mvn -q -Pnative -DskipTests -Djava8.home="${CODEANA_JAVA8_HOME:-$HOME/.sdkman/candidates/java/8.0.492-zulu}" package
probe_root="$repo_root/target/java-migration-probe"
mkdir -p "$probe_root/classes"
probe_jar="$PWD/native-bridge/target/native-bridge-0.1.0.jar"
probe_classpath="$probe_root/classes:$probe_jar"
# Query and SQLite witnesses observe the independent reference JAR. They are
# outside the native parser closure and must not be compiled into this probe.
"$JAVA_HOME/bin/javac" -cp "$probe_classpath" -d "$probe_root/classes" \
  "$repo_root/tests/java-migration/ParityWitness.java" \
  "$repo_root/tests/java-migration/NativeModelWitness.java" \
  "$repo_root/tests/java-migration/ReflectionInventory.java"
"$JAVA_HOME/bin/java" -cp "$probe_classpath" ReflectionInventory "$probe_jar" "$probe_root/reflection.json"
probe_temp="$(mktemp -d "$probe_root/.build-XXXXXX")"
trap 'rm -rf "$probe_temp"' EXIT
export TMPDIR="$probe_temp"
bash "$repo_root/scripts/build-java-completion.sh" "$probe_temp"
"$JAVA_HOME/bin/native-image" --no-fallback -R:MaxHeapSize=2048m \
  "-J-Dcodeana.completion.library=$probe_temp" \
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
       probe/'native-model-witness',probe/'reflection.json',root/'scripts/build-java-migration-probe.sh',
       *[root/'tests/java-migration'/name for name in
         ('ParityWitness.java','NativeModelWitness.java','ReflectionInventory.java')],
       *sorted((probe/'classes').rglob('*.class'))]
(probe/'build-provenance.json').write_text(json.dumps({str(p.resolve()):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},indent=2)+'\n')
PY
