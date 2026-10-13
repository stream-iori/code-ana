# Java / 扩展 / Maven：Anatomist 能力对齐审计

> 历史记录（2026-10-13 文档对齐）：早期 2dfc797 能力审计；表中缺口与旧源码位置按当时解释，不能直接当作现在的待办。现行版本、验证范围及未完成门禁见 [当前实现](../current-state.md)，最新结果见 [存储实施记录](anatomist-storage-migration.md)。

**结论：Java 8 产品链路已打通，但还不能称为 Anatomist 能力对齐。主要缺口是完整模型与绑定证据、扩展机制及规则、Maven 依赖调解，以及签名和规模化复用。**

| 审计项 | 基线与口径 |
|---|---|
| 参考 | Anatomist `dev/replace-parser@2dfc7977cb03095d14894f069ff30985551457b1`，工作区干净 |
| 本项目 | code-ana 当前工作区，包括未提交实现；2026-09-30 |
| 证据 | 生产代码、测试源码、仓库内历史验收记录；该审计表是实施前基线；本轮执行证据与进度见 java-validation.md |
| 对齐目标 | Java 8 解析、静态扩展、Maven 扫描的能力与证据等级；不要求数据库、ID、CLI、协议兼容 |
| 保留架构 | Rust daemon → 独立 Rust worker → GraalVM 25 C ABI → 目标 Java 8 javac；禁止 JNI |
| 保留输入边界 | daemon 消费封存源码、JAR、资源及外部 inputs_manifest；不执行目标构建或处理器 |
| 暂不纳入 | Gradle、Linux、目标 JDK 17/25；fixture 优先；随后验收冻结 iopengw HEAD，仅已提交内容 |
| 状态口径 | 部分＝已有子集；缺失＝未发现对应产品实现；待验＝现有证据不能证明。不用总百分比混合这三类情况 |

**更正上一版：Anatomist 的 Maven 输入发现不是自动构建。** `MavenInputsWorker.afterProjectsRead` 导出后抛出专用完成异常，明确在 validate/plugins/lifecycle 前结束。命令行出现 `validate` 不表示执行了该阶段。它仍启动 Maven、加载扩展并可能解析/下载依赖；本项目可将该方案用于**外部显式导出器**，保留 daemon 的 manifest 边界。执行 Lombok processor 的 compile 模式是另一条路径。

```text
外部 Maven 输入发现 → 冻结 manifest / 源码 / JAR / XML / 配置
                                  │
                        物理模块 × MAIN / TEST
                                  │
                       javac → 独立 Java 模型
                                  │
                         声明补充层（Lombok）
                                  │
                核心事实 + Java 扩展 + 调用证据扩展
                                  │
                     完整事实视图 + 共享资源清单
                                  │
                        Spring / SOFA 资源分析
                                  │
                    owner 替换 → 校验 → 原子发布
```

签名缓存、声明目录、编译器会话复用服务于该链路，不能改变事实结论。表中的 S/T/C 编号指向末尾证据索引。

## 0. 当前剩余实现复核（2026-09-30）

本节按当前工作区与上述 Anatomist commit 重新查源码；下文第 1–5 节继续保留实施前基线。矩阵目前为 **2 passed / 46 partial / 4 pending**，未通过的 50 项不是 50 项空白实现。本节已纳入后续实现进度；能力完成状态仍受逐项门禁约束。固定输入回归回执见 java-validation.md。

| 已有实现 | 本轮判断 |
|---|---|
| Maven | 已有生命周期前终止、一次 Maven 会话两阶段选择/Resolver 导出、scope/CP 顺序、生成根、外部 parent 控制输入、source-only/offline/timeout；不再按旧表描述为多次 help/dependency 调用 |
| Java 基础事实 | 已有结构化类型、字段读写、语法父链/控制上下文、注解常量/默认值、基本 lambda/SAM、外部成员；需要扩展语义和反例验收，不是全部重写 |
| 注解与框架 | 已有共享元注解/AliasFor、Bean 名/别名、MVC、生命周期/事件、SOFA 注解；完整 DI/XML 验收仍缺；已有 Spring/SOFA XML 配置树、位置与关系 fixture |
| 复用 | 已有复杂文件级 NoCode 投影、安全方法体缩减、目标 javac JVM 池 1/2/4/8；文件 LRU/私有 pin 已实现，模块归档/目录与统一管理仍缺 |
| daemon | 无沙箱重试后生产模式启动、CLI status 与 Hello 成功；证据 target/java-alignment/evidence/daemon-retry-20260930-112413/receipt.json。不是完整 Java daemon 验收 |

