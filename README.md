# code-ana

Rust CLI、daemon、配置/捕获、任务、SQLite 发布、查询、历史和运维可用 mock 验证；files/text 可在无语言配置下运行。本轮不扩展 diff。Java 语义暂由独立 mock-worker 回放，真实后端只采用 javac。

```text
CLI → gRPC daemon → mock-worker → SQLite revision → 查询 / 历史 / diff
                     后续替换为 Rust host → C ABI → Java 25 native / javac
```

```sh
just test-rust   # Rust 单元测试、真实进程回归、静态检查；无需 JDK
just test-rust-release # release 包校验和、包内二进制端到端回归
just demo-rust   # 独立临时 home，演示索引、查询、增量和历史
```

[运行和命令](docs/rust-first.md) · [设计文档](docs/README.md) · [Rust 实施状态](todo/07-rust-first.md)

生产 home 默认 `~/.code-ana`，普通 CLI 自动启动 daemon。mock 必须通过 daemon 的 `--dev-mock-fixtures` 和独立 `--home` 显式启用，只接受已知 fixture 的准确输入哈希。

保留原有 Hello 链路：

```sh
cargo build --workspace --locked
cargo run -p codeana-cli -- hello
cd lang-adaptor && sdk env && cd ..
./scripts/build-java-native.sh
cargo run -p codeana-java-worker -- lang-adaptor/target/native/libcodeana_java.dylib
```

`just smoke` 验证 gRPC Hello 和 Java FFI Hello；需 GraalVM JDK 25。它不能代替真实 javac 分析验收。
