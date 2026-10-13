# Native 分阶段性能改造

2026-10-13：存储和索引已改为 Anatomist schema 32，Java 输出类型化 GRA1 rows，Rust 直接入库。单轮 Native 索引 582.84 → 231.52 秒（-60.3%），图库 606.65 MB；具体限制与查询回归见[当前实施记录](anatomist-storage-migration.md)。下文保留上一阶段 CAF/Fact IR 的历史结果。


2026-10-12。第一阶段实现、正确性校验与单轮性能对照已完成。第二阶段实现及验证见文末；完整迁移验收单独登记，不沿用历史版本的通过结论。

| 口径 | 固定值 |
|---|---|
| 输入 | iopengw `267c7ced4993aa88613f8f17a8099943fec98159`，仅已提交文件，MAIN/TEST |
| Native 测量 | 不分冷热、不做预热；每轮独立空应用缓存和索引目录；OS 页缓存不控制 |
| 资源 | Java 宿主/解码各 2 GiB，javac 512 MiB，worker 4 GiB，结果 8 GiB，scratch 32 GiB，编译器并发 1 |
| 目标 | 分阶段验证收益；没有整体减半硬指标 |
| 对照 | 按用户要求，同机、同输入、同预算独占一轮基线 + 一轮改造版；记录单轮耗时、CPU、分配、GC、RSS、scratch 和查询 |

## 实现

```text
Java inventory → 每上下文一次候选目录 → 单元局部覆盖 → typed Fact IR / UTF-8 → 封存 CAF
                                                                          ↓
                                      Rust 校验/去重 → 有界分块 → daemon 私有入库
                                             ↓                       ↓
                                       原始 EOF/SHA → verified End → 索引/图校验/持久化/CAS 发布
```

| 项目 | 改动与边界 |
|---|---|
| 图处理 | `GraphIdentityRewriter`、`EdgeTargetBinder` 持有上下文目录；局部节点不改变下一单元的目录。保留同文件、同身份、MAIN、provider、重载/arity 及歧义规则 |
| 身份 | 规范路径精确匹配捕获文件；未命中保留原 roots 最长前缀和推导规则。记录精确命中及扫描次数 |
| 投影 | 固定事实字段使用 typed `FactRow`；owner Map 按身份复用，仅首次实际输出登记 replacement。动态 attributes 保留；canonical 字段序不变 |
| UTF-8 | 有界任务线程编码缓冲；保留原 `String.getBytes(UTF_8)` 的 surrogate 替换行为；大缓冲不常驻 |
| Rust 交付 | owned Fact→wire；原始 CAF 完整 SHA 和身份冲突校验保留；去掉产品链路的 worker 规范 CAF 和 daemon 接收 CAF |
| 队列 | 两端各四批，每批编码正文/帧不超过 8 MiB；排队编码载荷各最多 32 MiB，不包含正在处理的对象 |
| 取消 | CAF 校验每 256 条检查取消；FFI handle 留在原 OS 线程。读写控制未来保持完整，避免半帧读取因 select 丢失 |
| 存储 | 归档和流式记录共用校验/私有事务/派生/发布逻辑；流提前关闭不能完成候选；完整性检查、FULL 持久性与目录屏障保留 |
| 诊断 | 图目录/身份工作次数；索引与 FTS 分开计时。可选 JFR 输出最多 256 MiB，诊断产物独立于性能对照 |

CLI-daemon 2、C ABI 2、Fact IR 1、analysis request/result 8、candidate schema 4 不变。
worker 协议升级到 **17**：`Begin/Chunk` 可先于全归档验证；只有成功 `End` 允许候选完成。
Java 封存前仍有屏障，本次不读取 Java 正在写入的 CAF。

## 实验与采用

