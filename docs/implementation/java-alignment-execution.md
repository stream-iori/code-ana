# Java 分析完整对齐实施记录

目标：Java 分析对齐冻结 Anatomist；通用配置、捕获、缓存管理、进程调度、发布、存储和查询留在 Rust。保留读取官方有效模型的最小 Java Maven 桥。基准事实及置信度严格对齐，新增 javac 证明独立保存，不覆盖原始证据。

| 输入 | 固定基线 |
|---|---|
| Anatomist | dev/replace-parser@dff2e3954096853352ec91fcbfc6b995fbd4f390 |
| iopengw | 267c7ced4993aa88613f8f17a8099943fec98159，仅已提交内容 |
| 工具链 | SDKMAN Java 8；原生构建 GraalVM Java 25 |
| 范围 | macOS、Java 8、Maven MAIN/TEST/XML；不包含 Gradle/Linux/目标 JDK17/25 |
| 非法源码 | 保留原字节，明确解析失败，继续健康文件；strict 保留旧 head |

## 执行顺序

| 阶段 | 工作 | 状态 |
|---|---|---|
| S0 | 保存已有成果、刷新证据和逐能力任务 | 检查点 fc071ba 已提交；逐能力证据继续更新 |
| S1 | MAIN 调用绑定：92 个独立缺失、1 个额外目标及 30 对置信度差异 | 待完成 |
| S2 | 平面类型图接入编译器、模型、运算、事实和查询；关闭类型证据差异 | 待完成 |
| S3 | Java/扩展/Lombok/Spring/SOFA 完整独立对照 | 待完成 |
| S4 | Maven 完整扫描、复杂 NoCode、Rust 通用缓存管理和增量等价 | 待完成 |
| S5 | 冻结 40 模块 MAIN/TEST/XML 完整正确性验收 | 待完成 |
| S6 | 正确性通过后取证 OOM、独占三冷三热、2GiB 进程树容量 | 待完成 |
| S7 | 同版本最终门禁、52 项证据关闭与发行包 | 待完成 |

每阶段通过相应验证后提交。源码借用记录 commit/hash/许可证/依赖及适配说明。不设置整体任务时间预算；保护超时不作为性能结论。非预期进程被杀立即报告并保留现场。

## 当前已完成的正式回执

最新 complete-callback 产物的回执已终态，不能继续描述为正在运行：

| 证据目录（target/java-alignment/evidence/） | 结果 |
|---|---|
| non-generic-context-full-jvm-20261002-213454 | 40 组、319 项通过，950.18 秒，无产物漂移 |
| native-fixtures-20261002-213454 | 112 项通过，572.291 秒，无产物漂移 |
| functional-reference-20261002-213454-9637 | 22 项冻结调用对照通过 |
| supplemental-reference-20261002-213717-37224 | 4 项生成签名 native 对照通过 |
| complete-callback-native-cabi.log | SDK8、缓存、取消/回收、处理器陷阱、主动崩溃隔离、包校验通过 |
| core-service-main-test-complete-callback-1g-worker2g-20261002 | MAIN/TEST/XML 发布查询通过，431682 事实；全语义/全项目/容量尚未通过 |

最新真实 revision：rev_d6e9b601132445fd82310be63070079c（core-service-main-test-selected-binary-fresh-inputs-20261002，493.894 秒，发布及查询通过）。严格投影：声明 8332、调用点/归属 35813 零差异；目标缺 122、多 31（其中 30 对相同目标状态差异）。SOURCE 类型缺 2、多 3，集中在 ForwardInterfaceTypeHandler 一个回调。TEST 的调用、SOURCE 类型、反射成员和继承投影全部零差异。

原版 SOURCE 类型缺 11 中，9 项已由本轮回调重验消除；历史文档的“8 项”计数有误。仍然保留原始回执，不将局部对照或发布成功当作 52 项完整通过。

最终 java-real-semantic-diff、java-capacity、java-release 必须接入统一执行器并实际运行；不能只在矩阵中列出名称或导入历史成功回执。

## 本轮实现与阶段验证

