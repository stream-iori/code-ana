# 跨链验收与真实项目

[入口](README.md) · [统一 TDD / 证据规则](contracts.md#5-tdd-与证据) · [来源映射](traceability.md)

**各调用链保存自己的测试；本页只维护共同测试工具、跨链门禁、iopengw 和最终测量。**

| 旧门禁 | 新入口 / 顺序 |
|---|---|
| V1 JVM 解析 | JVM-01～03、FW-01～02 组件；普通 JVM/JDK 25 + 目标 javac 8 |
| V2 Rust 独立 | IDX/QRY/INC 固定事实与 mock；无需 JDK，可与 V1 分别推进 |
| V3 JVM 全链路 | IDX-02 最小闭环 → 各能力真实联调 → ACC-02 完整 fixture → ACC-03 iopengw |
| V4 native | ACC-03 通过后 NAT-01/NAT-02；同输入、场景和预期，加 ABI/资源/生命周期 |
| V5 最终验收 | ACC-04：最终产物、测试入口收尾、三轮性能与容量、退出回执 |

已知上游问题单列，严格结果不改绿；迁移/对接失败、未完成/跳过的 JVM 用例不能放行 native。正常 TDD 只跑最小相关测试，阶段末才汇总完整门禁。

<a id="acc-01"></a>

## ACC-01 最小可信测试工具和覆盖清单

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：逐 case 回执、重复/遗漏/跳过/产物漂移判失败；上游覆盖清单未封板 |
| 行为 | 任何失败、缺环境、旧产物或漏执行都不能被新运行器报告为通过。 |
| 调用链 | 旧测试/上游 Test+IT+fixture/scenario 清单 → case ID/独立 expected → 公共运行器 → 分层回执。 |
| 开发前置 | 无；从最小工具和已有测试清单开始 |
| 联调前置 | 无；按本任务门禁执行 |
| 实施 | 盘点全部 Java/Rust/Python/just/scripts 用例、helper、fixture、差分、进程控制和故障注入；允许全量重写，逐项记录保留/合并/替代/有理由归档。先做足够运行一个 JVM 和一个 Rust 场景的最小 Python stdlib/unittest 工具，后随能力补齐；不要求先重写完全部测试。Maven/JUnit、Cargo 继续各司其职。 |
| RED / 基线 | 工具自身先验证 success/断言失败/blocked/timeout/截断/坏摘要/漏指定 case/子用例失败；独立手工相同/不同样本验证差分。检查只跑 mock、旧 JAR、回执与二进制不符、产物被覆盖不能判真 JVM 通过；纯清单无需伪 RED。 |
| 完成条件 | 稳定 case ID 对账覆盖 Test/IT/分组/资源/工作目录；目标零执行或部分漏执行不通过，合法空模块单列。passed/failed/blocked/skipped 与 known-issue 分开；源码/产物身份绑定。最小工具完成后各链滚动迁移，最终旧用例闭合由 ACC-04 审计。 |
| 追溯 | A10/A11；T0/T1；旧 03 测试资产/重写计划 |

<a id="acc-02"></a>

## ACC-02 完整 JVM fixture 与 Rust 独立门禁

| 项目 | 落地要求 |
|---|---|
| 状态 | 当前功能门禁通过：完整JVM汇总02封存组件654/654、探针17/17、产品58/58；Rust工作区163项、产品17项及新增文本/注释6项通过。原来源覆盖闭合仍由ACC-01/04审计，尚有148份来源/741项原测试及动态工厂待处理 |
| 行为 | 组件分别正确，真 CLI→JVM→Rust 发布/查询的计划能力也完整。 |
| 调用链 | 上游 Test/IT/fixtures/e2e/tests-scenarios + Rust 固定事实 → 真 JVM CLI → 库/公开查询 → 覆盖回执。 |
| 开发前置 | ACC-01、JVM-03、IDX-02、IDX-03、IDX-04、QRY-02、QRY-04、QRY-05、FW-03、INC-03 |
| 联调前置 | 无；按本任务门禁执行 |
| 实施 | 按 traceability 的 A/S/旧章节核对覆盖，汇总字段映射、原模型/最终图、查询、框架、增量和受影响故障；先确认 Rust 无 JDK 门禁，再汇总 JVM 组件和全链。Rust 可提前独立完成。以 SemanticOperationRegistry、命令校验和实际注册能力为规格。 |
| RED / 基线 | 各链 RED/GREEN/基线回执进入整套；集成失败先最小 fixture 再修桥/Rust。检查器先以缺索引、错列序、丢 owner、悬空目标、陈旧投影/FTS、错范围/过滤、漏页重复页等反例自证失败。 |
| 完成条件 | 范围内 fixture 实际执行；原模型、提取、Fact IR、发布图、公开结果分别判定；SQL 正确性和完整性通过。既有生命周期复用，本次变化才补 TDD；未完成/blocked 不通过。真实项目交 ACC-03。 |
| 追溯 | P1/P2/P3；T2/T3；V1/V2/V3 fixture；旧 03 §2/§3 |

<a id="acc-03"></a>

## ACC-03 冻结 iopengw JVM 全链路

| 项目 | 落地要求 |
|---|---|
| 状态 | 已验证：完整JVM汇总02的15份回执/4223条摘要证明通过；40模块冻结输入、160模型、14图域、公开查询198+21+19+7、只读审计、四阶段已提交历史和重启读取通过，零未解释迁移失败；严格参考相等仍为false，精确上游缺陷独立保留 |
| 行为 | 真实工程的逻辑图、物理结构、公开查询和增量历史均有可追溯结论。 |
| 调用链 | 冻结输入/独立参考 → 真 JVM 索引 → 同 revision I0–I8/Q1–Q10 → 回执。 |
| 开发前置 | ACC-02 |
| 联调前置 | 无；按本任务门禁执行 |
| 实施 | 候选运行前冻结输入和查询清单/预期；先 fixture 再按下方矩阵跑真实项目。检查工具可提前准备，实际门禁等 ACC-02。只读共用 revision，写式 FTS/维护用一致私有 backup。 |
| RED / 基线 | 使用已自证检查器；保留真实失败后缩成 fixture，修复后重跑受影响域及最终门禁。不只挑一个刚写入的符号，也不编辑基准 HEAD。 |
| 完成条件 | 输入/结构/全量事实/所有 Q 类/全部分页/覆盖/重启/历史/增量分项回执齐全；partial/缺依赖/不适用/失败分开，无未解释迁移失败或遗漏。I8 留查询计划和本轮容量证据；最终性能完成前不得将 V5 判通过。此 JVM 门禁通过才构建 native。 |
| 追溯 | P3/T3/V3 真实项目；I0–I8/Q1–Q10 |

<a id="acc-04"></a>

## ACC-04 最终产物、测试入口收尾与性能

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：完整JVM汇总02、native汇总01已通过；正式冷/热各3轮测量已启动。原覆盖148份来源/741项测试及动态工厂、旧入口收尾、三轮存储测量与最终完整回执尚未关闭 |
| 行为 | 交付版本具备完整能力、测试替代、JVM/native 和性能容量证据。 |
| 调用链 | 最终源码/产物封存 → 正确性核对 → 三轮独占测量 → 覆盖/退出清单 → 文档同步。 |
| 开发前置 | NAT-02、ACC-03 |
| 联调前置 | 无；按本任务门禁执行 |
| 实施 | 关闭旧测试去向，切换 just/scripts 后才删已覆盖脚本和过期目录/协议/native 硬依赖；保持 Rust 无 JDK、JVM 不自动 native。按下方口径测量，记录无收益/退化实验；后续产品实施同步 architecture/contracts/build/能力文档实际状态。 |
| RED / 基线 | 核最终 SHA/回执，不挪用旧运行；正确性和可重复瓶颈先固定，再单项优化并重跑受影响回归。上游失败保留严格状态和独立复现。 |
| 完成条件 | 退出表各项有对应版本结论，旧覆盖不静默消失，JVM/native 分开汇总；至少三轮原值/中位数、冷热说明、空间/写入/查询取舍齐全。失败版本不能宣称优化完成。 |
| 追溯 | P5/T5/V5；S6；旧 03 §4/§5 |

## iopengw 专项矩阵

**iopengw 验证真实工程规模下的索引结构、事实和查询，是验证手段之一；上游测试、fixture、Rust 独立验证仍各自必须完成。** 现有 `java_real_acceptance.py` 的发布后检查只选一个声明执行 `structure`；现有模型及 14 域语义差分可提供基础，但不能替代下述索引和查询矩阵。

#### 输入与执行规则

- 基准固定 `267c7ced4993aa88613f8f17a8099943fec98159` 的已提交内容，记录实际 Git tree、源码/资源 hash、Maven 输入清单、模块/scope、依赖顺序、Lombok 配置和工具链；不沿用历史文件数作为当前硬编码预期。
- 枚举所有纳入分析的模块与 MAIN/TEST 上下文，核对发布源码、辅助源码、XML、排除项、空上下文和缺失输入；每项有纳入或排除依据。缺依赖如实披露，不自动运行目标构建补齐。
- V3 在 JVM 全链路先执行；V4 复用完全相同输入、查询清单和语义断言。各自使用独立 home/产物回执，避免误用旧安装包或发布库。
- 索引结构包含“语义事实图的逻辑结构”和“SQLite 表、索引及投影的物理结构”，两者分别验证。数据库表名/布局不要求与 Anatomist 一致。

#### 索引与生命周期矩阵

| 编号 | 验证项 | 对照与通过标准 |
|---|---|---|
| I0 输入完整性 | 捕获清单、模块/world/scope、源码与资源 hash、辅助/发布输入、配置及依赖 | 对照冻结 Git tree 和有效输入清单，无漏捕获、串模块或未说明排除 |
| I1 物理结构 | schema/Fact IR 版本、表/列、主键、索引列顺序和条件、FTS/触发器、统计信息 | 对照本版本 Rust DDL/迁移契约；新查询投影和依赖结构具备完整版本信息 |
| I2 图结构 | 声明父链、owner/world、来源范围、call_site/target、类型关系、引用、注解、Bean/XML | 全量检查身份唯一、合法所有权/端点、父链约束和范围；保留合法多重出现与 unresolved，不靠删除坏记录通过 |
| I3 原始模型与事实 | 按上下文对照 raw/Lombok 模型；按域比声明、调用、类型、字段、注解、框架、XML、DI 和诊断 | 原始模型与冻结参考、投影与独立映射对照；全量分组摘要定位后逐条差分，不以总数相等或少量抽样宣称完整 |
| I4 存储完整性 | SQLite integrity、事实与查询投影、反向依赖、FTS 同步 | 对照独立扫描/重建结果，无悬空端点、陈旧投影或漏项；FTS 写式检查只在一致私有副本执行 |
| I5 coverage/provenance | 每个发布 owner 的能力覆盖、零事实/失败文件、诊断、producer/profile/输入身份 | 每个纳入输入均有可解释结果；partial、未知、未请求、截断分别披露，发布成功不消除语义缺口 |
| I6 发布与持久读取 | manifest/profile/revision/catalog 一致、daemon 重启后查询、读取旧 revision、分页与快照引用 | 发布后数据可独立读取，固定版本不随新发布漂移；读者/快照/分页保护有效 |
| I7 增量与历史 | 未改动冷重跑、已提交历史变更对、删除/修改的 owner 更新、full/delta 与历史源码 | 相同目标输入/profile 的 full/delta 事实及查询相等；旧 revision 源码 hash 与结果保持不变 |
| I8 查询计划与容量 | 热点查询计划、读入/解码量、索引成本、库体积、临时峰值与复制成本 | 正确性先通过，再报告综合成本；必要扫描不误报，历史运行耗时不冒充本版本性能 |

图结构的引用校验依据 Fact IR 语义，不能用空 target 表示的合法 unresolved 误判悬空引用。部分关系本身允许环，不能对整个图施加树约束；仅声明父链等具有无环契约的结构检查环。

#### 查询矩阵

所有查询通过真实 CLI/daemon 执行，固定 revision，记录完整参数、stdout/stderr、退出码、终态证据及全部分页结果。数据库直读用于独立核对，不能代替公开查询验收。

| 编号 | 命令/场景 | 核对内容 |
|---|---|---|
| Q1 | search-code / resolve | 精确签名、重载/同名歧义、kind/package/path/注解、module/scope/world 筛选；预期 ID 集、唯一性及错误语义 |
| Q2 | structure / source | 文件、类型、成员、嵌套/合成声明、可见性、声明/site/owner、完整方法体、行范围、注释；父子结构与捕获源码 UTF-8 字节对应，无源码对象明确披露 |
| Q3 | calls / call-sites | 正反向、重复调用、构造器、lambda/方法引用、外部与未解析调用；关系去重和每次现场分别正确 |
| Q4 | references / annotations | 字段/类型/配置引用、读写类别、出现位置、直接/元注解路径；跨模块 incoming/outgoing、内部/外部目标的范围语义及出现次数一致 |
| Q5 | type-relations / callable-relations | 继承、实现、覆盖、正反向与传递深度；与冻结参考关系和源码声明一致 |
| Q6 | runtime-implementations / dispatch / trace | exact/CHA、分析世界、possible 候选、起终点路径和深度；证据标记正确，路径每一跳有合法调用证据 |
| Q7 | 框架事实的公开查询 | 用现有/计划对齐命令检索 Spring/SOFA、Bean、XML 配置与成员绑定、DI；候选和静态注册不冒充运行时确定调用 |
| Q8 | overview / search-text | 模块/包/依赖计数与独立 SQL 聚合相等；文本字面/正则/路径/大小写/上下文与捕获源码对照 |
| Q9 | pipeline / 分页 / 边界 | resolve→call-sites→dispatch、关系端点→source、非法组合、空结果、多页续读；类型传递与缺口披露正确，跨页无重复遗漏 |
| Q10 | 历史 / 重启 / 增量后查询 | 固定 revision 的同一组查询在重启、发布新版本后仍一致；变化仅出现在应变化的版本中 |

#### 选例、预期与覆盖

- 在候选实现运行前，从冻结源码和独立 Anatomist 参考结果选择并封存查询 case 清单，禁止从候选产物“挑一个查得到的符号”作为唯一验收。
- 每个有发布源码的模块/scope 至少固定一组声明解析、结构和源码读取用例；各 Q 类覆盖真实存在的典型和边界形态，包含高扇入/扇出、多页、多候选及合法空结果。按模块、scope、事实类别列出覆盖情况。
- 清单记录 case ID、源码路径/签名/位置、参数、预期结果或其独立摘要、排序/多重集规则、证据依据与预期 partial；ID 表示差异采用已审计映射。
- iopengw 中不存在的语法或边界形态明确记为本项目不适用并指向对应 fixture；项目中存在但产品未支持、解析失败或未执行的 case 不得标为不适用。
- 全量模型/事实对照与代表性查询矩阵分别报告，后者不声称穷举所有参数组合。明确排序的查询比较顺序；没有唯一排序承诺的多候选/等价路径按契约验证，不强迫唯一字符串路径。
- iopengw 已知的上游缺陷、缺依赖和 partial 单列；不能只判断退出码为 0、结果非空或抽样几条相等。

#### 增量、TDD 与产物

基准 HEAD 永不编辑。真实变更验收从仓库已提交历史选取可复现的基线/目标对，登记两端 commit 和输入清单；每端 full 与对应 delta 使用相同工具链和 profile。无可用历史或依赖输入时记录受限，受控修改/删除用例由独立 fixture 覆盖，不将人工修改冒充冻结 iopengw 结果。未改动的冷重跑与历史读取仍在固定 HEAD 上验证。

按 TDD 先验证检查器本身：构造缺索引、错索引顺序、悬空目标、丢失 owner、陈旧投影、错误范围、漏页/重复页、错误 module 过滤的小型反例，确认检查器失败；再实现检查器与产品修复并回归。iopengw 暴露新问题时，先保存真实失败，提炼最小 fixture 复现，修复对接/Rust 后重跑 fixture 和受影响真实域；最终门禁绑定最终产物，旧版本回执不能直接晋升。

后续输出统一保存至独立验收目录，按运行/backend/revision 分开：

| 产物 | 内容 |
|---|---|
| `inputs.json` / `artifacts.json` | 冻结输入、工具链、参数、产物 SHA、运行后端与版本 |
| `index-structure.json` | I1/I2/I4 的物理结构、逻辑不变量、投影/FTS/依赖核对及错误定位 |
| `model-fact-diff.json` | 各上下文/域完整数量、摘要、逐项差分位置、诊断及已知上游缺陷 |
| `query-cases.json` / `query-results.json` | 预先冻结用例、来源、全部分页结果、参数映射、Q1–Q10 结论 |
| `lifecycle.json` / `storage-metrics.json` | 重启/历史/增量/引用保护结果，以及查询计划、时间、内存和空间 |
| `receipt.json` | I0–I8/Q1–Q10 分项状态、覆盖分母、未执行项、已知问题和严格门禁结论 |

结构、事实、查询、生命周期、性能分别判定；失败、blocked、跳过、项目不适用均与 passed 分开汇总。性能测量仍按三轮独占要求执行，不因 iopengw 功能检查结束而自动标记通过。

## 性能与容量口径

| 指标 | 记录方式 |
|---|---|
| 入库 | 归档校验、去重、导入、建索引、派生、ANALYZE、完整校验、发布分别计时 |
| 查询 | SQL 计划、耗时、读入行数、JSON 解码量、批次数；必要时记录首屏和全部结果耗时 |
| 内存 | daemon、Rust worker、JVM runner 或 native、javac 及进程树峰值分别记录，标明后端 |
| 空间 | 每表/索引、空闲页、正文与 facet、临时产物峰值、归档接管前后空间 |
| 增量 | backup 时间/字节、替换事实数、索引维护与最终发布耗时 |
| 源码缓存 | 新写与复用数量/字节、完整性校验和回收成本 |

执行口径：

- 同机器、同输入、同能力范围、同资源预算；每种可比配置至少三轮独占运行，报告中位数和各轮原始值。
- V2 可先以事实回放验证存储优化；完整链路性能按 JVM/native 分开记录，最终 native 性能验收放在 V4 正确性通过之后。
- 冷/热分别记录。明确冷的是应用/派生缓存还是 OS 页缓存；无法控制的状态如实说明。
- 使用冻结事实回放隔离存储成本，再跑真实 Java 全链路；不能把 javac 差异全算成存储收益。
- Anatomist 与 Rust 原始事实范围不同的部分单列；不直接比较整库大小或总时间宣称倍数收益。
- 正确性相同后报告速度、内存、磁盘取舍；新增索引导致体积增长必须一并披露。
- 新优化只有实测收益且目标查询无未解释退化才进入默认路径；否则记录无收益/退化实验，保留原实现。

## 统一退出条件

| 门禁 | 独立结论 |
|---|---|
| 源码复用 | 完整能力清单、源码/测试/fixture 来源、边界适配可审计，无解析规则重写 |
| 测试体系 | 全部旧用例有去向，新运行器/差分工具有自身验证，入口已切换，关键覆盖无静默丢失 |
| TDD 执行 | 新行为/修复有真实 RED→GREEN→REFACTOR 证据；已有通过用例记基线，不伪造 RED |
| 解析一致 | 原始模型和提取结果与冻结参考一致；已知上游缺陷单列 |
| 投影完整 | Fact IR 保留身份、范围、原始证据与次数 |
| 发布成功 | 完整校验、耐久化、CAS、恢复和历史读者正确 |
| 查询对齐 | 命令能力、参数映射、语义和 pipeline 覆盖完成 |
| 增量正确 | 冷增量及可选缓存路径与全量等价 |
| JVM 放行 | V1/V2/V3 已验证，真实 JVM 全链路通过后才进入 native 阶段 |
| native 等价 | V4 复用相同输入与断言通过，新增 ABI、资源和生命周期专项通过 |
| 性能/容量 | 对应最终产物的独占测量与空间报告完成 |

回执按版本保存，不覆盖原失败。参考失败、产品失败、输入不一致、工具读取错误分别记录；允许披露“迁移范围完成但上游缺陷仍在”，不能把严格全量失败改成通过。

2026-10-10 本轮补充：`jvm-components-15/receipt.json` 226/226 无漂移；`jvm-product-06/receipt.json` 52/52 实际执行，18 项失败且无漂移。XML 父链局部 RED/GREEN；元注解来源 `annotation-provenance-red-01.log` → `annotation-provenance-green-01.log`；重复类型引用 `relation-multiplicity-red-01.log` → `relation-multiplicity-green-02.log`（首次 GREEN 编译失败保留）。独立冻结最终图 `type-graph-reference-01/index.db` 23 个 REFERENCES，对应产品先前 22 个，不能降低预期；新组件和集成整套复验中。旧恢复类型/owner/补充证明字段由原源码与 `recovery-contract-proof-02/04/receipt.json` 取证校准，保持目标、possible、诊断及 full/delta 检查；不把没有原 producer 的字段补造成编译器证据。

新真实项目入口支持显式 `--backend jvm`、当前私有 runtime 与 `--freeze-receipt`。Git tree 字节校验 `iopengw-freeze-verify-03.log` 覆盖 5039 文件/4217 Java；`iopengw-source-git-paths.json` 使用 Git 的精确路径，并披露本机 case-insensitive 文件系统的一处目录大小写别名。MAIN/TEST 的 40 个模块输入均已准备，实际索引仍需等待 ACC-02。

最新组件 `jvm-components-19/receipt.json` 230/230 无漂移；`jvm-reference-10-receipt.json` 独立 17×14 零差异；`type-reference-independent-14.log` 13/13 独立关系一致。索引检查器 `scripts/audit-java-index.py` 在 fixture 11 项自测中识别缺索引/错列顺序/端点/owner/world/父链环/投影/FTS/依赖缺失，`audit-index-product-02/receipt.json` 验证实际 CLI→JVM 的 schema 4 发布库，检查前后 SHA 相同且源码无漂移。包装脚本首次重复计算输入清单，已主动 SIGINT 中止（退出 130），修复后独立目录重跑；该中止不是安全 SIGKILL。旧 product-08 与最新定向 product-focused-11 仍运行，不作为完整 JVM 放行。

### 2026-10-10：模型采集暂缓项

- 原 `iopengw-jvm-models-01` 已主动 SIGINT 暂停，完整门禁未通过，已有通过/失败证据保留。
- 优先修复空目标多余依赖编译与解码预算；修复版先运行非 `app/test` 的 156 项。
- `app/test` raw/AST × MAIN/TEST 共 4 项为明确暂缓；修复后补跑，不能把 156 项作为完整 160 项放行。
- 资源预算统一 2 GiB 的参考覆盖层单独记录来源和 SHA；不能冒充冻结参考默认 512 MiB 的运行。

修复回归完成：`jvm-components-24` 241/241，`rust-workspace-11` 136 项，Clippy 与 JVM 启动校验通过；真实 CLI `java-decode-budget-cli-03` 通过（低预算拒绝并保留 HEAD、2 GiB 发布、默认预算事实等价）。非 `app/test` 的 156 项采集已启动：`iopengw-jvm-models-02`；其余 4 项明确暂缓，完整门禁仍未通过。

### 2026-10-10：剩余四项继续推进

| 剩余项 | 本轮进展 | 状态 |
|---|---|---|
| JVM 能力与 fixture | 39 个冻结上游测试文件原样合入，正式 reactor 执行新增 246/246（`jvm-upstream-formal-01`）；覆盖清单 511/991 文件已复制/边界适配。诊断 50004 次保留、重启归属通过；doctor 显式 JVM 后端/共享 adapter 回归通过。回调词法归属、局部/匿名类稳定身份、项目 XML 合并目录局部 32 项通过；隔离新源码完整组件 `jvm-components-25` 运行中。 | 进行中，未完整放行。 |
| 冻结 iopengw 验收 | 旧完整发布库上的 198 项声明/结构/源码查询为 196 通过、2 项身份编码差异；14 域语义审计发现回调归属、同名构造器以及 XML 候选差异。模型采集保留失败证据并继续，其 4 个 app/test 用例仍待补跑。 | 未关闭；这些旧发布库诊断不代表最新源码验收。 |
| native | 修正 Maven native profile、共享 bridge 路径、worker 版本和发行资源清单；三项元数据验证通过，不加载 native 代码。 | 实际构建/运行等待 JVM 完整门禁。 |
| 覆盖、性能和入口 | Rust 全套与 Clippy 通过；文本和聚合 CLI 回归已纳入常规测试。 | 覆盖映射、最终性能和 native 发行验收仍待完成。 |

`iopengw-index-audit-01` 的 revision 参数误传 ID，已主动中止，只读库未改；`iopengw-index-audit-02` 用实际 revision.json 重新验证。该中止不是安全拦截。

最新隔离组件 `jvm-components-25` 为 490/490，无源码/产物漂移；`jvm-product-11` 使用该新 bundle 和当前 Rust 二进制执行完整 54 项 CLI fixture。冻结旧发布库的 198 项公开查询已全部通过（`iopengw-jvm-queries-02`），匿名/局部类按 457 个独立源码跨度验证身份映射，零未匹配，不要求旧身份编码兼容；仍不是全部 Q1–Q10，也不是最新 bundle 的完整真实项目放行。

修复回归后，四个 `app/test` raw/AST × MAIN/TEST 已排入串行补验：`app-test-models-queue-01.json`，等待非 app/test 模型采集释放后执行，并校验同一 bundle SHA。原固定 bundle 在队列结束前保留不变。

物理审计 `iopengw-index-audit-02` 因注解依赖的 JSON 签名连接未使用索引而主动中止；EXPLAIN 证明旧计划重复扫描声明 × 注解。改用已校验投影的 signature 索引，provider/world/path 判断不变；`audit-annotation-index-green-01` 与 `index-audit-tests-16` 的 15 项回归通过。`iopengw-index-audit-03` 已重新运行；尚未判通过。

最新只读物理审计 `iopengw-index-audit-03.json` 已通过，errors=[]，库前后 SHA 一致；这是旧已发布库的物理完整性证明，不作为最新语义或性能放行。完整新 JVM fixture `jvm-product-11` 仍运行，模型采集继续保留严格失败。

当前 bundle 的探针 `jvm-probe-current-01` 为 16/17：扩大 XML 声明目录后 MAIN 消费者错误纳入 TEST 同名 Bean。修复后保留全项目 Java 声明查找，Bean 名称继续受模块/MAIN/TEST 资源可见性限制；隔离组件 `jvm-components-26` 中 AnalysisSession 32/32 通过，整套仍运行。`jvm-current-gates-queue-01.json` 串行排入修复后的 17 项探针与 54 项 CLI fixture，Rust 二进制来自已通过的 release 构建，产物身份单列；没有提前运行 native。旧完整 CLI fixture `jvm-product-11` 的大容量用例已通过：243140 条事实、368755204 字节正文、进程树峰值 1095565312 字节，full/no-op/delta/full 图等价；共享机器时间不作为性能门禁。

`jvm-components-26` 490/490、`jvm-probe-current-02/03` 17/17，通过 MAIN/TEST Bean 可见性修复。完整 `jvm-product-11` 发现纯 XML 消费者因空 Java 目标快路径丢失辅助声明目录：构造器退成 p.Api#Api() 占位；原失败保留，`xml-only-constructor-red-02` 在正确安装布局独立复现，red-01 为诊断路径误用。共享 session 已为资源消费者显式请求辅助目录，保持 publish=false；新增正式 Java 用例验证不发布 Java 声明而构造器/方法绑定保留。`jvm-components-27` 与 `jvm-current-gates-queue-03` 负责当前源的组件/探针/定向 XML/完整 54 项 CLI。前两个等待队列因产物更新主动替换，仅终止无子任务的等待进程（SIGTERM），未中止正在执行的 fixture；不是安全 SIGKILL。当前完整门禁仍未通过。

### 本轮新增公开查询与最新门禁

| 项目 | 实际回执 | 结论 |
|---|---|---|
| 上一版完整 JVM CLI | `jvm-product-current-03` | 54/54，通过；不含随后新增的公开查询修复 |
| 新版完整 Java 组件 | `jvm-components-28` | 492/492，零遗漏、跳过、重复和漂移 |
| 新版 XML/元注解公开入口 | `jvm-public-query-focused-02` | 3/3，通过；包含名称/签名、源码、配置树、多路径元注解、全部分页和 pipeline |
| 新版 worker 探针 | `jvm-probe-current-05` | 17/17，通过 |
| 新版完整 JVM CLI | `jvm-product-current-04` | 55 项执行中，未放行 |
| 真实工程代表查询 | `iopengw-representative-queries-05` | 冻结旧发布库 + 新查询二进制，20/20；仍需最新发布库和完整矩阵 |
| 模型产物复用依据 | `model-producer-equivalence-04.json` | 新 bundle 的原 Anatomist/Core、SDK8 bridge 与见证器逐类内容一致；仅未被模型见证器依赖的桥类变化；不晋升完整模型门禁 |
| 构造器同名方法冲突 | `constructor-identity-issue-02.json` | raw/AST 各 11 域完全相同，另外 3 域各恰好一条、仅身份字段变化；参考构造器与合法普通方法同 ID，产品分别保留。严格两项失败保持失败，已知上游问题单列 |
| 新版完整真实发布 | `iopengw-publication-queue-03.json` | 等待完整 JVM CLI 55 项通过；独立源码、运行时、项目副本 `jvm-verification-root-02` |

旧等待队列 `iopengw-publication-queue-02` 在尚未启动发布时受控 SIGTERM（PID 80784），原因是需纳入新查询修复；不是安全 SIGKILL，没有中止索引或模型采集。所有未执行的真实项目增量/历史、native、覆盖封板及最终三轮性能仍保留待完成。


### 继续原 updated plan：四项尚未关闭

| 剩余项 | 最新落地 | 后续门禁 |
|---|---|---|
| JVM 能力与整套 fixture | 公共配置实体、元注解、引用筛选之后，继续补可实例化 entity、传递层级、接收者约束/默认方法派发、exact/CHA/analysis-world。Java 组件 492、Rust 查询 22、Rust CLI `rust-cli-suite-06` 17/17，真实 runtime 局部用例 1/1。 | 全能力的最终完整 fixture 与公开参数矩阵。 |
| 冻结 iopengw | `iopengw-jvm-models-02` 已结束：156 执行、154 通过；仅 raw/AST 的同一个上游构造器 ID 冲突，源码/配置无漂移。app/test MAIN raw/AST 均通过，TEST 正在补跑。 | 汇齐 160，保留 strict failure 与精确已知问题；最新发布、14 图域、物理审计、全部查询类别与真实增量历史。 |
| Native | 薄 ABI、manifest/资源配置准备及混装校验已有。 | JVM 全链路通过后构建，再跑生命周期、fixture、真实项目及 JVM 等价。 |
| 覆盖与性能/入口 | 增加 `rust_product_gate.py` 和语义检查器反例，实际 case ID / 执行数 / SHA 检查。 | 原源码/用例逐项映射、正确测试入口、最终产物的三轮独占性能。 |

完整 fixture `jvm-product-current-04` 未指定私有目录的独立参考 manifest，2 项未进入语义比较；保留执行记录，已用受控 SIGTERM 停止失效的旧运行及未启动的发布队列。新 gate 在昂贵测试前验证独立参考 JAR/源码/工具链，并把它们加入前后 SHA；`jvm-probe-current-06` 17/17，`jvm-product-current-05` 使用明确参考继续执行。旧运行不记通过，没有观察到安全拦截导致的 SIGKILL。

`rust-cli-suite-05` 把 release 二进制放在私有 target/debug 下，导致路径判断误认故障注入能力；其失败保留。`rust-cli-suite-06` 使用真实 debug 构建，17/17（包括提交边界故障）通过，零遗漏/漂移。私有真实工程副本最初误排除了名为 cache 的业务包，发布前已补齐 50 文件；`iopengw-publication-source-copy-01.json` 记录逐文件一致和零遗留漏项。

`java-jvm-project-semantics.py` 将独立新参考的 14 域验证改为正式入口；`jvm-project-semantic-negative-controls-01` 验证拒绝未完成发布、运行时/输入漂移，并拒绝构造器已知问题之外的字段/出现次数/身份差异。不扩大已知问题豁免。


160 项原模型已汇齐：`iopengw-jvm-model-inventory-01.json` 逐项复核三份运行的 case ID、14 域、进程状态、捕获输入、编译 witness 与全部 proof SHA；158 相等、2 个精确上游已知问题、零未解释失败。`strict_model_gate_accepted=false`，不把修复后的构造器身份说成与旧缺陷相等，也不据此单独放行 native。app/test MAIN raw/AST：迁移 6.456 / 8.719 秒；TEST raw/AST：199.132 / 70.876 秒，四项均通过 2 GiB 预算。耗时包含并发验收，不作为最终独占性能。root JVM model bundle 保护已解除。

最新 `jvm-query-focused-03` 8/8，零漂移；query 22/22 与 `rust-clippy-24` 通过。已冻结 stage11，并排队 `jvm-current-gates-queue-06`（完整 57 项）及对应私有发布 `iopengw-publication-queue-05`。旧 stage10 的整套仍完成自身验证；尚未启动的 stage10 发布队列受控 SIGTERM 替换，所有旧回执保留，不混作最新运行。


最新完整 JVM 门禁已通过：`jvm-current-gates-queue-06.json` 绑定同一 Java 492 项组件源码，probe 17/17、真实 CLI fixture 57/57，零遗漏/漂移。独立扩展查询 `iopengw-query-matrix-04` 19/19：预先冻结参考种子；QueryWitness 仅观察独立原 JAR 的 runtime/callable/dispatch API，六种深度/可实例化组合、exact/CHA 与 product 比较；模块/包/折叠/依赖双向计数用原行 Counter，文本字面/大小写/正则/上下文用捕获字节独立核对，全部分页。该轮仍用旧发布库，最新私有发布 `iopengw-jvm-publication-05` 已启动，之后串行进行最新图域、物理审计和查询复验。

汇总模型的当前有效回执为 `iopengw-jvm-model-inventory-02.json`：01 在补齐从 unittest 导入所需脚本路径后被新回执替代，历史文件保留。`jvm-project-semantic-negative-controls-03` 3/3，验证缺/重复 case、false green、异常信号、未知失败、输入漂移和精确已知问题边界。`runtime-original-cli-01` 为原 CLI text/v5 观察；正式预期使用 QueryWitness 的原 API，未改原查询规则。扩展工具前两轮的选例要求/二进制字段 JSON 问题保留日志，19 项当前结果以 04 为准。


`iopengw-lifecycle-queries-03` 7/7 通过：独立参考预选最短两跳路径，逐跳核对调用出现次数与 call_site 证据、深度截断披露、resolve→call-sites→exact dispatch 全部分页、确认空与非法参数、daemon 重启后固定 revision 路径不变。仍使用旧发布库，不能代替最新发布/真实变更 full-delta。01 是检查器选例 JSON 的二进制字段错误；02 使用了不支持的阶段级 module/scope 字段，均保留失败记录，03 改用公开全局筛选。最新 debug 构建 `rust-debug-build-07` 与 `rust-cli-suite-07` 17/17、零漂移，覆盖当前查询参数及提交边界故障。

真实历史从原仓库 first-parent 已提交变更独立选取并 `git archive` 封存到 `iopengw-history-inputs-01`；不编辑原仓库或冻结 HEAD，正在分别导出两端 Maven 输入，未沿用 HEAD 清单冒充旧提交输入。历史对及 native/最终三轮性能尚未完成。

最新私有真实发布 `iopengw-jvm-publication-05` 已成功：1005.189 秒、无异常信号，配置/源码/产物漂移以其回执为准；14 图域严格对照已进入执行。该耗时不是三轮独占性能。补入 5 个未迁移的原始二进制测试类（ClassFileSource、JavaBinaryModel、JavaBinarySession、PackedClasspathIndex、TargetJdkBinaryRepository），共 26 项，原样源码已登记 `upstream-sources.json`，在私有 stage12 跑完整组件；未改当前发布的生产源码/产物。

最新库物理审计 `iopengw-index-audit-04` errors=[]；全部公开查询 `iopengw-jvm-queries-03` 198/198、`iopengw-jvm-representative-queries-06` 21/21、`iopengw-query-matrix-05` 19/19、`iopengw-lifecycle-queries-04` 7/7，通过。14 图域 `iopengw-jvm-semantics-02` 严格失败：calls/declaration 为一个新增构造器（原数据库 ID 冲突），lexical_calls 丢 7 条控制上下文，XML wiring 多 263 条派生重复，XML 调用/完整图差异仍需进一步核对。保留 strict failure，未放行 native。

`jvm-components-29` 518/518，新增原二进制 26 项全通过。`unresolved-control-context-red-02` 真实复现上下文缺失（red-01 的总事实数断言先误计外部声明，已纠正并保留）；green-01 通过。`xml-wiring-double-count-red-01` 复现同一 XML 引用被上游和 Rust 派生各计一次，green-01 通过：按 world/source/endpoints/行号逐条消耗匹配，合法重复引用仍保留，派生 profile 升为 6。`xml-bean-identity-red-02` 用两个 XML 文件复现 Bean 目录的 qualified/raw ID 冲突；同文件初例通过，仅属覆盖；已修正资源边界的原始 Bean ID，AnalysisSession 全套正在回归。当前源仍需最新组件/fixture/真实库复验，不能挪用上一版回执。

`jvm-components-30` 520/520、`jvm-components-31` 521/521，通过（新增未解析上下文、跨 XML Bean 身份、重复 XML ID 按位置计候选三个桥用例）；`model-producer-equivalence-05` 证明最终桥变化未改原模型 producer/SDK8 bridge/模型 witness 的依赖类，不晋升 strict model equality。`xml-ref-occurrence-count-red-01` 复现两个同 ID、不同位置的 XML 引用各被算成两个候选；green-01 按来源和起始行关联，两个 occurrence 均保留。

公开回归 `jvm-public-xml-focused-03` 1/2：原 XML wiring 全套通过，新增跨文件用例发现 plain 模块中 source_roots 外的 XML 捕获 module 为空，原分析器默认模块前缀导致目标 external 化。`xml-module-resource-target-red-02` 精确复现，red-01 的普通 app 模块资源没有复现；已在资源消费者边界按精确 raw ID + 可见捕获文件映射，未按 Bean 名猜目标，AnalysisSession 全套复验中。完整队列07及发布队列06因定向失败保持 failed，未构建 native。

独立原 JAR `xml-original-store-issue-01` 实际证明：原 stage 把明确 unresolved、candidateCount=0 的 `p.Owner#setValue(p.Actual)` 仅按 arity 重绑到 `p.Owner#setValue(p.Expected)`。两处真实 XML setter 元数据/绑定矛盾将精确登记为上游问题，不复制错误绑定。`xml-original-prepublication-01` 实为从最终 Java 元数据重放资源的诊断观察，无法恢复完整 prepublication FactView，不作为原始语义 oracle。

真实历史工具01 在进入产品前遇到 bytes/string 配置错误，02 进入索引后因未注册目标 JDK 被拒绝，均保留日志；两端源码/40模块 Maven 清单完整，修正工具将明确注册 Java8 和资源预算后重新运行，不记历史通过。


修复版 `jvm-components-32` 521/521、`jvm-public-xml-focused-04` 2/2、`jvm-probe-current-09` 17/17、`rust-cli-suite-08` 17/17 全部通过，零遗漏/漂移；完整产品58项继续执行。`model-producer-equivalence-06` 证明 bridge 调整未改原模型 producer、SDK8 编译器桥和 Maven helper。Java 来源继续补齐：原 JDK/二进制22项 `jvm-coverage-extra-01` 和原 ASM/JSON/身份90项 `jvm-coverage-extra-02` 全部通过；只加原样测试/helper，没有改生产规则。来源账本532文件，原测试逐项映射尚未封板。

`xml-original-identity-issue-02` 用实际原 javac/提取/资源分析和原数据库证明同 ID 的两个不同位置 Bean 只发布一个；01 的检查输出因二进制 fact_hash JSON 编码失败保留。`xml-real-identity-issue-01` 对提交中的 MERGE XML，以明确空 Java FactView 运行原资源分析及原 stage，确认14组位置不同的原 ID 冲突；该证明只覆盖 XML 身份/持久化，不冒充完整 Java 候选语义 oracle。上游 arity 误绑定与重复 ID 将按最新真实差分逐条登记，不能扩大豁免。

Native 尚未构建。实际 gate 新增显式 `--backend native`，共享探针/完整 CLI fixture、case ID 和运行包 SHA；native 桥资源校验改为读取实际运行包，崩溃夹具补入 Maven helper 清单。模型见证构建显式 `-Pnative`，仅编译原模型观察者/serializer helper，参考 query/SQLite witness 留在独立参考侧。真实历史比较器新增三项正式反例（键序不影响、等数量字段变化必须拒绝、重复出现不能集合去重），挂入组件入口；`committed-history-checker-02` 3/3，不表示真实历史验收通过。


当前完整 JVM 已闭合：`jvm-components-33` 654/654，新增原解析证据/契约/预算/质量21项独立回执 `jvm-coverage-extra-03` 21/21；`jvm-current-gates-queue-08` 17 probe +58 product 全通过。`jvm-runtime-exact-equivalence-01` 对 stage15/16 的 runner、SDK8 compiler bridge、Maven helper 内全部归一化条目逐个校验零变化；`jvm-probe-current-10` 用当前复制运行器再验17/17。扩展只加测试与 test-only quality helper，不改变已通过产品 fixture 的生产代码。

真实发布07在索引启动前失败：私有快照缺 justfile，input_files 严格校验拒绝；日志及 failed 回执保留，无 SIGKILL、无半成品发布。新私有 stage07补齐顶层文件后，`iopengw-jvm-publication-08` 已进入实际 full 索引，后续14域/审计/查询/lifecycle队列04/03等待其独立回执。`iopengw-committed-history-03` 已按真实两端提交进入实际索引；未完成不记通过。

Native 仅准备：新增 `java-native-project-models.py` 从已封存160 JVM上下文逐个通过同一个 AnalysisSession 的 native witness 对照14原模型域；native 新差异必须拒绝，JVM已知差异不得掩盖新 native 差异。三项反例 `native-model-gate-checker-03` 通过；01 缺 scripts 导入路径的失败日志保留。新增6项真实共享库 C ABI生命周期（同 isolate重复任务/分块/归档释放、4会话上限、非法状态、可读错误、取消后恢复、线程attach/detach与独立isolate），尚未执行，也未放行 native。

### 2026-10-11：真实 JVM 发布通过，历史在放行后续跑

| 项目 | 实际结果 / 回执 | 尚未完成 |
|---|---|---|
| 最新完整发布 | `iopengw-jvm-publication-08` 通过，1417.567 秒，无异常信号或输入/产物漂移 | 该并发耗时不计最终性能 |
| 14 图域 | `iopengw-jvm-semantics-04` 全部执行，严格10通过/4失败；`iopengw-known-upstream-classification-04.json` 精确证明4种上游缺陷，零未解释差异 | 保留严格失败，不修改独立预期 |
| 发布后公开查询 | queries-05 198/198、representative-08 21/21、matrix-07 19/19、lifecycle-06 7/7；audit-06 只读审计通过 | 不能替代历史增量 |
| 提交历史 | history-03 baseline-full、cold-repeat 完成且全图相等；target-incremental 被外部 SIGKILL。用户确认已放行，history-04 校验旧库/输入/运行时后实际续跑 | 当前仍运行；旧失败回执保留 |
| 完整 JVM 汇总 | 新 `java-jvm-acceptance-gate.py` 对15份回执、实际 case ID、产物谱系及输入 SHA 封存；原模型生产者证明实际重算。大库摘要改为1 MiB分块读取，避免一次装入约9.6 GiB数据库 | 历史完成后才封存与构建 native |
| native 构建约束 | 构建入口要求 `CODEANA_JVM_ACCEPTANCE_RECEIPT`；校验完整 JVM 回执及当前生产源码集合，拒绝删文件/混装；检查器反例通过 | 尚未构建/加载新 native，ABI布局与实际生命周期待执行 |
| 来源覆盖 | 538份保留来源；194份 Java 产品层实现归档到明确 Rust 职责；没有归档或豁免任何原测试。词法用例发现排除字符串/注释伪@Test，原组件654项不变 | 258份来源仍待逐项处理，覆盖清单 complete=false |

以上证据均位于 `target/implementation-evidence/`。后续继续原 updated plan，不以本条进度记录关闭 ACC-03、NAT-01/02 或 ACC-04。

2026-10-11 后续取证：history-04 的 target-incremental 已真实发布 `rev_29ff360a9b0c4e2fb18353755eb4c848`，872.907 秒、2444407 条事实；目标 profile 变化触发保守 full，明确不记为 delta。基线冷重跑实际记录为 delta 且全图相等。目标显式 full 及重启/旧版本检查继续执行。

来源审计继续收口：原 QueryEvidenceTest 的两项预期已明确映射 Rust Evidence，`query-evidence-port-02.json` 和实际 Cargo 日志证明通过；分页/depth/records 保留全部限制，截断不允许确认不存在，后续 complete 不擦除诊断。当前保留538份、归档260份实现/资源、Rust映射2份原测试；191份来源、820项原测试仍待处理，complete=false。Rust workspace-28 为156通过/0失败/0跳过，新证据用例及定向Clippy/格式检查另单列。

新增 `java-performance-monitor.py` 记录分角色进程树RSS、按PID/启动时间区分的累计CPU采样、磁盘逻辑/分配峰值与原始日志，并拒绝其它分析器并发；5项反例通过。OS页缓存不宣称已清空，短命子进程CPU可能漏采明确披露。尚未执行正式三轮测量，performance_accepted=false。

### 2026-10-11：完整 JVM 放行，进入实际 native 验收

| 门禁 | 当前证据 | 状态 |
|---|---|---|
| 真实提交历史 | history-04 全图和全部查询 full/目标请求相等，历史源码/重启相等，无输入漂移；target-full 880.959秒 | 通过；目标请求保守full，基线冷重跑实际delta，分别记录 |
| JVM 汇总 | `jvm-complete-acceptance-02.json` 15份回执、4223条绝对路径SHA；原生产者证明实际重算 | JVM阶段通过；严格参考相等false、coverage/performance/native仍false |
| 路径修复 | 首次native启动前因模型证明中的相对路径失败；封存路径改为仓库根下绝对路径，反例验证隔离cwd不改变含义 | 旧complete-acceptance-01/queue-01失败链保留，当前使用02 |
| 原生共享库与ABI | GraalVM25真实构建；native-abi-layout-02实际C/Rust均为 `[56,8,0,8,16,24,32,40,48,52,53,54]` | 通过；首次C offsetof字段名错用已保留01编译错误，显式对应保留字段下划线后再验证 |
| 原生fixture | lifecycle-current-02 6/6、probe-current-02 17/17，无产物漂移；product-current-02正在执行 | 未关闭完整产品/native门禁 |
| 原生错误证据 | 旧product-current-01真实OOM用例失败：native只返回e.toString，丢弃原核心附加的子进程stderr/exit。仅改NativeBridge有界cause/suppressed回传，保留8Ki字符/16异常上限和预分配fatal响应 | `native-error-evidence-green-01.json` 3/3；新实际产品OOM用例通过，原断言不变。共享Java分析源码未改 |
| 覆盖去向 | 保留538份、实现/资源归档283份、Rust映射2份原测试 | 剩余168份测试/辅助来源、820项原测试仍待逐项闭合，不豁免任何原测试 |

当前执行队列 `jvm-native-queue-04.json`。其完整fixture通过后，`native-real-queue-04.json` 才启动native160模型和冻结真实项目发布；该功能并发不计正式性能。后续还须14域/全量Fact IR/公开查询、native历史、发行以及三轮独占性能。


2026-10-11 native后续：`jvm-native-queue-04.json` 全部通过；实际共享库 lifecycle6/6、probe17/17、product58/58，真实OOM和临时磁盘满两项原断言均通过。冻结HEAD的 `iopengw-native-publication-01` 正常执行；native160模型工具01缺不存在的expected_contexts入口，02相对scratch造成Java8子进程找不到捕获文件，两次失败保留。修复工具使用完整40模块MAIN/TEST且evidence统一绝对路径后，`iopengw-native-models-03` 实际重跑。仅验收工具变动，不改模型producer。

发行包 `native-package-current-01/02` 的core、MAIN/TEST XML full/delta、错JDK三项均通过，但包内CLI派生daemon连续两次SIGKILL，不能将发行验收记为通过。同一01包daemon直接启动PID47073正常，签名校验通过；CLI创建新进程组是否触发拦截尚无安全日志证明，已向用户报告。修正发行验收脚本保留真实启动异常及daemon.log，不再被finally清理异常覆盖。

覆盖记录更新：538份保留来源、283份实现/资源归档、2份原测试Rust映射；另归档旧Anatomist发行契约BuildVersionTest与SkillMdContractTest原始字节及全部5项独立理由，不作为已执行通过。当前剩余166份来源/815项原测试，coverage complete=false。native功能并发耗时不记三轮性能。


2026-10-11 native真实图：`iopengw-native-publication-01` 发布 `rev_0f30d3290a9e476393d4301593999c5f`，无异常信号/输入漂移。`native-whole-fact-equivalence-01.json` 实际逐条规范化所有2,447,690条Fact IR（含属性、跨度、状态及重复次数），JVM/native摘要均为ba01dbbe4db4791f8b141c049a19a38b17e0703df17db19bf2b77b48433e4147。`native-jvm-shared-bytecode-03.json` 可重跑工具验证464个共享类/嵌入资源条目无差异；01误选保留的旧模型runner，只有既知三项bridge不同，失败保留，未改JVM门禁或原模型。

native14域全部执行严格10通过/4失败；known-upstream-01启动失败是错用模型采集receipt当构造器证明，02使用既已封存constructor-identity-issue-02，精确分类通过、零未解释差异。公开查询01错把仓库根冻结副本传给私有发布revision，全部遭PROJECT_MISMATCH，产品隔离生效。修正到实际发布的native-verification-root-01/target/iopengw-frozen后，queries02 198/198通过；`native-public-checks-queue-03` 后续完整执行通过，representative21、matrix19、lifecycle7和只读audit全部通过。所有旧失败保留。

SIGKILL仍未闭合：发行包自动启动对照连续失败；直接启动、新进程组直接启动正常，独立重新签名daemon仍未解决，仓库内临时目录同样失败，不能认定代码签名/临时目录/新进程组为根因。真实C ABI SIGSEGV夹具01运行包路径错误（缺bundle）；02正确运行包worker PID6433、03仓库内worker PID19690都在预期SIGSEGV前收到SIGKILL。已报告用户请求安全日志放行；不能将这两项归为native通过。

入口收尾：java_alignment主入口切为新JVM/native fixture，full只验完整迁移回执及绝对路径SHA，不再执行已删除DetachedModel旧门禁；旧capability_results/input_files helper仍保留供来源/反例使用。alignment-entry反例2项及既有gate-harness通过。独占三轮执行器/分阶段CPU-RSS-磁盘观察已准备，但尚未实际测量/放行。

2026-10-11 补充收尾：独立 ProcessArchitecture 原审计1项通过（process-architecture-port-01.json，211条文件证明）；未重建已封存bundle。WiringResolver 原用例已逐项迁入Rust，保留唯一实现推断和接口调用不被制造成实现调用的断言，7项派生关系回归/Clippy/fmt通过（wiring-resolver-port-03.json）。覆盖工具新增检查原测试保留字节及映射回执全部证明SHA，不能只凭源码存在计为迁移通过。另4项旧justfile脚本文本契约原字节逐项归档，未冒充执行通过；运行时查询/框架/增量能力继续要求独立验收。当前539份保留来源、294份归档来源、4份Rust映射来源；154份来源/763项原测试待闭合。

事实回放正确性实际通过：fact-replay-correctness-01/receipt.json，全部2,447,690条重建图摘要仍为ba01dbbe4db4791f8b141c049a19a38b17e0703df17db19bf2b77b48433e4147，原库SHA未变；blob/CAF1导出/stage/publish按阶段保留耗时。此运行与native功能验收并发，不计正式性能。独占3轮存储回放执行器已准备；性能入口拒绝空证明/修改后证明，3项反例通过。

拦截重试：native-package-current-03前三项包门禁通过，自动启动daemon仍SIGKILL；native-crash-current-04误选未安装bundle的debug目录，失败保留，05纠正到实际发布runtime后worker PID4271仍SIGKILL。新增独立崩溃回执包装器保存清理前的实际工作区、日志和SHA，不改已封存原夹具；未执行到SIGSEGV不能通过。已再次报告用户。

2026-10-11 原测试收尾补充：新增 DatabaseWiringResolver 原用例，独立SQLite候选正序/逆序输入的全部binding事实和候选顺序相等；完整派生关系8项回归通过，当前两项wiring映射统一绑定wiring-resolver-port-04.json。TextSearchService全部3项原用例迁入Rust：4项组件测试、真实CLI分页/空输入非法regex/闭stdout后daemon仍服务2项测试通过，text-search-port-02.json。独立upstream-components审计直接使用已封存的最新JVM runner，原ProjectScanner11和InternalCacheScan2原字节测试13/13通过，没有改动或重建生产Jar（upstream-scanner-components-01.json）。当前541份保留来源、294份归档、6份Rust映射；150份来源/746项原测试仍待闭合。新增native完整回执反例3项通过，不能以缺门禁或混合bundle计为native通过。

2026-10-11 Rust最终当前回归：rust-workspace-29.json记录163项主测试、0失败/忽略，Clippy全部target及fmt通过；嵌套子进程测试输出不重复累加。IndexStateStore原中断重建测试映射至现有两个已执行Rust恢复测试，index-state-recovery-port-01.json保留原字节和源码/运行证据；旧STALE/REBUILDING及dirtyGeneration表示改为不可变revision身份，旧head保留且中断候选清理。当前剩余149份来源/745项原测试；完整覆盖仍为false。

2026-10-11 native已提交历史完整通过：iopengw-native-committed-history-01四阶段完整图分别与JVM04相等，历史源码/重启读取及full_delta_equal均true，输入无漂移。target-incremental如实报告保守full，不伪称delta；实际target-full功能并发1287.585秒，不作为性能数据。

注释原写入结果4项迁入真实Rust CLI/daemon/SQLite，annotation-upsert-port-03.json通过。新边界为显式note ID和乐观版本，category/source/confidence/business_label原值保留为JSON文本；旧Java隐式复合键/列/批量方法形状不兼容，原批量测试仅验证成功最终结果，未声称提供批量事务。01误用无mock-worker的Java运行包，02补齐的临时副本mock-worker PID56991仍SIGKILL，失败保留且已报告；按标准target/debug构建后03四项通过。原测试保留字节，当前剩余148份来源/741项原测试。just test-rust已加入两组新原测试；新增native/性能汇总反例入口纳入组件工具检查。

2026-10-11 覆盖汇总工具已补齐：java-coverage-acceptance-gate独立核对冻结Git完整991文件、原方法列表、已封存JVM组件执行ID、Rust映射通过证据及全部SHA，归档逐项理由和原字节不可省略。attempt01只找原始Surefire文件导致609项漏识别，attempt02改读已封存组件case记录后仅剩5项业务fixture@Test；first-principles纠正为分析输入声明，仍保留原文件字节、不编译业务fixture或冒充分析器测试通过。attempt03当前准确为148份来源未闭合、已复制原分析器测试0项未执行，GoldenFileIT的TestFactory另保留为待审。Scope/空证据/修改后证据/失败或跳过JUnit反例4项通过；之前反例断言未canonicalize macOS /var路径，失败日志保留，修正预期而非放松SHA验证。完整覆盖仍为false。

2026-10-11 native模型完整闭合：iopengw-native-models-03 160/160通过，0 JVM差异，changed_inputs=[]；仅保留参考版两个精确构造器差异。完整native汇总manifest02汇集20份实际回执，attempt01发现发行回执相对路径误按仓库根解析，和私有运行根旧工具副本冲突。新增manifest显式input_root，4项反例通过；manifest03/attempt02已正确验证所有内容门禁后，在COMPLETE_NATIVE_RELEASE_REQUIRED拒绝，native_accepted=false。源码证明无漂移；发行03与crash05的SIGKILL没有豁免，不能签发完整native或启动正式性能。

最新标准Rust产品：rust-product-e2e-30 17/17通过，新文本产品03 2/2及注释产品03 4/4通过；临时副本拦截失败保留。独占三轮JVM/native索引与三轮存储回放执行器已准备，但尚未执行。覆盖闭合回执准确暴露148份来源/741项原测试及动态factory，仍需继续逐项映射。

2026-10-11 安全拦截放行复验：发行包 `native-package-current-04` 四项全部通过。真实崩溃夹具07原始断言通过，但回执工具错误地只在daemon.log找信号；改为保存失败CLI的stdout/stderr/退出码，夹具08实际SIGSEGV、旧head保留、daemon存活及worker回收全部通过，无SIGKILL。`native-complete-acceptance-01.json` 封存20份回执/5339条绝对路径摘要证明，native_accepted=true，零未解释失败。既有失败回执保留。`backend-performance-rounds-01` 已启动冷/热各三轮；未完成不计性能通过，原测试覆盖与最终迁移仍待关闭。