| 工作包 | 关联编号 | 需要新增或补齐的实现 | 源码依据 |
|---|---|---|---|
| 独立模型与二进制查询 | J01–03、J07、J10–11、J14–15 | 完整语法字段/子节点角色、声明与类型目录、类型身份与替换/捕获、SOURCE/二进制统一查找；补复杂声明身份及注解完整性。BinaryIndex 的 raw generic signature 需解析为可查询模型，成员截断须披露 partial | CompilerBridge.Scanner.scan 目前主要存 kind/parent/span/name/value；对照 JavaNode.fields 与 JavaModel。BinaryIndex 本轮已补 binary 泛型树，2048 成员后披露 members_complete=false 与遗漏数量；统一目录/查询仍缺 |
| 调用恢复、回调与派发 | J04–06、J12、E06 | SOURCE/建模成员的有界候选查找、访问性、重载/可变参数、泛型继承/替换、缺依赖与歧义分类；补构造器/数组方法引用、SAM 与建模覆盖。来源与选中声明/完整性分别保留，恢复不得升为 javac confirmed | 已加入 TypeOperations/CallRecovery：SOURCE/overlay/binary 泛型继承、三态匹配、重载/varargs、链式输入、显式泛型实参及 SAM 方法引用；不确定候选/访问/缺依赖保留 unresolved。多态 lambda、返回目标约束与复杂 capture/混合继承仍缺；对照 JavaModeledCallRecovery、JavaCallEvidence、JavaDispatchService |
| 有界反射 | J13 | 常量字符串/Class/方法或构造器句柄传播，Class.forName/getMethod/getDeclaredMethod/getConstructor/getDeclaredConstructor 与 invoke/newInstance；重赋值、分支冲突、循环写入使证据失效 | 本轮新增 ReflectionModel：常量/Class/句柄、精确 SOURCE/binary 方法及构造器查找、分支/循环/别名/逃逸失效、预算披露；10 项 JVM fixture 与 native/daemon/Rust 查询通过，完整分支/身份及产品验收仍待补。字段反射不作为本轮对齐要求 |
| 扩展项目视图与隔离 | E03–05 | 在全部 context 的 Java 事实暂存完成后执行项目资源阶段，保留 MAIN/TEST 与 world 可见性；提供查询/资源独立预算；Spring 等按单声明隔离失败。生成字段/类型/注解与有效修饰信息须保留独立建模来源，不能改写 javac 证据 | 本轮已将资源阶段移到全部 Java/call context 暂存之后；模型逐 context 从归档读取，事实只读视图按行冻结，按封存 world 过滤并加入上游扩展失效。单声明 SPI 事务和按 subject 的失败诊断已加入；selector、独立预算和内置分析器逐声明迁移仍缺 |
| Lombok 静态能力 | L01–06 | 访问级别/命名/冲突、构造器、toString/equals/hashCode、Builder/SuperBuilder/UtilityClass、logger/注解复制；封存 lombok.config 分层/stopBubbling/逐源有效配置；逐能力 detected/modeled/partial/unmodeled 与 strict。声明、调用恢复、覆盖和失效一起补齐 | 已补访问级别/静态/命名冲突、构造器与常用方法、logger、生成注解、封存配置级联和逐能力披露；已加入参考有界顶层/static UtilityClass。Builder/SuperBuilder、完整注解复制与下游绑定仍缺。参考 LombokJavaModelExtension、LombokConfigurationScope、LombokCapabilitySummary |
| Spring/SOFA 静态规则 | F01–07 | Primary/Qualifier 等 Bean 元信息；方法/构造器/参数/单构造器推断，javax/jakarta 注入；XML alias/parent/abstract/factory/init/destroy、嵌套 bean、constructor-arg/map/list/ref/idref/value/null 及位置/成员关系；SOFA publisher/consumer/binding/transport/channel/listener。现有 MVC/handlers/SOFA 注解补跨模块反例 | 本轮新增 SpringInjection：字段/方法参数/构造器/单构造器/工厂参数及 javax/jakarta，类型和 qualifier/Resource/集合形状静态披露；补 Primary/Qualifier Bean 元信息。XML 已改为有界 SAX/精确 UTF-8 span，新增 Spring 值树/alias/parent/abstract/factory/lifecycle/嵌套及 SOFA/MsgBroker 配置；XML 类型感知实参匹配及完整生产/差分仍待完成；已补封存 JAR 成员查询和保守预算。参考 SpringJavaModelComponents、SpringBeanParser、SofaXmlAnalyzer |
| Maven/扫描收尾 | M01–12 | 编译物理模块与发布标签解耦，补共享/重叠根的一致归属；资源 inventory/selector 支持扩展需要的封存文件，不只 XML；扩展外部 classifier、scope 传播、parent/profile/settings、漂移、超时/取消与预算反例 | 单会话 exporter 和 schema 3 已实现；物理 config id/root 与 manifest 精确匹配，source_roots.label 独立保存发布身份并共享物理 world；TEST-only 前置发布角色仍缺。resource roots 本轮已捕获完整 inventory、保留 MAIN/TEST 归属，内置 provider 再筛选 XML；最长源码根和歧义拒绝已有，不重复实现 |
| 模块复用与容量 | P01–06、P04 涉及所有新增 producer | 上游模块 NoCode 归档 + 声明目录，MAIN/TEST 计划、LRU/在途 pin/驱逐与安全回退；新增生成模型/配置/资源/失败查找纳入增量闭包；compiler model → native → Rust host → daemon → 候选库按有界段/暂存读取，含 baseline 和校验，避免整份结果多次复制 | 本轮已改为 model 5 分段行归档/共享类型表、Java 事实归档、native 文件编码、host/daemon 基线与结果暂存、SQLite 候选按行校验；不再使用 storage.all_facts。默认 64 MiB/100000 未提高；context 模型内存视图、模块归档及 2 GiB 容量仍待完成 |

