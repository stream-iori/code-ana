# 架构整改与注释索引联合开发计划

状态：已完成本计划本机 fixture 范围的实现与验收。收口日期：2026-11-01；原审查基线：`0395fe7c8cd7813a0e701ec0edb8ef81ecf677b5`。实际提交、产物与失败记录见执行表和各阶段回执；本计划完成不代表跨平台、完整语义或容量/稳定性能认证。

**实施顺序：必要架构拆分 → 注释功能交付 → 后续架构整改。**

## 1. 计划分工与实施依据

| 文档 | 唯一负责的内容 |
|---|---|
| 本文 | 架构目标、任务编号、依赖关系、实施顺序、阶段门禁 |
| [注释索引与代码关联计划](comment-code-indexing.md) | 提取规则、查询行为、配置、数据模型及专项验收场景 |

原注释计划的功能范围和验收要求全部保留。架构重构与功能变化分开提交、分别验收；必要拆分保持既有行为，注释能力集中进行协议与 schema 演进。不增加新语言、动态插件系统或其他 Java 解析后端。“注释”指源码 comment/docstring，不恢复已删除的业务标注、人工笔记。

### 当前问题与对应任务

| 整改前核对的代码边界 | 整改前问题 | 处理任务 |
|---|---|---|
| [索引路由](../crates/application/src/indexing.rs)、[公共执行](../crates/application/src/index_execution.rs)、[Python 输入](../crates/application/src/python_inputs.rs) | 语言入口还组织其他语言，Python 输入构造重复 | A1、A2 |
| [Worker](../crates/application/src/worker.rs)、[Java worker](../crates/java-worker/src/product.rs) | 公共 Worker 使用 Java 模块的磁盘预算；Java worker 为缓存依赖图库 crate | A1 |
| [配置](../crates/config/src/lib.rs)、[配置类型](../crates/config/src/schema.rs) | 配置、发现、捕获混合；Java/Rust 共用包含语言专属字段的配置类型 | A3、A5 |
| [查询派发](../crates/query/src/dispatch.rs)、[层级查询](../crates/query/src/hierarchy.rs)、[图关系整理](../crates/storage/src/graph_store.rs) | 公共查询包含 Java 规则与 Python 特判；存储承担部分语义补全 | A4 |
| [Engine](../crates/application/src/lib.rs)、[索引端口](../crates/application/src/ports.rs) | 多个模块共享 Engine 状态，基线接口包含 Rust 专属能力 | A5 |
| [Java 扩展接口](../lang-java-adaptor/analysis-core/src/main/java/com/anatomist/framework/JavaModelExtension.java)、[框架装配](../lang-java-adaptor/java-bridge/src/main/java/com/anatomist/application/FrameworkPlugins.java) | Lombok 实现在 core；旧扩展可实现接口但生产预算入口默认失败 | A6 |

保留现有进程隔离、解析器依赖隔离、不可变 revision、资源预算、PublicationFence 和单次 CAS 发布边界。职责整改以减少跨模块修改和状态访问为准，不以文件数量或接口数量作为完成标准。

现行依据：[架构](../docs/architecture.md)、[契约](../docs/contracts.md)、[当前实现](../docs/current-state.md)、[统一输入](../docs/modules/inputs.md)、[索引](../docs/modules/indexing.md)、[存储](../docs/modules/storage.md)、[查询](../docs/modules/query.md)、[配置](../docs/configuration.md)、[开发入口](../justfile)。

## 2. 任务、依赖与交付顺序

箭头表示硬依赖。缺少前置验收，不进入下游合并；契约讨论和只读调查可提前开展。下表是依赖关系的维护源，调整时同步图和原注释计划的阶段映射。

```text
A0 基线与验收清单
 ├─ A1 公共设施归位 ── A2 统一索引编排 ─────────┐
 └─ C0 注释契约冻结 ── A3 配置与查询接入边界 ──┤
                                               ↓
                                      C1 Java 注释纵向闭环
                                               ↓
                                      C2 生命周期与故障闭环
                                               ↓
                                      C3 Rust / Python 接入
                                               ↓
                                      C4 注释完整交付验收
                                       ├─ A4 语言语义策略
                                       │       ↓
                                       │   A5 服务所有权拆分 ──┐
                                       └─ A6 Java 扩展整理 ───┤
                                                              ↓
                                                         A7 架构收口

现行文档、覆盖映射与证据记录贯穿每项任务。
```

