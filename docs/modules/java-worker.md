# Java 适配器与 worker

对齐 2026-10-14。默认 Native/G1，javac 唯一后端，目标 Java 8；版本见 [当前实现](../current-state.md)，接口见 [公共契约](../contracts.md)。

```text
daemon ── worker protocol 21 ──► Rust Java worker（独立进程）
                                      │ C ABI 4 / FFI
                                      ▼
                               GraalVM Java 25 / G1
                                 ├─ 独立 Java 模型 / 框架 / 失效
                                 ├─ GRA1 protobuf Row 归档
                                 └─ batch 2 ──► Java 8 javac 子进程
                                                javac-model-v50
```

显式 JVM 后端供开发/诊断，使用同一模型和行交付；无自动 fallback。Java 8 桥字节码与 Java 25 共享库分开构建，禁止 JNI、JavaParser/SymbolSolver、注解处理器和业务字节码生成。

FFI 整数状态在边界解码为 `RunState`。Java engine request、bundle manifest、baseline 和 graph descriptor 使用明确 DTO；JVM 重定位只修改已知路径字段，外部输入保持原路径。Maven export 直接构造 `JavaInputs`，计时使用 `PhaseTiming`。sealed descriptor 的诊断副本读取当前 `graph_archive` / GRA1 并复制为 `graph.rows`。

## 1. 输入与模型

| 对象 | 边界 |
|---|---|
| 捕获输入 | daemon 冻结源码/资源/依赖、模块/scope、工具链和有效配置；worker 不读活工作区或重新加载 TOML |
| 编译计划 | 物理模块 × MAIN/TEST；MAIN 不读 TEST，TEST 可读本模块 MAIN；标签只改变发布 module 身份 |
| semantic inputs | 可以大于 publication sources；读取依赖源码不能越界发布 |
| javac 模型 | parse/analyze 后独立模型保存声明、类型、绑定、语法和诊断；Tree/Element/TypeMirror 不外泄 |
| 编码归档 | 分文件模型与共享类型字典，记录/字节/解码估算分别受限；按上下文释放 |
| 绑定与来源 | 确认绑定、局部证据完整和 javac/model/source 恢复来源分开；possible 不冒充 confirmed |
| 坐标 | 基于同一捕获字节/hash，将 Java UTF-16 映射到公共 UTF-8 位置；生成节点不伪造源码范围 |
| 主版本 | 目标 Java 8 当前支持；Java 17/25、混合版本/不支持的模块图明确拒绝 |

Lombok `mode=model` 只补声明 overlay，不执行处理器或合成可运行方法体。Spring/SOFA 消费独立模型和冻结事实视图；静态装配关系是 possible。SOFA 静态 XML 可独立于 Spring，关闭 Spring 不会自动开启它。

## 2. 增量与缓存

```text
捕获输入 + 固定 baseline
 → 比较声明契约 / 绑定 / 失败查找 / 资源依赖
 → 受影响源码提取与 producer 输出
 → baseline - replacement owners + 新事实
 → 资源视图 / 依赖传播稳定
 → 封存 GRA1 → 完整校验与 End → daemon 发布 → PublishAck
```

删除文件、资源或停用 producer 必须清旧归属，包括零输出文件。baseline 是逻辑事实，不等于 javac 类型输入；只有验证过的 NoCode 签名能替代相应依赖源码。

| 缓存 / 复用 | 当前实现 |
|---|---|
| NoCode | 文件/模块签名与独立声明目录；校验无 Code 属性、内容 SHA、版本/依赖/规则身份 |
| 持久管理 | Rust 存储持有目录、锁、256 MiB 共享配额/LRU；任务校验并复制，PublishAck 后提交 |
| 回退 | 缺失/损坏/不完整或声明/外部引用变化时，回退捕获源码；缓存写失败不丢已完成源码结果 |
| 在途 | pin/任务副本不能被淘汰冒充释放；缓存不是目标项目构建产物 |
| 元注解 | AnnotationMetaCache 上下文内复用直接父定义，最多 1,024 项 / 1 MiB；不缓存 AST/截断闭包 |
| javac 进程 | 一个任务内复用，编译上下文逐次释放；跨任务复用健康 host/isolate |
| 并行 | 配置允许 1–8，受总进程树预算预约限制；显式并行不改变输入顺序和语义 |

Maven `selected=false` 模块只保留输入/依赖身份，不作为独立分析根；所选 MAIN/TEST 按依赖读取必要 MAIN 源码，无关未选模块不能因缺 classpath 阻断任务。索引读取外部 inputs_manifest，不启动 Maven；独立导出命令的外部准备见 [配置](../configuration.md)。

## 3. 行交付与内存

