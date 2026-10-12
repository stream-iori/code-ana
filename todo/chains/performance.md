# 性能优化：每版一轮

## 最新诊断（2026-10-12）

| 项目 | 状态 |
|---|---|
| Java/Rust 分段诊断 | 已完成：graph/view/事实投影、分配/GC、FFI、归档、protobuf、SQLite/blob/发布，详见 [分析报告](../../docs/implementation/native-performance-analysis.md) |
| 单轮测量 | JVM 冷/热 681.276/649.956 秒；Native 冷/热 995.621/970.591 秒；四次完整图 2,447,690 条、摘要一致，六项查询成功 |
| 当前版本正确性 | Java 组件 661；Rust 172/clippy；Native fixture 82、JVM fixture 76；CLI 18，全部通过；没有新增 SIGKILL |
| SDKMAN 选择 | 测后按用户要求改为 Oracle `25.3.4+1.r25-graal`；版本输出已核对。本轮仍明确记录 Community/Serial，不签 Oracle/G1 收益 |
| G1 可行性 | 本机 Oracle `G1Feature` 允许 macOS/aarch64，且原生库存在；Anatomist macOS profile 默认 G1。本项目共享库、生命周期和性能验证待做 |
| 下一步 | 同 Oracle 工具链 Serial/G1 单轮对照；取证分配/graph 热点；核验 SHA 计算路径；再按量化成本优化 Rust 去重、入库和有界流水线 |
| 尚未通过 | Native 冷/热减半、整体查询性能门槛、整项目/Native 观测开关对照；不将诊断通过签成优化通过 |

证据：`target/implementation-evidence/native-performance-analysis-01/single-01/receipt.json`、`report-data-receipt.json`、`metrics.csv`、`resources.csv`、`runtime-phases.csv`、`storage-stages.csv`。详细代码及设计对照见上述报告。以下保留 2026-10-11 的历史实施记录，旧失败回执不改写。

2026-10-11。速度优先；内存、解码、事实、临时磁盘和累计磁盘预算保持原值。旧三轮数据仅作历史观察，不能拿 debug → release 的收益冒充优化收益。

| 编号 | 实施内容 | 当前状态 |
|---|---|---|
| P0 | 同源码 release 基线；阶段计时；每秒进程、每 10 秒磁盘、每分钟摘要；观察开销校准 | JVM/Native 冷热四阶段及存储基线通过；v2 修正退出进程 CPU 重复计数、将 scratch 纳入磁盘观察；真实事实 CPU/SQLite 单对照开销 1.25% 通过；范围不涵盖完整项目直接对照 |
| P1 | 保留解析、事实校验、字节预算、EOF、摘要及重复检查，复用归档中的规范 JSON；减少边界转换和 owner 哈希计算 | Rust 回归、Source03 release 完整 fixture 和单轮完整图通过；总性能门槛未通过 |
| P2 | 全量先入库，再批量构建声明投影、owner/source 索引；在 Wiring 前恢复全部索引与触发器；增量沿用 owner 替换 | 完整图通过；存储入库及校验单轮缩短 59.8%，导出缩短 45.8%；Native 总目标未通过 |
| P3 | 基于真实 Native/daemon 热点调整缓冲、分配和编译器调度；并发 1/2/3 的独立预算内实验；PGO 先核验工具支持 | 独立剖析通过，5 份采样齐全；已取得 GC/字符串/HashMap 与 SQLite 插入/索引构建证据；同 JAR 年轻代 25% 实验库的 Source02 产品 58/58、探针 17/17、生命周期 6/6 通过；CE 25 的 O3 等同 O2，不支持 PGO；性能收益未验收 |
| P4 | 未变化、单文件、跨模块、XML、配置变更的真实增量；测备份、缓存、导出、派生费用；证据不完整保守 full | JVM/Native 未变化专项各通过 1 项，JVM 原有 core 回归通过；保留 idle warm worker；仍执行旧图导出校验、备份、派生与发布；完整组与真实项目待验收 |
| P5 | 依据 DBSTAT 与固定查询计划调整布局；只有证明 JSON payload 是瓶颈才考虑压缩 | 历史 DBSTAT 已核查，尚未改布局或压缩 |

