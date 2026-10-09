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
| 状态 | 待实施 |
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
| 状态 | 待实施 |
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
| 状态 | 待实施 |
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
