# 当前实现与验证边界

对齐日期：2026-10-15。源码为准，历史回执按其提交和产物解释。

| 边界 | 当前实现 | 核查入口 |
|---|---|---|
| 服务 | Rust CLI → loopback gRPC → 用户级 Rust daemon | `crates/cli/`、`crates/daemon/`、`proto/codeana/v1/task.proto` |
| 分析 | Rust 独立 worker → C ABI → Java 25 Native Image → 目标 Java 8 javac 子进程 | `crates/java-worker/`、`lang-java-adaptor/` |
| 后端 | javac 唯一后端；显式 JVM 后端供开发/诊断，无自动回退 | `crates/application/src/java.rs` |
| GC | Oracle GraalVM G1 默认；每进程仅创建一次 isolate，健康任务复用，故障后换进程 | `crates/java-worker/src/native.rs` |
| 输出 | GRA1 + protobuf Row；JSON 仅用于控制输入、metadata/attributes 和查询展示 | `proto/codeana/v1/graph.proto`、`crates/contracts/src/graph_archive.rs` |
| 存储 | 不可变 SQLite revision；私有 candidate 校验、落盘后 catalog CAS | `crates/storage/src/graph_store.rs`、`crates/storage/src/lib.rs` |
| 紧凑布局 | 整数引用、路径/外部目标字典、重复大 metadata 共享、元注解定义/归属分离 | `crates/storage/src/compact-schema.sql`、`compact-meta.sql` |
| 配置 | 显式启用 Java；plain 显式源码根，Maven auto 需已导出的 inputs_manifest；仅解析字段另标注 | `crates/config/src/lib.rs` |
| Rust 源码分析 | rust-analyzer 0.0.349 独立 worker；冻结输入、声明/引用/调用、world 与增量替换；sysroot/过程宏/动态派发仍有缺口 | [Rust worker](modules/rust-worker.md) |

## Java 目录命名（2026-10-15）

Java 源码统一位于 `lang-java-adaptor/`，Maven 父 artifactId 同名；底层构建和 manifest 脚本分别为 `scripts/build-lang-java-adaptor.sh`、`scripts/java-adaptor-manifest.py`。Rust 开发路径、构建/安装/分发、测试及来源清单同步使用新名称。CLI/daemon/worker 仍从原 Cargo 路径执行，协议和图库版本保持；历史本机回执按原路径和产物解释，不改写旧证据。

| 更名回归（macOS ARM64 / 目标 Java 8） | 结果 |
|---|---|
| 源码与入口 | 615 个文件移动；其中 604 个文件字节不变，其余仅修正 POM/来源清单中的拼写；Java/C/header 字节不变，546 个来源文件校验通过，现行引用无旧拼写 |
| Rust / Java / Maven | Rust 完整门禁 181 项通过、1 项需 G1 的测试跳过；Java fixture 636 项、路径工具 28 项、Maven 导出与 21 项对齐通过 |
| JVM / Native | JVM 图 7 项；新目录 Native 开发构建/安装、G1 生命周期 8 项、图 8 项、错误 JDK 和独占真实 SIGSEGV 恢复通过 |
| 分发 | release 构建、包 checksum、原执行路径图 8 项/错误 JDK/随包 fixture 通过；源码及产物无漂移，原 debug 字节已恢复 |

回执：`target/java-adaptor-rename/validation.json`。Native 重建在完整 Java fixture 通过后使用 `--build-only -DskipTests`，ABI/产品回归另行执行；不签正式迁移或容量验收。本轮不重测 iopengw。

## Rust 可维护性重构（2026-10-15）

| 改动 | 保持的边界 |
|---|---|
| Rust/fixture 图行改用具名结构，关键身份与状态改用 newtype / enum | 列顺序、NULL/空值、metadata 原文、公开 ID 和协议版本不变；Java 原始行不重编码 |
| 拆分配置与捕获、Java 准备、Maven 导出、HIR 采集和 Rust 索引分支 | 原路径/文件名、临时资源寿命、取消、增量和一次 CAS 发布保持 |
| `TaskRepository` / `IndexRepository` 收窄访问；Engine 状态私有 | 候选和基线使用关联类型；报告/历史/维护仍依赖具体存储，不宣称应用层完全解耦 |
| 结果按业务字段稳定排序，冻结页保存可选排序策略 | 新首页顺序允许变化；相同键不去重，旧页按保存顺序重放 |
| Java 分发同时携带 Rust 语言 worker 和输入导出器 | 混合语言使用同套产物，release 在原 Cargo 路径暂存并恢复 |