| ID | 状态 | 任务及交付物 | 硬依赖 | 完成门槛 |
|---|---|---|---|---|
| A0 | 完成 | 固定开发基线、公开行为、协议版本、已有测试与缺口；建立受影响模块清单 | 无 | 能区分原有缺口与本轮回归，基线有明确提交和产物身份 |
| A1 | 完成 | 提取公共派生缓存、scratch 预算；消除 Python 输入构造重复 | A0 | Java worker 不再为缓存依赖图库 crate；通用 Worker 不引用 `java::DiskBudget` |
| A2 | 完成 | 普通、混合、prepared 入口汇入统一 IndexPlan 和执行生命周期 | A1 | Java/Rust/Python 适配器不组织其他语言；单次发布、预算、取消及复用行为保持 |
| C0 | 完成 | 冻结三语言注释契约、查询语义、版本影响表和 Java 预期 fixture | A0 | 公共模型能表达三语言注释与关联，不为后两种语言重新设计主链路 |
| A3 | 完成 | 建立查询参数归一化、配置/捕获模块边界和注释读取端口 | C0 | 显式值与默认值可区分；查询算法无需依赖具体存储或完整 Engine |
| C1 | 完成 | Java 提取 → GRA1 → 注释表/FTS → CLI/RPC/pipeline 查询 | A2、A3 | Java fixture 与公开查询闭环通过；包含两类检索入口和上下文配置 |
| C2 | 完成 | 增量、删除、历史、分页、预算、取消、失败与 CAS 闭环 | C1 | 同输入 full/delta 等价；失败保留旧 head；历史上下文不漂移 |
| C3 | 完成 | Rust/Python 提取与关联接入已有公共链路 | C2 | 三语言及各组合通过；不新增语言组合专用编排 |
| C4 | 完成 | Native、分发、混合语言、自建 fixture 综合验收及单轮测量 | C3 | 原注释计划完成条件逐项有实际证据，未通过范围明确保留 |
| A4 | 完成 | 查询语言策略及存储语义补全归属显式化 | C4 | 未声明能力的语言不进入 Java 默认语义；已有支持范围结果保持 |
| A5 | 完成 | 配置/捕获进一步拆分，Engine 按服务划分状态所有权 | A4 | 服务不通过完整 Engine 访问无关状态；主要策略可独立测试 |
| A6 | 完成 | Lombok 模块归位，Java 生产扩展预算契约收紧 | C4 | 扩展通过统一装配入口接入，缺预算实现无法进入生产注册 |
| A7 | 完成 | 架构依赖门禁、完整回归、文档与分发收口 | A5、A6 | 新边界有自动检查；注释功能及既有能力无回归 |

A7 通过 A5 传递依赖 A4。A1/A2 与 C0/A3 可以独立推进；A5 与 A6 在满足前置条件后也可独立推进。这里只描述任务关系，不预设并行人员或代理。

### 原注释计划阶段映射

| 原阶段 | 总计划对应项 | 调整 |
|---|---|---|
| P0 契约与 fixture | C0 | 可以提前，与公共设施整理独立进行 |
| P1 Java 闭环 | C1 | 增加 A2、A3 为前置 |
| P2 生命周期 | C2 | 保留 |
| P3 Rust/Python | C3 | 保留 |
| P4 综合验收 | C4 | 保留 |
| P5 文档 | 各任务持续更新，C4 最终核对 | 不再等全部开发结束才更新 |

注释交付只依赖 A0—A3，不等待 A4—A7。A4—A7 作为后续架构工作，在 C4 通过后实施，避免同时改动注释交付与既有语义策略。

## 3. 实施边界与接口安排

### 必要架构拆分：A1—A3

- **公共设施**：派生缓存提取为独立小 crate，由 storage 和 Java worker 共用；scratch 预算移入 application 的公共资源模块。保留缓存目录、key、锁、配额与持久化行为。
- **统一编排**：定义内部 `IndexPlan`、`AnalysisUnit` 和编译期语言注册入口。语言单元负责自身输入准备、profile、增量策略及 worker 启动描述；公共编排负责预算分配、执行、输出校验、合并与一次发布。
- **保留语言差异**：Java 健康 worker 复用、Rust 增量、Python 保守全量和 prepared 降级继续存在，作为显式策略实现。保留单语言流式通道，不统一退化为归档落盘；保留进程内累计账户、任务截止时间与提交裁决。
- **配置与捕获**：先在现有 crate 内拆分解析校验、语言输入发现、源码捕获模块；保留外部 TOML 格式。后续 A5 分离 Java/Rust 内部配置类型，共享扫描与资源配置。
- **查询边界**：注释查询使用窄 `CommentReader` 端口；请求归一化在应用层完成，冻结结果保存实际参数。普通源码查询和历史源码读取继续遵守原有差异。

A1—A3 不改既有公开 ID、图语义或协议版本，不顺带重写 Java 派发和存储补边规则。A3 提供内部端口和归一化接入点；新增公开注释请求、RPC 字段、配置项及持久化结构统一在 C1 接入，不提前暴露未实现能力。

