# code-ana 开发约定

| 边界 | 约定 |
|---|---|
| 架构依据 | 先读 `docs/architecture.md`、`docs/contracts.md` 和对应模块文档。设计与实现冲突时，查证后同步修改。 |
| CLI / daemon | Rust；CLI 与本机用户级 daemon 使用 HTTP/2 + protobuf（gRPC）。项目在 daemon 内隔离。 |
| Java | Java 代码放在 `lang-adaptor/`，使用 Java 25 + GraalVM Native Image 生成共享库；独立 Rust worker 通过 C ABI / FFI 调用。禁止 JNI。 |
| JDK 选择 | `lang-adaptor/.sdkmanrc` 固定 GraalVM JDK 25；进入该目录执行 `sdk env`。 |
| 配置 | 目标项目在 `.code-ana/config.toml` 显式启用语言，不自动发现；Lombok 配在 `[languages.java.lombok]`。 |
| 构建 | 产品只给目标项目的构建建议，不自动运行目标项目的编译命令。开发和测试可显式编译本项目与 fixture。 |
| 验证顺序 | Java fixture 优先；不设 Anatomist 兼容要求，真实项目暂缓。 |

## 软件设计六大原则（设计指导）

| 原则 | 含义 |
|---|---|
| 单一职责原则 | 一个模块只承担一类职责，避免多个变化原因耦合在一起。 |
| 开闭原则 | 对扩展开放，对已有代码的修改保持克制。 |
| 里氏替换原则 | 子类型应能替换其基类型，并保持原有契约成立。 |
| 接口隔离原则 | 使用方只依赖它需要的接口，避免臃肿接口。 |
| 依赖倒置原则 | 高层策略和底层实现共同依赖抽象，而非高层直接依赖细节。 |
| 迪米特法则 | 对象尽量只与直接协作者交互，减少对其他对象内部结构的了解。 |

当前骨架只验证 gRPC 通道和 Java 原生 FFI；尚无索引、配置解析、worker IPC、SQLite 或查询。

常用命令见 `justfile`；`just smoke` 完成编译和两条 Hello 链路的本地验证。

```text
cargo build --workspace
cargo run -p codeana-daemon
cargo run -p codeana-cli -- hello
JAVA_HOME=/path/to/graalvm-jdk-25 mvn -f lang-adaptor/pom.xml package
JAVA_HOME=/path/to/graalvm-jdk-25 ./scripts/build-java-native.sh
cargo run -p codeana-java-worker -- lang-adaptor/target/native/libcodeana_java.dylib
```

完整本地验证见 `scripts/smoke.sh`。
