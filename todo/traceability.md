# 来源、旧编号与覆盖映射

[入口](README.md) · [公共约定](contracts.md)

**本页保留证据与迁移去向，不维护第二套任务或完成状态。A/S 是来源编号；P/T/V 是旧阶段/门禁，不再作为串行排期。**

## 1. 基线与证据使用

| 来源 | 冻结依据 |
|---|---|
| Anatomist | ../anatomist，dev/replace-parser，dff2e3954096853352ec91fcbfc6b995fbd4f390；下表 Java 类名均相对此树的实际模块 |
| code-ana | afbe8ed972dfa7322b0bf1d97ca60d231cb1540f；Rust 证据集中在 crates/model/src/lib.rs、crates/query/src/lib.rs、crates/storage/src/、crates/application/src/lib.rs 及 proto |
| 历史删除 | Java 目录在 bdd408c 删除，旧 todo 在 afbe8ed 删除；历史“已迁入”叙述不代表当前已实现 |
| 被替换文档 | 01-java-anatomist-integration.md、02-rust-storage-alignment.md、03-java-storage-acceptance.md；本次删除，不留归档/重定向 |
| 历史设计 | [架构](../docs/architecture.md)、[契约](../docs/contracts.md)、[Java 迁移记录](../docs/java-anatomist-migration.md)、[存储迁移记录](../docs/storage-anatomist-migration.md)、[存储模块](../docs/modules/storage.md)；实施前按代码核对，不借用旧回执 |
| 维护办法 | 修改新任务时保留旧编号；将来源 commit/文件/SHA/原用例映射补进实现回执，正文状态只在任务处更新 |

## 2. A1–A17 全量对照去向

