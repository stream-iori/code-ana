# iopengw Java 性能诊断

> 历史记录（2026-10-13 文档对齐）：旧 Java/CAF1 流水线的诊断；阶段耗时和暂缓决定按原轮次解释。现行版本、验证范围及未完成门禁见 [当前实现](current-state.md)，最新结果见 [存储实施记录](implementation/anatomist-storage-migration.md)。

2026-10-09：用户要求本轮暂不考虑性能与三组独占冷启动；以下记录为历史证据。当前先完成模型收集、问题分类与下一轮修复方案。

2026-10-08。结论：移入的 javac/JavaModelExtractor 核心相同，整个索引流水线不同。code-ana 在核心提取之后新增的事实投影、重复编码及任务级保留数据已有明确开销证据；原 daemon 的 1086.611 秒仍不能全部归因到这些开销，需重放其实际捕获请求。

## 基线与测量边界

| 项目 | 固定值 / 限制 |
|---|---|
| 目标源码 | iopengw `267c7ced4993aa88613f8f17a8099943fec98159`，仅已提交文件 |
| 参考代码 | Anatomist `dff2e3954096853352ec91fcbfc6b995fbd4f390` |
| 参考 JAR | SHA256 `457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e` |
| 原产品 native 库 | SHA256 `e5077aa1e209282e2bf5d468a3130ed92cfacd119be1929f83c3e74f375b2a33`，adapter v4 |
| 工具链 | SDKMAN GraalVM 25 宿主 / Native Image；Zulu 8 javac 子进程 |
| 预算 | 宿主 Java/native 堆 2 GiB，javac 堆 512 MiB，编译并行度 1 |
| 内容 | MAIN/TEST、Lombok AST、Spring/SOFA；直接请求包含资源根 XML 与源码祖先的已提交 lombok.config |
| classpath | 参考报告中 66 个编译任务的外部 JAR 列表和顺序，与冻结 Maven 清单完全一致 |
| 无效编码 | 参考扫描显式排除 1 个非 UTF-8 TEST 文件；code-ana 保留输入并标记解析失败。有效 Java 文件均为 4213 个 |
| 隔离 | 全项目计时串行运行；新签名缓存、输出目录。未清 OS 文件页缓存，不是三轮正式性能验收 |

相邻 `../anatomist/target/anatomist.jar` 与冻结参考不匹配，会拒绝源码支持的 `index.parser` 配置。本轮使用既有验收目录内经摘要核对的冻结 JAR，未覆盖相邻产物。失败的启动尝试不计入参考耗时。

| 运行 | 墙钟耗时 | 实际结果 |
|---|---:|---|
| 原 Rust daemon + 产品共享库 | 1086.611 秒 | Java 投影报 `CONFLICTING_FACT_ID`，未发布 |
| 冻结 Anatomist CLI | 186.295 秒 | 成功索引并写库：55399 nodes / 168038 edges |
| 直接 Java JVM，同配置诊断 | 150.280 秒 | 63 个模型上下文后复现相同冲突，未发布 |
| 直接 Java native，同配置诊断 | 633.943 秒 | 63 个模型上下文后复现相同冲突；无 Rust、C ABI、发布数据库 |

直接入口调用生产 `AnalysisSession`，不是另一套解析算法。请求准备（清单读取、文件/JAR 摘要验证）不在直接分析计时内。参考 CLI 的计时包含发现、Maven 元数据导出和数据库发布。直接入口尚未重放所有 Rust 捕获的控制/资源文件及任务缓存，所以不能把失败耗时比例当作完整索引性能结论。直接诊断命令见 [Java README](../lang-java-adapotr/README.md)。

同配置直接 JVM/native 都在 `app/biz/service-impl@TEST` 的同一 `symbol_c3aed7b2f276f66e41a1df95fa5f3fcce61968315953cbf62f0334247ea2815e` 失败，无 SIGKILL。native 的进程树峰值 2589622272 字节（约 2.41 GiB）。原 daemon 比直接 native 多 452.668 秒，现有探针未覆盖该差距的全部条件，不能直接断言是 FFI、缓存或 Rust 引起。

| 同配置阶段 | JVM | native | 解释 |
|---|---:|---:|---|
| javac 等待 | 42.908 秒 | 54.835 秒 | 差异远小于总耗时差异 |
| 核心 JavaModelExtractor | 11.455 秒 | 74.091 秒 | 不含投影回调，仍受宿主 GC 影响 |
| 声明库存 inventory | 5.702 秒 | 30.747 秒 | 遍历辅助声明并建立符号索引 |
| 新增 unit_projection | 68.449 秒 | 421.536 秒 | 包含 ProjectView 累加、事实转换、编码/摘要、归档及期间 GC |
| extraction_total | 80.380 秒 | 496.274 秒 | 包含提取与投影，不与上述行相加 |

