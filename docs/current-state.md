# 当前实现与验证边界

对齐日期：2026-10-20。源码为准，历史回执按其提交和产物解释。

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
| Python 源码分析 | Pyrefly 1.4.0 独立 worker；显式冻结输入、声明/引用/调用、保守增量与多语言发布；动态目标保留未知 | [Python worker](modules/python-worker.md) |

## 业务标注与人工笔记裁剪（2026-10-20）

| 项目 | 当前边界 |
|---|---|
| 产品范围 | 删除图内业务语义标注、独立人工笔记及 annotate；保留 Java 源码注解、元注解与文本搜索 |
| 版本 | daemon 4、graph model 4、物理 schema 11、逻辑 schema 34；worker 21、analysis 10、ABI 4 保持 |
| 数据 | 不迁移旧图或人工笔记；不再创建和读取 annotations.db；全量建立新 revision 后切 head |
| 来源 | 选择性借鉴 Anatomist 90e405b2ddc1f9cbd1790302c027293c06af6a91，整体参考与来源清单各自提交保持 |
| Java / Rust | Java fixture 641 项、来源审计、Rust 完整门禁通过；JVM 图 7 项、Native 图与生命周期各 8 项通过 |
| Python / 分发 | 核心、CLI、输入准备、Native 三语言组合及 Rust/Java/Python 分发回归通过；Java release 原路径执行后恢复原二进制 |
| 冻结项目 | 同一 iopengw 提交；变更前精确包与本轮图的 11 张剩余逻辑表按 JSON 值一致，6 类公开查询一致；integrity=ok、FK 违规 0，仍为 partial |
| 既有差异 | 与 2026-10-16 历史图相比，变更前后均少 3,737 条框架关系边、290 条文件依赖、3,463 条符号依赖；本轮未修复该既有缺口 |
| 原始字节 | nodes / edges / index_diagnostics / call_sites 的原始 metadata JSON 键序有差异；按值比较一致，原始图摘要和查询输出均保留 |
| 放行边界 | 无新增正式性能测量；不签完整语义/来源覆盖、Native 或容量/稳定性能放行；以下历史回执保持各自含义 |

本轮证据：[汇总回执](../target/business-annotations-removal/20261020/validation.json)、[变更前版本来源](../target/business-annotations-removal/20261020/prechange-provenance.json)、[冻结图与查询对照](../target/business-annotations-removal/20261020/iopengw-final/receipt.json)。变更前包的二进制 SHA 对齐已提交回执中的 `0c769f6`，其运行时代码与本轮开始时 HEAD 相同；未把 2026-10-16 旧图当作当前基线。

安全拦截导致的 Rust release SIGKILL、首次 protobuf 生成方式错误、Native 缺少 Row.world_ 反射配置及初次历史基线差异均保留原日志和回执；安全放行后沿原可执行路径重试。补齐反射字段后生命周期 8/8 通过。覆盖清单撤销了 4 组指向已删除测试的旧“已覆盖”映射，恢复为待补；保留映射使用本轮真实执行证据，整体覆盖清单仍未关闭。

## 统一输入准备（2026-10-19，Python v3）

| 项目 | 当前结果 |
|---|---|
| 产品入口 | 仅新增 `index --prepare`；普通 index 消费冻结输入；doctor 静态检查，不执行构建工具 |
| 配置 | `[inputs]` 声明多语言入口；独立 Maven reactor / Cargo workspace 分开展开；Python 默认隔离、显式依赖或共享环境 |
| Python 元数据 | 默认静态读取入口 pyproject，显式 module 源码根优先；没有 pyproject 仍可索引。schema 3 兼容 1/2，adapter v3；worker/graph/存储协议不变 |
| 降级 | 可信模型但依赖缺失保留 partial；无可信模型时只保留文件/文本，查询明确语义不可用；strict 拒绝保留旧 head，恢复后重建替换旧事实 |
| 故障 | 工具崩溃、超时、取消、清单损坏和配置冲突失败，不伪装为降级成功；准备清单和索引 head 各自原子提交 |
| 报告 | schema 2 全局摘要；JSON / Markdown 同源，不保存逐文件明细和完整配置，不新增 JSONL；真实项目 JSON 约 4.7 KB |
| 门禁 | `just test-rust`、Java fixture 641 项、Python 核心 20 项与导出器 5 项通过；独立工作区、三语言 Native、降级/strict/恢复、无 pyproject 显式根均有 fixture |
| 冻结项目 | 分发包经 `index --prepare` 重放同一 diorama-sdd 提交、615 文件，25/25 导航及三个公开查询通过；integrity=ok、FK 违规 0 |
| 完整性边界 | 与 v2 一致：30 complete、585 partial；保留 5 个真实源码外部缺失名称；未新增完整语义或性能认证 |

