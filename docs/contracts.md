# 公共契约：事实、身份与协议

> 2026-09-29 实现状态：Java 8 真实 native/javac 已接入，新增结构化类型/ASM 的范围见 [Java 实现说明](java-implementation.md)，证据见 [验收记录](implementation/java-validation.md)。本页更广的框架与规模化设计仍为目标。

**先冻结“一个事实属于谁、对应哪个输入、怎样证明交付完成”，再实现传输和表结构。**

状态：事实组和身份分层继承原提案；CLI-daemon 已采用 gRPC/HTTP/2 + protobuf，握手协议 1；worker 协议 11，Fact IR 1。消息定义在 `proto/codeana/v1/task.proto` 与 `worker.proto`；当前 Java 模型版本 7、编译器桥版本 8。Java 8 字节码与真实 native 链路已验证；最新变更的完整门禁按实际回执记录。关联 D02/D05/D06/D07/D11/D13。

## 1. 身份

Java callable 参数 key 使用擦除后的 binary name：例如 `Api#accept(Outer$Inner,Outer$Inner[][])`。SOURCE、ASM、NoCode 目录与反射查找使用同一身份；展示名仍单独保留。原始类型注解不进入 primitive 身份。当前文件签名缓存格式为 `java8-source-signature-v10-model7-graphs`，模块格式为 `java8-module-signature-v6-model7-original-graphs`。

生成方法参与的覆盖关系使用 `origin=modeled_override`、`status=possible`，`declaration_resolved/evidence_complete=false`；保留已有 javac 覆盖事实且不重复。恢复检查 SOURCE/JAR 类泛型代入、raw 类型擦除、参数 subsignature、协变返回和整条继承路径的访问权限；final/static、降低可见性或非法返回使该方法候选全部撤销。不完整层级/类型及 256 层级节点、100000 工作步骤预算通过 `MODELED_OVERRIDE_INCOMPLETE` 明确披露；仅发布方法产生此诊断。纯 ASM 补出的外部方法使用 `binary_metadata`，不会冒充 javac 声明。

Java 字段及枚举常量初始化中的直接调用归属 FIELD/ENUM_CONSTANT；lambda 和方法引用归属自身 callback，callback 的父链保留字段。仅显式类初始化块生成 INITIALIZER，不为字段表达式另造初始化器。该行为与冻结 Anatomist 的 JavaIdentities.owner 一致；独立 bridge 校验和进入产品语义 profile，模块 NoCode key 另含 bridge SHA。

字段原始 javac occurrence 保留 `field_access=read_write` 以及 `field_mutation=true`（++/--）；公开 reads/writes 投影按冻结参考将 mutation 归类为 writes，复合赋值仍同时有 reads/writes。默认外部 owner 策略排除 java.lang/io/net/nio/math、sun/com.sun/jdk 前缀的二进制字段关系，原始 occurrence 和外部声明保留；真实 SOURCE owner 不排除。冷增量中经过 baseline/SHA 校验的原始 SOURCE catalogue 同样用于这一来源判定，NoCode 不得把 SOURCE 错当 binary。

显式泛型调用另存 `explicit_type_argument_models`；恢复检查类型实参数量、已知引用类型、替换和边界后才产生 possible 候选。非泛型方法按 Java 8 规则忽略合法实参。桥未归属的显式方法引用类型仍为 UNKNOWN，保留 unresolved；不从源码文本猜类型。

Java 恢复证据保持独立：CallSite 的原始 javac 选中声明、诊断和类型不覆盖；`recovered_parameter_type_models`、`recovered_return_type_model`、`recovered_sam` 另存。生成声明目标用 `lombok_model`，已捕获 SOURCE/binary 声明用 `source_recovery`/`binary_recovery`；全部 CallTarget 为 possible、evidence_complete=false，后两者可 declaration_resolved=true。默认 calls 排除这些目标，dispatch 给出恢复输入/阶段。按调用预算 100,000 步、递归 64/继承节点 256；缺候选证据、不可访问/不适用/歧义不作确定目标。语法节点身份是 `(source, tree_id)`，tree_id 只在单源码内有效。

| 身份 | 建议组成 / 语义 | 不保证什么 |
|---|---|---|
| RepositoryKey | 仓库身份；识别 linked worktree 的共同仓库 | 不能只取目录名 |
| ProjectKey | 仓库 + 项目相对根，非 Git 项目单独登记 | 不将同仓库不同子项目合并 |
| CheckoutKey | 一个工作区实例 | 历史提交快照不因 checkout 不同就必然重复 |
| IndexTarget | project + checkout/版本选择 + 请求配置槽位 | 不同 MAIN/TEST 请求不能竞争同一入口 |
| LogicalSymbolKey | language + 稳定模块身份 + 声明路径 + 语言签名 | 重命名、局部匿名实体跨修改匹配未必可靠 |
| WorldKey | 模块/源码集或 crate/target/features/cfg 的语义配置实例 | 不代表依赖内容未变 |
| SymbolKey | LogicalSymbolKey + WorldKey，公共编码带版本 | 不等于数据库 rowid |
| OccurrenceKey | world + source unit + occurrence kind + 规范化位置 + 区分序号 | 行号移动后可变化；diff 不能只按它判断语义变化 |
| FactKey | producer + fact kind + owner + 内容规范化摘要 + 必要重复序号 | 不用 hash 去掉有意义的重复事实 |
| SemanticProfile | adapter、工具链、Java 模型/编译器桥（适用时）、规则、已验证构建输入、按模块/world 规范化语义配置与依赖摘要 | 不等于整份 config 文件 hash；建议/报告/输出开关不机械改变语言事实 |
| SourceSnapshotId | 捕获清单及字节的身份 | 相同源码可产生不同语义图 |
| RevisionId | 一个已发布事实集合，关联 source snapshot 和 profile | 不是 Git commit；同 commit 可有多种 profile |
| SnapshotId | 历史入口身份，指向文件捕获，ANALYSIS 另指向 revision | 多个 snapshot 可以共享图字节 |
| StorageRecordId | 库内实现键 | 不进入公共 selector、跨版本匹配或分页契约 |

Java 类型引用分为两类：`type_reference` 表示声明签名、写明的声明类型和回调类型关系，按 owner/context 保留重复泛型参数与源码位置；`type_navigation` 保留 import、局部变量、new、cast、class literal 等原始 javac 类型导航。`references` 查询保留两种带 relation 的 occurrence。源码签名按冻结参考最多投影 5 层泛型，写明的通配符边界/throws/类型参数边界独立审计；回调参数读取实际捕获的声明类型。经过检查的回调恢复另存 `recovered_callback`，原 javac 属性不覆盖，其关系为 possible/evidence-incomplete，不追加未经检查的泛型参数。 补充编译按真实 world 边界选择上游 MAIN 模块：完整 Lombok 覆盖且 NoCode 投影可验证的模块使用有效声明头；本 world、解析/编码失败、部分声明或不完整 ABI 保留 SOURCE。已验证的回调投影可参与 MAIN/TEST 的 `type_reference` 基线，原 javac 属性和诊断保留；不按 scope 单独切换证据。生成嵌套类的显式局部变量在同路径/跨度/身份的独立 javac 证明通过后，另存 `supplemental_variable` 及签名/输入 SHA，恢复调用仍为 possible、evidence-incomplete。MAIN 单 world fixture 的辅助源码必须使用相同 world，TEST fixture 使用真实 MAIN/TEST 边界。未知、歧义及原始未解析类型不产生 complete 关系。单声明遍历耗尽 100000 步或 128 层时撤销该声明的类型关系，产生 `SOURCE_TYPE_REFERENCE_BUDGET` 并标记文件 partial；健康声明继续输出，取消仍中止任务。默认使用平面类型图，贯通 SDK8、模型归档、事实与 Rust 校验；扩展读取使用惰性不可变视图，扩展输出重新编码。内部 fixture 可显式使用有界旧树（48层/4096节点），产品始终传递图模式；不作解析后端回退。原子版本为 compiler8/model7/worker11/native request5，ABI2与Fact IR1不变。类型facet3、绑定facet4；旧请求/编译器上下文和混装产物均拒绝。完整真实项目语义仍须独立验收。图同时涵盖 ASM 二进制签名与生成/标量类型根；方法 formal 与类 formal 的 owner/节点身份分开，缺失外层变量不补 Object 或 owner。内部恢复 CAPTURE 导出为保留 capture_identity 的 TYPEVAR；未知上界保持不完整。 图对实际替换值不含 TYPEVAR/CAPTURE 的泛型代入使用联合根及数值边重定向，保留元数据/不可变原图；其他替换仍使用原有保守规则。图 NoCode 以图遍历验证头和注解路径，生成前按原版二进制签名 64 层/4096 节点上限验证；超过上限保留 SOURCE，不能写入后续读取会失败的缓存。源码图的写明注解按实际类型边计层，注解包装不重复加层。旧简写声明的空参数与 wildcard 空边规范化后仍由原严格图校验器校验。深层图的泛型变量查询按节点迭代，无替换的根保持共享；调用递归及总工作预算不变。