| 验收工作 | 完成口径 |
|---|---|
| 逐能力 fixture | 每个编号关联可执行正反例及真实 native/产品证据；矩阵状态不是由脚本中的 pending 名单自动证明。注解 J10 等即使代码已有，也不能直接改 passed |
| 生产链路 | 重新运行 Rust daemon、Java daemon、native crash、取消/旧版本保留/发布与 release 包回归；本次启动成功只移除了启动阻碍 |
| 规模与差分 | 100/1000/3000 文件及宽/深 reactor、JAR/XML 梯度；记录完整进程树 RSS、全量/无变化/方法体/声明编辑、串并行等价、取消和缓存降级 |
| iopengw | 冻结 commit 267c7ced4993aa88613f8f17a8099943fec98159 的完整语义发布/查询与 Anatomist 静态 model 模式差分；两侧固定相同源码、CP、scope 和配置。按可对应语义归一化比较声明/出现多重集/关系/绑定来源/框架证据，差异逐条解释；再验证 full/冷 delta/热 delta 等价 |

真实项目现有回执仅证明输入捕获：40 模块、4214 MAIN/TEST Java、933 JAR；`semantic_index_accepted=false`。尚无本轮全项目语义成功或差分通过证据。规模改造优先于这项最终验收，避免把提高 64 MiB/100000 上限当作流式容量实现。

建议推进顺序：先重跑已解除阻碍的产品回归并完善逐项 gate；随后模型/绑定与项目视图，Lombok/DI/XML 和 Maven 映射；再模块缓存与结果流式化；最终完成规模及冻结真实项目差分。结果流式化的设计应提前进行，因为它横跨 compiler/native/Rust/storage，而不只是 Java-worker 内部优化。

范围仍限 Java 8、macOS 和 Maven。Lombok 不运行目标 processor；参考自身标为 unmodeled 的 Accessors/With/CustomLog/Delegate 等只对齐披露。动态 DI/AOP、动态反射和 SOFA 运行时连通性不由静态证据证明。

后续源码核实：SpringBeanParser 将 parent/abstract/factory/init/destroy 保存为原始配置，XmlBeanGraphExtractor 输出 PARENT_BEAN 关系及工厂产品静态查询，不合并 Spring 运行时继承/default 配置。因此第 0 节中的“默认/继承配置”不再作为对齐缺口；仍需 binary 成员查询和完整差分。新增嵌套 callable 身份、JAR LRU、变更源 owner 替换与容量证据见 java-validation.md；这些进度不自动改变逐能力完成状态。

类型推断范围也按参考收敛：JavaModeledCallRecovery 的 conditional expression 分支明确把 numeric promotion、array capture 和 unrelated branch LUB 留给 javac；“完整 LUB”不属于其有限恢复能力。仍需对齐的是显式泛型参数、返回目标约束、poly lambda/方法引用、复杂捕获和混合继承的有界恢复，以及不能证明时的拒绝与原因。

## 1. Java 解析与语义事实

| 编号 | 能力 | Anatomist | code-ana / 缺口 | 参考 |
|---|---|---|---|---|
| J01 | 独立模型 | 类型、声明、绑定、语法、源码分层，多 pass 提取 | **部分**：已有声明/观察/类型树；缺统一可查询语法树、父子关系及声明目录，不能只增加字符串 facet | S1、C1 |
| J02 | Java 8 类型与泛型 | 泛型边界、替换、实例化方法类型、嵌套二进制身份进入模型及规则 | **部分/待验**：已有 declared/expression/selected 类型、数组/通配符/交叉界等树；缺完整类型运算、捕获及复杂替换验收。不是完全不会解析泛型 | S1、S2、C1 |
| J03 | 声明与身份 | 源码名/二进制名、局部/匿名/嵌套/枚举、合成及外部声明分开 | **部分**：已有 owner 和基本局部/lambda 归属；复杂构造器、枚举匿名体、源码与二进制对应需补验 | S1、S2、C1 |
| J04 | 调用绑定证据 | 分开选中声明、证据完整性、来源；按错误参数/歧义/不可访问/不适用处理 | **部分**：已有绑定 facet 和 resolved/possible/unresolved；缺同等细度的恢复规则、缺口分类和反例矩阵 | S2、T1、C2 |
| J05 | 安全恢复 | 唯一且类型兼容的 SOURCE/建模候选，查询预算独立，继承不完整时保守 | **部分**：只有简单 Lombok getter 恢复；一般 SOURCE 缺参数、复杂重载/继承恢复缺失 | S2、T1、C3 |
| J06 | lambda / 方法引用 | 回调实体、SAM、构造器/数组引用、恢复依据和类型引用 | **部分**：有基本 SAM/lambda/方法引用；缺完整引用形态与错误上下文的等价验证 | S2、C1、T6 |
| J07 | 类型引用 | 声明、泛型、继承、注解、表达式/回调等位置生成独立引用及外部策略 | **部分**：通用 Identifier/MemberSelect 引用不能证明所有语义位置覆盖，需按位置验收 | S2、T1、C2 |
| J08 | 字段读写 | 区分 REFERENCES / READS / WRITES，保留每次出现位置 | **缺失**：当前通用 reference 未可靠区分读写；赋值、复合赋值、自增、静态导入均需补齐 | S2、C1 |
| J09 | 词法控制上下文 | if/else、循环、try/catch/finally、switch 等调用/引用上下文 | **缺失**：没有等价语法父链/控制上下文；这不是路径可行性分析 | S2、C1 |
| J10 | 注解数据 | 原写法、显式值/常量、默认值、类型注解、SOURCE/二进制信息分开 | **部分**：直接注解主要是 source arguments，二进制有部分注解/常量；缺统一值模型与默认值语义 | S1、S2、S5、C1、C2 |
| J11 | 元注解与属性合成 | 有界元注解闭包、AliasFor、有效属性、路径证据与缓存 | **缺失**：直接名称匹配不能识别组合注解，是 Spring/SOFA 的共同前置 | S5、C3 |
| J12 | 覆盖与派发 | 泛型/访问规则、源码和外部父类、建模覆盖；声明/callable/运行时类型证明分开 | **部分**：javac overrides + 查询沿已发布边展开；缺丰富证明、二进制/建模覆盖与复杂类型验收 | S2、S4、C4 |
| J13 | 有界反射 | 常量可证的 Class.forName、方法/构造器查找及使用；冲突/重赋值/分支合并保守失效；当前参考 JavaReflectionFacts 未实现字段反射规则 | **缺失**：无反射事实与常量流模型，不能按同名猜目标 | S2、T1 |
| J14 | 二进制语义 | ASM 读写、类型/声明目录、嵌套身份及源/二进制联合查找 | **部分**：已用 ASM 补 class/member metadata；原始 generic signature 不等于完整可运算类型模型 | S3、C2 |
| J15 | 诊断和能力边界 | 模型/绑定/扩展分阶段诊断与预算，有超过默认诊断数等反例 | **部分/待验**：已有诊断/coverage/provenance、失败查找及故障门禁；细粒度语义预算和零事实能力披露待补 | T1、S5、C2 |

