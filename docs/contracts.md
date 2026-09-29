# 公共契约：事实、身份与协议

> 2026-09-29 实现状态：Rust 产品链路与独立 mock 已落地；具体命令、预算和首版限制见 [Rust 实现说明](rust-first.md)。本页未验收的 Java/规模化设计仍保留为目标。

**先冻结“一个事实属于谁、对应哪个输入、怎样证明交付完成”，再实现传输和表结构。**

状态：事实组和身份分层继承原提案；CLI-daemon 已采用 gRPC/HTTP/2 + protobuf，握手协议 1；worker 协议 2，Fact IR 1。消息定义在 `proto/codeana/v1/task.proto` 与 `worker.proto`；真实 Java 模型/编译器桥契约仍待其专属 P0 验证。关联 D02/D05/D06/D07/D11/D13。

## 1. 身份

| 身份 | 建议组成 / 语义 | 不保证什么 |
|---|---|---|
| RepositoryKey | 仓库身份；识别 linked worktree 的共同仓库 | 不能只取目录名 |
| ProjectKey | 仓库 + 项目相对根，非 Git 项目单独登记 | 不将同仓库不同子项目合并 |
| CheckoutKey | 一个工作区实例 | 历史提交快照不因 checkout 不同就必然重复 |
| IndexTarget | project + checkout/版本选择 + 请求配置槽位 | 不同 MAIN/TEST 请求不能竞争同一入口 |
| LogicalSymbolKey | language + 稳定模块身份 + 声明路径 + 语言签名 | 重命名、局部匿名实体跨修改匹配未必可靠 |
| WorldKey | 模块/源码集或 crate/target/features/cfg 的语义配置实例 | 不代表依赖内容未变 |
| SymbolKey | LogicalSymbolKey + WorldKey，公共编码带版本 | 不等于数据库 rowid |
| OccurrenceKey | world + source unit + occurrence kind + 规范化位置 + 区分序号 | 行号移动后可变化；diff 不能只按它判断语义变化 |
| FactKey | producer + fact kind + owner + 内容规范化摘要 + 必要重复序号 | 不用 hash 去掉有意义的重复事实 |
| SemanticProfile | adapter、工具链、Java 模型/编译器桥（适用时）、规则、已验证构建输入、按模块/world 规范化语义配置与依赖摘要 | 不等于整份 config 文件 hash；建议/报告/输出开关不机械改变语言事实 |
| SourceSnapshotId | 捕获清单及字节的身份 | 相同源码可产生不同语义图 |
| RevisionId | 一个已发布事实集合，关联 source snapshot 和 profile | 不是 Git commit；同 commit 可有多种 profile |
| SnapshotId | 历史入口身份，指向文件捕获，ANALYSIS 另指向 revision | 多个 snapshot 可以共享图字节 |
| StorageRecordId | 库内实现键 | 不进入公共 selector、跨版本匹配或分页契约 |

公开 key 的编码、hash 算法、路径大小写/分隔符、Unicode 和位置坐标规范须用跨平台夹具冻结。位置建议以捕获 UTF-8 字节范围为内部依据，另提供公开行范围；编码转换与原始文件 hash 分开记录，不能混用 Java UTF-16 偏移和 Rust 字节偏移。

## 2. Fact IR

| 类型 | 最少语义字段 | 约束 |
|---|---|---|
| SourceUnit | source snapshot、相对路径、内容 hash、language、world、scope | 缺失输入有明确原因，不伪造空文件 |
| Declaration | symbol、logical key、kind、parent、source owner、range、signature、origin | 合成声明可无直接源码，但必须有来源链 |
| ExternalSymbol | symbol、artifact identity、kind、signature、availability | 无源码不表示无方法体或无行为 |
| ReferenceOccurrence | owner、range、reference kind、目标/未解析线索、status | 保留每次出现，聚合在查询层完成 |
| CallSite | owner callable、occurrence、mechanism、range、resolution status | 可无目标；仍保留调用现场 |
| CallTarget | call site、target、binding kind、依据 | 源码绑定与静态可能候选区分 |
| StructuralRelation | kind、from、to、producer、依据 | contains、extends、implements、overrides 等有类型 |
| Dependency | consumer owner、dependency kind、目标/查找桶、依据 | 包含失败查找，不能只保存成功绑定 |
| Diagnostic | code、severity、owner、producer、数量/样例、原因 | 样例截断不改变事件总数 |
| Coverage | world/scope/owner、能力、状态、限制原因 | 必须能覆盖“没有任何事实”的文件和失败 producer |
| Provenance | producer/version、输入摘要、规则、直接依据或来源链 | 来源链有界，不能只写一段无法验证的说明 |
| Facet | namespace、schema version、所属实体、类型化字段 | Java 注解、Bean、Rust trait/cfg 等各自版本化 |

