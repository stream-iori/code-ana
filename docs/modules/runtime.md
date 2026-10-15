# CLI 与 daemon

> 对齐 2026-10-15。现行版本与支持范围见 [当前实现](../current-state.md)；以下验收表是需保持的行为约束，未完成能力单独注明。

当前 daemon 把提交时捕获、定向查询、Git 读取和发布存储工作放入阻塞线程；每项目一项分析、同目标写入串行，只读查询可并发。取消在查询选择器和源码读取边界生效；单条正在执行的 SQLite 语句、硬 RSS 隔离与严格项目间公平性仍需规模化验证。

文本搜索仅在工作区首页捕获当前配置和源码。显式 revision/snapshot 搜索读取冻结版本的源码 blobs；续页读取冻结分页对象，不重新捕获当前工作区。当前配置损坏或源码变化不得影响历史结果。

**CLI 只表达任务；daemon 管执行、版本句柄和资源。**

D01/D02 已落地：每用户一个 daemon，tonic gRPC/HTTP/2 + protobuf，handshake 3；有界调度与进程树 RSS 采样。协议见 [contracts](../contracts.md)，正式容量阈值仍待验收。

接入 `Request` 经显式映射成为 `TaskCommand`，调度依据命令判断分析与写入许可。任务登记、事件、终态和提交恢复通过 `TaskRepository`；该端口不暴露 SQL 或连接。状态及错误使用枚举，错误分类读取类型化 cause，不拆分错误文案。

`Engine` 的存储、任务集合及生命周期字段私有；daemon 使用 `task`、`task_events`、`watch_task` 和 drain/cancel 方法。`TaskWatch` 用 RAII 维护 watcher 计数，断连释放订阅。`IndexService<R>` 通过 `IndexRepository` 管理关联候选类型，取消在进入持久化前、适配器写对象前及 CAS 前检查；提交后失败不回滚 revision。

## 1. 输入、输出与内部组件

| 组件 | 输入 | 输出 / 责任 |
|---|---|---|
| CLI parser | argv / pipeline JSON spec | 类型化任务；按本项目契约校验参数 |
| DaemonLocator | 用户、协议版本 | 用户级 endpoint 发现；启动竞争用独占注册控制 |
| GrpcGateway | protobuf 请求和应用握手 | unary 任务控制、server streaming 事件、版本不兼容原因 |
| ConfigResolver | 用户配置 generation、项目配置、明确 CLI overrides | 校验并冻结任务配置和字段来源；语言实际环境解析交给发现阶段 |
| TaskRegistry | 请求 ID、任务阶段 | 可重连任务状态，写操作的提交记录 |
| Scheduler | 项目、任务种类、预算 | 有界队列、执行许可、等待/拒绝原因 |
| WorkerSupervisor | 分析计划、worker 描述 | 启动、握手、取消、退出状态、资源计量；Java 场景启动装载共享库的 Rust host |
| ResourceManager | 读者、连接、缓存、任务引用 | 预算执行、缓存淘汰、关闭与回收 |

用户已确认每用户一个 daemon，内部以 ProjectKey/CheckoutKey/IndexTarget 隔离。不同项目共享调度器，不共享可变查询状态、连接事务或无身份缓存。

当前通过本机 loopback TCP 承载 gRPC/HTTP/2，绑定端口由 daemon 登记到用户级运行目录；发现记录包括 endpoint、进程启动身份与协议版本。仅监听本机，随机 token 校验、0700 home 与 flock 单实例共同限制访问；端口发现不等于访问授权。Unix domain socket 可作为同一 gRPC 服务的本地承载优化，须验证库与平台支持，不能发展出另一套业务消息协议。

