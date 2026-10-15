#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

cargo build --workspace
"$repo_root/scripts/build-java-native.sh"

port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
daemon_log="$(mktemp)"
smoke_home="$(mktemp -d)"
daemon_pid=""
cleanup() {
  if [[ -n "$daemon_pid" ]]; then
    kill "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" 2>/dev/null || true
  fi
  rm -f "$daemon_log"
  rm -rf "$smoke_home"
}
trap cleanup EXIT

CODEANA_PORT="$port" "$repo_root/target/debug/codeana-daemon" --home "$smoke_home" >"$daemon_log" 2>&1 &
daemon_pid=$!

for _ in {1..40}; do
  if grpc_reply="$(CODEANA_PORT="$port" "$repo_root/target/debug/codeana-cli" hello 2>/dev/null)"; then
    break
  fi
  sleep 0.1
done
if [[ "${grpc_reply:-}" != "Hello, world!" ]]; then
  cat "$daemon_log" >&2
  echo "gRPC Hello failed" >&2
  exit 1
fi

ffi_reply="$("$repo_root/target/debug/codeana-java-worker" \
  "$repo_root/lang-java-adaptor/target/native/libcodeana_java.dylib")"
if [[ "$ffi_reply" != "Hello from Java 25 via FFI: 25" ]]; then
  echo "unexpected FFI reply: $ffi_reply" >&2
  exit 1
fi

printf '%s\n%s\n' "$grpc_reply" "$ffi_reply"