### 注释交付：C0—C4

- 新增类型化注释事实、关联事实、查询请求及结果；继续使用独立的 GRA1 行类型，不塞入方法 metadata，也不复用 Java annotation 表。
- **语言侧负责语义**：注释识别、文档归属和最近 callable 归属由各语言适配器产生；只保留 documents/enclosing_callable。存储校验来源、范围、world 和引用，不按同名猜测注释归属。
- **公共层负责检索**：建立独立注释分词模块，统一封装 jieba-rs、词典摘要、标识符拆分与归一化；索引和查询共用，query 不依赖 storage。
- 提供 `search-comments`、`search-code --in`、无源码窗口的 symbol 导航；完整贯通 RPC、pipeline、历史及冻结分页。
- 关键词 AND、稳定排序和位置映射保持；源码窗口、注释专用参数／配置及相邻语句关联已删除。完整声明通过 source 和固定 revision 按需读取。
- C0 固定受影响协议、模型、schema 和能力标签的升级清单；C1 同步所有生产端与消费端。保留 protobuf 已废弃字段号，C ABI 入口不变则不升级 ABI。
- C1 阶段 Rust/Python worker 即使尚不产出注释，也必须与新公共协议兼容；中间阶段不得宣称已完成三语言注释支持。C3 复用 C0 的公共契约，语言 producer 身份与能力按实际实现更新。
- 旧图库按既有规则要求全量重建；成功发布新 revision 前保留旧 head。DDL 修改生成输入及生成器，再生成紧凑 DDL，不直接修改生成结果。
- 查询展示参数不进入语义 profile；词典、分词和关联规则进入索引身份。Java 声明契约指纹继续忽略注释内容，注释独改仍更新所属 owner。
- 注释提取、关联、分词、传输、FTS、查询和源码输出接续现有资源预算及取消机制；类型绑定不完整时保留可提取注释，并独立披露注释覆盖缺口。

### 后续架构收口：A4—A7

- 将 Java/Python/Rust 查询能力与策略集中注册；公共查询保留遍历、分页、预算和证据传播。未知语言能力明确拒绝或返回未支持状态，不默认按 Java 解释。
- 将已有按符号、scope、反射名、arity 补全关系的规则组织为显式语言策略；存储仍执行批量 SQL、引用校验和物理映射，避免改成逐条查询。新增注释关联不进入这些补全规则。
- Engine 保留装配职责；任务、索引、worker 生命周期、历史和报告分别拥有自身状态与窄依赖。Rust 基线能力从通用基线端口分离；不机械地为每个存储方法增加接口。
- Lombok 实现移出 analysis-core，统一通过框架装配；生产扩展必须实现带预算的入口。保留 SOFA 对 Spring 的真实依赖。
- A7 检查 query 不依赖具体存储，公共核心不依赖编译器内部类型，语言单元不组织其他语言，Java worker 不依赖图库 crate；使用可检查的依赖规则与行为契约测试，避免只按代码行数设门禁。
- 每项重构先做行为等价验证。发现原有语义错误时单列缺陷，不混入结构重构的等价承诺。

## 4. 验证、发布与完成门槛

| 阶段 | 必测内容 |
|---|---|
| A0 | 记录精确提交、输入和产物；登记已有 partial、未支持能力、缺口与失败；不能使用旧回执冒充当前基线 |
| A1—A3 | 单语言、两两组合、三语言、prepared 多工作区；未变复用、删除、重规划、取消、超时、资源超限、CAS 冲突、Java worker 复用；公共缓存与 scratch 行为保持 |
| C1 | Java 注释词法与作用域；中文检索、混合标识符、孤立注释；默认 5/5、非对称值、0、100、非法配置；CLI/RPC/pipeline；UTF-8/UTF-16、emoji、CRLF 与高亮映射 |
| C2 | 注释独改、代码独改、零输出、改名、删除、语言停用、world 删除；FTS 清理；冻结分页、源码 hash；断流、崩溃、超大注释和磁盘失败 |
| C3 | Rust doc 属性及嵌套注释；Python docstring 与普通字符串区分；跨 world 隔离及混合发布 |
| C4 | Native 生命周期、实际分发包、自建 fixture 功能预期集、一次正式性能测量 |
| A4—A7 | 既有查询与事实等价、语言能力拒绝行为、注释完整回归、架构依赖限制和受影响分发 |

上表不替代原注释计划的详细测试矩阵，两者均须核对。行为等价比较保留公开身份、重复次数、来源位置、数组顺序和语义字段；必要的 JSON 对象键序规范化单独记录，不宣称原始字节等价。

验证顺序沿用项目约定：

