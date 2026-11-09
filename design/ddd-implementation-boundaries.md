# DDD 实现边界分析

状态：2026-11-09。以下问题表保留 11-08 的重构前证据；本轮已落实输入契约、公开编码与用例路由。这里只提出模块职责与协作方案，不新增进程、crate、公共 ID 或存储格式，不把文档分析记为产品放行。

## 1. 结论与推导

当前项目是分层实现，应用服务已按部分业务职责拆开，尚未形成由编译依赖完整约束的领域模块。应先收紧规则归属、公开契约和协作端口；六格不对应六个模块，B1–B7 也不机械对应七个服务。

CLI 最终目标、接口变化与阶段设计以 [完整重构方案](cli-refactor-plan.md) 为准。重构前 stop 仅调用 drain/cancel 并由 maintenance 请求退出；本轮 CLI 已补齐原 PID/启动身份的退出确认，restart 再确认新 generation。

| 分析起点 | 得出的边界依据 |
|---|---|
| 已确认的痛点与 P01–P15 场景 | 确定用户价值和完成条件，不从文件名或 RPC 数量倒推领域 |
| 六格中的对象与预期结果 | 识别场景需要哪些事实、判断和业务效果；定位主任务，不决定部署结构 |
| 业务规则由谁解释 | 确定领域能力和语言的所有者，例如候选不等于绑定、报告未知不等于零 |
| 哪个状态最有权威 | 确定提交、终态、引用、覆盖等事实归属，避免多个服务各猜一遍 |
| 哪些变更必须一起成立 | 确定事务与一致性边界；不以文件拆分破坏发布或引用保护 |
| 当前模块及真实依赖 | 判断已有隔离、职责混杂和改造顺序 |

DDD 中的核心/支撑说的是业务价值，限界上下文说的是语言和规则边界，crate/进程说的是技术组织；三者不能互相替代。

## 2. 业务责任与候选上下文

| 边界 | DDD 定位与负责的规则 | 当前承接 | 对外契约与不得承担的职责 |
|---|---|---|---|
| B1 Agent 交互契约 | 核心；能力发现、合法调用、结果含义、公开投影与交付语义 | cli；daemon 接入；query preflight；model 能力/结果类型；application output | 消费各域权威能力与结果，交付准确 help/text/导出；不重判语言绑定、实际提交或任务终态。先作核心交互模块，不要求独立数据库或服务 |
| B2 项目知识构建 | 核心候选上下文；输入/基线、分析 world、归属替换、事实/绑定/覆盖、质量与候选可发布性 | application index_plan/indexing/index_execution；语言模型/提取器；部分 staging completion | 产生候选内容、质量与范围，委托 B6 提交；不让 worker 自行切 head，不把通用任务状态当分析质量 |
| B3 代码知识求解 | 核心候选上下文；目标解析、结构/关系/派发/路径/聚合/代码差异及证据边界 | query；application QueryService 的图查询部分；HistoryService.diff | 消费固定知识/来源，返回类型化答案与证据；不触发编译器补答案，不承担 GC/恢复/引用删除策略 |
| B4 分析输入与就绪 | 支撑候选上下文；输入清单、有效配置、语言工作区、依赖/工具链条件、可信降级 | config；application inputs/语言输入/doctor；CLI 本地准备入口 | 交付冻结输入或就绪/缺口事实；不自动编译目标项目，准备完成不等于语义完整或索引生效 |
| B5 分析运行控制 | 支撑候选上下文；任务身份/幂等、准入、排队、预算/用量、取消、watch 和服务运行 | TaskService/TaskContext/scheduler/worker supervisor；daemon | 交付执行事实和控制效果；不重判绑定，不用取消或非零退出推翻 B6 的实际提交 |
| B6 分析资产生命周期 | 支撑候选上下文；实际提交、知识版本、快照引用、冻结端点、保护根、GC 与残留处置 | PublicationCoordinator/IndexRepository；history/maintenance；storage staged/objects/pages/maintenance | 提供持久提交与引用权威；不解释语言语义、不自行改变查询答案；保留 SQL 事务与文件协调的原子/恢复边界 |
| B7 分析解释与报告 | 支撑投影/比较模块；汇集原始状态/诊断/度量、已知/未知、报告可比性与留存 | ReportService；报告类型；各生产者诊断入口 | 消费原域事实形成报告，不成为绑定/质量/提交的第二权威；需要独立语言和生命周期时再固化为上下文 |