Maven 声明 UTF-8、捕获字节却不合法时，Java 8 桥保留原 hash/path 并产生 `SOURCE_ENCODING_INVALID` ERROR；该文件不交给 javac，不猜编码、不替换字节、不输出伪造声明。FactExtractor 保留 source owner、诊断、partial coverage 与原始 provenance；同上下文健康文件继续解析，全部输入无效时也给出可交付 partial。strict 仍拒绝发布，修复字节或删除文件后按 owner 清理旧诊断。生成签名遵循同一默认外部类型过滤，源码写明的类型继续保留；匿名类合成类型头不产生额外声明签名引用，其显式字段/方法与类型导航保留。

公开 key 的编码、hash 算法、路径大小写/分隔符、Unicode 和位置坐标规范须用跨平台夹具冻结。位置建议以捕获 UTF-8 字节范围为内部依据，另提供公开行范围；编码转换与原始文件 hash 分开记录，不能混用 Java UTF-16 偏移和 Rust 字节偏移。

## 2. Fact IR

| 类型 | 最少语义字段 | 约束 |
|---|---|---|
| SourceUnit | source snapshot、相对路径、内容 hash、language、world、scope | 缺失输入有明确原因，不伪造空文件 |
| Declaration | symbol、logical key、kind、parent、source owner、range、signature、origin | 合成声明可无直接源码，但必须有来源链 |
| ExternalSymbol | symbol、artifact identity、kind、signature、availability | 无源码不表示无方法体或无行为 |
| ReferenceOccurrence | owner、range、reference kind、目标/未解析线索、status | 保留每次出现，聚合在查询层完成 |
| CallSite | owner callable、occurrence、mechanism、range、resolution status | 可无目标；仍保留调用现场 |
| CallTarget | call site、target、binding kind、依据 | 源码绑定与静态可能候选区分 |
| StructuralRelation | kind、from、to、producer、依据 | contains、extends、implements、overrides 等有类型 |
| Dependency | consumer owner、dependency kind、目标/查找桶、依据 | 包含失败查找，不能只保存成功绑定 |
| Diagnostic | code、severity、owner、producer、数量/样例、原因 | 样例截断不改变事件总数 |
| Coverage | world/scope/owner、能力、状态、限制原因 | 必须能覆盖“没有任何事实”的文件和失败 producer |
| Provenance | producer/version、输入摘要、规则、直接依据或来源链 | 来源链有界，不能只写一段无法验证的说明 |
| Facet | namespace、schema version、所属实体、类型化字段 | Java 注解、Bean、Rust trait/cfg 等各自版本化 |

```text
SourceUnit -> Declaration(owner) -> CallSite
                                      |
                          +-----------+-------------+
                          |                         |
                    0..N CallTarget           resolution/coverage
                          |
                      Declaration / ExternalSymbol

calls(A,B) = 对已选择调用点目标投影并去重
call-sites = 返回每次出现
dispatch = 基于已发布事实计算可能目标，并附依据和预算限制
```

构建时已经产生的候选也必须标记为 possible；它们不能混入默认 resolved 调用关系。缓存或物化 `calls` 只作为可重建索引，不能形成第二套独立事实。

### Java 模型与绑定证据

```text
javac Tree / Element / TypeMirror（仅编译器上下文内）
    -> 独立 Java 模型（Java 专属、版本化）
    -> 声明 overlay / Java 提取 / 框架分析
    -> 公共 Fact IR + Java facets（可持久化、Rust 查询可消费）
```

| 契约 | 必须保存 / 校验 |
|---|---|
| Java 类型 | 声明类型、表达式类型、调用实例化类型分别保存；未知不能拿声明类型冒充实例化类型；类型引用受所属模型约束 |
| Java 身份与源码 | 源码名和 JVM 二进制名分开；保留泛型、SAM/覆盖依据、原始注解写法与编译器常量/默认值的区别 |
| 绑定维度 | `declaration_resolved`、`evidence_complete`、`binding_origin` 独立；字段名称待 P0 冻结 |
| 已确认目标 | 局部参数/结果类型有缺口仍可能有可靠选中声明；记录缺口，不一律丢掉目标 |
| 未确认目标 | 歧义、不可访问、不适用或仅模型恢复均不能冒充 javac 确认；候选不进入默认 resolved 投影 |
| 恢复 | 源码/模型恢复必须有唯一性、类型与继承证据；预算按独立调用查询拥有，递归共享，耗尽保留 unresolved 原因 |
| 声明覆盖 | 只允许替换 compiler-generated 构造器；源码声明不可删除。有效修饰符/能力摘要写入独立 `model_extensions[producer]`，原 javac 证据不改写；引用被替换构造器时不得保留 confirmed |
| 生成注解 | 原生静态模型的注解以 `java.annotation` 2 披露，标记 modeled/generated/来源；没有源码 span，不冒充源码注解或 javac 确认 |
| 配置控制 | Lombok 配置以 `JAVA_CONFIG` 封存；项目外祖先编码为 `.code-ana/lombok-ancestors/<六位深度>/lombok.config`，1 为直接父目录。worker 按已捕获 source 路径级联/stopBubbling，只读捕获 bytes/hash |
| 位置 | 内部 Java 模型可保存 UTF-16 span；转公共 IR 时映射到捕获 UTF-8 字节。补充平面字符、CRLF、源码 hash 必须对照；生成节点无位置时留空，不伪造 L0C0 |
| 诊断 | 原始 javac code/severity/phase、source/world/module、span、消息及重复次数；无位置诊断归上下文。语义诊断与协议/进程失败分开 |

上述字段在 Java 模型或版本化 facet 中表达，不能强迫其他语言接受 Java 类型系统。公共 CallTarget 的解析状态与 Java 绑定依据必须一致。独立 Java 模型不是公共 Fact IR，也不是可执行构建产物；不让编译器对象或内部数组 ID 泄漏成公共身份。

编译器类型树按单次模型构造限制为 48 层 / 4096 节点，为 Rust 的 128 层 JSON 解码保留包装空间。预算截断保存 `TYPE_MODEL_DEPTH_BUDGET` / `TYPE_MODEL_NODE_BUDGET`，向上传播 `complete=false`，按源码去重告警并标记文件 coverage=partial；不能将截断冒充循环引用。真实循环以 `recursive=true` 和类型变量 owner/name 或声明身份保留，其已知身份不等于预算缺失。公共 Java JSON、归档和扩展值复制的容器上限为 256，字节、节点及归档总量预算仍独立生效。独立证明仅覆盖已验收类型，不能据此声称全部类型图与 Anatomist 相等。

S2 的平面图格式目前由内部 fixture 的 `type_graph=true` 验证，产品默认和发行版本尚未切换：

```text
事实 attributes 中的 {$java_type: [0, slot]}
                 │
                 ▼
java_type_graphs[0].roots[slot] → nodes[node_id]
                                   │
                      component / upper / arguments ...
                                   │ 数字引用，可前向、可递归
                                   ▼
                              nodes[child_id]
```

