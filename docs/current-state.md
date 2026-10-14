# 当前实现与验证边界

对齐日期：2026-10-14。源码为准，历史回执按其提交和产物解释。

| 边界 | 当前实现 | 核查入口 |
|---|---|---|
| 服务 | Rust CLI → loopback gRPC → 用户级 Rust daemon | `crates/cli/`、`crates/daemon/`、`proto/codeana/v1/task.proto` |
| 分析 | Rust 独立 worker → C ABI → Java 25 Native Image → 目标 Java 8 javac 子进程 | `crates/java-worker/`、`lang-java-adapotr/` |
| 后端 | javac 唯一后端；显式 JVM 后端供开发/诊断，无自动回退 | `crates/application/src/java.rs` |
| GC | Oracle GraalVM G1 默认；每进程仅创建一次 isolate，健康任务复用，故障后换进程 | `crates/java-worker/src/native.rs` |
| 输出 | GRA1 + protobuf Row；JSON 仅用于控制输入、metadata/attributes 和查询展示 | `proto/codeana/v1/graph.proto`、`crates/contracts/src/graph_archive.rs` |
| 存储 | 不可变 SQLite revision；私有 candidate 校验、落盘后 catalog CAS | `crates/storage/src/graph_store.rs`、`crates/storage/src/lib.rs` |
| 紧凑布局 | 整数引用、路径/外部目标字典、重复大 metadata 共享、元注解定义/归属分离 | `crates/storage/src/compact-schema.sql`、`compact-meta.sql` |
| 配置 | 显式启用 Java；plain 显式源码根，Maven auto 需已导出的 inputs_manifest；仅解析字段另标注 | `crates/config/src/lib.rs` |
| Rust 源码分析 | rust-analyzer 仍为后续设计；mock 只回放冻结 fixture，不分析任意 Rust 源码 | [Rust worker](modules/rust-worker.md) |

## Rust 结构改造

2026-10-14 已落地请求/状态/错误枚举、显式 protobuf 映射、类型化查询记录与稳定属性、按能力拆分的读接口、索引阶段和任务/发布端口。运维、Maven/Java 控制结构有明确 DTO；FILES 不实现语义图，内部整数主键不进入查询属性。公开协议、图身份和存储版本保持。详见 [实现与验证边界](implementation/rust-type-refactor.md)。

本次改造已通过 Rust 完整门禁（含图 13 项、文本 2 项、标注 4 项及 clippy）、Java fixture、Native manifest/ABI/图/环境/真实崩溃、显式 JVM 图 5 项以及 Rust/Java 分发回归。Native 首次安装因活跃 JVM 被保护逻辑拒绝，等待退出后完成剩余门禁；详见 [验证记录](implementation/rust-type-refactor.md#验证)。以下 2026-10-13 真实项目单轮仍按原产物解释，不作为改造后的容量或稳定性能结论。

## 版本

| 项目 | 当前值 | 说明 |
|---|---|---|
| CLI-daemon handshake | 3 | `DAEMON_PROTOCOL_VERSION` |
| worker protocol | 18 | `WORKER_PROTOCOL_VERSION` |
| graph model | 2 | `codeana_model::VERSION`；manifest 仍沿用 `fact_ir` 键 |
| analysis request/result | 9 | Java bridge 与 Rust host 必须成套 |
| C ABI | 4 | 共享库/header/host 必须成套 |
| javac 模型 / compiler batch | javac-model-v50 / 2 | 与 worker 协议独立 |
| 图库物理格式 | user_version 7 | Rust 紧凑布局 |
| 上游逻辑格式 | schema 33 / GraphSemantics 26 | `project_meta.schema_version` 与物理格式不是同一个版本 |
| adapter | anatomist-4a903272-codeana-graph2 | 当前上游参考 `4a903272c22083585107e982f2899ed03a280aa2` |
| catalog / 人工标注库 | 2 / 2 | 独立事务升级，保留人工正文 |
| Native 构建 JDK | 25.3.4+1.r25-graal | `lang-java-adapotr/.sdkmanrc` 固定 Oracle GraalVM |

图 schema 6 及更旧格式的查询/增量返回 `GRAPH_SCHEMA_REBUILD_REQUIRED`。执行 `index --full` 创建新 revision，成功后切 head；旧文件保留，不原地迁移。历史图查询也要求兼容物理格式。

## 最新单轮结果

| 指标 | 2026-10-13 紧凑存储结果 | 口径 |
|---|---|---|
| 冻结 iopengw | HEAD `267c7ced4993aa88613f8f17a8099943fec98159`，MAIN + TEST | 仅已提交内容 |
| 索引 | 214.28 s | 一轮 Native/G1 全量；无 native 冷热分类 |
| 图库 | 233,488,384 bytes = 233.49 MB = 222.67 MiB | 发布的单个 facts.db；此前 606,650,368 bytes，减少 61.51% |
| worker 进程树峰值 | 2.699 GiB | 同时刻 host/原生库 + javac RSS |
| 正确性 | 12 个逻辑表多重集一致；integrity/FK/FTS 通过 | 已有 854 个 partial 文件保留，不声称语义全覆盖 |
| 验证 | Java 636 项；Rust 完整门禁；ABI 8 项；图 6 项；真实 SIGSEGV 和 release 门禁 | 本轮已执行范围 |

214.28 s 属于索引实测产物；随后修复 search-code 对逻辑视图使用 rowid 的错误，在同一库上重查 6 项，未再索引。原测量回执的查询失败保留，汇总回执记录后续验证。单轮数据不构成稳定性能承诺；历史 G1 234.88 s 与本轮环境/变更不同，不能全部归因到数据库压缩。

汇总回执：`target/compact-storage-upgrade-01/implementation-receipt.json`；单轮原回执、查询修复及未完成门禁详见 [存储实施记录](implementation/anatomist-storage-migration.md)。target 是可清理的本机证据位置，文档中的路径不保证在每个 checkout 存在。

## 支持范围与未完成项

| 已验证范围 | 仍未关闭 |
|---|---|
| macOS ARM64、目标 Java 8、G1 正常产品链路、全量/增量与公开图查询 | Linux 和目标 Java 17/25 |
| Maven 外部 metadata、Lombok 声明模型、Spring/SOFA 已发布静态图 | Gradle、全部框架规则和跨语言语义 |
| 紧凑存储、历史/源码/分页、人工标注保全、故障保旧版 | 当前版本完整语义/来源覆盖验收；旧 raw transport 门禁已删除 |
| fixture 和一次冻结真实项目测量 | 独占容量/稳定性能验收；`native_accepted`、`performance_accepted` 仍为 false |

开发 build-only、Hello、fixture、旧版本完整回执均不能代替当前版本正式验收。验证顺序和命令见 [实施与验收](delivery.md)。
