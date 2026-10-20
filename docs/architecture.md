# 总体架构

对齐 2026-10-20。Rust 管理任务、存储和查询；Java 负责 javac 分析与框架事实。版本与支持范围见 [当前实现](current-state.md)。

```text
CLI
 │ loopback gRPC / HTTP/2 + protobuf
 ▼
用户级 Rust daemon
 ├─ 配置 / 捕获 / 调度 / 取消 / 报告
 ├─ 查询 / pipeline ──────► 固定 revision 的逻辑视图
 ├─ snapshot / diff / source / GC
 └─ worker supervisor
       │ 长度前缀 protobuf，Begin / Row chunks / End
       ▼
   独立 Rust Java worker
       │ C ABI 4 / FFI（同进程，无 JNI）
       ▼
   Java 25 / Oracle GraalVM G1 共享库
       ├─ Anatomist 独立模型 / 提取器 / Lombok / Spring / SOFA
       └─ compiler batch 2 ──► 目标 Java 8 javac 子进程
                              parse / analyze，禁用处理器
       │ GRA1 类型化行
       ▼
   Rust private candidate → 校验 / 紧凑布局 → fsync → catalog CAS
                                                   │
                                         immutable facts.db
```

显式 JVM 开发/诊断后端走 `Rust host → JVM runner → 目标 JDK javac`，交付相同 GRA1。后端显式选择，无自动回退。daemon 不装载 Java 共享库；Java 崩溃隔离在 worker，javac 子进程也归同一进程树预算。

## 1. 模块与真实目录

| 目录 | 职责 | 边界 |
|---|---|---|
| crates/model | 公共身份、查询实体、诊断和版本常量 | 不依赖 SQL/进程；查询 Fact 形状不是持久化格式 |
| crates/contracts、proto/codeana/v1 | gRPC、worker、GRA1 与 Java 控制契约 | .proto、ABI、compiler 协议分别版本化 |
| crates/config | 严格 TOML、显式语言、捕获范围、有效配置与来源 | worker 不重新读配置 |
| crates/application | 调度、监督、索引、版本和运维编排 | 不解析 AST；不自动执行目标构建 |
| crates/storage | catalog、blob、候选/发布、紧凑图与只读投影 | SQL/整数引用封装在存储层 |
| crates/query | selector、pipeline、有界遍历和 Java 静态派发 | 只消费已发布图，不启动分析器补结果 |
| crates/cli、crates/daemon | CLI / gRPC 装配 | 本机用户级服务，按项目/checkout/target 隔离 |
| lang-rust-adaptor / crates/rust-worker | rust-analyzer HIR 与独立 Rust 语言 worker | schema 2 冻结输入；项目与一层直接依赖，过滤后加载；不主动展开宏或执行构建/过程宏 |
| crates/java-worker | 独立 Rust host、FFI 线程和异步控制 | 一进程一次 G1 isolate 创建 |
| crates/mock-worker | 固定 fixture 回放与协议故障注入 | 显式独立 mock home，无任意源码分析能力 |
| lang-java-adaptor/analysis-core | 迁入的 Anatomist 独立 Java 模型/提取器/框架 | javac 唯一后端，编译器对象不进入模型 |
| lang-java-adaptor/java-bridge | 捕获输入、编译器桥、行输出和 Maven metadata 桥 | Java 8 编译器桥独立构建 |
| lang-java-adaptor/native-bridge、jvm-runner | Native C 入口 / 显式 JVM 执行入口 | Java 25 构建环境与目标 Java 8 分开 |
| tests、scripts | fixture、故障/产品回归、构建/打包 | 开发可显式编译 fixture，不授予产品自动构建权限 |

Rust 接入 DTO 经显式映射进入 `TaskCommand`，索引和查询分别使用 `IndexCommand` / `QueryRequest`。查询消费按能力拆分的读接口并输出 `Record`；未知语言 metadata 仅透传。索引阶段持有 worker/candidate，任务与发布接入窄端口，提交后的报告/确认不改变发布事实。编码与历史兼容见 [公共契约](contracts.md)。

`Engine` 私有保存存储、任务和生命周期状态；daemon 通过任务读取、事件和 `TaskWatch` 访问，订阅释放时自动回收 watcher。索引策略依赖 `IndexRepository` / `IndexBaselineReader`，`IndexService<R>` 持有实现关联的候选和基线类型；SQLite、候选持久化与 CAS 位于存储适配器。提交后的报告、历史和维护仍使用具体存储实现。