本轮按用户要求不做 iopengw 验收，不更新历史性能结论。验证证据位于 `target/rust-maintainability/`；具体排序和数据边界见 [契约](contracts.md)，职责与端口见 [架构](architecture.md)。

| 重构回归 | 已执行结果 |
|---|---|
| Rust | workspace 181 项通过、1 项需 G1 的测试跳过；图 13、真实 Rust 5、文本 2、标注 4，以及 fmt / DDL / clippy 通过 |
| 基线等价 | 12 个逻辑表和 5 类查询内容无差异；新查询顺序按业务键变化，旧冻结页兼容测试通过 |
| Java / Maven | `just test-java`、Maven 导出 fixture 与 21 项对齐通过；Java 源码无改动 |
| 显式 JVM | 图 7 项通过，含真实 Java/Rust 混合 full/delta 等价、两种语言保留及 strict 失败保旧 head；用例与产物无漂移 |
| Native | G1 开发构建、manifest 5 项、ABI 生命周期 8 项、图 8 项、错误 JDK 及独占真实 SIGSEGV 恢复通过；不签正式 Native 或性能放行 |
| 分发 | release workspace、完整包 checksum、原路径图 8 项、错误 JDK、随包 fixture 自动启动/index/calls/source 通过；包与源码无漂移，原 debug 字节已恢复 |

最终汇总：`target/rust-maintainability/validation.json`；完整 JVM 图、最终混合复验和分发证据分别为该目录的 `jvm-graph-complete`、`jvm-mixed-verified`、`release`。Native 完整命令日志为 `native-gate.log`，最终 Rust 门禁为 `rust-gate-verified.log`。基线比较保留图 hash/重复次数，查询按记录多重集比较；旧页兼容由应用层回归覆盖。准备过程的序列化、路径和用例登记问题保留在 `preparation-failures.json`，未将首次六项 JVM 回执当作七项通过。

## Rust 存储压缩与有界流水线（schema 9）

Rust 单语言已直接复用 GraphStream：blocking HIR/记录生成 → 4 批 / 8 MiB 队列 → async IPC → 有界队列 → blocking SQLite。独立控制读取保留半帧，满队列/写阻塞仍可取消；只有 VerifiedEnd 后才能封存。混合语言继续采用已验证归档合并。活动 blob pin 使用引用计数，文件 I/O 共享并发上限 2，复制/校验/文件同步移出全局锁，最终持久化和 CAS 屏障保留。

schema 9 将 Rust 路径/world/key 字典化、依赖表改为 WITHOUT ROWID；依赖从状态 JSON 去重，baseline 批量还原。公开图 ID、worker 20、graph model 3、逻辑 schema 33 不变；schema 8 及更旧图必须 `index --full`。实现同时加入行首索引、owner 分组、任务内源码定位/path 缓存、接口摘要排除嵌套执行体，以及无变化跳过 HIR。

| 指标 | 优化前参考 | 本轮固定输入 |
|---|---:|---:|
| 内部 index 耗时 | 66.155 s | 54.453 s（参考差值 -17.7%） |
| CLI 墙钟 | 未记录 | 55.576 s |
| facts.db | 70,434,816 bytes / 67.17 MiB | 9,777,152 bytes / 9.32 MiB（-86.1%） |
| worker 监督器采样峰值 | 340.45 MiB | 339.67 MiB |
| daemon 独立峰值 | 未采样 | 96.52 MiB |
| 同一时刻 daemon + worker 树 RSS 峰值 | 未采样 | 435.22 MiB |
| blobs | 1,511 / 18,602,179 bytes | 相同 |
| 节点 / 边 / 调用点 / 调用目标 | 2618 / 6710 / 10545 / 1257 | 相同 |
| 增量依赖 | 109,394 | 相同，包括失败 lookup |

环境为 macOS ARM64 / debug，空独立 home、512 MiB worker、300 s 期限。冻结输入 SHA 与旧参考一致，原源码/依赖逐文件对照旧 archive 后运行，仅执行一轮；新二进制 SHA、输入、源码 archive 和失败历史各自保留。回执：`target/rust-dogfood/optimized-512m/receipt.json`；公开节点、边 hash、调用点 hash、调用目标和全部依赖逐项相等：同目录 `equivalence.json`。

worker 阶段：数据库加载 0.254 s、声明 12.856 s、绑定 28.291 s、最终增量摘要 0.209 s、行生成及背压 1.831 s。daemon `analyze_ms=46.534 s` 包含候选写库完成，发布 7.633 s；SQLite 阶段合计 4.757 s，与传输重叠，不能再次累加。worker/daemon 队列估算峰值分别 942,362 / 979,625 bytes，发送等待约 0.639 / 0.112 s，消费等待约 0.244 / 0.733 s。