| 边界 | 图格式约束 |
|---|---|
| 编译器 / RowSpool / 模型 | 每源码上下文共享不可变节点表；图版本 3，单图 100000 节点、构造深度 128；模型图表字节预算 64MiB。错误节点沿依赖边传播不完整，预算/取消撤销本次构造。 |
| 公共事实 | `java.type` 3、`java.binding` 4；单事实的全部类型根组成一个联合闭包。根据完整元数据及带角色/位置的边规范化，保留捕获身份、注解值、不完整原因；编译器局部编号和对象共享方式不成为公共身份。原模型图保留，公开编号只用于当前事实。 |
| 保留键 | 类型根用 `$java_type` 引用；用户同名对象经 `$java_object` 的键值对转义；`java_type_graphs` 为顶层证据表。保留键不能被误读成用户数据或反向绕过引用校验。 |
| Rust | 单事实及整批发布均验证版本、节点角色、整数索引、根、边与完整性传播。`JavaTypeGraphs` 只借用已发布节点，按边返回节点句柄，递归图不展开成递归 JSON，不执行 Java 类型推断。 |
| NoCode / 增量 | 图/树模式进入文件及模块签名键；文件目录保留平面图而非展开视图。模块恢复保持不可变图，按实际字段比较全量/增量/签名复用的公共事实。 |

fixture 证明不等于原子协议切换或全 iopengw 验收；最终仍须更新编译器/模型/worker/request 和缓存版本，再执行同版本 native、daemon、全语义及容量门禁。

## 3. 事实所有权与替换

Rust 索引策略 `derived-wiring-3` 从内部声明间的直接 `implements` 和 Java 提供的 `injects_possible` 推导 `wires_possible`，不做 Java 类型推断。注入关系的 registration facet 指回真实字段/参数声明；候选按目标 ID 去重排序，单候选 `confidence=inferred`，多候选 `ambiguous`。保留 `via=injection`、接口 ID `source`、全部 `candidates`、原注入位置及 `injection_evidence`。边始终 `status=possible`、`evidence_complete/declaration_resolved/runtime_confirmed=false`；不使用 qualifier/Primary 选定实现，也不写运行时 CallTarget。该政策与冻结 Anatomist WiringResolver 相同。上游源码在消费 world 内的 external alias，只有 source_origin 的 path/hash/world/module/scope 全部匹配本 revision 捕获清单、非 auxiliary，且发布声明按 source/world/signature 唯一时才转换为内部候选身份；不靠同名合并或修改 Java 原始事实。

XML 接线同样由 Rust 派生：Java 输出 Bean 的 `config_owner`（XML 显式 class）及 `config_type`（工厂产物类型）身份，Rust 沿配置引用的真实父链寻找 Bean；只有已发布 SOURCE owner 才形成起点。目标可为已确认 SOURCE/binary 类型或 XML 声明的外部类型，后者必须标记 `configured_only=true/binding_origin=xml_configuration`，不能冒充已读取字节码。引用逐条保留 `configuration_evidence`、原 XML span、source/target Bean 和位置，不以类名或行号去重。边使用 `via=xml_configuration`、静态 possible 状态，查询说明 `static_xml_configuration_open_world`。XML 解析、类查找和工厂返回类型仍由 Java 负责；动态父容器及无显式配置类的 owner 不猜接线。

派生事实归 `Owner(world, rust.derived.wiring, aggregate, static-injection-candidates)`；Rust 的规则版本进入 SemanticProfile。候选发布前清除旧/新 manifest 的所有对应 aggregate owner，替换 adapter 事实后整体重算，任何错误或取消仍保留旧 head。存储仅提供定向候选读写/校验接口，派生政策在 Rust application。公开 revision/baseline 导出保留这些事实；返回 Java worker 的 adapter baseline 排除这些 owner，禁止 worker 冒用此 producer。`type-relations <注入声明> --relation wires_possible` 返回静态候选及开放世界的未确认说明。

| Owner 维度 | 用法 |
|---|---|
| source owner | 一个源文件在一个 world 下生成的事实 |
| resource owner | XML/config 等资源与相关 world 的事实 |
| producer owner | Java core、Lombok、Spring、SOFA 等生成者 |
| aggregate owner | 确实跨文件生成的结果，必须显式列出依赖输入与替换单元 |

建议 `OwnerKey = (world, producer, owner_kind, owner_id)`。声明唯一拥有者由协议明确；其他 producer 用独立关系或 facet 补充，不能覆盖同一声明的归属。

```text
delta = {
  baseline_revision, input_snapshot, profile,
  replacement_owners, deleted_source_units,
  declarations, occurrences, relations, dependencies,
  diagnostics, coverage, provenance
}

candidate = baseline - 全部 replacement_owners 的旧事实 + delta 新事实
```

规则：空替换集与“某 owner 被替换为空”不同。文件删除、解析失败、插件关闭都要表达旧事实清理；新 coverage 说明原因。依赖其他 owner 的入边要通过失效闭包重算或转为未解析事实，不能靠外键级联悄悄删掉证据。关闭 producer 的 profile 变化若不能证明局部更新安全，退全量。

封存源码或资源 hash 变化时，所有引用该文件的旧 owner 都必须出现在 replacement 集；扩展输出内容恰好相同也不能跳过。发布校验继续按捕获清单检查这一点。模块 NoCode 只减少 javac 输入，不减少项目资源阶段；受影响上下文的资源 owner 保守地整组替换，包括删除和 producer 禁用。

## 4. worker 协议

本节定义 daemon 与 worker 的逻辑消息。Java 场景中的 worker 是独立 Rust host，host 再通过进程内 C ABI 调用 `lang-adaptor/` 构建的 GraalVM Java 共享库；FFI 不跨进程。ABI 的线程、内存和错误约束见 [Java 适配器](modules/java-worker.md)。

建议 worker IPC 使用有长度前缀的 protobuf 消息，复用 `.proto` 的 adapter/Fact IR 定义，与 CLI 的 gRPC 服务分别版本化；Java FFI 的字节缓冲可承载同一消息编码。HTTP/2 + protobuf 的用户决策针对 CLI-daemon，是否统一 worker RPC 承载需另行验证，不自动增加 Java 内部网络服务。

```text
daemon                                  worker
  | ---- Hello(versions, capabilities) ----> |
  | <--- Compatible / Unsupported -------- |
  | ---- Discover(captured workspace) ----> |
  | <--- BuildContext / required inputs --- |
  |       捕获并冻结补充输入                |
  | ---- Analyze(attempt, plan, baseline) -> |
  | <--- Progress / Diagnostic ------------ |
  | <--- ReplanNeeded --------------------- | 终止该 attempt，扩大后重跑
  |                 或                     |
  | <--- ResultBegin(full/delta, identity) - |
  | <--- ResultChunk(seq, section, data) --- | 分析冻结后才开始交付
  | <--- ResultEnd(counts, checksums) ------- |
  | 校验 / 持久化 / 发布                    |
  | ---- PublishAck(revision) ------------> | 才能复用会话状态
```

| 消息 / 对象 | 必须携带 |
|---|---|
| Hello | worker identity、adapter protocol、Fact IR、facet/capability versions、执行平台兼容信息 |
| AnalyzeRequest | task/attempt、不可变输入清单、BuildContext、冻结的语言/module/world 配置与摘要、profile、full/delta、baseline revision、replacement plan、预算；Java 另含 compiler plan（见下表） |
| BaselineFacts | 固定身份、符号/类型/Bean 摘要、依赖与查找桶、所需未变事实、coverage、provenance |
| ResultBegin | 期望基线、实际输入摘要、最终替换范围、profile、模式 |
| ResultChunk | 连续序号、事实段类型、条数、长度；每帧有上限 |
| ResultEnd | 所有段条数、内容校验、最终诊断/coverage 总结、完整交付标记 |
| Cancel / terminal | task/attempt、是否已经冻结/发布、失败原因、可重试性质 |
| BuildAdvice | 缺失输入、project/module/world、基于哪个捕获输入提出建议、cwd、argv、工具链要求、目的、预期产物、可能影响、重试操作；仅建议，产品不执行 |

Java compiler plan 明确区分三种范围：

