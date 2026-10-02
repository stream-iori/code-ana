# 实施与验收

> 2026-09-29 实现状态：Java 8 native/javac 已接入，实际范围与证据见 [验证记录](implementation/java-validation.md)。本页完整复合门禁仍逐项推进。

**Java 优先，先用 fixture 完成分析、发布和查询闭环。本项目没有旧版本，不设旧版兼容或迁移门禁。**

状态：用户已确定 Java 25/GraalVM FFI、HTTP/2 + protobuf、显式语言配置、外部构建建议以及 Java fixture 优先。Java 后端仅采用 javac（D16），D17 编译器执行方案已通过 Java 8 fixture；Rust mock 的部分测试已执行，逐项状态见 [验收表](../todo/05-acceptance.md)，Java 8 已执行子集单列 [J0–J6](../todo/08-java-worker.md)。

## 1. 阶段与交付

```text
P0 Java fixtures + javac 执行边界原型 + 模型/契约 + FFI/发布原型
  |
  v
P1 CLI -> gRPC daemon -> Rust host / Java 原生库 + javac -> SQLite -> 查询/pipeline
  |
  v
P2 完善 Java 增量/历史/diff/报告/恢复/GC/标注
  |
  v
P3 Rust syntax/semantic 已声明能力与混合语言验证

多语言模型和配置边界从 P0 保留；Rust 实现不阻塞 Java 主线。
真实项目与正式容量指标暂缓；SurrealDB、daemon 内嵌 worker 独立实验。
```

| 阶段 | 工作包 | 交付物 | 退出条件 |
|---|---|---|---|
| P0-A 夹具 | 选取/编写 Java fixture，记录源码、生成产物、预期事实 | 本项目 fixture manifest、源码/工具链摘要、预期记录 | 夹具可重复准备，预期来自源码语义，不盲目复制其他产品输出 |
| P0-B 契约 | Fact IR、owner、证据、配置、protobuf、C ABI、CLI 输出草案 | schema、类型与本项目 golden | Java 声明树、重复调用、框架证据不丢失；语言/world 可扩展 |
| P0-C 分析 | 先验证 shared library/目标 JDK 编译器桥，再验证独立 Java 模型、Rust FFI host、冷启动基线 | 原生库/header/manifest、full/delta 对照、fixture 内存记录 | 实际原生产物可运行；线程与缓冲生命周期正确，超预算保留已发布结果 |
| P0-D 执行/存储 | 用户级 daemon、gRPC、读者引用、原子发布、失败隔离 | 并发/流中断/崩溃原型 | 能判定是否提交；缺输入只给外部建议；语言不会自动启用 |
| P1 Java 闭环 | 全量/冷增量、核心查询、pipeline、Spring/SOFA | 可运行垂直切片 | Java fixture 上功能与证据断言通过 |
| P2 Java 完善 | 历史/diff、报告、GC、恢复、标注 | 本项目产品验收报告 | 声明支持的操作与故障/预算用例通过，无半发布或证据丢失 |
| P3 Rust | syntax/semantic 子集、world、宏、混合项目 | 能力矩阵与 Rust fixtures | 所声明能力可验证，缺口明确；不自动执行 build.rs/过程宏 |

P0-C 分成 P0-04a 执行可行性和 P0-04b 实际事实闭环；04a 从首批 fixture 开始，不等完整配置/存储实现，使用手工冻结的类型化输入，结论反馈模型与配置设计。

建议首个实现切片：Java fixture 的手工/提取事实 → SQLite candidate → 原子发布 → resolve/call-sites/source。再接入完整 Java 提取与冷启动增量。

## 2. Fixture 组织与准备

| 夹具族 | 内容 | 首批验收目标 |
|---|---|---|
| java-basic | 类型、字段、构造器、重载、继承、重复调用 | 声明身份、调用点与去重关系、源码范围 |
| java-modules | 不同源码集、多模块、外部 JAR | 候选 Java 8/17/25 与 adapter Java 25 分离、MAIN/TEST、物理模块/标签、语义输入/发布范围 |
| java-frameworks | Spring Java/XML、SOFA、Lombok | producer 归属、来源、模型缺口和扩展关闭后的清理 |
| java-incremental | 修改/新增/删除/重命名、未解析转解析 | 同输入 full/delta 等价、失效闭包 |
| java-build-advice | 缺依赖、缺生成源码、编译后产物 | 产品仅给命令建议；外部准备后重新提交才消费产物 |
| java-lifecycle | 临时 Git/WORKTREE、可修改源码、比较续页 | source hash 拒绝、历史冻结、diff、GC/读者竞争 |
| java-budget | 可参数化扩大的文件/调用点集合 | 有界资源、失败保留已发布结果；不等于真实项目容量验证 |