| 实验 | 执行入口 | 默认行为 |
|---|---|---|
| Serial/G1 | `CODEANA_NATIVE_GC=serial/G1 ./scripts/build-java-native.sh`；同 bytecode 对照另见 `java-native-performance-variant-v2.py --gc` | 默认 Serial；G1 已通过构建、进程隔离 ABI、真实 OOM、取消/崩溃恢复和单轮完整图验证，248.90 秒；完整迁移门禁仍未据此关闭。见 Anatomist 存储迁移的 G1 记录 |
| 分配栈 | 诊断构建 `CODEANA_NATIVE_MONITORING=jfr`，运行 `CODEANA_PROFILE_ALLOCATIONS=1` | 关闭；正式性能对照拒绝开启录制 |
| 事务批次 | `cargo run -p codeana-storage --release --example dedup_batches -- facts.db scratch_root` | 2,048；8,192/32,768 为实验。唯一身份实验不代表 raw CAF 重复分布或完整索引收益 |
| 正式测量 | `scripts/java-performance-fresh-rounds.py --variants ... --frozen-project ... --frozen-receipt ... --expected-graph ... --query-plan ... --evidence ...` | 默认一轮；成功整图/查询校验后清理本轮重复库与缓存，保留回执、运行输入和计时 |

资源不得超预算；记录端到端和代表查询的单轮变化及是否超过 5%。
单轮不估计中位数、波动范围，也不把点估计包装成稳定收益。
测量工具不会自动签署 `optimization_accepted` 或修改默认 GC。
固定 SHA-256、自然键/重复次数、原始/规范摘要和首个冲突证据不改变。

流式版本的 CAF 校验、去重、传输和私有入库互相重叠。
`archive_validated_and_deduplicated` 到 `result_end_sent` 很短，不代表传输免费；
不得沿用旧串行版本的阶段相加方式。索引/FTS、图验证、完整性与发布时间仍单独记录。

## 当前验证

证据根目录：`target/implementation-evidence/native-performance-upgrade-01/`。

| 项目 | 结果 | 回执 / 日志 |
|---|---|---|
| Java 组件 | 665 项通过，包含 3 项 native-bridge 错误封装的 JVM 测试；C ABI 单独验证 | `java-components-final.json` |
| Rust | workspace 177 项通过；Clippy 无警告；Rust CLI 18 项通过 | `rust-tests-complete-final.log`、`clippy-complete-final.log`、`rust-e2e.log` |
| JVM probe / 产品 | 17 / 58 项通过；58 项完整运行中 6 次外部 SIGKILL，完全相同产物重试通过，原回执保留 | `jvm-probe-final/receipt.json`、`jvm-product-final-consolidated.json` |
| owner 生命周期 | 回归发现旧尝试按对象身份保留每条 baseline 的 owner，造成大 delta OOM；修复后只保留唯一 owner。64 MiB 重复 owner 探针、大 delta 和完整产品回归通过 | `owner-retention-probe/`、`jvm-large-budget-fixed-retry-02.log` |
| 冻结项目 JVM | 2,447,690 条事实、整图摘要与基线一致，六类查询与只读校验通过。并行回归期间运行，耗时不作性能结论 | `jvm-correctness-final/receipt.json` |
| 新 Serial 库 | 6 项 C ABI 生命周期、17 项 Rust→Native probe、58 项 Native 产品回归通过；冻结项目整图与基线一致，六类查询与只读校验通过 | `candidate-serial-build.json`、`candidate-serial-lifecycle.log`、`candidate-serial-probe-02.log`、`native-product-final/receipt.json`、`native-correctness-final/receipt.json` |
| G1 | 同 bytecode 的 Oracle 共享库能构建；C ABI 生命周期触发 `GC doesn't support multiple isolates at the moment`，拒绝采用 | `g1-lifecycle-rejection.json` |
| JFR | JVM 诊断用例通过；Native 小 fixture 捕获 226 个带栈分配事件，但 isolate teardown 等待 `JfrRecorderThread`，取样后终止实验。默认构建关闭 JFR | `jfr-fixture/`、`candidate-jfr-serial/allocation-stack-proof.json`、`native-jfr-lifecycle-rejection.json` |
| SQLite 批次 | 唯一身份索引单次 2,048 / 8,192 / 32,768 为 15.93 / 5.84 / 5.35 秒；没有 raw CAF 重复分布与端到端证据，默认不改 | `dedup-batches.json` |
| JVM 模型 | Raw 80 / AST 80 项全部执行；158 项完全一致，2 项为原有构造函数与类同名方法的身份碰撞，无新增差异。原始 collector 保留失败状态，严格模型门禁不标绿 | `jvm-model-inventory.json`、`constructor-known-issue.json`、`jvm-models/receipt.json` |