外部进程树采样 worker 峰值为 341.72 MiB，与监督器 339.67 MiB 因采样时刻不同；435.22 MiB 是同一采样的总和，不是独立峰值相加。0.78 MiB 的监督器差异不足以宣称显著内存收益。默认请求仍为 256 MiB，本轮不声称默认预算已跑通；历史 256 MiB 失败回执不变。

当前源码另做功能 dogfood，初次 full 产生 2,690 节点、6,895 边、10,863 调用点、1,295 目标，图库 10,203,136 bytes；回执 `target/rust-dogfood/optimized-functional/receipt.json`。它与固定旧输入测量分开。同目录 `validation.json` 已通过三个自身符号查询、无变化跳过 HIR、方法体修改仅改变一个 owner 的 source hash 且全部 interface hash 保持、delta/full 图逐项相等，以及恢复原源码后的图一致。

当前回归：Rust workspace 174 项通过、1 项需 G1 的测试跳过；真实 Rust 导出/E2E 5、公共图 13、文本 2、标注 4，以及证据保护/采样口径 2 和 bundle 版本生成 1 项通过。Java fixture 636 项、显式 JVM 产品图 6 项通过。新增半帧控制取消覆盖未消费输出，新增坏 owner/旧 schema、增量依赖往返、嵌套方法体和 GC/共享 hash 验证。fmt、clippy、DDL 与脚本检查通过。

JVM 回归曾被生成脚本写死 graph model 2 阻塞；已让 JVM/Native manifest 从 Rust 常量生成，打包/运行时按 manifest 核对，并补未来版本回归。准备失败原因保留于 `target/rust-dogfood/optimization-java-graph-preparation-failure.json`。本轮未重建 Native、未做 release/正式 Native 验收。汇总回执 `target/rust-dogfood/optimization-validation.json`，其中二进制 SHA 与单轮回执一致。

上述旧值来自功能参考记录，新轮加入更细诊断；单样本不证明稳定加速或完整语义，仍为 87 文件 partial，`performance_accepted=false`。版本边界及宏/sysroot 限制保持。

## Rust 输入收敛与内存诊断（schema 2，优化前历史记录）

当前采用项目源码加一层 Cargo 直接依赖；依赖只辅助解析，不发布自身图。项目与依赖目录规则分别配置，在复制/捕获前过滤；辅助文件按白名单加载。主动宏展开已关闭，HIR 内部仍可按需展开声明宏；过程宏不执行。旧 schema 1 输入要求重导，adapter/profile 改变后首次全量清除旧宏生成事实。

| 当前验证 | 结果 |
|---|---|
| Rust 门禁 | workspace 167 项通过、1 项需 G1 的测试跳过；真实 Rust 导出/E2E 5 项、公共图 13 项、文本 2 项、标注 4 项通过；clippy、fmt、DDL、脚本语法及 diff 检查通过 |
| 输入边界 | 项目 → A → B → C 仅保留项目与 A；过滤后的旧复制文件不进入捕获/HIR；MAIN/TEST、别名、cfg、strict、delta/full 保持回归 |
| 预算 | Rust 默认请求 256 MiB，项目配置可修改且不超过用户上限；只改预算无需重导、保持语义 profile；doctor 披露请求与上限 |
| 单轮环境 | macOS ARM64 / debug；空独立 home，256 MiB worker、512 MiB 输出、2,000,000 fact、300 秒期限；产物与冻结输入 SHA 随回执保存 |
| 实际输入 | 274 个候选实例保留 46 个，其中 12 个发布项目实例；过滤 663 个源码实例；实际 1,529 文件/实例，18,237,477 bytes |
| 测量结果 | 21.773 s 后 `WORKER_MEMORY_BUDGET`；最后阶段 `bindings`，触发采样/已见峰值 268,484,608 bytes（256.046875 MiB），限额 268,435,456 bytes |
| 发布与证据 | 无 revision 发布；`target/rust-dogfood/memory-v2/receipt.json`、输入清单及 `frozen-sources.tar.gz` 保留，不加预算、不重复测量 |
| 512 MiB 功能验证 | 同一二进制/输入 SHA，仅修改项目预算和独立用户上限；成功发布 `rev_802cbd117e25451f85ff312ba2a2b32a`，采样峰值 356990976 bytes（340.45 MiB）；87 文件 partial，三个自身符号查询通过 |

该采样是终止前已见的峰值，不能推断完成索引所需的最低内存，也不能将失败中止耗时用于速度比较。本轮尚未在默认预算下完成自身索引，`performance_accepted=false`。当前产物未重新执行 release 或 Java Native 正式验收。配置、过滤语义和命令见 [Rust worker](modules/rust-worker.md)。

