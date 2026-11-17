# 索引编排与增量

配置公共输入清单时走多分析单元路径：CLI 可显式准备，daemon 统一捕获并分别分析；有效降级进入 partial，strict 拒绝。模式切换全量清旧绑定；失败/取消/拒绝也有全局摘要。旧单语言流式路径保留。详见 [统一输入准备](inputs.md)。

> 对齐 2026-10-15。现行版本与支持范围见 [当前实现](../current-state.md)；以下验收表是需保持的行为约束，未完成能力单独注明。

当前 Discover 响应可为 BuildContext 或结构化 BuildAdvice。Java 支持 plain 显式根，以及有外部 inputs_manifest 的 Maven auto 模块展开；无 metadata 的 auto 明确拒绝。mock 只按已知 fixture 输入 hash 回放。FILES 和工作区文本搜索不进入语言发现。

**daemon 决定任务与发布；adapter 决定语言依赖如何失效。增量正确性以同输入全量结果为对照。**

当前使用固定基线、归属替换、私有 candidate 和 CAS；语言失效在 adapter，引用/交付/发布校验在 Rust，关联 D05/D07/D09/D11。

索引核心只接收 `IndexCommand`。编排分为 setup、analysis、publication、post-processing；`IndexSetup`、`AnalyzedCandidate`、`PublishedIndex` 持有各阶段所需状态和 worker/candidate。任务事件/预算/发布控制通过本任务 `TaskContext`，公共基线通过 `IndexBaselineReader`、Rust 专用基线通过 `RustBaselineReader`，候选暂存、摘要和发布通过 `IndexRepository` / `IndexService<R>` 接入；关联类型保留各实现的候选与基线所有权。已发布结果与后处理分离，报告/确认失败不能撤销提交。

Rust 路径先准备输入、启动 worker、固定基线，再分别执行未变复用、单语言流或混合语言归档阶段。Java 准备分别持有 bundle、捕获输入与工具链；Maven 外部导出分为准备、发现、组装和落盘。临时目录、会话与候选的生命周期覆盖其最终消费者，阶段拆分不提前释放资源。

普通、混合、prepared 入口先生成内部 IndexPlan：参与语言包含删除/零输出模块，普通/prepared 实例均按后端、稳定 ID 排序并保留原实例身份。计划自身的单元数组在分配前接续任务账本，不提前复制实例 ID。跨语言 worker 的准备、执行与输入生命周期由公共 index_execution 持有；Rust/Python 输入模块只构造自身描述符。单语言流式、混合归档、Java 健康 worker 复用及 Python 保守全量策略保持。

所有路径使用 publish_candidate 的统一质量门禁和发布入口；strict 拒绝在消费 capture blobs 前返回。发布确认仍在 durable commit 后执行，确认失败只记录 warning。

空闲 worker 用 `waitid(WNOWAIT)` 观察退出，保留 PID 至进程组清理；协议错误先留出有界诊断宽限，再结束进程组并回收，取消沿用两秒协作期限后统一清理。回收后停止监控且不再向旧 PGID 发信号。Darwin 对仅有 zombie leader 的进程组可能返回 EPERM；只在非回收探针确认自有 leader 已退出时继续 wait，活进程权限错误仍报告清理失败。

## 1. 端口与状态

| 端口 | 输入 | 输出 |
|---|---|---|
| ConfigResolver | project root、用户配置 generation、项目 TOML、显式 CLI overrides | 经校验的配置请求与字段来源；发现实际环境后冻结 EffectiveConfig |
| InputCapture | project、Git/WORKTREE、扫描策略、已有构建产物 | 冻结输入清单与私有路径 |
| Adapter.discover | 捕获的项目文件、配置 | BuildContext、world、所需依赖和资源 |
| BuildAdviceService | adapter 披露的缺失产物与构建上下文 | 给 Agent/人类的执行建议；不启动目标项目编译、不自动重新索引 |
| BaselineReader.export | revision/profile、所需范围 | 足够本轮分析的 BaselineFacts |
| Adapter.analyze | 输入、基线、计划、预算 | 完整 full/delta，或 ReplanNeeded |
| CandidateValidator | 输入身份、结果、基线 | 完整性、引用、coverage 与门禁结论 |
| Publisher.commit | validated candidate、expected revision | committed revision 或 conflict |