```text
开发者 / 测试准备流程：准备 fixture，可显式编译并固定产物
                             |
                             v
产品测试：配置 Java -> 分析已有输入 -> 查询 / 发布 / 取证
                             |
             缺构建输入 -> 返回 BuildAdvice，验证未启动构建/处理器或业务字节码生成
```

产品允许禁用处理器的 javac parse/analyze，执行记录须与目标项目构建区分。

测试准备与产品执行路径必须分开，防止预编译夹具掩盖产品偷偷自动构建的行为。本项目 Rust/Java 构建和 mock fixture 准备不涉及产品自动编译目标项目。

可参考 [Anatomist fixtures](../../anatomist/fixtures)、[语义场景](../../anatomist/tests/scenarios)、[框架设计](../../anatomist/docs/framework-plugins.md) 和 [基准方法](../../anatomist/docs/benchmarks.md)。借用时记录来源 commit、文件 hash 和准备步骤；夹具与预期在本项目独立维护，不依赖 sibling 仓库未来 HEAD。无需保存旧二进制、旧输出协议或建立兼容映射。

## 3. 功能矩阵

以下名称表示设计中的操作族；最终 CLI 命令、输出格式、默认值与退出码在本项目 P0 冻结。

| 能力组 | P1 | P2 |
|---|---|---|
| 索引 | full、冷增量、Spring/SOFA | 环境变化、重规划、冲突、门禁、预算、删除清理 |
| 查找阅读 | resolve、search-code、structure、source | search-text、外部声明、hash 校验、完整续页 |
| 关系导航 | calls、call-sites、references | annotations、类型/方法关系、dispatch、trace、overview |
| pipeline | 类型组合、固定版本 | 能力预检、预算、证据传递与恢复动作 |
| 版本 | 内部 revision | snapshot、Git/WORKTREE、files/analysis、linked worktree |
| diff | 可延后 | 双端文本/声明/关系/影响、冻结结果、续页过期 |
| 运维 | 基本诊断与任务状态、构建建议 | doctor、reports、恢复、GC、报告级别 |
| 用户数据 | 独立标注存储 | 本项目标注保全、绑定歧义；无旧产品标注导入要求 |
| 分发 | 本地原型 | CLI/daemon/host/共享库/编译器桥/ABI/协议成套验证，目标 JDK 前置条件明确 |

未完成的操作明确 unsupported；P2 以本项目声明的范围验收，不承担替换 Anatomist 的兼容义务。

## 4. 正确性与故障验收

