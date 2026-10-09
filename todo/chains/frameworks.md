# 框架：Lombok / Spring / SOFA / XML → 事实 → 公开查询

[入口](../README.md) · [共用契约](../contracts.md) · [追溯](../traceability.md)

```text
封存配置/源码/XML → 原插件装配与模型 → 声明/Bean/config/注入事实
                                              ↓
                    Rust 投影/已有派生 → search/structure/annotations/references/dispatch
```

**JVM 框架组件可先行；公开查询的 Rust 部分用固定事实独立开发，再做纵向联调。**

<a id="fw-01"></a>

## FW-01 插件装配与静态 Lombok

| 项目 | 落地要求 |
|---|---|
| 状态 | 待实施 |
| 行为 | 配置开关和原插件依赖生效，生成成员与调用证据符合冻结上游。 |
| 调用链 | FrameworkPlugins → Spring→SOFA requires/ID/version → Lombok 模型与声明/调用证据注册。 |
| 开发前置 | JVM-02 |
| 联调前置 | 无；本任务独立组件验证 |
| 实施 | 复制必要 app 装配、静态 Lombok 和原模型算法；配置来自捕获的祖先链，保留 stopBubbling、模块根和 strict。输入指纹包含启停、插件身份/版本；不执行目标注解处理器。 |
| RED / 基线 | 复制相关 JUnit/fixture 和 LombokConfigurationScopeTest；新增缺前置/重复 ID、off/model、祖先配置、stopBubbling、生成成员、多项目隔离、配置变化输出变化的对接 RED。 |
| 完成条件 | JVM 原模型和生成事实与参考一致；Lombok 缺口与 strict 明确，注册顺序/指纹可审计。无效配置拒绝，不静默降级；JVM baseline 和新增对接证据分开。 |
| 追溯 | A1/A4/A9/A16；旧 03 Lombok/装配 |

<a id="fw-02"></a>

## FW-02 Spring/SOFA/XML 语义与扩展隔离

| 项目 | 落地要求 |
|---|---|
| 状态 | 待实施 |
| 行为 | 注解和 XML-only 工程产出完整框架事实，捕获目录变化不改变结果。 |
| 调用链 | ProjectResourceDiscovery/OfflineXml → 原 analyzer → ProjectFactView → ExtensionExecution → 框架事实。 |
| 开发前置 | FW-01、JVM-03 |
| 联调前置 | 无；本任务独立 JVM 组件验证 |
| 实施 | 直接复制 framework-spring/sofa 的分析/XML/资源/测试，保留根级 XML 和离线 DOCTYPE/DTD。提供原声明/类型/Bean 目录视图，后续 INC-02 合入 baseline；求解原样保留，扩展失败隔离，部分输出不能泄漏为成功事实。 |
| RED / 基线 | 复制 ProjectScannerSpringXmlTest、XmlBeanExtractorTest、SofaXmlAnalyzerTest；注解/XML-only、跨文件 ref、MAIN 共享、条件/同名 Bean、多 analyzer 消费同资源、扩展写一半失败、strict/非 strict、producer 停用、捕获后活动 XML 变化。 |
| 完成条件 | raw→提取→框架结果多重集/身份/属性/来源/歧义逐项对齐；所有读取来自封存输入，不加载外部 DTD；共享资源按契约消费，失败与覆盖如实披露。 |
| 追溯 | A1/A2/A9/A16；旧 03 框架/装配 |

<a id="fw-03"></a>

## FW-03 框架、元注解与配置图公开查询

| 项目 | 落地要求 |
|---|---|
| 状态 | 待实施 |
| 行为 | 可通过公开命令查询 Spring/SOFA、Bean/XML 结构、成员绑定、配置引用、注入和元注解路径。 |
| 调用链 | 框架/annotation facts → Rust projection/derived.rs → search/structure/annotations/references/dispatch → 证据。 |
| 开发前置 | QRY-01、QRY-03；固定事实先行 |
| 联调前置 | FW-02、IDX-02、QRY-05 |
| 实施 | 补 annotation name/raw attributes/target_kind/target_path/meta target/source/state、config node/parent/relations 投影；保留 DEFINED_BY、XML_REFERS_TO、PARENT_BEAN、BINDS_TO、INJECTS、WIRES、HANDLES 的方向/次数。参考 AnnotationQueryService 有界防环（上游深度 16）；外部观察语义复用 QRY-01。核对 DatabaseWiringResolver 等原规则与现有 Rust 跨 producer 派生，不另写 Java 解析。 |
| RED / 基线 | AnnotationSpringContractIT/原注解和 config fixture；元注解环/多路径、XML 父子/ref、handler、唯一/多注入候选、内部/外部同名。Rust 手工事实先复现公开入口缺口，再接 JVM。 |
| 完成条件 | 每类 metadata 有消费者，关系经 CLI 可查，属性/target_path/来源/次数对齐。候选/静态注册不冒充运行时确定调用，facts/projection 原子；保留真 JVM→Rust→CLI 回执，full/delta 交 INC-03。 |
| 追溯 | A2/A12/A17；S3；旧 annotations/框架；Q4/Q7 |

### FW-03 的投影约束

- config 关系可能跨 FactKind，统一读取端口不能假定它们都已是 Rust Reference。
- 注解保留名称/原名、原始属性、目标种类/路径；元注解返回路径和深度，不能以全局去重吞掉不同路径或 occurrence。
- 内部目标与观察到的外部目标分别按范围查询，保留歧义和内部覆盖规则。

完整上游 fixture 按 [公共测试清单](../contracts.md#4-测试来源与组织)对账。单纯存入 facet 或通过 Java 单测，不能代替 FW-03 的公开查询验收。
