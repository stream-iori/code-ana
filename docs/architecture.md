# 总体架构

2026-10-13：Java 输出 Anatomist 类型化 protobuf rows，经 C ABI 4 共享库或显式 JVM 后端交付。Rust 暂存使用 schema 33 元注解归属设计，正式图库采用独立的紧凑 storage schema 7：整数节点引用、共享路径/外部目标及有界 metadata 共享。逻辑视图还原字符串字段，公开 ID 与 GRA1 列契约保持一致；不再持久化完整 Fact IR / CAF1。实施与未完成验收见[存储迁移](implementation/anatomist-storage-migration.md)。

> 2026-09-29 实现状态：Java 8 真实 native/javac 已接入；结构化类型、ASM 外部声明与 owner 级冷增量见 [Java 实现说明](java-implementation.md)，证据见 [验收记录](implementation/java-validation.md)。本页更广的框架与规模化设计仍为目标。

**用语言 worker 生成事实，用 Rust 管理事实生命周期并回答查询。查询只读已发布版本。**

状态：用户已确认每用户一个 daemon、HTTP/2 + protobuf 通信，以及 `lang-java-adapotr/` 的 Java 25/GraalVM C ABI 原生库方案；Java 分析后端仅采用 javac，不保留 JavaParser 或回退路径（D16，2026-09-29）；javac 执行位置采用 D17 目标 JDK 子进程方案（Java 8 fixture 已验证），其余模块细分和发布方式为建议，见 [决策登记](design-review.md)。

## 1. 系统边界

```text
用户 / Agent
     |
  薄 CLI ---- HTTP/2 + protobuf ------ Rust daemon
                                         |
                 +-----------------------+----------------------+
                 |                       |                      |
            查询 / pipeline         索引编排                 版本服务
                 |                       |                      |
          语言查询策略              输入捕获 / 基线        snapshot / diff
                 |                       |                      |
                 |                 worker supervisor            |
                 |                  /           \               |
                 |            Java worker*   Rust 语言 worker   |
                 |                  \           /               |
                 |                   完整结果交付                |
                 |                       |                      |
                 +------ 只读句柄 --> 存储端口 <-- 校验 / 发布 ---+
                                         |
                            SQLite catalog + 不可变事实库
                            源码 blobs / 比较结果 / 标注 / 报告
```

索引、查询、历史是 daemon 内的逻辑模块；首版无需把每个模块做成独立进程或服务。CLI 通道建议使用标准 gRPC。

```text
* Java worker 的进程内部：

显式 JVM：daemon -- worker IPC --> Rust host --> JVM runner --> 目标 JDK javac
                                                   |
                                             同一 java-bridge/CAF1

daemon -- worker IPC --> [ Rust worker host -- C ABI / FFI --> Java 原生库 ]
                        |____________ 一个隔离进程 ____________________|

Java 原生库 = lang-java-adapotr/ 中的 Java 25 + GraalVM Native Image 构建产物
当前内部链路（D17，Java 8）：Java 原生库 -- 编译器协议 --> 目标 JDK javac 子进程
                    <- 独立 Java 模型 -- parse/analyze（禁用处理器）
```

Rust worker host 是 Java 分析器的宿主，与 Rust 语言的 rust-analyzer worker 是不同角色。FFI 只发生在宿主进程内，禁止 JNI；daemon 与 worker 之间仍需 IPC。Java 堆/原生崩溃由 worker 进程边界隔离。

| 模块 | 拥有的职责 | 关键边界 |
|---|---|---|
| CLI | 参数、提交整条任务、stdout/stderr、退出码 | 不选择增量算法，不读取 SQLite |
| Runtime | 连接、任务登记、调度、取消、资源预算、worker 生命周期 | 不解释语言 AST 或 SQL 关系 |
| Configuration | 公共/语言/模块/world 校验、配置来源、工具链解析协调、冻结与指纹 | daemon 统一解析；worker 消费类型化有效配置，不自行重读 TOML |
| Indexing | 捕获请求、固定基线、计划协商、接收、校验、请求发布 | 语义失效规则由 adapter 提供 |
| Java / Rust adapter | 发现语言项目、解析绑定、依赖传播、事实与能力披露 | 不直接写已发布库 |
| Java worker host | 装载 GraalVM 共享库、管理 isolate/线程、转换 IPC 与 C ABI | 位于独立 Rust 进程；不把 Java 库装进 daemon |
| Query | selector、遍历、语言事实策略、证据合并、稳定分页 | 不调用索引去“修好”普通查询 |
| Storage | 定向事实读取、基线导出、候选写入、发布事务 | 不决定派发候选与证据结论 |
| Versioning | 捕获、历史句柄、diff、结果续页、对象引用和 GC | files 路径无需语义构建 |
| Operations | 质量、门禁、doctor、报告、人工标注 | 报告失败不能篡改已发布事实 |