泛型/注解/嵌套类型的**基本解析**，与能否**完整投影成 NoCode 签名供下次 javac 使用**是两件事。本项目目前主要限制后者；高级类型关系与证据另有缺口。

## 2. 扩展机制

| 编号 | 能力 | Anatomist | code-ana / 对齐动作 | 参考 |
|---|---|---|---|---|
| E01 | 扩展点分工 | JavaModelExtension、JavaModelAnalyzer、JavaCallSiteEvidenceProvider、ProjectResourceProvider、ProjectResourceAnalyzer | **缺失**：FrameworkAnalysis 硬编码框架。拆出五类接口，分离声明补充与事实提取 | S5、C3 |
| E02 | 装配与依赖 | 构建期插件 id/version/requires、registry、producer/config 指纹 | **部分**：有固定 producer/配置开关；缺统一注册、依赖顺序、扩展版本失效契约 | S5、C3 |
| E03 | 声明补充约束 | 确定性 overlay，校验归属/编译器证据，生成声明有来源 | **部分**：Lombok 有 origin，缺通用 overlay 校验与事务边界 | S5、S6、C3 |
| E04 | 失败隔离 | callback 暂存，成功才提交；阶段诊断/计时；Spring 可隔离到单声明 | **缺失通用机制**：保留其他成功事实，失败 producer 披露 coverage，并清理旧 owner | S5、S7、T3 |
| E05 | 项目与资源视图 | 完整事实视图、统一资源 inventory、selector、资源扩展 | **部分**：共享基本事实/XML 逻辑，但缺通用接口、资源发现扩展和独立预算 | S5、C3 |
| E06 | 扩展反馈到绑定 | Lombok 声明、调用证据、覆盖参与恢复，保留 hypothesis 来源 | **部分**：只有简单 getter；不能只生成成员名而不补调用、覆盖和失效依赖 | S2、S6、C3 |

对齐的是**构建期可插拔**分析器，Anatomist 也不是任意用户 JAR 的热加载插件市场。

## 3. Lombok / Spring / SOFA 规则