B1 的 help 即便由 clap 渲染，也仍承载核心价值。B1/B7 的价值定位并不要求立刻创建独立进程或独立数据上下文。B2/B3 有不同业务规则和读写职责，但共享已发布事实的公开语言，不共享可任意修改的同一聚合。

Java/Rust/Python 的解析模型、事实提取与派发语义承载 B2/B3 的领域规则；javac、rust-analyzer、Pyrefly 的接入和进程/FFI 是适配机制。不能把全部 Java 代码归为基础设施，也不能把查询语言策略全部搬到 worker。当前 query 在已发布图上执行 Java 派发是已确认的产品约定。

## 3. 上下文协作图

~~~text
Agent ←→ B1 能力/请求/结果交付
             │
     ┌───────┴─────────────────────┐
     ▼                             ▼
B4 输入/就绪 → B2 知识构建        B3 知识求解
                    │              ▲
              候选、质量、范围     │ 固定图/源码/比较端点
                    ▼              │
               B6 提交/历史 ───────┘

B5 承载任务、预算、控制；发布结果以 B6 为准
B7 消费各域真实事实形成解释/报告 → B1 交付
~~~

箭头表示业务信息交付和权威关系，不是所有代码 import 的方向。

| 协作 | 建议契约 | 一致性要求 |
|---|---|---|
| 外部编译器/分析器 → B2 | 各语言适配层转换冻结输入与已声明事实；GRA1/模型版本显式 | 编译器对象不越过模型；输入/world/来源与预算不偷换 |
| B2 → B6 | 候选、期望基线、质量门禁结论、任务身份及发布控制 | B6 再核对实际基线并持久提交；候选生成不代表已生效 |
| B6 → B3 | 固定版本/快照读视图与捕获源码 | 一次查询/多阶段/续页保持同一上下文；历史不回退当前文件 |
| B3 → B1 | 有类型答案、语义/计算证据与结果单位 | 公开投影选择必要内容，不按 SQL 列自动复制，不把静态可能升级为运行事实 |
| B5 ↔ B6 | 任务控制、持久提交查询与完成事实 | 取消/超时不能撤销已进入实际提交的持久效果；恢复以持久事实为准 |
| 各域 → B7 | 原任务身份、真实状态、诊断与已知度量 | 报告失败不回滚已提交结果，不从最新 head 猜原任务 |

共同保留公开身份、位置与稳定证据定义；已发布图/GRA1 是版本化集成契约，不是把各域全部内部模型作为共享内核。RPC protobuf 与存储 schema 各按现行版本管理，不能因为 DDD 分模块擅自改公共 ID/版本。

## 4. 重构前实现已经做对的边界

| 代码证据 | 判断 |
|---|---|
| [Engine](../crates/application/src/lib.rs) 只持 TaskService、TaskExecution 和 generation；服务在 new 中装配 | 已避免整机状态集中在一个 Engine，方向正确 |
| [TaskContext / TaskService](../crates/application/src/task_runner.rs) 与 [IndexTask](../crates/application/src/indexing.rs) | 索引拿任务绑定控制和发布端口，不直接访问全局任务表；运行与构建已部分分离 |
| [query Cargo](../crates/query/Cargo.toml) 只依赖 model/comment-search，不依赖 storage/application | B3 的查询核心与 SQL/调度隔离；GraphAccess 等读端口真实存在 |
| [IndexRepository](../crates/application/src/ports.rs) 与 [PublicationCoordinator](../crates/application/src/publication.rs) | 索引提交有窄端口；等待持久提交后再释放本任务生命周期 |
| [staged 发布](../crates/storage/src/staged.rs) | 先落盘，catalog 内核对 expected、登记 revision/head/commit；存储端保有最终提交权威 |
| [LanguagePolicy](../crates/model/src/language_policy.rs) 与 [语言查询入口](../crates/query/src/language_policy.rs) | 显式语言决定支持范围，不按名称/路径猜未知语言；Java completion 显式启用 |
| [TaskExecution.snapshot](../crates/application/src/task_execution.rs) 通过显式索引动作组合历史快照 | 跨域用例由应用层组合，不让快照模块访问整台 Engine |
| [Java 模块约束](../scripts/check-architecture.py) | core 不依赖编译器内部类型或框架实现；Java worker 不依赖存储/其他语言适配器 |

以上说明已有业务模块和依赖保护，不能概括成“全部重写”。但 application/history/storage/model 等仍跨多个责任边界，模块名和 crate 依赖尚不等于完整上下文隔离。

## 5. 具体混杂点与改造建议