```text
SourceUnit -> Declaration(owner) -> CallSite
                                      |
                          +-----------+-------------+
                          |                         |
                    0..N CallTarget           resolution/coverage
                          |
                      Declaration / ExternalSymbol

calls(A,B) = 对已选择调用点目标投影并去重
call-sites = 返回每次出现
dispatch = 基于已发布事实计算可能目标，并附依据和预算限制
```

构建时已经产生的候选也必须标记为 possible；它们不能混入默认 resolved 调用关系。缓存或物化 `calls` 只作为可重建索引，不能形成第二套独立事实。

### Java 模型与绑定证据

```text
javac Tree / Element / TypeMirror（仅编译器上下文内）
    -> 独立 Java 模型（Java 专属、版本化）
    -> 声明 overlay / Java 提取 / 框架分析
    -> 公共 Fact IR + Java facets（可持久化、Rust 查询可消费）
```

| 契约 | 必须保存 / 校验 |
|---|---|
| Java 类型 | 声明类型、表达式类型、调用实例化类型分别保存；未知不能拿声明类型冒充实例化类型；类型引用受所属模型约束 |
| Java 身份与源码 | 源码名和 JVM 二进制名分开；保留泛型、SAM/覆盖依据、原始注解写法与编译器常量/默认值的区别 |
| 绑定维度 | `declaration_resolved`、`evidence_complete`、`binding_origin` 独立；字段名称待 P0 冻结 |
| 已确认目标 | 局部参数/结果类型有缺口仍可能有可靠选中声明；记录缺口，不一律丢掉目标 |
| 未确认目标 | 歧义、不可访问、不适用或仅模型恢复均不能冒充 javac 确认；候选不进入默认 resolved 投影 |
| 恢复 | 源码/模型恢复必须有唯一性、类型与继承证据；预算按独立调用查询拥有，递归共享，耗尽保留 unresolved 原因 |
| 位置 | 内部 Java 模型可保存 UTF-16 span；转公共 IR 时映射到捕获 UTF-8 字节。补充平面字符、CRLF、源码 hash 必须对照；生成节点无位置时留空，不伪造 L0C0 |
| 诊断 | 原始 javac code/severity/phase、source/world/module、span、消息及重复次数；无位置诊断归上下文。语义诊断与协议/进程失败分开 |

上述字段在 Java 模型或版本化 facet 中表达，不能强迫其他语言接受 Java 类型系统。公共 CallTarget 的解析状态与 Java 绑定依据必须一致。独立 Java 模型不是公共 Fact IR，也不是可执行构建产物；不让编译器对象或内部数组 ID 泄漏成公共身份。

## 3. 事实所有权与替换

| Owner 维度 | 用法 |
|---|---|
| source owner | 一个源文件在一个 world 下生成的事实 |
| resource owner | XML/config 等资源与相关 world 的事实 |
| producer owner | Java core、Lombok、Spring、SOFA 等生成者 |
| aggregate owner | 确实跨文件生成的结果，必须显式列出依赖输入与替换单元 |

建议 `OwnerKey = (world, producer, owner_kind, owner_id)`。声明唯一拥有者由协议明确；其他 producer 用独立关系或 facet 补充，不能覆盖同一声明的归属。