- 二进制回退只保留 javac 精确选中的可访问成员；已知参数不兼容仍拒绝。非泛型方法支持 UNKNOWN 的局部证据和 varargs 元素校验；方法泛型维持原有约束边界。直接放宽泛型的试验未通过负例，未保留。
- selected-binary-nongeneric-collector.log：14 项通过；selected-binary-binding.log：16 项通过；selected-binary-nongeneric-frozen-reference/receipt.json：22 个专项严格一致。尚未据此宣称 MAIN 残余差异已关闭。
- TypeGraph / TypeGraphArchive / CompilerTypeGraph 已从 ignored 原型转入正式源码。编译器以内部 fixture 开关 `type_graph=true` 接入 RowSpool、ModelArchive 和公共事实；默认产品链路暂未切换，协议版本不变。模型以共享节点表保存，公共事实按单事实联合根闭包编码，不发布编译器局部 ID。
- type-graph-compiler-model.log：8 项 SDK8 验证通过；包含 64MiB 下 10001 节点共享归档、真实编译器 80 层泛型与递归边、错误邻居、懒加载/立即加载拒绝非法根、悬空边、顺序、计数及小数引用。原版 WorkerTypes 在相同 javac 上下文独立验证。
- type-graph-fact.log：3 项实际 SDK8→模型→提取器证明通过；80 层泛型公共 JSON 容器深度小于 32，递归边/绑定证据 owner 不变，冷增量和删除与全量事实完全相等。此为 JVM 阶段证明，不替代 native/daemon/iopengw 验收。
- 现有回归：type-graph-integration-model.log 29 项、annotation.log 9 项、signature.log 7 项通过；archive.log 的大模型、反射、扩展、索引查询与取消证明通过。公共图的 Rust 校验/查询、全消费者验证、缓存失效和原子版本切换仍待完成，S2 尚未关闭。
- Rust 已接入平面图的单事实/整批发布校验及借用节点读取；type-graph-rust-model.log 包含图/绑定新版本及损坏反例。真实 SDK8 输出由 Rust 产品校验器消费，type-graph-fact-rust.log 七项通过，包括递归 TYPE_USE 注解值、文件目录图/树隔离、泛型/注解/嵌套模块 NoCode 严格相等及冷增量/删除。
- NoCode 新图失败曾由缓存把视图展开、公共身份依赖 javac 对象共享及声明无变化仍复制类型导致。已修复，未删减比较字段；公开闭包按完整元数据及角色边进行结构划分，捕获身份和不完整原因保持不同，原始模型图不变。type-graph-compiler-model.log 的八项仍通过，含 10001 深度图、独立上下文共享差异及捕获负例。
- Rust workspace 回归与全部 targets 的 clippy 已通过；新增校验反例继续按实际日志记录。完整消费者、原子版本切换、默认产品图、native/daemon/真实语义验收尚未完成。
- type-graph-native-20261002-231041/receipt.json：新版 GraalVM 共享库经 release Rust host/C ABI 的七项图证明全部通过，16.444 秒，无源码或产物漂移；实际输出仍经 Rust 产品发布校验器。此为 opt-in 图的真实 native 验证；未开启默认图，未把全语义 gate 置为通过。新版默认树模式的模型 29、文件签名 7、模块签名 9 项回归通过。

## 消费者接入修复（本轮）

| 事项 | 证据/状态 |
|---|---|
| 默认 native 基线 | native-fixtures-20261002-231556：112 项通过，266.948 秒，无源码/产物漂移 |
| 最新真实项目局部验收 | core-service-main-test-selected-binary-fresh-inputs-20261002：MAIN/TEST/XML 发布查询通过；复原配置，无非预期信号 |
| 调用目标差分 | 缺 122、多 31；92 个独立缺失、1 个独立额外及 30 对状态差异。新增保留 Stream.filter/collect 两个原版目标 |
| TEST 调用投影 | 声明 2924、调用点 17612、目标 17632 零差异；不替代全语义对照 |
| 新图扩展读取 | ExtensionContext 按事实惰性解码公开引用，再返回不可变共享类型视图；FactSink 在验证完成后重新编码扩展输出 |
| 新图恢复目标 | FactExtractor 只编码归档副本，避免改变后续恢复目标读取的调用点证据、导致重复编码 |
| 新门禁 | java_graph_consumer_alignment.py：直接运行框架/XML/反射/模块缓存/扩展的现有断言和 full/delta 比较，不转换结果、不删减比较字段；正式 JVM 回执已通过；native 消费者回执待完成 |
| 已完成专项 | graph-consumer-fact-rust.log：7 项通过；graph-consumer-default-framework.log：12 项通过 |
| 未合入试验 | 无条件移除 lambda javac 参数快捷路径导致嵌套回调恢复预算失败；按 recovered_callback 存在性一律降级会破坏 46 个同类回调中的已对齐事实。两种试验均已撤销 |

