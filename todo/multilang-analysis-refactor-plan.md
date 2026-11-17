# 多语言分析架构：破坏性重构实施计划

用途：交给干净的 main 分支实施公共架构重构，并为 [typescript-go C ABI 接入计划](typescript-go-c-abi-plan.md) 提供接入基础。本文仅定义目标、任务和验收条件，不包含执行记录，不依赖其他分支的探针、SDK 或本机回执。

允许破坏性修改内部类型、配置/输入清单、worker 协议和 SDK 格式；直接迁移调用方并删除旧实现，不保留兼容适配、双轨入口或自动回退。实现前重新核对 [开发约定](../AGENTS.md)、[文档导航](../docs/README.md)、[当前实现](../docs/current-state.md)、[架构](../docs/architecture.md)、[契约](../docs/contracts.md)、[输入](../docs/modules/inputs.md) 和 [索引](../docs/modules/indexing.md)。这些链接用于核对实施时基线，历史通过记录不能替代本计划验收。

## 1. 目标与重构依据

目标：所有语言按同一种分析单元执行；语言差异留在后端策略，公共层负责冻结、预算、生命周期、交付和一次发布。新增后端不再新增语言组合执行流程。

```text
普通配置 ───┐
            ├→ 公共任务配置 + 类型化分析单元
prepare 清单┘                │
                            ▼
              冻结输入：内容对象 + 上下文视图
                       + 文件系统观察
                            │
              计划 / 预算分配 / 后端装配
                            │
                            ▼
                       公共执行器
              ┌─────────────┼─────────────┐
              │             │             │
           Java 策略     Rust 策略    Python / TS 策略
              └─────────────┼─────────────┘
                            │
              经验证的 Full / Delta / Reuse
                            │
              统一候选合并 → 校验 → 一次 CAS
                            │
                      确认 / 回收 / 报告
```

### 1.1 代码依据与对应改造

| 现有入口                                                                                                                                                        | 要消除的结构问题                                                              | 目标                                               |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- | -------------------------------------------------- |
| [IndexPlan](../crates/application/src/index_plan.rs)、[index_execution](../crates/application/src/index_execution.rs)                                           | 全局策略由 Python/Rust 输入决定；Rust+Java、Python 混合和 prepared 仍各有流程 | 按单元表达策略，统一执行与发布                     |
| [analysis_unit](../crates/application/src/analysis_unit.rs)                                                                                                     | One/Pair/Sessions 反映不同组合；会话与输入分别放在数组中                      | 每个运行单元直接拥有会话、输入和 manifest          |
| [AnalysisLanguage](../crates/model/src/analysis_allocation.rs)、[输入配置](../crates/config/src/inputs.rs)                                                      | 源码语言、预算后端和实例身份未分开；EffectiveUnit 包含整份 Effective          | 明确三类身份，配置归一为公共部分与单元输入         |
| [capture](../crates/config/src/capture.rs)、[Rust capture](../crates/config/src/rust_inputs.rs)、[Python capture](../crates/config/src/python_inputs.rs)        | 按路径登记、同文件多上下文及辅助角色校验分散实现                              | 统一内容捕获和归属登记，语言只提供自身解析结果     |
| [worker](../crates/application/src/worker.rs)、[WorkerService](../crates/application/src/worker_service.rs)                                                     | 启动参数、producer、输出上限、进程监督和 Java 池策略混合                      | 后端装配、公共 supervisor、后端复用策略分离        |
| [Java delivery](../crates/java-worker/src/delivery.rs)、[公共 worker_rows](../crates/contracts/src/worker_rows.rs)、[stream](../crates/contracts/src/stream.rs) | 重复分批/摘要/终态实现；运行设施位于契约模块                                  | 归一到 graph-io；契约不持有运行队列                |
| [Java result](../crates/java-worker/src/result.rs)、[engine_request](../crates/contracts/src/engine_request.rs)、[bundle](../crates/contracts/src/bundle.rs)    | Java 内部 envelope、公共归档验证、SDK 专属字段混在公共入口                    | 语言 codec 独立，共享类型化归档描述和 SDK 基础校验 |
| [架构门禁](../scripts/check-architecture.py)                                                                                                                    | application 仅扫描顶层文件，语言名单及路径规则固定                            | 递归检查所有生产模块及间接依赖，配反例测试         |

这些是重构依据，不将结构风险直接等同为已发生的产品故障。实施时若入口变化，先定位同职责代码再迁移，不照抄旧路径。

