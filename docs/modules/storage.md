# 存储与原子发布

对齐 2026-11-17：图库物理 `user_version=15`、Anatomist 逻辑 schema 36；catalog 为 2。实现见 [graph_store.rs](../../crates/storage/src/graph_store.rs) 和 [graph_compact.rs](../../crates/storage/src/graph_compact.rs)，测量及验证记录见 [当前实现](../current-state.md)。

Rust 存储按 blob、task、object、maintenance、page、graph 分模块，共享协调状态、读者引用、候选封存及 CAS 事务。派生缓存位于独立 codeana-derived-cache crate，文本搜索与 Java worker 共享实现，原目录、锁、配额及持久化契约保持。对象使用明确 DTO；分页接收 `Record`，内部编码及旧页兼容见 [公共契约](../contracts.md)。

## 1. 布局

```text
<user-home>/                     默认 ~/.code-ana
  catalog.db                    project / checkout / target / refs / tasks / objects
  revisions/<id>/facts.db        不可变语义图
  candidates/                   私有未发布候选
  blobs/<content-hash>           捕获源码 / 输入内容
  pages/                        冻结查询页
  reports/                      报告附件
  cache/java-signatures/        可重建的有界 NoCode 缓存
```

home 共享 catalog，以身份隔离项目；不是每仓库一套 catalog。manifest、snapshot、diff、报告等对象及引用由 catalog 保存。catalog 使用事务升级。

## 2. 物理布局与逻辑读取

| 物理表 / 对象 | 逻辑入口 | 压缩与不变量 |
|---|---|---|
| graph_nodes | nodes | 公开 id 不变；整数 node_pk 支撑关系、注解和 FTS；UPSERT 保留未变节点主键 |
| graph_edges | edges | 节点引用为整数；保留 fact_hash / fact_ordinal，边 source_file 独立保存 |
| graph_call_site_owners / graph_call_sites / graph_call_site_targets | call_site_owners / call_sites / call_site_targets | 调用点与目标分离；不额外展开方法 CALLS 边 |
| graph_annotations | annotations | Java 源码注解；引用节点和路径字典 |
| annotation_meta_relations / annotation_meta_sources / annotation_meta_owners | annotation_meta_owned | 定义复用、归属独立，owners 为 WITHOUT ROWID |
| graph_file_cache / graph_file_dependencies / graph_symbol_dependencies | file_cache / file_dependencies / symbol_dependencies | 源文件字典 + 增量契约/依赖 |
| graph_index_diagnostics / graph_analysis_coverage | index_diagnostics / analysis_coverage | 零事实文件与 partial 缺口仍保留 |
| index_providers / project_meta | 同名表 | provider 身份、逻辑 schema 与计数 |
| graph_rust_source_states / graph_rust_analysis_dependencies | rust_source_states / rust_analysis_dependencies | world/key 字典与整数引用，WITHOUT ROWID；依赖只保存一次，baseline 批量还原 |
| source_files | 路径逻辑列 | 全局复用路径，NULL 与空字符串分开 |
| external_targets | 外部目标逻辑列 | 四 nullable 字段完整身份，NULL/空不能混并 |
| metadata_values | metadata 逻辑列 | 仅原始 UTF-8 ≥256 bytes 且重复至少两次共享 |
| node_names | FTS5 | external-content 为 graph_nodes，以 node_pk 连接；4096 字节页 |

逻辑视图负责恢复字符串列，私有候选的写触发器负责翻译整数引用。查询只读逻辑投影，解码稳定属性时不输出 `site_pk`、`owner_pk` 等物理列。`facts(body)`、整条 Fact JSON、Java raw type/binding/syntax 表均不在产品图库中。schema 11 是 code-ana 的物理格式，不承诺与 Anatomist 物理数据库兼容。

DDL 生成入口：[generate-compact-schema.py](../../scripts/generate-compact-schema.py) 从 [anatomist-schema.sql](../../crates/storage/src/anatomist-schema.sql) 和 [compact-meta.sql](../../crates/storage/src/compact-meta.sql) 生成 [compact-schema.sql](../../crates/storage/src/compact-schema.sql)。修改生成输入并运行生成器，不手改生成文件；`--check` 已纳入 `just test-rust`。

metadata 共享保持原始文本，不做 JSON canonicalization。SHA-256 只是候选索引，完整字节相等才合并；同 SHA 不同内容不合并。分组使用临时 SQLite，每次处理一个值，不把全库文本装入 Rust map。仅 nodes/edges/call_sites 的合格重复值共享。

