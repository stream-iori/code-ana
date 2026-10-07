# Java 适配器与 worker

> 2026-09-29 实现状态：Java 8 真实 native/javac 已接入，范围见 [Java 实现说明](../java-implementation.md)，证据见 [验收记录](../implementation/java-validation.md)。本页更广的类型、框架与规模化设计仍为目标。

**Java 分析只采用 javac。编译器负责解析与绑定，独立 Java 模型承接提取和框架分析，最终交付公共 Fact IR。**

| 决策 | 状态 |
|---|---|
| D13：`lang-adaptor/`、Java 25/GraalVM 共享库、独立 Rust host、C ABI、禁止 JNI | 用户已确认；保留该边界 |
| D16：javac 唯一后端，无 JavaParser/SymbolSolver、无兼容 AST 或解析器回退 | 用户已确认，2026-09-29 |
| D17：原生库协调目标 JDK 编译器子进程 | Java 8 实现已通过实际共享库与目标 JVM fixture；更广 JDK/规模门禁仍待验证 |

参考实现固定为 Anatomist `dev/replace-parser@dff2e3954096853352ec91fcbfc6b995fbd4f390`，排除未提交修改。其 native 主程序启动目标 JDK JVM；这不证明 javac 可内嵌 GraalVM 共享库。已执行冻结参考的独立 Java/Lombok fixture 与 iopengw MAIN/TEST/XML（无效 UTF8 单文件按输入错误单列）；产品完整语义差分仍待验收。参考位置和证据见 [设计评审](../design-review.md)。

## 0. 工程与进程边界

```text
daemon
  │ worker IPC：任务 / baseline / Fact IR
  v
Rust worker host（独立进程）
  │ C ABI / FFI
  v
Java 25 / GraalVM 共享库（同进程）
  ├─ 独立 Java 模型、ASM 二进制分析、框架、增量、事实提取
  └─ 编译器协调器
       │ 编译器内部协议 9 / 独立模型 9（D17，Java 8 已实现）
       v
     目标 JDK JVM 子进程：javac parse → analyze
       └─ 禁用 annotation processor，无业务字节码生成
```

| 项目 | 要求 |
|---|---|
| 工程 | Java 相关代码均在 `lang-adaptor/`；建议按 `analysis-core`、`framework-*`、`native-bridge`、`compiler-bridge` 拆职责，不强制立即拆 Maven 子模块 |
| 原生库 | 锁定 GraalVM JDK 25、Native Build Tools、依赖；`--shared` 产物连同 C header、ABI 与构建 manifest 分发 |
| 编译器桥 | 与原生库分开构建、分发及版本化。候选以 Java 8 兼容字节码运行于目标 JDK 8/17/25；具体 API/字节码范围在 P0-04a 验证，不把 Java 25 字节码交给 JDK 8 |
| 目标 JDK | 完整且与目标版本匹配；路径从冻结工具链配置取得，不猜环境变量。版本支持以本项目实际验收能力清单为准 |
| 进程所有权 | host/原生库拥有编译器会话，supervisor 掌握所属子进程的启动身份和清理职责；host 崩溃不能留下无主编译器 |
| 原生可行性 | P0 必须实际通过共享库启动桥、接收模型、提取事实、取消及释放。Anatomist native executable、普通 JVM 测试和 Hello 均不替代该证据 |
| 失败路径 | D17 不成立则记录具体问题并调整 javac 执行设计；影响 D13 时形成具体方案评审。任何情况都不退回其他解析器 |

`parse/analyze` 仅做索引语义分析，不执行项目 Maven/Gradle 构建、处理器或业务代码，不生成业务 `.class`。编译器只读取封存的源码/依赖/平台输入，不通过隐式 source path 扩大范围。缺少必要输入时披露缺口或重规划；生成输入仍由外部 Agent/人类准备。

配置入口为 `languages.java`，Lombok 为 `languages.java.lombok`，模块可用 `modules.lombok` 覆盖。worker 只消费 daemon 冻结的 JavaAnalysisConfig，不重读 TOML。参数与工具链规则见 [configuration](../configuration.md)。

### FFI 与编译器协议

| 接口族 | 作用与生命周期 |
|---|---|
| abi_version / capabilities | 装载前先核对平台与成套 manifest，再通过稳定 bootstrap 验证 ABI、Fact IR、Java 模型及编译器能力 |
| session_create / input_chunk / seal_input | 分块导入控制输入/基线并冻结；输入指针只在调用内有效，需保留则复制到 adapter 所有内存 |
| analyze_start | 返回 opaque handle，分析在线程中运行；控制通道能继续取消与轮询 |
| poll_event / read_result_chunk | 分析冻结后读取有上限的结果块；编译器上下文完成不表示项目事实完成 |
| cancel | 可与分析并发，贯穿编译器交换、解码、提取；超时由 supervisor 终止所属进程树 |
| buffer_release / task_close / session_close | 谁分配谁释放；所有异常、取消、传输失败路径均回收 |
| publish_ack | 仅 daemon 发布成功后允许按该 revision 复用会话事实 |

