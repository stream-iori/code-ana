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
  ./target/debug/codeana-java-worker ./lang-java-adapotr/target/native/libcodeana_java.dylib

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

# Java bridge and model checks use SDKMAN Java 8 and the pinned GraalVM 25.
test-java:
  ./scripts/test-java.sh

test-java-migration-reference:
  python3 tests/java_anatomist_reference.py

# Legacy adapter parity gate records evidence and refuses to treat fixture success as 52/52 acceptance.
test-java-alignment:
  python3 tests/java_alignment.py

test-java-alignment-fixtures:
  python3 tests/java_alignment.py --level fixtures

test-java-alignment-native:
  python3 tests/java_alignment.py --level native

test-java-maven:
  ./scripts/build-java-maven.sh
  python3 tests/java_maven_export.py
  python3 tests/java_maven_alignment.py

test-java-native:
  ./scripts/build-java-native.sh
  cargo build -p codeana-java-worker --locked
  python3 tests/java_anatomist_native.py

test-java-e2e:
  cargo build --workspace --locked
  python3 tests/java_e2e.py
  python3 tests/java_native_crash_e2e.py

package-java:
  ./scripts/package-java.sh

test-java-release:
  ./scripts/package-java.sh
  cd "dist/codeana-java-$(uname -s)-$(uname -m)" && shasum -a 256 -c SHA256SUMS
  CODEANA_TEST_BIN="$PWD/dist/codeana-java-$(uname -s)-$(uname -m)/bin" python3 tests/java_e2e.py
  CODEANA_TEST_BIN="$PWD/dist/codeana-java-$(uname -s)-$(uname -m)/bin" python3 tests/java_native_crash_e2e.py

test-java-stress:
  python3 tests/java_stress.py --rounds 20