| 优先级/问题 | 当前证据 | 影响与根因 | 建议 |
|---|---|---|---|
| 高：公开能力契约分散且存在矛盾 | [QueryArgs](../crates/cli/src/main.rs) 接受 in/out/both；[preflight](../crates/query/src/lib.rs) 只允许 overview 使用 both；daemon 握手和 LanguagePolicy 另声明能力 | agent 根据 help 构造的请求可能被拒绝；B1 无一致的公开契约，规则来源混在参数复用中 | 由 B2/B3 等拥有业务规则，B1 统一消费能力描述和校验依据；命令专属参数、语言支持、本机就绪分别表达。stop 的 cancel_all 也须纠正承诺/实现，不只改文字 |
| 高：存储读取形状进入公开输出 | [graph_query.attributes](../crates/storage/src/graph_query.rs) 复制除 *_pk 外的 SQL 列并展开 metadata；[Record.wire](../crates/model/src/records.rs) 与 [output.render](../crates/application/src/output.rs) 串成 JSON record 行 | 读取模型、公开内容和编码预算耦合，造成 display 别名/metadata 等重复；仅调整排版不足以解决 | B3 返回语义类型，B1 定义公开投影和 text/JSON 编码；保留必要生产扩展，按最终所选格式计费，不能按同值盲删 |
| 中：QueryService 实为通用操作路由 | [execute](../crates/application/src/query_service.rs) 同时执行 Doctor、Gc、Recover、SnapshotRemove、Report、Diff 和图查询 | 应用层统一调度本身合理，但写用例和维护策略放在“查询服务”中，后续容易依赖越界 | 将路由放在明确的用例调度层；图查询、资产命令、就绪与报告分别交给所属服务。先在现有 crate 内收紧，不要求新的 RPC 或进程 |
| 中：HistoryService 同时承接历史、代码差异和普通查询冻结页 | [history](../crates/application/src/history.rs) 包含 snapshot、diff、search_page、text_page、continue_page | B6 的引用/生命周期、B3 的差异含义、B1 的交付契约共同变化 | 将代码差异规则归 B3，快照/冻结结果及保护归 B6；页交付只消费固定结果，B1 管呈现/交付边界。保留共同事务与冻结端点 |
| 中：启动恢复在 TaskService 直接跨三个职责 | [recover_unfinished](../crates/application/src/task_runner.rs) 查询提交、store.read revision、完成任务后 store.recover(true) | B5 当前通过通用 Store 同时处理 B6 的提交与存储残留；恢复语义依赖整库接口 | 抽取启动恢复用例，分别调用提交事实读取、任务恢复、资产清理的窄能力；保留“已提交但正文未交付”的真相 |
| 中：多个服务持有完整 Store | QueryService、HistoryService、ReportService、TaskService、MaintenanceService 的构造与字段 | 有门禁也仍能从局部服务调用整库任意能力；索引已有更窄的 Repository，而其他域不一致 | 按稳定业务能力逐步提供历史读写、报告、维护与提交查询端口；SQL 和原子操作留在适配器，避免为每个函数创建一个 trait |
| 中：任务事件仍含 RPC 终态 | [Event::Terminal](../crates/application/src/lib.rs) 持有 p::Terminal，[TaskRepository.event](../crates/application/src/ports.rs) 接收 Event；TaskSnapshot 已返回内部 TaskResult | 隔离是局部完成的，不能说所有任务模型都依赖 protobuf；事件持久/重放仍可能随 RPC 变更 | 逐步以内部事件/结果表示领域事实，在 RPC 与事件持久入口映射；保留旧记录重放与错误身份 |
| 归属需显式：语言补全规则与批 SQL 在存储同处 | [language_completion](../crates/storage/src/graph_store/language_completion.rs) 按 Java 规则做 scope/反射/arity 的唯一补全；model 注册谁可补全 | 已有显式语言保护和歧义测试，不是未知语言误解析；但业务规则演进容易与 SQL 物理实现绑定 | 明确 B2 拥有语言补全规则，存储负责受策略约束的批 SQL；先建立规则契约/测试归属，再考虑提取计划，不能删掉或全部移入查询 |
| 扩展风险：model 是跨域模型集合 | [model](../crates/model/src/lib.rs) 与 commands/records/resources/publication 等 | 公共类型稳定有价值，但不等于全部内部类型都应成为共享内核；跨域修改会放大影响面 | 稳定身份/位置/证据可共享；构建、查询、任务、资产、报告内部状态各归所有者；对外依赖版本化发布契约，渐进整理 |