1. Java fixture 优先：`just test-java`。
2. Rust/Python 核心与共享存储、查询验证。
3. `just test-rust`、`just test-python`，以及受影响的资源/混合/历史矩阵。资源相关入口以 `justfile` 为准，包括 `just test-analysis-resources-e2e` 和 `just test-analysis-resources-mixed-e2e`。
4. Native、Java/Python/Rust 受影响分发回归：`just test-java-native`、`just test-java-release`、`just test-rust-release`、`just package-python /absolute/new/package`、`just test-python-release /absolute/new/package`、`just test-python-mixed`。执行路径、SDK 和原二进制按现有约定恢复，禁止混装和替换活跃进程。
5. C4 使用本仓库固定的自建 fixture，先建立并验证注释命中和方法定位预期集，再进行一轮固定输入、空应用状态、固定预算及产物的测量。

改造前后的功能验证可以分别执行；不为每项架构整理增加正式性能测量。C4 记录耗时、同一时刻进程树峰值内存、图库大小、注释与关联数量、固定查询集结果，不区分 Native 冷热。A7 后续重构不得沿用 C4 的测量作为新版本性能认证，也不默认重复正式测量。保留所有原失败回执，fixture 通过不扩写为完整语义、跨平台或容量放行。

### 分阶段完成条件

- [x] A0—A3：必要边界已拆分，原有索引/查询/发布行为通过对应回归，允许进入 C1。
- [x] C0—C4：原注释计划全部完成条件和测试要求有实际证据；三语言查询、生命周期、分发及自建 fixture 综合验收范围明确。
- [x] A4—A7：语言策略、服务所有权和 Java 扩展整改完成；依赖门禁、既有能力及注释回归通过。

## 5. 文档维护与排期规则

- 本文每项任务维护状态、硬依赖、交付物、验收结果、证据位置和未完成项。未产生证据时明确写“未执行”，不以计划命令代替实际结果。
- 原注释计划只补依赖和映射，不复制另一套架构任务或删减功能要求。
- 功能规则变化先改注释计划；依赖和排期变化先改本文，两处链接与阶段映射同步。
- 各任务完成时同步对应现行 docs、版本门禁、导入/打包入口和覆盖映射；P5 为持续任务，C4 做注释交付最终核对，A7 做架构最终核对。
- 状态只依据实际交付与检查更新；“代码完成”“fixture 通过”“分发通过”“自建 fixture 综合验收”分别记录。前置任务失败时，下游保留待办，不通过提高预算或减少范围冒充原门槛通过。
- 任务采用分阶段提交；先做结构重构和等价验证，再接入注释行为。清理失效入口和测试时修正门禁、共享工具及覆盖映射，保留来源清单各自的历史提交。
- 仅整理计划文档时检查相对链接、任务编号、依赖无环、阶段映射完整，以及无遗漏原计划事项，无需触发构建或性能测试；实际开发按任务门禁执行并登记证据。

### 执行记录

开发基线为 5d2283f37675244f67de928bdb582e6025fb1e17。A0—A7、C0—C4 均已完成本计划范围的实现与验收；验证结果在下表登记。回执绑定实际提交和产物，历史过程保留原义，不复制大日志到本目录。