模型测量期间只暂停调度，已运行子进程正常完成，随后恢复并完成全部对照。
暂停期间对应模型执行墙钟包含调度等待，不作性能证据，见 `model-performance-pause.json`。

标准 Native 构建的完整 JVM 迁移门禁保持不变。
本轮隔离诊断库在当前组件、JVM probe、冻结项目完整 Fact IR/查询通过后构建，
共享 JVM/Native 的 483 个生产类逐字节一致；其构建通过不表示 `native_accepted`。
模型矩阵运行绑定的 JAR 与最终 JAR 的 442 个 core/common 类逐字节一致；
全部 class 对照仅 `FactProjection.class` 不同，raw/AST witness 不调用该类。
该关联由 `model-final-jar-class-diff.json` 绑定不可变归档产物；
旧临时 JAR 路径在 clean 后移除，原校验及归档摘要一致性保留在 `model-lineage-supersession.json`。
最终投影另外由完整 Fact IR 和产品回归验证。

## 单轮性能结果

按用户要求仅一轮基线、一轮改造版；两版均为 Oracle GraalVM 25.3.4 Serial。
测量时模型调度暂挂，已启动子进程结束，JFR 关闭；各自独立空应用缓存和索引目录。
两版完整图均为 2,447,690 条事实，SHA-256
`ba01dbbe4db4791f8b141c049a19a38b17e0703df17db19bf2b77b48433e4147`。

| 指标 | 基线 | 改造版 | 单轮变化 |
|---|---:|---:|---:|
| 完整索引 | 1,198.22 s | 806.20 s | **减少 32.7%（392.03 s）** |
| Java 分析 | 739.94 s | 491.21 s | 减少 33.6% |
| 图处理 | 305.57 s | 1.85 s | 减少 99.4% |
| Java 分析线程累计分配 | 457.27 GiB | 171.01 GiB | 减少 62.6%；不是存活内存 |
| Native GC 累计计时 | 223.87 s | 128.25 s | 减少 42.7%；与阶段墙钟有包含关系 |
| 进程树峰值 RSS | 1.615 GiB | 1.651 GiB | 增加 2.2%，未超预算 |
| 本轮逻辑磁盘峰值 | 15.244 GiB | 13.845 GiB | 减少约 9.2% |

六类查询单次均未变慢：resolve、structure、source、search-code、search-text、overview。
局部阶段也有变慢：事实投影 139.09→162.01 s（+16.5%）、inventory 59.34→70.74 s、
resources 30.91→41.20 s、归档封存 26.15→33.21 s（+27.0%）。
多个改动一起测量，不能据此单独归因；本轮不追加测量。
这是单轮点估计，不宣称稳定波动范围，也不自动签署优化/完整迁移验收。
Java 中仍执行完整归档 SHA，Oracle 同 bytecode 基线的 archive seal 为 26.15 s，
没有证据证明仅换 Oracle 即改善该计算。默认 GC、去重批次和持久性不变。

工作量计数：4,213 个源单元准备 66 个上下文目录，共 2,621,234 个目录节点；
387,707 次身份查找全部精确命中、roots 扫描为 0。
3,253,862 条原始事实只登记 4,406 个唯一 cached owner；
原始事实正文仍为 5,282,248,604 字节。

回执：`native-single-round/receipt.json`、`single-round-analysis.json`、`single-round-runtime-analysis.json`。
本轮通过整图/查询后自动清理两次运行的 revisions/blob/cache/candidates，日志和计时保留。

## Rust ↔ Java 交互结论

| 证据 | 判断 |
|---|---|
| 改造版 C ABI 只读 439 字节描述；启动 3.23 ms、读取描述 0.25 ms | 大数据在 5.30 GB 的封存 CAF 中，重点是后续校验、转换和入库 |
| 17,503 次 poll：墙钟累计 96.84 s、调用线程 CPU 0.174 s，最大单次 1.38 s | 不能把等待墙钟当成 FFI 计算成本；同步 poll 期间取消响应仍可能延迟 |
| worker 规范 CAF 的 encode/decode/read/write 次数从各 2,446,344 降为 0 | 本轮移除了重复归档，保留原始 CAF 的 EOF/SHA 和全局身份校验；daemon 直接私有 staging |
| protobuf 编码仍为 3,964,454,186 字节，38,227 帧；改造版编码累计 0.92 s | wire 体积和消息数量不变；性能收益来自减少重复处理及阶段重叠 |
| frame 写等待 17.63→28.87 s；去重提交 23.80→25.20 s | 有界队列的背压和 SQLite 去重仍有成本；阶段重叠，不能直接相加 |