### 1.2 保持的行为与不纳入范围的工作

| 边界       | 要求                                                                                                       |
| ---------- | ---------------------------------------------------------------------------------------------------------- |
| 产品行为   | 显式语言启用、冻结输入、明确 partial/unknown、可信用量、失败保旧 head、一次 CAS；提交后错误不撤销 revision |
| 语言能力   | Java 健康池与 javac、Rust 增量、Python 保守全量保持；不以重构为由降级成全语言全量分析                      |
| 身份与事实 | 无语义变化时保持既有公开 ID 算法、调用现场次数、hash/ordinal、注释映射和来源；预算与临时路径不进入语义身份 |
| 进程边界   | 编译器留在独立 worker；Java 无 JNI；Go runtime 不进入 daemon，TS 首版每次执行新进程                        |
| 工作区     | 保留 Python 独立 Cargo workspace；公共库不依赖 RA/Pyrefly/Go/JDK，普通 Rust 构建不自动构建 Native SDK      |
| 范围       | 不重写 CLI 展示、查询算法、SQLite 存储引擎或 Java 编译器桥；修改它们仅限新契约接入及必要图语义适配         |
| 目标项目   | 不自动运行构建、包管理器、业务代码、注解处理器或配置插件；显式 prepare 沿用各语言允许的静态准备能力        |

## 2. 目标模块、类型与所有权

### 2.1 模块依赖

下列 `graph-io`、`worker-runtime` 为拟新增 crate；其他为现有模块的职责调整。不另建 worker-support，不给旧模块保留兼容 re-export。

```text
model ← contracts ← graph-io ← worker-runtime
  ↑          ↑           ↑          ↑
  └──────────┴───────────┴──────────┤
                                    │
                         application / 各语言 worker

storage → model / contracts / graph-io
query   → model / 公共读端口
```

箭头表示依赖；实际使用方只引入需要的模块。config 保持依赖 model，不依赖 worker-runtime 或编译器；application 的存储适配器仍通过既有窄端口接入 storage。

| 落点                     | 唯一职责                                                                                  | 禁止引入                                                 |
| ------------------------ | ----------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| model                    | SourceLanguage、AnalysisBackend、AnalysisUnitId、预算、状态、公共图语义及分析范围         | SQL、进程控制、protobuf 对象、编译器对象                 |
| contracts                | 同源 protobuf、边界映射、帧/行编码与预解码准入规则、类型化 SDK/归档描述                   | 生命周期策略、运行队列、Java 引擎内部 JSON DTO           |
| `crates/graph-io/`       | GRA1 文件读写/校验、有界图行队列、批次/摘要、VerifiedEnd                                  | SDK 装载、worker 进程、Java/TS envelope、数据库发布      |
| `crates/worker-runtime/` | IPC 会话控制、帧读写进度、终态/取消、公共账本交接；独立 ffi 子模块提供有界 owner/通知设施 | application、storage、具体语言 worker、AST、目标配置解析 |
| application              | 配置与 prepare 用例、后端装配、进程 supervisor、任务计划、候选合并和发布                  | 编译器名称解析、任意 metadata 选择后端                   |
| 各语言 worker/adaptor    | 语言输入解释、引擎内部 codec、FFI 装载、语义提取和增量失效                                | 跨语言任务编排、catalog CAS、其他语言编译器              |
| storage/query            | 候选/引用/归属验证、持久化、读取和能力派发                                                | worker-runtime、语言 worker、启动分析器补查询结果        |

Java 引擎请求/回执 DTO 移入 Java worker；daemon 与 worker 都需要的 Java Discover DTO 和纯校验留在 contracts 的具名 Java 子模块。SDK 公共格式与后端身份为类型化契约，Java JDK/GC 的解释由 Java 装配/运行模块负责，避免 application 反向依赖 Java worker 二进制 crate。

### 2.2 单元、配置与能力

