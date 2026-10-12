# 索引发布：CLI → worker → CAF1 → 候选 → revision

[入口](../README.md) · [共用契约/批量阈值](../contracts.md#打批契约) · [追溯](../traceability.md)

```text
CLI / daemon → 捕获与调度 → Rust worker → JVM bridge
                                                ↓
                      CAF1 → 校验/去重/分块/背压
                                                ↓
           prepared INSERT → facts + 投影 + 依赖
                                                ↓
              派生/索引/统计/校验 → 耐久化 → CAS 发布
```

**先用固定事实完成 Rust 接缝，再接真实 JVM；每批边界和故障窗口独立做 TDD。**

<a id="idx-01"></a>

## IDX-01 固定事实的批量入库与原子候选

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：候选 schema 3 同事务维护 source_units / declaration_projection；owner 删除与 kind 变化回归通过 |
| 行为 | 合法事实完整发布；冲突、截断或不完整输入不会成为可见 revision。 |
| 调用链 | 固定 Fact IR/CAF1 → Rust worker validator/dedup → staged.rs prepared INSERT → 候选 facts/FTS/投影 → 完整校验 → catalog。 |
| 开发前置 | 无；固定事实与 mock 可独立实施 |
| 联调前置 | 无；本任务验证 Rust 独立链路 |
| 实施 | 沿用已有归档、去重、候选/索引/发布；补真实 FactValidator 对新增模型的兼容和必要投影写入口。完整正文权威，按 fact ID/owner 维护可重建投影，kind 变化/删除不残留；版本化 schema，在私有候选升级，旧 revision 不原地修改。字段投影由各查询任务提出并同事务接入。 |
| RED / 基线 | 手工可判定事实：0/1/255/256/257、末批、1 MiB/8 MiB/帧边界、合法超缓冲单条、多合法批大小等价；同 ID 同正文/不同正文、错序/缺 End/截断/摘要/计数/owner 越界。新增缺口先 RED，已有错误窗口和恢复测试直接回归。 |
| 完成条件 | 真实 SQLite 与 CLI/mock 均通过；多重集、diagnostic、coverage、provenance 不受批大小影响；facts/投影/依赖/FTS 一致，非法端点拒绝，合法 unresolved 保留。旧库读路径和缺能力提示明确，完整校验后仅一次发布；无 JDK。 |
| 追溯 | 旧 01 §3 打批；S3；03 打批与存储/传输存储故障；V2 |

<a id="idx-02"></a>

## IDX-02 真实 JVM 最小闭环和交付生命周期

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：真 JVM 最小 CLI 闭环通过；完整生命周期门禁未通过 |
| 行为 | 真实 CLI 索引 Java fixture 后可查询发布声明与捕获源码。 |
| 调用链 | CLI → daemon → Rust worker → 显式 JVM runner → bridge → CAF1 → candidate → revision → CLI structure/source。 |
| 开发前置 | IDX-01；可先用 mock request/result 接 worker |
| 联调前置 | JVM-03 |
| 实施 | JVM-03 到位后接真实解析；request/result 8、protocol 15、baseline/cancel/ack 共用。JVM 独立 manifest/启动入口/摘要，不读取 native manifest；任务私有请求和描述符，日志 stderr。Java 按文件流出，框架目录/模型内存有界；JVM host+javac 合计既有进程树预算。 |
| RED / 基线 | 先写真 CLI Hello 索引→查询失败用例；补取消/超时/JVM 或 javac 崩溃/OOM、缺 End、错版本/旧产物、多项目同名、多会话/取消后重跑、发布前后 ack 窗口。已有测试复用，新增或变化行为才 RED。 |
| 完成条件 | JVM 入口不触发 native-image；真实解析事实完整校验后发布，结果与 byte span 独立核对。进程组/上下文释放，消费者结束才释放归档；发布前失败 head 不变，提交后 ack 失败不倒退；未 ack 不提交会话基线。 |
| 追溯 | A9/A10/A11；旧 01 JVM 入口；S6 归档接管；T3/P3；最小 V3 |

<a id="idx-03"></a>

## IDX-03 诊断计数、strict 与公开报告

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：diagnostic count 按发生次数聚合，新增 RED/GREEN 通过 |
| 行为 | 区分事实条数、诊断次数和样本省略，可定位 JVM 能力与失败原因。 |
| 调用链 | IndexDiagnostic/AnalysisCoverage/Retention → bridge → 存储计数/投影 → report/doctor/发布判定。 |
| 开发前置 | IDX-01 |
| 联调前置 | IDX-02 |
| 实施 | 复制计数/覆盖语义，存 capability/owner/status/code/reason/phase/module/scope/severity/count；按模块/阶段/代码查询。完整计数与采样分离，不从前 100 样本推 coverage，避免 capability 重复累加。Rust strict 在发布前拒绝，不复制上游先发布后失败退出。 |
| RED / 基线 | 一条 count=7、超过 100 个诊断样本、零事实文件、能力重叠、strict/非 strict；复现 staged.rs 按 fact 行数汇总缺口。参考 NavigationDiagnosticsTest/QueryCoverageServiceTest，JVM 再比真实诊断。 |
| 完成条件 | 条数/次数/省略数各有独立断言，producer/profile/输入身份及 partial 保留；report/doctor 定位准确，不把 mock 记成 Java 通过，不新建健康平台；具备 Rust 独立及 JVM 联调回执。 |
| 追溯 | A8/A15；S3；03 诊断及覆盖 |

<a id="idx-04"></a>

## IDX-04 候选统计、索引成本与容量改进

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：full/delta 私有候选逐索引 ANALYZE；索引数/耗时/警告进入发布报告；历史库摘要不变回归通过。查询成本与容量门禁未结束 |
| 行为 | 发布库只读；候选查询计划和容量有可重复证据。 |
| 调用链 | 导入 → 派生/建索引 → ANALYZE → 完整校验 → 发布；成本采样 → 单项优化。 |
| 开发前置 | IDX-01 |
| 联调前置 | QRY-01、QRY-03、QRY-04、QRY-05 |
| 实施 | 参考 DataWriter，在派生/建索引后、发布前 ANALYZE，计入任务期限；大 delta 也评估。普通统计失败可告警，取消/写入/完整性错误仍使任务失败。记录每索引使用、列序/条件、尺寸和维护成本，有证据才删冗余项；临时归档/dedup/candidate 峰值、backup 字节和 blob 复用/写放大分开。 |
| RED / 基线 | 固定正确结果后记录计划/扫描/解码/大小；新增统计步骤与历史库不可写契约先 RED。选择性/宽范围查询都测；不锁死整段 EXPLAIN、不以毫秒波动制造 RED，必要聚合扫描合法。 |
| 完成条件 | full/大 delta 统计、旧库回归通过，收益连同空间/写入取舍报告，free pages 与实际释放区分；各查询联调后度量。最终产物三轮测量由 ACC-04 完成。 |
| 追溯 | S4/S6；旧 02 已有能力/容量；03 性能 |

### 计数、维护和容量的补充约束

- IDX-03 不把 count=7 展开成七条相同事实；严重度、语义覆盖、完整性校验和样本展示分别映射，样本裁剪不改变 strict 发布结论。
- IDX-04 删除冗余索引前，在私有副本证明结果与查询性能不退化；不能只因计划出现索引名称就宣称收益。
- IDX-02 在 Rust 归档封存接管、Java 原归档消费者结束后释放原归档；失败只保留必要诊断和复现数据，确认/引用生命周期不变。
- 共享/压缩实验是本项目候选优化，不宣称 Anatomist 已提供。采用内容共享须证明无损恢复及引用/GC 正确；减少 delta 复制还须证明恢复不退化。

## 复用的存储基础

| 已有能力 | 本轮处理 |
|---|---|
| staged.rs prepared 写入；全量后建索引/FTS；delta 不无谓重建 | 复用，按变更回归 |
| parent/target/relation/signature 组合与条件索引 | 按真实计划调整，不重复加同义索引 |
| external-content FTS5 trigram、短词 literal fallback、旧库路径 | 保留语义，QRY-04 补范围与规模 |
| call_site/call_target 分离、occurrence 身份 | 不吞合法重复次数 |
| source CAS blobs/hash/durable；revision/catalog CAS/reader pin | 复用既有 blob 损坏、发布/历史/GC 测试 |
| 去重库 OFF、每 2048 新身份提交 | 只用于临时可重建去重库 |
| candidate DELETE/FULL；catalog WAL/FULL | 不照搬上游快速 PRAGMA；fact_name 冗余索引已删除 |

### 有证据才开展的实验

默认关闭，不阻塞必做链路；由 IDX-04 登记未开展/无收益/退化/采用及回执。

| 实验 | 前置与退出条件 |
|---|---|
| type graph/facet 共享 | 实测重复正文占比；身份、owner、范围、次数不丢，证明查询/增量收益 |
| body 压缩 | 格式/读取/恢复可验证，旧库可读，收益大于解压开销 |
| delta 避免全库复制 | 先测 backup 成本；复用历史/读者/CAS/GC，变化点才补 TDD |
| 候选耐久性调整 | 实测写成本且所有发布崩溃窗口安全后才评估；已发布数据绝不 OFF |

本机节约磁盘统一见 [公共执行约定](../contracts.md#6-本机磁盘执行约定)。

2026-10-10：`diagnostic-retention-formal-01` 验证 50004 诊断 occurrence、100 条有界样例、按 A.java/B.java 保留 50003/1，并在 Store 重开后计数。已纳入正式 Rust storage 测试，对应原 NavigationDiagnosticsTest 的 retention 预期。`java-doctor-cli-02` 验证显式 JVM 时显示 java8_jvm、实际 bundle adapter 和版本；不是旧 java8_native 标签。
