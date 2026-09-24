# code-ana

设计见 [docs/README.md](docs/README.md)。当前仅有可运行的基础骨架。

```text
CLI -- gRPC / HTTP/2 / protobuf --> daemon
Rust worker -- C ABI / FFI --> GraalVM Java 25 shared library
```

## 本地运行

先在 `lang-adaptor/` 执行 `sdk env` 选择 `.sdkmanrc` 中的 GraalVM JDK 25。常用命令可通过 `just build`、`just java`、`just daemon`、`just hello`、`just worker`、`just smoke` 执行。

终端 A：

```sh
cargo run -p codeana-daemon
```

终端 B：

```sh
cargo run -p codeana-cli -- hello
# Hello, world!
```

Java FFI 独立验证（需 GraalVM JDK 25 和 Maven）：

```sh
cd lang-adaptor && sdk env && cd ..
./scripts/build-java-native.sh
cargo run -p codeana-java-worker -- lang-adaptor/target/native/libcodeana_java.dylib
# Hello from Java 25 via FFI: 25
```

`scripts/smoke.sh` 自动检查上述两条路径。当前 Hello 仅检验通信与装载，未实现索引分析。