```text
PREPARING -> CAPTURING -> PLANNING -> ANALYZING -> RECEIVING
                            ^            |           |
                            +-- REPLAN --+           v
                                               VALIDATING
                                                    |
                                               COMMITTING
                                                    |
                                               PUBLISHED

提交前任意失败/取消 -> 不发布；释放构建引用，候选等待清理
提交后报告/清理失败 -> revision 保留；后处理状态独立
```

## 2. 输入冻结

| 输入 | 捕获要求 |
|---|---|
| 源码、XML、配置、生成源码 | 清单列出包含/排除规则、相对路径、字节 hash、scope/world；分析读取捕获副本 |
| EffectiveConfig | 多语言/module/world 有效值、字段来源、配置版本及语义/捕获摘要；不以整个 TOML hash 代替语义指纹 |
| 依赖 JAR / metadata | 内容摘要与可用状态；分析用不可变副本或已验证的不可变内容对象 |
| JDK / Rust toolchain | 版本、位置身份和实际分析所需产物指纹；执行期间不可变约束可验证 |
| BuildContext | 从已有输入解析模块图、源码根、classpath/crate graph、target/features/cfg；缺信息返回 BuildAdvice，不代执行编译 |
| adapter / plugins | producer、版本、规则、启停和配置进入 profile |

Git commit 可提供树身份；WORKTREE 捕获声明为一次文件清单与字节的冻结集合，不声称 OS 全目录原子快照。捕获期间发现清单/字节变化则重试或失败；严格时点一致的需求另用只读 checkout。捕获后编辑工作区不影响本次分析。

discover 可提出补充输入需求，但必须先由 daemon 捕获并最终封存 manifest 再分析；分析中发现新输入则重新规划，不能悄悄读活工作区。Agent/人类外部编译产生的文件在新请求中先核验并封存再分析；产品不启动目标项目构建。

PREPARING 固定项目配置字节及用户配置 generation；discover 解析 auto 与真实构建图后，PLANNING 冻结 EffectiveConfig 和各语言分析请求。不同 worker 只收到自己语言的配置、世界条件和公共预算，不读取 TOML。配置详情见 [configuration](../configuration.md)。

发现阶段无法取得完整 classpath/生成产物时，返回 BuildAdvice；能分析的部分按覆盖与门禁处理。产品不运行 Maven/Gradle/Cargo 编译、构建脚本或过程宏。外部准备后必须新建任务，重新核验源码、产物和工具链身份；不能将上次未完成分析就地改成已发布结果。

### javac 分析范围与发布范围

编译计划按物理模块 × MAIN/TEST 建立依赖图；SOURCE 标签不改写 Maven 依赖方向。MAIN 不读 TEST，TEST 可读本模块 MAIN，GENERATED 按冻结构建上下文归属。不支持的混合目标版本/JPMS 图及依赖环明确拒绝。发现须排除工具缓存、内部历史 checkout 和 review worktree，避免把副本当模块。

```text
封存的语义输入（可大于改动集合）
    -> javac 上下文 -> 独立 Java 模型 -> 提取 publication sources
    -> 资源/producer 分析 -> 完整 replacement owners -> delta
```

小 delta 不保证小编译上下文。未变事实是项目逻辑视图的一部分，但 BaselineFacts 本身不能作为 javac 的类型输入；当前以完整源码或验证后的 NoCode 模块签名提供类型输入；不完整签名有界回退捕获源码。必需输入被排除/缺失时明确披露或重规划，不偷偷读取活工作区。

可选签名缓存须保留源码身份、SOURCE 注解和独立声明目录；NoCode 只供索引，不可执行、不证明构建成功。键包含源码、依赖内容及顺序、目标 JDK、编译器桥/模型版本、Lombok 规则/配置。缺签名、冲突或完整性不成立时有界退回封存源码；最终仍须通过 full/delta 等价验收。

