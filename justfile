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