最终 native GC summary：累计分配对象 131805.17 MiB（约 128.7 GiB）；incremental GC 1988 次 / 46.544 秒，full GC 247 次 / 312.290 秒，总 GC 358.834 秒 / run time 632.179 秒，约 **57%**。构建输出确认该探针使用 Serial GC，最大堆 2 GiB。GC 时间与上表各阶段重叠，不能当成独立工作重复相加。结论是事实投影和整体分配/保留压力需要先处理，而不是仅增加堆；目前不能凭 GC summary 确定每个保留对象的占比。

## 数据路径差异

```text
Anatomist:
  javac → JavaModelExtractor → 分批暂存 → 分页事实摘要 → 数据库发布

code-ana Java:
  javac → 声明库存（含辅助 NoCode 单元）→ JavaModelExtractor
        → ProjectView 累加 → syntax / binding / type 投影
        → JSON + SHA + 全局 ID 去重表 → 再编码 JSON → 磁盘 FactArchive
                                                        ↓
                         Rust：校验、存储、原子发布、查询
```

当前冲突发生在 Java `FactProjection.emit`。该失败分支尚未执行 native 最终结果 JSON 展开，也未进入 Rust 发布，因此本次 18 分钟失败不能归咎于 Rust SQLite 发布。

| 发现 | 代码 / 运行证据 | 应参考的 Anatomist 处理 |
|---|---|---|
| 每个新事实至少两次 JSON 编码 | `FactProjection.emit` 先 `Json.write` 求 SHA，`FactArchive.add` 再 `Json.write` 写磁盘；早期 JVM JFR 中 `Json.append` 为首个应用帧的采样 2381/6910，约 34.5% | 事实摘要使用规范字段；`StableHash` 复用线程内 MessageDigest；本项目可先复用一次编码的原字节，保持现有身份与冲突判断 |
| 源码根元数据被展开成文件列表 | `CapturedProject.roots/moduleInputs` 将每个 Java 文件作为根；`JavacMavenInputs.owningModule` 逐模块、逐根做 startsWith；对应 JFR 应用帧采样 811/6910，约 11.7% | 原版 Maven 模型用源码目录作为根；传递真实根元数据或建立准确的 producer 归属索引，同时仍只分析 Rust 已捕获文件 |
| 辅助声明重复物化并累计 | 每个上下文遍历全部 model units 作 inventory；`ProjectView.symbols.addAll` 累加，snapshot 才构造去重目录，原 List 仍保留重复项 | 复用 NoCode/声明目录，按来源及可见性建立项目视图；避免反复展开、长期保留同一辅助声明 |
| 去重及原始模型事实的任务级内存增长 | `FactProjection` 保存全任务 ID→摘要、symbol/rawSymbol 表；rawEvidence 展开 syntax/binding/type；native GC 明显增长 | 原版 `FactIdentityPreparer` 每页 2048 条并批量更新暂存库；全局存储/去重应由 Rust 管理，Java 只保留有界分析状态 |

上述 JFR 来自早期省略根 lombok.config 的诊断变体，用于定位调用热点，不代表最终同配置阶段百分比。没有保留对象堆转储，不能断言某一个表独自造成全部堆压力。

早期完整 native 变体（省略配置）运行 460.334 秒后同样冲突；GC summary 记录累计分配 132952 MiB，GC 250.117 秒 / 总计 459.142 秒，约 54%；其中 full GC 231 次、215.134 秒。5 上下文对照中，javac 等待和核心提取接近，native 新增 unit_projection 为 15.111 秒，JVM 为 6.680 秒。它们支持继续检查新投影的分配量和 retained state，不能替代最终同配置验收。

native 30 上下文控制实验中，构建默认堆 2 GiB 与默认 256 MiB、运行时同设 2 GiB 分别约 99.8 / 95.2 秒，未显示足以解释 18 分钟的大幅差异。81 文件小模块实际共享库 C ABI 与独立 native 都约 5 秒；该小对照与回归启动有短暂重叠，只用于排除明显的固定调用开销，不作为精确性能门禁。

注意：参考报告的实际编译输入数与探针的 model units 数不能直接比较，后者还包括补回的辅助 NoCode 单元。不能据此断言重复编译了多少源码；后续必须记录 `batchEvidence/analysisStats` 的实际编译批次指标。

## 后续修复顺序