| 编号 | 能力 | Anatomist 静态范围 | code-ana / 缺口 | 参考 |
|---|---|---|---|---|
| L01 | Getter/Setter/Data/Value | 访问级别、命名/冲突处理，组合能力逐项披露 | **部分**：无参数访问器及 Data/Value getter/setter 子集；参数化策略、静态成员等不足 | S6、C3、T6 |
| L02 | 构造器与常用方法 | NoArgs/RequiredArgs/AllArgs、Data/Value 构造器、ToString、EqualsAndHashCode | **缺失**：补声明与来源，不虚构方法体 | S6 |
| L03 | Builder/SuperBuilder/UtilityClass | 有界声明建模及 partial 处理 | **缺失**：生成类型、泛型、冲突和下游调用需一起处理 | S6、T2 |
| L04 | 日志字段/注解复制 | 多种 logger、生成注解、类型/成员注解复制 | **缺失**：依赖声明和注解值模型 | S6、T2 |
| L05 | lombok.config | 祖先/源码目录分层、stopBubbling、快照/漂移校验；有限配置键，未知项降级 | **缺失**：当前只有模式配置。需封存外部配置、每源码有效配置与指纹，worker 不读未封存文件 | S6、T2、C3 |
| L06 | 逐能力披露 | detected/modeled/partial/unmodeled、生成数量、hypothesis 策略 | **部分**：已有粗粒度 gap；需按能力/声明披露并接 strict 门禁 | S6、C3 |
| F01 | Spring 组件/Bean | stereotype/RestController、组合注解、名称/属性、Primary/Qualifier 等元信息 | **部分**：直接常见 stereotype、Bean 和简单字符串名；缺组合注解、有效属性/别名与证据 | S7、C3 |
| F02 | Spring 注入 | 字段、方法参数、构造器、单构造器推断、javax/jakarta、qualifier、工厂依赖 | **部分**：基础字段候选；方法/构造器/参数及完整限定信息缺失 | S7、T3、C3 |
| F03 | Spring MVC | Request/Get/Post/Put/Delete/PatchMapping，类/方法映射及 HTTP 元信息 | **缺失** | S7 |
| F04 | Spring 生命周期/事件 | PostConstruct/PreDestroy、EventListener/TransactionalEventListener、Bean init/destroy | **缺失**；静态注册不证明运行时执行 | S7 |
| F05 | Spring XML | alias、abstract/parent、factory-bean/method、init/destroy、嵌套 bean、property/constructor-arg、map/list/ref/idref/value/null 与位置/图关系 | **部分**：基础 bean/ref/property；缺完整配置树、来源、别名/工厂和成员关系 | S7、T3、C3 |
| F06 | SOFA 注解 | SofaService/SofaReference、interfaceType/uniqueId，核对 API 成员形状，不符保留 unverified | **缺失**：目前只有 XML | S8、T3、C3 |
| F07 | SOFA XML | service/reference、binding/transport/channel/listener 等配置证据 | **部分**：粗粒度服务/引用 facet，详细关系和来源待补 | S8、C3 |

**检测到注解不代表完整支持。** Anatomist 的 LombokCapabilitySummary 将 Accessors、With、CustomLog、Cleanup、SneakyThrows、Synchronized、Locked、Delegate、ExtensionMethod 列为 unmodeled，Value modifiers 也可能 partial。Spring 动态条件、AOP/容器最终选择、SOFA 运行时连通性不能由这些静态事实证明。本轮不要求补齐参考本身未实现的能力。

## 4. Maven 项目扫描与编译输入

| 编号 | 能力 | Anatomist | code-ana / 缺口与影响 | 参考 |
|---|---|---|---|---|
| M01 | 生命周期前导出 | core extension 在 afterProjectsRead 导出并终止；reactor 用索引占位 JAR，不先 install/package | **部分**：外部 Python 运行 help/dependency goals；无同会话/生命周期前停止方案，未安装 reactor 的依赖解析需专门验收 | S9、T4、C5 |
| M02 | 有效 reactor/物理目录 | MavenProject.getBasedir()/有效 reactor，不把任意后代 POM 当模块 | **部分**：模块来自 effective-pom，但目录由 build.directory.parent 推断，自定义输出目录可能拒绝；controls 递归收集所有 POM，会受无关嵌套项目影响 | S9、C5 |
| M03 | 调解后的 reactor 图 | 每模块每 scope 经 Resolver 版本选择、传递性、optional、exclusions、scope 过滤 | **关键缺口**：外部 JAR CP 已由 Maven 解析，但源码 reactor 边由直接 POM dependency 拼接，Rust 再递归展开；该路径未编码 optional/exclusions，不能证明与 Maven 调解相同 | S9、T4、C5、C6 |
| M04 | 依赖身份/顺序 | reactor/external、coordinate/classifier/extension/path，有序 CP | **部分**：CP 保序/hash，但 Artifact 只有 path/hash，源码边按 GAV；按 target/.m2 路径识别 reactor，缺准确 classifier/type 身份 | S9、C5 |
| M05 | MAIN/TEST 选择 | 先选模块/测试范围，只解析所需 CP；分别编译 | **部分**：分析有 scope 隔离，但导出所有模块两份 CP，即使只要 MAIN 也解析 TEST，增加失败面和开销 | S9、T4、C5 |
| M06 | 源码/生成/输出根 | effective source roots、generated-sources/generated-test-sources、output roots 分开；扫描允许分类生成根在 target 下 | **部分**：manifest 可手填多根和 generated_from；导出器仅主/测试单根，无生成/输出根。需完整保留 MAIN/TEST 归属和 generated 来源两维 | S9、S10、C5、C6 |
| M07 | 资源发现 | main/test resources 与资源扩展共享扫描策略 | **部分**：已导出目录、捕获 XML，缺 provider/selector；参考也不等于执行 Maven resource filtering | S5、S9、S10、C6 |
| M08 | 归属/发布标签 | 最长 effective source-root 优先，再按物理目录；歧义拒绝；逻辑标签不改变编译归属 | **缺失等价映射**：manifest id/root 与配置强绑定，同物理模块多个独立发布标签未对齐 | S9、T4、C6 |
| M09 | parent/profile/settings | 有效模型、parent 输入、profile 生效与模型 hash，遵循 Maven 规则 | **部分**：已有 selected/active profiles、effective-model、settings/.mvn/executable hash；缺完整外部 parent POM 输入清单与单会话证明，不能把现有 profile 支持说成缺失 | S9、T4、C5 |
| M10 | explicit CP/source-only/offline | resolved CP 与只采集 reactor 图分开；offline；只保留声明且调解保留的 reactor 路径 | **部分**：有 explicit/none 和 metadata；缺等价两阶段导出/source-only/offline 接口和调解验收 | S9、T4、C5 |
| M11 | 同会话/完整回执 | 一次 Maven 先 metadata、后选择/解析；nonce、回执 hash、漂移校验 | **部分**：当前约 2+2×模块数 次启动；有前后 controls 校验，但不等价于同一 Resolver 会话 | S9、T4、C5 |
| M12 | 失败/取消/预算 | 超时、有界输出、模块/依赖/响应预算；拒绝错误回执、未解析依赖 | **部分**：manifest 有 1 MiB/256 模块等限制，Python subprocess.run 无内建 timeout/统一取消和导出预算；测试外层 timeout 不是产品实现 | S9、T4、C5 |