使用说明：[统一输入准备](modules/inputs.md)。本轮独立回执：[2026-10-19-unified-inputs](../lang-python-adaptor/validation/2026-10-19-unified-inputs/receipt.json)。安全机制 SIGKILL 原日志和重试日志均保留，可执行路径沿用；Java SDK 仍单独安装。v2 历史回执不改写。

## Python 配置与完整性修复（2026-10-19，v2）

| 项目 | 当前结果 |
|---|---|
| 可选元数据 | 显式 --pyproject 静态读取；无该文件时保留显式根流程。标准字段、setuptools 常规布局、TEST 的 pytest pythonpath；动态字段不执行，依赖版本不宣称已核验 |
| 冻结与兼容 | 输入 schema 2，接受旧 schema 1；读取的配置进入 CONTROL，变更要求重新导出。adapter v2 和后端提交严格核对；worker 21、graph 3、物理 schema 10 不变，v1 profile 不复用 |
| 完整性归因 | 全部诊断保留，区分 source / auxiliary / backend；后端来源不冒充捕获文件。使用实际反向依赖传播，未知影响单列上下文 partial，不广播到全部文件 |
| 提取修复 | 赋值左侧不记作读取引用；except 的 as 变量补齐声明。每 world 保存每类最多 3 个定位样本和后端目标证据 |
| 仓库门禁 | Java 641 项、Python 核心 20 项（含 subprocess helper）及导出器 5 项、共享 Rust 门禁、独立 Release 7 项 CLI、Native Java/Python 与三语言发布通过 |
| 冻结项目 | 同一 diorama-sdd 提交 31259b761a5ea77672d0e90bc9e2776e4dfe397e、615 物理文件；TEST 显式加入 tests 和 fixtures/e2e/layout13，25/25 导航与三个公开查询通过 |
| 缺失模块 | helpers / seed_remote_knowledge / update_remote_knowledge 在项目上下文中已解析；保留 jury/Codex 的 5 个外部名称。另完整暴露 25 个辅助依赖缺失名称，不能与旧的源码诊断口径混比 |
| coverage | 30 complete、585 partial；378 文件有带来源证据的依赖缺口，未知影响上下文 0、无证据传播标记 0。原先仅全局标记的 41 文件中，30 已完整，11 有实际依赖缺口 |
| 剩余提取缺口 | reference_target_not_extracted 涉及文件从 220 降至 153；Any、合成 callable 和其他未知仍明确保留，不代表完整语义或全库准确率 |
| 图规模 | 57,375 节点、264,013 条非调用边、66,775 调用位置、50,504 调用目标；4,019 诊断包含源码 1,689、辅助 2,319、后端 11；integrity=ok、FK 违规 0 |
| 性能边界 | 本轮只做功能重放，measured=false；没有新增正式性能测量，下面 v1 的 38.458 s 不能作为 v2 性能 |

最新回执：[2026-10-19](../lang-python-adaptor/validation/2026-10-19/receipt.json)。原始安全机制 SIGKILL、配置/测试失败和修复前功能回执均保留；可执行文件路径和名称沿用，旧 Release 字节另存本机备份。配置及查询边界见 [Python worker](modules/python-worker.md)。

## Python 初轮验收（2026-10-17，v1 历史）

