# Anatomist 存储设计与逻辑的 Rust 实现

2026-10-08：用户明确要求参考 `../anatomist` Java 存储实现，而非只迁 Java 分析核心。
目标是保留事实语义和成熟的存储逻辑，由 Rust 持有数据库生命周期。

## 已取证的设计对照

| Anatomist 依据 | Rust 对应 | 状态与下一步 |
|---|---|---|
| `store/StagedGraphStore.java`、`StagingSchema.java`：磁盘暂存、prepared statements、批量事务 | `storage/staged.rs`：CAF1 流式导入、单事务、复用 INSERT | 已存在；候选补充先入库再准备查询索引 |
| `resources/schema.sql`：来源/目标与 relation 复合索引、声明条件索引 | 原 Rust 主要为 kind/parent/target 单列索引 | 已合入复合及声明签名条件索引；全量空间和性能待验收 |
| `store/CallSitePersistence.java`：调用点/静态目标分离、未解析调用点、多个候选目标 | `FactKind::CallSite/CallTarget`、验证器与定向查询 | 已有独立事实；候选新增调用点正反向条件索引；多目标和未解析用例须验收 |
| `store/FactIdentityPreparer.java`：fact_hash + fact_ordinal 保留重复出现 | Java 投影稳定 ID + occurrence；Rust 去重冲突校验 | 现有逻辑保留；不得仅按端点合并重复关系 |
| `store/SourceBlobStore.java`：内容寻址冻结源码 | Rust `put_blobs/put_blob_files/blob` | 已有 SHA 校验与耐久化；继续覆盖损坏输入 |
| `file_cache/file_dependencies/symbol_dependencies`：输入身份及反向失效 | Manifest/Owner/Dependency、Java 失效计划 | 架构对应；需独立核对 body-only、删除、跨 MAIN/TEST 的全量/增量相等 |
| `store/IndexFileSwap.java`：关闭/checkpoint 后替换，失败恢复 | `publish_staged`：文件 fsync/rename、catalog CAS、历史读者 pin | Rust 已有；保留更适合 daemon 的不可变 revision，继续故障测试 |
| `store/SqliteStore.java`：可丢弃构建 OFF，发布前恢复持久化 | worker 私有去重库 OFF；候选 DELETE/FULL，catalog WAL/FULL | 已分开；不对已发布数据库关闭持久化 |
| `schema.sql`：diagnostics/coverage/provider/profile | Fact IR diagnostics/coverage/provenance + revision profile | 已有；零事实、partial、输入身份一起发布 |
| `schema.sql`：FTS5 node_names 与触发器 | 当前 FactReader 名称精确读取，工作区文本搜索另有路径 | 已在 v13 合入 external-content FTS5，保留字面子串契约；旧 revision 可读 |
| `semantic_annotations`、`SnapshotCatalog` 的独立版本 | annotations.db、catalog.db | 已有独立版本；不自动兼容 Java 数据库文件 |

## 存储实现顺序

1. 提取 Rust DDL 为存储模块的明确来源；定向 reference 查询去掉可选 parent 的 OR。
2. 补来源/目标/关系复合索引、声明签名条件索引、调用点/目标独立索引；新库批量入库后再创建查询索引。
3. 在私有候选升级旧索引；重复准备不重复重建，失败不碰已发布版本。
4. 验证全量与 owner delta、重复事实、多调用目标、外部目标、取消、CAS、崩溃恢复和历史读者。
5. 核对反向依赖与符号检索需求；对没有等价实现的逻辑单独实施和验收。
6. 使用冻结真实项目测量入库、派生、查询、库体积和增量复制成本；确认语义相等后做独占冷启动性能对照。

## 证据边界

先前真实候选库的同一 reference 查询，带可选 OR 的版本取 3 行约 6.586 秒，
直接 parent 等值条件约 0.000193 秒，执行计划分别为 kind 扫描与 parent 查找。
这是诊断样本，不是整项目性能验收；证据见
`target/java-migration-implementation/reference-query-plan-diagnostic.json`。

新增 DDL、索引与查询当前在已合入主目录，Rust workspace 回归通过。旧 v12 模型回执在 23 个上下文后封存，
封存时输入和源码没有变化；新运行 `project-models-v12-storage` 固定新源码，继续完整收集。
新运行复用分别归属参考/JVM/native 的编译器签名缓存，所有上下文重新比较，没有晋升旧上下文回执。参考 JAR 的 512 MiB 解码限制保留，继续收集其余差分。