| Rust 数据边界 | 约定 |
|---|---|
| 身份与状态 | 身份 newtype 保留字符串编码；操作、方向、端点、算法、分析世界、任务状态、错误码、分析模式和阶段记录族使用枚举 |
| 请求与协议 | `Request` 用于接入、配置捕获及持久化；领域/protobuf 逐字段映射，不经通用 JSON 中转 |
| 业务结果 | 稳定实体字段使用 struct / enum；索引、缓存、分组及按名称组织的集合使用 Map |
| JSON | 限于传输/持久化编解码、公共展示、原始协议验证和不解释的 producer 扩展；核心业务不得通过 `wire_map` / `Record::wire` 按键读取字段 |

`Fact.id`、所属 world和 expected revision 使用各自身份 newtype。封闭选项通过 `TryFrom` / `FromStr` 拒绝非法值；producer 状态使用 `FactStatus::Other(String)` 保留未来扩展。Rust 声明种类、可见性、关系、解析状态和分析模式使用封闭枚举。Rust/fixture 通过具名行结构构造 GRA1，Java 原始 protobuf 行继续按原契约验证和交付。

配置解析与输入捕获、Java bundle/工具链/上下文准备、Maven 发现与输出、Rust HIR 采集分别按阶段拆分。Rust 索引明确区分未变基线复用、单语言记录流与混合语言归档合并，各分支仍共享一次校验和原子发布。

语言模型原始 wire arena 继续有界验证；类型图通过节点句柄及 `SingleEdge` / `ManyEdge` 遍历，不展开循环。

`lang-adaptor/` 已删除。Rust 适配器位于 `lang-rust-adaptor`，独立 host 位于 `crates/rust-worker`；能力和限制见 [Rust worker](modules/rust-worker.md)。

## 2. 输入与分析

显式 `index --prepare` 在 CLI 本地通过类型化 provider 准备 Java/Rust/Python 输入，保留各原生工作区回执并原子更新公共清单。daemon 不运行这些工具；捕获阶段验证公共清单及各配置视图，分析单元各持上下文，全部验证后一次 CAS。上下文不可得的入口只保留文件/文本及不可用 coverage；新模式保守全量或相同输入复用。见 [统一输入准备](modules/inputs.md)。

| 阶段 | 实现 |
|---|---|
| 配置 | 用户服务/工具链 + 项目公共策略 + 语言/module/world + 显式 CLI 覆盖；校验后冻结 |
| 捕获 | Git commit 或工作区清单，复制并校验源码/资源/依赖；工作区捕获不是 OS 全目录原子快照 |
| 发现 | plain 显式根；Maven 从外部 inputs_manifest 读取有效模块/依赖。缺有效模型返回 BuildAdvice；依赖失败可使用带证据的源码模式 |
| 分析 | 物理模块 × MAIN/TEST；语义输入可以多于 publication sources；目标 JDK javac parse/analyze |
| 增量 | adapter 决定声明契约/绑定/失败查找/资源依赖失效；Rust 固定基线、检查替换范围 |
| 输出 | 封存 GRA1；worker 流式校验并发送行，daemon 同时写私有候选，End 前不可发布 |

模型分文件读取、上下文释放。声明/类型和元注解缓存有界，不保留 Tree/Element/TypeMirror。资源分析复用快照与查询索引。无需建立完整 Fact JSON、CAF1 或 worker 去重数据库。

NoCode 签名缓存已经接入：Rust 管理持久目录、锁、内容校验和共享配额/LRU；任务复制经过 SHA 校验的条目，PublishAck 后提交。Java 决定语言签名 key、可复用性和在途 pin。缺失/损坏/不完整签名有界回退捕获源码；NoCode 不含可执行方法体，不证明目标项目已编译。

## 3. 存储与查询

```text
full: 新 private candidate
                 ├─ staging → 逻辑列 / hash / ordinal / 调用点 / WIRES
                 ├─ 整数引用与路径 / 外部目标 / metadata 字典
                 └─ 索引 + FTS + 完整性校验

delta: SQLite backup(兼容 baseline) → 替换归属 → 同样封存校验
                                   │
                    持久化不可变 revision → catalog CAS
                                   │
                     老读者保留旧 revision；新查询固定新 head
```

