# 索引编排与增量

普通配置和公共输入清单共用逐单元执行器。CLI 可显式 prepare，daemon 冻结每个单元的输入、选择 Full/Delta/Reuse，并统一处理 Removed/FilesOnly；有效降级进入 partial，strict 拒绝。只有一个执行单元时保留记录流，多份交付读取各自原归档；失败不发布半图。详见 [统一输入准备](inputs.md)。

> 对齐 2026-10-15。现行版本与支持范围见 [当前实现](../current-state.md)；以下验收表是需保持的行为约束，未完成能力单独注明。

当前 Discover 响应可为 BuildContext 或结构化 BuildAdvice。Java 支持 plain 显式根，以及有外部 inputs_manifest 的 Maven auto 模块展开；无 metadata 的 auto 明确拒绝。mock 只按已知 fixture 输入 hash 回放。FILES 和工作区文本搜索不进入语言发现。

**daemon 决定任务与发布；adapter 决定语言依赖如何失效。增量正确性以同输入全量结果为对照。**

当前使用固定基线、归属替换、私有 candidate 和 CAS；语言失效在 adapter，引用/交付/发布校验在 Rust，关联 D05/D07/D09/D11。

索引核心只接收 `IndexCommand`。IndexPlan 固定基线、单元身份及静态资源份额；公共 index_execution 准备 RunningUnit、选择逐单元意图、验证原始交付并暂存一个候选，再经过质量门禁和一次 CAS。删除 ExecutionPolicy、JavaAttempt/RustAttempt 及 Java/Rust/Python/prepared 组合执行函数。任务事件/预算/发布控制通过 TaskContext，基线及候选通过窄端口接入。已发布结果由 PublishedIndex 交给报告与维护；确认失败记录 warning，继续处理其他实例，不撤销提交。

Java、Rust 保留各自的增量基线与失效闭包；Python 输入变化只使自己的单元 Full。Java bundle/捕获输入/工具链、Rust frozen inputs、Python 描述符均由对应 RunningUnit 持有。临时目录、会话与候选的生命周期覆盖最终消费者，失败清理等待进程回收。

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

Java 封存 GRA1/protobuf rows，worker 25 的 Begin/Chunk 只能进入私有候选，完整 EOF/SHA/End 才允许封存。Rust 解析引用、hash/ordinal、调用点和派生 WIRES，最终写物理 schema 17，查询以逻辑 schema 36 读取；不再经过 CAF1 / Fact JSON 去重数据库。

增量对兼容基线使用 SQLite backup，保持未变节点整数主键并重映射新暂存引用，封存时回收孤立字典/元注解定义并重建索引/FTS。旧格式 delta 返回 GRAPH_SCHEMA_REBUILD_REQUIRED；显式 index --full 只固定旧 head 的 CAS 身份，建新库成功后切入口。G1 worker 复用健康 isolate，失败恢复换进程。

