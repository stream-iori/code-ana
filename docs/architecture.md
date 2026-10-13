# 总体架构

对齐 2026-10-13。Rust 管理任务、存储和查询；Java 负责 javac 分析与框架事实。版本与支持范围见 [当前实现](current-state.md)。

```text
CLI
 │ loopback gRPC / HTTP/2 + protobuf
 ▼
用户级 Rust daemon
 ├─ 配置 / 捕获 / 调度 / 取消 / 报告
 ├─ 查询 / pipeline ──────► 固定 revision 的逻辑视图
 ├─ snapshot / diff / source / GC / 人工标注
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
| crates/java-worker | 独立 Rust host、FFI 线程和异步控制 | 一进程一次 G1 isolate 创建 |
| crates/mock-worker | 固定 fixture 回放与协议故障注入 | 显式独立 mock home，无任意源码分析能力 |
| lang-java-adapotr/analysis-core | 迁入的 Anatomist 独立 Java 模型/提取器/框架 | javac 唯一后端，编译器对象不进入模型 |
| lang-java-adapotr/java-bridge | 捕获输入、编译器桥、行输出和 Maven metadata 桥 | Java 8 编译器桥独立构建 |
| lang-java-adapotr/native-bridge、jvm-runner | Native C 入口 / 显式 JVM 执行入口 | Java 25 构建环境与目标 Java 8 分开 |
| tests、scripts | fixture、故障/产品回归、构建/打包 | 开发可显式编译 fixture，不授予产品自动构建权限 |

`lang-adaptor/` 已删除。rust-analyzer 适配器仍为 [后续设计](modules/rust-worker.md)，不存在已交付的 Rust 语言 worker。

## 2. 输入与分析

| 阶段 | 实现 |
|---|---|
| 配置 | 用户服务/工具链 + 项目公共策略 + 语言/module/world + 显式 CLI 覆盖；校验后冻结 |
| 捕获 | Git commit 或工作区清单，复制并校验源码/资源/依赖；工作区捕获不是 OS 全目录原子快照 |
| 发现 | plain 显式根；Maven 从外部 inputs_manifest 读取有效模块/依赖。缺输入返回 BuildAdvice |
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

物理 schema 7，通过 `nodes`、`edges`、`call_sites` 等视图还原逻辑 schema 33。内部整数主键不进入公共输出，FTS 使用真实 `graph_nodes.node_pk`。旧 schema 查询/增量要求 `index --full`，新库成功发布后才切换入口；不原地修改旧库。

graph/catalog/人工标注分别版本化。源码 blobs、分页结果、snapshot/diff/report 等仍由 Rust 生命周期和引用保护管理；人工正文不随图重建删除。

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

当前 macOS ARM64 / Java 8 的 fixture、故障、紧凑存储和冻结 iopengw 单轮已验证。Linux、Java 17/25、Rust 语言分析、完整框架覆盖、当前 raw model/AST 迁移与容量/稳定性能门禁仍待完成；Hello 或 build-only 不代表正式放行。

现行 [契约](contracts.md)、[模块](README.md)、[验证入口](delivery.md) 与 [存储实施记录](implementation/anatomist-storage-migration.md) 分别说明协议、行为和已执行证据。