| 范围 | 含义 |
|---|---|
| semantic inputs | javac 可见的当前模块/前置模块源码、有序 classpath、JDK、声明签名；必须全部来自封存输入 |
| publication sources | 本次需要产生新事实的源码，可小于 semantic inputs；读取某文件不自动授予发布其事实的权限 |
| replacement owners | delta 清理与替换的最终 owner 集；还包括删除、资源、失败 producer，不能由非空输出反推 |

计划保存物理构建模块与发布模块/world 的映射；MAIN 不消费 TEST，TEST 可消费本模块 MAIN。BaselineFacts 不能直接充当 javac classpath；需完整封存源码或经验证的签名投影，输入不足按 ReplanNeeded 处理。

D17 的 Java 8 实现采用目标 JDK 子进程，使用独立版本化的编译器内部协议：目标 JDK/桥版本、source hash、task/attempt/context 身份、帧序号/校验、诊断和完成标记。其完成只表示一个编译器上下文结束，不替代对 daemon 的 ResultEnd 或 PublishAck。C ABI、worker IPC、编译器协议分别协商，未知必需能力明确拒绝。

重排、缺块、重复终结、身份漂移、未知必需 facet 都拒绝发布。P0 建议失败后整次 attempt 重传，先不做断点续传。建议 host 用专用继承管道承载 IPC，stdout/stderr 只处理日志，避免嵌入库的意外输出污染协议。传输有背压，daemon、Rust host 和 Java 桥接层都不能保留多份无限增长的序列化结果。

BuildAdvice 的 `argv` 为结构化参数数组，cwd 明确指向用户可操作的目标工作区；不能建议进入即将被清理的内部捕获目录。历史 commit 缺输入时说明需在哪个提交的用户 checkout 中准备产物。建议不作为进度成功事件，不触发自动构建/自动重试；Agent/人类独立执行后重新提交，daemon 再核验并捕获产物。证据不足无法给出可靠命令时，只返回所缺输入与所需信息，不猜构建参数。

当前生产与 mock worker 协议均为 11，Fact IR 仍为 1；Discover 可返回 BuildContext 或结构化 BuildAdvice。已传输 `missing/cwd/argv/purpose/expected/impact/retry/capture/module/world`，project 从任务身份取得；Java Discover 传输冻结的 JavaConfig、JavaContext 和 source/resource 输入；缺 Maven metadata 返回外部导出建议，Gradle 不支持。CLI/daemon 握手协议仍为 1，旧 worker 协议在分析前拒绝。

## 5. CLI 与 daemon 协议

采用 **HTTP/2 + Protocol Buffers**，建议通过标准 gRPC 定义服务、生成客户端/服务端绑定并传输消息。`.proto` 集中放在拟建 `proto/codeana/v1/`，Rust 使用生成代码映射领域类型。HTTP/2 帧与 protobuf 序列化由协议栈处理，不再采用原建议的自定义 socket + JSON 任务协议。

```text
CLI 命令 / pipeline JSON 配置
              |
       TaskRequest protobuf
              |
       gRPC / HTTP/2（本机）
              |
       daemon TaskService
              |
       TaskEvent protobuf 流
              |
       CLI 写出 本项目版本化文本响应
```

| 对象 | 含义 |
|---|---|
| TaskRequest | client request ID、operation/pipeline spec、项目选择、显式类型化配置覆盖、预算、期望版本、断连策略 |
| TaskAccepted | daemon 分配的任务身份；若观测模式关闭，不对外泄露运行 ID |
| TaskEvent | accepted/queued/running/progress/terminal；事件不冒充查询结果 |
| TaskResult | execution、identity、assessment、gate、publication、report，以及记录和终止证据 |
| Attach / Status / Cancel | 重连查已登记任务、读取状态、表达取消；不是重新提交写操作 |

| 建议 RPC | 形式 | 作用 |
|---|---|---|
| Handshake | unary | 协商服务协议、能力、版本；HTTP/2 连接成功不代表应用协议兼容 |
| SubmitTask | unary | 登记任务与幂等键，返回 TaskAccepted；不等待长任务完成 |
| WatchTask | server streaming | 首次订阅或重连，按事件游标读取进度、输出和终态 |
| GetTask | unary | 查询任务、发布与结果可用状态 |
| CancelTask | unary | 提交取消意图，由任务状态机裁决 |

SubmitTask 到 WatchTask 之间有有界订阅宽限期，防止刚提交任务就因尚无流被取消；之后按任务断连策略处理。WatchTask 的事件序号只用于传输去重/重连，不替代语义 query/page cursor。结果过期或事件窗口缺失时明确报告，不能自动重跑写任务。

内部 RPC 版本与 `本项目版本化文本响应` 独立。建议 daemon 的输出模块按最终文本预算生成输出块，封装为 TaskEvent 的 protobuf `bytes` 字段，CLI 原样写出。typed pipeline 记录继续留在 daemon 内；任务身份、状态和证据终态使用类型化字段，不能只塞入一个不透明 JSON 字符串。pipeline 的 JSON 配置格式和内部报告格式不受传输编码变化影响。