CLI 持有可复用的 gRPC channel；daemon 的连接生命周期与任务生命周期独立。RPC 定义、protobuf presence、业务状态与传输状态分离详见 [公共契约的进程协议](../contracts.md#3-cli-daemon-与-worker)。

Java 分析进程内部为 `Rust host -> C ABI/FFI -> GraalVM Java shared library`；daemon 不装载该库。host 负责 isolate/线程和缓冲生命周期，supervisor 负责进程级取消兜底与崩溃恢复。host 与库的内存共同计入一个进程 RSS。D17 已接入目标 Java 8 javac 子进程：host/原生库拥有内部协议与上下文，supervisor 登记进程启动身份、归属及退出状态；顶层 worker 退出时必须回收所属子进程。整个进程树计入该 worker 预算，不能把 -Xmx 当 RSS 上限。
默认使用 Native/G1；显式 JVM 用于开发/诊断：`Rust host -> JVM runner -> 目标 JDK javac`。JVM runner 单独验证 bundle manifest 与各文件 SHA；任务请求、结果及取消标记放在私有目录，Java 消费同一捕获输入/bridge。JVM host 与 javac 的 RSS 均计入 worker 进程树；JVM 堆上限复用 java_native_heap_bytes。后端显式选择，无 JVM/native 自动回退。显式 JVM 的宿主 JDK 25 由 daemon 与 worker 共用路径选择，bin/lib/release 内容摘要进入语义身份，发布前重新验证；路径搬迁不改变内容身份，宿主字节变化阻止旧基线复用。

用户配置管理 daemon 生命周期、worker 总资源上限和本机工具链别名；项目配置管理语言/module/world。首版用户配置经 drain/restart 切换 generation，任务固定生效值；项目无权改变单用户 daemon 的服务设置。具体字段与优先级见 [configuration](../configuration.md)。

## 2. 生命周期

```text
CLI 发现用户 endpoint --不存在--> 竞争启动权 --> 启动
       |                                      |
       +------ gRPC 连接 / Handshake <--------+
                       |
                  SubmitTask 登记
                       |
                  排队 / 执行
                       |
                  WatchTask 事件流
                       |
                  终态 / 输出完成

daemon: starting -> ready -> draining -> stopped
worker: starting -> ready -> analyzing -> frozen -> dumping -> idle / stopped
```

退出 drain 时停止接收新工作，等待或取消未提交任务；已提交状态先持久化再退出。空闲超时可释放 worker、连接和 daemon，具体时间在 D12 固定。握手不兼容时返回明确升级提示；协调排空旧服务后才能接替用户级 endpoint，不能顺手杀旧 daemon。升级是否自动协调在 D02 决定。

## 3. 并发与预算

| 资源 | 当前机制 / 约束 | 超限行为 |
|---|---|---|
| 同一 IndexTarget 构建 | 串行；发布仍校验 expected revision | 排队或返回冲突，不覆盖较新入口 |
| 仓库维护 / GC | 与版本捕获和引用变更协调 | 等待并重新检查引用 |
| 查询 | 独立连接和 ReadContext，可并发读取不可变库 | deadline/工作量耗尽，按契约披露 |
| 项目间分析 | 全局预算 + 每项目配额 | 公平排队，不允许单项目无限占槽 |
| CPU 密集解析、遍历、SQLite I/O | 使用受限执行池或 worker | 不阻塞接入和取消控制路径 |
| 缓存 | key 含 project/revision/profile/world/query policy；字节有界 | LRU/TTL 淘汰，只影响性能 |
| 输出连接 | 有背压和慢消费者超时 | 关闭并记录未完整交付，不伪造完整响应 |
| gRPC | 有界消息、流数量、发送队列和 RPC deadline | 不把 HTTP/2 流控视为完整内存限制 |

资源预算需要覆盖 daemon 空闲、索引暂存、查询中间记录、Java/Rust RSS、传输缓冲、磁盘候选和结果缓存；不能只统计 Rust 堆。

Java 默认按预算规划编译上下文并发；显式 java_parallelism 支持 1–8，须通过 host/javac 预约检查。批次接收校验、模型解码、框架提取均有取消检查；不允许长 FFI 或阻塞编译器管道阻断取消入口。严重协议/进程错误结束 attempt，不自动重放。回收阈值需本项目测量，不能照搬参考实现的任务数或堆阈值。

解码预算覆盖跨帧累计对象分配估算、字符串/集合/类型/树/诊断；临时磁盘和已编码待消费结果另有限额。RSS 按同一时刻采样进程树，不累加各进程独立峰值。

## 4. 取消、断连与重试

| 时点 | 处理 | 返回语义 |
|---|---|---|
| 队列中 | 移除任务，释放预约 | cancelled，未发布 |
| 分析/传输中 | 协作取消，超时后终止所属 worker/子进程 | cancelled/failed，候选不发布 |
| 校验完成、事务前 | 再检查取消意图 | 未提交可取消 |
| 提交事务中 | 以最终提交记录为准 | 不猜测成功或失败 |
| 提交后 | 保留 revision，可停止可取消的后处理 | published=true，另列报告/清理状态 |
| CLI 断连 | 查询默认取消；索引默认请求取消，已提交结果可重新读取 | 断连不等于回滚；是否允许后台继续由显式请求控制 |
| daemon 崩溃 | 恢复任务日志和 catalog | 已提交任务不重执行；未提交 attempt 标记中断 |

写任务以 `(client request ID, payload hash)` 检查重复；同 ID 不同内容拒绝，同 ID 相同内容返回现有状态。保留窗口过期后不能声称 exactly-once，应返回未知/过期状态并要求重新检查当前 revision。取消后只终止 supervisor 证明归属的进程。

## 5. 验收

| 场景 | 必须观察到 |
|---|---|
| 两 CLI 同时冷启动 | 只有一个兼容服务赢得对应 endpoint |
| 慢查询 + 新发布 | 查询始终读旧版，后续请求读新版 |
| 放大 Java fixture 分析 + 小 fixture 查询 | 查询能按预算获得服务，取消控制可执行 |
| 断连发生在提交后 | 重连返回已发布 revision，不重复构建 |
| worker 崩溃 | daemon 继续处理其他请求，旧 revision 可读 |
| 客户端/worker 不兼容 | 分析开始前明确失败，无产物损坏 |
| protobuf 新旧客户端 | 兼容字段可演进，未知必需能力或破坏性版本明确拒绝 |
| gRPC 流断开 / deadline / 重订阅 | 事件不重复输出，提交结果不丢，写任务不自动重复执行 |

当前默认预算与未完成规模化门禁见 [delivery](../delivery.md) 和 [配置](../configuration.md)。

JVM 回放阶段使用 `jvm_started/jvm_finished`，独立探针超时为 `JVM_TIMEOUT`；native 继续使用自己的阶段和超时代码。两者的 heap 配置仍使用现有 java_native_heap_bytes 字段。

## G1 的恢复边界

Native 默认 G1。一个 worker 进程仅创建一次 isolate，健康任务复用；teardown 后也不进程内重建。终态通过非阻塞 socket 异步等待，不周期 poll；取消/致命失败退出 worker，由 supervisor 启动新进程。缺失/未知 GC manifest 拒绝加载。RSS 按同时刻 worker 整个进程树采样，超额终止；这是应用级预算执行，不是内核硬隔离。