| 任务 | 代码/文档提交 | fixture | 分发 | 自建 fixture 综合验收 | 证据位置 | 未完成项 |
|---|---|---|---|---|---|---|
| A0 | 基线取证；工作区实现未提交 | Java 701/701；原始 Rust application/config/storage 110/110 | 未执行 | 未执行 | target/todo-implementation/receipt.json | 既有跨平台/完整语义/容量缺口保留 |
| A1 | 公共缓存、scratch、Python 输入已实现 | 完整 Rust、Python 核心/CLI、prepared 7、混合产品 2、资源 29 + 历史 1 + 混合故障 12 通过 | 未执行 | 未执行 | 同上 | 分发和正式验收属于后续阶段 |
| A2 | IndexPlan、公共执行/输入所有权、复用/确认/回收及统一发布门禁完成 | 应用 47；完整 Rust；资源 29+历史 1；混合故障 12；Native prepared 7；Java 图 8、复用 5；真实 C ABI 崩溃/恢复通过 | 未执行 | 未执行 | target/todo-implementation/execution-lifecycle/receipt.json | 分发和正式验收属于 C4；显式语言执行策略按原契约保留 |
| C0 | 注释契约/类型与 Java 预期样例已实现 | 模型 2；Java 8 预期样例编译及锚点通过；C1 已读取该预期验证真实提取/关联 | 不适用 | 不适用 | 同上 | 公开查询预期待 C1 余下链路 |
| A3 | 配置模块拆分、CommentReader、可选上下文值及应用层归一化完成 | 配置 39；上下文模型 2、应用归一化 2 通过 | 不适用 | 不适用 | 同上 | 公开配置/查询及冻结页中的注释实参属于 C1，内部有效值可冻结 |
| C1 | Java 提取、独立 GRA1/FTS、公开查询与配置完成 | 完整 Java 719；Rust 门禁；Native/JVM 查询各 2；ABI 生命周期 10 | 开发 SDK 已安装，正式分发待 C4 | 未执行 | target/todo-implementation/comment-c1-final/receipt.json | Rust/Python 提取属 C3；综合验收属 C4 |
| C2 | 生命周期、预算/取消、owner 与旧游标校验完成 | 完整 Rust；Native 生命周期 8+2；存储 39；原 HEAD 冻结页摘要兼容通过 | 未执行 | 未执行 | target/todo-implementation/comment-c2-final/receipt.json | 原 128 MiB Native 分片失败保留；正式分发及测量属 C4 |
| C3 | Rust/Python 词法、文档、范围/声明关联及 COMMENTS 覆盖完成 | Rust 完整门禁 314 单元通过/1 默认忽略；Python 核心 30；Rust 注释 2、Python 注释 3、语言组合 3、prepared 7、Python 产品 7 | 未执行 | 未执行 | target/todo-implementation/comment-c3-final/receipt.json | C4 Native/分发/综合验收及单轮测量 |
| C4 | Native/三种分发/混合及固定 fixture 验收完成 | Java 719；Rust 317+显式 G1；Python 32；release 资源 42、注释/组合 11 | 三种实际包通过，原执行字节恢复、SDK 未变 | 128 类、3,328 注释、4,224 关联、5 组查询；单轮测量通过 | target/todo-implementation/c4/receipt.json | 默认 100 万节点预检失败保留；功能/测量使用显式 1,000 万节点；不签全局容量/跨平台/完整 Native 迁移 |
| A4 | 显式查询能力与 Java 批量补全策略完成 | workspace 321；最终 query 34；Native 图 8、Python 7、Java 注释 2、混合注释 3、最终 Rust 派发 1 通过 | 留待 A7 | 不适用 | target/todo-implementation/a4/receipt.json | Rust 旧误入 Java CHA 单列缺陷修正；不重测性能 |
| A5 | 独立配置与服务状态所有权完成 | 完整 Rust 323；资源/故障 42；Native 图 8、Python 7、Python 注释 4、prepared 8、Java 注释 2、生命周期 8、删除 2、混合 3、真实崩溃恢复通过 | 留待 A7 | 不适用 | target/todo-implementation/a5/receipt.json | 旧配置/prepared 指纹不变；SDK 恢复一致 |
| A6 | Lombok 模块和生产预算契约完成 | Java/Native 728；Native/JVM 各 23；11 组门禁通过 | 留待 A7 | 不适用 | target/todo-implementation/a6/receipt.json | 原生产漏装失败保留，已修正；固定上游对象缺失不冒充覆盖通过 |
| A7 | 架构门禁及注释缺口修正完成 | Rust 完整；Python 核心 32、prepared 9；Native 23；依赖反例 7 | 三种分发、资源 42、注释/组合 11 通过 | 功能 5 组；未重测性能 | target/todo-implementation/a7/receipt.json | 固定上游对象缺失/跨平台/完整语义与容量仍保留全局边界 |

以下推进段落按各自执行时点保留；现行状态以任务表与各阶段收口回执为准。

2026-11-01 范围调整：按用户要求排除 iopengw，不作为开发、构建或放行前置；C4 保留自建 fixture、Native、分发与单轮固定输入测量。历史回执保持原义。

A2 后续实施：普通 Python、prepared 与 Rust 未变复用已共用 stage_reused_revision，Reused 回执仍先于候选构建；保留各策略的 producer/profile/manifest 与发布屏障。运行单元驱动全部准备和确认仍待完成，不将这次公共方法提取标为 A2 完成。

A2 继续实施：四条执行策略已共用 analysis_unit 的复用终态/发布确认处理，保持原顺序、失败回执与最后一项确认错误。输入准备和运行单元所有权、A3 生产归一化及 C1—C4/A4—A7 仍未完成。

A2 输入所有权推进：prepared 执行使用 RunningUnits 持有 sessions、类型化语言输入与共享 scratch；可用单元及内存分片按 IndexPlan.units 驱动，降级单元仍输出原 coverage。普通入口统一准备及 A3/C1—C4/A4—A7 仍未完成。

本轮所有权回归：application 42、最终 Native 三语言 prepared 7、静态配额/原子发布 3 通过；先前 prepared 资源矩阵运行 19 项，其中 2 项默认跳过，另由显式 Native 产品回归覆盖。workspace build、clippy、fmt 与差异检查通过。证据：target/todo-implementation/owned-inputs/receipt.json。A2 保持部分实现。

