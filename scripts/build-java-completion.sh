#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$1"
mkdir -p "$output"
cc -std=c11 -fPIC -c "$repo_root/lang-java-adapotr/native-bridge/src/main/c/completion-notification.c" -o "$output/completion-notification.o"
ar rcs "$output/libcodeana_completion.a" "$output/completion-notification.o"
