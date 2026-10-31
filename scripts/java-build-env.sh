#!/usr/bin/env bash
# Sourced after SDKMAN initialization, from lang-java-adaptor.
# Explicit developer selection only; no automatic toolchain fallback.
if [[ -n "${CODEANA_BUILD_JAVA_HOME:-}" ]]; then
  [[ -x "$CODEANA_BUILD_JAVA_HOME/bin/java" ]] || { echo "CODEANA_BUILD_JAVA_HOME must contain bin/java" >&2; return 1; }
  export JAVA_HOME="$CODEANA_BUILD_JAVA_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "Explicit development build JDK: $JAVA_HOME" >&2
else
  sdk env
fi