参考本身的两个限制也必须保留：

- **reactor classifier/非 JAR 类型不完整支持**：MavenInputsWorker 明确拒绝；对齐应明确拒绝，不能误映射普通 MAIN 源码。外部制品则保留 classifier/extension 身份。
- **不执行生成器**：扫描已有标准生成目录，不等于发现所有执行 build-helper/生成插件后才出现的根。本项目 generated_from 来源校验应保留，缺失输入继续要求外部准备。

## 5. 签名、增量、会话与容量

| 编号 | 能力 | Anatomist | code-ana / 差距 | 参考 |
|---|---|---|---|---|
| P01 | 复杂 NoCode 签名 | 可投影泛型/边界、类型/声明注解、嵌套、隐藏构造器、常量/枚举；不完整回退 | **部分**：每源码简单声明，泛型/注解/嵌套等主动回退；参考已有对应代码与测试 | S3、T2、C7 |
| P02 | 模块签名/声明目录 | 上游模块签名 + 独立目录/SOURCE 信息，按物理模块/scope 计划 | **缺完整方案**：主要减少同 context 的安全单文件方法体编辑，不能替代上游模块源码广泛复用 | S3、S9、C7 |
| P03 | 缓存管理 | 指纹、来源证明、会话再校验、预算、在途 pin/回退 | **部分**：已有 hash/manifest/NoCode 校验和回退；8 MiB 单项、256 MiB 总额，满额停写；无等价模块归档预算/驱逐策略 | S3、C7 |
| P04 | 增量闭包 | 声明、绑定、模块/scope、框架与失效共同决定计划 | **部分**：已有 owner/失败查找/full-delta；新增模型/扩展/Maven 必须进入闭包、指纹和清理语义 | S2、S3、C2、C6 |
| P05 | javac 会话/调度 | 一个目标 JVM 会话处理多个 context，有界调度，独立释放编译上下文 | **部分**：热池仅复用 Rust host/native isolate；CompilerSession 每 context 新开 JVM。热 worker ≠ 热 javac；目前只在预算允许时双 context 并行 | S11、C7 |
| P06 | 容量/性能证据 | 仓库有约 2,858–2,859 Java 文件、数十 context、XML/Bean 冻结项目记录，也有 degraded/未解析和性能回退 | **待验**：本项目仅 20 轮×64 辅助源码等压力；不能推导大项目容量、速度对齐或照搬后更快 | T5、C8 |

Anatomist 历史结果不是本次复测，也不是全解析成功。两份记录使用的模式/输入和统计不同，均保留未解析/诊断；可借鉴规模和方法，不能替代本项目 native 共享库链路的进程树 RSS、解码预算、时延和取消验收。

## 6. 实施顺序与完成门槛

| 阶段 | 范围/借鉴入口 | 完成门槛 |
|---|---|---|
| A0 | 按 J/E/L/F/M/P 编号冻结 fixture、模型/扩展/manifest 契约 | 每项有正反例、来源、限制；旧 J0–J9 完成状态不能替代本表 |
| M1 | Maven 外部发现/物理范围；MavenInputsWorker/JavacMavenInputs | 未 install 多模块、custom build/source roots、无关 POM、MAIN-only、parent/profile、物理模块/标签；生命周期标记未执行 |
| M2 | 同会话 Resolver、依赖身份、scope/CP、回执/预算 | 版本冲突、optional/exclusions、provided/runtime/test 传播、外部 classifier、reactor classifier 拒绝、未解析、POM 漂移、超时/取消；对应 T4 |
| A1 | 模型/基础事实；J01–03、J07–11、J14–15 | Java 8 逐事实验证类型/读写/注解/嵌套/控制上下文；缺依赖、Unicode、重复位置；真实 native 链路通过 |
| A2 | 绑定恢复、回调、覆盖/派发、反射；J04–06、J12–13 | confirmed/possible/unresolved 分离；候选可解释、查询预算独立；动态反射保留未知 |
| E1 | 五类扩展点、registry、annotations、execution/report；E01–06 | 假扩展验证顺序/指纹、归属、失败不泄露半份事实、关闭清理；Native Image 构建期装配通过 |
| E2 | 先 L01/02/05/06、F01/02/05/06，再 Builder/日志/MVC/handlers/SOFA XML | 各能力正反例；SOURCE/class 注解一致；origin/partial/strict；跨模块、资源删除、关闭后的 full/delta 相等 |
| A3 | 复杂签名、模块目录/归档、闭包；P01–04 | Java 8 javac 分别消费源码、真实 class、NoCode class，对比完整模型/事实；缓存关闭/损坏、CP 重排、配置变更、并发 pin 安全回退 |
| A4 | 目标 javac 会话复用/规模；P05–06 | 先 100/1,000/3,000 文件生成 fixture、宽/深 reactor、XML/JAR 梯度；全量/无变化/方法体/声明编辑的耗时、进程树峰值、取消/回收/旧版本保留 |

