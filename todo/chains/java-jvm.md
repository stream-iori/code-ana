# Java JVM：封存输入 → 复制解析 → 模型与事实

[入口](../README.md) · [共用契约](../contracts.md) · [追溯](../traceability.md)

**先让完整复制的解析核心独立运行，再逐能力接 Rust。此处的“实现解析”均指 copy、裁剪和边界对接。**

```text
封存源码 / 输入 / 资源
  → 上游上下文与 javac worker
  → JavaUnit / 各提取 pass
  → 身份 / 绑定 / 最终图语义
  → 公共 bridge / Fact IR → IDX-02
```

<a id="jvm-01"></a>

## JVM-01 复制依赖闭包和独立 JVM 包

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：复制台账及独立 JVM 已构建；依赖闭包完整核对未结束 |
| 行为 | 无需兄弟仓库产物和 .dylib，即可执行冻结上游模型用例。 |
| 调用链 | 根 POM/插件注册 → analysis-core/framework 依赖 → JVM 包内 worker → Java 8 javac。 |
| 开发前置 | 无；可与最小测试工具同步推进 |
| 联调前置 | 无；本任务独立 JVM 验证 |
| 实施 | 逐文件清单先记原 SHA 再复制；裁剪 CLI、Java 查询、JDBC/发布产品层。保留 app/store 中必要解析规则及 FrameworkPlugins 装配依赖；复制 src/worker/java、src/maven-worker/java、Plexus、XML 和包内资源生成规则。保留目标 17/25 源码及测试来源，当前门禁只认目标 8。 |
| RED / 基线 | 复制 JavacWorkerSessionTest、JavacParallelWorkerIT、MavenInputsWorkerTest；原样通过记基线。新增包资源/worker 缺失、旧 JAR、漏 Test/IT 的检查先失败；缺入口可记 bootstrap RED。 |
| 完成条件 | 独立 JVM package 找到 worker；字节码 major 52 且 Java 8 实际执行。按指定 case ID 核验 Test/IT、分组、资源路径/工作目录；不能只认 Maven 退出码或测试总数。源码/测试/许可证清单可审计。 |
| 追溯 | A1/A5/A6/A10/A11；旧 01 §1/§2/复制裁剪；P0/P1；T0/T2 |

<a id="jvm-02"></a>

## JVM-02 捕获输入、有效配置和二进制上下文

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：捕获输入、语言/模块 external 策略、Java 8 子进程已接通 |
| 行为 | 解析仅使用本轮捕获内容；目录迁移不改变模块、资源或 Lombok 配置作用域。 |
| 调用链 | Rust JavaInputs/EffectiveConfig → manifest → bridge → JavacMavenInputs 等价上下文 → JavaSource/二进制来源。 |
| 开发前置 | JVM-01 |
| 联调前置 | 无；独立 JVM 验证 |
| 实施 | 映射有效 reactor、MAIN/TEST、有序 classpath、profiles/classifiers、生成根/输出根/资源、辅助模块和发布范围；外部准备 inputs_manifest，不在索引中发现 POM 后触发目标构建。JavaSource.read、scanner、祖先配置读取指向捕获物理目录，保留逻辑路径/hash。复制 JavaBinarySources、ClasspathClassFileSource、JdkClassFileSource 和签名缓存；语言/模块配置继承覆盖 → wire → ExternalTypePolicy 等原核心 → profile/失效 → 结果闭环。 |
| RED / 基线 | MAIN/TEST 依赖顺序不同、同名多 JAR/嵌套类、缺损 JAR、歧义根、辅助/未选模块、空 Java 输入、目标/宿主 JDK 不同；切换 external.exclude_patterns 必须改变结果或明确拒绝。捕获后活动源码改变不能混入解析；不运行处理器。 |
| 完成条件 | 对照冻结参考输入选择与模型，缺依赖/编码/输入失败如实披露；有序依赖、SOURCE/binary、目标平台不被宿主污染，资源关闭；所有语义配置进入 profile，拒绝接受但忽略。框架资源专测在 FW-01/FW-02。 |
| 追溯 | A3/A4/A5/A6/A16；旧 01 Bridge；03 输入边界 |

<a id="jvm-03"></a>

## JVM-03 原模型、提取 pass 与 Fact IR 映射

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：17 fixture × 14 原模型/提取域差分为零；Fact IR 产品回归仍有失败 |
| 行为 | 完整保留上游解析结果，并产出能由真实 Rust FactValidator 接受的事实。 |
| 调用链 | JavaUnit → JavaModelExtractor/ExtractionResult → 身份与绑定 → bridge 投影 → Fact IR。 |
| 开发前置 | JVM-02 |
| 联调前置 | 无；本任务独立验证原模型与真实 Rust FactValidator |
| 实施 | 逐字段登记原字段/producer → Fact IR/facet 版本 → world/owner/source/范围/状态 → 消费者；覆盖 syntax/bindings/types/declarationInventory/extensions、nodes/edges/annotations/meta/declarations/unresolved/stats。复制 JavaReflectionFacts；反射/恢复保持候选。核对 full 的 StagedGraphStore.finalizeRawFacts、delta 的 GraphIdentityRewriter/GraphPostProcessor、DatabaseWiringResolver/CallSitePersistence：Java 语义规则分离后原样复用；Rust 只接已有存储/跨 producer 派生。先核实 derived.rs，不能假定对齐。 |
| RED / 基线 | 复制泛型/注解/继承/覆盖、lambda/方法引用、字段/初始化器、二进制及 JavaReflectionFactsIT/JavaAnnotationFactsIT/JavaModelAnnotationsIT。桥新增 UTF-16/UTF-8、中文/emoji/CRLF、无效编码、无位置不造范围、syntax/type 分片/缩小/超限、跨 world 同名、原绑定/恢复分离、重复 occurrence RED；固定事实经 Rust 真校验。 |
| 完成条件 | 原模型、提取、身份/绑定后、派生前后分层差分，身份/来源/内容/次数/覆盖不漏；facet namespace/version、类型图、绑定引用和分片校验通过。编译器对象不跨上下文存活；semanticAnnotations 无 parser producer 则登记 Rust 人工标注管理，不凭空生成。上游裁剪记录前后数量与原因，非法 Fact IR 拒绝。 |
| 追溯 | A2/A12；旧 01 完整复制/Bridge；03 解析与投影基础；V1 |