| 项目 | 结果 |
|---|---|
| 核心实现 | 固定 Pyrefly 1.4.0，独立 Cargo workspace / worker；显式清单冻结；GRA1；Python 查询策略；多语言单次发布与完全相同输入复用路径 |
| 存储迁移 | 物理 schema 10 接受 Python 声明种类；禁止按同名为 Python 补边；旧图需 `index --full` |
| 已验证 | Java fixture（worker 21）、Native 生命周期/图库、Python CLI 六项（含九个探针）、Native/JVM 混合组合；核心和共享门禁见最新回执 |
| 分发 | 原路径 Release 构建和独立 home 的随包六项 CLI 通过；Rust/Python 包含锁文件、许可证清单、fixture 与 SHA256SUMS，Java SDK 单独安装 |
| 真实项目功能 | diorama-sdd 冻结提交 `31259b761a5ea77672d0e90bc9e2776e4dfe397e`，615 文件；23 个调用与 2 个字段引用共 25/25；Python 3.12/darwin 声明依赖的 wheel 字节冻结，不执行目标代码 |
| 失败保留 | 安全机制 SIGKILL、默认栈溢出、超时、图目标约束及验收脚本选错导航/selector 的原回执均保留；没有重标原失败 |
| 单轮测量 | 隔离 Release、空应用状态、4 GiB worker / 512 MiB 输出 / 2 GiB scratch：CLI 全流程 38.458 s，进程树峰值 2.034 GiB，图库 168.742 MiB |
| 图规模 | 57,086 节点、295,695 条非调用边、66,775 调用位置、50,233 调用目标；integrity=ok，FK 违规 0 |
| 调用分母 | 66,775 位置中 exact 48,382、heuristic 1,793、ambiguous 23、unresolved 16,577；这是存储分布，不是准确率 |
| 未关闭 | 615 文件均为 partial；8 个缺失模块名含 5 个可选外部名称及 3 个运行时搜索路径名称；不认证完整 Python 语义、Linux 或容量/稳定性能 |

capture 0.732 s；分析及入库 25.436 s；发布 12.202 s。后端 solve / facts 为 4.696 / 0.364 s；交付和写库重叠，不能与上述阶段再累加。OS 页缓存未控制，RSS 每 0.2 s 采样同一时刻 daemon 及子进程之和。仅正式测量一轮，前面的功能重放不计作性能样本。

该轮范围验收回执：[2026-10-17](../lang-python-adaptor/validation/2026-10-17/receipt.json)，包含冻结输入、wheel 哈希、原失败、同库复查和制品哈希；大型原始 JSON 无损 gzip 保存。当前 dist 已更新为 v3，不能用其字节冒充旧测量；原产物另存本机备份。

配置、能力限制和命令见 [Python worker](modules/python-worker.md)。后续历史 Java/Rust 回执保持原协议和产物含义，不构成 worker 21 / schema 10 的 Java Native 正式放行；Python 本轮仅签上述冻结范围。

## 架构整改与真实项目复验（2026-10-16）

| 改动 | 结果 |
|---|---|
| Rust 任务所有权 | TaskRunner 持有完整任务；取消/超时后等 blocking 工作退出再写终态、释放许可，避免取消后后台切换 head |
| 调度与幂等 | 分析、只读和维护独立有界通道；max_query_tasks 默认 2；先查重再准入，重复请求可取消捕获中的原任务 |
| 发布裁决 | PublicationFence 在取消与开始提交之间原子裁决；提交完成后按 durable commit 返回已发布结果 |
| Java 可维护性 | 请求/结果/上下文改为不可变 DTO；拆分调用恢复、Lombok 规则、编译器池与模型消费；保留资源所有权、诊断路径校验和规则顺序 |
| 兼容边界 | 原入口与可执行文件路径不变；公开身份、协议和图库版本不变，Rust 不重编码 Java metadata |

| 本轮回归 | 结果 |
|---|---|
| Rust | workspace 182 项通过、1 项需 G1 的测试跳过；图 20、真实 Rust 5、文本 2、标注 4，以及 fmt / DDL / clippy 通过 |
| Java | fixture 641 项通过；源码审计通过，来源清单 561 个文件、24 个适配文件校验通过 |
| JVM / Native | JVM 图 7；Native manifest 5、ABI 生命周期 8、图 8、错误 JDK 与真实 SIGSEGV 恢复通过 |
| 分发与验收工具 | release 包、原路径产品图/错误 JDK/随包 fixture 通过；当前门禁、查询多重集和运行路径回归 8 项通过 |
| 冻结真实项目 | 12 张逻辑表及 6 类公开查询与改造前 JVM 基线一致；重复记录、NULL、数组顺序和源码位置保留 |

| Native/G1 唯一一轮 | 观测 |
|---|---:|
| index 命令墙钟 | 214.548 s |
| 发布 facts.db | 233,615,360 bytes / 233.62 MB |
| 同时刻进程树 RSS 采样峰值 | 2,715,762,688 bytes / 2.53 GiB |
| 证据目录磁盘采样峰值 | 2,439,239,085 逻辑 bytes / 2,481,790,976 分配 bytes |
| 全流程监控墙钟（含启动、查询与图校验） | 230.883 s |
| partial 文件 | 854，保留原覆盖缺口 |

