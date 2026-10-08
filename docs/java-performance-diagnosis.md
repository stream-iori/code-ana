# iopengw Java 性能诊断

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