这些是源码能支持的职责和演进风险判断。除公开参数矛盾等已明确行为外，不据此推断生产故障或性能收益。尤其 SQL 可以保留最终约束和批执行，DDD 不要求把所有存储规则搬出事务。

## 6. 实体与一致性边界

| 对象/边界 | 需要一起成立的事实 | 实现约束 |
|---|---|---|
| 任务 | 请求幂等身份、阶段、事件顺序和终态 | 由 B5 管；发布状态从 B6 读取，不能仅由任务退出码猜测 |
| 索引发布点 | expected 基线、revision 登记、head 与任务 commit | 当前 [staged](../crates/storage/src/staged.rs) 在 catalog 事务内提交；fsync/rename 和中断恢复也是同一用例的必要部分，不可拆成独立远程成功 |
| 快照引用 | 保存/替换对象及对应保护引用 | [objects](../crates/storage/src/objects.rs) 事务替换 object/refs；同名可替换，解除引用不等于删除图 |
| 冻结答案/比较 | 固定端点、参数、结果、顺序、证据及引用保护 | 归 B6 生命周期，B3 定义结果含义；续页不重新查询当前工作区 |
| 维护处置 | 当前保护根、预览/执行选择、实际处理范围 | [maintenance](../crates/storage/src/maintenance.rs) 负责最终协调与保护核对；计划不保证未来完全相同 |
| 报告 | 原任务身份、原状态、度量已知范围及可比性 | B7 消费各域事实；[比较](../crates/application/src/reports.rs) 检查 profile/capture_digest/level，不用当前 head 替代原任务 |

项目是身份与隔离范围，不是把所有任务、快照、报告和整张图装入内存的巨大聚合。不可变图的规模化 staging 与最终发布一致性点应分开看；DDD 不要求单个领域对象装载全图。

## 7. 实施次序与验证

本轮优先处理的三项工作以 [优先重构待办](../todo/refactor.md) 为执行清单；下表保留整体演进顺序。

| 顺序 | 具体交付 | 必须守住的验证 |
|---|---|---|
| D0 | 固化场景/六格、规则权威与公开能力契约 | help 构造合法请求；支持/就绪分开；不增加默认 Next |
| D1 | 统一公开投影及最终格式预算 | 原文完整、关系多重性、证据与提交事实保留；text 和 JSON 分别验收 |
| D2 | 收紧操作路由及历史/差异/维护的职责，按需要引入窄端口 | 35 路径不漏；历史/续页稳定；GC 保护、报告比较与恢复原语义保持 |
| D3 | 整理内部事件与跨域模型、明确补全策略的规则所有者 | 旧任务/页重放、公开 ID、语言能力与 Java 歧义真值保持；协议/存储升级按现行规则执行 |

D0/D1 与本轮 D2 路由收口已落实；D3 的任务终态已改为内部 TaskResult，在协议边界映射。完整 Store 的逐域收窄、HistoryService 的进一步拆分以及语言补全策略提取属于后续演进，不将其列为本轮已完成。优先在现有 crate 内形成模块与端口，不要求一次性重构所有类型或拆微服务。

本轮产品与分发验证、实际失败及保留边界统一见 [当前实现](../docs/current-state.md)。架构门禁不检验全部业务职责，也不替代产品回归。

依据：[现行架构](../docs/architecture.md)、[公共契约](../docs/contracts.md)、[当前实现](../docs/current-state.md)、[场景六格](cli-application-scenarios.md)、[业务对象](cli-business-objects.md)、[能力边界](cli-business-capabilities.md)。实现验收另见 [实施计划](../todo/cli-implementation-plan.md)。

## 本轮实现落点

| 规则 | 落点 | 一致性边界 |
|---|---|---|
| 合法输入及静态能力 | application/input_contract + query/input_contract；CLI command_contract 消费 | 查询数值/语言规则仍由 query 预检和语言策略裁定 |
| 业务求解与用例组合 | OperationRouter → QueryService / HistoryService / MaintenanceService / DoctorService / ReportService | 未新增进程或整库对外端口；查询核心仍通过窄读取接口 |
| 公开内容与编码 | presentation / output；CLI watch_output / output_io | 类型化记录不解析旧 stdout；编码器不覆盖原质量门禁或实际提交 |
| 冻结与引用 | HistoryService + 原存储页/引用事务 | 原请求、上下文、排序/投影/格式固定，编码后核算页大小 |
| 发布与取消 | 原 IndexRepository / PublicationFence / TaskRepository | 原事务和提交优先语义保持；原文档指出的完整 Store 持有现状仍存在，未声称全部替换为窄端口 |
