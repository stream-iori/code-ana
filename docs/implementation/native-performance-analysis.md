# Java Native 性能分析与下一步方案

2026-10-12。后续实施与统一测量口径见 [Native 分阶段性能改造](native-performance-upgrade.md)。以下冷/热标签仅保留历史应用缓存测量记录；Native 不分冷热，新对照每轮使用独立空应用缓存和索引目录。

诊断计划完成，四阶段单轮及完整图校验通过；**Native 减半未通过，G1 性能尚未实测**。

| 结论 | 直接证据 | 判断边界 |
|---|---|---|
| 首要差距在 Java 内部 | 冷 Native/JVM 全链路差 314.3 秒，Java 差 308.7 秒，占 98.2%；热分别差 320.6/314.5 秒，占 98.1% | 同工作量、同预算；本轮含诊断埋点 |
| GC 是大项，但不是全部 | Native young + complete GC 为 179.9/179.0 秒；Java 总计 615.3/613.1 秒 | GC 包含于 Java/投影计时，不能相加；具体对象来源未确定 |
| FFI 的 CPU 开销很小 | Native 轮询 22,677/22,262 次，调用线程 CPU 0.245/0.230 秒；启动约 3 毫秒 | 轮询墙钟 116.4/115.2 秒主要为等待；没有逐次 GC/轮询时间关联，不能全判为 GC |
| protobuf 编解码不是主瓶颈 | worker 编码 0.381/0.384 秒，daemon 解码约 1.4 秒；交付约 37 秒 | 交付还包含 CAF 读取/解码/摘要、事实转换和接收端落盘；未证明链路达到硬件带宽上限 |
| 缺少端到端流水线重叠 | Java 封存 → 完整校验/去重 → 首帧事实 → daemon 存储/发布 | 控制通道异步；事实交付分块流式，但此前存在全量屏障 |
| 本机 Oracle 版本支持 G1 平台 | SDKMAN `25.3.4+1.r25-graal` 的 `G1Feature.verifyOptionsAndPlatform` 允许 `DARWIN_AARCH64`，且有 `libg1gc-*.a` | 已确认平台代码和库；本项目共享库构建、C ABI 生命周期及完整性能仍待验证 |

## 实测与输入

| 后端/应用缓存 | 全链路索引秒 | Java 秒 | 分析线程累计分配 GiB | GC 指标 |
|---|---:|---:|---:|---|
| JVM 冷 | 681.276 | 306.599 | 507.47 | Young 6.231 秒；Concurrent 0.432 秒；Old 0 |
| JVM 热 | 649.956 | 298.610 | 472.00 | Young 6.028 秒；Concurrent 0.414 秒；Old 0 |
| Native 冷 | 995.621 | 615.344 | 519.85 | Young 116.042 秒/8,724 次；Complete 63.880 秒/173 次 |
| Native 热 | 970.591 | 613.128 | 519.85 | Young 113.695 秒/8,679 次；Complete 65.348 秒/177 次 |

累计分配是不断创建又回收的对象总量，不是峰值存活堆或进程内存。Native 分析线程 CPU 为 549.3/546.1 秒，JVM 为 239.4/235.2 秒；Serial GC 可能在发起线程执行，这些 CPU 计时不能再与 GC CPU 相加。

| 输入/验证 | 结果 |
|---|---|
| 冻结项目 | iopengw `267c7ced4993aa88613f8f17a8099943fec98159`；5,039 个已提交文件，4,217 个 Java 文件；经原仓库 Git 对象逐文件验证 |
| 工作量 | 每次原始 CAF 3,253,862 条/5,295,264,056 字节；去重 CAF 2,446,344 条；完整发布图 2,447,690 条 |
| 完整图摘要 | 四次均为 `ba01dbbe4db4791f8b141c049a19a38b17e0703df17db19bf2b77b48433e4147` |
| 测量顺序 | JVM 冷 → JVM 热 → Native 冷 → Native 热，各一次，串行，无并行构建或其他 analyzer |
| 缓存口径 | 应用缓存冷/热；OS 文件页缓存未控制，不计算中位数/P95 |
| 固定预算 | 解码/Java 宿主堆各 2 GiB、javac 堆 512 MiB、worker 4 GiB、结果 8 GiB、scratch 32 GiB、编译器并发 1 |
| 当前测量工具链 | `25.3.4+1.r25-graalce`，Java `25.0.4.1`，Serial Native；Java 8 子进程保持原 Zulu 版本 |
| 测后配置 | `.sdkmanrc` 和工具默认路径已改为用户指定的 `25.3.4+1.r25-graal`；`sdk env`、Java/native-image 版本核对通过。本轮旧产物未重新标为 Oracle/G1 |
| 正确性 | Java 组件 661；Rust 工作区 172、clippy；Native fixture 82、JVM fixture 76；CLI 18；均通过 |
| 查询 | 每阶段六项查询成功、revision 正确，查询前后数据库 SHA 相同；这是单次回归观察，不签 5% 性能门槛 |