```text
发布事实 → CAF1 解码 + 校验 → 复用 JSON 入库
                                  ↓
全量：批量声明投影 + 索引 → 恢复触发器 → Wiring → 全图校验 → integrity_check → ANALYZE → 原子发布
增量：备份旧图 + owner 替换 ────────────────┘
```

## 单轮验收

| 项目 | 规则 |
|---|---|
| 每版后端测量 | JVM 冷/热、Native 冷/热各一次，共 4 个阶段；不算中位数或 P95 |
| 存储回放 | 每版一次；输入库只读，完整 Fact IR 摘要、数量相同，原库 SHA 不变 |
| Native 最终目标 | 冷、热完整索引分别 ≤ 同源码 release 基线的 50% |
| JVM 和查询 | JVM 冷/热和 6 个固定公开查询不得回退超过 5%；异常只重验受影响项 |
| 候选改造 | 目标阶段改善 ≥10%，端到端不回退；最终仍须满足 Native 减半门禁 |
| 正确性 | 完整 Java 门禁、生命周期、旧 head、历史查询、全量/增量结果；失败、缺项或源码漂移均不能签通过 |
| 预算与协议 | 既有预算不变；CAF1、Fact IR、C ABI、CLI 和 gRPC 不变；计时只写本机任务报告/sidecar |

新工具：`scripts/java-performance-single.py`、`java-performance-phase-single.py`、`java-performance-monitor-single.py`、`java-storage-performance-single.py`、`java-performance-compare-single.py`。构建回执绑定 release 二进制与源码清单；旧验收仅可用与原 SHA 完全匹配的源码快照验证，不能替新实现签发门禁。

## 本次证据与阻塞

| 证据 | 结果 |
|---|---|
| `target/implementation-evidence/performance-rust-tests-03.log` | P1/P2 的 167 项工作区单元测试通过；当前 P4 新实现已另以 tests-04.log 验证 167 项通过；隔离子进程不重复计数 |
| `performance-clippy-02.log` | `cargo clippy --workspace --all-targets --locked -- -D warnings` 通过 |
| `performance-single-tool-tests-02.log` | 4 项通过：双温度减半、JVM/查询回退、缺项/重复/并发/图/预算漂移、历史源码摘要 |
| `performance-rust-cli-02.log` | 17 项执行，16 项通过；自动启动 daemon 的 1 项仍因 SIGKILL 失败；当前源码 cli-03 同为 16/17 |
| `release-baseline-single-02/receipt.json` | JVM 冷索引 worker PID 77521 收到 SIGKILL，0 个成功性能阶段 |
| `performance-candidate-build-05.log` | libc/serde_core/anyhow 已通过；generic-array、prettyplease、rustix、httparse 构建脚本 SIGKILL，已报告待处理；候选 release 尚未构建成功 |
| `performance-candidate-build-06.log` | Source03 release 重试仍被 generic-array、prettyplease、httparse 构建脚本 SIGKILL 阻塞；未生成候选 release 性能产物 |
| `performance-jvm-unchanged-02/receipt.json`、`performance-native-unchanged-01/receipt.json`、`performance-jvm-core-01/receipt.json` | 新 worker 放行后，当前 debug Rust 三项真实 Java 专项通过；不能代替完整 Java 门禁 |
| `performance-monitor-v2-tests-01.log` | 2 项 CPU 计数回归通过：退出名称缩短不重复计数，PID 复用与延迟识别角色保持正确 |
| `performance-monitor-v2-tests-06.log` | 3 项通过，增加候选构建与历史门禁不能混用的回归；双 ps 采集方案实测更慢，已撤回 |
| `performance-native-profile-03/receipt.json` | 独立诊断通过：发布成功、5 份采样齐全、输入摘要未漂移；有诊断构建重叠，耗时不用于正式比较 |
| `native-symbol-build-02/receipt.json`、`sample-instruction-mapping-02.json` | 符号库构建通过；完整机器码布局有差异，仅唯一匹配的 64 字节指令片段归因，未匹配样本不归因 |
| `native-young25-build-01/receipt.json`、`performance-native-young25-core-01/receipt.json` | 同一已验收 JAR，年轻代 25% 的隔离实验构建及 Native core 专项通过；堆与 worker 预算不增加，尚无性能验收 |
| `performance-native-young25-product-01/receipt.json`、`performance-native-young25-probe-01/receipt.json`、`performance-native-young25-lifecycle-01/receipt.json` | Source02 debug Rust＋实验库，58＋17＋6 项完整组通过；尚不能替 Source03 新 worker 或完整真实项目验收签通过 |
| `performance-jvm-product-01/receipt.json` | Source02 debug Rust＋原已验收 JVM bundle，完整产品组 58/58 通过，无缺项、失败或产物漂移 |
| `performance-rust-tests-05.log`、`performance-clippy-04.log`、`performance-rust-debug-build-05.json` | Source03 可选 Native 临时目录参数接入后，167 项工作区测试、clippy、debug 构建通过；Source02 二进制已另封存，历史回执不改写 |
| `performance-native-temp-scope-01/gate/receipt.json` | Source03 worker PID 15319 在专项首次索引收到 SIGKILL，签名校验通过，已报告待处理；未验证实际临时文件位置 |
| `release-storage-baseline-single-02/receipt.json` | 单轮通过：blob 63.0 秒，CAF1 导出 67.5 秒，stage 339.8 秒，发布 3.2 秒；2,447,690 条事实及完整图摘要一致，原库 SHA 不变 |
| `release-baseline-single-03/receipt.json` | 单轮全部通过：JVM 冷 919.9 秒、热 868.4 秒；Native 冷 1359.5 秒、热 1237.8 秒；完整图和 6 项查询均通过 |