| 边界 | 约束 |
|---|---|
| 数据 | 固定宽度数值、长度明确的字节缓冲、opaque handle、状态码；不跨 C ABI 传 Java 引用或 Rust 所有权对象 |
| 编码 | C ABI 可承载 worker protobuf；编译器协议独立版本化，不照搬参考项目自定义 wire 常量或版本号 |
| 线程 | 每个进入 C entry point 的 OS 线程 attach 到对应 isolate，thread handle 不跨线程使用；控制调用使用受限线程 |
| 异常 | Java 可恢复异常转状态/诊断，Rust panic 不跨 ABI；native 致命错误由进程边界隔离 |
| 原生任务 Error | OOME/StackOverflow/Error 使用预留失败响应；线程已退出但无终态时拒绝继续 running。Rust host 不接收成功结果或进入热池，supervisor 回收失败 worker 并保留旧版 |
| 退出 | 停止编译器工作并关闭管道/子进程，释放模型与缓冲，再 detach/销毁 isolate；卡死走有界终止 |
| 内部协议 | JDK/桥/模型版本、task/attempt/context、source hash、长度/序号/校验、诊断及终态；缺块、漂移、未知必需能力均拒绝 |
| 预算 | 单帧、总载荷、解码估算、排队结果和临时磁盘分别设限；stdout/stderr 日志不能污染协议 |

## 1. 输入、模型与事实

| 输入 | 输出 |
|---|---|
| 封存源码/资源、物理模块图、scope、有序 classpath、目标 JDK | 独立 Java 单元、类型、声明、绑定、源码位置和原始诊断 |
| Lombok/Spring/SOFA 配置、producer 版本 | 声明覆盖层、框架事实、能力缺口和来源链 |
| BaselineFacts、semantic inputs、publication sources、replacement owners | 完整 full/delta、依赖、coverage、provenance、完成清单 |
| 执行与语义预算、取消 | 分阶段状态、明确失败或受限证据；worker 不发布 revision |

| Java 模型内容 | 不变量 |
|---|---|
| 语法/声明 | parse 后保存原始源码形态，analyze 再补语义；原始语法位置与编译器生成声明分开；无源码范围不伪造坐标；不重建其他解析器 AST |
| 类型 | 声明类型、表达式类型、调用实例化类型分开；保留泛型边界、类型替换、SAM/覆盖依据 |
| 身份 | 源码显示名与二进制名分开，内部类型/节点序号不成为公共 key |
| 绑定 | 声明是否选中、局部证据是否完整、javac 确认/模型推断/源码恢复来源独立 |
| 注解 | SOURCE 属性写法、单值/数组形状与编译器常量/默认值分开；声明目录不丢 SOURCE retention 信息 |
| 坐标 | Java UTF-16 span 映射到公共 UTF-8 字节位置，基于同一捕获字节/hash；覆盖 Unicode 补充字符及换行变体 |
| 生命周期 | Tree/Element/TypeMirror、compiler/file manager 不进入独立模型、框架缓存或事实库 |

错误恢复只在有明确源码、继承、类型兼容及唯一性证据时产生有来源的候选；不得把 javac 错误恢复时给出的任意成员当确认绑定。局部类型不完整也不应机械否定可靠选中声明。恢复预算按独立调用查询拥有、递归共享，耗尽时保留未解析原因且不污染后续查询。公共编码见 [contracts](../contracts.md)。

## 2. 分析顺序与增量

```text
封存输入 + BuildContext + baseline
    -> 物理模块 × MAIN/TEST 编译计划
    -> javac parse/analyze（语义输入范围）
    -> 独立 Java 模型 + 原始诊断；释放编译器上下文
    -> Lombok 声明 overlay -> 声明契约 -> Java 提取器
    -> Spring/SOFA 模型分析
    -> 完整逻辑事实视图（baseline - replacement owners + 新事实）
    -> 共享 XML inventory -> Spring resource -> SOFA resource
    -> 依赖传播/重算直到稳定 -> 冻结 -> 分块交付
```