原 release 应用仍在 `target/implementation-evidence/performance-candidate-build-01/release/`，共享库仍在其 `../lib/java/native/libcodeana_java.dylib`。没有复制改名执行或从归档启动应用。

## 慢在哪里

```text
捕获/准备
    ↓
Java inventory → extraction [graph.process → view.add → fact projection]
    ↓                                     GC 与计算交织
CAF 封存 + 全文件 SHA
    ↓ 全量屏障
Rust 原始 CAF 校验、身份冲突/去重 → 规范 CAF
    ↓ 全量屏障
分块 protobuf → daemon 接收及 CAF 写入      ← 这一段已有异步流式 I/O
    ↓
入库 → 批量索引/FTS → 图校验/integrity_check → blob/发布 → 终态
```

| Java 阶段，秒 | JVM 冷 | JVM 热 | Native 冷 | Native 热 |
|---|---:|---:|---:|---:|
| inventory | 14.697 | 14.762 | 54.563 | 54.969 |
| extraction_total | 201.525 | 195.049 | 418.585 | 413.817 |
| 其中 unit_projection | 174.811 | 169.126 | 371.531 | 365.720 |
| 其中 graph_process | 117.247 | 113.262 | 253.161 | 245.486 |
| 其中 view_add | 0.070 | 0.068 | 0.065 | 0.072 |
| 其中 fact_projection | 57.486 | 55.789 | 118.295 | 120.155 |
| resources | 18.033 | 18.054 | 27.623 | 28.010 |
| archive_seal | 2.381 | 2.498 | 24.731 | 25.206 |

父子行存在包含关系，不能逐行相加。`view_add` 很小，不值得优先优化。Native 抽取阶段累计分配约 438.8 GiB，GC 137.5/136.4 秒；inventory 分配约 38.6 GiB、GC 23.7/24.1 秒。已确定这些阶段分配大、GC 重；没有分配栈/存活对象证据，不能断言某个 HashMap 是唯一根因。

归档封存中，Native SHA 为 24.715/25.194 秒，JVM 为 2.368/2.489 秒。该区间没有 GC，Native 线程 CPU 24.671/25.035 秒；force 仅 0.017/0.012 秒。因此额外约 22 秒主要是计算，不能解释为磁盘同步慢。具体 SHA 实现、CPU 特性或编译优化差异须在同 bytecode 的 Oracle 构建中另验，完整 SHA-256 契约保留。

| Rust/传输阶段，秒 | JVM 冷 | JVM 热 | Native 冷 | Native 热 |
|---|---:|---:|---:|---:|
| 原始归档校验/去重 | 87.500 | 85.244 | 90.344 | 90.245 |
| 其中 SQLite 去重提交 | 19.479 | 19.820 | 19.739 | 19.251 |
| 结果交付 | 37.191 | 35.654 | 37.344 | 37.132 |
| 其中 protobuf 编码 | 0.378 | 0.372 | 0.381 | 0.384 |
| daemon protobuf 解码 | 1.363 | 1.349 | 1.372 | 1.372 |
| worker 发送等待，累计 | 11.804 | 10.459 | 11.756 | 11.496 |
| daemon 最大单次接收等待 | 394.335 | 385.334 | 705.736 | 704.440 |

去重提交每 2,048 个新身份一次，四次均 1,194 次；Native 提交 CPU 18.2 秒左右，明显不是 fsync 等待。已使用私有 SQLite、缓存语句和批量事务，不应再次把“启用批量”列为未实现项。

每次 worker 输出约 3.964 GB protobuf、38,227 帧，交付段平均约 106–111 MB/s（十进制，字节数÷交付墙钟）。这是整个交付段的有效吞吐，不是 FFI 带宽或纯管道吞吐。FFI 只传约 4.2 MB 请求和几百字节结果描述符；约 5.3 GB 原始事实通过文件读取。最大接收等待与 Java+校验完成时刻对应，尚未到达首批事实时接收端没有事实可处理。

