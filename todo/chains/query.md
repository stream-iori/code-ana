# 查询：参数 → 主体 → 定向读取 → 结果与证据

[入口](../README.md) · [共用契约](../contracts.md) · [追溯](../traceability.md)

**查询行为和支撑它的 Rust 投影、索引、读取端口在同一任务交付。以独立固定事实先做 TDD，再接 JVM 结果。**

```text
CLI / pipeline 类型化参数
  → selector + module/scope/world → 主体唯一性
  → 定向 SQL / 批量关系 / 遍历预算
  → 结果与 coverage / provenance
  → 稳定排序 / 固定 revision 分页
```

<a id="qry-01"></a>

## QRY-01 主体选择、范围和观察到的外部实体

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：SQL 范围下推及精确组合筛选；旧 revision fallback 已接入 |
| 行为 | search-code/resolve 支持精确 ID、FQN、签名及组合筛选，在有效范围内判定唯一性。 |
| 调用链 | CLI/proto → 类型化 selector/filter → FactReader SQL → 内部声明/observed external → 结果。 |
| 开发前置 | 无；手工固定事实和私有 SQLite 可独立开发 |
| 联调前置 | IDX-01 |
| 实施 | 贯通 ID/FQN/signature/name/package/path/kind/annotation、module/scope/world；声明 projection 保存 signature/kind/package/visibility/synthetic/parent/source。补规范化 source-unit → world/module/scope 映射，不从路径猜模块；无 source 外部实体通过观察关系关联范围。过滤先于结果预算和唯一性。内部身份优先，外部只返回实际观察对象，不枚举整 JAR。 |
| RED / 基线 | 跨模块同名但选定范围唯一、重载/限定签名、内部/外部同名、大量无关范围命中/少量目标、空/歧义/不支持参数；冻结上游参数语义和手工预期 ID，复现当前 resolve/预算缺口。 |
| 完成条件 | 真实 SQLite 与 CLI 固定事实结果正确；SQL 下推不丢目标，不能静默忽略新参数。外部选择按 ExternalDeclarationCatalog 语义，投影与 IDX-01 同候选维护；JVM 对照在 ACC-02。 |
| 追溯 | A7/A13/A17；S1/S3；旧查询矩阵 search/resolve；Q1 |

<a id="qry-02"></a>

## QRY-02 结构与冻结源码

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：复用结构/冻结源码链路，完整 fixture 门禁未通过 |
| 行为 | structure/source 展示完整声明、调用现场、所属声明和行范围，固定 revision 读取。 |
| 调用链 | selector → 父子/声明 projection → source/site/owner → 捕获 blob → UTF-8 范围/行/注释。 |
| 开发前置 | QRY-01 |
| 联调前置 | 无；本任务验证固定事实，真实 JVM 留给跨链门禁 |
| 实施 | 补文件/类型/成员/嵌套、递归、visibility/synthetic、完整方法体及注释。owner 与 site 分别按自身范围读取；外部/生成对象无源码明确披露。参考 SourceCommand 对象语义，保留 Rust 冻结 blob，不移入上游活动工作区过期策略。 |
| RED / 基线 | SourceOwnerFallbackIT、SourceCommandTest、SourceWindowServiceTest 的输入与预期；声明/site/owner、Unicode/emoji/CRLF、无范围、跨页行、外部/生成成员。旧 revision 在活动文件改变后读取复用既有回归。 |
| 完成条件 | 对同一捕获字节核对范围/内容；完整声明不缩成名字跨度，与参考冻结快照比较；分页与版本身份不丢。新增行为先 RED，既有历史无需另造 RED。 |
| 追溯 | A14；S3；旧查询 structure/source；Q2 |

<a id="qry-03"></a>

## QRY-03 关系、类型、运行时候选和有界路径

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：跨模块 incoming、批量多跳 calls、起点到终点 trace 已实现 |
| 行为 | calls/call-sites/references/type-relations/callable-relations/runtime-implementations/dispatch/trace 按方向和预算给出带证据结果。 |
| 调用链 | 主体 → 关系 projection → 操作/方向过滤 → 分层批量邻接 → 类型/调用遍历 → occurrence/candidate/path。 |
| 开发前置 | QRY-01 |
| 联调前置 | 无；本任务验证固定事实，真实 JVM 留给跨链门禁 |
| 实施 | 批读 ID/关系/邻接，复用现有 500 端点批次并按层读取，不每节点一次 SQL；筛列后解码 body。call-site/target 分离，保存 caller/site/target/relation/state/position，支持 caller/callee 筛选。内部 B 主体 incoming 保留 A 模块来源；outgoing/外部观察范围逐操作定义。继承/实现/覆盖、反向/传递深度、可实例化、exact/CHA/possible；analysis world 与 world ID 分开。trace 是起终点一条有界路径，不用现有边展开冒充。 |
| RED / 基线 | 宽图/深图/环/多目标/未知外部、跨模块 MAIN/TEST incoming/outgoing、重复调用/构造器/lambda/方法引用/字段读写、resolved/possible、不可达/深度耗尽；独立断言各跳证据/次数/预算/SQL 批次，复现方向和 trace 缺口。 |
| 完成条件 | SQLite/CLI 固定事实通过；occurrence 不因同端点丢失；工作量/枚举量/输出量预算分开，批间可取消，候选不当确定调用。FW-03 扩展配置/注解，不能全局套 source module 过滤。 |
| 追溯 | A7/A13；S1/S2/S3；旧关系/派发/trace；Q3/Q5/Q6 |