元注解保留各来源首次位置、各归属 resolution_status 和定义聚合状态。全局路径字典保留 NULL/空；元注解 identity_file 仍遵守上游无文件身份规则。删除/停用 producer 时清理对应归属，封存时删除孤儿定义。

## 3. 导入与增量

| 阶段 | 行为 |
|---|---|
| 接收 | GRA1 行长度/列数/类型/源路径/module/scope/序号受限；End 前只写私有候选 |
| staging | 原逻辑列暂存，解析引用、UTF-16 长度规则 hash、重复 ordinal、调用点目标和派生 WIRES |
| full | candidate 使用私有导入设置，保留唯一约束，暂停非唯一索引/FTS 触发器，导入后重建 |
| delta | SQLite backup 固定兼容 baseline；按替换归属删除/合并，重映射暂存整数引用，保留未变 hash/ordinal |
| 封存 | 调用点/派生关系/依赖更新，字典共享及孤儿清理，索引/FTS/统计重建 |
| 校验 | 完整归档与 End、引用、coverage、SQLite integrity、foreign_key_check 和 FTS |

candidate 的快速导入设置不能推广到 catalog 或已发布图。最终使用新的发布连接 WAL/NORMAL、checkpoint/关闭和持久化屏障；完整校验没有移除。

## 4. 发布

```text
active r10 ──► reader Q 固定 r10，引用保护
     │
     └─► full 新 candidate / delta backup(r10)
           → 完整交付 + 校验 + 门禁
           → blobs / manifest / facts.db 同步
           → 不可变 revision 路径 + 目录同步
           → catalog 事务检查 expected head == r10
           → ready revision + head + commit 记录
           → active r11

Q 继续读 r10；新读者取 r11；Q 释放前 GC 不删 r10。
```

blob 批次使用共享上限 2 的文件 I/O，复制时计算 hash、同步和重命名，整批同步 blobs 目录；复用已有 blob 同样保持目录同步屏障。只在私有 candidate 写，已发布图永不修改。delta 使用 SQLite backup API，不能复制正在写入的数据库文件冒充一致快照。

## 5. 失败、升级与回收

| 情况 | 处理 |
|---|---|
| 缺块/错摘要/取消/磁盘满/worker 崩溃 | 候选不发布，旧 head 保留 |
| 文件落盘但 catalog 未提交 | 未发布孤儿，核对归属再清理，不自动补发 |
| catalog 提交后响应/ack/报告失败 | 已发布事实保留，以 commit 记录裁决 |
| CAS 冲突 | 不覆盖新 head；当前候选不发布 |
| graph schema 6 或其他不兼容格式 | 查询/delta 返回 GRAPH_SCHEMA_REBUILD_REQUIRED；index --full 建新 revision 后切 head，旧文件保留 |
| 旧格式历史 | 文件保留不等于新 reader 支持；显式图读取同样拒绝旧格式 |
| catalog migration 失败 | 事务回滚 |
| GC | 受活动入口、snapshot、reader、build、有效结果引用保护；删除失败登记 cleanup_pending，recover 重试 |

当前 GC 字节预算只计 facts.db，不是整个 home 硬配额。backup 和不可变 revision 会占额外磁盘；233.49 MB 是单个最终图库，不能等同分析时峰值或整个 home。单轮结果和边界见 [当前实现](../current-state.md)。

活动 blob pin 使用引用计数，覆盖复制、校验到提交/回滚；并发任务共享同 hash 时不会互相解除保护，GC 同时识别活动 pin 和原有暂存保护。大文件复制、发布前流式 hash、facts.db 同步在协调锁外完成；锁内保留最终 head 检查、rename/目录同步及 catalog CAS。故障恢复和持久化屏障保持。


## 注释物理布局（C1 已交付）

当前源码 schema 17 / 逻辑 36 保留 graph_comments、graph_comment_links 与 comment_terms FTS5；逻辑视图 comments/comment_links 只输出公开身份。暂存层使用任务原有语言 host 预算和冻结源码读取器，验证 hash、字节/UTF-16 范围、body_map、显式 world 及声明引用。所有语言使用同一个分词实现；tokenizer 身份写入保留元数据，查询不匹配时要求全量重建。关联只接受 documents/enclosing_callable，basis 只接受 language_rule。FTS 与候选同一事务完成后才允许 CAS。失败保留旧 head；C1 Native/JVM 公开查询和 ABI 验证已通过；三语言生命周期与实际分发已由 C2—C4 覆盖，A7 验证最终重构产物。

### 显式语言补全