仍需核查的进程路径（均相对仓库根）：

- Java worker 已由用户确认放行；新基线正常运行。
- `target/implementation-evidence/performance-rust-cli-root-03/target/debug/codeana-daemon`（自动启动回归项）
- `target/implementation-evidence/performance-candidate-build-01/release/build/generic-array-07035c2e216cc3a5/build-script-build`
- 同目录 `prettyplease-e9f83e690e31e147`、`rustix-4ada0b415368f129`、`httparse-63766c58da21ffd3` 的 `build-script-build`。
- `target/implementation-evidence/performance-native-temp-scope-01/gate/runtime/target/debug/codeana-java-worker`（PID 15319）。

Java worker 的代码签名校验通过。SIGKILL 日志本身不能确定谁发出信号；已向用户报告，安全拦截来源需要用户侧确认。现阶段 `optimization_accepted=false`，不声称耗时减半或完整计划完成。

当前 Source03 已完成 debug 构建、167 项单测、clippy；worker PID 89490 被拦截的失败回执保留，Source02 放行后的重验已通过。正式性能期间没有并行编译。旧基线阶段报告默认关闭，分段计时未完整采集；旧监控磁盘范围未涵盖系统临时目录。v2 工具与旧封存工具分开，修正 CPU 计数和 scratch 范围，并要求候选构建的独立完整正确性回执；观察开销仍须校准。