```text
A0 ──┬── M1 → M2 ──────────────────┐
     └── A1 ──┬── A2 ──────────────┤
              └── E1 → E2 ─────────┤
                                   └── A3 → A4
```

Maven 输入正确性与模型可分支推进；扩展底座先于继续堆规则，复杂签名晚于完整声明/注解/生成来源。真实项目已授权按冻结 HEAD `267c7ced4993aa88613f8f17a8099943fec98159` 验收，仅已提交内容。输入捕获的结果不能替代语义索引与参考差分。

当前实施状态与执行证据见 [验证记录](java-validation.md) 和 Java worker TODO（历史路径 `../../todo/08-java-worker.md`，已不存在）。上表缺口是本轮实施前基线，不是最终完成清单。

复用优先级：**借鉴实现与边界测试 > 机械复制类文件**。extract/框架输出依赖 Anatomist Node/Edge/ExtractionResult，需映射 owner/world/Fact IR；Maven worker 抽出模型导出职责；目标 JVM 会话适配 Rust supervisor/C ABI。实际借用源码记录 commit/hash/许可证/传递依赖/修改，不引入其数据库/应用层或第二种 Java parser。

Lombok compile 是明确架构差异：参考会运行锁定 processor 并生成缓存 class；本项目仅对齐静态 ast/model 及 partial 披露。要求覆盖 compile 时须先修改“不运行目标处理器”约定，不能暗中混入本轮实现。

## 7. 证据索引

相对路径中的 Anatomist 为本机相邻 checkout，后续实施继续固定上述 commit。

| 编号 | Anatomist 生产源码 |
|---|---|
| S1 | [独立模型](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/java)：JavaType/JavaTypes/JavaBinding/JavaDeclaration/JavaSyntax/JavaModel |
| S2 | [提取器](../../../anatomist/anatomist-core/src/main/java/com/anatomist/extract)：JavaCallEvidence/JavaModeledCallRecovery/JavaFieldFacts/JavaSiteFacts/JavaReflectionFacts 等 |
| S3 | [JavaSignatureTypes](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/asmsolver/JavaSignatureTypes.java)、[JavaBinaryRepository](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/asmsolver/JavaBinaryRepository.java)、[JavacSignatureCache](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/javac/JavacSignatureCache.java)、[JavacSignaturePlan](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/javac/JavacSignaturePlan.java) |
| S4 | [JavaDispatchService](../../../anatomist/anatomist-core/src/main/java/com/anatomist/query/JavaDispatchService.java) |
| S5 | [扩展接口与执行](../../../anatomist/anatomist-core/src/main/java/com/anatomist/framework)：JavaModelExtensions/JavaModelAnnotations/ExtensionExecution/ProjectAnalysisRunner |
| S6 | [Lombok](../../../anatomist/anatomist-core/src/main/java/com/anatomist/framework/lombok)：LombokJavaModelExtension/LombokConfigurationScope/LombokCapabilitySummary/LombokJavaCallEvidence |
| S7 | [Spring 分析器](../../../anatomist/anatomist-framework-spring/src/main/java/com/anatomist/framework/spring)、[SpringBeanParser](../../../anatomist/anatomist-framework-spring/src/main/java/com/anatomist/core/SpringBeanParser.java) |
| S8 | [SOFA](../../../anatomist/anatomist-framework-sofa/src/main/java/com/anatomist/framework/sofa) |
| S9 | [JavacMavenInputs](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/javac/JavacMavenInputs.java)、[MavenInputsWorker](../../../anatomist/anatomist-core/src/maven-worker/java/com/anatomist/maven/MavenInputsWorker.java)：afterProjectsRead/module/dependency/Reactor/resolveSelected/owningModule |
| S10 | [ProjectScanner](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/ProjectScanner.java) |
| S11 | [JavacBackend](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/javac/JavacBackend.java)、[JavacWorkerSession](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/javac/JavacWorkerSession.java)、[JavacAnalysisPlanner](../../../anatomist/anatomist-core/src/main/java/com/anatomist/core/javac/JavacAnalysisPlanner.java) |

