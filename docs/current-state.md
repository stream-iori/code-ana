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

## Rust 结构与回归

请求/状态/错误枚举、显式 protobuf 映射、类型化查询记录与稳定属性、按能力拆分的读接口、索引阶段和任务/发布端口已落地。运维、Maven/Java 控制结构有明确 DTO；FILES 不实现语义图，内部整数主键不进入查询属性。类型与职责约定见 [架构](architecture.md)，编码及旧页兼容见 [契约](contracts.md)。

以下类型改造回执属于 Maven 降级改动前的 worker 18 / request 9；新增版本回归见下一节，不将旧回执重标为新产物。

| 门禁 | 2026-10-14 类型改造阶段结果（macOS ARM64） |
|---|---|
| `just test-rust` | DDL、fmt、workspace、图 13 项、文本 2 项、标注 4 项、clippy 通过 |
| `just test-java` | Java fixture、桥与模型通过 |
| Native | G1 开发构建与安装、manifest 5 项、ABI 生命周期 8 项、图 6 项、环境检查及真实 SIGSEGV 恢复通过 |
| 显式 JVM 产品 | 图 5 项通过，无预期项漂移 |
| Rust release | checksum 通过；包内图 13 项中执行 8 项、跳过 5 项故障注入；manifest 从现行常量生成，版本为 3/18/2 |
| Java release | release workspace 构建、checksum、隔离安装运行及原 Cargo 路径恢复通过 |

Native 首次安装因活跃 JVM 触发 `STOP_RUNTIME_BEFORE_NATIVE_INSTALL:97923`，未绕过保护；等待 JVM 退出后完成安装和剩余门禁。Rust 图门禁首次发现故障注入文本多了空格，恢复 `TEST_FAULT:migration` 原拼写后完整重跑通过。新增回归覆盖 wire 枚举拒绝、上下文包装后的取消身份、属性缺失/null/扩展透传、类型化端点、旧页读取及内部主键隐藏；既有测试覆盖 full/delta、冻结历史、分页、CAS、取消、重启和发布后失败。

本机汇总：`target/rust-types-refactor/validation.json`；显式 JVM 图证据：`target/rust-types-refactor/jvm-graph`；Java release 回执：`target/java-alignment/evidence/release-package-20261014-115048-15443/receipt.json`。本次未重测冻结真实项目；以下 2026-10-13 单轮按原产物解释，不作为类型改造后的容量或稳定性能结论。

## Maven 依赖失败后的源码分析

独立导出器现在使用 `maven-inputs-v2` / manifest schema 4。有效模型已校验后，外部依赖解析失败或 jar 缺失可整次转为源码分析；保留 reactor、源码根与 MAIN/TEST，丢弃全部外部 classpath。索引只读 manifest，不启动 Maven；超时、取消、模型变化、损坏回执和清理失败仍中止。schema 3 必须重新显式导出，旧图无需因本次功能升级改物理格式。