| 协议规则 | 要求 |
|---|---|
| 字段演进 | field number 固定；删除字段保留编号/名称；破坏性变化升级 package/version |
| 未知与默认值 | 需要区分缺失/false/0 时显式 presence；枚举零值为 UNSPECIFIED；未知状态不当成功 |
| 事件类型 | `oneof` 区分 accepted/progress/output/terminal，终态必须含真实任务与发布状态 |
| 完成判定 | RPC status、任务终态、CLI 退出码分别处理；流结束或 HTTP 200 不代表任务成功 |
| 超时与取消 | RPC deadline 约束该次交互；任务 deadline 约束后台执行，默认 120 秒、显式最大 86400 秒；单 javac context 仍最大 3600 秒；取消订阅按任务策略传播 |
| 背压与预算 | 单条 protobuf 消息、待发送队列、总输出各设上限；HTTP/2 流控不等于业务内存预算 |
| 文本终态预算 | 先为真实终态预留空间；证据明细过大时只在文本投影中保留完整字符串样本，并标记 `terminal_evidence_details` 与省略计数。已保存 TaskResult 和 typed terminal 保留完整已收集明细；不能因此撤销发布或将 semantic/assessment 改成 complete。记录截断另标 `output_bytes`/enumeration=false；strict 拒绝文本明细或记录截断，已有失败退出码不被覆盖 |
| 重试 | SubmitTask 使用应用幂等键；传输重试不代表允许重复执行写任务 |
| 身份与摘要 | protobuf 的序列化字节不作为跨版本规范身份；语义摘要按独立规范化规则计算，见 [序列化非规范性说明](https://protobuf.dev/programming-guides/serialization-not-canonical/) |

服务/流模式依据 [gRPC 核心概念](https://grpc.io/docs/what-is-grpc/core-concepts/)，HTTP/2 承载见 [gRPC 协议](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md)，字段演进见 [protobuf 指南](https://protobuf.dev/programming-guides/proto3/)。这是协议选型依据，尚未进行本项目的端到端验证。

配置的权威解释在 daemon：CLI 传入显式 overrides，不将未填写的 protobuf 默认值当成用户选择。公共配置和 Java/Rust 的类型化子消息分别校验；扩展配置按已注册 namespace/schema version 编码。EffectiveConfig、字段来源和 schema/defaults 版本随任务冻结，worker 禁止自行重新读取工作区或用户 TOML；详见 [配置契约](configuration.md)。

## 6. 证据与版本兼容

| 轴 | 判断内容 | 不能被什么替代 |
|---|---|---|
| enumeration | 所有 subject 是否从头读至明确结束 | SQL 返回空页、只读源码末页 |
| semantic | 选定 world/scope 内能力和覆盖是否充分 | 执行成功、目标名称解析成功 |
| limits | 计算、输入和输出是否受预算限制 | 普通翻页耗尽、下游 source 读取成功 |

否定结论还须符合具体操作的语义：三轴满足是必要条件，`overview project` 等操作仍不能声明否定安全。限制按阶段、subject、范围合并，只有本项目契约允许的 replacement 能替换对应证据。

| 独立版本 | 升级规则 |
|---|---|
| CLI text / typed pipeline | 本项目独立定义版本、参数、退出码与建议动作；以自己的 fixture/golden 验证，不兼容 Anatomist v5/v1 |
| CLI-daemon / adapter protocol | 握手协商；不兼容时不抢占或终止已有服务，升级接替遵守用户级单 daemon 约束 |
| Java native ABI | 独立版本；共享库、生成 header、Rust 绑定和平台匹配后才进入分析；禁止以 JNI 兜底 |
| Config schema / adapter config schema | 版本化字段、默认值与归属；未知必需字段失败；不提供旧配置迁移；TOML 与语言配置 protobuf 需对照验证 |
| Fact IR / facet / graph semantics | 明确兼容读取或重建，未知必需语义不能跳过 |
| graph schema / catalog schema | 本项目未来版本分别演进；失败保留已发布工件；不导入 Anatomist 数据库 |
| source capture / report | 独立版本、明确不可读原因，不借重跑写操作修复 |

本项目 selector 需覆盖 provider/module/scope、重载签名、外部声明、位置和分页身份；歧义明确拒绝。命令、文本协议名/版本、默认值与退出码在 P0 独立冻结；Anatomist 的同名概念仅作设计参考，不提供旧 ID 映射或旧协议兼容层。

## 当前 worker 10 传输

`Discover.java` 固定工具链、bridge、context、私有输入路径与语义摘要。`BaselineChunk`（连续序号）和 `BaselineEnd`（总数/SHA-256）先于 Analyze，旧内嵌 baseline 为空。当前 C ABI 用有界 JSON chunk 承载独立模型/事实；worker 专用 FD 用长度前缀 protobuf，stdout/stderr 不参与协议。compiler 子进程以私有结果文件加 context/长度/hash 回执完成；每次上下文后清理。`PublishAck` 成功后 worker 返回 `Acked` 并释放任务状态；daemon 仅在身份与版本匹配、进程存活时复用一个空闲 worker，超时或失败则丢弃。

## Java 构建期扩展

| 边界 | 当前契约 |
|---|---|
| 注册 | 五类 SPI：JavaModelExtension、JavaModelAnalyzer、JavaCallSiteEvidenceProvider、ProjectResourceProvider、ProjectResourceAnalyzer；仅本项目构建期装配 |
| 顺序与身份 | registry 校验 id/version/requires，拓扑排序；缺依赖、重复 id、循环、启用依赖缺失拒绝。实现类、producer、版本、facet、有效配置形成指纹 |
| 模型 | 深度只读输入；overlay 只增加无方法体声明，校验已有来源、父声明、源文件、唯一签名。不能修改 javac 的树、选中目标、诊断或 ownership |
| 事实 | 回调先写临时 FactSink；producer、source/world/owner、facet/schema 与图引用全部验证后提交。不能创建编译器声明或 CallTarget |
| 调用证据 | 只能在既有调用点上补 facet；不能改变调用绑定或提升为 confirmed |
| 资源 | provider 只筛选封存 inventory；未知/重复输入拒绝，resource analyzer 读取已暂存 Java 事实 |
| 失败 | 回调失败丢弃整份输出，保留其他 producer，并产生诊断与 partial coverage；取消、Error 和 strict 门禁继续终止任务。耗时只在 telemetry 中，不参与事实摘要 |
| 演进 | 扩展指纹记录在 context provenance、参与增量与 NoCode key；版本/配置/启用变化必须重算并清理旧 owner |

当前 `java.spring.mvc`、`java.spring.handler`、`java.spring.injection`、`java.spring.xml`、`java.reflection` facet 的 schema 均为 1。`handles_possible`/`injects_possible` 表达静态注册/注入类型依据。反射 CallTarget 为 possible，origin=reflection_model，保留精确查找和值来源，默认 calls 不投影，dispatch 披露来源。Fact IR 仍为 1。项目资源阶段在全部 Java/call context 暂存后执行，以封存 source worlds 过滤可见性；上游扩展指纹变化进入失效闭包。资源 selector 在项目事务内执行，只筛选封存 provider inventory；谓词/构造失败保留其他输出，取消继续终止。独立视图预算及内置单声明级迁移仍待完成。

XML 模型以 namespace 身份识别框架，DOCTYPE/外部实体/网络 schema 禁用。配置树使用捕获 UTF-8 的真实字节 span；SAX locator 另标明为 tag-end 位置。配置引用和成员引用只使用 `config`/`config_member`/`config_nested_bean` possible 关系，不制造 CallTarget 或运行时 Bean 确认。畸形/超预算文档丢弃该文档的树，保留其他成功文档并披露 partial；hash 漂移、取消继续终止。Spring 内置版本为 4、SOFA 为 2，进入扩展失效指纹。

### worker 10 / native ABI 2 的事实暂存

| 边界 | 当前实现 |
|---|---|
| daemon / Rust host | baseline 和结果按记录写入任务拥有的 CAF1 暂存；按 64 行及 protobuf 编码字节预算分块，校验次序、数量和事实数组 SHA256 |
| Rust host / native | request version 2；baseline_archive 传 path/sha256/count/bytes，Java 验证后读取。ABI 2 的结果读取 offset 为 64 位；库内 JSON 直接写文件，host 分段拷贝到任务文件后逐行解码 |
| Java 汇总 | 每 context 结果进入 RecordArchive；仅保留调度元数据，owner 差异比较逐组读取。并行待消费模型最多为 parallelism |
| 校验 / 发布 | 私有 SQLite 候选从已 pin 的 baseline 复制，再按 owner 删除/插入；行形状、悬挂引用、coverage 全部校验后 strict 判断，再 CAS 发布 |
| 生命周期 | 基线读者和候选目录在使用期间 pin；失败/取消丢弃候选；强杀遗留候选由 recover 清理。已发布 revision 保持只读 |

Fact IR 仍为 1，compiler 为 6、model 为 5；旧 worker/ABI/compiler/model 明确拒绝，NoCode 格式更新为 v6-model5（嵌套 binary 参数身份、类型变量增加声明 owner，旧派生缓存按 miss 处理）。模型按固定 section 写 CAF1 行，类型树以共享表和向后引用编码；校验 context/hash/记录顺序/终止计数后逐行解码，保留节点 child roles 和 symbol。本次没有提高默认 64 MiB/100000 上限；每个 context 的独立模型仍保留内存视图，完整有界模型视图、模块归档和全容量验收尚未完成。

文件签名缓存保持 8 MiB 文件上限；与模块缓存共享 256 MiB 磁盘总量、进程内锁及两目录外的文件锁。成功读取刷新使用时间，写入按 LRU 驱逐。消费前在锁内验证 hash/NoCode 并复制到任务私有目录；在途 javac 消费该不可变副本，原缓存可驱逐，任务结束清理副本。单个任务内文件/模块私有签名输入合计限制 256 MiB，文件 catalogue 累计 64 MiB；关闭或复制失败释放预留，超限回退 SOURCE。锁等待可取消且最长 5 秒；miss/损坏/可选缓存不可写均回退 SOURCE 分析，不伪造成功复用。模块 NoCode 使用独立目录与格式，共用预算和协调域；淘汰任一目录的旧条目不影响任务私有副本。拒绝目录和共享锁的符号链接。

Java 来源身份：source_roots.label 可指定逻辑发布标签；SourceUnit.module 保存标签（默认物理 module.id），SourceUnit.world 保持物理 module.id × MAIN/TEST。上下文、依赖和 classpath 查找依据物理身份，不能用标签分割编译器上下文。相同最深根的不同归属拒绝；标签改名进入语义指纹。TEST-only 发布与未发布 MAIN 前置输入的角色协议尚未完成。

Spring XML 可查询封存 JAR 的声明和继承成员；SOURCE 优先，不加载/初始化业务类。查询最多 256 层级访问、100000 总查找步骤，缺类、声明读取超限或耗尽查询预算保留 partial，不据此推断唯一工厂产品。构造器、factory/static、setter、init/destroy 的 name/arity 候选关系仍为 possible，已加入 explicit type / Bean ref 类型提示与 constructor index 重排；已知提示保守过滤同元数重载，未知提示保留候选歧义，候选共有且完整的返回类型可作为静态产品类型。显式不同 FQN 不按同名误匹配。资源阶段新增的 external_symbol 只允许 context owner、无 source/span、source_available=false、binding_origin=binary_metadata、origin=producer、possible 且两个证据标志为 false；禁止冒充 javac 声明。

worker 10 的 JavaConfig 使用 input_table/classpath_table；JavaContext 保存有序 source_indices/classpath_indices/resource_indices，publish_indices 是 source_indices 子集且 world/scope 必须属于该 context。共享清单的 SourceUnit 必须与捕获 manifest 一致，重复路径不同身份拒绝。压缩态不允许并存展开输入；源/资源索引不重复，JAR 顺序及重复项保留。全表每类最多 10000、最多 256 contexts、每 context 每类 10000 indices、全任务最多 1000000 references；protobuf packed 索引在分配前计数，越界/歧义在 javac 前失败。保持 8 MiB 单帧，编码前检查长度。

Native ABI 签名仍为 2；当前分析 JSON 使用 analysis request 4，支持展开和共享输入；保留测试兼容 request 2（展开）/3（共享）。bundle manifest 要求 analysis_request=4 / compiler_protocol=6 / java_model=5，旧产物在载入前拒绝。native 为各 context 创建惰性输入视图，不将全部重复来源描述重新复制到请求。目标 javac 接收 compiler request 6，模型格式仍为 5；receipt 校验实际 model_archive_bytes，禁止旧编译器静默忽略限额。

模型容量追加：JavaContext.model_record_limit 对应 resources.index.java_model_record_limit（默认 100000，1–5000000）；0 仅在 worker protobuf 中表示使用默认值。编译器分别披露 context/phase/source/count/limit，不将超限和取消混为一个错误。模型归档的物理记录上限 2000000 包括共享类型/源/诊断等行，仍受默认 64 MiB 字节限额约束。声明/观察为任务拥有的惰性可写归档行：只保留 64 行解码缓存和已修改行，借出的嵌套容器保持普通 Map/List 别名语义；语法为不可变归档行。扩展回调获得声明/观察的稳定只读快照，后续写入不影响快照。关闭模型后所有归档视图失效。提取/反射按文件展开，输入模型消费完成或签名优化回退后关闭并删除语法归档；项目资源阶段的暂存模型另按 context 打开/关闭。扩展关闭时不创建项目模型归档或完整项目事实副本。

编译器侧也使用任务独占的逐行声明/观察/语法临时归档，写入前共享类型树；每份临时归档默认 64 MiB（使用显式 model_archive_bytes）、2000000 行、100000 个类型、64 MiB 类型记录，最多三个归档，不提升语义结果默认上限。仅最新声明行可继续补 lambda/SAM，旧行按需解码，解码类型缓存最多 64 项；成功、可恢复失败及父任务回收均清理归档。原始编译器图在最终模型归档写入前释放。Java 8 未完成推断的下界 null 保留 incomplete 原因；捕获类型编号按源码内确定顺序映射，实际上下界及各捕获身份保留，不改写源码字面量。


### Java 任务预算与共享模型

| 资源 | 默认 / 显式范围 | 执行边界 |
|---|---|---|
| 模型归档 | 64 MiB / 64 KiB–1 GiB，每 context | compiler 声明/观察/语法 spool、最终模型、native 读取与项目暂存使用同一限额；控制 JSON 仍 64 MiB |
| 原生堆 | 256 MiB / 8–1024 MiB | Graal v5 isolate params 传入 -Xmx；codeana_java_heap_bytes 返回实际堆并校验，warm reuse 必须一致；C ABI 仍 2 |
| javac | 默认 128 MiB、120 秒 | heap 8–512 MiB，context deadline 1–3600 秒；超时杀掉独占子进程并清理 |
| 事实/结果 | 100000 条、64 MiB / 最多 5000000 条、8 GiB | native 汇总/基线/结果 JSON、Rust 基线/解码/daemon 接收与存储基线导出；owner 逐个解码，先限制数量；单行/IPC 仍 8 MiB |
| 任务 scratch | 2 GiB / 64 KiB–32 GiB | 捕获原件、prepared 输入副本、compiler 模型/私有签名 pin、native JSON、Rust 基线/结果均计入；候选 SQLite 逐批和备份逐步校验；100ms 采样超限杀 worker 进程组；失败不发布 |

scratch 不等于文件系统硬配额：采样写入可短暂超限；SQLite 每 256 行/备份每 128 页检查且发布前复核。按文件逻辑长度计数；缓存写入预留 64 MiB、blob 发布副本预留最大捕获文件，持久共享缓存另受缓存上限约束。模块归档与 5M/8GiB/32GiB 的完整容量验收仍缺，不能用配置上限代替验收。

预算进入 adapter semantic_identity 和 worker 复用身份。Context 的核心事实复制进任务归档后关闭/删除，不累计保留所有 core spool；失败/签名回退也释放。经过 archive 校验的 type 树递归不可变且带 ImmutableType 标记，扩展 callback 与 ModelOverlay 暂存路径共享此不可变节点，overlay 仅深拷贝其余可变容器；公共 mutableMap 继续提供独立且可编辑的完整副本。类型替换仅复制变化路径，派生节点同样封存为不可变类型。内置 Lombok 只复制需修改的声明/attributes 容器；不修改共享的 type 树。回调快照的修改/追加行在入口写入任务私有 RowSpool，采用同 context 模型字节上限；读取按行解码，encoder 身份表在封存后释放，成功/失败随父模型关闭删除。完成模型在同一 Java/call 阶段共用只读快照，各回调仍固定自己的事实视图长度。

核心提取器的事实限额来自当前 RecordArchive，而非固定 100000；扩展 validate 的上下文总量使用相同任务 fact_limit，单次扩展写出仍有独立 100000 上限。ModelOverlay 的总声明检查使用 context model_record_limit；取消/错误隔离规则不变。Java 8 SOURCE Element 的准确 TreePath 在声明时建立身份缓存，未知/外部首次查询后缓存；不使用引用处替代声明位置，模型封存前清空并释放编译器图。

数字传输支持精确的整数、小数与指数，整数配置必须精确落在 long 范围，不允许截断；数字 token 最长 1024 字符。NaN/Infinity 不属于 JSON 数字，SOURCE 和 ASM 常量以 `{ "non_finite": "NaN|Infinity|-Infinity" }` 形式保存。诊断按源码 URI 与位置索引，查询匹配后恢复编译器报告顺序，不改变原始诊断证据。

模块 NoCode 缓存格式 `java8-module-signature-v3-model5-original`。串行按上游 MAIN 顺序分析，并行按依赖就绪批次分析；消费模块等待父模块完成，环依赖保留 SOURCE。父模块与消费模块的有序编译 classpath 内容身份不一致时保留 SOURCE，避免 Maven 版本调解改变常量或类型后复用错误的签名。归档仅投影模型扩展之前的原始 javac 声明，不含方法 Code；恢复 SOURCE 目录后重新执行当前 context 的模型扩展，生成方法仍为 possible，不会升级为 javac-confirmed。诊断、目录冲突或不完整模型回退完整 SOURCE。持久化原子提交、验证摘要后复制到任务私有 pin；共享目录解码/LRU/租约使用同一锁，保护并行解码和在途引用。文件/模块共享 256 MiB 磁盘和任务私有 pin 预算已实现，真实 JVM 的双向淘汰、跨进程写入、取消及副本消费通过；新完整生产容量仍待验收。

冻结 JAR 的中央目录若被 Java25 拒绝，可使用 Anatomist 同范围的受限本地 ZIP 顺序读取；不改写原始输入/hash。每次扫描最多 50000 entries、8 MiB 名称、256 MiB 解压，保留 CRC、class 2 MiB 上限、取消和生命周期检查；无本地 ZIP 头或内容损坏仍拒绝。

## 大文件语法与来源（2026-10-01）

| 契约 | 当前编码 |
|---|---|
| 小文件语法 | `java.syntax@1` 保留原 ID、完整 nodes 与 source owner |
| 大文件语法 | `java.syntax@2`；约 1 MiB 节点负载一片，单条记录仍受 8 MiB 上限；单节点不能容纳时明确失败 |
| 分片身份 | `(world, source, chunk_index)`；`source_hash`、`node_offset`、`total_nodes`、`last_chunk`；节点 ID/parent/roles 不变，可跨片引用 |
| 校验与增量 | Rust 校验源码摘要和每片范围/尾标记；JVM 验证节点无丢失、父节点存在。变化按整个 source owner 替换，缩小为 @1 时删除旧片 |
| 外部来源 | 原有有序 `artifact_inputs` 在 context provenance 保存一次；外部行只保留已证明的 JAR 名称，并以 `artifact_origin` 记录 CP 索引/name/SHA 或 platform/name/SHA |
| 辅助源码 | `source_origin` 保留 path/hash/world/module/scope；不把 SOURCE 声明归到未证明的 JAR。该 external 行不发布源码正文；Rust 导航核对 origin 五元组，并要求对应 world 的唯一已发布 javac SOURCE 声明有 span，随后读该声明的源码；调用 world/target 不变，隐藏前置、binary/model-only 和歧义拒绝 |
| OOM 回执 | 每个阶段预分配 `NATIVE_HEAP_EXHAUSTED` 的 context/phase；OOM 返回不再临时分配诊断；并行时仅代表最近开始的阶段 |

本轮没有扩大单帧上限。语法分片不是 Java 模型协议升级；worker 9 / request 4 / ABI 2 / compiler 6 / model 5 / Fact IR 1 保持。


## TEST-only 前置输入与扩展索引（2026-10-01）

| 契约 | 当前实现 |
|---|---|
| 传输 | worker 10；新增 `SourceUnit.auxiliary` 与 `JavaContext.prerequisite`。request 4 / ABI 2 / compiler 6 / model 5 / Fact IR 1 不变 |
| 捕获 | TEST-only 仍封存自身及已调解 reactor MAIN 源码、MAIN/TEST 各自 CP；语言、scope、world、原始字节 hash 不变。默认 auxiliary=false，旧清单 JSON 身份保持 |
| 发布 | 辅助 SOURCE 只供编译和失效；不得进入 publish_indices、覆盖清单或公共 source facts。Rust 候选校验与 JVM 输入校验均拒绝辅助发布 |
| MAIN 前置 context | 仅 MAIN，输入不发布；执行编译、模型扩展和可用 NoCode 缓存，跳过调用/反射/Java/project 事实阶段。TEST 消费 SOURCE 或验证的目录；缓存失败仍回退 SOURCE |
| Maven | TEST-only 导出保留 MAIN 源/生成源及 MAIN CP/已调解依赖；选中 scope 仍只有 TEST。MAIN-only 不解析 TEST 依赖；不递归扩张其他模块的依赖清单 |
| 编码失败 | UTF-8 严格校验失败返回 `SOURCE_ENCODING_INVALID`，含 context、捕获路径、UTF-8 与原始 SHA；不替换字节、不转换源码或混用解码后哈希 |
| 扩展读取 | Framework/DI/XML/SOFA 按声明位置索引，最多缓存 64 个解码行；内部 header 只用于选择，不进入公共事实。SOURCE/外部行排序和遮蔽优先级保留 |
| Lombok | builtin 4；支持冻结参考的无显式选项 SuperBuilder 子集：泛型继承、抽象父类、Default、生成 builder/impl/self/build、有限递归自界；缺失父源码/raw 父参数/custom builder/不支持选项均披露 partial |
| 捕获类型 | 按正式参数 owner/index 分配身份，递归 CAPTURE 用有限身份引用；查询递归/工作预算与 4096 个上下文捕获上限显式披露，恢复目标仍为 possible |

完整协议/原生/真实项目门禁与 52 项能力验收见 `docs/implementation/java-validation.md`；这里描述实现，不把 fixture 当作最终通过。

恢复可读取变量初始化、赋值左值、方法返回及已唯一选中调用的参数目标，对未绑定方法形式参数施加返回约束并校验参数/上下界/输出转换。泛型调用的候选目标、lambda/SAM 的隐式参数和返回校验使用任务内候选缓存；失败候选不写入原始观察、外部声明或正式恢复缓存。回调参数按实际绑定身份匹配，支持嵌套回调读取外层参数；显式参数类型、参数数目、void 表达式形状及有界块返回路径均校验。无目标的形式参数保持原身份；强转仅给 lambda/reference 提供函数接口目标，不给 invocation 猜返回目标。恢复可替代依赖 modeled 输入时 javac 误选的 Object/JDK 调用目标，但原始调用点和 java.binding 仍保留 javac 诊断、选中声明与类型，恢复目标始终 possible。完整泛型 LUB、复杂块完成性及数组返回目标仍未实现或未验收。

接口和注解的父接口使用 extends；普通类/枚举实现接口使用 implements。隐式 Object/Enum 根不生成图边，源码显式 extends Object 仍保留；原始结构化 superclass 类型不删除。恢复的枚举 switch 标签仅接受 modeled selector 返回的唯一 SOURCE 枚举常量，读取关系标为 possible。class literal 保留真实类型引用，不创建 javac 的伪 class 字段或字段读取。

Lombok builtin 6 使用冻结参考的 basic 首字母大写规则（含第二字符大写和 Unicode titlecase），equals/canEqual 的参数名为 other。builder 存储字段可按原始字段身份映射源码注解位置及书写值：java.annotation 标为 source_projection/modeled、evidence_complete=false，不把这种源码映射当成注解处理器实际复制或原始声明上的编译器注解。

### 任务事实元数据索引

任务事实归档可附带平坦的 `id/signature/kind/source/world/generated` 磁盘索引，每份最多 64 MiB；内存仅保留分页偏移，不保留完整事实或类型树。预算耗尽后的记录继续写入完整事实，查询回退完整解码。索引字节计入任务 scratch，关闭归档时删除；公开 CAF1/Fact IR 和事实内容不变。Java/framework/call/项目视图传递索引，完整事实仅在回调实际消费时读取。Java 8 桥单独编译并执行归档边界测试；该内部优化不改变 native/worker 协议版本。


## 当前 compiler 7 / model 6（2026-10-01）

| 契约 | 当前实现 |
|---|---|
| 版本 | worker 10 / request 4 / ABI 2 / compiler 7 / Java model 6 / Fact IR 1；旧模型和派生缓存拒绝或按 miss 处理 |
| 写法 | 桥恢复 Java 8 TYPE_USE 原写法、注解常量及默认值；属性 `modifier_annotation_types`、`written_type_complete`、`has_initializer` 和 `receiver_type_model` 属于 Java 模型 |
| 签名 | primitive 身份只含 Java 关键字；不完整 written type 不生成 NoCode；调用声明类型采用一致的写法模型 |
| 枚举 | `values/valueOf` 是源码拥有的 javac 隐式声明，无虚构方法体；缺少真实标识符范围的 enum new-expression 不作为源码调用点 |
| 辅助源码 | 仍全部参与 javac parse/analyze，保留声明、诊断、外部符号和初始化存在性；不发布的方法体省去 syntax/observation 输出。完整辅助模型是测试对照模式，不是产品回退 |
| Lombok | 内置版本 5；原写法注解复制策略参考 Anatomist，显式选项与默认属性分开；生成成员仍为 modeled/possible |
| Maven | 外部显式 `java-inputs export` 由 Rust 编排，Java helper 只提供 Maven JVM API 访问；daemon 不执行导出或构建 |

上文带日期的版本记录保留其历史含义；以上是当前构建契约。完整 iopengw 语义差分、容量和发布包仍按实际验收回执判断。

生成辅助 NoCode 的第二次 javac 可以为原始 ERROR/UNKNOWN/NONE 表达式提供独立基本类型证明。仅接纳两侧唯一的同路径、原位置、节点种类匹配，且新类型完整；不替换原始 syntax、调用点类型或诊断。源 provenance 的 `supplemental_expression_evidence` 保留 UTF-8 字节 span、原类型种类、类型、signature/auxiliary_inputs SHA 和 `original_evidence_retained=true`。调用恢复可以消费该证明，结果仍为 possible，不据此宣称运行时调用或原始 javac 确认。辅助世界的 ABI/Lombok 覆盖不完整时不补充；配置、原始输入或生成返回类型变化必须重新归属并清除失效证明。


### 调用证据共用（2026-10-01）

| 记录 | 当前契约 |
|---|---|
| CallSite | 保存完整声明、选中、表达式、SAM 和恢复类型证据 |
| `java.binding@3` | `parent` 与 `evidence_owner` 必须指向同 owner/source 的 CallSite；保存 resolved/complete。类型图通过该调用点读取 |
| 已确认 CallTarget | `binding_evidence` 指向其 parent CallSite，目标及绑定状态仍直接保存；不再复制整份类型图 |
| possible CallTarget | 保留自身候选恢复/反射证据；不得用已确认目标的共享引用冒充确认 |
| 辅助 SOURCE | 所有源码仍 parse/analyze，完整类型/成员头用于框架；未被发布观察引用的局部参数/变量/初始化器/回调不输出外部符号 |
| METHOD_REF | 源码范围、SAM、类型和模式独立保存；数组构造为 intrinsic，无虚构方法调用；bound receiver 调用与参考一样归属回调，另标 evaluation_phase=callback_creation |

旧 `java.binding@1/@2` 仍可读取。文件/模块签名缓存格式及扩展指纹变更，防止复用缺少回调或旧事实布局的目录。


### 框架阶段模型与编码归档传输

`projectRequiresFullModel` 默认 true，自定义扩展仍获得完整模型。已验证的内置框架阶段只需要声明/外部符号/注解类型/源码表；框架暂存省去不读取的语法、观察与诊断，原始 Java 模型不变。Spring DI 是 Java 项目阶段，空 XML 不能禁用它。`requiresCapturedResources` 默认 false，只有显式声明该约束的内置 XML provider/analyzer 才可在空资源输入时跳过。

FactHeaders 编码传输只复制已经生成的有界 CAF1 记录，公共事实布局不变。记录计数、字节上限、取消、关闭/封存、索引范围与完整 framing 检查仍执行；排序/拼接视图显式传递原始位置，不通过 hash 丢弃重复。

二进制声明完整读取 class 文件内成员，不按 2048 条截断。类缓存同时受 64 条 / 8 MiB 加权上限约束，超出单类缓存权重仍完整读取且不缓存；class 输入保持 2 MiB 上限，最多 8 个打开的 JAR。ASM 保存 `annotation_metadata` 的显式值/可见性、`annotation_default`、参数及 TYPE_USE reference/path；这些原始元数据不冒充 javac 的 effective annotation 值。dotted nested 查找须由实际 binary header 证明，精确 binary 名优先。


生成辅助 NoCode 的第二次 javac 还可提供独立生成接收者证明。接纳条件为两侧唯一同 source/start/end/lookup_name/mechanism 的调用，新调用 resolved/complete、完整 DECLARED 接收者、唯一的辅助 modeled CLASS 和选中 METHOD；原接收者须为缺失或 ERROR/UNKNOWN/NONE。`supplemental_receiver_evidence` 发布原 UTF-8 span、完整接收者、selected_declaration、signature/auxiliary_inputs SHA 与 original_evidence_retained=true。恢复仅消费该独立接收者，不据此确认上游歧义工厂。输入/生成成员/注解变更清除旧证明。继承候选遮蔽按完整 JVM descriptor（含返回类型），同参数的协变选择须明确返回转换成立；缺失 descriptor 不推断遮蔽。递归类型图的接收者及 readable 操作从真实节点引用重新遍历，仍保留查询预算与循环边界。


未解析声明注解可附加 `source_annotations`，不覆盖 javac 的 `annotations`。书写限定与已知选项只供扩展视图；`source_options_complete` 不代表 annotation declaration 已确认，保留 `declaration_resolved=false`、来源和原始证据。Lombok builtin 7 仍先验证原始 compiler annotation 的 lombok 名，缺 jar 的简单 import Getter 不直接生成成员。缺失 CLASS annotation 定义只以 `ANNOTATION_TYPE_UNAVAILABLE`、UNKNOWN retention 和 incomplete 元数据披露。反射 occurrence 的可选 `type_context` 取自同位置原始调用点非空 `control`，不改变目标置信度。以上兼容性可选属性不切换模型/协议版本，桥 SHA 和 builtin 版本纳入缓存身份。


Lombok builtin 8 的可选 `prerequisite_annotations` / `prerequisite_annotation_proof` 属于独立 Java 语义证据：要求 MAIN 依赖模块按自身捕获 classpath 分析、完整源码清单/每个 SHA 相同、配置输入和有效策略相同、完整 ABI及Lombok覆盖、无循环。消费者已完整确认的注解保持自身上下文权威性。复用不覆盖原始编译器注解或失败证据，不提升生成调用置信度；隐藏前置不发布源码。任务内证明合计32MiB，超限报 MODULE_ANNOTATION_EVIDENCE_BUDGET；不作为持久缓存实现。可选属性保持现有wire版本，builtin版本参与增量/缓存身份。


Maven输入schema3的ExportReceipt增加可选 prerequisite_classpath_rounds（旧值默认0，最大256）。同会话先返回选定模块的实际已调解输入；Rust按已确认的scope图发 AMP1/nonce/模块清单请求，桥只解析这些前置MAIN并重新封存输入回执。前置依赖新出现时继续受同一总deadline/取消/32MiB响应预算约束，不启动第二Maven进程或任何目标生命周期。未选中的 MAIN 仅在真实依赖闭包内且自有classpath已捕获时形成隐藏JavaContext；不发布其源码、框架/XML/project结果。未选中TEST和无关模块不得作为根启动，旧手工清单默认resolved=true也不能扩大范围。最终extension/input SHA进入原有配置/缓存身份。


### 通用派生缓存（Rust）

产品签名缓存使用 managed-v1 存储格式，receipt version1；不改变公开事实或 worker wire。namespace/key 和不透明 payload 的 SHA/长度均校验，单条最多192MiB/16文件，receipt最多64KiB；两个 Java namespace 合用256MiB持久配额。任务副本最多128MiB，编译器 pin 另限128MiB；显式旧树与图、模型和语言签名版本仍由 Java key 区分。拒绝 store/lock/payload 符号链接，损坏/未知条目布局不能命中；缺缓存或锁/配额异常使用 SOURCE，不修改语言证据。Rust worker 在结果已校验、发布 Ack 且 native finish 后原子提交条目；取消、失败、未 Ack 不提交。持久淘汰不影响已有任务副本。Java发布确认包含native任务释放及同步缓存原子提交，daemon等待Acked最多30秒（缓存锁可等5秒）；身份仍严格校验。确认异常披露ack_warning、已发布head不回滚、不复用该worker。mock退出确认仍为2秒。

Java 元注解闭包使用 `java.annotation.meta` 1：Java 从独立 SOURCE/binary annotation headers 生成，保存 annotation_fqn/meta_annotation_fqn/raw_name/resolution_status 和原始 source_origin（path/hash/span）。facet 的 source owner 是发现它的已发布源码，辅助源码仅作为来源证据，不冒充已发布文件。同一个文件内同一来源的元注解边唯一；不同发现文件保留独立事实，元数据图投影按参考库公开的唯一边身份（来源文件、两端、原名、状态）合并发现。循环不重复展开，16层/单根100000步/单发现文件1000000步或缺失header撤销该根全部候选，发布 ANNOTATION_META_INCOMPLETE 与partial coverage；健康文件继续。原字节定位和全部发现事实仍保留，不能据图投影合并丢弃注解使用点或生成事实的重复。

Spring 7 的 XML Bean facet 另存 `xml_configuration_model` v1：构造属性与带整数索引的配置森林。真实元素保留原始字节跨度，属性生成节点以 `synthetic=true` 指向原包含元素；SAX 位置与字节跨度分别保存。Rust 仅校验格式、唯一父节点、完整可达性和捕获源码范围，不解释 XML 或 Java 类型。

Model 7 归档的类型图在打开时逐图完整验证；任务内按原归档位置加载，存活视图固定同一不可变图，未访问的图不常驻堆。归档 SHA、字节/节点预算、取消和关闭边界不变；SDK8 与 native 使用同一格式。