| 类型 / 概念      | 必需内容与规则                                                                                                  |
| ---------------- | --------------------------------------------------------------------------------------------------------------- |
| SourceLanguage   | 公开源码语言：java/rust/python/javascript/typescript；已发布未知 producer metadata 可以透传，但不能注册执行后端 |
| AnalysisBackend  | 编译期登记 Java/Rust/Python/TypeScript；TypeScript 同时支持 JS/TS，不提供动态插件注册                           |
| AnalysisUnitId   | 在项目内稳定且唯一；来自显式入口/原生上下文身份，不取 task、attempt、generation 或内容 hash                     |
| TaskConfig       | 一次解析的公共扫描、发布质量、资源、取消及报告策略；各单元引用，不复制完整项目配置                              |
| UnitInput        | Java/Rust/Python/TypeScript 类型化枚举；每个变体只包含本后端有效输入；删除多语言 Option 同时存在的内部状态      |
| PlannedUnit      | 实例 ID、后端、启用的发布语言集合、上下文/world、输入引用、发布范围、更新策略、预算绑定和生命周期策略           |
| UnitAvailability | Ready / FilesOnly / Removed；缺上下文与明确删除分开，FilesOnly 产生覆盖缺口，Removed 清除旧归属                 |
| RunningUnit      | 直接持有 WorkerSession、PreparedInputGuard、单元 manifest 及资源租约；共享 scratch 由运行集合持有               |
| UnitOutcome      | 经验证的 Full / Delta / Reuse，以及结构化失败；失败不带可发布证明，不能用空结果伪装成功                         |

外部 TOML/清单可以调整版本和字段，但解析后只产生上述类型化模型，不再构造 `EffectiveUnit → Box<Effective>`。普通入口与 prepared 入口归一后使用同一计划构造器；准备模式影响输入如何取得，不决定另一套执行流程。

JS/TS 同一原生上下文合并一次执行，发布语言取显式启用集合；不同 tsconfig 不按目录相同而合并。合并必须校验 compilerOptions、根与依赖视图一致。未启用语言的必要文件只能以明确辅助角色参与绑定，不能静默发布其语义事实。

后端注册只描述支持能力、工厂和身份校验；源码查询策略仍属于 model/query。把“支持、SDK 就绪、本次上下文充分”分别输出，不用 enabled 或一个 capabilities 字符串代替三者。

### 2.3 捕获、归属与稳定摘要

```text
ContentStore：hash → CapturedBlob（同一内容存一份）
                              ↑
Context A：逻辑路径 → hash，发布，world A
Context B：逻辑路径 → hash，辅助，world B
Context C：逻辑路径 → hash，发布，world C

ResolutionObservations：目录枚举 / 缺失查找 / 链接与 realpath / 配置
```

| 接口职责     | 实现要求                                                                                                                    |
| ------------ | --------------------------------------------------------------------------------------------------------------------------- |
| 内容捕获     | 提供一次读取、摘要核对、spool、配额与取消检查；内容去重不免除重复上下文引用的内存/工作计费                                  |
| 上下文登记   | 以实例、world、逻辑路径标识来源视图，校验 module/scope/language 和辅助/发布角色；允许同内容多上下文，不按 path 单独去重归属 |
| 冲突         | 同一冻结文件身份出现不同内容、同一上下文归属矛盾必须拒绝；不同上下文的辅助/发布角色可以不同                                 |
| 文件系统观察 | 文件、目录、失败查找、链接映射分别建模，不伪装成普通 SourceUnit；封存前复核，变化要求重新准备/捕获                          |
| 符号链接     | 仅使用清单明确记录且受允许根约束的逻辑路径→真实内容映射，检查循环/越界；不全局开放递归跟随链接。pnpm 真值由 TS T3 验证      |
| 语义身份     | 内容、上下文规则、依赖顺序和工具链进入 profile；prepare generation、日志、证据文件、scratch 及可搬迁 SDK 的安装位置不进入   |
| 完整性身份   | 配置原始字节与清单文件 SHA 继续用于一致性/篡改检查；与决定事实复用的规范化语义摘要分开                                      |
| 输入生命周期 | 由任务持有捕获内容，语言只读本单元视图；同输入在不同 scratch 重放应产生相同语义事实                                         |

复用现有 CapturedBlob，不另建 blob 数据库。语言层负责 Maven/Cargo/Python/TS 上下文解释，公共层不实现模块解析。WORKTREE 仍是经验证的逐文件冻结集合，不承诺 OS 全目录瞬时快照。

### 2.4 启动、会话与预算

后端装配产生类型化 LaunchSpec：二进制及参数、SDK 身份、预期 producer/后端/源码语言、输出上限和复用策略。application supervisor 负责自有进程组、RSS、截止时间和回收；worker-runtime 负责协议会话；具体 worker 负责引擎。