## 验证结果

| 验证 | 结果 |
|---|---|
| storage | 26 个测试通过：旧索引升级/重复准备、查询计划、增量替换、取消、CAS、恢复、历史读者 |
| application | 25 个测试通过；集成子进程回归通过 |
| clippy | storage/application 全 targets 零警告 |
| 真实事实样本 | 9 类各 3,000 条，共 27,000 条；查询正文 SHA 完全相等；integrity_check=ok |
| 执行计划 | reference 使用 parent+kind+relation；声明使用 source+world+signature 条件索引；call_target 使用 target 条件索引；三者无需临时排序 |
| 空间取舍 | 样本库 79,028,224 → 94,068,736 字节，约增加 19%；索引创建 0.313 秒，仅诊断样本 |
| 完整库 | Rust v12 8,274,608,128 字节，冻结参考库 604,082,176 字节；事实范围不同，不能直接宣称空间差异全部为浪费 |

样本证据：`target/java-migration-implementation/storage-anatomist-sample/receipt.json`。
Rust v12 中 facet 1,565,457 条，body 合计 2,879,835,120 字节，占事实正文最大部分；
随后应核对哪些是必须保留的类型/原始证据，哪些适合按内容共享。
所有空间优化必须保持公开 Fact IR、原始出现次数、绑定来源和证据完整性，不能为缩库丢事实。
新增复合索引有空间成本，全量容量与入库性能门禁尚未通过。


## 主目录合入后的产品验证

Rust workspace：111 个测试通过；clippy 全 workspace/all-targets 零警告；fmt 与 diff 检查通过。
发布包四条链路全部通过，24.808 秒，源码和产物无变化：核心 fixture、Maven MAIN/TEST/XML 增量、
错误 JDK、包内 fixture 自动启动 daemon 后的索引/调用/源码查询。
证据：`target/java-migration-optimization/release-storage-validation/receipt.json`。

完整冻结项目存储验证已完成：`single-project-storage-v12`。
发布成功、查询 exit=0、配置恢复、源码/产物无变化，849.114 秒；业务语义 partial 原样保留。
该运行与模型收集并发，耗时仅用于诊断，不作为独占冷启动性能门禁。


## FTS5 检索补充（v13 已合入）

取证：`query::search-code` 先通过 FactReader 加载所有 declaration/external_symbol，再做字符串过滤。
冻结项目有 113,503 条 declaration，超过存储单次读取 100,000 条预算，稀疏匹配也会提前失败。

采用 Anatomist 的 external-content FTS5 与 INSERT/DELETE/UPDATE 触发器逻辑，Rust 额外保留当前
字面子串契约：trigram、大小写敏感、SQL instr 精确复核；少于三个字符、包含 NUL 或旧 revision
使用 SQL 字面匹配。名称与 signature 保持原始 Fact IR，不把 FTS 命中提升为 Java 解析结果。

已通过：100,002 条声明中的单条关键词命中不再先加载全部声明；广泛结果仍受预算限制；
短词、大小写、中文、引号、空词、外部符号与原逻辑相等；删除/替换和类型变更后索引同步，
FTS integrity-check 通过。FTS5 与 XML/模型编号修复已合入 v13。最终回执为 `single-project-v13`、`project-models-v13`，不得把 v12 回执算作 v13 全量验收。


FTS5 冻结符号差分：135,372 条 declaration/external_symbol，8 个选择器的全部 ID/body 相等，
FTS integrity-check=ok。`JSONUtil` 返回 360 条，诊断耗时 SQL 扫描 0.743 秒、FTS 0.0067 秒；
结果与时间证据见 `target/java-migration-implementation/storage-symbols-real-diagnostic/receipt.json`。
隔离副本的发布包四条全流程通过（25.656 秒），输入和产物没有变化。


### 全量事实正文核对

新旧两库各 2,451,465 条事实，按 kind 对 ID/body 完整计算 SHA-256：
声明、外部符号、引用、调用点、调用目标、关系、facet、coverage、provenance 九类逐字节相等。
诊断均为 3,212 条，原始正文不同；已取证差异含任务临时捕获路径和 classpath_order_sha256，
原始诊断和差分回执保留，不宣称全部正文相等。

证据：`storage-publication-body-parity/by-kind-receipt.json` 与对应 diagnostic 回执。
事实库 8,274,608,128 → 9,490,116,608 字节（约增加 14.7%）；
查询与空间成本须共同评估，不能把本批索引改动称为存储空间优化已完成。