| 存储阶段，秒 | JVM 冷 | JVM 热 | Native 冷 | Native 热 |
|---|---:|---:|---:|---:|
| candidate_validated - analyzed | 193.130 | 203.289 | 199.732 | 204.439 |
| 其中 archive_validate_ingest | 70.391 | 78.701 | 69.939 | 77.314 |
| 其中 projection_query_indexes_fts | 50.305 | 47.666 | 55.718 | 51.595 |
| 其中 graph_validate_summary | 24.285 | 29.063 | 26.247 | 28.847 |
| 其中 integrity_check | 37.721 | 37.315 | 38.346 | 37.621 |
| published - candidate_validated | 36.657 | 9.266 | 37.766 | 9.563 |
| 其中 blob 文件强同步 | 23.659 | 0（复用） | 24.311 | 0（复用） |
| catalog commit | 0.004 | 0.004 | 0.005 | 0.005 |

这些阶段来自任务 sidecar 的时间差及内部存储计时，存在包含关系。完整性检查和目录/文件持久性不能为了提速直接删除。Source06 已完成批量目录同步；本轮冷文件强同步仍约 24 秒，热复用保留整批目录屏障。

## 为下一步排序

| 优先级 | 改造/实验 | 依据与验收 |
|---|---|---|
| P0 | Oracle 同 bytecode 的 Serial/G1 对照 | 本机版本平台允许 G1，Native GC 约 180 秒。先验证共享库、堆探针、生命周期/取消/失败、Java 8 桥，再在独立空应用缓存和索引目录下对照；两版都用 Oracle，预算和其余构建参数一致 |
| P0 | inventory/graph/投影的分配定位及 typed 数据实验 | 约 520 GiB 累计分配，Native graph 245–253 秒、fact projection 118–120 秒。先取分配栈与存活量；再减少重复 Map/owner/identity/JSON 构造，保留自然键序、完整事实和完整哈希 |
| P1 | 同 JAR 哈希计算实验 | Native 全文件 SHA 约 25 秒，近似全部是 CPU，GC=0。先核验 Oracle 的 SHA provider/CPU 特性，再对比同输入摘要；不能缩短哈希或不验归档 |
| P1 | 去重事务粒度和 CAF 转换/分配 | 1,194 次提交约 19–20 秒；原始校验/去重约 90 秒。对比更大但有界的事务批次、owned wire 转换和复用已有编码；保留首个冲突记录、预算、EOF、原始/规范摘要 |
| P1 | SQLite 入库、批量索引/FTS 与图校验 | 约 200 秒存储阶段。分别核对 SQL 计划、DBSTAT、读写与事务 CPU，验证有界分页/缓存语句的收益；增量 owner 替换和完整性门禁保持 |
| P2 | 有界流水线重叠 | 优先重叠 Rust 校验、交付和临时 staging，最后才完成 EOF/摘要验证及发布；Java 生产重叠另做接口设计。失败丢弃临时产物，旧 head 不变 |
| P2 | CLI 提交后通知 | watcher 30 ms 轮询可影响小查询；先订阅后读、持久事件重连、定时兜底。它不是当前 Native 多出 300 秒的主要原因 |

```text
当前版本诊断（已完成）
    ↓
Oracle Serial 构建/正确性 → Oracle G1 构建/正确性
    ↓ 同机、同 JAR、同预算；按用户要求每版一轮，独立空应用缓存/索引目录
GC 时间 + 全链路 + RSS + 完整图对照
    ↓
按剩余 graph/分配/SHA/Rust 成本选择下一项，再单独验收
```

G1 的并发/暂停指标与 Serial 不一定同口径，实验前要核验 MXBean 计时；分别记录整个进程 CPU、分析线程 CPU和峰值 RSS。更换 Oracle 编译器或对象布局的收益，不归因于 G1。沿同一固定应用/共享库路径顺序安装候选，归档只取证，不改名执行。

