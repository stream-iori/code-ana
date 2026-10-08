# code-ana

Java 8 真实索引已接入 Rust 产品链路：javac 分析、SQLite 发布、查询与冷增量。SDK 来自 SDKMAN；files/text 可在无语言配置下运行。独立 mock 保留作无需 JDK 的 Rust 回归。

```text
CLI → gRPC daemon → Rust host → C ABI → Java 25 native → Java 8 javac
          ↑                  ← Fact IR ← 独立模型 ← parse/analyze
          └→ SQLite revision → 查询 / 历史 / diff
```

```sh
just test-java-native # SDKMAN 构建共享库、Java 8 桥与真实 FFI 验证
just test-java-e2e # 真实 daemon 索引与查询；需先构建共享库
just test-java-release # 完整 Java release 包验收
just test-rust   # Rust 单元测试、真实进程回归、静态检查；无需 JDK
just test-rust-release # release 包校验和、包内二进制端到端回归
just demo-rust   # 独立临时 home，演示索引、查询、增量和历史
```

[Java 运行与范围](docs/java-implementation.md) · [验收记录](docs/implementation/java-validation.md) · [Rust 命令](docs/rust-first.md) · [设计文档](docs/README.md) · [Rust 实施状态](todo/07-rust-first.md)

生产 home 默认 `~/.code-ana`，普通 CLI 自动启动 daemon。mock 必须通过 daemon 的 `--dev-mock-fixtures` 和独立 `--home` 显式启用，只接受已知 fixture 的准确输入哈希。

保留原有 Hello 链路：

```sh
cargo build --workspace --locked
cargo run -p codeana-cli -- hello
cd lang-java-adapotr && sdk env && cd ..
./scripts/build-java-native.sh
cargo run -p codeana-java-worker -- lang-java-adapotr/target/native/libcodeana_java.dylib
```

`just smoke` 验证 gRPC Hello 和 Java FFI Hello；需 GraalVM JDK 25。它不能代替真实 javac 分析验收。