输入为 iopengw 冻结提交 `267c7ced4993aa88613f8f17a8099943fec98159` 的独立副本，仅已提交内容、MAIN+TEST；原目录 `/Users/stream/codes/antcodes/ipay/gw-group/iopengw` 未修改。改造前源码固定 `e4230c2`，先封存 JVM 基线，再在最终冻结输入上重放。外部 Maven 导出器原字节独立封存，避免 SDK 安装覆盖控制输入；实际 5,983 个输入文件及源码/产物的 SHA 均核对。预算见 [实施与验收](delivery.md#本轮整改验收入口2026-10-16)，应用状态独立且为空；OS 页缓存未控制，进程采样 1 秒、磁盘采样 10 秒。磁盘范围含本轮证据、暂存和原二进制备份，不能与单个 facts.db 混比；RSS 是同一时刻进程树之和，不是独立峰值相加。

原实现两次重放的 nodes/edges/call_sites 原文摘要不同，取证为 JSON 对象键顺序跨进程变化；解析后的 12 表多重集相同。最终 Native 也单独记录这三表的原文差异，不宣称字节等价。逻辑比较只规范 JSON 对象键并排除运行时 classpath 摘要；数组及字段值仍严格比较，存储不改写 metadata。公开查询只额外排除 overview 顶层宿主 profile。

证据根目录为 `target/architecture-hardening/20261016T031030Z/`：汇总 `validation.json`，本轮 `iopengw-native-single/receipt.json`，原实现重放与差异 `baseline/replay/receipt.json`、`baseline/replay-equivalence.json`。安全拦截、来源清单准备失败和未启动索引的运行路径失败均保留；实际 Native 全量只有一轮，结束后原 debug 字节已恢复。运行路径准备问题随后由 manifest 直接定位原执行目录解决，并补回归；没有重跑测量或修改分析产物。

本轮完成整改和上述回归，未签正式完整语义、容量或稳定性能放行，`native_accepted=false`、`performance_accepted=false`。历史 214.28 s / 233.49 MB 回执保留，不能将不同预算的单轮差值解释为提速或回退。Linux、目标 Java 17/25、完整 Rust 语义/来源覆盖等未完成项保持。

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

当前 worker 21 / graph model 3 / 物理 schema 10 需要配套重建；旧图执行 `index --full`。下文 Java Native / release / 性能回执属于各自历史产物，不构成本次版本的正式放行。

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
| CLI-daemon handshake | 4 | `DAEMON_PROTOCOL_VERSION` |
| worker protocol | 21 | `WORKER_PROTOCOL_VERSION` |
| graph model | 4 | `codeana_model::VERSION`；manifest 仍沿用 `fact_ir` 键 |
| analysis request/result | 10 | Java bridge 与 Rust host 必须成套 |
| C ABI | 4 | 共享库/header/host 必须成套 |
| javac 模型 / compiler batch | javac-model-v50 / 2 | 与 worker 协议独立 |
| 图库物理格式 | user_version 11 | 删除业务语义标注存储，Rust/Python 紧凑布局保留 |
| 上游逻辑格式 | schema 34 / GraphSemantics 26 | `project_meta.schema_version` 与物理格式不是同一个版本 |
| adapter | anatomist-4a903272-codeana-graph4-maven-fallback1 | 当前上游参考 `4a903272c22083585107e982f2899ed03a280aa2` |
| catalog | 2 | 事务升级；独立人工笔记库已移除 |
| Native 构建 JDK | 25.3.4+1.r25-graal | `lang-java-adaptor/.sdkmanrc` 固定 Oracle GraalVM |

图 schema 10 及更旧格式的查询/增量返回 `GRAPH_SCHEMA_REBUILD_REQUIRED`。执行 `index --full` 创建新 revision，成功后切 head；旧文件保留，不原地迁移。历史图查询也要求兼容物理格式。

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
| 紧凑存储、历史/源码/分页、故障保旧版 | 当前版本完整语义/来源覆盖验收；旧 raw transport 门禁已删除 |
| fixture 和一次冻结真实项目测量 | 独占容量/稳定性能验收；`native_accepted`、`performance_accepted` 仍为 false |

开发 build-only、Hello、fixture、旧版本完整回执均不能代替当前版本正式验收。验证顺序和命令见 [实施与验收](delivery.md)。