```text
delta = {
  baseline_revision, input_snapshot, profile,
  replacement_owners, deleted_source_units,
  declarations, occurrences, relations, dependencies,
  diagnostics, coverage, provenance
}

candidate = baseline - 全部 replacement_owners 的旧事实 + delta 新事实
```

规则：空替换集与“某 owner 被替换为空”不同。文件删除、解析失败、插件关闭都要表达旧事实清理；新 coverage 说明原因。依赖其他 owner 的入边要通过失效闭包重算或转为未解析事实，不能靠外键级联悄悄删掉证据。关闭 producer 的 profile 变化若不能证明局部更新安全，退全量。

## 4. worker 协议

本节定义 daemon 与 worker 的逻辑消息。Java 场景中的 worker 是独立 Rust host，host 再通过进程内 C ABI 调用 `lang-adaptor/` 构建的 GraalVM Java 共享库；FFI 不跨进程。ABI 的线程、内存和错误约束见 [Java 适配器](modules/java-worker.md)。

建议 worker IPC 使用有长度前缀的 protobuf 消息，复用 `.proto` 的 adapter/Fact IR 定义，与 CLI 的 gRPC 服务分别版本化；Java FFI 的字节缓冲可承载同一消息编码。HTTP/2 + protobuf 的用户决策针对 CLI-daemon，是否统一 worker RPC 承载需另行验证，不自动增加 Java 内部网络服务。

```text
daemon                                  worker
  | ---- Hello(versions, capabilities) ----> |
  | <--- Compatible / Unsupported -------- |
  | ---- Discover(captured workspace) ----> |
  | <--- BuildContext / required inputs --- |
  |       捕获并冻结补充输入                |
  | ---- Analyze(attempt, plan, baseline) -> |
  | <--- Progress / Diagnostic ------------ |
  | <--- ReplanNeeded --------------------- | 终止该 attempt，扩大后重跑
  |                 或                     |
  | <--- ResultBegin(full/delta, identity) - |
  | <--- ResultChunk(seq, section, data) --- | 分析冻结后才开始交付
  | <--- ResultEnd(counts, checksums) ------- |
  | 校验 / 持久化 / 发布                    |
  | ---- PublishAck(revision) ------------> | 才能复用会话状态
```

| 消息 / 对象 | 必须携带 |
|---|---|
| Hello | worker identity、adapter protocol、Fact IR、facet/capability versions、执行平台兼容信息 |
| AnalyzeRequest | task/attempt、不可变输入清单、BuildContext、冻结的语言/module/world 配置与摘要、profile、full/delta、baseline revision、replacement plan、预算；Java 另含 compiler plan（见下表） |
| BaselineFacts | 固定身份、符号/类型/Bean 摘要、依赖与查找桶、所需未变事实、coverage、provenance |
| ResultBegin | 期望基线、实际输入摘要、最终替换范围、profile、模式 |
| ResultChunk | 连续序号、事实段类型、条数、长度；每帧有上限 |
| ResultEnd | 所有段条数、内容校验、最终诊断/coverage 总结、完整交付标记 |
| Cancel / terminal | task/attempt、是否已经冻结/发布、失败原因、可重试性质 |
| BuildAdvice | 缺失输入、project/module/world、基于哪个捕获输入提出建议、cwd、argv、工具链要求、目的、预期产物、可能影响、重试操作；仅建议，产品不执行 |

Java compiler plan 明确区分三种范围：

| 范围 | 含义 |
|---|---|
| semantic inputs | javac 可见的当前模块/前置模块源码、有序 classpath、JDK、声明签名；必须全部来自封存输入 |
| publication sources | 本次需要产生新事实的源码，可小于 semantic inputs；读取某文件不自动授予发布其事实的权限 |
| replacement owners | delta 清理与替换的最终 owner 集；还包括删除、资源、失败 producer，不能由非空输出反推 |

计划保存物理构建模块与发布模块/world 的映射；MAIN 不消费 TEST，TEST 可消费本模块 MAIN。BaselineFacts 不能直接充当 javac classpath；需完整封存源码或经验证的签名投影，输入不足按 ReplanNeeded 处理。