| 顺序 | 工作 | 验证边界 |
|---|---|---|
| 1 | 保存冲突的两个候选事实、实际 worker 请求、捕获和缓存摘要；补充编译批次、投影、编码、归档计数及阶段耗时，重放同一产品共享库 | 查明冲突字段与身份来源；解释原 1086 秒与直接 native 的剩余差距；不忽略冲突、不把失败计为成功 |
| 2 | 对齐来源目录/producer 归属；一次编码用于摘要和归档 | 多源码根、嵌套模块、MAIN/TEST、生成源码归属不变；字节、事实 ID、冲突检测、预算和取消语义不变 |
| 3 | 共享任务内声明目录和有界解码缓存；辅助声明按来源和可见性复用，ProjectView 提前去重 | 辅助类型查询、XML-only 上下文、Lombok AST、泛型/注解/嵌套类型与原版一致 |
| 4 | 减少重复 syntax/type 图展开，保留模型查询；事实流和全局去重有界化 | Java 负责语言分析，Rust 负责存储/缓存/发布；不移入 Java JDBC 产品存储，不删除必要绑定或未知信息 |
| 5 | 正确性通过后验收容量与耗时 | fixture → iopengw 完整发布与查询 → raw/AST、14 域语义差分 → 独占三轮成功索引；中位数小于同口径参考的 2 倍 |

本轮新增诊断钩子不暴露到协议或 C ABI，不改变适配器身份。Java 77 项回归通过，迁移来源校验仍为 204 文件 / 8 适配文件。完整 iopengw 发布、语义与容量门禁仍未通过。

## 证据位置

均位于 `target/java-migration-continue/timing-2g/`：`reference-frozen-receipt.json`、`reference-report.txt`、`reference.db`、`classpath-comparison.json`、`aligned-jvm/`、`aligned-native/`、`jvm-sample-summary.json`、`full-native/`、`prefix-jvm/`、`prefix-native/`、`prefix30-native/`、`prefix30-default256/`、`facade-library/`、`facade-executable/`、`profiling-jvm-tests.log`。原产品失败见 `../iopengw-v4-2g/receipt.json`。大型运行证据不提交 Git；本文记录测量值、边界和摘要用于追溯。


## 2026-10-08 v5 实际请求与交付层复核

同一产物在用户处理 SIGKILL 后，daemon 核心/深层类型图/XML-only 3 项通过（207.723 秒）。新 iopengw MAIN/TEST/XML 独占运行 513.089 秒失败，无异常信号、无源码或产物漂移；第 65 个 javac batch 后 CAF1 到达 4 GiB，`FACT_BYTE_LIMIT`，未发布。不能用本轮作为性能门禁通过证据。原生阶段：声明目录 79.312 秒，事实投影 210.860 秒，javac worker 等待 80.935 秒；后者与整个 backend 计时嵌套，不能直接相加。目录缓存 849952 次命中、353977 次未命中；编码 2958214 次，归档 2958213 条。这些是去重前的物理输出统计，不能称为独立语义事实数。

证据：`target/java-migration-continue/iopengw-v5-verified-final/receipt.json`；实际请求、初始缓存/输入及冻结运行产物在 `target/java-migration-continue/iopengw-v5-replay-final/java-replay-0wgt9c/`。此前因来源清单说明变更主动中止的一轮不计入验收。

复核相邻 Anatomist HEAD 确认为冻结 `dff2e3954096853352ec91fcbfc6b995fbd4f390`：

| 位置 | 实际行为 / 与 code-ana 的差异 |
|---|---|
| `anatomist-core/.../extract/JavaModelExtractor.java` | 只遍历 context.publish 提取；迁入版本也如此，不能将问题归因成辅助源码全部重复提取 |
| `anatomist-core/.../extract/JavaCallFacts.java` | 外部调用边保存 isExternal / externalTargetFqn，不为每次目标解析再生成外部节点 |
| `anatomist-app/.../application/JavaIndexPass.java` | 按文件数或事实数成批 writeRawBatch，写完 clearFacts；没有我们的逐节点公共 Fact IR 投影链路 |
| `anatomist-core/.../store/StagingSchema.java` | 节点 ON CONFLICT(id) 更新、声明 INSERT OR REPLACE；不能宣称上游从不遇到重复，也不能照抄覆盖策略掩盖本产品身份冲突 |
| `native-bridge/.../FactProjection.target()` | 每次目标解析都会构造并编码 external_symbol；延后到 Rust 全局去重后，完全相同的候选也先写入物理归档 |
| `native-bridge/.../FactProjection` 原始模型投影 | java.type/java.binding 等额外事实需要保存查询证据；共享内存类型图仍会在多个事实 JSON 内反复编码，并未消除磁盘载荷重复 |