## 3. 失效与传播

```text
比较 input manifest / profile
          |
    变更、新增、删除、环境变化
          |
    adapter 计算初始 owner 集
          |
    分析受影响文件并比较声明契约
          |
    成功绑定依赖 + 失败查找桶 + 类型/导入/资源依赖
          |
    扩大受影响集，直到固定点或预算上限
          |
    (baseline - 被替换 owners) + 新事实
          |
    重新绑定与框架资源分析 -> 冻结 delta
```

| 变化 | 处理要求 |
|---|---|
| 方法体修改 | 重算当前 owner 的调用、引用、局部来源；声明契约不变可减少消费者重算 |
| 新增/删除/修改公开声明 | 传播到成功绑定消费者及可能受影响的未解析名称查找 |
| 导入、模块、classpath 顺序 | 可能改变同名解析；不能只比较已绑定 symbol |
| 父类型/接口/Bean 声明变化 | 更新层级、派发依据、Bean 与资源依赖闭包 |
| 删除资源或关闭 producer | 显式替换为空，清除旧关系、依赖、诊断和 coverage |
| 解析/扩展失败 | 丢弃本轮部分输出，清旧 owner；写不完整 coverage，由门禁决定是否发布 |
| toolchain/adapter/规则变化 | 根据能力声明决定失效范围；无法证明局部安全就全量 |
| 仅一种语言的语义配置变化 | 按模块/world 失效；有证明的其他语言事实可复用，新的项目 revision 仍统一原子发布 |
| 语言禁用、模块/world 删除 | 新候选清除相应 owners 并更新 coverage；既有历史保持不可变 |
| 仅报告、输出、GC 配置变化 | 更新相应策略，不单独触发语义事实重建 |

daemon 不自行实现 Java 名称求解或 Rust 宏失效。adapter 负责解释依赖种类并返回闭包；daemon 检查它声明的输入、范围和基线是否自洽。

## 4. 基线不足和发布竞争

| 情况 | 做法 |
|---|---|
| 新 worker，无任务内模型 | 从固定 revision 导出声明/符号/Bean、依赖/查找摘要及必要未变逻辑记录；不导出已移除的 raw type/binding/syntax 表 |
| 不知道最小基线 | 使用已实现的基线摘要与必要逻辑记录，缩减范围须先验证全量/增量等价 |
| 分析发现额外需求 | 返回 ReplanNeeded；当前 attempt 不交付可发布 delta；扩范围重跑 |
| 反复扩展或 delta 太大 | 按执行路径的重规划边界退全量；全量仍超预算则失败保旧版 |
| 基线不兼容 / 无输入证据 | 不使用旧事实；profile 不兼容按执行策略全量，物理格式不兼容明确要求 index --full |
| 发布时 active revision 已变 | CAS 冲突；重新取得基线重新分析，不自动把旧 delta 套到新版 |
| worker 会话结果未提交 | 不可作为下一次基线；丢弃或保持隔离直到 PublishAck |

## 5. 发布前校验和验收

| 校验 | 失败处理 |
|---|---|
| begin/end、段计数和摘要完整 | 接收失败，旧版本不变 |
| 输入、profile、baseline 与请求一致 | 拒绝身份漂移 |
| 每条事实有合法 owner；替换范围覆盖新事实 | 拒绝遗漏与越界替换 |
| 合并后引用可解释，重复身份无冲突 | 合法 unresolved 保留，不静默删除悬挂事实 |
| diagnostics/coverage 包含失败和零事实 owner | 缺披露拒绝；已披露缺口按健康策略评估 |
| 同输入 full/delta 规范化一致 | 验收失败；不以行数近似代替内容比较 |

规范化比较保留重复调用、诊断总数、coverage 和 provenance 的语义内容，只排除 task ID、耗时、数据库行号等非语义字段。测试必须覆盖删除、重命名、未解析转已解析、插件停用、资源依赖、OOM、中断和发布冲突。