D17 若采用目标 JDK 子进程，增加独立版本化的编译器内部协议：目标 JDK/桥版本、source hash、task/attempt/context 身份、帧序号/校验、诊断和完成标记。其完成只表示一个编译器上下文结束，不替代对 daemon 的 ResultEnd 或 PublishAck。C ABI、worker IPC、编译器协议分别协商，未知必需能力明确拒绝。

重排、缺块、重复终结、身份漂移、未知必需 facet 都拒绝发布。P0 建议失败后整次 attempt 重传，先不做断点续传。建议 host 用专用继承管道承载 IPC，stdout/stderr 只处理日志，避免嵌入库的意外输出污染协议。传输有背压，daemon、Rust host 和 Java 桥接层都不能保留多份无限增长的序列化结果。

BuildAdvice 的 `argv` 为结构化参数数组，cwd 明确指向用户可操作的目标工作区；不能建议进入即将被清理的内部捕获目录。历史 commit 缺输入时说明需在哪个提交的用户 checkout 中准备产物。建议不作为进度成功事件，不触发自动构建/自动重试；Agent/人类独立执行后重新提交，daemon 再核验并捕获产物。证据不足无法给出可靠命令时，只返回所缺输入与所需信息，不猜构建参数。

当前 mock worker 协议为 2，Fact IR 仍为 1；Discover 可返回 BuildContext 或结构化 BuildAdvice。已传输 `missing/cwd/argv/purpose/expected/impact/retry/capture/module/world`，project 从任务身份取得；目标工具链要求的独立字段以及真实 Maven/Gradle 建议仍待 Java 适配器接入。CLI/daemon 握手协议仍为 1，旧 worker 协议在分析前拒绝。

## 5. CLI 与 daemon 协议

采用 **HTTP/2 + Protocol Buffers**，建议通过标准 gRPC 定义服务、生成客户端/服务端绑定并传输消息。`.proto` 集中放在拟建 `proto/codeana/v1/`，Rust 使用生成代码映射领域类型。HTTP/2 帧与 protobuf 序列化由协议栈处理，不再采用原建议的自定义 socket + JSON 任务协议。

```text
CLI 命令 / pipeline JSON 配置
              |
       TaskRequest protobuf
              |
       gRPC / HTTP/2（本机）
              |
       daemon TaskService
              |
       TaskEvent protobuf 流
              |
       CLI 写出 本项目版本化文本响应
```

| 对象 | 含义 |
|---|---|
| TaskRequest | client request ID、operation/pipeline spec、项目选择、显式类型化配置覆盖、预算、期望版本、断连策略 |
| TaskAccepted | daemon 分配的任务身份；若观测模式关闭，不对外泄露运行 ID |
| TaskEvent | accepted/queued/running/progress/terminal；事件不冒充查询结果 |
| TaskResult | execution、identity、assessment、gate、publication、report，以及记录和终止证据 |
| Attach / Status / Cancel | 重连查已登记任务、读取状态、表达取消；不是重新提交写操作 |

| 建议 RPC | 形式 | 作用 |
|---|---|---|
| Handshake | unary | 协商服务协议、能力、版本；HTTP/2 连接成功不代表应用协议兼容 |
| SubmitTask | unary | 登记任务与幂等键，返回 TaskAccepted；不等待长任务完成 |
| WatchTask | server streaming | 首次订阅或重连，按事件游标读取进度、输出和终态 |
| GetTask | unary | 查询任务、发布与结果可用状态 |
| CancelTask | unary | 提交取消意图，由任务状态机裁决 |

SubmitTask 到 WatchTask 之间有有界订阅宽限期，防止刚提交任务就因尚无流被取消；之后按任务断连策略处理。WatchTask 的事件序号只用于传输去重/重连，不替代语义 query/page cursor。结果过期或事件窗口缺失时明确报告，不能自动重跑写任务。

内部 RPC 版本与 `本项目版本化文本响应` 独立。建议 daemon 的输出模块按最终文本预算生成输出块，封装为 TaskEvent 的 protobuf `bytes` 字段，CLI 原样写出。typed pipeline 记录继续留在 daemon 内；任务身份、状态和证据终态使用类型化字段，不能只塞入一个不透明 JSON 字符串。pipeline 的 JSON 配置格式和内部报告格式不受传输编码变化影响。