| 生命周期阶段     | 规则                                                                                                                                                        |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Hello / Discover | 核对协议、producer、实例、后端与输入；Discover 配置使用 oneof。合法编码下只能存在一个变体，wire 准入也拒绝重复/多变体，不能依赖 protobuf 的最后字段覆盖行为 |
| BuildContext     | 公共身份、模块/world、支持源码语言、语义摘要和能力；classpath、目标 JDK 等放入后端上下文。未知身份或不匹配变体拒绝                                          |
| Analyze          | 同一 attempt 消费冻结输入和固定基线；状态机与输入参数分离，不让 Java product.rs 成为通用服务模板                                                            |
| FFI              | 公共 owner/通知仅提供机制；Java 线程绑定与 G1 isolate、Go goroutine/cancel/句柄仍各自实现。Go 不依赖 dlclose 重置 runtime                                   |
| 终态             | Begin/Chunk 是 provisional；End 要求内容校验与可信用量回执。取消、失败、Reuse 和成功各有独立合法终态                                                        |
| 关闭             | 启动失败、Discover 失败、执行错误和取消都走显式异步回收；保留原执行错误，清理错误独立披露。无法确认停止时保留所需输入，不能提前删除                         |
| 池化             | 仅 Java 按身份、健康状态和确认结果复用；成功终止会话后解除任务输入引用。失败、ack 异常或 G1 teardown 后换进程；TS 首版不入池                                |

预算采用现有累计账本，重构身份与交接接口，不另建第二套计量器。分配顺序固定 Java→Rust→Python→TypeScript；先扣输入准入消耗，按参与后端分片，再按稳定实例 ID 分片。任务中删除/零输出单元保留既定份额，分片后不借用、不因重试续领；无执行单元时不得启动兜底 Java worker。

FFI 交接使用显式状态 `HostOwned → EngineOwned → HostOwned / Unknown`：先预留请求编码、引擎解码、控制与失败回执成本，再转交累计 seed；EngineOwned 期间禁止 Rust 修改同一累计账户。结果完成编码预留后封账，校验回执才能恢复 host 计费。未知终态保留最后核实水位，不认证成功。各实例独立账户可以并存，不把单账户互斥扩大成全任务锁。

公开资源报告按后端→实例展示，另列发布语言集合，避免 JS/TS 双重统计。静态分配顺序、实例排序和状态序列均有测试；首轮公共重构按确定单元顺序调度，不新增跨单元并行策略，语言内部已有并发规则保持。

### 2.5 归档、合并与发布

将 GRA1 IO、有界队列和图行生产统一到 graph-io；contracts 保留 Row/Descriptor/OutputLimits 等契约和 wire 准入。Java JSON envelope、TS protobuf 分别映射到公共描述符，归档读取器不判断语言。原归档摘要基于原字节校验，不能重编码后代替；向 worker IPC 交付的行流独立计算自身摘要。

全流程保留流式处理和有界 spool，不把统一结果建成内存中的完整 `Vec<Fact>`。graph-io 仅在 EOF、count、SHA、行准入和结束检查全部成功后提供 VerifiedEnd；daemon 还需核对会话身份与最终用量才能接受该单元。

| 单元结果         | 对候选的作用                                                                                             |
| ---------------- | -------------------------------------------------------------------------------------------------------- |
| Full             | 清除该单元旧/新发布范围对应的 owners，写入全部新事实；不能仅根据新输出推导删除范围                       |
| Delta            | 固定基线，核对本单元完整 replacement owners 后替换；Rust 专属依赖基线仍走 Rust 窄端口                    |
| Reuse            | 输入与语义 profile 相同，取得 Reused 回执后保留基线范围；保留真实 Discover 用量                          |
| Removed          | 无需启动 worker，按基线单元归属清空旧 owners，包括零输出、禁用语言及删除 world                           |
| FilesOnly        | 清除失效语义并写缺口/coverage，保留可捕获文件；strict 在发布前拒绝，不冒充正常零输出                     |
| Failure / Replan | 失败不发布；重规划丢弃受影响 attempt 的 provisional 数据，沿用任务预算和截止时间，不修改已验证数据的身份 |

在 revision 中持久化类型化单元归属清单，记录稳定实例、后端、上下文、发布范围和语义身份，用于删除与下一轮比较。首次新格式全量建立；不得从 producer 名称或当前剩余行反推旧范围。多个单元的写入/替换范围冲突应拒绝；显式共享输入不意味着共享发布 owner。

合并先固定同一项目基线，所有单元校验后构造一个候选。单元 Full 不等于整项目 Full；混合 Full/Delta/Reuse 必须与同输入整项目全量的规范化结果等价。后端不能证明增量安全时全量该单元；TS/Python 首版只允许 Full/Reuse。重规划需要清单外输入时返回明确准备需求，不能偷读 live FS 或重置预算。

