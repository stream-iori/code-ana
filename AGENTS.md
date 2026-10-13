# code-ana 开发约定

| 边界 | 约定 |
|---|---|
| 架构依据 | 先读 `docs/architecture.md`、`docs/contracts.md` 和对应模块文档。设计与实现冲突时，查证后同步修改。 |
| CLI / daemon | Rust；CLI 与本机用户级 daemon 使用 HTTP/2 + protobuf（gRPC）。项目在 daemon 内隔离。 |
| Java | Java 代码放在 `lang-java-adapotr/`（旧 `lang-adaptor/` 已删除）；原生适配器使用 Java 25 + GraalVM Native Image 生成共享库；独立 Rust worker 通过 C ABI / FFI 调用。禁止 JNI。 |
| Java 后端 | 仅采用 javac；不引入 JavaParser/SymbolSolver，也不提供回退。原生库协调目标 Java 8 javac 子进程；桥的 Java 8 字节码与 Java 25 原生库分开构建。显式 JVM 仅供开发/诊断，无自动回退。 |
| JDK 选择 | `lang-java-adapotr/.sdkmanrc` 固定 GraalVM JDK 25，Oracle `25.3.4+1.r25-graal`；进入该目录执行 `sdk env`。默认 Native GC 为 G1；显式 Serial 仍支持。 |
| 配置 | 语义索引须在 `.code-ana/config.toml` 显式启用语言；FILES 和工作区文本搜索可无语言配置。Maven 自动展开模块需外部准备的 `inputs_manifest`；Lombok 配在 `[languages.java.lombok]`。 |
| 构建 | 产品只给目标项目的构建建议，不自动运行目标项目的编译命令或注解处理器。禁用处理器、无业务字节码生成的 javac parse/analyze 属于索引分析。开发和测试可显式编译本项目与 fixture。 |
| 存储 | GRA1/protobuf 行交付；Rust 图库物理 schema 7、上游逻辑 schema 33。公开 ID 不变，内部整数主键不输出。旧格式查询/增量要求 `index --full`，成功建新 revision 才切 head。 |
| 生命周期 | G1 每个 worker 进程仅创建一次 isolate，健康任务复用；teardown 后也不在同进程重建，取消/致命失败由 supervisor 换进程。 |
| 验证顺序 | Java fixture 优先；能力对齐不要求 Anatomist 协议兼容；随后验收冻结的 iopengw HEAD 267c7ced4993aa88613f8f17a8099943fec98159，仅已提交内容。Native 不分冷热；性能只做一轮，固定输入、空应用状态、预算和产物，保留原失败回执。 |

## 软件设计六大原则（设计指导）

| 原则 | 含义 |
|---|---|
| 单一职责原则 | 一个模块只承担一类职责，避免多个变化原因耦合在一起。 |
| 开闭原则 | 对扩展开放，对已有代码的修改保持克制。 |
| 里氏替换原则 | 子类型应能替换其基类型，并保持原有契约成立。 |
| 接口隔离原则 | 使用方只依赖它需要的接口，避免臃肿接口。 |
| 依赖倒置原则 | 高层策略和底层实现共同依赖抽象，而非高层直接依赖细节。 |
| 迪米特法则 | 对象尽量只与直接协作者交互，减少对其他对象内部结构的了解。 |

现行说明先读 [文档导航](docs/README.md)、[当前实现](docs/current-state.md)、[架构](docs/architecture.md) 和 [契约](docs/contracts.md)。协议版本：daemon 3、worker 18、graph model 2、analysis request/result 9、C ABI 4；javac-model-v50 / compiler batch 2。参考 Anatomist 固定 `4a903272c22083585107e982f2899ed03a280aa2`，来源清单保留各自提交，不能把 sibling HEAD 或旧验收回执当作当前依据。

## 开发与验证

| 场景 | 命令 / 约束 |
|---|---|
| Rust 回归，无 JDK | `just test-rust`；含生成 DDL 检查、workspace、图/文本/标注 E2E、clippy |
| Java fixture | `just test-java`，优先验证分析与模型 |
| Native 开发构建 | `./scripts/build-java-native.sh --build-only`；默认 G1，不签正式验收 |
| Native 安装与产品回归 | `just test-java-native`；安装 SDK、ABI 生命周期、图与真实崩溃 |
| Java 分发回归 | `just test-java-release`；原 Cargo 路径暂存 release 后恢复，禁止混装/活跃进程替换 |
| 正式构建 | 不带 `--build-only` 时要求 `CODEANA_JVM_ACCEPTANCE_RECEIPT`；详见构建脚本 |
| 紧凑 DDL | 改 `anatomist-schema.sql` / `compact-meta.sql` 及生成器，再生成 `compact-schema.sql`；`python3 scripts/generate-compact-schema.py --check` |
| mock | 独立 home + 显式 `--dev-mock-fixtures`，仅 Rust 产品回归，不是 Rust/Java parser |

不能把 Hello、开发构建、旧版本或 fixture 的通过写成当前正式放行；Linux、目标 Java 17/25、Rust 语言分析、完整迁移覆盖与容量/稳定性能仍有未完成项。当前边界及最新 214.28 s / 233.49 MB 单轮记录见 `docs/current-state.md`。

`lang-java-adapotr/e2e/fixtures/*/AGENTS.md` 是 Anatomist 参考场景的分析输入，保留其原始 CLI/编辑边界，不作为 code-ana 根目录开发命令。

```sh
cargo build --workspace --locked
cargo run -p codeana-cli -- hello
./scripts/build-java-native.sh --build-only
python3 scripts/install-java-native.py
```

常用入口以 `justfile` 为准。`just smoke` 仅验证 gRPC/FFI Hello，内部调用正式 Native 构建并要求有效验收回执；不替代 javac/图产品测试。新增或修改能力时同步现行 docs；历史文档标明版本与已退役路径，不改写原测量和失败记录。
