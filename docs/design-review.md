# 设计评审与待决项

**Java 后端固定 javac，目标 Java 8 的 Native/G1 分析、图存储与 Rust 产品链路已实现。以下保留决策编号与历史依据；当前状态以源码和本项目回执为准，参考项目通过不等于本项目通过。**

对齐日期：2026-10-13。版本、最新单轮和未完成门禁见 [当前实现](current-state.md)。

## 1. 从目标倒推设计

```text
Agent 要可信答案
    -> 每条关系能回到源码或配置证据
    -> 空结果必须说明覆盖与计算限制
    -> 多阶段查询必须使用同一版本

支持多语言
    -> 共享事实与查询契约
    -> 解析器、名称解析、框架推导留在语言前端

频繁查询同一项目
    -> 已发布结果独立于分析进程
    -> 是否常驻、复用多少资源，由实测决定收益
```

Rust、daemon、SQLite 都是实现选择；正确性首先由输入捕获、事实身份、发布事务和证据约束保证。

## 2. 哪些合理，依据是什么

| 设计 | 判断 | 依据 / 成立条件 |
|---|---|---|
| Rust 编排，语言 worker 分析 | 合理 | 隔离 AST、语言依赖和分析崩溃；Rust 本身不自动带来正确性或提速 |
| Java 内部完成分析后交付 | 已有单轮观测，仍有容量边界 | GRA1 消除了旧 Fact JSON 投影与去重副本；模型/事实视图峰值仍须实测，不能据一次项目宣称容量上限 |
| 保留调用点，再投影方法关系 | 必须保留 | 同一方法对可出现多次；去重关系无法还原调用位置与证据 |
| SQLite 先行 | 合理 | 参考项目有存储、历史和测试经验，本项目已有不可变 revision/CAS 实现；图遍历仍需测批量邻接，不能凭数据库类别决定迁移 |
| daemon 执行整条 pipeline | 合理但性能待验证 | 参考项目单条 pipeline 已共享连接；新增收益主要是跨命令复用与统一调度 |
| 公共模型区分 world / snapshot / profile | 必须保留 | 同文件可能在不同构建上下文中产生不同事实，源码相同不代表语义相同 |
| Java 优先，P2 完善产品能力 | 合理 | 报告、历史、恢复、标注保全仍需验证；无旧版替换门禁 |
| 多语言模型，Rust 后续实现 | 用户已确定 Java 优先 | P0 保留 language/world 扩展边界；Rust 原型不阻塞 Java fixture 闭环 |

## 3. 决策登记

用户已确认 javac 唯一后端、服务与 FFI 方向，以及全新产品、建议式构建、显式语言配置、source 读取策略和 Java fixture 优先；具体实现建议与待验证细节分别列出。用户后续决策优先于原提案，编号供模块设计引用。

| ID | 待决问题 | 建议默认方案 | 状态 / 最晚决定点 |
|---|---|---|---|
| D01 | daemon 管理范围 | 每 OS 用户一个 daemon；内部项目隔离 | 用户已确认，2026-09-24；版本升级协调见 D02 |
| D02 | CLI-daemon 通信 | HTTP/2 + protobuf gRPC，.proto 为源；loopback + token | 已落地 tonic；handshake 3；任务/事件与传输状态分开，规模化背压仍按预算验收 |
| D03 | revision 物理存储 | 不可变 SQLite generation；delta backup，私有候选，catalog CAS | 已落地；当前图物理 schema 9 / 逻辑 33；复制和磁盘峰值单独记录，失败保旧版 |
| D04 | Java 容量边界 | 进程树 RSS、heap、解码/结果/暂存分别限额 | fixture 和冻结 iopengw 单轮已验证；正式容量承诺未完成，不把 heap 当 RSS |
| D05 | 基线与重规划 | 固定 revision/profile；归属替换、有界重规划 | 已落地；旧格式要求全量，worker 不读半成品 |
| D06 | 语言查询策略 | Rust 只读逻辑投影与公共事实，Java 层级/CHA 不重跑 javac | 查询/pipeline 与固定图门禁已实现；raw type/binding/syntax 图查询不支持；Rust 分析仍待实现 |
| D07 | 身份与归属 | 公开 ID/hash/ordinal 与源/module/scope/world/producer 归属 | GRA1 与紧凑布局已验证；内部整数主键不成为公开身份；完整来源覆盖仍有门禁 |
| D08 | 产品与兼容范围 | 全新产品，独立定义 CLI/输出/配置/存储；不做旧版兼容或迁移 | 用户已确认；Anatomist 只提供设计、代码和 fixture 参考 |
| D09 | 目标项目构建 | 读取已有可验证产物；缺失时返回 cwd/argv/目的/预期产物，Agent 或人类自行执行后重试 | 用户已确认；产品不执行 Maven/Gradle/Cargo 编译、build.rs、过程宏或代码生成，不提供 allow 自动执行开关 |
| D10 | 历史与用户数据 | 捕获源码、冻结结果与独立人工标注正文/锚点 | 已落地 catalog/annotation 2；绑定状态保留；标注接入 typed pipeline 未实现 |
| D11 | 项目与 world | project/checkout/target 隔离，module/scope/world 固定 | 已实现 Java 模块/MAIN/TEST；Rust feature world 已接入冻结输入；跨语言符号桥仍未实现 |
| D12 | 验收与性能 | 先 Java fixture，后冻结真实项目；Native 不分冷热，性能一轮 | 当前 214.28 s / 233.49 MB 为单轮观测；正式迁移/容量/稳定性能未关闭 |
| D13 | Java 与互操作 | lang-java-adaptor/，Java 25 GraalVM G1 shared library；独立 Rust C ABI host，无 JNI | 已落地 ABI 4；每进程一次 G1 isolate 创建，故障后换进程；Oracle SDK 固定 |
| D14 | 配置 | 显式 languages.<id>.enabled；公共/语言/module/world 分层 | 严格 TOML 已实现；Java auto 需外部 inputs_manifest；Rust schema 不等于 adapter 能力 |
| D15 | source 读取策略 | 普通 source 校验工作区文件 hash，不匹配报错；历史 source 读取冻结内容 | 用户已确认；这是新产品行为选择，与旧版兼容无关 |
| D16 | Java 解析后端 | javac 唯一后端；不引入 JavaParser/SymbolSolver、不建兼容 AST、不做双后端或失败回退 | 用户已确认，2026-09-29 |
| D17 | javac 执行 | 原生库协调目标 Java 8 子进程，独立模型与桥分别版本化 | 已落地 javac-model-v50 / batch 2；Java 8 字节码 52；17/25 未支持，不称纯原生 javac |

## 4. 下一步决策边界

| 待验证项 | 需要的证据 |
|---|---|
| 完整迁移与来源覆盖 | 当前版本模型/绑定/语法、原始测试清单和图差分；不复用旧版本签名 |
| 容量/性能 | 固定预算和输入的单轮数据；内存/磁盘峰值与查询/取消响应，不先给稳定承诺 |
| 跨平台/JDK/Gradle | 实际构建、运行与产品故障门禁；schema 接受不能代替能力 |
| Rust/跨语言 | 真实 adapter + 已声明能力矩阵；不得用 mock 冒充解析器 |
| 查询/运维限制 | 单条 SQLite 语句取消、调度公平性、标注 pipeline 等按实际代码逐项补齐 |

当前直接依据为 [公共契约](contracts.md)、[Java worker](modules/java-worker.md)、[存储](modules/storage.md) 和 [当前实现与验证边界](current-state.md)。
