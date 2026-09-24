# 设计评审与待决项

**结论：职责划分合理，可以进入 P0；尚不足以直接并行实现全部模块。先确定事实所有权、语义查询边界和版本发布方式。**

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
| Java 内部完成分析后交付 | 合理但有容量风险 | `DefaultProjectFactView` 已是集合视图；只能证明边界可拆，不能证明大项目放得进内存 |
| 保留调用点，再投影方法关系 | 必须保留 | 同一方法对可出现多次；去重关系无法还原调用位置与证据 |
| SQLite 先行 | 合理 | 当前已有存储、历史和测试基线；图遍历应先测批量邻接，不能凭数据库类别决定迁移 |
| daemon 执行整条 pipeline | 合理但性能待验证 | 当前单条 pipeline 已共享连接；新增收益主要是跨命令复用与统一调度 |
| 公共模型区分 world / snapshot / profile | 必须保留 | 同文件可能在不同构建上下文中产生不同事实，源码相同不代表语义相同 |
| Java 优先，P2 完善产品能力 | 合理 | 报告、历史、恢复、标注保全仍需验证；无旧版替换门禁 |
| 多语言模型，Rust 后续实现 | 用户已确定 Java 优先 | P0 保留 language/world 扩展边界；Rust 原型不阻塞 Java fixture 闭环 |

## 3. 已核查的证据