三态依赖模式贯通 worker 19 / analysis request-result 10、报告与签名缓存。失败模式输出 `CLASSPATH_UNAVAILABLE`、`analysis_mode=source_only`、`classpath_status=unavailable`，即使未引用缺失依赖也不声明语义完整；`--strict` 返回 3 并保留旧 head。模式切换全量重建；必要的未选中 MAIN 上游只供分析，不发布。详情见 [配置](configuration.md#maven-依赖失败与源码模式)。

| 本次验证（macOS ARM64 / 目标 Java 8） | 结果 |
|---|---|
| Rust 完整门禁 | workspace、DDL、fmt、图 13 项、文本 2 项、标注 4 项、clippy 通过；含快速退出、终态冲突、超时不降级、schema 4 和全局 coverage 回归 |
| Java / Maven fixture | 初始完整组件回归通过；状态与缓存改动后 13 项关联专项通过；21 项 Maven 对齐及导出 fixture 通过。636 条组件记录为分批执行，未冒充单次最终源码完整验收 |
| JVM / Native 产品 | 最终 JVM 图 6 项、Native 图 7 项、G1 生命周期 8 项通过；覆盖失效/恢复、strict、增量/全量、历史报告、未选模块及无变更复用 |
| Native 故障与安装 | manifest 5 项、错误 JDK、独占真实 SIGSEGV 通过；安装/运行时工具单元回归通过 |
| Java 分发包 | checksum 与执行路径字节核对、release 图 7 项、错误 JDK、随包 fixture 自动 daemon / index / calls / source 通过；原 debug 二进制已恢复，源码和包产物无漂移 |

汇总：`target/maven-fallback/validation.json`；JVM、Native、ABI、分发回执分别在该目录的 `jvm-graph-sealed`、`native-graph-final`、`native-lifecycle-sealed`、`release-sealed`。原失败回执及原因保留在 `preparation-failures.json` 与对应目录：包括旧 extension、安装保护、JSON 键序比较、终态读取时序，以及误并行故障注入。真实崩溃测试新增独占检查，已串行复验；库 SHA 已恢复核对。

仅选择性借鉴 Anatomist `e8a940557c9365084cdd5318ed2be3d147697113` 的行为与测试场景，来源/覆盖清单记录补充引用，整体上游基线仍为 `4a903272...`。本次不重测冻结 iopengw，不新增容量/稳定性能结论，`native_accepted` / `performance_accepted` 仍为 false。

## 版本

| 项目 | 当前值 | 说明 |
|---|---|---|
| CLI-daemon handshake | 3 | `DAEMON_PROTOCOL_VERSION` |
| worker protocol | 19 | `WORKER_PROTOCOL_VERSION` |
| graph model | 2 | `codeana_model::VERSION`；manifest 仍沿用 `fact_ir` 键 |
| analysis request/result | 10 | Java bridge 与 Rust host 必须成套 |
| C ABI | 4 | 共享库/header/host 必须成套 |
| javac 模型 / compiler batch | javac-model-v50 / 2 | 与 worker 协议独立 |
| 图库物理格式 | user_version 7 | Rust 紧凑布局 |
| 上游逻辑格式 | schema 33 / GraphSemantics 26 | `project_meta.schema_version` 与物理格式不是同一个版本 |
| adapter | anatomist-4a903272-codeana-graph2-maven-fallback1 | 当前上游参考 `4a903272c22083585107e982f2899ed03a280aa2` |
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
| 目录逻辑峰值 | 2.35 GiB | 既有流程 2.69 GiB；不等于最终图库大小 |
| 逻辑记录 | MAIN / TEST 节点 38,839 / 16,561；元注解 56,957 条 | 与原图库相同；元注解 341 个定义、3,403 个归属来源 |
| 字典 | 路径 4,317、外部目标 7,182、共享 metadata 2,688 条 | integrity/FK/FTS 通过，最终无空闲页 |
| 正确性 | 12 个逻辑表多重集一致；integrity/FK/FTS 通过 | 已有 854 个 partial 文件保留，不声称语义全覆盖 |
| 验证 | Java 636 项；Rust 完整门禁；ABI 8 项；图 6 项；真实 SIGSEGV 和 release 门禁 | 本轮已执行范围 |

首次测量准备因缺少独立应用状态标记被采集器拒绝，尚未启动索引，原回执保留；补齐后仅运行一次全量索引。214.278 s 属于该次实际索引流程；发布及 12 表逻辑多重集比较成功，随后 `search-code` 因逻辑视图沿用 `nodes.rowid` 查询失败。

修复将 FTS 整数主键映射到稳定 ID，归档原测量二进制及查询源码，在同一已发布库重查 6 项通过；图库 SHA 不变，未再索引，原失败回执保留。该时长不冒充修复后测量。既有 G1 流程 234.879 s、进程树峰值 2.856 GiB，与本轮环境/变更不同，不能全部归因到数据库压缩；单点不构成稳定性能承诺。

该轮 Java 636 项组件、Rust 完整门禁（31 项存储，其中 5 项紧凑布局）、G1 生命周期 8 项、Native 产品 6 项、真实 SIGSEGV 和 release 图/错误 JDK/自动 daemon 三组验收通过。release 字节临时安装到原执行路径，验证后恢复 dev 二进制；fixture 和该次单轮不签完整语义/来源覆盖或容量/稳定性能验收。

| 本机回执 | 路径 |
|---|---|
| 原测量（保留查询失败） | `target/compact-storage-upgrade-01/receipt.json` |
| 同库查询复验 | `target/compact-query-recheck-01/receipt.json` |
| 汇总 | `target/compact-storage-upgrade-01/implementation-receipt.json` |

target 是可清理的本机证据位置，上述路径不保证在每个 checkout 存在；历史源码及文档由 Git 保留。验证入口和维护规则见 [实施与验收](delivery.md)。

## 支持范围与未完成项

| 已验证范围 | 仍未关闭 |
|---|---|
| macOS ARM64、目标 Java 8、G1 正常产品链路、全量/增量与公开图查询 | Linux 和目标 Java 17/25 |
| Maven 外部 metadata、Lombok 声明模型、Spring/SOFA 已发布静态图 | Gradle、全部框架规则和跨语言语义 |
| 紧凑存储、历史/源码/分页、人工标注保全、故障保旧版 | 当前版本完整语义/来源覆盖验收；旧 raw transport 门禁已删除 |
| fixture 和一次冻结真实项目测量 | 独占容量/稳定性能验收；`native_accepted`、`performance_accepted` 仍为 false |

开发 build-only、Hello、fixture、旧版本完整回执均不能代替当前版本正式验收。验证顺序和命令见 [实施与验收](delivery.md)。