`../anatomist` 的 `.sdkmanrc` 与 macOS profile 已明确使用 Oracle `25.3.4+1.r25-graal`/`--gc=G1`。本机 `svm.src.zip` 的平台判断和 Darwin G1 原生库比通用文档更具体；[官方 JDK 25 页面](https://docs.oracle.com/en/graalvm/jdk/25/docs/reference-manual/native-image/optimizations-and-performance/MemoryManagement/)仍仅列 Linux，不能据此否定这个创新版本的 macOS 支持。此处已纠正早先基于该页面的判断。

| Anatomist 设计 | 可参考之处 | 已有机制/不可直接照搬 |
|---|---|---|
| `FactIdentityPreparer` | 2,048 条 keyset 分页、typed 身份、预编译语句和批量更新 | Rust 私有去重已有缓存语句/2,048 条提交；应实验粒度及重复转换，而不是重复实施 |
| `StagedGraphStore` / `SqliteStore` | 私有构建阶段与发布阶段分离，事实落盘后准备身份/引用/索引 | Rust 全量批量建投影/索引已实现；其私有 journal/sync 策略只能另做受控实验，发布持久性不降低 |
| `SemanticExecutionContext` | 同一语义操作复用连接、读锁与身份 | Rust 已有 `ReadContext`；无证据要求增加连接池 |
| `StableHash` | typed 字段避免多余 canonical Map/字符串 | 短摘要不能替换本项目完整 SHA-256 |
| `SourceBlobStore` | 内容校验、临时文件与原子移动 | 参考实现未显式 fsync；本项目每文件强同步和整批目录屏障保持 |

历史冷/热减半目标为 679.746/618.882 秒，本轮尚差 315.875/351.710 秒。**假设其他成本不变，即使把 Native GC 时间全消掉，仍约 815.7/791.5 秒**；这只是单项成本的乐观估算，不是 G1 收益预测。只改 Rust 则需把 Java 之外约 380.3/357.5 秒压至约 64.4/5.8 秒；因此应同时优化 Java。各项耗时互相包含，不能把多个估算收益直接相加。

## 证据与限制

| 证据 | 用途 |
|---|---|
| [单轮回执](../../target/implementation-evidence/native-performance-analysis-01/single-01/receipt.json) | 四阶段通过、输入/构建/fixture/校准绑定、图等价；`optimization_accepted=false` |
| [分析 JSON](../../target/implementation-evidence/native-performance-analysis-01/single-01/analysis.json)、[报告数据](../../target/implementation-evidence/native-performance-analysis-01/single-01/report-data.json) | Java/Rust 指标、GC/分配分段、资源摘要、daemon 时间线 |
| [边界指标 CSV](../../target/implementation-evidence/native-performance-analysis-01/single-01/metrics.csv)、[资源 CSV](../../target/implementation-evidence/native-performance-analysis-01/single-01/resources.csv) | 精确次数/字节、采样次数/步长/耗时、每秒 RSS/累计 CPU |
| [Java 分段 CSV](../../target/implementation-evidence/native-performance-analysis-01/single-01/runtime-phases.csv)、[存储 CSV](../../target/implementation-evidence/native-performance-analysis-01/single-01/storage-stages.csv) | 每个上下文的墙钟/线程 CPU/分配/GC、SQL/staging/发布阶段 |
| [派生校验回执](../../target/implementation-evidence/native-performance-analysis-01/single-01/report-data-receipt.json) | 原始计时及输出 SHA；CSV 累计 CPU 与 monitor 回执一致 |
| [G1 本机证据](../../target/implementation-evidence/native-performance-analysis-01/g1-feasibility.json) | Oracle release、G1 平台源码、静态库及 Anatomist 配置摘要；共享库实测尚未完成 |
| [Native GC 计时字节码](../../target/implementation-evidence/native-performance-analysis-01/native-gc-accounting-bytecode.txt) | 本轮 Serial young/complete 计时分支互斥；不泛化到 G1 |

逐记录固定每 1,024 条采样一次，次数和已统计字节精确，采样耗时不是独占阶段总量，可能有周期混叠。未统计的逐步骤 `bytes=0` 不表示零数据，实际 UTF-8 总量以 `encoded_fact_bytes` 为准。原始计时、采样和估算分列；异步读写等待不记录跨线程 CPU，`cpu_samples=0` 表示未测，不表示 CPU 为零。Java 不支持的 CPU/分配/GC 指标明确为空。

Java JVM 实际事实投影/身份/CAF、Rust 实际事实编解码/wire/CAF，各做一个预热后关闭/开启对照，摘要一致、开销未超过 3%；负开销只视为单次噪声，不宣称埋点提升速度。**Native 和完整项目的开关对照没有直接证明**，新版本性能验收前仍须验证。资源曲线涵盖启动、索引、整图校验、查询和 DBSTAT，不能当作纯索引 CPU；短命子进程可能漏采。

未跑三轮或 160 用例矩阵，没有单独恢复 app/test 性能测试；完整冻结项目仍保持既有 MAIN/TEST 输入。诊断通过不等于优化通过，不覆盖原查询性能失败。本次为下一步提供实测排序，具体存活对象、每次 safepoint 等待和 Oracle/G1 收益仍待实验。