<a id="qry-04"></a>

## QRY-04 overview 与文本检索 SQL 路径

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：SQL GROUP BY overview、范围下推搜索通过；search-code 100003 条 keyset 完整枚举通过 |
| 行为 | 大库 overview 不受 10 万条正文限制，范围内稀疏命中不被提前预算截掉。 |
| 调用链 | CLI overview/search-text → 范围 → SQL 聚合/FTS + literal 复核 → 计数/文本窗口。 |
| 开发前置 | 无；手工事实和私有 SQLite 可独立开发 |
| 联调前置 | IDX-01 |
| 实施 | module/package/dependency 用 COUNT/GROUP BY，不全拉 JSON；修复 fact select 100001/100000 截断。search 下推范围/投影；text literal/regex/case/path/context/page 保留捕获源码语义，复用 FTS5 trigram、短词 fallback 与旧库路径。 |
| RED / 基线 | 100001 条以上和百万级事实独立计数；无关范围大量命中/目标稀疏命中；短词/中文/引号/空词/大小写/正则/外部实体/旧库；固定预期与解码量，不能只比非空。 |
| 完成条件 | 聚合与独立扫描计数一致，不全解码 body；选择性计划/读取量有证据，合法聚合扫描不误报；文本和上下文/全部页对应捕获源码，FTS 与独立匹配一致。 |
| 追溯 | S1/S3/S4；旧 overview/text；Q8 |

<a id="qry-05"></a>

## QRY-05 类型化 pipeline、证据与稳定分页

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：显式关系端点 pipeline、SQLite 冻结分页已接入；search-code 分批写 spool；其他操作初始计算仍有工作预算 |
| 行为 | 关系端点可继续查询；空结果区分确认不存在和无法确认，续页固定 revision 与参数。 |
| 调用链 | CLI/proto pipeline → 类型检查/endpoint → 查询 → coverage 合并 → 排序/游标 → 分批冻结结果。 |
| 开发前置 | QRY-01、QRY-02、QRY-03 |
| 联调前置 | IDX-03 |
| 实施 | 贯通主体/关系/端点与类型参数；允许 calls→resolve(callee)→source、resolve→call-sites→dispatch；非法连接读库前拒绝。参考 QueryCoverageService 的 capability/direction/anchor/module/scope，区分阳性/确认空/不确定空/部分聚合。内部 scan 游标与公开 frozen page 分开，排序附唯一 ID，身份含 revision/filter/sort/budgets；按批写 spool，不全装数组，复用 TTL/pin。 |
| RED / 基线 | 同排序值/不同页大小/发布新版本后续页无漏重；端点类型错误、合法后续 resolve、下游空保留上游缺口；无关模块 partial、锚点 partial、生成/外部体缺失、incoming 未知调用者、取消和预算耗尽。 |
| 完成条件 | SQLite/CLI 固定事实及全部分页核验；本次变更才补生命周期 RED。JVM 联调验证新参数/证据，旧端不支持明确拒绝而不丢参数。 |
| 追溯 | A7/A8；S2/S3；旧 pipeline/coverage；Q9 |

## 统一对照与优化出口