独立小例子：同一文件调用 System.out.println 100 次。冻结参考提取为 3 个节点、102 条边；新产品 CAF1 写 1018 条，Rust 去重后 820 条，仅 2 个唯一 external_symbol，却发生 198 条完全重复写入。去重后还包括 406 条 java.type、100 条 java.binding、100 个调用点、100 个调用目标，计数模型不同，不能把 820/105 直接当成性能倍数。证据：`target/java-migration-continue/repeated-target-diagnosis/`，包含原生事实、阶段统计及冻结参考逐域数据。全项目按种类的重复比例尚未取得，不能将全部 4 GiB 都归因于这 198 条样例重复。

后续改造需优先处理投影路径：按 context 的稳定外部声明目录复用目标，避免重复构造/编码，内容变化仍保留并交由 Rust 冲突检查；原始类型/绑定证据采用共享、分片的不可变模型表示，Rust 管持久化、事实去重与发布。必须先验证所有查询证据仍可读、冲突不被覆盖，再独占重跑，不通过扩大预算或隐藏事实绕过门禁。单纯在编码后加一个 LRU 只能减少重复写入，不能消除重复构造与类型图编码，尚未采纳为最终修复。

用户已删除旧 lang-adaptor。当前产品与构建主入口使用 lang-java-adapotr；旧模型验收和若干脚本仍调用旧类/协议，需要明确替换成迁入核心的验收入口，不能只改目录名。完整正确性/性能门禁继续保持未通过。


## 2026-10-08 有界投影与 XML 发布诊断

| 同一实际请求的诊断 | 结果 | 测量限制 |
|---|---|---|
| 只复用外部目标与类型图编码，JVM | 190.317 秒仍触及 4 GiB；类型事实约 1.810 GB，元注解约 614 MB | 未成功，不计性能门禁 |
| 再复用完全相同元注解投影，JVM | 189.154 秒完成 66 个编译上下文；2291778 条物理记录，3935701276 字节（约 3.665 GiB）；元关系缓存命中 729789，外部目标命中 235790 | 诊断运行有并发，只证明预算内完成，不能作为正式速度比 |
| v6 真实 Native + Rust | Java Native 完成 358.316 秒，投影 127.480 秒；完整任务 539.233 秒因 XML 模型校验失败，未发布 | 与历史 v5 的 210.860 秒投影并非三轮独占对照，不宣称性能通过 |

XML 失败定位到 `app/web/home/src/main/resources/MERGE-INF/merge-smvc-scheme-default.xml` 的两个 #if 分支。Anatomist 原节点按 bean 名称形成相同原 ID，投影查表覆盖后把第二次定义的属性模型放进第一次定义的源范围。两次 multipartResolver 分别配置 32 MiB / 512 MiB；不能靠删除其中一个定义或跳过校验处理。v7 用原始位置区分 occurrence，所有候选先登记，引用不唯一时输出 possible 框架占位符和完整候选；冻结原始提取数据保持不变。资源独立重放与 Native fixture 已通过，完整发布仍须等待回执。

现有优化保持类型、绑定、语法和原始模型查询证据。Rust 继续负责 CAF1 全局去重、冲突检查、持久化与原子发布；有界 Java 缓存只减少完全相同投影的构造、编码或写入。剩余载荷主要为 java.type（1906322168 字节）和绑定/语法数据，尚未做丢弃或不兼容压缩。完整 raw/AST、14 域差分通过前不启动或登记三轮性能通过。


v7 完整发布第一次续验终态：1326.384 秒触发 `JAVA_SCRATCH_BUDGET bytes=17180609316 limit=17179869184`，未发布；进程树峰值 1717895168 字节，无源码/产物漂移，配置已恢复。该运行与原始模型 witness 并发，Java Native 799.359 秒不计入性能门禁。工作进程采样已进入 FactChunks 传输，证明问题发生在分析后的交付/暂存阶段。

代码复核确认原生导出的 3.67 GiB CAF1 已关闭文件句柄，Rust 已校验全部记录及归档 SHA 并生成自己的去重归档，原文件却保留到发布 Ack。新增明确的 owned 归档消费：仅在 Rust 验证成功、去重归档 seal 成功之后删除原生 CAF1；探针借用读取不删除，验证失败不删除并保留错误事实。此举释放重复临时占用，未放宽预算、未提前确认发布。对应 Rust 回归与 clippy 通过，产品及大项目续验仍须读取新的回执。证据：`target/java-migration-optimization/single-project-v7/receipt.json`、`worker-v7-sample.txt`，以及 `target/java-migration-optimization-worker-release-archive.log`。