回执：`single-round-interaction-analysis.json`。采样的逐事实计时不外推为精确总量。

## 清理和回执

| 事项 | 记录 |
|---|---|
| 已清理 | 过期性能轮次、历史索引与回放的重复 revisions/blob/cache/candidates，累计约 **199.51 GiB** |
| 保留 | 最新 JVM/native 汇总所依赖文件、冻结输入、当前基线、源代码/JAR/运行包、回执与日志 |
| 清理清单 | `target/implementation-evidence/native-performance-upgrade-01/cleanup.json`、`cleanup-02.json` |
| 本轮回归 | 同目录 Java/Rust 日志、JVM probe/product、模型、整图及 Native/性能回执；汇总见 `final-summary.json` |
| 拦截重试 | 原始 SIGKILL 保留；`jvm-product-retry-same-artifact-01` 证明同产物重试通过，摘要无漂移 |

Anatomist `dev/replace-parser@dff2e39` 已是迁入核心来源。本次借鉴其全量 staging/最终引用解析的阶段分离，
不重复实施已有预编译语句、批量事务和延迟建索引，也不采用其短摘要替换完整 SHA。


## 第二阶段：投影、异步通知与入库（实施与测量完成）

证据目录：`target/implementation-evidence/native-performance-upgrade-02/`。
以下是第二阶段的当前实现，前文 ABI 2 / schema 4 与 806.20 秒属于第一阶段。

```text
Tokio 控制帧 ───────────────────────────→ Cancel（发送+退出共用 2 s）
    │ bounded commands=8                         │
    ↓                                            ↓
固定 FFI OS 线程 ── ABI 3 ── Java task finally ── Unix socket 0x01
    ↑                                            │
    └── 完成后读取一次终态 ←── Tokio await ────────┘

Fact → ASCII 批量 UTF-8 / 固定字段头 → 借用 buffer → CAF 同步复制/写入
     → Rust 校验+去重 → schema 5 写入器 → typed selectors + 声明投影 + rowid FTS
```

| 优先级 | 已实现 | 边界 |
|---|---|---|
| P0 | ASCII 连续段一次 reserve；字段头预编码；自然顺序 SortedMap 直接遍历；`encodeTo` 借用缓冲；owner 固定字段行 | 每条记录 8 MiB、JSON 64 MiB、保留缓冲 64 KiB；回调同步消费；重入使用独立缓冲；surrogate/canonical 字节不变 |
| P1 inventory | contract/identity/catalogue/erasure 分项计时；captured unit 和 kind Set 提前准备；上下文 erasure 缓存 | 最多 8192 / 8 MiB，closeContext 清空；保留已有身份及 catalogue 缓存，不改变 contract 算法 |
| P1 resources | analyzer 和 graph 共用本次 resourceView；一次建立上下文可见性集合 | 每次 snapshot 新建不可变视图，不跨视图更新复用；保留本地/依赖 MAIN bean 规则 |
| P1 FFI | 创建、调用、销毁 isolate 均在专属线程；Unix socket 完成通知；持久半帧读取进度 | 产品链路取消优先，无周期 Native poll；Native 保持不可 Send；FD 用 CLOEXEC 复制，非阻塞且抑制 SIGPIPE |
| P1 dedup | 提供同一真实 CAF 的 2048/8192/32768 单次对照入口 | 真实 CAF 单次选择 **8192**；重复事实分布、首冲突 offset、完整 EOF/SHA 不变；校验取消仍每 256 条 |
| P2 SQLite | schema 5 的 signature/status 独立列；统一 FactWriter 同事务写声明投影；保留 rowid FTS；支持旧 head 全量重建 | 真实 CAF 单次选择 **8 KiB**；signature/status 放在正文前；FULL 与完整校验不降低，旧库不迁移/不自动删除 |

Anatomist 参考仍为 `dev/replace-parser@dff2e3954096853352ec91fcbfc6b995fbd4f390`：
借鉴 bounded tasks、全量 staging 和身份预处理；已有缓存与分页不重复实现。
该分支没有可直接复用的 Rust↔Java C ABI 完成通知桥，本轮按本项目 isolate 所有权设计。