| 编号 | 测试源码/历史记录（不是本次执行结果） |
|---|---|
| T1 | [javac 集成测试](../../../anatomist/anatomist-app/src/test/java/com/anatomist/core/javac)：JavaBindingEvidenceIT、JavaRelationFactsIT、JavaReflectionFactsIT；不可访问/歧义/缺参数/独立预算、反射冲突失效 |
| T2 | [JavaSignatureArchiveIT](../../../anatomist/anatomist-app/src/test/java/com/anatomist/core/javac/JavaSignatureArchiveIT.java)；同目录 JavaModelExtensionIT、LombokJavaModelIT、LombokTypeAnnotationIT、LombokUtilityClassIT、JavaModeledOverridesIT；[LombokConfigurationScopeTest](../../../anatomist/anatomist-app/src/test/java/com/anatomist/framework/lombok/LombokConfigurationScopeTest.java) |
| T3 | [SpringJavaModelTest](../../../anatomist/anatomist-framework-spring/src/test/java/com/anatomist/framework/spring/SpringJavaModelTest.java)、[SpringBeanParserTest](../../../anatomist/anatomist-framework-spring/src/test/java/com/anatomist/core/SpringBeanParserTest.java)、[SofaJavaModelTest](../../../anatomist/anatomist-framework-sofa/src/test/java/com/anatomist/framework/sofa/SofaJavaModelTest.java) |
| T4 | [JavacMavenInputsIT](../../../anatomist/anatomist-app/src/test/java/com/anatomist/core/javac/JavacMavenInputsIT.java)：selectedInputsReuseOneMavenProcess、selectionFailureAndChangedPomAbortTheMavenSession、effectiveMavenInputsMediatePerModuleAndScopeWithoutBuildingTheReactor、explicitClasspathMediatesOnlyDeclaredReactorPaths；物理模块/标签、无关 worktree、回执/响应预算 |
| T5 | [javac-parser.md](../../../anatomist/docs/javac-parser.md)、java-scope-validation.md（历史路径 `../../../anatomist/docs/java-scope-validation.md`，已不存在） 历史矩阵与容量记录 |
| T6 | 本项目 [java_model_tests.py](../../tests/java_model_tests.py)、[java_maven_export.py](../../tests/java_maven_export.py)：基本泛型/SAM/二进制、签名回退、框架子集、单模块 Maven/profile；不能替代 T1–T4 |

| 编号 | code-ana 当前实现 |
|---|---|
| C1 | CompilerBridge（历史路径 `../../lang-adaptor/src/compiler/java/dev/codeana/compiler/CompilerBridge.java`，已不存在） |
| C2 | FactExtractor（历史路径 `../../lang-adaptor/src/main/java/dev/codeana/analysis/FactExtractor.java`，已不存在）、BinaryIndex（历史路径 `../../lang-adaptor/src/main/java/dev/codeana/analysis/BinaryIndex.java`，已不存在） |
| C3 | FrameworkAnalysis（历史路径 `../../lang-adaptor/src/main/java/dev/codeana/analysis/FrameworkAnalysis.java`，已不存在）、LombokModel（历史路径 `../../lang-adaptor/src/main/java/dev/codeana/analysis/LombokModel.java`，已不存在） |
| C4 | [Rust query](../../crates/query/src/lib.rs) |
| C5 | [Maven 导出器](../../scripts/export-java-inputs.py)、[JavaInputs](../../crates/config/src/java_inputs.rs) |
| C6 | [配置/输入捕获](../../crates/config/src/lib.rs)、[Java context 计划](../../crates/application/src/java.rs) |
| C7 | SignatureCache（历史路径 `../../lang-adaptor/src/main/java/dev/codeana/analysis/SignatureCache.java`，已不存在）、JavaAnalysis（历史路径 `../../lang-adaptor/src/main/java/dev/codeana/analysis/JavaAnalysis.java`，已不存在）、CompilerSession（历史路径 `../../lang-adaptor/src/main/java/dev/codeana/analysis/CompilerSession.java`，已不存在） |
| C8 | [验证记录](java-validation.md)、[实现范围](../java-implementation.md)、[压力脚本](../../tests/java_stress.py) |

S2/M08 已加入 Java source_roots.label：MAIN 多标签共享物理编译上下文、TEST 读取 MAIN，来源声明保持标签逻辑身份；capture metadata 二次读取保留标签，依赖/CP 仍按物理 ID。最长根/歧义、JVM full/delta 与真实 daemon 的外部 JAR/标签改名/旧 head 保留通过。

F05 已加入纯 JAR Bean 的 factory/constructor/继承 setter/lifecycle 静态查询；SOURCE 优先，成员截断或独立步骤预算耗尽不推断唯一产品。扩展输出仅可能性元数据，禁止 source/span/javac confirmed。仍需类型感知 XML 实参匹配、完整生产门禁和独立参考差分。

S1 真实阻碍已取证：冻结 iopengw 在 Discover 返回 FRAME_BUDGET，未进入 javac；66 contexts 重复携带约 9.5 万 source/4.2 万 JAR 引用。已补 worker 8 / native request 3 共享清单与有序索引/惰性输入视图；不能通过调大单帧上限冒充容量完成。原生与真实预检需重跑，context 模型/模块 NoCode/大事实预算仍缺。

容量后续：共享输入真实预检已启动 javac，新的阻塞为模型工作记录预算；回执 iopengw-semantic-preflight-20260930-201944。补显式记录限额及具体超限诊断、语法归档行/不可变回调、按文件提取/反射、资源阶段逐 context 打开关闭，并跳过关闭的项目阶段。声明/观察、模块 NoCode 和最终事实容量预算仍待补齐；新产物统一门禁和真实项目尚未验收。