通用派生缓存由 Rust storage 管理持久目录、跨进程锁、内容回执、共享配额和 LRU。Java worker host 为每个任务复制经过 SHA 校验的独立缓存，发布 Ack 后提交已封存条目；取消/失败不提交，任务副本随任务释放。Java 只决定语言签名 key、NoCode 可复用性与任务内编译器 pin，不直接管理产品持久缓存。独立 JVM/native fixture 的本地缓存仍用于语言行为验证。

Java 原生库交付封存 CAF1 描述符；Rust worker 流式校验归档并在任务临时 SQLite 中执行全局事实去重。Java 不保留全项目结果 JSON 和事实去重表。worker protocol 17 允许校验/去重、分块交付与 daemon 私有候选入库重叠；原始 EOF/SHA 和全部身份校验成功后才发送 End，daemon 再核对规范摘要并完成候选/发布。失败丢弃私有候选，旧 head 不变。语言声明与类型图缓存有界、随任务或上下文释放。阶段性能与门禁见 [Native 改造](implementation/native-performance-upgrade.md)。

跨 producer 的静态图派生由 Rust indexing policy 完成，经存储的定向候选读写接口参与原子发布；例如注入点到直接实现的 `wires_possible`。Java 只生成注入/继承的语言事实，Rust 不解析 AST、不替代 Java 类型推断。

## 2. 建议代码组织

`lang-java-adapotr/` 是用户确定的 Java 适配项目根目录；以下子模块拆分为建议，目录不等于必须立即拆成同名 crate。

```text
crates/
  model/          事实、身份、诊断、版本化 facet
  contracts/      任务、worker、输出记录的契约与编码
  config/         多语言 schema、EffectiveConfig、来源与规范化指纹
  application/    runtime、indexing、versions、operations
  query/          查询计划、pipeline、语言查询策略
  storage/        定向端口与 SQLite 实现
  cli/            用户命令入口
  daemon/         装配与进程入口
  java-worker/    Rust worker host，装载 Java 原生库、执行 C ABI 调用
lang-java-adapotr/     Java 适配工程；原生部分使用 Java 25 + GraalVM
  pom.xml         原生部分锁定 release 25、GraalVM/插件；编译器桥单独设字节码目标
  analysis-core/  独立 Java 模型、javac 协调、ASM 二进制分析、增量与内存事实
  compiler-bridge/ 目标 JDK javac 桥与内部协议（D17 候选），与原生库分开构建
  framework-*/    Lombok / Spring / SOFA 的语言适配与框架分析
  native-bridge/  Native Image C entry points、ABI、共享库构建配置
proto/
  codeana/v1/    CLI-daemon 服务与消息；独立的 adapter/Fact IR schema
adapters/
  rust/           rust-analyzer 集成边界，独立 worker
tests/
  contract/       事实回放、协议一致性
  acceptance/     本项目 CLI/事实/恢复行为的 fixture 与 golden
  scenarios/      并发、崩溃、增量、多 world
```

```text
cli -----> contracts <----- daemon 装配入口
              |                  |
              v                  v
            model <------ application
              ^             |       |
              |           query   storage 实现
              |             |
              +-------- 存储只读端口

Java / Rust worker <---- contracts 的 wire schema
```

依赖规则：model 不依赖数据库或进程；query 通过端口取事实；SQLite SQL 留在实现；Java 不需要依赖 Rust crate。Java 构建导出的 C header 约束 Rust FFI 绑定，`.proto` 约束消息编码，两者独立版本化并以夹具校验。原提案的独立 Java native executable 调整为“独立 Rust host + Java native shared library”。