质量校验、fsync、PublicationFence 和 catalog CAS 保持单一入口。提交前任何失败保旧 head；提交后尽力确认所有单元，ack、报告或清理失败仅记录实际后处理状态，不重跑发布。

### 2.6 SDK、图语义与破坏性切换

| 对象     | 改造规则                                                                                                                                                                   |
| -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SDK      | 公共 schema/platform/protocol/files 摘要，加类型化 Java/TS 等后端身份；Java JDK/GC/compiler bridge 与 TS Go/上游/标准库/ABI 分别验证。安装、doctor、启动与打包消费同一格式 |
| 图语义   | 公共实体类别、语言专属声明类别、调用目标证据分别定义。TS 的类型/值空间、合并符号、重载、声明目标和外部目标先做映射表；不伪装 Java method/class，不引入 Java CHA            |
| 图范围   | 字节区间、UTF-16 行列、声明来源、注释归属、hash/ordinal 和 owner 在模型、行映射、DDL、投影中一起校验                                                                       |
| 版本     | 实施时核对并递增实际改变的配置/清单、worker、SDK、图及公开报告协议；所有消费者同步重建。不预占下一版本，不因 Rust 代码搬迁修改 Java ABI                                    |
| 旧格式   | 旧协议/SDK/清单拒绝；要求重建 SDK、重新 prepare 或 index --full；不提供静默迁移和旧格式回退                                                                                |
| 历史数据 | 不自动删除旧 revision；不兼容查询明确拒绝，新格式全量建库成功后才切 head。新格式历史/source/diff/续页仍需完整回归                                                          |

允许破坏性修改用于消除不合理边界，不扩大为无关的模型/DDL 全面重写。TS 新声明类别和查询能力的生产实现仍归 TS T4/T5；本计划提前固定它们所需的公共边界。

## 3. 实施阶段与 TS 接入顺序

### 3.1 R0—R5 任务与出口

| 阶段              | 工作                                                                                                | 阶段出口                                                                      |
| ----------------- | --------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| R0 基线与契约     | 核对 main 和现行版本，先建 Java fixture 基线；固定新类型、依赖图、输入/更新策略、人工真值及删除清单 | 基线问题已定位处理；实施者能按契约迁移，不需要猜测兼容策略                    |
| R1 身份与协议     | 拆语言/后端/实例；配置归一，Discover oneof、公共上下文、SDK 与预算身份同步迁移既有三语言            | 新格式生产/消费端一致，错后端/实例及旧格式拒绝；旧内部类型退出调用链          |
| R2 交付与运行设施 | 建 graph-io/worker-runtime；迁移三语言交付、会话、计费交接；拆 Java 内部 codec                      | 只有一套公共图交付及会话规则；Java Native/JVM 行为、资源和终态通过回归        |
| R3 冻结输入       | 迁移三语言捕获、上下文归属、文件系统观察和语义摘要，保留各语言静态准备器                            | 多上下文无冲突、捕获后只读、重复 prepare 不因临时路径失效；去除递归 Effective |
| R4 执行与发布     | 统一普通/prepared 计划与 RunningUnit；实现 Full/Delta/Reuse/Removed/FilesOnly 合并及归属清单        | 语言组合流程删除，所有路径使用一次发布，增量/复用与全量语义等价               |
| R5 门禁与分发     | 递归架构门禁、反例、既有语言真实组合与实际包验证；更新现行文档和覆盖映射                            | 旧实现无生产调用方、全部必需回归无未解释跳过；三语言公共重构可独立验收        |

执行清单：

- [ ] R0：记录本次 HEAD/工具链与基线命令，保留原始失败；旧行为输出只用于比较，人工语义真值独立维护。
- [ ] R1：修改同源 .proto 和显式映射，重做受影响 wire 预扫描、帧预算、配置/报告/SDK 测试；禁止在新增字段缺失时补旧默认值。
- [ ] R2：将 Java result 解码和共享归档校验拆开，删除重复 Encoder；迁移 Rust/Python 的 GraphStream 调用并更新两个 workspace 锁文件及依赖门禁。
- [ ] R3：提供公共捕获/登记接口，迁移 Java/Rust/Python；把 prepare 路径定位与语义身份分开，修正 fixture 和包内 exporter。
- [ ] R4：迁移既有 Java、Rust 增量和 Python 保守策略，删除 One/Pair/Sessions 与独立组合流程；迁移后再移除旧函数，不能留下无人测试的备用路径。
- [ ] R5：同步架构、契约、配置、输入/索引/运行模块、版本说明、just 入口、打包和测试覆盖；只登记本阶段实际验证范围。