### 第二阶段验证

| 验证 | 当前结果 |
|---|---|
| Java 组件 | 666 项通过，零失败/跳过 |
| Rust 组件 / CLI | 181 个独立组件测试通过，Clippy 通过；CLI 19 项通过，包含 schema 4 拒绝读取/delta、schema 5 全量重建、保留旧 revision |
| JVM / Native probe | 各 17 项通过 |
| Native C ABI | 7 项通过，包含完成通知成功/异常、调用方 FD 关闭后 native duplicate 仍有效、未启动任务关闭释放 FD |
| 产品、冻结完整图与六查询 | 最终 Native / JVM 各 58 项通过。两后端完整图均 2,447,690 条、摘要一致，六类查询与完整性检查通过；并行正确性运行不作性能结论 |
| 真实 CAF 批次/页布局 | 入口 `cargo run -p codeana-storage --release --example dedup_batches -- --caf descriptor.json scratch_root`；每配置只跑一次，临时库结束后释放 |
| Raw/AST 模型 | 全 160 项执行完成，158 项一致；2 项已知上游身份碰撞经独立重放确认，无未解释失败。严格模型门禁仍未通过 |
| 正式性能 | 唯一一轮对照已完成，不分冷热、不做预热；overview 单次 +6.9%，未通过全部查询不退化门槛，未追加测量 |

真实 C ABI 验证发现 Darwin ARM64 的 fcntl 不能用固定三参数 CFunction 描述其可变参数 ABI（包装函数汇编确认第三参数写入栈）。
已加固定签名 C 包装并静态链接，修复后 7 项生命周期通过。失败库及完全相同库的重试记录保留。
组件/产品回归中的外部 SIGKILL 单独保留原日志，同名称、同路径、同 SHA 产物重试；不改代码掩盖拦截。
标准 Native 构建的完整 JVM 前置门禁保持不变；当前共享库属于隔离诊断构建，`native_accepted=false`。


第二阶段参数实验使用同一份 5,295,264,056 字节 CAF：3,253,862 条原始事实，
2,446,344 个唯一身份、807,518 条完全相同的重复事实。每配置仅执行一次。

| 配置 | 单次总耗时 | 完成库大小 |
|---|---:|---:|
| 去重 2048 | 72.357 s | 仅临时身份索引 |
| 去重 8192 | 68.008 s | 仅临时身份索引 |
| 去重 32768 | 67.073 s | 仅临时身份索引 |
| 4 KiB 页 | 255.143 s | 10,305,126,400 B |
| 8 KiB 页 | 204.822 s | 9,194,881,024 B |
| 16 KiB 页 | 197.469 s | 8,670,674,944 B |

按“比基线至少快 5%，与最快值相差不超过 5% 时选较小参数”选择 8192 / 8 KiB。
单次局部变化分别 -6.0% / -19.7%；不等于完整索引收益，也不证明稳定增益。
RSS 采样峰值 1,009,811,456 B、scratch 采样峰值 10,305,126,400 B，未超预算。
每配置检查完整 EOF/SHA、重复分布；页实验保留 SQLite/FTS 完整性检查和 FULL。
临时实验目录随配置结束释放。记录见 `caf-layout-03.json`、`layout-selection.json`、
`caf-layout-sampled-resources.json`；两次被终止的启动日志保留，随后同名称、同路径、同产物直接运行。

最终整图正确性记录为 `native-correctness-final/receipt.json`、`jvm-correctness-final/receipt.json`、
`whole-fact-equivalence-final.json`。Native 产品链路的 `engine_poll.calls=1`，该终态 getter 为 17.9 µs；
Java 分析等待由异步 socket 承担，不将等待墙钟算成 FFI 计算成本。
这些正确性运行与其他测试并行，其墙钟/查询时间不能用于单轮性能验收。

高负载回归另发现抓取阶段 `CAPTURE_TIMEOUT` 被映射为普通失败码 1；
已保持具体错误码并修复 CLI 返回值为 124，与整体 deadline 超时一致。
原失败日志及原路径重试结果保留，不将该问题归类为外部拦截。


### 第二阶段唯一一轮完整链路结果

