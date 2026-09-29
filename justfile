set shell := ["bash", "-euo", "pipefail", "-c"]

default:
  @just --list

build:
  cargo build --workspace

java:
  ./scripts/build-java-native.sh

daemon: build
  ./target/debug/codeana-daemon

hello: build
  ./target/debug/codeana-cli hello

worker: build java
  ./target/debug/codeana-java-worker ./lang-adaptor/target/native/libcodeana_java.dylib

smoke:
  ./scripts/smoke.sh

# Rust product checks; no JDK required.
test-rust:
  cargo fmt --all -- --check
  cargo test --workspace --locked
  cargo build --workspace --locked
  python3 tests/rust_e2e.py
  cargo clippy --workspace --all-targets --locked -- -D warnings

test-rust-release:
  ./scripts/package-rust.sh
  cd "dist/codeana-rust-$(uname -s)-$(uname -m)" && shasum -a 256 -c SHA256SUMS
  CODEANA_TEST_BIN="$PWD/dist/codeana-rust-$(uname -s)-$(uname -m)/bin" CODEANA_TEST_FIXTURES="$PWD/dist/codeana-rust-$(uname -s)-$(uname -m)/fixtures" python3 tests/rust_e2e.py

demo-rust:
  ./scripts/demo-rust.sh

package-rust:
  ./scripts/package-rust.sh