普通入口继续实施：Python/混合入口已使用 RunningUnits 持有输入；IndexPlan 区分资源参与单元与可执行单元，Rust/Java 及 Python 混合语言启动按计划选择。单 Java IndexSetup 按 worker、输入的顺序释放。A3 的任务提交现经过应用层 query_normalization，内部注释有效参数与旧预检共用此入口；公开字段、TOML 和分页实参接入留待 C1，不声明注释查询可用。

A2 输入准备工厂：单 Java、Java/Rust、Python 混合及 prepared 统一调用 prepare_java_unit；prepared 的语言输入创建由类型化 Preparation 请求经 prepare_analysis_unit 执行，沿用原语言准备器、冻结用户配置与预算账户。44 项应用、Native prepared 7、普通三语言混合 1、prepared 静态配额 2，以及构建/clippy/fmt/差异检查通过。证据：target/todo-implementation/unit-input-factory/receipt.json。A2 仍待完整执行和清理生命周期验收，不标完成。

A2 执行清理推进：补齐 Worker 显式 shutdown/reap；四条执行路径在 fallible 执行体外持有输入并等待回收，启动校验、warm rediscover、基线准备失败接入同一回收边界；正常 Java 健康池保留。45 项应用及 Native prepared 7 已通过，普通产品/混合故障/Java 图回归验证中。尚不以此替代整个 A2 阶段验收。

A2/A3 收口审查：以上推进记录描述各自执行时点，当前状态以任务表为准。IndexPlan 决定资源参与者、可执行语言和 prepared 实例；公共执行层拥有跨语言编排，语言输入模块只准备自身。输入在 fallible 执行体外保留至显式 shutdown/reap，复用/确认均经公共单元终态，候选经同一质量门禁、PublicationFence 和单次 CAS。Java 健康池、Rust 增量、Python 保守全量及 prepared 降级是计划要求保留的策略差异，不继续为了统一文件形状改写。空闲检查改用 WNOWAIT，退出后停止监控，取消与协议错误清理在回收 leader 前结束所属进程组；Darwin zombie 的 EPERM 仅在确认自有 leader 已退出时按回收处理。真实 C ABI SIGSEGV、旧 head 保留、unknown 用量披露、worker 回收和恢复分析已验证。A3 的内部归一化、配置边界、窄读取端口和有效值冻结通过；公开请求/TOML/分页接入按原分工进入 C1。A2/A3 标为完成，不代表注释功能或分发验收完成。

C1 起步：新增独立 comment-search crate，固定 jieba-rs 0.11.0 及内置词典摘要，规则与词典参与 identity；索引 search、查询 default、HMM 开启。共享实现包含标识符拆分、关键词 AND、去重和原正文位置复核，以及 body_map 到捕获源码的 UTF-8/UTF-16 映射；接续调用方预算/取消。8 项专项覆盖中文、驼峰/缩写/数字/下划线、空/标点词、Unicode 小写展开、emoji、CRLF、转义非线性范围、坏映射与资源拒绝。尚未接入 Java 输出、存储或公开查询，不升级生产协议。

C1 Java 模型推进：Java 8 worker 的主 javac 扫描器录制真实 comment token，按编译器归属表将同一 token 关联到声明；支持空 Javadoc 和一条文档对应多个字段，避免 DocTrees 对空正文返回 -1 时按位置猜测。v51 请求/响应同时接入冻结源码范围、正文映射和原始声明语法身份；宿主核心没有编译器类型。语言侧建立有界范围索引，生成 documents、enclosing_callable、leading_statement、trailing_statement，lambda/初始化块阻断外层 callable，语句只在同块相邻且无空行时关联，并标为 heuristic。原 expected.json 的词法和关联预期直接接入真实编译器/模型断言；其中公开查询预期仍未运行。

当前 C1 验证：just test-java 718/718，通过包检查和来源清单检查；专项 18 项包含 17 项新注释测试及既有并行编译回归。Rust Java worker 9 项及 clippy/fmt 通过，默认 G1 项保持跳过；未用旧 Native SDK 作本轮产品验收。原完整 Java 门禁因独立编译测试入口缺模块导出参数失败，现通过共享工具链参数修正，原失败及空文档位置/测试编译失败日志均保留。请求沿用累计账户预付词法范围，保守流式估算覆盖字符串伪标记、块注释与 EOF 尾注释；没有可能注释的源码不录制，大文件短文档不预付整文件正文映射。来源提交保持原值，只更新迁移摘要和本地来源说明。证据：target/todo-implementation/java-comment-model/receipt.json。仅 javac 模型边界切到 v51，graph/worker/analysis/schema 等仍待 GRA1 与查询链路同步升级；已安装 Native SDK 为 v50，v51 Native 构建/安装、C1 余项及 C2—C4/A4—A7 均未完成。