v13：Java 96 个测试通过，native 11 个回归通过，Rust workspace 113 个测试通过，clippy/fmt 通过。
发布包四条全流程通过，22.656 秒，输入和产物无漂移；冻结参考 fixture 10 × 14 类差分通过。
全部正文对照中，3,212 条诊断除任务捕获路径前缀、classpath 指纹及其派生 ID 外完全相等。
该对照只用于定位差异，未改动原始诊断，也未晋升为原始正文/全语义门禁通过。


安装校验：早期 `single-project-v13` 因本机 `target/lib/java` 仍为 v12 而拒绝混合运行，
未发布；失败回执保留。已从验证通过的 v13 发布包同步本机 Java bundle，runtime-info 验证
worker14/request8/ABI2/FactIR1/adapter-v13；最终运行改为 `single-project-v13-final` 与 `project-models-v13-final`。


## 2026-10-09：v14 隔离候选与空间取舍

用户确认本轮优先真实查询收益，允许有证据支持的空间增长；不调整事实正文格式。
v13 发布库的 `dbstat` 测量见 `target/java-migration-implementation/storage-v13-space.json`：
库体积 9,575,821,312 字节，facts 表约 6.94 GB；`fact_name` 占 217,673,728 字节。
当前全部 Rust 查询均未使用 name 等值索引；符号搜索通过 FTS 候选及 SQL instr 精确复核。
隔离候选从新库 DDL 移除此索引，并在私有候选准备查询时删除旧索引；已发布库不修改。
Rust 全量回归、clippy 已通过。新建完整库的实际空间收益仍待发布验证；增量删除索引产生的空闲页
不等于立即缩小现有数据库文件，因此不宣称已回收上述磁盘空间。

旧 DI 对照工具已在隔离目录恢复冻结 resolver 探针，并使用覆盖事实的 `input_hash` 验证捕获。
主目录 v13 运行库的 DI 变更/删除及跨模块 MAIN/TEST 增量对照通过；不要求额外生成 SOURCE
external alias，直接指向已发布声明时验证原文件摘要和跨 world 端点。
候选尚未合入，最终发行包失败记录与严格全量门禁保持原样。

私有真实库验证：用 APFS clone 复制 v13 发布库，仅在副本删除 `fact_name` 并重复准备。
2,451,465 条事实的 ID/body 全量摘要相等；JSONUtil、IOpengwManagerServiceImplTest、
service.facade、jsonutil、Factory 五组查询的完整正文、顺序及执行计划相等。
产生 217,673,728 字节（207.59 MiB）可复用空闲页；文件仍为 9,575,821,312 字节，未执行 VACUUM。
原库保留旧索引，inode、大小、修改时间未变。
证据：`target/java-migration-implementation/storage-name-index-real/receipt.json`。
该实验只证明删除索引的内容与查询兼容性，不替代候选完整发布或独占性能门禁。


## v15 同版本验证（2026-10-09）

| 项目 | 结果 |
|---|---|
| Rust workspace / clippy / fmt | 113 项通过，静态检查通过 |
| 最终发行包 | 四条完整链路通过，宿主 SIGKILL 修复后未再复现 |
| DI 增量 | 显式使用 v15 release daemon/worker；修改、删除与全量对照通过，56.453 秒 |
| 跨模块增量 | MAIN/TEST 发布端点及增量对照通过，34.870 秒 |
| 真实完整库 | 发布 2,447,969 条事实；9,328,340,992 字节；integrity_check=ok，fact_name 已移除，已发布库无改动 |
| 性能 | 尚未执行三组独占冷启动；并发测试耗时不计性能门禁 |

证据见 `target/java-migration-implementation/applied-v15/release-validation/receipt.json`、
`di-release-incremental.log`、`cross-module-release-incremental.log`。早期默认 debug daemon 的增量结果
仅作诊断，不计入 v15 存储验收；本轮没有调整 Fact IR 正文格式。

完整库证据：`target/java-migration-implementation/applied-v15/storage-inventory.json`。
v13 最终库为 9,575,821,312 字节，v15 减少 247,480,320 字节（约 236.02 MiB、2.58%）。
这是调用事实修复与索引调整后的整体体积变化，不能全部归因于移除 name 索引。
独立私有副本已证明单独移除该索引可产生 207.59 MiB 可复用页；新库本次没有改正文格式。