平面类型图仍仅内部 opt-in。默认开启、原子协议版本切换、全部 Java 消费者、全 40 模块语义差分与最终发行门禁尚未完成。初始消费者探测曾与 test-compile 重叠，相关 ClassNotFoundException 不作为产品缺陷或正式证据；正式回执必须在编译完毕、输入及产物稳定后执行。

| 新正式回执 | 结果 |
|---|---|
| type-graph-consumers-jvm-20261003-000855 | 102 项全部通过，284.667 秒，无输入/产物漂移 |
| type-graph-native-20261003-001015 | SDK8→native→Rust 发布校验九项全部通过，36.396 秒，无输入/产物漂移 |
| graph-consumer-defaults-final.log | 默认 Builder/SuperBuilder/框架/扩展 51 项通过，129.781 秒 |
| graph-consumer-sdk8-model-final.log | SDK8 图模型/归档/独立原版探测八项通过 |

修复完整目录与基线表示不同导致的保守全量回退；公开注入类型断言通过类型根读取器访问新图，不修改事实或基线。SuperBuilder 重绑定以数值边保留递归和 NULL 下界，已封存图直接采用传播后的 complete 证明；80 层 Getter 能输出完整类型。

## native 消费者新增取证

| 失败 | 根因与处理 |
|---|---|
| 压缩请求的两个验收用例 | native fixture 误发请求版本 2；已按现有 ABI 改为压缩请求 3，保留版本校验及非法发布反例 |
| 辅助源码载荷探针 | 原 JVM 私有 DependencyViewProbe 返回归档统计，C ABI 默认返回事实；已通过现有 facts=false native 模型入口测实际 syntax/observations 及 UTF-8 JSON 字节，并明确 byte_representation，不替换或跳过断言 |
| 错误 XML 牵连健康资源 | SDKMAN Java 25 java.xml 模块的 Xerces XMLMessages/SAXMessages 未进入 native bundle，抛 MissingResourceException 撤销整个项目 callback；新增诊断资源配置，待重建及严格坏/好资源共存验收 |

graph-native-model-input-diagnostic.log 的三项 native 定向测试已通过；错误 XML 仍待重建后的证据，不把前一个 84 项失败回执计为通过。

## 本轮终态（2026-10-03）

| 正式回执 | 结果 |
|---|---|
| type-graph-consumers-jvm-20261003-002052 | 102 项通过；277.732 秒，无输入/产物漂移 |
| type-graph-consumers-native-20261003-002052 | 84 项通过；297.2 秒，无输入/产物漂移 |
| type-graph-native-20261003-002149 | 9 项通过；29.311 秒，无输入/产物漂移 |
| native-fixtures-20261003-002120 | 112 项通过；313.451 秒，无输入/产物漂移 |

graph-native-xml-resource-fixed.log：默认树/平面图两种模式均验证错误 XML 独立失败、健康 XML 继续产出；压缩协议及发布反例、native 模型载荷的三个定向验收通过。Xerces XMLMessages/SAXMessages 已进入新共享库；target/lib/java 成套替换为本轮通过产物，旧目录保留用于回滚。上一轮失败回执仍保留，不篡改为成功。上述回归均已终态，不再描述为运行中。

