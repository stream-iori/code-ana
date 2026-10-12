# 公共约定

[入口](README.md) · [来源与旧编号](traceability.md) · [跨链验收](acceptance.md)

**本页统一维护边界、接口、TDD 和执行约定。任务细节留在对应调用链。**

## 1. 冻结依据与产品边界

| 项目 | 约定 |
|---|---|
| 核对日期 / code-ana | 2026-10-09；HEAD afbe8ed972dfa7322b0bf1d97ca60d231cb1540f |
| Anatomist | 只取 dev/replace-parser 已提交代码 dff2e3954096853352ec91fcbfc6b995fbd4f390；不混用其他分支或工作区改动 |
| iopengw | 267c7ced4993aa88613f8f17a8099943fec98159；基准仅用已提交内容 |
| 当前状态依据 | 2026-10-10 Java 已恢复，显式 JVM 链路接通；完整迁移实施中。历史 native 回执不作为本次门禁，逐项核对实际文件与新回执 |
| 架构依据 | [architecture](../docs/architecture.md)、[contracts](../docs/contracts.md)、[storage](../docs/modules/storage.md)；实施时发现冲突先查代码，再同步设计与实现 |
| 三块边界 | Rust CLI/query；复制的 Java 解析；Rust storage/application。Java 放 lang-java-adapotr/ |
| 解析 | 仅 javac；解析、类型/绑定、恢复和框架规则直接 copy Anatomist。桥只做边界适配，不重写 Java 算法 |
| 工具链 | 宿主 GraalVM JDK 25，.sdkmanrc 固定；目标 Java 8 javac 子进程，桥字节码 major 52 单独验证。上游目标 17/25 能力保留来源，当前标未接入 |
| 构建边界 | 语义语言显式启用；FILES/文本搜索可无语言配置。Maven inputs_manifest 外部准备；产品不自动编译目标工程、不运行处理器。开发可显式编译本项目和 fixture |
| 执行后端 | JVM 为显式开发/验收入口；最终 native 为 Rust worker + C ABI，无 JNI。native 故障不自动回退 JVM |
| 对齐口径 | 解析能力完整复制，CLI/存储按 code-ana 契约对接；不要求 Anatomist 命令、输出或数据库协议兼容 |
| 已有功能 | snapshot/diff/人工标注/历史/GC 复用 Rust；已有测试作回归，受本次行为变化影响时才加专项 |

## 2. 复制与裁剪

```text
冻结调用链与逐文件清单
  → 复制源码 + 测试 + 资源 + 构建规则，登记原 SHA
  → 裁掉不被解析依赖的 Java CLI/query/JDBC/发布实现
  → 把耦合点接到输入、事实视图和存储端口
  → 原模型 / 提取 / 最终图逐阶段对照
  → JVM 完整链路通过 → native
```

| 清单字段 | 要求 |
|---|---|
| 四种去向 | 原样复制 / 边界适配 / Rust 承担 / 非产品依赖；逐文件注明理由和真实调用依据 |
| 来源 | 原路径、commit、原始 SHA、迁入路径/SHA、许可证、包名、资源和测试去向 |
| 裁剪边界 | 上游 app/store 中若含解析或语义规则，先分离并保留原规则，再替换产品 I/O；不整类删除后在桥中重写 |
| 生产能力范围 | 核对根 POM 的四个生产模块、真实注册入口及依赖；旧工具类、未注册查询、实验/JMH 单列，不据类名增加产品命令 |
| 特殊构建 | worker/Maven worker 特殊源码、Plexus 和包内资源列入闭包；未进入产品的部分说明原因，参考测试需要的保留在测试侧 |
| 完整性 | 原模型、各提取 pass、身份/绑定、框架、派生、公开查询均有消费者；不能保存一份 JSON 就宣称完成 |
| 已知上游缺陷 | 如构造器身份冲突，保留复现、原始差分、影响与后续项；桥不静默修正语义，不把严格失败改成通过 |

