#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
native_gc="${CODEANA_NATIVE_GC:-serial}"
case "$native_gc" in serial|G1) ;; *) echo "CODEANA_NATIVE_GC must be serial or G1" >&2; exit 2;; esac
native_monitoring=()
case "${CODEANA_NATIVE_MONITORING:-}" in "") ;; jfr) native_monitoring=(--enable-monitoring=jfr);; *) echo "CODEANA_NATIVE_MONITORING must be empty or jfr" >&2; exit 2;; esac
python3 "$repo_root/scripts/check-java-jvm-acceptance.py"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adapotr"
sdk env
set -u
java8_home="${CODEANA_JAVA8_HOME:-$HOME/.sdkman/candidates/java/8.0.492-zulu}"
[[ -x "$java8_home/bin/javac" ]] || { echo "Java 8 compiler required" >&2; exit 1; }
mvn -q -Pnative -Djava8.home="$java8_home" "$@" package
"$repo_root/scripts/build-java-maven.sh"
mkdir -p target/native
cp analysis-core/target/classes/META-INF/anatomist/javac-worker.jar target/compiler-bridge.jar
python3 - <<'PY'
import pathlib,zipfile,struct
with zipfile.ZipFile('target/compiler-bridge.jar') as jar:
    for name in jar.namelist():
        if name.endswith('.class'):
            assert struct.unpack('>H',jar.read(name)[6:8])[0]==52, name
PY
native_temp="$(mktemp -d "$PWD/target/native/.build-XXXXXX")"
trap 'rm -rf "$native_temp"' EXIT
export TMPDIR="$native_temp"
bash "$repo_root/scripts/build-java-completion.sh" "$native_temp"
"$JAVA_HOME/bin/native-image" --shared "--gc=$native_gc" "${native_monitoring[@]}" -R:MaxHeapSize=256m \
  "-J-Dcodeana.completion.library=$native_temp" \
  "-J-Djava.io.tmpdir=$native_temp" "-H:TempDirectory=$native_temp" \
  -H:IncludeResources='META-INF/anatomist/javac-worker.jar' \
  --native-compiler-options=-save-temps=obj \
  -cp native-bridge/target/native-bridge-0.1.0.jar \
  -o "$native_temp/libcodeana_java"
# A running worker may still map the previous signed library. Replace its inode only
# after linking finishes; overwriting mapped code can terminate a macOS process.
native_suffix=so
[[ "$(uname -s)" == Darwin ]] && native_suffix=dylib
mv "$native_temp/libcodeana_java.$native_suffix" "$PWD/target/native/libcodeana_java.$native_suffix"
for native_header in "$native_temp"/*.h; do
  [[ -f "$native_header" ]] && mv "$native_header" "$PWD/target/native/"
done
python3 "$repo_root/scripts/java-adapotr-manifest.py" "$PWD/target"