本轮提交首先为 5b75160，随后单独提交 native XML 资源与测试入口修复。S2 尚未关闭：默认图与原子版本切换、所有绑定/类型消费者、完整真实语义仍待；S1 的 92 个独立缺失、1 个独立额外和 30 对状态差异仍未关闭。全 40 模块、全部扩展、Rust 通用缓存迁移、容量与 release 门禁未通过。

产品 IPC 补充：graph-consumer-product-ipc-final.log 验证 release Rust host 使用本轮成套 native 产物，protocol10、共享输入、full/delta、校验和、事实验证、Acked 和热 host 复用全部通过。未据此关闭 40 模块语义或容量验收。

## 高级绑定图模式验收入口复核

新增平面图绑定/Lombok/缓存消费者组。类型断言使用惰性节点读取器读取原始公共图，原始事实及 full/delta 多重集不改写，原断言的目标、状态、来源、类型值及负例保持不变。

发现 java_functional_reference_alignment 在安装图入口前导入并固化部分 fixture 方法别名。旧消费者回执仍保留，但不独立用于证明全部请求均为图模式。新入口先安装 context 与 analyze 两个 hook，再加载 fixture；每次真实分析都拒绝未启用 type_graph 的上下文，回执新增 graph_requests。

初始三次绑定探测不作为正式通过：前两次分别为遗漏构造器嵌套读取及 helper import 的测试入口错误；第三次主动 SIGINT 停止混合入口探测（退出 130），不是安全软件杀进程。审计后的 JVM/native 全组正在执行，终态以新的 receipt.json 为准。

补充探测：审计后的 JVM 首次完成 111 项时，仅捕获类型检查失败（旧测试把 upper 当嵌套字典）。新增惰性节点遍历，按节点身份防止递归重入；Number/String 两个捕获身份、常量字符串、源码顺序稳定与 full/delta 的专项已全部通过（type-graph-capture-audited-focus.log）。并行 native 探测主动停止以便冻结修正后的输入，非安全软件拦截。两侧完整绑定组与两侧消费者组正在按相同源码冻结运行；在取得终态前不标记通过。

Rust 全事实验收补充：产品 IPC 使用新 SDK8 编译器桥已通过（type-graph-wildcard-product-ipc.log），桥哈希参与 Rust semantic_identity，旧缓存不复用。绑定 JVM 193 项首次只有通配符注解边失败；native 混合探测 193 项有该失败及私有 JVM SourceTypeBudgetProbe 入口失败。新 native 组明确列出 11 个私有 JVM 测试，保留在 java-fixtures 的完整 JVM 193 项；实际 C ABI 组应为 182 项，未删除测试断言。

每个真实 Fact IR 输出现交给 Rust 单事实与合并发布校验器；初次全消费者严格验收发现资源验收清单遗漏可默认 module/scope，以及 AnalysisResult 与 FactValidator 重复维护 facet 版本名单导致合法 java.syntax@2 被前者拒绝。资源清单按真实默认值补齐；合并发布的 facet 使用 FactValidator 相同形状、图和分区规则，仍拒绝未知版本、坏哈希、偏移、尾标志和空分区。Rust model 十项通过，发布正反例已补入原分区测试。消费者失败回执保留；正在冻结新版完整回归，不将探测成功等同全部通过。

严格回执终态（修复后的源代码）：type-graph-consumers-jvm-20261003-011309 为 102 项、275 次图请求、258 次 Rust 校验，424.635 秒，无输入/产物漂移；type-graph-consumers-native-20261003-011557 为 84 项、209 次 Rust 校验，424.878 秒，无漂移。

绑定 JVM 193 项和实际 C ABI 182 项全部断言通过，但并行 cargo test --workspace 重建了共享 target/debug/examples 校验器，两份回执因此 all_ok=false，不能计作正式通过。增加任务独占校验器副本并记录其哈希，避免中途执行不同产物；需在 release 构建结束后重跑受影响门禁。Rust workspace、clippy --all-targets -D warnings、fmt --check 通过。Object 参数一律重入 SAM 的隔离试验丢失已恢复的 collect/toMap，未合入；lambda-object-parameter-chain-probe.log 为失败方向的证据，不是产品能力通过。