v7 owned 回收归档后，16 GiB 原预算下完整任务成功发布（932.997 秒，partial），发布后查询成功，峰值 1767931904 字节，配置恢复且源码/产物无漂移。Java Native 398.983 秒，其中投影 137.946 秒、声明库存 64.547 秒。该运行与模型对照并发，不作为正式速度倍数。回执在 `target/java-migration-optimization/single-project-v7-owned/`。

原始模型差分另发现显示编号不能用作捕获身份：Java 8 Printer 的对象 hash 对 997 取模存在碰撞。完整图节点和边保留了区分能力；v8 只在显示拼写中按原字典节点贴标签，源字符串/注解值不变。图缓存因此删除不必要的显示编号状态，提高复用的确定性。具体字节码和两份逐字段数据在 `javac8-standard-printer-bytecode.txt`、`model-absolute-reference/` 和 `model-absolute-captured/`。原始模型 witness 独立处理同一显示问题，保留结构、图索引和原始证据；其正式全项目重验尚未完成。


2026-10-08 v10 发布校验优化：对 v8 daemon 的采样落在 `stage_archive_with_derivation` 的 SQLite 端点反连接。原查询逐行读取 facts 并对相同父/目标重复执行主键探测；现查询先在 `fact_parent` / `fact_target` 覆盖索引去重端点，再检查存在性。缺失端点仍返回具体 fact 并拒绝发布。12 万行、300 个重复端点的独立合成诊断为 0.188 → 0.102 秒；该数据仅用于确认查询计划，不证明冻结项目性能通过。

v8 冻结项目完整发布与查询成功，耗时 2596.659 秒，语义状态保持 partial；v10 完整发布正在执行。三轮独占冷缓存性能验收必须等待对应源码和产物的语义门禁通过，当前未执行，不将并发运行计时登记为性能结论。


2026-10-08 v11 worker 临时去重库续查（未合入）：

| 证据 | 结果 |
|---|---|
| 活跃 worker 短采样 | 主线程 480 个样本中，270 个落在 `FactDeduplicator::observe` 的 SQLite 插入；含 rollback journal `pwrite`。daemon 当时等待 worker，不是发布库插入热点；采样不能当作整段墙钟比例 |
| 当前边界 | 去重数据库仅由 `read_owned/load` 使用，任务私有；任何错误直接拒绝该任务，数据库自动清理，不承担发布或恢复 |
| 30 万条合成诊断 | 8 MiB / DELETE：插入 13.221 秒；64 MiB / DELETE：11.642 秒；64 MiB / OFF：3.017 秒。每组数据库 51,765,248 字节，完整 duplicate 查询与 integrity_check 通过 |
| 隔离实现 | 私有去重数据库 journal_mode=OFF、cache_size=-65536；原有 2048 条事务、事实冲突与首条 offset 证据保留。Storage / worker 回归通过，尚未合入正在验收的 v11 |
| 验收范围 | 仅候选瓶颈诊断，`formal_performance_accepted=false`。产品发布数据库仍使用 DELETE / FULL；冻结参考 JAR 不变 |

证据：`target/java-migration-implementation/worker-v11-sample.txt`、`daemon-v11-sample.txt`、`dedup-cache-diagnostic/receipt.json`、`dedup-stage-final-tests.log`。后续若合入，须重新冻结对应 Rust 产物并验证完整链路，不能把 v11 运行回执用于变更后的二进制。


v12 完整发布/查询通过，1340.346 秒（v11）→ 840.601 秒（v12），输入和运行产物无漂移，配置恢复，语义 partial 保留；并发诊断不能计入正式性能验收。`single-project-v12/receipt.json`。

v12 发布采样进入 Rust 派生关系：546 个阻塞线程样本中，277 个落在 `CandidateFacts::references` 的 SQLite 扫描。实际 SQL 的 `(? IS NULL OR parent=?)` 使用 fact_kind 索引遍历 reference，再临时排序；定向 SQL `parent=?` 使用 fact_parent。同一已发布数据库/参数返回 3 条，单次诊断为 6.586 秒与 0.000193 秒（缓存和并发条件不构成性能门禁）。隔离副本按 Some/None 分开 SQL，无新增索引、无谓词或排序语义改变，Storage/application 回归与全局/定向/空/缺失父节点、逐行取消测试通过。此查询优化尚未迁入 v12。

证据：`daemon-v12-publication-sample.txt`、`reference-query-plan-diagnostic.json`、`directed-read-stage-contract-tests-final.log`（均在 `target/java-migration-implementation/`）。