| 编号 | 用例 | 判定 |
|---|---|---|
| T01 | full vs incremental vs 会话复用 | 同输入/profile 的事实多重集、coverage、诊断、来源一致 |
| T02 | 修改/新增/删除/重命名、失败查找转成功 | 消费者重算，无陈旧 owner 或漏关系 |
| T03 | 插件关闭/失败、XML 删除、Lombok 受限 | 旧事实清理，质量披露正确 |
| T04 | Java 调用重复、外部符号、派发 | 对照源码标注的 fixture 预期，区分候选与已绑定目标 |
| T05 | 多 world、宏、cfg | Rust 阶段验证；不作为 P0/P1 退出门槛 |
| T06 | 缺块/乱序/错误摘要/错误 owner/基线冲突 | candidate 拒绝，已发布 head 不变 |
| T07 | 提交窗口 crash、磁盘满、worker OOM | catalog 裁决可恢复，无半发布 |
| T08 | pipeline 与发布/标注更新并发 | 单请求事实与标注分别固定版本 |
| T09 | 查询/索引/diff 与 GC 竞争 | 引用对象不被删除，失效引用可回收 |
| T10 | 取消/断连/重连/重复写请求 | 提交状态真实，不误重执行 |
| T11 | 工作区源码变化、历史 source、diff 续页 | 普通 source 拒绝 hash 不匹配；历史与比较不漂移 |
| T12 | 输出/记录/深度/状态预算 | 完整记录、真实 returned、明确限制 |
| T13 | 本项目重建/未来 schema 演进失败 | 已发布产物和人工正文保留；不测试旧产品库迁移 |
| T14 | 发布后报告/清理失败 | 提交与后处理状态分开表达 |
| T15 | GraalVM shared build、C header、isolate/线程、buffer | Java 25 原生库可被 Rust 调用；无 JNI，生命周期正确 |
| T16 | Java 异常/native 崩溃/FFI 取消 | 异常不越 ABI；致命错误止于 worker |
| T17 | protobuf 演进、presence、未知枚举、握手 | 未知值不当成功，破坏性变化拒绝 |
| T18 | gRPC 中断/超时/重订阅/慢读者 | 传输与任务状态分开，事件去重、背压、幂等成立 |
| T19 | 显式语言配置、缺配置、只有报告配置/manifest | 只调度启用语言；未声明时拒绝语义索引，已有历史可读 |
| T20 | 配置注释/报告变化、禁用语言/模块 | 语义摘要正确，不无故重建，owners 清理正确 |
| T21 | CLI 覆盖、数组/范围、排队时改配置 | 来源与生效值明确，任务配置固定 |
| T22 | 未知键、无效 world、TOML/protobuf | 明确错误；worker 获得相同类型化配置，不加载其他产品配置 |
| T23 | 编译依赖/生成源码缺失 | 返回 cwd/argv/目的/预期产物，未启动构建或处理器；关闭建议仍披露缺口 |
| T24 | 外部执行建议后重新索引 | 重新捕获并验证新产物与输入对应，不仅凭文件存在复用 |
| T25 | javac 原生链路、目标 JDK/桥版本、错误环境变量 | 实际共享库与编译器链路；缺 JDK/错版本拒绝，无后端回退 |
| T26 | 绑定确认/证据完整、泛型/SAM、歧义/不可访问、大量诊断 | 可靠目标保留，恢复候选不冒充 confirmed；诊断收集耗尽不伪装完整 |
| T27 | 源码注解/二进制名/UTF-16→UTF-8/生成节点 | 源码 hash 与范围正确，生成节点不造位置，不丢 SOURCE 注解 |
| T28 | 语义输入/发布/owner、物理模块/标签、MAIN/TEST | 读取不越界发布；依赖方向正确；混合版本/JPMS 不支持时明确拒绝 |
| T29 | 上下文释放、同名类型、多轮取消、解码/磁盘超限 | 不串上下文，进程树可回收，失败保旧版 |
| T30 | 签名缓存启停/损坏/冲突、线程数与输入顺序变化 | 事实多重集与证据等价，有序 classpath 保序；性能放行独立 |

## 5. 当前测量范围

| Java fixture 负载 | 记录 |
|---|---|
| 冷启动与连续查询 | 启动/握手/任务耗时，daemon 和 worker 进程树的同时刻 RSS（host/原生库同进程只计一次） |
| 单命令与 pipeline | protobuf/FFI/IPC 开销、查询和渲染时间 |
| 全量与冷/热增量 | 基线导出、分析、编码、写入与发布耗时 |
| 不可变 revision | 复制量、候选磁盘峰值、GC 后占用 |
| 并发/慢读者/取消 | 排队、响应和资源释放；使用可控夹具制造场景 |
| 历史/diff/恢复 | 捕获、比较、续页、重启恢复的正确性与耗时 |

记录 fixture 版本、生成参数、实际机器/工具链、预算和重复次数即可；当前不要求用户提供真实仓库或正式容量上限，不套用 Anatomist 产品性能门禁。Java 基线先在本项目建立，后续真实项目和 SurrealDB 对照另行安排。fixture 通过不代表大型项目容量达标。

## 6. 文档验收

| 项目 | 检查 |
|---|---|
| 导航与示例 | 本地链接有效、Markdown 围栏完整、TOML 可解析 |
| 决策一致 | javac 唯一后端、D17 状态真实、无旧版兼容门禁、无自动目标构建/处理器、无隐式语言启用、source hash 行为一致 |
| Java 优先 | P0/P1 以 Java fixture 验收，Rust/真实项目不阻塞 |
| 工作范围 | Rust 产品 + 独立 mock 已验收；Java FFI Hello 保留并验证，真实 javac/native 分析仍待验收 |