| 旧项 | 冻结源码证据 / 已发现缺口 | 新任务（唯一实施正文） |
|---|---|---|
| A1 插件装配 | 上游 app 的 `application/FrameworkPlugins.java` 负责 Spring→SOFA 依赖顺序、插件指纹、Lombok 声明与调用证据注册；只复制 core/framework 会漏装配 | [JVM-01](chains/java-jvm.md#jvm-01)、[FW-01](chains/frameworks.md#fw-01)、[FW-02](chains/frameworks.md#fw-02) |
| A2 提取后的语义处理 | 上游 full 经过 `StagedGraphStore.finalizeRawFacts`，delta 经过 `GraphIdentityRewriter`、`GraphPostProcessor`；发布还有 `DatabaseWiringResolver`、`CallSitePersistence` | [JVM-03](chains/java-jvm.md#jvm-03)、[IDX-01](chains/index-publish.md#idx-01)、[FW-03](chains/frameworks.md#fw-03)、[INC-03](chains/incremental.md#inc-03) |
| A3 Maven 输入等价 | 上游 `JavacMavenInputs` 区分有效 reactor、MAIN/TEST、有序依赖、生成目录、输出目录及资源；Rust 有外部 `JavaInputs` 清单 | [JVM-02](chains/java-jvm.md#jvm-02) |
| A4 配置真正生效 | Rust 配置定义了语言/模块 `external.exclude_patterns`，当前 JavaContext/JavaConfig 协议无对应字段，Rust 源码未见消费；上游有 `ExternalTypePolicy` | [JVM-02](chains/java-jvm.md#jvm-02)、[FW-01](chains/frameworks.md#fw-01)、[INC-02](chains/incremental.md#inc-02) |
| A5 二进制与平台类型 | 上游 `JavaBinarySources`、`ClasspathClassFileSource`、`JdkClassFileSource`、签名缓存提供有序依赖和目标 JDK 类型 | [JVM-01](chains/java-jvm.md#jvm-01)、[JVM-02](chains/java-jvm.md#jvm-02) |
| A6 JDK 支持边界 | 上游 `JavacToolchain.resolve` 明确支持目标 8/17/25；本项目约定目标 8、宿主 25 | [JVM-01](chains/java-jvm.md#jvm-01)、[JVM-02](chains/java-jvm.md#jvm-02)、[NAT-01](chains/native.md#nat-01) |
| A7 查询选择与 pipeline | Rust `resolve` 目前按 ID 或 name 查找，唯一性判断先于范围过滤；pipeline 禁止后续 resolve，edge 不能继续传入 | [QRY-01](chains/query.md#qry-01)、[QRY-03](chains/query.md#qry-03)、[QRY-05](chains/query.md#qry-05) |
| A8 查询证据 | 上游 `QueryCoverageService` 按能力、方向、锚点、module/scope 判断；Rust coverage 当前仅按 world 过滤缺口 | [IDX-03](chains/index-publish.md#idx-03)、[QRY-05](chains/query.md#qry-05) |
| A9 增量/缓存/扩展失败 | 上游 `ProjectAnalysisRunner` 共用资源清单，`ExtensionExecution` 隔离扩展失败；`Javac*Cache` 与 `IncrementalIndexer` 参与复用 | [IDX-02](chains/index-publish.md#idx-02)、[FW-02](chains/frameworks.md#fw-02)、[INC-02](chains/incremental.md#inc-02) |
| A10 构建与测试门禁 | 上游根 POM 允许没有匹配的指定测试；本项目旧测试含过期路径，并有每回执重复构建 Cargo target 的行为 | [ACC-01](acceptance.md#acc-01)、[ACC-04](acceptance.md#acc-04) |
| A11 特殊源码、资源与测试发现 | 上游 core POM 在 `generate-resources` 编译 `src/worker/java` 和 `src/maven-worker/java`；app Surefire 显式包含 `*Test.java`、`*IT.java` 及分组规则 | [JVM-01](chains/java-jvm.md#jvm-01)、[ACC-01](acceptance.md#acc-01)、[NAT-01](chains/native.md#nat-01) |
| A12 模型与事实逐字段映射 | `JavaUnit` 含 syntax/bindings/types/declarationInventory/extensions；`ExtractionResult` 不只有 nodes/edges；`JavaModelExtractor` 还执行 `JavaReflectionFacts`。Rust `FactValidator` 严格检查 facet 名称/版本、type graph、syntax 分片与绑定证据 | [JVM-03](chains/java-jvm.md#jvm-03)、[IDX-01](chains/index-publish.md#idx-01)、[FW-03](chains/frameworks.md#fw-03) |
| A13 选择范围随方向变化 | 上游 `query/GenericSemanticService.edgeSites` 对内部目标的 incoming 查询保留跨模块来源；Rust `references` 当前只查 target，再统一 `allowed()` 过滤，未按方向选择 | [QRY-01](chains/query.md#qry-01)、[QRY-03](chains/query.md#qry-03) |
| A14 source 的对象与数据来源 | 上游 `SourceCommand` 区分声明、site、`--owner`、文件行范围及注释；`IndexedSourceVerifier` 区分活动源码与快照。Rust 当前从捕获 blob 取范围 | [QRY-02](chains/query.md#qry-02) |
| A15 诊断数量与发布判定 | 上游 `IndexDiagnostic.count`、`AnalysisCoverage`、`IndexDiagnosticRetention` 区分出现次数与保留样本；Rust `staged.rs` 当前诊断汇总按 fact 条数、保留前 100 个样本，doctor 主要报告配置/能力 | [IDX-03](chains/index-publish.md#idx-03)、[QRY-05](chains/query.md#qry-05) |
| A16 封存路径与资源语义 | 上游 `JavaSource.read` 读文件，Lombok 配置读取会遍历/向上查找，`ProjectResourceDiscovery` 使用 scanner；XML 使用 `OfflineXml` | [JVM-02](chains/java-jvm.md#jvm-02)、[FW-01](chains/frameworks.md#fw-01)、[FW-02](chains/frameworks.md#fw-02) |
| A17 注解、配置图与外部实体查询 | 上游 `ReferenceRelations` 的 config 组包含定义/XML/注入/WIRES/handler；`AnnotationQueryService` 支持有界防环元注解；`ExternalDeclarationCatalog` 基于实际观察到的外部目标。Rust annotations 当前只返回 `java.annotation`，references 仅查 Reference | [QRY-01](chains/query.md#qry-01)、[FW-03](chains/frameworks.md#fw-03) |

## 3. S1–S6 存储对齐去向

| 旧项 | 保留内容 | 新任务 |
|---|---|---|
| S1 筛选/聚合 | SQL 聚合；范围先于预算；操作/方向/外部观察范围 | [QRY-01](chains/query.md#qry-01)、[QRY-03](chains/query.md#qry-03)、[QRY-04](chains/query.md#qry-04) |
| S2 定向批读/分页 | 类型化端口、批量邻接、内部游标/公开续页、按批冻结 | [QRY-03](chains/query.md#qry-03)、[QRY-05](chains/query.md#qry-05) |
| S3 投影/条件索引 | 事实权威与同事务重建；声明/关系/注解/config/诊断各随消费者实现 | [IDX-01](chains/index-publish.md#idx-01)、[IDX-03](chains/index-publish.md#idx-03)、[QRY-01](chains/query.md#qry-01)、[QRY-03](chains/query.md#qry-03)、[FW-03](chains/frameworks.md#fw-03)、[INC-01](chains/incremental.md#inc-01) |
| S4 统计/索引治理 | 候选 ANALYZE、受控查询计划、索引空间/维护成本 | [IDX-04](chains/index-publish.md#idx-04)、[QRY-04](chains/query.md#qry-04) |
| S5 反向依赖 | 给定依赖的 Rust 读取/替换与 Java 语义失效分开 | [INC-01](chains/incremental.md#inc-01)、[INC-02](chains/incremental.md#inc-02)、[INC-03](chains/incremental.md#inc-03) |
| S6 容量/临时/写放大 | 已有生命周期复用；归档接管、单项实测优化、最终测量 | [IDX-02](chains/index-publish.md#idx-02)、[IDX-04](chains/index-publish.md#idx-04)、[ACC-04](acceptance.md#acc-04) |

存储源码依据：S1 对照 DataReader.queryKindCounts/queryRelationCounts 与 SearchService；S2 对照 DataReader.nodeIdsForSymbols、DeclarationQueryService；S4 对照 DataWriter.analyze；S5 对照 file_dependencies、symbol_dependencies、dependentsOf。S3/S6 同时参考 StagingSchema、FactIdentityPreparer、CallSitePersistence、SourceBlobStore、IndexFileSwap。Rust 现有路径包括 staged.rs、schema.rs、query-indexes.sql、symbol_search.rs、symbol-search.sql、fact_dedup.rs、put_blob_files/blob；来源类名用于定位，不要求照搬 Java 数据库结构。

现有 prepared/事务、后建索引、FTS、call-site/target、CAS blob、revision/CAS/pin、临时去重库直接复用，见 [IDX 复用基础](chains/index-publish.md#复用的存储基础)。旧库回归不改成新 TDD；本机空间执行约定集中在 [contracts](contracts.md#6-本机磁盘执行约定)。

## 4. P / T / V 阶段改为依赖与门禁

| 旧编号 | 含义 | 新位置 |
|---|---|---|
| P0 / T0 | 来源/契约/旧测试盘点 | [JVM-01](chains/java-jvm.md#jvm-01)、[JVM-03](chains/java-jvm.md#jvm-03)、[ACC-01](acceptance.md#acc-01) |
| T1 | 最小可信运行器，不先重写全部测试 | [ACC-01](acceptance.md#acc-01) |
| P1 / V1 | 独立 JVM 解析与框架 | [JVM-01](chains/java-jvm.md#jvm-01)、[JVM-02](chains/java-jvm.md#jvm-02)、[JVM-03](chains/java-jvm.md#jvm-03)、[FW-01](chains/frameworks.md#fw-01)、[FW-02](chains/frameworks.md#fw-02) |
| P2 / V2 | 固定事实和 mock 驱动 Rust 独立开发 | [IDX-01](chains/index-publish.md#idx-01)、[IDX-03](chains/index-publish.md#idx-03)、[IDX-04](chains/index-publish.md#idx-04)、[QRY-01](chains/query.md#qry-01)、[QRY-02](chains/query.md#qry-02)、[QRY-03](chains/query.md#qry-03)、[QRY-04](chains/query.md#qry-04)、[QRY-05](chains/query.md#qry-05)、[INC-01](chains/incremental.md#inc-01) |
| T2 | 上游测试复制和 Rust 组件重写，随能力滚动 | 各链任务中的 RED/基线和完成条件 |
| P3 / T3 / V3 | 最小 JVM 闭环 → 每能力联调 → fixture → 真实项目 | [IDX-02](chains/index-publish.md#idx-02)、[FW-03](chains/frameworks.md#fw-03)、[INC-02](chains/incremental.md#inc-02)、[INC-03](chains/incremental.md#inc-03)、[ACC-02](acceptance.md#acc-02)、[ACC-03](acceptance.md#acc-03) |
| P4 / T4 / V4 | JVM 完整通过后的 native | [NAT-01](chains/native.md#nat-01)、[NAT-02](chains/native.md#nat-02) |
| T5 | 全部旧测试去向闭合后切换入口/删除旧实现 | [ACC-04](acceptance.md#acc-04) |
| P5 / V5 | 最终产物与容量性能回执 | [ACC-04](acceptance.md#acc-04) |

这些映射保留验收顺序，不要求先完成整层 Java 或整层 Rust 才开始下一个能力；具体依赖以任务的开发/联调前置为准。

## 5. 旧章节迁移清单

| 旧文档 / 章节 | 新正文位置 |
|---|---|
| 01 §1 现状、完整复制、风险边界 | [公共约定 §1/§2](contracts.md#1-冻结依据与产品边界)、JVM-01；A 证据在本页 |
| 01 再次全量核对、裁剪顺序 | [复制与裁剪](contracts.md#2-复制与裁剪)、JVM-01～03、FW 链 |
| 01 §2 三块架构与 Bridge 边界 | [总入口](README.md)、[共用接口](contracts.md#3-共用接口)、JVM-02/03 |
| 01 §3 接口/打批/JVM 入口 | [打批契约](contracts.md#打批契约)、IDX-01/02、NAT-01 |
| 01 §4 阶段、TDD、查询矩阵 | 本页 P/T/V 映射；[统一 TDD](contracts.md#5-tdd-与证据)；QRY-01～05、FW-03 |
| 01 §5 风险/验收边界 | JVM/FW/INC 各任务；[验收退出](acceptance.md#统一退出条件) |
| 02 §1 已有能力 | IDX 复用基础，保持既有回归 |
| 02 §2 S1–S6 含方向/外部、投影/计数补充 | 本页 S 表及对应 QRY/IDX/INC/FW 任务 |
| 02 S6 本机磁盘节约 | [公共执行约定](contracts.md#6-本机磁盘执行约定)；不新增产品门禁 |
| 02 §3 接口/顺序/完成、S TDD 表 | contracts 接口/TDD、各链任务依赖/RED/完成条件 |
| 02 iopengw 存储专项 | ACC-02 检查器；[I0–I8](acceptance.md#索引与生命周期矩阵)/I8 成本；IDX-04 |
| 03 §1 测试资产与全量重写 | [公共测试来源](contracts.md#4-测试来源与组织)、ACC-01/04，实际用例随各任务 |
| 03 统一 TDD/阶段/证据 | contracts §5、acceptance 门禁表；不复制到每条链 |
| 03 §2 A1–A10 / A11–A17 新用例 | 本页 A 表对应任务中的 RED/基线；ACC-02 核对完整范围 |
| 03 原模型/投影/位置/大文件 | JVM-03、IDX-01、INC-01 |
| 03 Lombok/框架/输入/隔离 | JVM-02、FW-01/02、IDX-02 |
| 03 打批与存储 | IDX-01/02、INC-01；阈值 contracts 唯一维护 |
| 03 查询能力（聚合、筛选、搜索、遍历、trace、分页、pipeline） | QRY-01～05、FW-03 |
| 03 §3 增量/故障/发行 | INC-01～03、IDX-01/02、NAT-01/02；旧生命周期复用 |
| 03 §4 磁盘/性能 | contracts §6；IDX-04 的组件证据；[最终测量](acceptance.md#性能与容量口径) |
| 03 §5 真实项目/产物/退出 | ACC-03、I/Q 矩阵与产物、ACC-04 退出表 |

## 6. iopengw 编号保持不变

| 原编号 | 新位置 / 能力负责人 |
|---|---|
| I0 | ACC-03 输入清单；JVM-02 |
| I1 / I4 | ACC-03 物理结构/完整性；IDX-01/04、各 QRY/FW 投影、INC-01 |
| I2 / I3 / I5 | ACC-03 图/全量模型事实/coverage；JVM-03、FW、IDX-03、QRY-05 |
| I6 / I7 | ACC-03 生命周期/历史变更；INC-03 |
| I8 | ACC-03 本轮查询计划和容量；IDX-04、ACC-04 最终三轮测量 |
| Q1 / Q2 | QRY-01 / QRY-02 |
| Q3 / Q5 / Q6 | QRY-03 |
| Q4 / Q7 | QRY-03 + FW-03 |
| Q8 / Q9 | QRY-04 / QRY-05 |
| Q10 | INC-03 |

完整 I0–I8/Q1–Q10 表、预先选例、缺依赖/不适用规则、历史变更对及产物格式唯一保存在 [acceptance](acceptance.md#iopengw-专项矩阵)。

## 7. 不可丢失的差异与范围

| 项目 | 登记方式 |
|---|---|
| 上游构造器身份等缺陷 | 独立复现/原差分/影响范围/后续项；不静默修桥、不改 oracle、不把严格失败改绿 |
| 上游目标 JDK 17/25 | 保留代码与原测试来源；当前产品只验目标 8，其他单列未接入 |
| 原自研专属能力或冲突预期 | 保存旧用例与差异；不能为了旧断言重新写 parser |
| Java app/query/store 耦合 | 解析规则纳入复制，产品 CLI/query/JDBC/发布裁掉；未注册/实验代码按依赖清单解释 |
| 原始绑定与恢复、反射候选 | 保持独立证据/possible/次数，不伪造确定性 |
| 活动源码与历史捕获 | Rust 固定 revision；参考也使用同一冻结内容对照 |
| 诊断与 strict | 行数/次数/样本分开，Rust 发布前拒绝；报告和语义失败独立 |
| 本机磁盘与产品历史/GC | 前者仅执行约定；后者复用既有回归，受行为变化影响时才补 TDD |