`graph_store/language_completion.rs` 执行语言注册表声明的批量 SQL 补全。当前只有 Java 声明按符号与 source/module/scope 顺序唯一匹配、外部 ID/符号、反射 `$` 名和 arity 补全；顺序与唯一性检查保留。Rust/Python 的精确 producer ID 引用在行交付时已指定，不进入这些 Java 规则。缺失或未知语言的模糊引用不补成 Java 关系。注释关联始终只校验精确声明 ID、world 与捕获范围，不进入此模块。

CommentReader 将已有 `project_meta.inputs.unit.*` 和不可用输入 coverage 投影为类型化注释输入缺口。JSON 及 scope/world 字符串先按存储长度准入，再解码/复制；SQL VM 与读取共用当前账户和取消 token。仅消费已冻结字段，不新增 schema 或修改历史库。

物理 schema 17 将 source/world 联合用于替换和基线保留。边、注解、元注解来源、文件缓存及依赖保留 world；文件依赖同时保存目标 world，反查不再按路径挑第一个来源。声明匹配、重复事实 ordinal 和调用点来源/重建使用对应 world；注释/FTS 与 Rust 状态/依赖只删除指定来源实例。路径字典仍只代表字节路径，内部 file_pk 不充当语义来源身份。无 source 的汇总诊断沿用重新交付策略，NULL 不因复合 NOT IN 比较被额外保留。

所有写入仍在私有候选和同一次 CAS 内。旧 schema 16 及更早不原地升级，查询/增量返回 GRAPH_SCHEMA_REBUILD_REQUIRED；显式 index --full 从冻结输入建立新库并切 head，旧文件按原保留策略处理。GRA1 逻辑列、worker 25、graph model 6、Java ABI 4 和上游逻辑 schema 36 不变；主机投影补足的 world 不改公开实体 ID 或 hash 算法。持久化单元归属与混合暂存已有组件；公共执行器已接通，真实五模式混合与 Full 对照通过；SQL 预算及完整 R5/TS 验收仍待完成。

公开节点 ID 不允许跨 world 覆盖，公开关系/注解的 hash+ordinal 与调用点 stable_hash 有物理唯一约束。producer 的身份不满足隔离要求时返回 GRAPH_WORLD_OWNERSHIP_CONFLICT，不能靠改名或静默合并生成假精确结果；失败保留旧 head。

整项目复用使用显式入口并独立复核身份、归属与语义输入。Reused 与重新分析的空 delta 分开：复用须保留原全局诊断、CLASSPATH_UNAVAILABLE coverage 和无来源元数据；重新分析时旧汇总由新结果替换。准备 generation 和根配置展示字节的例外由 model 的 same_semantic_sources 共用，辅助绑定内容/角色变化不能借旧 input_digest 绕过检查。

复用校验使用 model 的 `UnitMergePlan::all_reused`，同时核对 project/checkout/target、完整有效归属清单，以及 FilesOnly 单元没有增删或变更。生产入口通过 `all_reused_with_budget` 从原 host 账户预付校验映射和计划分配；共享 manifest 映射归固定顺序首个 host，单元成本归自身后端，额度失败保留资源错误身份。`UnitMergePlan::new` 已定义逐单元 Full/Delta/Reuse/Removed/FilesOnly 的合并意图：Full、Removed 和 FilesOnly 从新旧清单导出完整来源范围；Delta 必须覆盖变更、删除和新增来源，不能借其他单元或 world 的 owner；Reuse 不接受替换范围。辅助绑定输入的发布单元消失或身份变化时，其消费者必须重分析。这是来源范围与复用的前置校验，尚未开放任意单元混合的 SQL 合并，也不代替各 worker 的 VerifiedEnd。

生产候选入口接收带主机来源的 `graph_io::records::Record`，再按 Ready 单元的完整输入、语言和观察模块校验。未知、FilesOnly 或跨单元的来源拒绝；共享辅助输入不能授权节点发布。Host 仅可写准备说明及 coverage，保留原行大小/列数/world 准入。旧无来源行接口仅供显式 fixture，应用架构门禁禁止回到该入口。

物理 schema 17 将 diagnostics、coverage、providers 与 project_meta 按主机来源持久化。unit_catalog 保存内部整数键与稳定单元 ID；0 仅为显式 Host/Fixture 桶。coverage 的物理唯一键含 unit_pk，公开视图列与业务 ID 保持；unit_providers、unit_project_meta 保存原始逐单元值，不存完整 Row/Fact 日志。活动集合来自执行计划的 Ready 单元，不能从输出推断；即使零输出也替换其全局观察，未活动单元保留。Delta 只继承活动单元未替换来源的文件观察；NULL、空串和 `*` 的全局观察均要求重新交付。显式整项目 Reused 不清除任何单元观察。当前活动集合仍由既有整项目执行路径提供，尚未开放任意单元混合。