| 协议规则 | 要求 |
|---|---|
| 字段演进 | field number 固定；删除字段保留编号/名称；破坏性变化升级 package/version |
| 未知与默认值 | 需要区分缺失/false/0 时显式 presence；枚举零值为 UNSPECIFIED；未知状态不当成功 |
| 事件类型 | `oneof` 区分 accepted/progress/output/terminal，终态必须含真实任务与发布状态 |
| 完成判定 | RPC status、任务终态、CLI 退出码分别处理；流结束或 HTTP 200 不代表任务成功 |
| 超时与取消 | RPC deadline 约束该次交互；任务 deadline 约束后台执行；取消订阅按任务策略传播 |
| 背压与预算 | 单条 protobuf 消息、待发送队列、总输出各设上限；HTTP/2 流控不等于业务内存预算 |
| 重试 | SubmitTask 使用应用幂等键；传输重试不代表允许重复执行写任务 |
| 身份与摘要 | protobuf 的序列化字节不作为跨版本规范身份；语义摘要按独立规范化规则计算，见 [序列化非规范性说明](https://protobuf.dev/programming-guides/serialization-not-canonical/) |

服务/流模式依据 [gRPC 核心概念](https://grpc.io/docs/what-is-grpc/core-concepts/)，HTTP/2 承载见 [gRPC 协议](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md)，字段演进见 [protobuf 指南](https://protobuf.dev/programming-guides/proto3/)。这是协议选型依据，尚未进行本项目的端到端验证。

配置的权威解释在 daemon：CLI 传入显式 overrides，不将未填写的 protobuf 默认值当成用户选择。公共配置和 Java/Rust 的类型化子消息分别校验；扩展配置按已注册 namespace/schema version 编码。EffectiveConfig、字段来源和 schema/defaults 版本随任务冻结，worker 禁止自行重新读取工作区或用户 TOML；详见 [配置契约](configuration.md)。

## 6. 证据与版本兼容

| 轴 | 判断内容 | 不能被什么替代 |
|---|---|---|
| enumeration | 所有 subject 是否从头读至明确结束 | SQL 返回空页、只读源码末页 |
| semantic | 选定 world/scope 内能力和覆盖是否充分 | 执行成功、目标名称解析成功 |
| limits | 计算、输入和输出是否受预算限制 | 普通翻页耗尽、下游 source 读取成功 |

否定结论还须符合具体操作的语义：三轴满足是必要条件，`overview project` 等操作仍不能声明否定安全。限制按阶段、subject、范围合并，只有本项目契约允许的 replacement 能替换对应证据。

| 独立版本 | 升级规则 |
|---|---|
| CLI text / typed pipeline | 本项目独立定义版本、参数、退出码与建议动作；以自己的 fixture/golden 验证，不兼容 Anatomist v5/v1 |
| CLI-daemon / adapter protocol | 握手协商；不兼容时不抢占或终止已有服务，升级接替遵守用户级单 daemon 约束 |
| Java native ABI | 独立版本；共享库、生成 header、Rust 绑定和平台匹配后才进入分析；禁止以 JNI 兜底 |
| Config schema / adapter config schema | 版本化字段、默认值与归属；未知必需字段失败；不提供旧配置迁移；TOML 与语言配置 protobuf 需对照验证 |
| Fact IR / facet / graph semantics | 明确兼容读取或重建，未知必需语义不能跳过 |
| graph schema / catalog schema | 本项目未来版本分别演进；失败保留已发布工件；不导入 Anatomist 数据库 |
| source capture / report | 独立版本、明确不可读原因，不借重跑写操作修复 |

本项目 selector 需覆盖 provider/module/scope、重载签名、外部声明、位置和分页身份；歧义明确拒绝。命令、文本协议名/版本、默认值与退出码在 P0 独立冻结；Anatomist 的同名概念仅作设计参考，不提供旧 ID 映射或旧协议兼容层。