C1 GRA1/存储/查询继续实施：源码新增独立 COMMENT/COMMENT_LINK 行与 schema 12/逻辑 35 表、FTS、显式 world 校验；Java 冻结原始 UTF-8 范围/映射并绑定真实声明 ID。查询已接入 CommentReader、search-comments、search-code --in、CLI/RPC/pipeline、项目上下文默认值及冻结页有效值；词典/分词身份进入语义 profile，展示参数保持独立。新增存储集成验证独立发布、检索顺序/分页、坏证据拒绝、旧 head 保留、历史读取、声明投影、pipeline 和高亮；配置专项验证 0/100/非法值与语义身份不变。当前源码版本为 worker 23/graph 5/analysis 12/GraphSemantics 27/javac-model-v51/物理12/逻辑35；Native SDK 重建和真实产品回归待完成，C1 仍为部分实现。原测试失败日志保留，不把协议测试样例漏升或生成代码遗漏写成产品通过。


C1 阶段收口：Java 提取→GRA1→独立表/FTS→CLI/RPC/pipeline 两类查询及上下文配置通过。完整 Java 719/719，0 失败/跳过；完整 Rust 门禁通过，最终补充的存储/查询测试、SQL VM 预算专项、构建和 clippy 通过；新 Native/JVM 真实查询各 2 项，Native ABI 生命周期 10、manifest 5、JVM bootstrap 2 通过。新 G1 Native SDK 与 JVM SDK 已安装，保留旧 SDK 备份；Native 为 build-only 开发包，不签分发、完整语义或性能放行。分词、SQL FTS 枚举、正文复核和源码输出使用原账户；覆盖 COMMENTS 独立于绑定覆盖；翻页冻结实际上下文。证据：target/todo-implementation/comment-c1-final/receipt.json。C1 标为完成，进入 C2；C2—C4/A4—A7 仍保留各自全部门槛。

C2 阶段收口：最终完整 Rust 门禁及 Native 生命周期 8+2 项通过，补充存储共 39 项与真实旧版游标摘要回归通过。SQL 枚举接入累计预算/取消；大正文按长度预准入；新建与保留注释核对捕获 owner/hash；旧规则 baseline 禁止增量复用。原混合默认 128 MiB Native 启动失败保留，生命周期采用既有混合矩阵固定 1 GiB，不宣称原预算容量通过。C2 完成，进入 C3；C4/A4—A7 保留原门槛。

C3 收口：固定 RA token 与 Ruff/Pyrefly AST 提取三语言公共契约，字面量转义映射、匿名作用域隔离、同块语句、独立覆盖与精确声明锚点均接入；不执行 doc 属性表达式或目标代码。Rust/Python producer 升至 comments v5，公共协议维持 C1 版本。三对语言及三语言、prepared 独立 reactor 发布均通过；Python 多 world 删除通过。prepared 初次运行缺 Maven PATH 导致 Java 降级，补齐已有工具路径后 7/7 通过；原失败日志保留。正式 Native/分发/性能仍未执行，进入 C4。

C4 收口：当前实际分发通过；共享资源矩阵 42/42 及包字节绑定的注释/组合 11 项通过，原执行字节恢复、SDK 未变。固定 fixture 五组命中/方法定位及完整性验证通过；仅一轮测量：索引 3.137 s，全工作负载 5.215 s，1 s 采样进程树峰值 269.30 MiB，图库 5.35 MiB。测量使用独立空 home、固定代码/产物/预算，OS page cache 不受控，不分冷热。

原失败保留：prepared 根显示配置触发 stale/full 已由绑定原始回执的准备指纹及受 profile 保护的 FILES 根配置例外修正；Python 每函数重复预付整文件 token 的超时失败已改为一次边界/范围索引，原预算下三个超时及全 42 项复验通过；Rust 合法 doc 续行警告不再误判 fatal；Java release 汇总固定组数遗漏已修正并重跑通过。128 类 fixture 的初始 100 万节点预算确实失败（worker 分片 749,806）；原回执保留，最终功能/唯一测量显式使用 1,000 万节点，不能作为默认预算容量通过。C4 完成，进入 A4；后续重构不复用本轮作为新版本性能认证。