配置统一入口见 [多语言 config.toml](configuration.md)：公共策略 → 语言默认 → module 覆盖 → world 条件 → CLI 显式覆盖，最后进行范围和资源上限校验。用户 daemon/工具链配置独立于可移植项目配置；Java 25 构建工具链与被分析项目的 Java 版本分开。

Java 解析、绑定只走 javac；提取器和框架消费脱离编译器对象的 Java 模型，再输出公共 Fact IR。目标 JDK 子进程方案保留 D13 的 Rust host/C ABI 原生库，但增加 JVM 运行依赖；Java 8 真实共享库链路已通过 fixture 验证，完整 P0 范围另按验收表跟踪。Java 8 编译器桥的字节码兼容与 Java 25 原生库构建规则分开评审，不能把 Anatomist 的 native 主程序通过验收当作内嵌 javac 已可用。详见 [Java worker](modules/java-worker.md)。

## 3. 四条主流程

| 流程 | 顺序 | 一致性单位 |
|---|---|---|
| 全量 | 捕获输入 → 分析 → 冻结 → 传输 → 校验 → 发布 → 报告 | 一个候选 revision |
| 增量 | 固定基线 → 失效闭包 → 合并分析 → 交付替换范围 → 校验 → CAS 发布 | baseline + delta |
| 查询 | 预检 → 选择版本并取得读者引用 → 执行全部阶段 → 汇总证据 → 输出 | 一个 ReadContext |
| diff | 固定两端 → 比较 → 冻结结果和证据 → 按结果 ID 续页 | 一个 ComparisonResult |

同一索引目标串行构建；不同项目可按预算并发。发布只改变入口指针，已在执行的查询持有旧版读者引用。语义快照和普通活动索引可复用同一不可变图产物，但 snapshot 的 Git/捕获身份独立。

## 4. 全局不变量

| 编号 | 必须成立 |
|---|---|
| I01 | 原始输入可追溯，source 读出的字节与捕获 hash 一致 |
| I02 | 缺少完成清单、校验未通过、门禁拒绝的候选都不可见 |
| I03 | 图、coverage、profile、输入身份作为一个 revision 发布 |
| I04 | pipeline 所有阶段共享事实版本；diff 使用两个固定版本 |
| I05 | 同输入与 profile 下，增量和全量的规范化事实及证据相等 |
| I06 | 未解析、未知、未请求、输出截断、枚举结束互不替代 |
| I07 | 原始调用点是调用事实源；方法关系是可重建投影 |
| I08 | 重建或未来本项目 schema 演进失败时保留已发布产物；不承诺读取 Anatomist 数据 |
| I09 | GC 不删除活动入口、固定快照、读者、构建、有效比较所需对象 |
| I10 | 重建不删除人工标注；源码变化导致绑定不明时保留内容并披露 |

## 5. 范围

| Java 产品阶段包含 | 首版不承诺 |
|---|---|
| Java 查询、框架事实、全量/增量、source、历史、diff、报告、恢复 | 全程序精确数据流、堆别名、路径可行性 |
| 本地 CLI + daemon + native worker | 远程多租户服务、分布式部署 |
| 多语言公共事实与配置边界，Java fixture 优先 | Rust 原型作为 Java 阶段的退出门槛 |
| SQLite 可替换存储端口 | 默认 SurrealDB、运行时动态插件市场 |
| 跨语言显式证据模型 | 按同名自动桥接、自动架构推理 |

本项目为全新产品，Anatomist 仅作代码与设计参考，无旧命令/输出/配置/数据库兼容要求。目标项目编译只返回执行建议，交 Agent/人类操作。

依赖与验收顺序见 [delivery](delivery.md)；每个模块的输入、输出和失败处理见对应模块文档。


Native 第二阶段性能改造使用 ABI 3：专属 OS 线程拥有 isolate，Tokio 通过非阻塞 Unix socket 等待任务完成；库持有 CLOEXEC duplicate，终态 finally 通知并关闭。控制帧读取保存半帧进度，取消不依赖周期 poll。事实库 schema 5 将 signature/status 与声明投影直接从 Fact 写入，FTS 保留 rowid；旧事实库显式要求全量重建，catalog CAS 保持。见 [契约](contracts.md#native-完成通知与事实库-schema-5第二阶段性能改造)。