macOS JDK 8/25 的实测证明 `TMPDIR` 不改变 `java.io.tmpdir`；显式 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=...` 对两者生效。v2 阶段工具因此同时设置 Rust 临时目录、JVM 临时目录及 Native 的可选 `CODEANA_PERFORMANCE_TMPDIR`，worker 在创建 isolate 时传入相同 `-D` 参数。独立 C ABI 探针证明参数接受、ABI=2、配置堆上限与正常销毁均通过；实际产品临时文件位置的专项因 Source03 worker SIGKILL 尚未通过，不声称磁盘观察已完整。

## Native 冷基线的分段证据

`target/implementation-evidence/release-baseline-native-cold-core-timings-01.json` 从本轮 worker 的独立 scratch 复制：Java 总分析 632.4 秒，unit_projection 399.9 秒，编译器等待 47.2 秒，归档封存 23.1 秒。阶段存在包含关系，不能相加。原始归档 3,253,862 条、5,295,264,056 字节；发布前去重后的完整图仍为 2,447,690 条。新 Native 冷门槛为 679.7 秒，仅改存储不足以达到目标；需在独立高频剖析中区分投影的编码、身份计算、绑定范围查询与归档写入成本。系统内存快照另存，不将累计压缩/换页计数作为本轮变慢的根因。

Native 热 Java 分析 691.3 秒，unit_projection 435.5 秒、归档封存 25.6 秒；冷/热均 restored_modules=0，不声称恢复缓存产生收益。正式 Native 减半门槛分别为 679.7/618.9 秒。独立 CPU 剖析工具 `scripts/java-native-performance-profile.py` 使用原基线二进制，结果不能充当候选性能验收。JVM worker 已由用户确认放行且重验通过；CLI 自动启动 daemon 与 generic-array 构建脚本仍待处理。

旧监控进程 CPU 按 `(role,pid,birth)` 累计，退出后角色变为 other 会重复计数。`release-baseline-cpu-role-correction-01.json` 从封存采样按 `(pid,birth)` 重算通过；四阶段索引耗时、完整图与查询不受影响。新 `*-single-v2.py` 工具保留可识别角色，CPU 只累计一次，并把任务 scratch 放进监控目录；旧脚本与回执摘要不变。

`native-cpu-targets-01.log` 确认默认 armv8.1-a 不含 SHA2，而 native 目标包含 SHA2；这只是编译目标差异，尚未证明哈希是主热点。`native-image-options-01.log` 确认默认删除本地符号。`java-native-performance-variant.py` 可使用完整旧 Native 门禁绑定的同一份 bytecode JAR 构建独立符号库或同机 native CPU 变体，现有产物不覆盖；构建成功也不等于正确性或性能验收通过。

## 放行后重验（2026-10-11）

| 项目 | 最新结果 |
|---|---|
| release build-07 | 已越过 generic-array、prettyplease、rustix、httparse；新增 pulldown-cmark/getrandom 构建脚本 SIGKILL，已报告 |
| Source03 Native core | 复用放行的 temp-scope-01 worker，recheck-02 通过；源码/运行产物无漂移 |
| 临时目录专项第一次重验 | fixture 落在仓库被忽略的 target 下，捕获为空，查询失败；修正 Python fixture 临时根后通过。未观察到默认 Native result 目录，实际位置仍待验证 |
| CLI 自动启动 | 复用 daemon 原路径，recheck-01 仍 SIGKILL；签名有效，已再次报告 |
| Source03 Native 完整产品组 | 已开始，复用已放行的同一运行库与二进制；不能以 Source02 回执代替 |

之前的失败回执均保留；最新状态见 `performance-implementation-status-04.json`。只跑每版一轮正式性能；当前仍未签发性能通过。

Source03 的 `performance-source03-native-probe-01`（17/17）和 `performance-source03-native-lifecycle-01`（6/6）完整组均通过，运行库及源码摘要无漂移。`performance-native-temp-released-recheck-03` 也通过：实际观察到指定 scratch 内的 `.worker-*/received.bin`、`tree-*.bin`、`anatomist-facts.caf`、`native-result-*/facts.caf`，不能把没有出现旧的默认目录前缀误判为没有临时文件。当前产品批处理路径将结果位置随请求传递；javac 环境清空 JAVA_TOOL_OPTIONS，不影响这些已显式指定的位置。此专项仅证明所执行 fixture 的文件位置，完整项目磁盘覆盖及观察开销仍待正式核验。最新状态见 `performance-implementation-status-05.json`。

CLI 自动启动的进一步取证见 `performance-cli-autostart-trace-01`：同一 daemon 二进制由 Python 直接启动（普通进程、process_group=0 两种）均能生成 endpoint，由同目录 CLI 的 `hello` 自动启动则立即 SIGKILL、daemon.log 为空。不能将“二进制已放行”直接等同于“CLI 启动子进程场景已通过”；已请用户检查该启动场景。未修改产品启动机制来绕过拦截。

## Source03 本轮回归完成

| 回执 | 结果 |
|---|---|
| `performance-source03-native-product-01/receipt.json` | 完整产品组 58/58，通过；1242.9 秒仅为正确性组耗时，不作性能比较 |
| `performance-source03-native-probe-01/receipt.json` | 完整探针组 17/17，通过 |
| `performance-source03-native-lifecycle-01/receipt.json` | 完整生命周期组 6/6，通过 |
| `performance-source03-native-unchanged-01/receipt.json` | 未变化专项 1/1，通过；保持 warm worker、跳过 Java 分析、保留发布及旧图验证 |

以上 82 项绑定 Source03、debug 构建与同 JAR 年轻代 25% 实验库；无缺项、重复、失败或源码/产物漂移。不是候选 release 正确性、完整项目验收或 Native 减半通过。当前无在跑的验收进程；正式性能仍每版一轮。

最新状态为 `performance-implementation-status-06.json`。仍等待 build-07 中 pulldown-cmark/getrandom 构建脚本和 CLI→daemon 自动启动场景的 SIGKILL 处理反馈；此前 generic-array/prettyplease/rustix/httparse 构建脚本及 Source03 worker 已越过原失败点。

## 固定应用路径与放行后重验

用户要求：编译后的应用路径不变，避免每次更换路径重新放行。候选构建持续使用 `performance-candidate-build-01/release/`；二进制名、Cargo target 均不变。fixture 与单轮 v2 工具现在通过 `java-performance-stable-runtime.py` 在固定二进制旁安装校验后的 JVM/Native bundle，证据目录仅链接同一运行布局，不再复制应用。执行前核对源码、构建、二进制及 bundle 摘要；已有安装不同则报漂移，不能悄悄覆盖。构建 Source03 未更改；工具自身的最新源码摘要另记入 status-07。

| 最新证据 | 结果 |
|---|---|
| `performance-rust-cli-released-recheck-04` | 原路径自动启动受影响项通过 |
| `performance-rust-cli-04` | 同一放行路径完整 CLI 17/17 通过，无产物漂移 |
| `performance-stable-runtime-tests-02.log` | 3/3 通过：后端/证据目录切换不改变应用路径，拒绝覆盖不同安装，先验证再安装 |
| `performance-monitor-v2-tests-09.log` | 原有 3 项回归通过；工具语法检查通过 |
| `performance-candidate-build-08.log` | pulldown-cmark/getrandom 已通过；首次执行 zmij 被 SIGKILL，用户已放行 |
| `performance-candidate-build-09.log` | zmij 已通过；首次执行 thiserror/serde/serde_json 被 SIGKILL，已报告待处理 |

这些新增依赖脚本由 Cargo 在固定 target 中首次生成，未改名、未换应用目录。最新状态见 `performance-implementation-status-07.json`。候选 release 尚未完成，因此一轮性能与 Native 减半验收仍未签通过。

## release 已构建，存储单轮验收通过

| 最新证据 | 结果 |
|---|---|
| `performance-candidate-build-12.log`、`performance-candidate-release-build-01.json` | Source03 release 构建通过；4 个二进制及 fact_replay 均留在固定 Cargo target，源码/构建/产物 SHA 已绑定 |
| `performance-release-native-core-01`、`performance-release-jvm-core-02` | 首次 daemon 启动固定 release worker 被 SIGKILL（PID 34564/37646）；失败记录保留 |
| `performance-release-native-core-02`、`performance-release-jvm-core-03` | 用户放行后，两个后端 core 受影响项均通过；应用路径未变 |
| `performance-stable-runtime-tests-03.log` | 固定路径 3 项回归通过；JVM 的正常 ABI=null 校验已修正，仅改工具，未重编应用 |
| `performance-candidate-storage-single-01`、`performance-candidate-storage-comparison-02.json` | 独立单轮通过；完整图与 2,447,690 条事实相同，原库 SHA 不变 |
| `performance-candidate-gate-tests-02.log` | 候选正确性聚合工具的 4 项防误签回归通过：缺项/替换/重复、失败/跳过、输入漂移、旧构建混用 |

存储回放：blob 64.1 秒（基线 63.0）、CAF 导出 36.6 秒（67.5，缩短 45.8%）、入库及校验 136.5 秒（339.8，缩短 59.8%）、发布 3.1 秒（3.2）。RSS 峰值从 658,292,736 增至 818,020,352 字节；限制值未增加，不能声称峰值不变。磁盘峰值约 15.6 GB，与基线相近。回放无 javac、无 Wiring 派生，不能据此签 Native 完整索引减半。

固定应用路径上的 JVM/Native 完整 release 组已开始，短组（探针、Native 生命周期、未变化专项）均通过，产品组仍在运行。监控开销的单对照校准工具已准备，待正确性组结束后独立执行；标明真实事实 CPU/SQLite fixture 范围，不冒充完整项目开销证明。最新状态见 `performance-implementation-status-08.json`。

## 固定路径 release 正确性完成

| 项目 | 结果 |
|---|---|
| Native release | 产品 58、探针 17、生命周期 6、未变化 1，全部通过；core 重验通过 |
| JVM release | 产品 58、探针 17、未变化 1，全部通过；core 重验通过 |
| 候选聚合门禁 | `performance-candidate-correctness-01/receipt.json` 通过；绑定 Source03、release 构建和同 JAR Native 库，无缺项、漂移或未解释失败 |
| 监控校准 | 正确性证明校验结束后独立运行 `performance-observer-calibration-02`；01 因重叠校验已终止并标记无效，不计为验收 |

当前无待处理 SIGKILL。应用持续使用 `performance-candidate-build-01/release/` 原路径。上述通过只代表正确性；完整项目一轮性能、查询回退与 Native 减半仍待验收。

`performance-observer-calibration-02/receipt.json` 通过：100 万次真实事实处理，控制 22.94 秒、观察 23.23 秒，开销 1.25%（门槛 3%），摘要完全一致。范围是单对 CPU/SQLite fixture，完整项目观察开销未直接证明。候选正式一轮已启动，四阶段串行，应用路径不变。

## 候选正式一轮完成，优化未通过总门槛

| 阶段 | 基线 / 候选秒 | 缩短 |
|---|---|---|
| JVM 冷 | 919.9 / 797.8 | 13.3% |
| JVM 热 | 868.4 / 709.0 | 18.4% |
| Native 冷 | 1359.5 / 1058.2 | 22.2% |
| Native 热 | 1237.8 / 992.3 | 19.8% |

四阶段均独占执行且完整图为 2,447,690 条、SHA ba01dbbe…；预算未变，应用路径未变。测量回执 `performance-candidate-single-01/receipt.json` 通过仅表示四阶段完整测量成功；对比 `performance-candidate-comparison-01.json` 明确失败，Native 冷热均未减半。原始查询回退为 JVM 冷 search-text 9.0%、Native 冷 structure 7.4%、Native 热 overview 14.0%。

`performance-query-recheck-01` 只做三项诊断单对照，保留原失败回执、不重跑索引。两项回到 5% 内，Native structure 仍回退 10.3%；不能凭诊断覆盖正式失败。查询代码和索引 SQL 与冻结基线相同，仍需检查布局和运行链路。

Native 冷 Java 662.1 秒，unit_projection 416.3 秒（约 63%），Rust 归档校验去重交付 123.3 秒；年轻代 25% 未证明收益，仍只作实验，不采纳默认。继续 P3：规范 JSON 对象排序减少临时 TreeMap 节点；CAF1 用固定 64 KiB 缓冲合并逐记录文件写入，字节、格式、记录/结果预算不变。改造前 Java 源码封存在 `performance-java-before-projection-01`；新 Java 必须另验，原回执不替新版本签通过。最新状态 `performance-implementation-status-11.json`。

## 投影传输优化的新版本验证

`performance-projection-source-01` 冻结 Java 源码。656/656 组件测试通过，完整保留旧 654 项并新增两项字节/排序回归。`performance-projection-transport-lineage-03.json` 对两版接受过的 JAR 和新 JAR 做完整归一化条目对比：生产类仅 Json/FactArchive 改变；原始模型生产类不变且不引用这两个传输类；470 个 Native/JVM 共享生产条目相同。JAR 清单仅构建元数据改变，Main-Class、Build-Jdk-Spec 等行为字段保持一致。该证明不签完整 Java/Native 或性能门禁。

新 Native 用默认年轻代策略，实验 25% 未作为默认采纳。Java 8 桥 31 个类均 major=52。旧安装在 `performance-runtime-before-projection-{native,jvm}-01` 保存字节与原路径映射；原应用路径和 SHA 均不变。新库升级在无 daemon/worker 运行时执行，升级工具 3 项回归通过；传输谱系的 3 项拒绝误签回归通过。

新完整 release fixture 使用原固定应用路径；Native 探针 17、生命周期 6、未变化 1 和 JVM 探针 17、未变化 1 通过。产品组 58 项正在执行；新库完整项目性能尚未开始。Native structure 回退未关闭，查询计划相同；单次 CLI --help 探测没有显示候选启动更慢。自定义 TCP incoming 绕过 Tonic 的默认 TCP_NODELAY 设置已取证，但 loopback 双写探针未重现约 40 ms 差异，不能把它签成查询回退根因，也尚未修改此配置。

工具测试文件在产品组运行期间新增，触发了来源集合漂移：Native product-01 的 58 项实际通过但回执失败；JVM product-01 主动中止并标记无效。没有放宽漂移门禁。工具冻结后，两版 product-02 完整 58/58 重验均通过、无漂移；短组保持通过。工具源码已另封存 `performance-tools-source-02`，新候选聚合回执正在做摘要核对。此时无在跑的正确性测试或 SIGKILL 待处理。

新聚合 `performance-projection-correctness-03/receipt.json` 通过：严格核对相对路径对应的绝对证据和 SHA；4 个被新构建覆盖的旧 JVM 产物从历史运行目录找到相同字节，另记 `performance-old-jvm-artifact-archive-01.json`。历史回执保持原样。路径校验 5 项通过，工具封存为 Source03。新版本正式一轮即将开始，仍固定应用路径、预算不变；性能目标尚未通过。

## 投影优化正式一轮完成，总验收仍失败

| 阶段 | 基线秒 | 候选秒 | 缩短 |
|---|---:|---:|---:|
| JVM 冷 | 919.9 | 688.6 | 25.1% |
| JVM 热 | 868.4 | 650.0 | 25.1% |
| Native 冷 | 1359.5 | 976.6 | 28.2% |
| Native 热 | 1237.8 | 953.6 | 23.0% |

`performance-projection-single-01/receipt.json` 四阶段完整测量通过，均为 2,447,690 条且完整图 SHA 相同、预算和应用路径不变，无 SIGKILL。对比 `performance-projection-comparison-01.json` 明确失败：Native 冷热未减半；7 项查询超过 5%：JVM 冷 structure 5.8%、search-text 12.4%；Native 冷 resolve 11.5%、structure 9.6%；Native 热 structure 5.5%、search-text 6.8%、overview 41.9%。不重跑完整索引筛选好结果。

嵌套 worker 计时另整理 `performance-projection-stage-analysis-01.json`，原回执不修改。Native 冷 Java 589.4 秒（上一候选 662.1）；unit_projection 356.9（416.3，缩短 14.3%），热 Java 599.6、投影 363.0。归档校验去重和交付冷 122.5、热 123.6 秒，基本未变；冷存储 184.9、发布 66.0 秒，热存储 205.1、发布 9.6 秒。Java 细分位于 worker 子目录，顶层 receipt 未收录，不能据此判断不存在计时。最新状态 `performance-implementation-status-15.json`。

根据用户建议对照 `../anatomist`：SemanticExecutionContext 在流水线内共用读锁、连接与身份；StagedGraphStore/FactIdentityPreparer 分离批量写入和身份/索引准备；IndexFileSwap 完成 checkpoint 后原子切换。当前 Rust 查询已有单 ReadContext，不能假设重复图连接是根因。发现 CLI 输出每 16 KiB 单独打开 catalog、提交 FULL 事务；实际 structure 95,373 字节/6 块。`performance-output-event-batch-diagnostic-01` 用真实输出做 SQLite 诊断，30 次输出的存储耗时 0.330→0.081 秒，事件内容/顺序摘要相同。范围为 Python/SQLite 诊断，不冒充产品查询收益；按一次任务输出一个事务改造，保留事件序号、持久性、输出预算和终态契约。

Source04 已实现输出事件批量事务：仍保留每个 16 KiB chunk 的独立事件、序号和 FULL 持久性，事务中任意序列化/插入失败全部回滚；空批次不写入。工作区 169 项通过（隔离子进程 2 项不重复计数），clippy 通过，原 release 路径构建通过。Rust CLI 首次因快照缺少 debug 链接而跳过 4 项，原失败保留；链接解析到既有应用原路径后，完整重验 `performance-output-batch-rust-cli-02` 17/17 通过、无跳过。Native/JVM 新 release 探针、生命周期和未变化专项通过；产品 58 项正在执行，尚不签 Source04 完整验收。旧应用字节仅封存在 `performance-before-output-batch-apps-01` 作原回执校验，不从归档执行。最新状态 status-16。


## Anatomist CLI/store 参考与历史查询隔离修复

| 项目 | 已确认结果 |
|---|---|
| Source04 输出事务 | Native/JVM 产品各 58、探针各 17、Native 生命周期 6、两版未变化各 1、Rust CLI 17 全通过；完整聚合通过 |
| Source04 查询诊断 | 原 7 项失败中 6 项回到 5% 内；Native 热 search-text 仍回退 5.7%。所有冻结结果一致；原正式失败保持不变 |
| 编码实验 | 500 随机样本、转义/Unicode/深度/预算回归通过；100 万记录 JVM 单对照 1.088→1.144 秒，慢 5.1%，不采纳，也不签 Native 收益 |
| 历史查询缺陷 | search-text 指定 revision 仍捕获当前工作区；旧应用在当前配置损坏时返回 INVALID_PROJECT_CONFIG。修复只让工作区首页捕获，历史及续页读取冻结对象 |
| Source05 验证 | Rust 工作区 169、CLI 18 全通过，clippy 通过，固定 release 路径构建成功。新增回归覆盖损坏配置、修改源码、revision/snapshot 和工作区差异 |

参考源码对照见 docs/implementation/performance-anatomist-comparison.md。Source05 生产变化相对 Source04 仅历史捕获条件；新的诊断明确核对该逐字差异，并核对 Source04 完整聚合的归档证明及 Source05 CLI 18 项，不冒充 Source05 完整 Java 重验。原应用字节只归档取证，不执行；应用路径不变。查询只使用原冻结库，每项单对照，不重跑完整索引，预算不变。

待办：完成 Source05 查询诊断；细分 Native 投影约 357 秒、归档交付约 123 秒；测试源码 blob 整批目录同步的实际收益（每文件同步/hash 与发布契约保留）。SourceBlobStore 参考实现未见显式 fsync，不能直接照搬持久性。Native 冷热减半目标仍失败。最新状态 performance-implementation-status-17.json。

Source05 查询诊断 performance-historical-text-query-recheck-01 完成：7 项中 6 项在 5% 内；JVM 冷 search-text 比率 0.393、Native 热 0.358，历史结果一致且原库 SHA 未变。JVM 冷 structure 本次回退 13.4%，上次 Source04 通过，不能筛选好结果签通过。当前只签历史捕获修复有效，不签整体查询门槛；blob 同步单对照已开始，最新状态 status-18。


## Source06：整批 blob 目录同步

本机 Rust sync_all 在 Apple 平台调用 F_FULLFSYNC；普通 Python fsync 的初次诊断不作为产品依据。相同同步强度的单对照（performance-blob-directory-sync-diagnostic-02）：5,306 个真实 blob、727,355,990 字节，逐文件目录同步 58.681 秒、整批 34.513 秒，省 41.2%；每个文件仍强同步，全部 hash 相同。该结果仅为文件系统诊断，系统缓存未控制。

Source06 已实现每个文件同步/hash 校验/重命名、整批目录同步后才返回成功；已有文件复用也同步目录，内存式 put_blobs 保留同一复用屏障，覆盖失败批次的遗留文件。部分失败后重启重试、复用源被改动、已有 blob 损坏专项通过；工作区 170 项、clippy、原路径构建通过，CLI 18 项正在执行。生产范围证明 performance-blob-batch-source-scope-01.json：相对 Source05 仅两个 blob 方法改变，schema、发布、Java/worker/IR、查询均不变。随后实际 Rust 存储回放单轮验收；不将文件系统诊断签为 Native 减半。


Source06 CLI 完整 18/18 通过。实际 Rust 单轮 performance-blob-batch-storage-single-01 完成并通过：完整图 2,447,690 条、SHA ba01dbbe…与冻结源相同；原输入数据库 SHA 3fed7158…不变、无来源漂移/并发分析/SIGKILL。与 Source03 存储候选相比：blob 64.054→34.680 秒（缩短 45.9%），归档导出 36.594→35.871，入库校验 136.508→142.365（增加 4.3%），发布 3.083→3.125；四项合计 240.239→216.042 秒（缩短 10.1%）。对比证据 performance-blob-batch-storage-comparison-01.json。

存储回放没有 Java，不签完整 Native 索引或减半；Source06 完整后端性能尚未测量。原性能失败和查询诊断波动保留。剩余方向：先拆 Native graph.process/view.add/projection.unit 计时，再定位 123 秒归档链路；CLI watcher 提交后通知保留 SQLite 重连/多读者/终态语义。应用路径、持久性和预算保持，最新状态 status-20。