公开摘要由所有保留单元重新计算：任一 source_only／classpath unavailable／fallback 不会被其他正常单元覆盖。同一 provider 仍只有一条公开摘要；不同单元 profile 以稳定单元 ID 排序后生成聚合 hash，相同 profile 保持原值，运算能力取交集，不同限制按单元保留，版本或语言不同时明确显示 mixed／`*`。不相容的同 world 编译上下文拒绝发布。provider 读取/聚合在复制和 JSON 解码前从原 host 账户预付；SQL VM、临时文件与其余 staging 分配的完整计量仍待审计。无来源节点、元注解、缓存及依赖的完整单元归属仍待后续实现。


物理 schema 17 保存 node_units 的逐单元最终定义摘要、声明摘要与 arity，不记录原始行日志。相同公开节点 ID 只有最终定义兼容时才能共享；无来源节点只能被实际交付该定义的单元引用。边、注解和调用点 owner 保存主机分配的 unit_pk，推导 wiring 使用独立 host_derived 标记，不从 producer 名猜归属。精确引用与 Java 名称／arity 补全均限制在本单元及显式声明的输入路径/world 内；辅助输入只授权目标绑定，不授权发布调用者或注解 owner。Delta 对活动单元的无来源定义和观察采用完整快照替换，显式 Reused 保留原归属。基线事实沿用原 hash，避免数据库默认值在复用重算时制造重复边。旧 schema 16 及更早要求显式全量重建。元注解与辅助缓存/依赖的单元归属见下节；公共执行器现经逐单元入口驱动该合并。


R4 的混合暂存组件 `stage_mixed_graph_records_with_context` 接受逐单元更新意图，持有同一个旧 revision 读者复核 UnitMergePlan，再在同一候选中替换 Full/Delta、保留 Reuse、清理 Removed/FilesOnly 的已归属数据。允许项目汇总 profile 改变，复用资格仍逐单元严格检查；Full 单元交付完整快照，Delta 来源必须在自身替换闭包内，不能借用其他单元的范围。Reuse、Removed、FilesOnly 不接受新单元行，混合入口也不接受 Fixture 无签名语义行。Host 行独立代表本次任务观察的完整快照。模型的混合验证与更新范围预付使用任务原 host 账户，拒绝保留原预算错误码。公共产品执行器现通过 StageInput::Units 调用此入口；节点、边/注解/调用 owner、metadata、元注解及辅助缓存/依赖均保持逐单元归属。真实五模式 fixture 已完成混合与 Full/历史对照；完整 R5/TS 与资源审计仍待完成。


物理 schema 17 将元注解来源、file_cache、显式 file/symbol dependencies 按主机 unit 保存。模式表区别 Full/Delta/Reuse/Removed/FilesOnly：Full、不可用与删除清理该单元所有原始观察，Delta 继承未替换来源，Reuse 保留原值；全局元注解每次活动单元重新交付。辅助输入的四类观察独立于语义发布范围，首次新交付只替换该单元、该输入、该表的继承快照，后续行累积并保持首个位置；诊断和语义行仍不能借辅助输入或其他单元的替换范围。共享元注解定义保留，来源/状态/位置分单元。

依赖的 host_derived 标记与 unit_pk 均为私有列；重建只清理推导行，显式交付不丢失，公开依赖视图按原逻辑列去重。推导包含静态调用点，注解/元注解的名称依赖仅指向本单元声明输入内的节点；Java 增量反查也按单元过滤。输入 file dependency 只有路径时，在本单元声明的目标 world 集合内保守展开，不按全项目第一个路径猜 world，未声明目标拒绝；空来源的观察不作为可导航文件依赖。交付策略 6 纳入 profile，公开 ID / worker / ABI / GRA1 列不因此改变；旧图需显式全量重建。SQL VM、SQLite cache 与临时磁盘的完整预算审计及 R5/TS 仍待完成。

元注解查询展开的全局 FQN 目录尚待按直接注解所属单元隔离；当前逐单元持久化及依赖补全通过，不代表该查询闭包已完成隔离。

元注解查询以真实直接 occurrence 为锚定位 unit，拒绝伪造 hash/ordinal 或来源归属，再读取该 unit 的原始元注解观察；查询缓存使用稳定 unit/world/producer 上下文。重复事实按逻辑 backend/unit ID 排列序号，保证 Full+Reuse 与 Full 的公开 occurrence/元注解 ID 一致。公开属性过滤内部整数行号及 *_pk，保留文本业务 ID。