Maven manifest schema 4 记录外部依赖三态。自动源码降级仍通过候选完整性检查；模块级 coverage 参与严格门禁，失败保留旧 head。模式改变使 Java 语义身份变化并全量重建，恢复后补齐关系、再次失效时清旧绑定；无变更不自动运行 Maven。详见 [配置](../configuration.md#maven-依赖失败与源码模式)。

## Python 与混合语言

Python 只读取显式清单并验证全部来源哈希。相同单元 profile/语义输入可复用，变化后该单元全量重算，不强制其他语言 Full。所有执行单元的 End/归档通过校验后才允许发布；Python 不写 Rust 增量元数据。源码和预算见 [Python worker](python-worker.md)。

公共执行器将明确的逐单元意图通过 StageInput::Units 交给混合候选。Reuse 先取得可信 Reused 回执，不交付新行；Java 可继续健康池，其他后端封存账户后退出。存储核对单元身份、profile、语义输入及绑定发布身份，保留该单元的观察和字典归属。准备 generation 与根配置展示字节只按既有例外比较；项目汇总 profile 改变不自动使所有单元 Full。所有单元完整替换时直接建立新库，因此显式 full 可重建旧物理 schema。

worker 终态由所属 RunningUnit 处理。执行单元提交后分别 Ack，Reuse 单元不重复确认；只有账户和 scratch 已解除、进程健康的 Java 单元可入池。任何确认失败都保留已发布 revision，并继续处理其余单元。

普通/prepared 均按后端和稳定 ID 准备、启动 Ready 单元。Vec<RunningUnit> 中每个单元独立拥有会话、输入守卫、manifest 和 Unit Capture，集合最后释放共享 scratch；未启动或移交健康池后的单元不能提供流。输入装箱、manifest 副本及基线序列化继续使用既有 host 预算；Rust 默认进程上限仍为 256 MiB，显式项目上限再按 Ready 数量分片。

PlannedUnit 明确 Ready/FilesOnly/Removed。FilesOnly 和从基线归属清单发现的 Removed 均保留静态后端/实例份额，主机封存零 worker 用量，不能重领或借给 Ready；进程内存只按 Ready 数量分片。普通单元和 prepared 一样使用稳定实例 ID。全部单元禁用/删除时发布空语义及当前 FILES，不启动 Java 兜底；无基线且未启用语言仍报 NO_ENABLED_LANGUAGE。

每个新 revision 持久化 unit_ownership v2。Ready 记录发布语言、模块/world/scope、语义/输入身份、完整输入及各自辅助角色和完整非辅助发布来源，包括零输出；FilesOnly 没有语义发布来源。辅助依赖不取得发布 world。清单与图经同一候选/CAS 提交；删除依据取自旧清单。缺清单或仅有 v1 的旧基线需要 index --full，失败保旧 head。该元数据不改变已有公开 ID。公共执行器已接管任意单元 Full/Delta/Reuse，并在同一候选处理 Removed/FilesOnly；真实五模式 fixture 与 Full 对照一致。

计划分别表达资源参与和实际执行：删除/空模块仍保留预算份额，缺少冻结 Rust/Python 输入时不因此启动对应 worker；实际混合参与语言由计划确定。

Java 输入准备统一经 prepare_java_unit 进入受限 blocking 工作，使用 daemon 冻结的用户配置及 home；单语言、混合、prepared 共享入口。prepared 输入工厂按已规划语言委派现有 Java/Rust/Python 准备器，保留各语言 scratch 与 Python 入口校验策略。

运行结束和失败清理增加显式异步回收：普通/混合/prepared 执行的输入 guard 保留在 fallible 执行体之外，结束后等待所属 worker 进程退出，再释放输入。Hello/Discover 启动校验失败、Java warm rediscover 失败、Rust 基线准备失败也等待回收；已确认且符合条件的 Java worker 继续进入健康池。清理 warning 独立于原执行错误和已提交 revision。

A5 服务边界：索引策略实现于 `IndexTask`，只拿本任务状态、IndexService、WorkerService、ReportService 与发布后维护能力；不读取全局 jobs，也不持有原始 Store。健康 Java 池与 60 秒回收由 WorkerService 拥有；报告与 GC 的异步 join 错误边界、发布后失败结果和原确认顺序保留。

普通、混合及 prepared 的语言输入均使用 config 的单元视图，Analyze/Discover 共用同一单元 manifest。worker 请求绑定逐单元 semantic_profile，候选绑定项目汇总 profile；交付策略 6 纳入两者身份，旧策略先重分析。Java 的增量 descriptor.files 只列可发布源码/资源，避免把控制文件和只读依赖误报成删除 owner；绑定节点、上下文及失效查询仍来自全部声明输入。


## worker 输入租约与回收

所有现行启动入口使用 LaunchSpec 携带冻结入口和 InputLease；Java/Rust 的 prepared scratch、Python 共享 scratch 改为共享拥有，租约同时固定捕获内容的 spool。进程在 Hello/Discover 前取得租约，初始化失败、执行 future 被丢弃也不会先删除输入。

application/supervisor 的 Process 统一保有 child 与租约。显式关闭和 Drop 都先终止所属进程组；独立回收线程保有待回收 child，按有界 OS 快照确认组内无非僵尸成员后才 try_wait 回收 leader、释放租约。leader 未回收前不复用其数值 PGID。任务 runtime 关闭不销毁回收登记。显式停止最多等待两秒；失败/超时独立报告并保留输入，后台继续回收。未确认记录达到 1024 时拒绝新增启动；该阈值不是业务预算的可借额度。

验证过 attempt/revision 的 Acked，或验证过 attempt/grant/可信用量的 Java Reused，才解除健康 worker 的任务租约与 scratch 观察；错误回执保留输入并由调用方关闭。正在进行的磁盘检查也持有旧租约，过期检查不能杀死下一任务的 worker。Java 健康池策略与 RSS 监控仍在既有模块；固定基线、公共装配与混合执行已接通；SQLite VM/cache/匿名临时磁盘及完整 host 计费仍待专项审计。

索引捕获后只读取一次 head，并通过元数据租约持有该 revision，覆盖规划、worker、候选及发布后的处理。后续策略使用计划中的相同 baseline，提交仍做 CAS；GC 在租约释放前保留基线。元数据租约不打开旧 facts.db，因此显式全量可从旧图 schema 迁移；读取前先为元数据解码预留预算。

每个 worker 的 Receiver 在 Begin 时核对 replacement producer 与当前/历史完整输入的精确 world/path，在 Chunk 时核对当前实例冻结来源、模块/scope 和允许语言，失败后不能生成 VerifiedEnd。历史辅助输入只授权删除旧 owner，不授权发布新语义。fixture 允许其显式测试语言标签，仍须遵守来源/world 范围。公共字典/metadata 的单元归属由物理 schema 17 保存，混合候选进一步检查发布范围；读辅助输入不能借用发布权限。

Java 的未变判定比较该单元历史 inputs，避免将全项目 FILES 与单元视图直接比较。增量基线的文件、节点、上下文和类型关系仅取该单元声明输入（含辅助绑定）；变更闭包按 path/world 计算，反向依赖只回到该单元输入，交给 Java 的删除 owner 清单进一步只保留该单元可发布源码/资源；显式 fixture 使用独立测试输入。Python 的汇总 coverage 模块还包括冻结输入描述符中的原生上下文模块，与具体源文件的模块归属分别校验。

所有生产 staging 路径携带主机分配的 Unit/Host/Fixture 来源；普通 Java 流、Rust 增量/混合、Python 和 prepared 不再在存储端口丢弃单元身份。多单元结果逐份读取原主机归档，保留原 count/SHA/EOF 和目录租约，每次只打开一个文件；删除了 Rust 混合和保守/prepared 的二次合并归档。原总输出上限和 Host 尾部准入保留，解码接续原 host 账户。交付策略版本同时进入项目/单元 profile，单元 profile 也纳入注释规则身份；旧策略更新重新分析。持久化观察模块包含实际 BuildContext 模块，存储可据其复核全局 coverage。现已由公共执行器驱动逐单元混合合并；SQL 资源审计和完整 R5/TS 验收仍未完成。

现行单 Java、Rust、普通保守及 prepared 启动统一为 RunningUnit → WorkerService::launch → LaunchSpec/supervisor；只有工厂可启动或 rediscover，架构反例覆盖关联函数别名、嵌套同名文件与绕过 owner 的调用。Java 池 key/基线匹配和错误后禁止重领 grant 的规则在工厂内；mock 是显式 Fixture 输入，须与该服务配置的 fixtures 路径相同。只有账户已结束、任务 scratch 已解除且进程健康时，单元才可移交健康池；移交后为 Released，不能再提供流、上下文或重启。单 Java 流式暂存与候选磁盘检查保持，工厂/装箱/新增 manifest 副本继续使用原 host 预算。

任务进度的 unit_update 记录规划意图；unit_result 记录 worker 已验证的实际 Full/Delta/Reuse，或候选校验后的 Removed/FilesOnly。NeedFull 可使实际结果从 Delta 变为 Full，两者分别记录；预算与 grant 不因此重置。