模块职责：analysis-core 负责模型/javac/ASM/提取/SPI/静态 Lombok；framework-spring/sofa 复制框架；java-bridge 共用输入/投影/归档/生命周期；jvm-runner 和 native-bridge 仅提供各自入口。

## 3. 共用接口

| 接缝 | 必须保持 / 实施去向 |
|---|---|
| CLI → daemon | HTTP/2 + protobuf，handshake 2；新增 selector、方向、分析世界、预算及 pipeline 参数贯通 wire、版本和续页身份；旧端不支持时明确拒绝 |
| daemon → worker | protocol 15 / Fact IR 1；Begin/Chunk/End、baseline、cancel、ack；[IDX-02](chains/index-publish.md#idx-02) |
| worker → Java | request/result 8，CAF1 描述符；JVM 有独立清单与任务私有输入，日志 stderr；native ABI 2 后置 |
| Java → javac | batch 2 / javac-model-v50；目标 Java 8 真实执行，与宿主工具链分开 |
| 输入 → Java | 捕获 manifest、logical/physical path、hash、编码、MAIN/TEST、world、有序依赖、辅助/发布输入、XML、祖先 Lombok 配置和资源 |
| 原模型 → Fact IR | 身份、owner/world/source、UTF-16 → 捕获 UTF-8 字节跨度；原始绑定与 recovery 分开，保留次数/状态/诊断/coverage/provenance；[JVM-03](chains/java-jvm.md#jvm-03) |
| ProjectFactView | baseline 与新声明、类型、Bean 合成完整逻辑视图；求解继续用复制代码，编译器对象不跨上下文存活 |
| 候选写入 | 完整事实正文为权威，查询投影可重建；facts/FTS/投影/依赖在同一候选事务维护，校验后一次发布 |
| FactReader | 类型化筛选/计数、定向读取、批量邻接/依赖、稳定游标；调用者不依赖 SQL，不由 Rust 猜 Java 语义 |
| 语义身份 | 新 adapter/profile 不复用旧语义基线，旧 revision 仍按旧读路径查询；缺新能力明确披露 |
| 版本变化 | 以上是当前基线，不是禁止升级；不兼容字段或语义须协调 CLI/proto/worker/manifest/profile/schema 并测试混装拒绝，禁止静默丢字段 |

### 打批契约

| 环节 | 边界 |
|---|---|
| Java 产出 | UnitFacts 按文件回调；框架目录/模型内存也有界。CAF1 默认 256 条或 1 MiB flush，结束必 flush |
| 大记录 | 超缓冲但合法的单条直接写；单条 CAF1 上限 8 MiB，还要满足下游 protobuf 帧上限。超限明确失败 |
| 大模型 | syntax/type 可分片，节点、parent、offset 和完整性可重建；增量缩小后删除旧分片 |
| 禁止 | 全项目结果 JSON、逐事实 FFI、用丢事实降低内存 |
| Rust worker | Chunk 最多 64 条，同时按编码字节限制并背压；与 Java 缓冲阈值独立 |
| 全局去重 | 私有 SQLite，每 2048 个新身份提交；同 ID 同正文去重，不同正文拒绝，保留冲突 |
| 入库 | prepared INSERT + 候选事务 → 索引/派生/完整校验 → 耐久化和 catalog CAS；读者看不到半成品 |
| 生命周期 | 发布前失败保持 head；提交后响应/ack 失败不能回滚已发布状态。消费完毕才释放归档；取消/失败不提交缓存基线 |

## 4. 测试来源与组织

**Anatomist 的测试、fixture 和语义预期继续作为来源；允许重写 code-ana 当前所有测试逻辑，统一重建组织、入口、运行器、断言实现和报告。保留的是可追溯的覆盖目标与独立预期，不要求保留旧脚本结构。**

| 来源 | 迁入/改造方式 |
|---|---|
| Anatomist `anatomist-app/src/test/java/com/anatomist/core/`、`extract/` 中的解析/模型测试 | 随解析依赖迁入对应 Java 模块，保留原用例、helper、断言与资源；测试在 app 下不代表必须引入产品 app |
| Anatomist `anatomist-framework-spring/src/test/`、`anatomist-framework-sofa/src/test/` | 随框架模块复制，先在 JVM 执行，补原测试依赖的配置和 XML 资源 |
| Anatomist `fixtures/`、`e2e/fixtures/` | 复制能力清单对应的完整 fixture，包括源码、POM、XML、Lombok 配置、资源；相关构建依赖显式登记 |
| Anatomist `tests/scenarios/` 与 CLI/query/store 测试 | 保留输入、操作序列、语义预期，转换命令参数和输出表示后验证 Rust；原 expected 留存，不直接用 code-ana 当前输出覆盖 |
| code-ana `tests/java_model_tests.py` 与依赖它的 alignment 用例 | 已发现仍引用 `lang-adaptor`、旧 request 6 和旧 probe；统一改为公共 JVM runner/request 8/CAF1，保留可用断言，记录旧自研能力与上游能力的差异 |
| code-ana `tests/java_anatomist_reference.py`、`tests/java-migration/` | 保留冻结参考对照职责，允许重写差分/witness 工具；核对参考 JAR/hash、输入清单和模型读取，保持独立 oracle |
| code-ana `tests/java_anatomist_native.py`、`tests/java_product_runtime.py` 等 | 当前入口依赖 `.dylib`/native manifest；抽出与后端无关的用例和断言，显式选择 JVM/native，构建与运行清单分别校验 |
| code-ana Rust 测试、`tests/rust_e2e.py`、mock-worker | 测试代码和调度可重写，保留 CLI/store 契约及故障覆盖，补上游语义场景；不用于证明 Java 解析能力 |

测试迁移规则：

- [ ] 建立“能力 → 原测试/fixture → 迁入测试 → 适配说明 → JVM/native 结果”清单，记录来源 commit、原始与迁入 SHA、资源和许可证。
- [ ] 复制范围覆盖能力清单的全部相关用例，不以“只选能跑的测试”替代完整迁移；未迁入、失败、环境阻塞、产品边界外的用例逐项登记。
- [ ] 全面盘点并允许重写现有测试、helper、fixture 生成、断言代码、差分工具、进程管理、故障注入控制、报告及 just/scripts 测试入口；不降低语义断言或删除失败预期。
- [ ] 对旧自研能力专属或与冻结上游冲突的断言单列兼容性差异，保存原用例来源及预期；不为使其通过重新实现 Java 逻辑。
- [ ] 上游 JDBC 测试仅在独立参考/测试范围使用，转换出的 Rust 用例验证同等存储行为；产品 worker 不依赖 JDBC。
- [ ] 为公共 bridge 的输入封存、坐标转换、批量输出、owner、取消和生命周期补对接专项；解析测试以复制上游为主。
- [ ] JVM/native 共用场景、事实读取和语义断言，但上游 oracle 保持独立；ABI/isolate/动态库专项只归 native 阶段。
- [ ] 对照上游 app 中的架构测试、CLI/query/store/incremental 测试、测试资源生成与构建插件，登记迁入或转换去向；基准测试/JMH 场景可转为 Rust/端到端测量，不直接比较不同运行器的分数。
- [ ] 每个指定门禁登记期望 case ID 与实际发现/执行数，关键测试零执行失败；多模块的合法空模块与整个目标零执行分开判断。复制上游测试时不能照搬 `surefire.failIfNoSpecifiedTests=false` 后仅凭 Maven 退出码判绿。
- [ ] 保留上游 Surefire 对 `*Test.java` 与 `*IT.java` 的发现规则，以及测试分组、资源路径、工作目录的有效含义；按预期 case ID 集核对，避免部分 IT 未运行却因其他单测通过而误判完整。

复制源码和测试只能建立可追溯基础；完整能力清单、独立差分及最终查询验证共同证明解析能力完整迁入。

| 层 | 组织 |
|---|---|
| Java | Maven/JUnit；原测试/helper/resources 随能力迁入，公共 bridge 单独测；不依赖 native |
| Rust | Cargo + 真实私有 SQLite + CLI/mock；不要求 JDK |
| 公共工具 | Python 标准库/unittest，统一封存、进程树、超时、CAF1、差分与回执；oracle 独立 |
| 入口 | just/scripts 分开 Rust、JVM 组件/e2e、native 专项/e2e、性能；保持 just test-rust 无 JDK |
| 清单 | 稳定 case ID、覆盖、来源、层级、后端、fixture/expected、依赖、替代关系、执行结果 |
| 迁移办法 | 最小工具先行；按任务滚动重写全部现有测试，逐项覆盖后才删除旧实现。旧测试坏了可用冻结参考证明，不恢复旧自研 parser |

## 5. TDD 与证据

TDD 针对本次 Java 能力迁入、bridge 对接、CLI/存储/查询的新增行为与缺陷修复。已有且行为未变的测试作为回归复用，无需另造 RED 或重新实施。历史版本测试验证旧 revision 仍可读取；引用保护测试验证查询、分页或增量任务使用中的数据不会被 GC 删除。这些既有能力仅在本次改动影响它们时补针对性测试，不因本机磁盘节约而新增专项。

| 步骤 | 必须执行 | 回执 |
|---|---|---|
| 1 定义行为 | 选一个能力/错误窗口，确定输入、独立预期和最小运行命令；测试不得从待测输出自动生成 expected | case ID、来源、fixture、预期 |
| 2 RED | 在改实现前执行，确认失败对应缺失行为/契约；缺模块接口可登记 bootstrap RED，环境损坏记 blocked | 修改前源码/产物身份、命令、退出码、具体失败 |
| 3 GREEN | 只实现该行为所需改动；Java 为源码复制/装配/bridge 对接，Rust 为 CLI/store/query 实现 | 修改后身份、同一用例通过、原失败已消失 |
| 4 REFACTOR | 整理重复、模块边界和测试 helper；重跑直接受影响测试与该层回归 | 回归结果和未完成项 |
| 5 阶段门禁 | 组件通过后进入真实集成，阶段末运行完整能力矩阵；fixture 完成后运行冻结真实项目 | [ACC 门禁](acceptance.md)的独立结论 |

已有上游测试复制后直接通过，记录为基线覆盖，不人为破坏解析器制造 RED；新 bridge/查询/存储行为仍应先用测试复现缺口。修改已有缺陷时，新增回归必须在修改前失败、修改后通过。纯文档、来源登记不要求构造伪失败；性能优化先固定正确性用例与可重复瓶颈证据，不把机器时延波动当成 RED。

运行结果采用 `passed/failed/blocked/skipped`，另记录是否为已知上游缺陷。关键用例未执行、环境不满足、超时或已知失败均不能被聚合为 passed；known-issue 披露与已完成迁移范围结论分开。运行器测试必须证明这些汇总规则，以及一条子用例失败会使严格门禁失败。

TDD 先跑最小目标测试，GREEN/REFACTOR 后跑相关层回归，阶段末跑完整门禁。只有代码变化、新失败或未解决问题才扩大/重复验证；完整 native 门禁仍须等待 JVM 全链路完成。

### 独立对照

```text
同一封存输入
  +--> 冻结 Anatomist --> 原始模型 / 提取事实 / 查询结果
  |
  +--> 迁入 JVM / 公共 bridge --> 原始模型 / Fact IR --> Rust 库 / CLI
  |
  +--> V3 完成后：native / 同一 bridge --> Fact IR --> Rust 库 / CLI
                                 |
                         内容 / 位置 / 证据 / 次数
                                 |
                         全量 <--> 冷增量 <--> 缓存路径
```

| 证据 | 必须记录 |
|---|---|
| 输入 | commit、文件清单/hash、source roots、module/scope/world、classpath 顺序与 hash、Lombok 配置、XML |
| 产物 | code-ana 源码提交/差异摘要、参考提交、backend=jvm/native、JAR/worker/daemon SHA、协议和 schema；native 阶段另记共享库/bundle SHA |
| 环境 | OS/架构、工具链、资源预算、并行度、缓存状态、运行是否独占 |
| 结果 | 原始日志、失败阶段、数量、内容摘要、诊断、coverage、耗时、峰值内存/磁盘 |
| 对照 | 参数映射、规范化规则、可比范围、已知缺陷及原始差分 |

独立参考不能调用产品自己的投影或 normalizer 生成预期。规范化只消除无关的任务 ID、临时路径等运行差异；必须记录规则和原始值。不能删除不一致记录来换取“零差异”。内容比较保留重复次数，不能只比集合或总数。

JVM 产物清单和启动入口独立于 native 清单，不提前校验不存在的共享库。二者使用同一语义核心和事实协议，运行身份分别记录，避免把 JVM 的结果错误登记为 native 通过。

## 6. 本机磁盘执行约定

**TDD 只覆盖项目需求。本机磁盘空间通过构建和验证的执行安排节约，不增加磁盘满、空间预算竞争、跨卷计量或环境清理故障等专项测试。**

已核实的重复占用入口：`tests/java_rust_validator.py::build` 每回执创建 `rust-validator-build`；`tests/java_xml_model_reference_alignment.py` 同样新建 `rust-build`；`java_real_acceptance.py` 把完整 daemon home 放在逐次 evidence 下。just/scripts 多处写死 `target/debug`、`target/release`，复用构建目录时同步产物发现路径。

| 环节 | 执行约定 |
|---|---|
| Rust 构建 | 使用稳定、兼容的 Cargo target，不按用例/回执新建依赖树，不每轮 cargo clean；回执只封存实际运行的二进制、清单和摘要 |
| Java/发行构建 | 复用 Maven 产物，不为每个 fixture 重复 clean/package；native 仍在 JVM 全链路通过后构建 |
| 调试产物 | 本机确需进一步节约时，可显式减少调试信息或关闭增量缓存；记录构建配置，性能/发行结果保持对应配置可比 |
| 验证范围 | TDD 先跑最小用例、再跑相关层；阶段末跑完整门禁，避免无变化地重复全量索引 |
| 大项目运行 | 参考、JVM、native 和大库备份串行，避免多套大产物同时生成 |
| 索引复用 | 同一 revision 完成结构、事实和全部只读查询验证；写式 FTS/维护校验共用一份一致私有备份，用完释放 |
| 全量/增量比较 | 比较期间保留必要版本；完成后按现有引用和 GC 规则回收 |
| 多轮性能 | 三轮串行，逐轮保存指标和结果、清理临时库；不长期保存三套相同索引 |
| 回执与失败现场 | 保留输入/产物身份、完整差分和必要复现；不长期保留每次构建的依赖树与全部中间件 |
| 临时目录 | 消费者和进程退出后及时清理；已有 revision、活跃引用及尚需取证的现场不直接删除 |

运行前按实际需要查看本机余量、清理已确认无用的旧产物即可。不建设磁盘预算/预留/自动准入系统，不要求额外磁盘预检或清理报告，也不把本节变成进入 iopengw 的新增门禁。项目存储性能报告仍记录索引大小、写放大和临时峰值，用来评估存储优化。

## 7. 任务记录规则

每个任务正文维护唯一状态（待实施 / 进行中 / 阻塞 / 完成）和实现回执链接。README 只索引，不抄状态。
任务包含行为、调用链、开发前置、联调前置、复制/适配或 Rust 改动、RED/基线、完成条件、旧编号来源。
缺口、已知上游失败、产品边界外和未执行分别登记；任务编号只提供稳定链接，不代表先后顺序。