删除项须在对应阶段完成，而非最后只检查文件名：

| 删除项                                           | 对应替代                                          |
| ------------------------------------------------ | ------------------------------------------------- |
| AnalysisLanguage 兼任源码标签与预算后端          | SourceLanguage / AnalysisBackend / AnalysisUnitId |
| 递归 Effective、平行多语言可选输入               | TaskConfig + UnitInput + 上下文引用               |
| Java/Rust+Java/Python 混合/Prepared 全局策略流程 | 单元计划 + 后端策略 + 公共执行器                  |
| One/Pair/Sessions 与分离的 session/input 数组    | RunningUnit 所有权集合                            |
| Java 自有图行 Encoder、共享校验中的语言 envelope | graph-io + 各语言 codec                           |
| 通用模块中的 Java 引擎请求、JDK/GC 默认解释      | Java worker codec / Java 后端装配                 |
| 旧协议解码、旧 manifest 默认值和兼容 re-export   | 唯一当前契约，旧输入明确拒绝                      |

### 3.2 与 typescript-go-c-abi-plan.md 的边界

两份计划共同实施时，公共架构、模块落点和重构顺序以本文为准；Go 来源/构建、C ABI、解析、提取和 TS 真值以 TS 计划为准。本文不改变 TS 每次执行新进程、冻结 VFS、同源 protobuf 和不执行目标构建的决定。

```text
公共重构： R0 → R1 → R2 → R3 → R4 → R5（三语言独立验收）
            ↕ 契约对齐   │     │     │
TS 接入：  T0 → T2a ─────┘     │     │
                    T2b ──────┴→ T3 → T4/T5 → T6 → T7/T8
```

依赖以此表为准，避免箭头产生循环解释：

| TS 阶段 / 原描述             | 本计划后的实施方式                                                                                                              |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| T0 探针                      | 可以独立执行，不依赖 R1—R5；从固定来源重新验证，不能继承已有探针通过状态                                                        |
| T2a 草案                     | 在 T0 后与 R1 公共契约对齐；公共重构不要求先完成 TS 生产实现                                                                    |
| T1 / worker-support          | 公共抽取由 R2 承接，使用 graph-io 和 worker-runtime；TS 专属 FFI 适配及真实第二使用方仍由 T2b 验证，不因 R2 完成自动勾选整个 T1 |
| AnalysisLanguage::TypeScript | 改用 AnalysisBackend::TypeScript；公开标签仍为 javascript/typescript                                                            |
| contracts::graph_archive     | 契约 Descriptor/OutputLimits 留在 contracts；IO/队列/交付迁入 graph-io，TS 不新增第二套                                         |
| T2b 组件闭环                 | 依赖 T0、T2a、R2；用组件 fixture 接真实 Go 引擎，不要求先完成产品 capture                                                       |
| T3 输入闭包                  | 依赖 T2b、R3；Go 导出解析观察，Rust 使用公共捕获器封存；TS 的 pnpm/references/缺失查找按其真值验收                              |
| T4/T5 语义                   | 使用 R1 明确的公共边界，生产映射/DDL/查询和真实 JS/TS 真值由 TS 计划实施                                                        |
| T6 Conservative/Prepared     | 改为 R4 的统一执行器加 TypeScript Full/Reuse 策略；产品接入以 R5 既有语言门禁通过为前置，不恢复全局组合分支                     |
| T7/T8                        | 执行 TS 真实故障、资源和分发矩阵；重跑受 TS 接入影响的公共及既有语言门禁                                                        |

R0—R5 不以真实 Go 工具链或 TS 产品能力为放行前置；允许用合成 JS/TS 单元验证计划/预算模型，但必须注明不是 TS 编译器验收。TS 接入时必须补齐真实证据。

## 4. 测试设计与验收

### 4.1 测试组织与判定规则

沿用现有单测及产品测试入口，将断言迁到新的职责所有者。删除仅绑定旧函数/文件结构的测试，保留语义与故障断言；旧协议正向测试改为拒绝测试，不维护旧协议运行环境。新增测试必须验证边界或行为，不能只是复述实现。