## 当前交付与存储边界

Java 封存 GRA1/protobuf rows，worker 25 的 Begin/Chunk 只能进入私有候选，完整 EOF/SHA/End 才允许封存。Rust 解析引用、hash/ordinal、调用点和派生 WIRES，最终写物理 schema 13，查询以逻辑 schema 36 读取；不再经过 CAF1 / Fact JSON 去重数据库。

增量对兼容基线使用 SQLite backup，保持未变节点整数主键并重映射新暂存引用，封存时回收孤立字典/元注解定义并重建索引/FTS。旧格式 delta 返回 GRAPH_SCHEMA_REBUILD_REQUIRED；显式 index --full 只固定旧 head 的 CAS 身份，建新库成功后切入口。G1 worker 复用健康 isolate，失败恢复换进程。

Maven manifest schema 4 记录外部依赖三态。自动源码降级仍通过候选完整性检查；模块级 coverage 参与严格门禁，失败保留旧 head。模式改变使 Java 语义身份变化并全量重建，恢复后补齐关系、再次失效时清旧绑定；无变更不自动运行 Maven。详见 [配置](../configuration.md#maven-依赖失败与源码模式)。

## Python 与混合语言

Python 只读取显式清单并验证全部来源哈希。完全相同的 manifest/profile 可复用；变更后全量重算。含 Python 的多语言任务在所有独立 worker 返回有效 End 后才构造统一候选，严格门禁后一次 CAS；ACK 失败只产生提交后的警告。Python 不写入 Rust 增量元数据表。源码和预算见 [Python worker](python-worker.md)，产品验收状态以该页和当前实现为准。

未变复用的空 delta 候选构建由 stage_reused_revision 共用，普通 Python、prepared 和 Rust 各入口先完成 worker Reused 回执，再调用同一暂存逻辑。基线/profile 校验、取消及后续质量门禁与 CAS 保持。

worker 终态由公共 analysis_unit 的同一 worker 引用集合入口处理，删除 One/Pair/Sessions 类型；Reused 失败仍中止暂存，PublishAck 失败只保存 warning 并继续确认其余实例，不改变已发布 revision。该迭代不复制 worker 或另建预算账户。

prepared 入口以计划中的 Ready 单元确定内存分片和启动语言，按后端和稳定实例 ID 顺序准备/启动，保留降级 coverage。保守与 prepared 集合使用 Vec<RunningUnit>：每个单元直接持有 Prepared/Running 会话状态、输入守卫、manifest 和 Unit Capture，集合最后释放共享 scratch；删除平行 sessions/inputs 数组及其 Deref 入口。构造时核对单元捕获、后端和 manifest 子集，启动时核对 grant 实例，未启动状态不能提供流或上下文。输入装箱的预留接续既有 host 账户；单 Java 健康池及 Rust 增量策略仍保留其自身生命周期结构，R4 统一执行尚未完成。

PlannedUnit 明确 Ready/FilesOnly/Removed。FilesOnly 和从基线归属清单发现的 Removed 均保留静态后端/实例份额，主机封存零 worker 用量，不能重领或借给 Ready；进程内存只按 Ready 数量分片。普通单元和 prepared 一样使用稳定实例 ID。全部单元禁用/删除时发布空语义及当前 FILES，不启动 Java 兜底；无基线且未启用语言仍报 NO_ENABLED_LANGUAGE。

每个新 revision 持久化 unit_ownership v2。Ready 记录发布语言、模块/world/scope、语义/输入身份、完整输入及各自辅助角色和完整非辅助发布来源，包括零输出；FilesOnly 没有语义发布来源。辅助依赖不取得发布 world。清单与图经同一候选/CAS 提交；删除依据取自旧清单。缺清单或仅有 v1 的旧基线需要 index --full，失败保旧 head。该元数据不改变图物理 schema 或已有公开 ID；任意单元 Full/Delta/Reuse 混合仍待公共执行器接管。

计划分别表达资源参与和实际执行：删除/空模块仍保留预算份额，缺少冻结 Rust/Python 输入时不因此启动对应 worker；实际混合参与语言由计划确定。

Java 输入准备统一经 prepare_java_unit 进入受限 blocking 工作，使用 daemon 冻结的用户配置及 home；单语言、混合、prepared 共享入口。prepared 输入工厂按已规划语言委派现有 Java/Rust/Python 准备器，保留各语言 scratch 与 Python 入口校验策略。

运行结束和失败清理增加显式异步回收：普通/混合/prepared 执行的输入 guard 保留在 fallible 执行体之外，结束后等待所属 worker 进程退出，再释放输入。Hello/Discover 启动校验失败、Java warm rediscover 失败、Rust 基线准备失败也等待回收；已确认且符合条件的 Java worker 继续进入健康池。清理 warning 独立于原执行错误和已提交 revision。

A5 服务边界：索引策略实现于 `IndexTask`，只拿本任务状态、IndexService、WorkerService、ReportService 与发布后维护能力；不读取全局 jobs，也不持有原始 Store。健康 Java 池与 60 秒回收由 WorkerService 拥有；报告与 GC 的异步 join 错误边界、发布后失败结果和原确认顺序保留。

普通、混合及 prepared 的语言输入均使用 config 构造的单元视图；Java setup 保留自己的 Unit Capture，Analyze 与 Discover 使用同一 manifest。结果先经单元身份/终态验证，存储候选 header 再绑定全项目 revision 清单。原有增量/保守策略和单一发布门禁继续保留，尚未实现同一任务内任意 Full/Delta/Reuse 的统一执行。


## worker 输入租约与回收

所有现行启动入口使用 LaunchSpec 携带冻结入口和 InputLease；Java/Rust 的 prepared scratch、Python 共享 scratch 改为共享拥有，租约同时固定捕获内容的 spool。进程在 Hello/Discover 前取得租约，初始化失败、执行 future 被丢弃也不会先删除输入。

application/supervisor 的 Process 统一保有 child 与租约。显式关闭和 Drop 都先终止所属进程组；独立回收线程保有待回收 child，按有界 OS 快照确认组内无非僵尸成员后才 try_wait 回收 leader、释放租约。leader 未回收前不复用其数值 PGID。任务 runtime 关闭不销毁回收登记。显式停止最多等待两秒；失败/超时独立报告并保留输入，后台继续回收。未确认记录达到 1024 时拒绝新增启动；该阈值不是业务预算的可借额度。

只有验证过 attempt/revision 的 Acked 才解除健康 worker 的任务租约与 scratch 观察；错误 ACK 保留输入并由调用方关闭。正在进行的磁盘检查也持有旧租约，过期检查不能杀死下一任务的 worker。Java 健康池策略与 RSS 监控仍在既有模块；完整后端装配、公共监控策略、固定基线及混合 Full/Delta/Reuse 执行仍待 R4。

索引捕获后只读取一次 head，并通过元数据租约持有该 revision，覆盖规划、worker、候选及发布后的处理。后续策略使用计划中的相同 baseline，提交仍做 CAS；GC 在租约释放前保留基线。元数据租约不打开旧 facts.db，因此显式全量可从旧图 schema 迁移；读取前先为元数据解码预留预算。

每个 worker 的 Receiver 在 Begin 时核对 replacement producer 与当前/历史完整输入的精确 world/path，在 Chunk 时核对当前实例冻结来源、模块/scope 和允许语言，失败后不能生成 VerifiedEnd。历史辅助输入只授权删除旧 owner，不授权发布新语义。fixture 允许其显式测试语言标签，仍须遵守来源/world 范围。无来源的公共字典/metadata 的单元归属、存储按 world 精确替换及任意 Full/Delta/Reuse 组合仍待后续完成。

Java 的增量基线文件列表从该单元历史 inputs 取得，不将项目 FILES 清单传给引擎用于生成删除 owner；显式 fixture 的旧基线保持其测试输入。Python 的汇总 coverage 模块还包括冻结输入描述符中的原生上下文模块，与具体源文件的模块归属分别校验。