| 事项 | 要求 |
|---|---|
| 参数 | 对照显式传 scope/depth/budget/analysis world；保留 code-ana 默认值，记录双方差异 |
| 结果 | 比身份/端点/occurrence 次数/范围/状态/证据/全部页，不要求输出字节一致；分别披露扫描预算、结果预算和真正枚举结束，返回一页不代表查询完整 |
| 存储 | 各投影随 owner/kind 替换删除，与 facts 同事务；完整正文权威，可独立重建 |
| 索引 | 只为真实查询新增；记录计划/读取/解码/批次/维护成本，统计治理归 IDX-04 |
| 集成 | [ACC-02](../acceptance.md#acc-02) 真实 JVM fixture；[ACC-03](../acceptance.md#acc-03) 冻结 iopengw |
| 框架 | 元注解/属性/路径、XML/config、观察到的外部实体见 [FW-03](frameworks.md#fw-03) |

2026-10-10 继续盘点：既有 `jvm-product-10` 52/52 通过不覆盖所有计划查询参数。真实 CLI `java-text-options-cli-red-01.log` 复现 `--regex`（以及尚未声明的 ignore-case/context）被拒绝，QRY-04 仍未完成。冻结 SearchTextCommand 的参数范围为 context 0..100，path 仅收窄配置输入；Rust 将保留自身捕获字节/固定分页契约，并补 regex/case/context/路径联动。该缺口尚未修复，iopengw 基线发布/模型取证不作为完整 ACC-02/ACC-03 放行。

### 2026-10-10：剩余计划继续实施

| 能力 | 最新证据 | 尚未关闭的门禁 |
|---|---|---|
| 文本查询 | 正则、Unicode 忽略大小写、路径过滤、上下文（最多 100 行）；结果按 512 条/约 1 MiB 写入冻结页库。`text-cli-green-02` 与 Rust CLI 16 项通过；100003 个命中完整枚举且上下文有界。 | FTS5 trigram 加速、旧库与最终性能。 |
| overview | `--section modules/packages/dependencies`、依赖 in/out/both 与关系过滤、`--package-depth` 折叠；SQL COUNT/GROUP BY 不加载正文。`overview-sections-green-03`、`overview-scale-green-01`、`overview-dependencies-green-01` 通过。 | 冻结项目 Q8、聚合分组上限与所有别名/范围的完整验收。 |
| 公共握手 | `text-search-options-v1`、`overview-sections-v1`；旧 daemon 缺能力时拒绝，不静默忽略新增 protobuf 字段。 | 完整最终产物回归。 |

以上回执位于 `target/implementation-evidence/`，均不是完整 QRY 或 JVM 放行。

FTS 文本候选已接入：`text-trigram-green-03` 验证中文/引号、精确字面量、无效编码保留、已验证缓存避免重读、损坏重建、短词/预算扫描和取消；`text-trigram-cli-01` 17/17。缓存只是候选筛选，最终匹配/上下文仍来自固定捕获字节，正则/忽略大小写继续扫描。最终性能及超出缓存预算的大项目加速仍待验收。

2026-10-10：overview dependencies 的关系组按冻结 `ReferenceRelations` 与共享 `FactProjection` 映射：type/reads/writes/config/all/calls，以及原关系名和 Fact IR 名；去重、空项与未知组显式拒绝。all 保持原定义，不含 calls/继承/contains。`overview-relation-red-01` 证明旧实现接受未知组；`overview-relation-green-01` 查询组件全套通过，`rust-workspace-15` 与 `rust-clippy-16` 通过。完整 CLI / 冻结项目独立分组差分仍待补验。

`overview-relations-cli-green-01` 在固定事实经 CLI→daemon→SQLite 的实际链路验证 type/config/all、原关系名、重复 occurrence 计数、calls 独立以及每页 1 条的完整分页；未知组显式失败。fixture 在私有目录生成，不改正在跑的测试输入。该局部通过不替代冻结 iopengw 的 overview 对齐。

references 参数缺口已取证：旧实现忽略 direction/relation，`references-direction-red-01` 的 outgoing 应有两条而实际为零。修复后入/出方向、type/reads/writes/config/all 及原始关系过滤下推到定向端口，保留 occurrence 次数，incoming 不套调用者 module 限制。CLI 默认保持历史 incoming，其他命令仍 out；新选项/含 references 的 pipeline 检查 `reference-options-v1`。`references-direction-green-02` 为查询组件 14/14，`reference-options-cli-green-01` 实际 CLI 验证逐页出向 type、默认入向 reads、配置引用、未知组失败和 references→resolve(target)；`rust-workspace-16`、`rust-clippy-17` 通过。修复后的最新 release 二进制已冻结在 `jvm-next-root-04`，完整 JVM CLI 复验已排队。


2026-10-10 继续补 QRY-03：`runtime-candidates-red-01` 复现 runtime-implementations 返回继承边并被种子 module 过滤；新实现返回 entity、穿过抽象层级并保留每跳 proof，支持可实例化筛选和显式传递层级关系。`runtime-candidates-green-02` / `rust-workspace-22` 通过；`jvm-runtime-query-focused-01` 的真实 CLI 1/1 通过，覆盖全部分页、source pipeline、full/delta 和重启后的旧版本读取。

`dispatch-receiver-red-01` 复现接收者已限定到子类型时仍返回抽象/其他类型的方法。派发规则按冻结 JavaDispatchService 移入 Rust 定向查询，补 exact/CHA 与独立 analysis-world 参数。`dispatch-receiver-green-04` 查询组件 22/22；其中 1200 实现宽图核对全部候选和批读上界，`rust-clippy-24` 通过。真实 JVM 8 项局部复验 `jvm-query-focused-03` 执行中；不能用组件通过代替完整 fixture / 冻结项目 / native 放行。