| 层次      | 测试内容                                                                             | 必须断言                                                                       |
| --------- | ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------ |
| 架构      | 递归生产目录、间接依赖、两个 workspace、库/二进制边界；嵌套路径和中间 crate 注入反例 | 正常结构通过，越界一定失败；不能因改目录或别名绕过门禁                         |
| 类型/计划 | 普通/prepared、输入排序、同后端多实例、JS/TS 合并、冲突上下文、删除/空/全 FilesOnly  | 单元数、身份、执行次数、发布语言、预算份额和结果顺序符合预期                   |
| 捕获      | 同字节多 world、同上下文冲突、辅助角色、目录/缺失项、链接循环/越界、捕获后改删文件   | 字节与归属分别校验；只读封存视图，不从工作区或全局缓存补输入                   |
| 稳定性    | 重复 prepare、新 generation、新 scratch、改变枚举顺序、搬迁内容相同 SDK              | 仅非语义定位变化时 ID/profile/规范化事实相同；内容/配置/依赖顺序改变时正确失效 |
| 协议      | 缺/错后端、Discover 多变体、错实例/attempt、重复终态、非法顺序、半帧、超大/畸形帧    | 分配前拒绝、半帧状态保留、错误身份准确；失败不能认证 End                       |
| 预算      | 分片余数、删除保留份额、重试、FFI 移交/归还/unknown、业务耗尽与输出回压              | 总额守恒、累计单调、无重复领取、未知消耗明确；控制终态有界可达                 |
| 归档      | 坏路径/类型、截断、尾随字节、count/SHA 不符、解码放大、大行、消费取消                | 原字节校验；失败不发 VerifiedEnd，不封存候选；不泄漏临时资源                   |
| 生命周期  | 创建/Hello/Discover/分析失败、并发取消、进程死亡、回收失败、Java 健康复用/禁止重建   | worker 停止先于输入释放；无自有活跃进程残留；保留原错误，清理失败独立          |
| 合并      | Full/Delta/Reuse 混合、删除到零、禁用语言、world 改变、替换范围冲突、Replan          | 无漏删/越界复用；重规划不遗留旧 attempt 数据；与同输入全量等价                 |
| 发布      | 任一单元失败、strict partial、CAS 冲突、提交前取消、提交后 ack/report/清理失败       | 失败前后 head 与 commit 次数准确；每任务最多一次成功 CAS；所有单元均尝试清理   |
| 产品      | 实体、源码、引用、caller/callee、调用现场、注释、coverage、报告、snapshot/diff/续页  | 目标、范围、重复次数及未知原因符合人工真值；新格式历史稳定                     |
| 分发      | 实际包、独立 home、隐藏开发路径、缺组件、错协议/架构、坏 SHA、SDK 恢复               | 不借源码路径补依赖，不混装；开发产物恢复可核对                                 |

规范化比较仅排除 task/attempt、耗时、临时路径和数据库内部主键等非语义内容；不能排除公开 ID、world、绑定目标、调用次数、诊断/coverage 或注释位置来让比较通过。合法行顺序变化通过语义排序比较，原始 GRA1 SHA 测试单独保持严格。

预算/顺序/归属增加表驱动边界测试及固定种子的排列测试；失败保留种子与输入。并发/取消测试用显式 barrier、通知和有界超时，不用碰运气的 sleep 验证顺序。mock 仅用于公共协议与故障注入，真实编译器负责语义真值。

### 4.2 组合矩阵

| 阶段           | 必需组合                                                                                                          |
| -------------- | ----------------------------------------------------------------------------------------------------------------- |
| R1—R5 既有语言 | Java、Rust、Python；Java+Rust、Java+Python、Rust+Python；三语言；同后端多个独立工作区/上下文                      |
| R3/R4 输入路径 | 普通与 prepare 等价；全部 FilesOnly；一单元降级；明确删除；辅助输入共享但发布隔离；重复 prepare 后复用            |
| R4/R5 更新路径 | 单元 Full/Delta/Reuse 共存；首/中/末单元失败；删除/空图/重命名；相同更新序列与最后一次全量对照                    |
| TS T3—T6       | 单 JS、单 TS、同上下文 JS+TS、多 tsconfig、同文件多上下文、禁用其中一种语言；真实 JS↔TS 边与两种语言各自 coverage |
| TS T6—T8       | TS 后端分别与 Java/Rust/Python 组合及全部后端组合；重复 prepare、真实 Go 取消/崩溃和实际分发                      |