512 MiB 功能回执保存在 `target/rust-dogfood/functional-512m/receipt.json`。本次未重新导出输入；`stage_graph_records`、`analyze`、`index_rust` 均可定位。功能验证与原 256 MiB 固定预算测量分别保存，不作为新的稳定性能结论。

## Rust 首版自身验证（schema 1，历史回执）

以下为输入收敛前的历史产物。采用固定 `ra_ap_* = 0.0.349` 的 syntax/HIR API，不通过 LSP 自动加载工作区。`scripts/export-rust-inputs.py` 显式生成输入；索引不运行目标项目构建脚本或过程宏。适配器通过独立 worker 交付 GRA1，复用既有存储、查询和原子发布；混合项目一次切换 head。

| 验证 | 当前证据与边界 |
|---|---|
| Rust 门禁 | workspace 163 项通过、1 项需 G1 的测试跳过；真实 Rust E2E/导出 3 项、公共图 13 项、文本 2 项、标注 4 项通过；fmt、DDL、clippy 与 diff 检查通过 |
| 解析与增量 | 双 crate fixture 覆盖跨文件模块、别名/重导出、trait、声明宏、cfg、失败查找、取消、输入漂移；函数体局部重绑定及接口/重导出失效传播 |
| 产品 | 真实 Rust worker → store → CLI；MAIN/TEST world、resolve/structure/calls、delta/full 一致、strict 拒绝和过期输入保留旧 head |
| 混合存储 | 真实 Rust archive 与 Java fixture 行合并；Rust delta 保留 Java ID，未封口失败不发布。未重测当前协议的真实 Java Native 混合运行 |
| code-ana dogfood | 冻结的自身源码快照成功发布 `rev_e24ea07adaad4e61bed26f12bcfc199b`，3,649 节点、9,063 边、10,590 调用点、1,410 调用目标；图库 77,234,176 bytes |
| 自身查询 | `stage_graph_records`、`analyze`、`index_rust` 均可经现有 CLI 定位 |
| 覆盖 | dogfood 为 partial，88 文件有缺口；sysroot 未加载、过程宏不执行、动态派发及宏展开上限均明确记录 |

dogfood 使用独立 home、4 GiB worker 预算、512 MiB 结果预算及 2,000,000 fact 上限。功能回执保存在 `target/rust-dogfood/functional-receipt.json`，包含失败尝试及成功产物 SHA；快照早于最后的模块包含关系修正，不冒充最终源码逐字验收。默认 256 MiB 的首次性能尝试以 `WORKER_MEMORY_BUDGET` 失败，原回执 `target/rust-dogfood/receipt.json` 保留；后续仅功能验证，不报告性能通过或稳定容量结论。

当前 worker 20 / graph model 3 / 物理 schema 9 需要配套重建；旧图执行 `index --full`。下文 Java Native / release / 性能回执属于各自历史产物，不构成本次版本的正式放行。

Rust 分发脚本已包含真实 worker、导出器、fixture 和语言文档，release 门禁已增加真实 Rust E2E；本轮验证 debug 构建及脚本语法，未重新执行 release 构建和分发验收。

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
| worker protocol | 20 | `WORKER_PROTOCOL_VERSION` |
| graph model | 3 | `codeana_model::VERSION`；manifest 仍沿用 `fact_ir` 键 |
| analysis request/result | 10 | Java bridge 与 Rust host 必须成套 |
| C ABI | 4 | 共享库/header/host 必须成套 |
| javac 模型 / compiler batch | javac-model-v50 / 2 | 与 worker 协议独立 |
| 图库物理格式 | user_version 9 | Rust 紧凑布局 |
| 上游逻辑格式 | schema 33 / GraphSemantics 26 | `project_meta.schema_version` 与物理格式不是同一个版本 |
| adapter | anatomist-4a903272-codeana-graph2-maven-fallback1 | 当前上游参考 `4a903272c22083585107e982f2899ed03a280aa2` |
| catalog / 人工标注库 | 2 / 2 | 独立事务升级，保留人工正文 |
| Native 构建 JDK | 25.3.4+1.r25-graal | `lang-java-adaptor/.sdkmanrc` 固定 Oracle GraalVM |

图 schema 8 及更旧格式的查询/增量返回 `GRAPH_SCHEMA_REBUILD_REQUIRED`。执行 `index --full` 创建新 revision，成功后切 head；旧文件保留，不原地迁移。历史图查询也要求兼容物理格式。

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
