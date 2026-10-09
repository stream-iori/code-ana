# Java 解析接入：按调用链实施

**Java 解析能力 copy Anatomist，再接 Rust CLI/store；按行为做 TDD，JVM 完整通过后才做 native。任务编号是索引，不是施工顺序。**

## 1. 从哪里开始

| 需要做什么 | 文档 |
|---|---|
| 看边界、接口、TDD 和节约本机磁盘的约定 | [contracts](contracts.md) |
| 做 JVM 源码复制、裁剪、输入与模型映射 | [java-jvm](chains/java-jvm.md) |
| 接 CLI/worker/CAF1、批量入库与发布 | [index-publish](chains/index-publish.md) |
| 做查询及其 SQL/投影/索引 | [query](chains/query.md) |
| 做 Lombok/Spring/SOFA/XML 及公开查询 | [frameworks](chains/frameworks.md) |
| 做基线、失效、owner 替换与 full/delta | [incremental](chains/incremental.md) |
| JVM 通过后接 C ABI/.dylib | [native](chains/native.md) |
| 汇总 fixture、iopengw、最终性能和退出条件 | [acceptance](acceptance.md) |
| 查旧 A/S/P/T/V 编号和遗漏项 | [traceability](traceability.md) |

**范围：这次只重组计划文档。产品任务的状态和回执只在各任务正文维护；本次文档完成不代表产品实现或验证通过。**

## 2. 实际调用链

```text
Rust CLI ── gRPC ── daemon ───────────────────────→ 查询固定 revision
                       │                                    ↑
                       └→ 捕获输入 / 调度                   │
                              ↓                             │
                         Rust worker                        │
                              ↓                             │
                 显式 JVM（先） / C ABI native（后）           │
                              ↓                             │
                    共用 java-bridge                        │
                              ↓                             │
                  复制的 Anatomist ←→ 目标 javac 8           │
                              ↓                             │
                     原模型 / 提取 / 语义处理                 │
                              ↓                             │
                  Fact IR / CAF1 / 打批 / 背压               │
                              ↓                             │
                 Rust 校验 / 候选 / 投影 / 派生 ── 发布 ──────┘
```

Anatomist 仅用 dev/replace-parser 的 dff2e3954096853352ec91fcbfc6b995fbd4f390。源码与必要依赖、测试和 fixture 一起复制，裁掉不需要的 Java 产品层；解析规则不在 bridge 中重写。

## 3. 可分别推进的工作与集成顺序

```text
公共约定 + ACC-01 最小测试工具（持续随任务完善）
  ├─ JVM-01 → JVM-02 → JVM-03       原模型/投影
  │                └─ FW-01 → FW-02   框架 JVM
  ├─ IDX-01 → IDX-03 / INC-01      固定事实入库、计数、替换
  └─ QRY-01 / QRY-04 → 其他 QRY   固定事实查询
            │
            ├─ JVM-03 + IDX-01 → IDX-02 最小真 JVM 索引闭环
            │
            └─ 各能力接入真实链路：声明/源码 → 关系/框架/证据
                                  + INC-02/03 全量增量
                                  + IDX-04 统计/容量
                                             ↓
                                  ACC-02 全部 JVM fixture
                                             ↓
                                  ACC-03 冻结 iopengw JVM
                                             ↓
                                  NAT-01 → NAT-02
                                             ↓
                                  ACC-04 最终性能与收尾
```

图是导航，**精确依赖看任务的“开发前置 / 联调前置”**。例如 FW-03 可以用固定事实先做 Rust 查询，等 FW-02/IDX-02 到位再联调；INC-01 不等 Java 增量完成。

| 小步交付 | 做法 |
|---|---|
| 开始一个任务 | 固定独立预期与最小场景；用现有工具或 ACC-01 最小工具跑通失败判定 |
| 组件开发 | 新行为 RED → 最小 copy/对接/Rust 改动 → GREEN → REFACTOR；上游原样已通过只记基线 |
| 纵向接通 | 组件有产出就接真实 CLI→JVM→store→query，保存本能力回执 |
| 阶段门禁 | 相关测试先跑；阶段末完整 fixture；之后 iopengw；JVM 全链路通过才构建 native |
| 保持范围 | 既有历史/pin/GC 回归复用；本机磁盘节约不新增环境治理或 TDD 门禁 |

## 4. 任务索引

状态与回执见链接正文，此表不复制完成标记。

| 调用链 | 稳定任务 |
|---|---|
| Java JVM | [JVM-01 复制依赖闭包和独立 JVM 包](chains/java-jvm.md#jvm-01)；[JVM-02 捕获输入、有效配置和二进制上下文](chains/java-jvm.md#jvm-02)；[JVM-03 原模型、提取 pass 与 Fact IR 映射](chains/java-jvm.md#jvm-03) |
| 索引发布 | [IDX-01 固定事实的批量入库与原子候选](chains/index-publish.md#idx-01)；[IDX-02 真实 JVM 最小闭环和交付生命周期](chains/index-publish.md#idx-02)；[IDX-03 诊断计数、strict 与公开报告](chains/index-publish.md#idx-03)；[IDX-04 候选统计、索引成本与容量改进](chains/index-publish.md#idx-04) |
| 查询 | [QRY-01 主体选择、范围和观察到的外部实体](chains/query.md#qry-01)；[QRY-02 结构与冻结源码](chains/query.md#qry-02)；[QRY-03 关系、类型、运行时候选和有界路径](chains/query.md#qry-03)；[QRY-04 overview 与文本检索 SQL 路径](chains/query.md#qry-04)；[QRY-05 类型化 pipeline、证据与稳定分页](chains/query.md#qry-05) |
| 框架 | [FW-01 插件装配与静态 Lombok](chains/frameworks.md#fw-01)；[FW-02 Spring/SOFA/XML 语义与扩展隔离](chains/frameworks.md#fw-02)；[FW-03 框架、元注解与配置图公开查询](chains/frameworks.md#fw-03) |
| 增量 | [INC-01 反向依赖端口与 owner 原子替换](chains/incremental.md#inc-01)；[INC-02 复制失效和缓存策略，接通基线确认](chains/incremental.md#inc-02)；[INC-03 真实 full/delta 与历史读取回归](chains/incremental.md#inc-03) |
| Native | [NAT-01 薄 C ABI 和完整发行资源](chains/native.md#nat-01)；[NAT-02 native 全链路与 JVM 等价](chains/native.md#nat-02) |
| 跨链验收 | [ACC-01 最小可信测试工具和覆盖清单](acceptance.md#acc-01)；[ACC-02 完整 JVM fixture 与 Rust 独立门禁](acceptance.md#acc-02)；[ACC-03 冻结 iopengw JVM 全链路](acceptance.md#acc-03)；[ACC-04 最终产物、测试入口收尾与性能](acceptance.md#acc-04) |

## 5. 维护与验收

- 公共接口/规则只改 contracts；每个能力的实现、局部测试和状态只改对应任务。
- I0–I8/Q1–Q10、选例/预期、完整分页、历史变更对和产物只维护在 acceptance。
- 来源与旧编号只维护在 traceability；新增任务给稳定 ID，依赖写具体 ID。
- 完成任务时附源码/产物身份、后端、case ID、RED/GREEN 或基线、回归及未完成项；不以文档复选框代替真实证据。
