# Java 适配器与 worker

对齐 2026-10-14。默认 Native/G1，javac 唯一后端，目标 Java 8；版本见 [当前实现](../current-state.md)，接口见 [公共契约](../contracts.md)。

```text
daemon ── worker protocol 20 ──► Rust Java worker（独立进程）
                                      │ C ABI 4 / FFI
                                      ▼
                               GraalVM Java 25 / G1
                                 ├─ 独立 Java 模型 / 框架 / 失效
                                 ├─ GRA1 protobuf Row 归档
                                 └─ batch 2 ──► Java 8 javac 子进程
                                                javac-model-v50
```

显式 JVM 后端供开发/诊断，使用同一模型和行交付；无自动 fallback。Java 8 桥字节码与 Java 25 共享库分开构建，禁止 JNI、JavaParser/SymbolSolver、注解处理器和业务字节码生成。

FFI 整数状态在边界解码为 `RunState`。Java engine request、bundle manifest、baseline 和 graph descriptor 使用明确 DTO；JVM 重定位只修改已知路径字段，外部输入保持原路径。Maven export 直接构造 `JavaInputs`，计时使用 `PhaseTiming`。sealed descriptor 的诊断副本读取当前 `graph_archive` / GRA1 并复制为 `graph.rows`。

## 1. 输入与模型

| 对象 | 边界 |
|---|---|
| 捕获输入 | daemon 冻结源码/资源/依赖、模块/scope、工具链和有效配置；worker 不读活工作区或重新加载 TOML |
| 编译计划 | 物理模块 × MAIN/TEST；MAIN 不读 TEST，TEST 可读本模块 MAIN；标签只改变发布 module 身份 |
| semantic inputs | 可以大于 publication sources；读取依赖源码不能越界发布 |
| javac 模型 | parse/analyze 后独立模型保存声明、类型、绑定、语法和诊断；Tree/Element/TypeMirror 不外泄 |
| 编码归档 | 分文件模型与共享类型字典，记录/字节/解码估算分别受限；按上下文释放 |
| 绑定与来源 | 确认绑定、局部证据完整和 javac/model/source 恢复来源分开；possible 不冒充 confirmed |
| 坐标 | 基于同一捕获字节/hash，将 Java UTF-16 映射到公共 UTF-8 位置；生成节点不伪造源码范围 |
| 主版本 | 目标 Java 8 当前支持；Java 17/25、混合版本/不支持的模块图明确拒绝 |

Lombok `mode=model` 只补声明 overlay，不执行处理器或合成可运行方法体。Spring/SOFA 消费独立模型和冻结事实视图；静态装配关系是 possible。SOFA 静态 XML 可独立于 Spring，关闭 Spring 不会自动开启它。

## 2. 增量与缓存

```text
捕获输入 + 固定 baseline
 → 比较声明契约 / 绑定 / 失败查找 / 资源依赖
 → 受影响源码提取与 producer 输出
 → baseline - replacement owners + 新事实
 → 资源视图 / 依赖传播稳定
 → 封存 GRA1 → 完整校验与 End → daemon 发布 → PublishAck
```

删除文件、资源或停用 producer 必须清旧归属，包括零输出文件。baseline 是逻辑事实，不等于 javac 类型输入；只有验证过的 NoCode 签名能替代相应依赖源码。

| 缓存 / 复用 | 当前实现 |
|---|---|
| NoCode | 文件/模块签名与独立声明目录；校验无 Code 属性、内容 SHA、版本/依赖/规则身份 |
| 持久管理 | Rust 存储持有目录、锁、256 MiB 共享配额/LRU；任务校验并复制，PublishAck 后提交 |
| 回退 | 缺失/损坏/不完整或声明/外部引用变化时，回退捕获源码；缓存写失败不丢已完成源码结果 |
| 在途 | pin/任务副本不能被淘汰冒充释放；缓存不是目标项目构建产物 |
| 元注解 | AnnotationMetaCache 上下文内复用直接父定义，最多 1,024 项 / 1 MiB；不缓存 AST/截断闭包 |
| javac 进程 | 一个任务内复用，编译上下文逐次释放；跨任务复用健康 host/isolate |
| 并行 | 配置允许 1–8，受总进程树预算预约限制；显式并行不改变输入顺序和语义 |