| 边界 | 当前行为 |
|---|---|
| 产物 | GRA1 + typed protobuf Row，analysis request/result 11；不生成完整项目 Fact JSON / CAF1 |
| worker | 流式读取/校验原归档，直接交付有界 rows；不运行 Fact JSON 转换或事实去重数据库 |
| 发布 | Begin/Chunk provisional；完整长度、EOF、SHA、行预算通过才发 End；daemon 再校验并发布 |
| 编码 | 固定逻辑列与 NULL/text/int64/bytes，metadata JSON 保留原始文本；减少重复投影/编码与拷贝 |
| RSS | Rust host/原生库同进程计一次，加同时刻所有 javac 子进程；-Xmx 不是 RSS 上限 |
| heap | native 默认 256 MiB，8–2048 MiB 且按 MiB 对齐；javac 默认 128 MiB，8–512 MiB |
| 解码与磁盘 | java_model_decode_bytes 默认 512 MiB；模型归档、结果和 scratch 分别有界 |

## 4. G1 与异步完成通知

```text
worker 进程 A：create isolate（仅一次） → task 1 → task 2 → ...
                   健康结束：复用 isolate
                   取消/致命失败：关闭引擎并退出进程
supervisor：启动 worker 进程 B → 创建新的 isolate
```

本机 Oracle GraalVM G1 共享库不能在同进程创建第二个 isolate，首次 teardown 后也不行。Rust 根据已校验 manifest 记录进程级历史；二次创建在进入 Graal 前返回 `G1_ISOLATE_PROCESS_RESTART_REQUIRED`。Serial 可显式构建，生命周期测试按 manifest 区分：G1 的独立 ABI 用例使用独立进程，同 isolate 多任务与线程 attach/detach 仍验证。

isolate 由固定 OS 线程拥有，Tokio 通过有界命令队列操作。任务 start 前注册非阻塞 Unix socket，库复制 CLOEXEC FD；finally 先发布终态，再写 `0x01` 并关闭。Rust 异步等待通知，再读取一次终态。控制帧持久 FrameReader 保存半帧进度，取消优先；取消与退出共用 2 秒期限，超时回收所属进程组。异常/无终态/通知损坏实例不复用。

## 5. 构建、安装与验证

| 入口 | 含义 |
|---|---|
| ./scripts/build-java-native.sh --build-only | 完整 Maven/Java 8 桥/native 构建，默认 G1；manifest 标记开发构建，不签正式验收 |
| ./scripts/build-java-native.sh | 要求有效 CODEANA_JVM_ACCEPTANCE_RECEIPT 与源码/证据 SHA；不能拿旧版本回执放行 |
| python3 scripts/install-java-native.py | 验证 bundle/worker SHA 与协议，拒绝活跃进程替换；归档旧 SDK，原子安装到 target/lib/java |
| just test-java-native | 构建、安装、manifest、生命周期、当前 GRA1 图回归、真实崩溃 |
| just test-java-native-product EVIDENCE | 当前图门禁，回执 group=graph；不签正式迁移/容量验收 |
| just test-java-release | 开发构建 + release + 包/图/错误 JDK/自带 fixture；保留原路径并恢复开发字节 |

执行文件沿用原 Cargo 路径。`tests/java_release.py --bin-root "$PWD/target/debug" --stage-release` 先检查无活跃进程，归档并原子安装 release 字节，运行后恢复；包与执行路径 SDK/二进制必须逐字节一致。

最新正确性、单轮 iopengw 和未完成验收见 [当前实现](../current-state.md)。过期 raw transport 测试及入口已删除，完整来源覆盖与正式放行仍需新的当前版本证据。

Maven 源码模式通过 worker 22 / analysis request 11 传入；`CapturedProject` 将外部依赖模式与已捕获的完整模块拓扑分开。必要的未选中模块可提供 MAIN 签名/源码，不独立发布。失败模式为参与上下文输出 `CLASSPATH_UNAVAILABLE`，质量证据跨增量和复用保留；主动源码模式不伪造依赖失败。

## 任务内协作与类型边界（2026-10-16）

Java 桥在 JSON 入口构造 AnalysisRequest、ContextOptions、BaselineDescriptor 和 AnalysisResult；紧凑 source/classpath 索引保留按需共享描述，不展开复制所有上下文。AnalysisSession 保留现有入口及资源所有权，按准备、Java 提取、资源提取和封存阶段执行。coverage 只从 diagnostics 汇总，旧空 coverage/context 调用已删除。

JavacRunConfiguration 一次冻结捕获运行配置；旧诊断/fixture setters 保留，但活动分析期间拒绝修改。JavacWorkerPool 拥有编译器 lane 与执行器，JavacModelConsumer 负责解码预算、模型校验及消费。调用恢复按类型运算、候选、推导和选择拆为任务内协作类；Lombok 按 Builder、声明规则、生成成员拆分，共享 Overlay 所有者。预算、缓存、递归保护、规则顺序和模型/行语义保持。

既有类名、源码入口、Rust 执行路径和 SDK 路径保留；新增内部类由 Maven 默认源码收集和 Native reachable closure 打包。来源清单为提取类记录 extracted_from，不改写原始上游 commit/SHA。

## JSON / XML 资源边界

