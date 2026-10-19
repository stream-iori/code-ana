# code-ana

Java、Rust 和 Python 源码分析共用 CLI、daemon、SQLite 发布与查询。Java 使用 javac / Native G1；Rust 使用独立 `lang-rust-adaptor` 和 rust-analyzer HIR，消费显式导出的 schema 2 冻结 Cargo 输入，仅加载项目及一层直接依赖，关闭主动宏展开。Python 使用固定 Pyrefly 1.4.0、显式冻结输入和保守增量，独立 Cargo workspace 构建；不执行目标代码。各语言的动态语义仍有覆盖缺口；files/text 可在无语言配置下运行。

```text
CLI → gRPC daemon ┬→ Java worker → C ABI → Java 25 native → Java 8 javac
                 ├→ Rust worker → rust-analyzer HIR
                 └→ Python worker → Pyrefly
                         │ GRA1 rows
                         ▼
                  SQLite revision → 查询 / 历史 / diff
```

```sh
just test-java-native # SDKMAN 构建共享库、Java 8 桥与真实 FFI 验证
just test-java-e2e # 真实 daemon 索引与查询；需先构建共享库
just test-java-release # 完整 Java release 包验收
just test-rust   # workspace、真实 Rust 解析/进程回归、静态检查；无需 JDK
just test-rust-release # release 包校验和、包内二进制端到端回归
just demo-rust   # 独立临时 home，演示索引、查询、增量和历史
just build-python # 独立 workspace 构建并安装到原 target/debug 路径
just test-python # Java fixture → Python 核心和 CLI fixture
just package-python /absolute/new/package # Rust/Python Release 包；Java SDK 单独安装
```

[Rust 配置、选型与范围](docs/modules/rust-worker.md) · [Java 运行与范围](docs/modules/java-worker.md) · [Python 配置与范围](docs/modules/python-worker.md) · [当前验证边界](docs/current-state.md) · [CLI 命令](docs/rust-first.md) · [设计文档](docs/README.md) · [验证入口](docs/delivery.md)

配置 `[inputs]` 后，使用统一入口，不必在 CLI 上传语言专用参数：

```sh
codeana-cli --project . doctor
codeana-cli --project . index --prepare
codeana-cli --project . report <report-id> --format markdown
```

多独立工作区、Python 环境和降级规则见 [统一输入准备](docs/modules/inputs.md)。普通 index 与原导出器保持可用。

生产 home 默认 `~/.code-ana`，普通 CLI 自动启动 daemon。mock 必须通过 daemon 的 `--dev-mock-fixtures` 和独立 `--home` 显式启用，只接受已知 fixture 的准确输入哈希。

保留原有 Hello 链路：

```sh
cargo build --workspace --locked
cargo run -p codeana-cli -- hello
cd lang-java-adaptor && sdk env && cd ..
./scripts/build-java-native.sh --build-only
cargo run -p codeana-java-worker -- lang-java-adaptor/target/native/libcodeana_java.dylib
```

`just smoke` 验证 gRPC Hello 和 Java FFI Hello；它调用正式 Native 构建，需固定 GraalVM JDK 25 和有效 JVM 验收回执。开发回归使用 `just test-java-native`。它不能代替真实 javac 分析验收。