Maven `selected=false` 模块只保留输入/依赖身份，不作为独立分析根；所选 MAIN/TEST 按依赖读取必要 MAIN 源码，无关未选模块不能因缺 classpath 阻断任务。索引读取外部 inputs_manifest，不启动 Maven；独立导出命令的外部准备见 [配置](../configuration.md)。

## 3. 行交付与内存

| 边界 | 当前行为 |
|---|---|
| 产物 | GRA1 + typed protobuf Row，analysis request/result 10；不生成完整项目 Fact JSON / CAF1 |
| worker | 流式读取/校验原归档，直接交付有界 rows；不运行 Fact JSON 转换或事实去重数据库 |
| 发布 | Begin/Chunk provisional；完整长度、EOF、SHA、行预算通过才发 End；daemon 再校验并发布 |
| 编码 | 固定逻辑列与 NULL/text/int64/bytes，metadata JSON 保留原始文本；减少重复投影/编码与拷贝 |
| RSS | Rust host/原生库同进程计一次，加同时刻所有 javac 子进程；-Xmx 不是 RSS 上限 |
| heap | native 默认 256 MiB，8–2048 MiB 且按 MiB 对齐；javac 默认 128 MiB，8–512 MiB |
| 解码与磁盘 | java_model_decode_bytes 默认 512 MiB；模型归档、结果和 scratch 分别有界 |

## 4. G1 与异步完成通知

```text
worker 进程 A：create isolate（仅一次） → task 1 → task 2 → ...
                   健康结束：复用 isolate
                   取消/致命失败：关闭引擎并退出进程
supervisor：启动 worker 进程 B → 创建新的 isolate
```

本机 Oracle GraalVM G1 共享库不能在同进程创建第二个 isolate，首次 teardown 后也不行。Rust 根据已校验 manifest 记录进程级历史；二次创建在进入 Graal 前返回 `G1_ISOLATE_PROCESS_RESTART_REQUIRED`。Serial 可显式构建，生命周期测试按 manifest 区分：G1 的独立 ABI 用例使用独立进程，同 isolate 多任务与线程 attach/detach 仍验证。

isolate 由固定 OS 线程拥有，Tokio 通过有界命令队列操作。任务 start 前注册非阻塞 Unix socket，库复制 CLOEXEC FD；finally 先发布终态，再写 `0x01` 并关闭。Rust 异步等待通知，再读取一次终态。控制帧持久 FrameReader 保存半帧进度，取消优先；取消与退出共用 2 秒期限，超时回收所属进程组。异常/无终态/通知损坏实例不复用。

## 5. 构建、安装与验证

| 入口 | 含义 |
|---|---|
| ./scripts/build-java-native.sh --build-only | 完整 Maven/Java 8 桥/native 构建，默认 G1；manifest 标记开发构建，不签正式验收 |
| ./scripts/build-java-native.sh | 要求有效 CODEANA_JVM_ACCEPTANCE_RECEIPT 与源码/证据 SHA；不能拿旧版本回执放行 |
| python3 scripts/install-java-native.py | 验证 bundle/worker SHA 与协议，拒绝活跃进程替换；归档旧 SDK，原子安装到 target/lib/java |
| just test-java-native | 构建、安装、manifest、生命周期、当前 GRA1 图回归、真实崩溃 |
| just test-java-native-product EVIDENCE | 当前图门禁，回执 group=graph；不签正式迁移/容量验收 |
| just test-java-release | 开发构建 + release + 包/图/错误 JDK/自带 fixture；保留原路径并恢复开发字节 |

执行文件沿用原 Cargo 路径。`tests/java_release.py --bin-root "$PWD/target/debug" --stage-release` 先检查无活跃进程，归档并原子安装 release 字节，运行后恢复；包与执行路径 SDK/二进制必须逐字节一致。

最新正确性、单轮 iopengw 和未完成验收见 [当前实现](../current-state.md)。过期 raw transport 测试及入口已删除，完整来源覆盖与正式放行仍需新的当前版本证据。

Maven 源码模式通过 worker 20 / analysis request 10 传入；`CapturedProject` 将外部依赖模式与已捕获的完整模块拓扑分开。必要的未选中模块可提供 MAIN 签名/源码，不独立发布。失败模式为参与上下文输出 `CLASSPATH_UNAVAILABLE`，质量证据跨增量和复用保留；主动源码模式不伪造依赖失败。