| 编号 | 本地来源 | 能证明什么 |
|---|---|---|
| E01 | [原提案](../../anatomist/ana-next-gen/next-gen-anatomist.md) | 架构方向、非目标、迁移顺序；不是性能验证报告 |
| E02 | [DefaultProjectFactView](../../anatomist/anatomist-core/src/main/java/com/anatomist/framework/DefaultProjectFactView.java) | 框架消费 Set / Map / List；存在构造时复制，需计入内存 |
| E03 | [SemanticExecutionContext](../../anatomist/anatomist-app/src/main/java/com/anatomist/cli/SemanticExecutionContext.java) | 当前 pipeline 共享 QueryService、连接与身份 |
| E04 | [FullIndexService](../../anatomist/anatomist-app/src/main/java/com/anatomist/application/FullIndexService.java) | schema mismatch 会先删除旧库与 sidecar；失败保旧版是新增保证 |
| E05 | [命令文档 annotate](../../anatomist/docs/commands.md#annotate)、[StagedGraphStore](../../anatomist/anatomist-core/src/main/java/com/anatomist/store/StagedGraphStore.java) | 当前标注存在重建丢失路径；旧暂存具有 producer 与文件归属概念 |
| E06 | [输出契约](../../anatomist/docs/output-contract.md) | v5、三轴证据、完整记录预算、报告失败与发布状态区分 |
| E07 | [版本管理](../../anatomist/docs/git-snapshots.md) | 冻结比较、源码 blob、读者保护、恢复和 GC 已有验收边界 |
| E08 | [产品基准](../../anatomist/docs/benchmarks.md) | JMH 与产品测试不同；新测量不能替代已有回归门禁 |

以上来自源码和文档阅读；未执行运行时验证。Anatomist `686ee91..39ecdd8` 只变更了提案文件，作为代码复用与设计依据；本项目没有旧版本，不承担其命令、输出、配置、存储兼容或迁移义务。文中的“保留旧 revision”仅指保留本项目上一次成功发布结果。

## 4. 必须澄清的边界

| 模糊点 | 不澄清的后果 | 本轮建议 |
|---|---|---|
| “语义全在 worker”与“Rust 执行派发” | 普通查询重新启动 worker，或 Rust 偷做名称解析 | worker 提供足够事实；Rust 的语言查询策略只消费冻结事实，缺信息则披露限制 |
| 完整结果与增量 dump | 将完整交付误解为必须输出整个项目 | full 交付完整图；delta 交付完整替换范围及其新事实，二者都有结束清单 |
| 最小 BaselineFacts | 缺少未解析查找依赖，新增声明后漏更新 | P0 先验证足够的项目摘要，再逐步缩小；不能先承诺最小基线 |
| 替换范围只写“文件” | 删除文件、XML 重分析、producer 停用留下陈旧事实 | owner 包括 source/resource/world/producer；范围清单和空输出都能表达删除 |
| WorldKey 与 profile | 升级依赖后所有符号身份变化，或错误复用旧事实 | WorldKey 表达配置实例；依赖内容和规则版本进入 profile / 输入摘要；复用同时检查二者 |
| 发布原子性覆盖多个文件 | 图已存在但 catalog 未提交，恢复误认为成功 | 先持久化不可变对象，再提交 catalog 引用；catalog 是发布裁决点 |
| “输入有 hash” | worker 读取到 hash 之后已变化的源码 / JAR | worker 消费捕获副本；无法固定的输入不进入可信发布 |
| 请求取消与提交竞争 | 已发布结果被报告为从未执行 | 发布前可取消；提交后报告已发布，取消只能停止后续工作 |
| 同库保存人工标注 | 重建或 GC 丢失用户内容 | 独立保存标注正文，绑定失败只改变绑定状态 |
| FFI 与进程隔离混淆 | 错把函数调用当 IPC，或把 Java 崩溃引入 daemon | 已确认 Rust host 独立进程，内部 C ABI 调 Java 共享库；两层边界各自定义 |

## 5. 决策登记

用户已确认服务与 FFI 方向，以及全新产品、建议式构建、显式语言配置、source 读取策略和 Java fixture 优先；具体实现建议与待验证细节分别列出。用户后续决策优先于原提案，编号供模块设计引用。

| ID | 待决问题 | 建议默认方案 | 状态 / 最晚决定点 |
|---|---|---|---|
| D01 | daemon 管理范围 | 每 OS 用户一个 daemon；内部项目隔离 | 用户已确认，2026-09-24；版本升级协调见 D02 |
| D02 | CLI-daemon 通信 | HTTP/2 + Protocol Buffers；建议标准 gRPC，`.proto` 为协议源 | 用户确定协议方向；gRPC 库、endpoint 发现、版本升级协调与预算待 P0 验证；worker 通道单独定义 |
| D03 | revision 物理存储 | 不可变 SQLite generation，增量私有副本写入，catalog 原子换指针 | 待验证，P0；复制成本过高再评估 live WAL + 显式快照 |
| D04 | Java 容量边界 | P0 使用 Java fixture 记录各进程内存，覆盖预算耗尽后保留已发布结果 | 用户确定先 fixture；真实项目与正式容量上限暂缓，不作为当前阻塞 |
| D05 | 基线与重新规划 | 固定 revision/profile，缺依赖结束本次 attempt 后扩范围重跑，有界退全量 | 待验证，P0；不让 worker 读取半成品 |
| D06 | 语言查询策略 | Rust 内置 Java/Rust 策略，只读版本化 facets 与公共事实 | 待评审，P0；先选复杂 Java 派发用例回放验证 |
| D07 | 身份与替换所有权 | 采用 contracts 中的复合身份和 owner；hash 编码、位置归一化另冻结 | P0 用 Java fixture 验证，保留 language/world 扩展；Rust fixture 在后续阶段补齐 |
| D08 | 产品与兼容范围 | 全新产品，独立定义 CLI/输出/配置/存储；不做旧版兼容或迁移 | 用户已确认；Anatomist 只提供设计、代码和 fixture 参考 |
| D09 | 目标项目构建 | 读取已有可验证产物；缺失时返回 cwd/argv/目的/预期产物，Agent 或人类自行执行后重试 | 用户已确认；产品不执行 Maven/Gradle/Cargo 编译、build.rs、过程宏或代码生成，不提供 allow 自动执行开关 |
| D10 | 历史与用户数据 | 源码随 revision 捕获；标注独立，历史默认不自动回绑新声明 | 待评审，P1；标注是否另提供历史视图未定 |
| D11 | 项目与 world 边界 | checkout 区分活动索引，repo/project 共享历史；world 显式选择 | 待评审，P0；混合语言不凭同名自动建桥 |
| D12 | 当前验收与性能 | Java fixture 优先；建立本项目功能、故障和性能记录；Rust 与真实项目验证后续开展 | 用户已确认当前范围；fixture 测量不宣称真实大项目容量达标 |
| D13 | Java 适配目录与原生互操作 | `lang-adaptor/` 下独立 Java 25 + GraalVM 项目；独立 Rust worker host 通过 C ABI/FFI 调用 Java native shared library，禁止 JNI | 用户已确认；ABI、内存/线程生命周期、具体工具链版本待 P0 验证 |
| D14 | 多语言 config.toml | 必须显式 `languages.<id>.enabled=true`；无配置或无语言声明时不自动启用；公共/语言/module/world 分层 | 用户确认显式语言选择；其余字段/路径/默认为建议，P0 冻结；无旧配置迁移 |
| D15 | source 读取策略 | 普通 source 校验工作区文件 hash，不匹配报错；历史 source 读取冻结内容 | 用户已确认；这是新产品行为选择，与旧版兼容无关 |

## 6. 优先讨论顺序

```text
D07 身份/所有权 + D06 查询语义
    -> D03 发布存储 + D05 增量基线
    -> D01 服务范围 + D02 进程协议 + D13 原生 ABI + D14 多语言配置
    -> D04/D12 容量与性能
    -> D08/D09/D10/D11 产品边界
```

先用 Java fixture 验证内存闭环、事实表达与发布正确性。fixture 中超预算、事实不足或复制成本异常仍需处理；真实项目的规模与正式性能承诺留到后续阶段，不阻塞当前实施。