宿主 JSON 使用 Jackson Core 2.21.5 流式解析/生成；保留显式 `JsonCodec`，不使用自动反射对象映射。输出活动路径防环，跨分支共享对象合法；输出展开上限 256，读取默认深度 512，拒绝非有限数字。canonical 在输出当前对象时排序键，避免递归复制完整排序树。

Spring XML 继续使用 JDK SAX；每文件解析与提取共享 `XmlResourceBudget`，深度 256、每阶段节点 100,000、累计载荷 64 MiB。文本按 UTF-8 字节计费，路径在拼接前保留其组成部分与有界后缀额度；平台更严格的限制继续生效。提取使用显式栈，保留原顺序和路径身份。资源异常穿透扩展、JSON 元数据及签名缓存恢复边界，普通坏 XML 仍按原行为恢复。Native 显式注册 Xerces XMLMessages 资源包；缺包属于致命基础设施失败，不得转成部分语义事实。候选发布仍由完整 worker End、校验和 PublicationFence 控制。

开发构建可显式设置 `CODEANA_BUILD_JAVA_HOME` 与 `CODEANA_JAVA8_HOME`；不设置时仍使用 `.sdkmanrc` 和固定 Java 8 路径，没有自动回退。替代工具链、Serial 和 `--build-only` 均不代表固定 G1 工具链正式验收。

## 统一任务预算

| 边界 | 当前行为 |
|---|---|
| 身份 | anatomist-4a903272-codeana-graph4-resource2-analysis；worker22、request/result11；旧bundle/请求/结果拒绝 |
| 传递 | 显式 AnalysisContext，不使用全局或ThreadLocal账本；普通、混合、prepared实例下发静态grant |
| 同账户 | Rust请求编码后发送累计水位；Java接续计费并回传；Rust拒绝错身份/超额/倒退，再为归档解码、重编码及IPC计费 |
| 计费入口 | 捕获副本/上下文、模型解码、语法与声明索引、自有事实保留、扩展批次、语义JSON、XML解析/提取和归档输出；签名缓存冷暖读取、哈希缓冲、源码校验及行坐标数组也传递任务上下文 |
| 局部作用域 | XML每文件/解析/发射、javac解码和catalogue局部额度与根账本一起原子检查；拒绝不先扣任一维度，失败对所有兄弟作用域粘性生效；释放不退款 |
| 平台深度 | JDK SAX更严格时保留该限制，并把拒绝写入统一失败回执；当前固定工具链为深度100，未提高平台额度 |
| 丢失回执 | 可发送Failure但FFI用量未知时回传usage_known=false；进程失联保留最后核实水位并标Unknown；不以0或已知水位证明完整消耗 |
| 当前验收 | codec/签名/ASM/Lombok/元数据与混合/分发已完成本机fixture验收；正式范围见当前实现 |

资源失败维持HardResourceLimit致命异常，不能被扩展恢复边界转成可发布的部分图。健康G1 worker在发布确认后可服务新任务；失败仍由supervisor换进程，不在原进程重建isolate。普通坏XML继续原恢复行为。实际回归范围及原失败见当前实现文档和target/unified-analysis-resources/java-context/receipt.json。

Hello先签发grant并回传首个水位，再准入Discover业务帧，配置校验消费同一任务账本；BuildContext只在wire携带回执，不改变语义profile。未变复用用Reuse/Reused回执结束账户后退出。Native最终回执丢失仍标Unknown，保留已核实Discover水位。

业务 IPC 共用 contracts 分配前准入：小型 Hello 初始化账本，随后 Discover/Analyze 在分配/解码前检查额度；有界堆控制通道支持超限后的失败回执，取消保留半帧进度并流式丢弃业务字节。实现与证据见 contracts.md 和当前 frame-admission 回执；语言内部投影/完整分发仍按各自未关闭项验收。

声明指纹、声明压缩、源签名投影和ASM签名写出已接入累计账本；传输JSON改为共用Jackson，普通canonical字节和精确数字有冻结兼容向量。Rust预留JSON解码节点，Java先预留结果编码再封账回执。SOFA XML读取/SAX/元数据与注册计入原任务；源码切片/位置查找与coverage输出也累计计费。该轮Java fixture为688项，具体产品回执见java-signature-codecs；Lombok overlay/freeze已在后续701项及产品门禁中关闭，见java-extensions回执。

Lombok声明overlay显式接收原任务账本；配置捕获/合并、生成声明/注解、类型索引、调用证据与JavaExtensionData freeze均在副本构造前检查。常量/注解辅助context不再另领默认账本，硬失败穿透扩展恢复。类型名从活动表读取，保留canonical嵌套名及原方法ID。合并JAR在Native Image前核对reactor全部类；重复package强制重建薄JAR，避免旧依赖混入。

本轮统一资源治理八项已完成本机自建fixture验收，状态与历史阶段区分见current-state.md最后一节。三种分发及所有实际release worker的42项资源/混合/历史矩阵已通过；原二进制与SDK恢复记录齐全。此为本机fixture验证，不签完整语义/来源、跨平台或容量/稳定性能放行。