A4 收口：`model::LanguagePolicy` 显式注册三语言能力，Java 派发/可实例化规则独立；Rust/Python 保留已发布静态绑定和部分证据，未知/缺失语言明确拒绝 Java 专属操作。存储补全移入独立语言策略模块，保留 Java 批量匹配顺序和唯一性；非 Java 精确 ID 仍直达，不推断注释关联。原 Rust 派发套用 Java CHA 的缺陷单列修正为 `rust-analyzer-static` 与显式 CHA/runtime 拒绝。workspace 321 单元、最终查询 34、Native 图 8、Python 产品 7、Java 注释 2、混合注释 3、最终 Rust 派发 1 通过；clippy/fmt/DDL 通过。原缺语言的 Java 单元样例和新增 scope 测试的歧义预期失败均保留，修正测试输入后通过。证据：target/todo-implementation/a4/receipt.json。A4 完成，继续 A5；A7 仍需最终分发/完整回归，不重跑 C4 性能。

A5 收口：Engine 仅装配任务服务和操作入口；任务表/队列/调度/终态属于 TaskService，索引接收 TaskContext 的本任务预算/取消/发布/事件，不再访问全局 jobs 或原始 Store。WorkerService 拥有健康 Java 池，HistoryService 与 ReportService 拥有各自存储；快照通过显式索引动作衔接，阻塞查询只捕获所需服务。RustBaselineReader 独立于公共基线端口；JavaLanguage/RustLanguage 与 RustAnalysis 分离，私有 wire 兼容层保持原外部格式及配置/prepared 四个固定摘要。Java 捕获主体迁移前后文本一致，公共冻结字节/spool 独立。完整 Rust 323 单元及产品/clippy、Native 资源/故障 42/42（无源码/产物漂移）、九组产品回归通过；真实 C ABI SIGSEGV 保留旧 head、回收 worker，并恢复 SDK 后成功分析。SDK 字节与门禁前一致。证据：target/todo-implementation/a5/receipt.json。A5 完成，进入 A6；最终分发留待 A7，不签新的性能或全局迁移认证。

A6 收口：Lombok 实现与资源移入 framework-lombok；生产 AnalysisSession 与诊断入口共用 FrameworkPlugins.selected 装配。扩展必须实现带 AnalysisContext 的回调，旧两参实现编译失败；生产入口复用原累计账户。真实产品回归曾发现生产选择器漏装 Lombok，已修正并补回归，原失败保留。当前 Java/Native 728/728；Native/JVM 综合各 23/23，11 组产品/ABI/G1/崩溃恢复门禁全部通过，SDK 前后字节一致。证据：target/todo-implementation/a6/receipt.json。原来源身份不变，8 项覆盖路径迁移；本机参考仓库缺固定提交对象，未宣称重新完成上游全量覆盖验证。A6 完成，A7 继续最终门禁、分发与注释缺口修正；不重测或重标 C4 性能。

A7 收口（2026-11-01）：todo 全部任务在本机 fixture 范围完成。证据：`target/todo-implementation/a7/receipt.json`。

| 项目 | 实际结果 |
|---|---|
| 架构 | Cargo 传递依赖、服务状态/语言编排、javac 源集、Lombok/Maven、生产预算门禁；7 个违规反例通过 |
| 单列缺陷修正 | prepared 不可用输入按冻结 roots/coverage 披露注释缺口；健康路径完整，历史 revision 不漂移 |
| 完整回归 | Rust 完整门禁；Python 核心 32、产品 7、注释 4、prepared 9；Native 综合 23、混合注释 3；G1 项显式通过 |
| Java 绑定 | Java 源码及 SDK 与 A6 当前 728 项验收一致；A7 仅改 Rust/测试/文档 |
| 实际分发 | Rust 矩阵 26、Python 矩阵 36、Java 包通过；release 共享资源 42、注释/组合 11 通过；原可执行文件恢复，SDK 字节不变 |
| 固定 fixture | 128 类、5 组功能查询通过；未重跑性能，C4 唯一测量只认证原产物 |
| 原失败 | 测试导入生成未列入清单的 pycache；保留失败包/差异/日志，修正导入后完整复验通过 |
| 保留边界 | iopengw 排除；固定上游对象缺失不冒充覆盖验证；跨平台/完整语义/容量稳定性未签全局放行 |

## 2026-11-08 注释规则收敛

当前只保留 documents/enclosing_callable，删除相邻语句关联、源码窗口及专用配置／参数。Java 同时支持冻结的 8／17 目标，SDKMAN 工具链匹配、分版本桥、JVM／Native 导航和故障验证已贯通。本机 fixture、Rust 完整门禁、三语言产品和三种实际分发已通过；共享 Release 资源矩阵 42/42、组合注释 11 项通过，原执行文件已恢复、SDK 未变。汇总回执：target/java17-plan-check/joint-receipt.json。历史回执与唯一旧测量保持原义，本轮没有性能或真实项目测量。