物理 schema 11，通过 `nodes`、`edges`、`call_sites` 等视图还原逻辑 schema 34。内部整数主键不进入公共输出，FTS 使用真实 `graph_nodes.node_pk`。旧 schema 查询/增量要求 `index --full`，新库成功发布后才切换入口；不原地修改旧库。

graph/catalog 分别版本化。源码 blobs、分页结果、snapshot/diff/report 等仍由 Rust 生命周期和引用保护管理。

## 4. 执行与故障

| 边界 | 不变量 |
|---|---|
| 并发 | 每项目一项分析、同目标写入串行；查询使用独立只读上下文；全局有界 worker 与队列 |
| G1 | 健康 worker 多任务复用同一 isolate；teardown 后不能进程内重建，故障恢复启动新进程 |
| 完成通知 | FFI 固定 OS 线程；Tokio 通过非阻塞 socket 异步等待终态，不周期 poll |
| 取消 | 控制帧保存半帧进度；协作取消超时后回收证明归属的 worker 进程组 |
| 预算 | heap、同一时刻进程树 RSS、解码估算、结果/暂存磁盘分别受限 |
| 发布 | 长度/EOF/SHA/End、引用、coverage、门禁和完整性检查通过，先同步对象再提交 catalog |
| 发布后失败 | 断连、ack、报告或清理失败不能撤销已提交 revision |
| 查询 | pipeline/续页固定版本，缺口随阶段传播；历史兼容库读取不依赖当前 JDK |
| source | 普通入口校验当前文件 hash；显式历史入口读捕获 blob，不回退活文件 |

本产品不自动运行 Maven/Gradle/Cargo 编译、注解处理器或业务代码。独立 `codeana-export-java-inputs` 是用户显式调用的外部准备命令，会启动 Maven 并可能下载依赖；索引只读取已导出的 metadata。

## 5. 验证边界

当前 macOS ARM64 / Java 8 的 fixture、故障、紧凑存储和冻结 iopengw 单轮已验证。Linux、Java 17/25、完整 Rust 语义与框架覆盖、当前 raw model/AST 迁移与容量/稳定性能门禁仍待完成；Hello 或 build-only 不代表正式放行。

现行 [契约](contracts.md)、[模块](README.md)、[验证入口](delivery.md) 与 [当前实现](current-state.md) 分别说明协议、行为、验证要求和已执行证据。

Maven 外部准备借鉴 Anatomist `e8a940557c9365084cdd5318ed2be3d147697113` 的依赖失败回执；仅迁入行为，不改整体上游基线。有效模型与依赖可用性分离，源码降级仍走同一 javac / GRA1 / candidate / CAS 链路。索引不启动 Maven，超时不降级。详见 [配置](configuration.md#maven-依赖失败与源码模式)。

Rust 单语言复用有界 GraphStream，blocking 记录生成、异步 IPC 和 blocking SQLite 可重叠；混合语言保留封口归档合并。队列按批数和估算字节限流，控制读取与数据发送分离；内部 VerifiedEnd 前不允许发布。CPU/文件/SQLite 工作保持在受限 blocking 工作中，不使用无界任务或额外无锁队列。存储和诊断细节见公共契约及 Rust worker 模块。

## 任务与发布职责（2026-10-16）

Engine 只装配任务生命周期和业务服务。TaskRunner 管捕获、执行、join 与终态；Scheduler 管分析/只读/维护三个有界通道及项目许可；PublicationCoordinator 管 blocking 发布的完成等待。存储通过任务共享 PublicationFence 裁决取消与提交，catalog 仍是发布事实的依据。取消后不分离存储线程，任务结束时不存在可继续切换 head 的后台工作。

## Python 进程边界

Python 通过独立 Rust worker 嵌入固定 Pyrefly，构建位于独立 Cargo workspace，以隔离 Unicode 依赖版本。输入先捕获再在私有目录恢复，禁止目标解释器/主机环境补全。Python 单语言复用有界候选行流；多语言逐个验证输出后合并，只有一次 CAS。Java isolate 生命周期保持。详见 [Python worker](modules/python-worker.md)。

adapter v2 可在导出阶段显式读取 pyproject 静态元数据；没有该文件的项目继续使用显式输入。配置本身进入 CONTROL 冻结。诊断来源和影响在持有 Pyrefly 事务时按实际反向依赖取得，随后仅交付自有类型；文件级缺口与未知上下文影响分开，辅助及后端诊断均保留。
