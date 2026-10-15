set shell := ["bash", "-euo", "pipefail", "-c"]

default:
  @just --list

build:
  cargo build --workspace

java:
  ./scripts/build-java-jvm.sh

java-native:
  ./scripts/build-java-native.sh

daemon: build
  ./target/debug/codeana-daemon

hello: build
  ./target/debug/codeana-cli hello

worker: build java-native
  ./target/debug/codeana-java-worker ./lang-java-adapotr/target/native/libcodeana_java.dylib

smoke:
  ./scripts/smoke.sh

# Rust product checks; no JDK required.
test-rust:
  python3 scripts/generate-compact-schema.py --check
  cargo fmt --all -- --check
  cargo test --workspace --locked
  cargo build --workspace --locked
  python3 tests/rust_graph_e2e.py
  python3 tests/rust_language_e2e.py
  python3 tests/rust_dogfood_tests.py
  python3 tests/java_bundle_manifest_tests.py
  python3 tests/rust_upstream_text_search.py
  python3 tests/rust_upstream_annotations.py
  cargo clippy --workspace --all-targets --locked -- -D warnings

test-rust-release:
  ./scripts/package-rust.sh
  cd "dist/codeana-rust-$(uname -s)-$(uname -m)" && shasum -a 256 -c SHA256SUMS
  CODEANA_TEST_BIN="$PWD/dist/codeana-rust-$(uname -s)-$(uname -m)/bin" CODEANA_TEST_FIXTURES="$PWD/dist/codeana-rust-$(uname -s)-$(uname -m)/fixtures" python3 tests/rust_graph_e2e.py
  CODEANA_TEST_BIN="$PWD/dist/codeana-rust-$(uname -s)-$(uname -m)/bin" CODEANA_TEST_FIXTURES="$PWD/dist/codeana-rust-$(uname -s)-$(uname -m)/fixtures" python3 tests/rust_language_e2e.py

demo-rust:
  ./scripts/demo-rust.sh

package-rust:
  ./scripts/package-rust.sh

# Java bridge and model checks use SDKMAN Java 8 and the pinned GraalVM 25.
test-java:
  ./scripts/test-java.sh

# Static ownership audit runs separately and does not rebuild sealed bundles.
test-java-source-audit:
  JAVA_HOME="$HOME/.sdkman/candidates/java/25.3.4+1.r25-graal" mvn -q -f lang-java-adapotr/migration-audit/process-architecture/pom.xml test

# Original component tests run against an already accepted runner, without rebuilding it.
test-java-upstream-audit runner:
  JAVA_HOME="$HOME/.sdkman/candidates/java/25.3.4+1.r25-graal" mvn -q -f lang-java-adapotr/migration-audit/upstream-components/pom.xml -Dcodeana.audit.runner="{{runner}}" test

test-java-jvm-components evidence:
  python3 tests/acceptance_support_tests.py
  python3 tests/java_source_cases_tests.py
  python3 tests/java_coverage_acceptance_gate_tests.py
  python3 tests/java_native_build_gate_tests.py
  python3 tests/java_native_install_tests.py
  python3 tests/java_performance_monitor_v2_tests.py
  python3 tests/java_performance_stable_runtime_tests.py
  python3 tests/java_performance_upgrade_runtime_tests.py
  python3 tests/java_jvm_gate.py --evidence "{{evidence}}"
  python3 tests/java_jvm_bootstrap_tests.py

test-java-jvm-product evidence:
  cargo build --workspace --locked
  python3 tests/java_jvm_product_gate.py --backend jvm --group graph --evidence "{{evidence}}"

test-java-jvm-fixtures evidence:
  just test-java-jvm-components "{{evidence}}/components"
  just test-java-jvm-product "{{evidence}}/graph"

java-reference:
  python3 scripts/build-java-reference.py

test-java-maven:
  ./scripts/build-java-maven.sh
  python3 tests/java_maven_export.py
  python3 tests/java_maven_alignment.py

test-java-native:
  ./scripts/build-java-native.sh --build-only
  cargo build --workspace --locked
  python3 scripts/install-java-native.py
  python3 tests/java_native_manifest_tests.py
  python3 tests/java_native_lifecycle.py
  CODEANA_JAVA_BACKEND=native python3 tests/java_graph_storage.py
  CODEANA_JAVA_BACKEND=native python3 tests/java_native_environment.py
  python3 tests/java_native_crash_e2e.py

# Run a freshly built native bundle with the same recorded Java fixture groups.
test-java-native-product evidence:
  cargo build --workspace --locked
  python3 scripts/install-java-native.py
  python3 tests/java_jvm_product_gate.py --backend native --group graph --evidence "{{evidence}}"

test-java-native-lifecycle evidence:
  cargo build --workspace --locked
  python3 scripts/install-java-native.py
  python3 tests/java_jvm_product_gate.py --backend native --group lifecycle --evidence "{{evidence}}"

test-java-e2e:
  cargo build --workspace --locked
  CODEANA_JAVA_BACKEND=native python3 tests/java_graph_storage.py
  CODEANA_JAVA_BACKEND=native python3 tests/java_native_environment.py
  python3 tests/java_native_crash_e2e.py

package-java:
  ./scripts/package-java.sh

test-java-release:
  ./scripts/build-java-native.sh --build-only
  cargo build --workspace --locked
  cargo build --release --workspace --locked
  python3 scripts/install-java-native.py
  python3 tests/java_release.py --bin-root "$PWD/target/debug" --stage-release