| 范围 | 规则 |
|---|---|
| semantic inputs | 足够的当前模块/依赖源码、目标 JDK、有序 classpath；可能远大于改动集，但必须已捕获 |
| publication sources | 本次提取并发布新事实的源码；不能因文件被 javac 读取就越界发布 |
| replacement owners | 包括删除、失败、资源、producer 的完整替换单元；空输出仍能清理旧事实 |
| 模块与 scope | 物理 Maven 模块决定编译上下文，配置标签决定发布身份；MAIN 不读取 TEST，TEST 可读取本模块 MAIN |
| 冷基线 | BaselineFacts 供逻辑视图/依赖传播使用，本身不等于 javac classpath；P0/P1 先使用足够封存源码上下文 |
| 闭环 | 框架/模型影响绑定时由 Java 显式恢复或重算并保留来源，不能要求 Rust 在 dump 后补名称解析 |

Lombok `mode=model` 仅补声明模型，不执行处理器、不合成可执行方法体；modeled/partial/unmodeled 与 strict 门禁独立。关闭或失败不能留下部分 overlay 或旧 producer 事实。声明完整与方法体建模完整也分别披露；签名足够不代表方法体能力已实现。

## 3. 复用边界

| 固定参考代码 | 处理 |
|---|---|
| `core/java/` | 复用独立模型与生命周期思路；映射本项目身份、owner/world、UTF-8 位置和 facets |
| `core/javac/`、`src/worker/java/` | 复用编译器调用、上下文、错误分类和模型编码思路；输入改为封存路径，协议/进程管理按本项目重做 |
| `extract/JavaModelExtractor` 与 `Java*Facts` | 按依赖审计拆出提取，写内存 fact sink；审计恢复规则与证据，不直接搬数据库结果 |
| `JavaModelExtension`、`JavaModelAnalyzer`、Spring/SOFA 模块 | 消费独立模型与完整逻辑视图；保留构建期装配与依赖顺序 |
| `LombokJavaModelExtension` | 借鉴声明 overlay 和来源披露；不移植处理器执行开关 |
| `incremental/`、`StagedGraphStore` | 拆出语言契约/失效/绑定；通用校验进 Rust，SQL 按新存储端口实现 |
| `JavacMavenInputs` | 仅参考有效模型数据契约；其 Maven 启动扩展路径不进入本项目只读发现 |
| CLI/application/query/JDBC | 不作为 worker 产品依赖；不整包引入 anatomist-core |

借用文件记录 commit、hash、传递依赖、资源、许可证及修改说明。不维护 JavaParser/SymbolSolver 依赖、兼容 AST、双后端比较或历史解析器回退。

## 4. 内存、缓存与调度

| 对象 | 生命周期 / 限制 |
|---|---|
| javac compiler/task/file manager/Tree/Element | 一个编译上下文；子进程可复用但上下文不跨任务泄漏 |
| 独立 Java 模型 | 上下文目录保存位置，按文件读取声明/语法/绑定；调用恢复与反射状态按文件释放。类型字典验证后共享不可变图，原packed副本不驻留；归档租约按上下文回收 |
| 类型/Bean/依赖与基线 | 本轮完整逻辑视图；计入冷增量峰值和复制成本 |
| 最终事实 | 冻结到完整交付；分块、有背压，不积累多份整图序列化副本 |
| 编码结果/语法暂存 | D17 原型评估是否需要有界临时文件；若引入须定槽位、总字节、清理与磁盘满行为，不能默认无限 spill |
| 解码 | 跨帧共享分配估算预算，字符串/集合/树/类型/声明/诊断分别计量；估算不是精确堆硬限 |
| 会话事实 | 收到 PublishAck 且 revision/profile 匹配才可复用；与可重建编译器缓存分开 |

```text
同时刻进程树 RSS = Rust host + Java 原生库（同一个进程）
                 + 目标 JDK 编译器子进程
全局预算还包括 daemon、SQLite、其他 worker；临时磁盘另计。
```

P0/P1 单个编译器子进程、串行上下文先验收。P2 可评估固定就绪批次、有界并行、按稳定序号消费；输入顺序/线程数不能改变语义。缓存/调度优化先验证等价再测收益，默认开启另需门禁；参考实现默认仍为单线程。

P2 可选 NoCode 模块签名 + 独立声明目录减少重复分析上游源码。缓存不是项目可运行产物，不证明项目已编译；必须保存源码身份、注解、泛型和生成来源。键覆盖源内容、有序依赖、目标 JDK、桥/模型/规则版本、Lombok 配置；不完整/冲突/缺失时有界回退封存源码。在途引用 pin，不能通过 LRU 驱逐假装释放仍被持有的对象。

