# code-ana 设计文档

**设计仍是实施依据；Rust 产品实现与实际能力见 [Rust 实现说明](rust-first.md)，Java 8 当前实现见 [Java 实现说明](java-implementation.md)。**

| 项目 | 基线 |
|---|---|
| 设计来源 | [下一代 Anatomist](../../anatomist/ana-next-gen/next-gen-anatomist.md)，修订日期 2026-09-24 |
| 原提案源码基线 | `686ee91`，Java 25，schema 32，graph semantics 25 |
| javac 参考核查 | 2026-09-29；`dev/replace-parser@2dfc7977cb03095d14894f069ff30985551457b1` 已提交代码，排除工作区修改；未重跑测试 |
| 本项目现状 | 已实现 Rust 配置、任务、worker IPC、SQLite、查询/历史/运维；Java 8 真实共享库/编译器桥/事实/冷增量已接入，mock 保留独立回归 |
| 状态用语 | **原文已定**：继承提案；**用户已确认**：本轮明确选择；**建议**：本轮补充，待评审；**待验证**：需要原型或测量 |
| 用户已确认 | 每个用户一个 daemon，内部隔离项目（D01） |
| Java 适配技术 | Java 相关代码放在 `lang-adaptor/`；Java 25 + GraalVM shared library，由独立 Rust worker 通过 C ABI / FFI 调用，禁止 JNI（D13） |
| Java 后端 | 仅 javac，无 JavaParser/SymbolSolver 或回退（D16）；原生库协调目标 Java 8 子进程已实现，完整 D17 门禁仍按能力范围验证 |
| CLI 通信 | HTTP/2 + Protocol Buffers；建议用标准 gRPC 实现（D02） |
| 产品边界 | 全新产品，无旧版兼容、数据或配置迁移要求；Anatomist 仅作设计与代码参考（D08） |
| 目标项目编译 | 仅返回可由 Agent/人类执行的构建建议，产品不自动执行（D09） |
| 语言选择 | 必须在项目配置显式启用；缺少配置时不自动发现语言（D14） |
| 源码读取 | 普通 source 校验当前文件 hash，变化则报错；历史 source 读取冻结内容（D15） |
| 当前验收 | Java 优先，使用 fixture；真实项目、正式容量门槛暂缓（D04/D12） |

## 阅读顺序

```text
设计评审：为什么这样做、哪些还没决定
    |
    v
全局架构：职责、依赖、主流程
    |
    v
公共契约：身份、事实、协议、证据
    |
    +--> 配置：公共策略 / 语言 / 模块 / world
    +--> CLI / daemon
    +--> 索引编排 --> Java / Rust worker
    +--> 存储发布 <--> 版本 / 源码 / diff
    +--> 查询 / pipeline
    +--> 诊断 / 报告 / 人工标注
    |
    v
实施与验收：P0 -> P1 -> P2 -> P3
```

| 层次 | 文档 | 回答的问题 |
|---|---|---|
| 评审 | [设计合理性与待决项](design-review.md) | 哪些有依据，哪些是风险，何时必须决定？ |
| 全局 | [总体架构](architecture.md) | 系统怎样拆分，各层负责什么？ |
| 契约 | [事实、身份与进程协议](contracts.md) | 各模块交换什么，怎样证明结果完整？ |
| 配置 | [多语言配置设计](configuration.md) | 配置如何分层、显式启用语言、校验与冻结？ |
| 示例 | [项目 config.toml](config.toml) / [用户 config.toml](examples/user-config.toml) | Java/Rust 混合项目与本机服务如何配置？ |
| 当前示例 | [Java fixture config.toml](examples/java-fixture-config.toml) | 首阶段如何显式配置 Java 源码与建议式构建？ |
| 模块 | [CLI 与 daemon](modules/runtime.md) | 如何提交、调度、取消、重连任务？ |
| 模块 | [索引与增量](modules/indexing.md) | 如何固定输入、扩散失效、交付变更？ |
| 模块 | [Java worker](modules/java-worker.md) | javac、独立 Java 模型、原生库和编译器子进程如何分工？ |
| 模块 | [Rust worker](modules/rust-worker.md) | syntax/semantic、world、宏怎样表达？ |
| 模块 | [存储与发布](modules/storage.md) | 怎样保证失败不损坏旧版、读者不混版？ |
| 模块 | [查询与 pipeline](modules/query.md) | 如何组合查询并保留证据缺口？ |
| 模块 | [版本、源码与 diff](modules/versioning.md) | 如何冻结历史、续页、恢复和 GC？ |
| 模块 | [诊断、报告与人工标注](modules/operations.md) | 怎样说明失败、保存报告和用户数据？ |
| 落地 | [实施与验收](delivery.md) | 每阶段交付什么，以什么证据退出？ |

## 使用规则

| 规则 | 做法 |
|---|---|
| 决策集中维护 | `D01`～`D17` 在设计评审中登记，区分用户决策与待验证细节 |
| 契约优先 | 模块实现不能自行修改身份、证据或发布含义 |
| 状态真实 | 建议方案不视作用户已确认；性能、容量、兼容性均需验收 |
| 接口设计 | 本项目独立定义命令、输出、配置与存储契约；Anatomist 示例和测试不能作为兼容门禁 |
| 链接约定 | `../../anatomist/` 等链接指向同级参考仓库；借用源码/fixture 时记录来源并在本项目独立维护 |

Rust 验收和演示见 [根目录 README](../README.md)；Java 8 证据见 [验收记录](implementation/java-validation.md)，正式性能基准未验收。