以第一阶段封存的 ABI 2 / schema 4 运行包作基线，当前 ABI 3 / schema 5 作候选。
两版均为 Oracle GraalVM 25.3.4 Serial；模型和其他测试全部结束后顺序执行。
各自独立空应用缓存/索引，不做 Native 预热，OS 页缓存未控制。
同一轮基线 **652.404 s**，候选 **597.473 s**，减少 **54.931 s / 8.42%**。
前文第一阶段 806.20 s 是历史测量，不能与本轮候选拼接计算第二阶段收益。

| 指标 | 同轮基线 | 第二阶段候选 | 单轮变化 |
|---|---:|---:|---:|
| 完整索引 | 652.404 s | 597.473 s | -8.42% |
| 事实投影 | 125.099 s | 112.063 s | -10.42% |
| inventory | 53.409 s | 52.688 s | -1.35% |
| resources | 24.058 s | 18.602 s | -22.68% |
| 归档封存 | 23.057 s | 21.679 s | -5.98% |
| 校验与入库 | 103.063 s | 90.113 s | -12.57% |
| 投影/查询索引/FTS 阶段 | 56.310 s | 39.904 s | -29.13% |
| 完整性检查 | 41.158 s | 39.875 s | -3.12% |
| 去重提交 | 1194 次 / 18.459 s | 298 次 / 15.950 s | 耗时 -13.59% |
| Native 终态读取 | 13617 次 | 1 次 / 10.5 µs | 等待改由 socket 承担 |
| 分析线程累计分配 | 170.94 GiB | 161.64 GiB | 约 -5.4%；不是存活内存 |
| Native 累计 GC 计时 | 94.004 s | 91.000 s | -3.20%；与阶段墙钟重叠 |
| 进程树采样峰值 RSS | 1.741 GiB | 1.673 GiB | -3.93% |
| 逻辑磁盘采样峰值 | 13.902 GiB | 13.244 GiB | -4.73% |
| facts.db | 10,252,996,608 B | 9,210,683,392 B | -10.17% |

各阶段存在包含与重叠，不能相加当作总体收益。逐事实抽样计时不外推为精确总量。
两版原始 CAF 记录数与字节数、正文编码量和完整 Fact IR 一致；整图仍为 2,447,690 条、前述完整摘要。
资源预算、SQLite/FTS 完整性校验均保留。

| 查询 | 基线 | 候选 | 单次变化 |
|---|---:|---:|---:|
| resolve | 434.97 ms | 401.75 ms | -7.64% |
| structure | 451.29 ms | 463.78 ms | +2.77% |
| source | 464.44 ms | 445.62 ms | -4.05% |
| search-code | 1056.53 ms | 726.30 ms | -31.26% |
| search-text | 413.25 ms | 398.85 ms | -3.48% |
| overview | 317.34 ms | 339.29 ms | **+6.92%** |

六查询的功能校验通过，但 overview 超过 5% 不退化门槛，因此 `performance_accepted=false`。
仅有一轮，不能据此判断稳定退化或稳定收益；按用户要求不追加测量。
后续优先查看 overview 的具体 SQL/执行路径，以及 inventory contract 指纹：
候选指纹计算 95,252 次累计 38.878 s，仍占 inventory 墙钟约 74%；
erasure 缓存命中 229,938 次、未命中 222,470 次，保持上下文隔离及有界缓存。

第二阶段模型 160 项全部执行，158 项一致；两个已知上游身份碰撞经最终独立重放确认，
无未解释失败，严格模型门禁仍为 false。JVM / Native 共享生产类 488 个及嵌入 javac worker 字节一致。
标准 Native 构建的完整 JVM 前置门禁未绕过，隔离诊断库不签署完整迁移验收。
ABI 3 与 schema 5 为破坏性升级：运行包须成套更新，旧事实库须全量重建；旧 revision 不自动删除。

回执：第二阶段目录的 `native-single-round/receipt.json`、`single-round-analysis.json`、
`jvm-model-inventory-final.json`、`constructor-known-issue-final.json`、
`final-jvm-native-bytecode-lineage.json`、`timeout-fix-validation-lineage.json`、`final-summary.json`。
本轮两次性能运行生成的 revisions/blobs/cache/candidates 已在校验后自动释放，计时和证据保留。
程序名称与执行路径保持不变，没有提交或推送代码。