## 本链交付物

- 源码/测试/资源复制与裁剪清单、字段映射、独立 JVM JAR/运行清单及摘要。
- 上游 fixture 原始预期；新增 bridge 用例 RED/GREEN/REFACTOR 回执，原样通过用例登记基线。
- raw model / ExtractionResult / Fact IR 差分；框架见 [FW 链](frameworks.md)，完整门禁见 [ACC-02](../acceptance.md#acc-02)。单个任务通过不等于完整能力迁入。

2026-10-10：原始观察钩子仍在最终图 pass 之前；GraphProjectionBoundary 使用捕获的逐文件身份及当前上下文完整可见声明目录，调用原 GraphIdentityRewriter / GraphPostProcessor，并保留原声明键供 Fact IR join。provenance 带 GraphSemantics.VERSION=25。组件回执 `target/implementation-evidence/jvm-components-13/receipt.json` 为 223/223，无漂移；新增注解非有限值、语法 member 名和条件 Bean 的后续修复待完整回执。

最新组件 `jvm-components-14/receipt.json` 为 225/225，无漂移；源注解的非有限值/UTF-16 与 modeled 调用的原语法 lookup_name 通过。条件 Bean 歧义修复已由探针复验，XML 父链在 `xml-parent-red-01.log` 失败后修正限定边 ID 的 join key，`xml-parent-green-01.log` 通过；新整套仍在执行。

原 Annotation/AnnotationMetaRelation 的 language/provider/mechanism/sourceLocation/producer 与 SemanticAnnotation.source 已进入投影；直接构造原模型验证，不重写注解解析。相同语义边使用稳定的多重集 occurrence 身份，保留同一范围中重复泛型引用；原语义边和数组顺序不改。对应局部 RED/GREEN 见 acceptance，最终图及 full/delta 完整复验未结束。

原始列投影补充：`provider-columns-red-01.log` 证明 Node.providerId 丢失；`provider-columns-green-01.log` 通过。节点补原始 domain/language/provider/entity-kind/namespace/qualified-name/module/scope/位置/序号，声明补 type-kind/declaring-type/namespace/depth/direct-member/位置/producer；关系补原始两端、外部目标各列、semantic/mechanism/provider/位置/序号/syntax-target/relation。保持原 confidence 与推断来源；完整组件复验中。这不替代所有字段和最终图的完整审计。

### 2026-10-10：优先修复空目标与解码预算

| 项目 | 实施与验收状态 |
|---|---|
| 空目标 | `JavacBackend.analyzeFiles` 在目标为空时返回完整空 inventory，不规划/编译辅助上下文；仍保留捕获输入校验。针对性 RED 为依赖模型被错误输出，GREEN 为零 worker、零批次、无缓存。 |
| 解码预算 | 新增 `[resources.index].java_model_decode_bytes`；默认 536870912，允许 65536–2147483648。通过 protobuf、Rust worker 和 Java backend 传入，每个响应累计；语义指纹、校验和与其他模型上限不变。worker protocol 升为 16，拒绝旧 worker 混装。 |
| 参考版 | 固定 JAR 不改写；独立编译冻结 `JavacProtocol` 的资源准入覆盖层，两版同用 2 GiB；来源、覆盖层源码/类文件、预算和 JAR SHA 单独绑定回执。此回执明确是调整资源策略后的对比。 |
| `app/test` | 按用户要求暂缓 raw/AST × MAIN/TEST 四项；修复回归后补跑。原解码失败回执保留。`--exclude-module app/test` 不缩减完整门禁的 160 项预期，不会产生完整通过。 |

真实空目标 `app/biz/shared@MAIN`：迁移版 raw 从旧回执 100.601 秒降至 6.106 秒，AST 为 7.810 秒；两版 14 域一致。见 `target/implementation-evidence/javac-empty-real-02/receipt.json`。这些是正确性采集耗时，非最终性能门禁。

修复回归完成：`jvm-components-24` 241/241，`rust-workspace-11` 136 项，Clippy 与 JVM 启动校验通过；真实 CLI `java-decode-budget-cli-03` 通过（低预算拒绝并保留 HEAD、2 GiB 发布、默认预算事实等价）。非 `app/test` 的 156 项采集已启动：`iopengw-jvm-models-02`；其余 4 项明确暂缓，完整门禁仍未通过。

模型采集与最新 bridge 产物分开取证：`model-producer-equivalence-02.json` 比较 fat JAR 的每项内容（嵌套编译器/Maven JAR 递归比较，忽略 ZIP 封装时间）。解析/提取核心和两条 worker JAR 内容全部相同，仅三个 witness 不使用的 bridge class 改变；同时拒绝 witness/core 对这些 bridge 的依赖。六项独立工具测试覆盖 parser/嵌套编译器/外部编译器变更与依赖误复用。该证明不接受任何模型用例，完整 160 项和已知差异仍单列；当前新共享 session 的完整 JVM 产品门禁继续运行。