不扩展为所有配置的笛卡尔积；公共状态机边界用可控输入覆盖，真实产品按上述组合覆盖，并对每个后端执行启动、执行和交付阶段的失败场景。Java Native、显式 JVM、G1 teardown 后拒绝重建和 Python 独立 workspace 分别有对应门禁。

### 4.3 现有入口、未来新增测试与顺序

以下命令由实施者在代码改造阶段运行；编写本计划不构成执行这些门禁。

| 顺序 | 入口                                                                                                                                                                                   | 用途                                                                         |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| 1    | 已有 `just test-java`                                                                                                                                                                  | R0 Java 基线优先；后续源码/契约受影响时复验                                  |
| 2    | 各受影响 crate 的单测；[架构反例](../tests/architecture_boundary_tests.py)                                                                                                             | 新类型、IO、会话、捕获和合并的阶段边界；新 crate 测试随 crate 创建           |
| 3    | 已有 `just test-rust`                                                                                                                                                                  | 递归架构、DDL 生成一致性、fmt、workspace、产品和 clippy                      |
| 4    | 已有 `just test-python`                                                                                                                                                                | 独立 workspace/锁文件及真实 Python 核心、产品链路；按其现行入口补独立 clippy |
| 5    | 已有 `just test-java-native`，以及 [prepared](../tests/prepared_inputs_e2e.py)、[混合资源](../tests/analysis_resources_mixed_e2e.py)、[混合注释](../tests/comment_mixed_e2e.py) 等场景 | 当前源码构建的 SDK、真实生命周期、增量/组合与故障；按新模型迁移测试          |
| 6    | 已有 `just test-rust-release`、`just test-java-release`、`just package-python /absolute/new/package`、`just test-python-release /absolute/new/package`                                 | 实际包及恢复；缺少正式签名条件时如实记录范围，不能用 build-only 代替正式放行 |
| 7    | TS 计划拟新增的 core/product/release 入口                                                                                                                                              | 仅 TS 接入阶段执行；不将 R5 的合成单元测试当 TS 验收                         |

R5 新增一个公共重构产品矩阵入口 `tests/multilang_analysis_e2e.py`，复用现有 fixture/harness，集中表达单元数量、更新组合、一次发布和删除归属；共用工具先抽取再删除旧组合测试的重复部分。入口加入 just 的对应产品门禁，声明需要的真实后端；强制验收缺后端必须失败，不能静默 skip。保留语言专属语义测试，不全部搬入大测试文件。

阶段验证遵循先聚焦后完整：受影响单测通过后执行该阶段必需门禁；后续无改动不重复同一套昂贵验证。最终 R5 使用当前源码重建并跑全体受影响语言、混合与分发；历史回执不代替本阶段结果。

## 5. 完成标准与实施记录要求

- [ ] R0—R5 各阶段任务、删除项和出口逐项关闭；普通、prepared 和多语言任务无备用旧流程。
- [ ] 既有三语言迁入公共设施；新增后端只增加输入/引擎/语义/策略及显式注册，不增加组合执行器、账本、图交付器或发布入口。
- [ ] contracts 无运行队列和 Java 引擎内部 DTO；graph-io 无后端分支；worker-runtime 不依赖编译器、application 或 storage。
- [ ] 正常、失败、取消、超限、Replan、Reuse、清空与提交竞争都有可核对断言；归属清单能解释删除/禁用后的替换范围。
- [ ] 旧协议/清单/SDK/不兼容图库明确拒绝，没有自动旧格式回退，没有自动删旧数据。
- [ ] 现行 docs、配置示例、just、包清单和测试覆盖映射与新实现一致；TS 计划的公共落点按 3.2 对齐，未实施的 TS 能力继续待办。
- [ ] 保存本次源码、输入/预算、SDK/二进制 SHA、工具链、命令、日志、失败与跳过原因到唯一新建证据目录；执行状态归现行文档，不覆盖旧失败、不复用其他分支的通过声明。

性能不作为每阶段反复执行的门禁。最终正确性通过后如执行性能验证，按仓库约定固定自建 fixture、空应用状态、预算和实际产物，仅取一轮；报告索引/查询、同一时刻进程树 RSS、归档/图库大小及语义摘要，不以重构或 C ABI 推定性能收益。

本文的文件路径和代码链接用于定位现有职责，拟新增 crate、类型与测试在对应阶段创建。实施时按当前 main 查证并更新现行说明；不能将本计划、文档检查或静态架构检查标记为代码重构、Native、TS 产品或正式分发验收完成。
