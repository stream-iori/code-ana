#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$repo_root/lang-java-adapotr"
sdk env
set -u
java8_home="${CODEANA_JAVA8_HOME:-$HOME/.sdkman/candidates/java/8.0.492-zulu}"
mvn -q -pl analysis-core -am -DskipTests -Djava8.home="$java8_home" package
mkdir -p target
cp analysis-core/target/classes/META-INF/anatomist/javac-worker.jar target/compiler-bridge.jar
python3 - <<'CHECK'
import struct, zipfile
with zipfile.ZipFile('target/compiler-bridge.jar') as jar:
    classes = [name for name in jar.namelist() if name.endswith('.class')]
    assert classes, 'EMPTY_COMPILER_BRIDGE'
    for name in classes:
        assert struct.unpack('>H', jar.read(name)[6:8])[0] == 52, name
print('Anatomist compiler bridge: Java 8 bytecode verified')
CHECK