当前文件级方法体编辑优化支持已验证的泛型、注解默认值、枚举常量、成员内部类和静态嵌套类。NoCode classfile 只供 javac 消费；验证过的源码目录恢复声明证据与 SOURCE 信息。缓存校验摘要、目录和无 Code 属性，文件单项 8 MiB；文件/模块共享 256 MiB 磁盘 LRU 与任务私有副本预算，超限回退 SOURCE。缺失/损坏/不完整签名、声明或外部引用变化时回退源码；可选缓存写入失败不丢失已完成的源码分析。模块归档、双向 LRU/在途副本和并行已接入，专项 JVM 验证通过；外围 TYPE_USE 路径及新完整生产/容量仍待验收。目标 JVM 在一个任务内复用，编译器上下文逐次释放；跨任务目前只复用 Rust host/native isolate。

Maven `selected=false` 的模块保留捕获身份和依赖源码，但不作为独立 javac 分析根；导出器不保证为它们解析 classpath。Rust 为所选 MAIN/TEST 上下文按该 scope 的已调解 `dependencies` 读取必要 MAIN 源码，TEST 同时保留本模块 MAIN 输入。依赖源码变化仍进入消费者 digest 和增量失效；不能因未选择的无关模块缺依赖而阻断发布。

## 5. 故障与验收

| 用例 | 预期 |
|---|---|
| 实际共享库 → 目标 JDK → 模型 → Fact IR | P0-04a/04b 分别给执行可行性与完整事实证据；仅 Hello/JVM 测试不算通过 |
| 缺 JDK/错版本/缺编译器/不支持模块图 | 分析前明确拒绝，无解析器回退；已有历史仍可查询 |
| 源码错误、缺依赖、歧义/不可访问调用 | 语法/可靠事实与诊断可解释；候选不冒充确认目标，门禁独立判断 |
| 单文件失败、producer 关闭/XML 删除 | 清理相应旧 owner，coverage 覆盖零事实文件 |
| 泛型/SAM/lambda/方法引用/嵌套类/注解/Unicode | 独立预期验证身份、绑定、来源与源码范围 |
| full/冷 delta/可选热复用或签名缓存 | 同输入 profile 的事实多重集、诊断、coverage、provenance 等价 |
| 取消/native 崩溃/javac OOM/协议截断/解码超限 | 本次不发布；daemon 保旧版，所属编译器与临时资源可回收 |
| 多轮同名类型与上下文释放 | 不跨项目/world 串数据，无编译器对象滞留；RSS 与释放证据结合 |
| ABI/模型/编译器协议不匹配 | 不兼容调用或分析开始前拒绝，不静默接受未知必需能力 |

具体 fixture 与阶段映射见 [delivery](../delivery.md)、[P0](../../todo/01-p0.md)、[验收计划](../../todo/05-acceptance.md)。

## 当前构建期扩展装配

```text
封存 context / javac 独立模型
  -> JavaModelExtension   -> 校验并提交声明 overlay
  -> Java core 提取       -> 暂存事实视图
  -> JavaModelAnalyzer    -> 验证 producer/归属/schema/图后提交
  -> CallSiteEvidence     -> 只补既有调用点的证据 facet
  -> ResourceProvider     -> 筛选封存 inventory
  -> ResourceAnalyzer     -> 从资源与完整暂存视图提取事实

回调失败 -> 丢弃该回调输出 + diagnostic/partial coverage
取消 / strict / Error -> 终止任务，旧发布版本不变
```

接口与执行器在 `lang-adaptor/src/main/java/dev/codeana/analysis/extension/`；`BuiltinExtensions.REGISTRY` 显式装配，不扫描目标 classpath 或热加载业务 JAR。扩展版本、依赖、producer、facet 与有效配置形成指纹，记录在 context provenance，进入增量与 NoCode key。

`JavaUnitModelExtension`用于文件级Lombok候选校验与最后统一提交。`JavaUnitAnalyzer`提供冻结上下文查询和文件body视图；一个文件的输出通过producer/schema/归属校验后提交，异常时丢弃该文件输出并继续其他文件；不能写入其他文件事实。规则仍输出本项目Fact IR，Rust继续负责存储与发布。最新实现与未完成的真实项目验收见[重写记录](../implementation/java-adaptor-rewrite.md)。

当前 Spring MVC、生命周期/事件、SOFA 注解已使用独立 analyzer；静态注册是 possible，不能生成 confirmed 调用。NoCode 缩减会保留未变 owner 的事实供查找，并重跑变更源码的扩展以更新位置。当前视图按 compiler context；跨 context 的项目全局视图、单声明故障隔离与完整框架规则仍在实施。

Java发布确认等待最多30秒，包括native释放和Rust缓存原子提交；确认失败披露ack_warning且不进入热池，已发布head保持。该故障期限不是项目性能目标。元注解公开图、原始注解/MVC/事件/SOFA属性、生成覆盖跨度和当前真实项目失败/修复回执见[执行记录](../implementation/java-alignment-execution.md)。
