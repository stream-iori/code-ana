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
| S1 | MAIN/TEST 调用绑定：最新局部对照 81 个独立缺失、0 个额外目标及 6 对置信度差异；TEST 调用恢复零差异 | 待完成 |
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


## 固定校验器后的正式绑定回执

| 回执 | 结果 |
|---|---|
| type-graph-bindings-jvm-20261003-013300 | 193 项、431 次图请求、401 次 Rust 校验；546.842 秒，all_ok=true，无源码/产物漂移 |
| type-graph-bindings-native-20261003-013300 | 实际 C ABI 182 项、430 次图请求、401 次 Rust 校验；650.691 秒，all_ok=true，无源码/产物漂移 |
| type-graph-syntax-publication-product-ipc-final.log | 更新 Rust release 后的产品 IPC：共享输入、full/delta、校验和、发布校验、Acked 与热 host 复用通过 |

上述两组使用任务独占校验器副本，其哈希进入回执。11 个 JVM 私有装配测试全部保留在 JVM 193 项，native 未将其伪报为 C ABI 执行。早前产物漂移回执继续保留为失败。

本轮代码检查点为 122c351。图消费者、绑定和 Rust 发布校验的阶段证据已终态；新图仍内部 opt-in，不据此关闭默认图、原子版本切换、40 模块语义、容量或 52 项总验收。

lambda-type-mirror-evidence.log 的独立 SDK8 javac 取证：缺失 Getter 的 filter/toMap 回调参数，VariableElement.asType 与语法类型镜像均为 Api，隐式类型树没有有效结束位置。因此“声明类型被语法类型覆盖”不足以解释残余差异，未据此修改产品。继续以候选拒绝及冻结参考为依据诊断泛型恢复。


## 泛型编译器选中声明：实现修正

已从冻结 JavaModeledCallRecovery.confirmedBinary/methodArguments/Blocked 的语义适配新的泛型约束处理。CallRecovery 的推断返回 YES/NO/UNKNOWN；明确不兼容通过内部 Result.blocked 传给嵌套工厂、lambda 和成员引用，不把失败的原 javac 表达式类型当作约束证明。候选、参数/边界检查及缓存隔离保留；证据不足时仅保留编译器精确选中的可访问二进制声明，未推断返回值保持声明结构，不造出具体结果。

| 验证 | 结果 |
|---|---|
| generic-selected-blocked-collector.log | 隔离实现 14 项 Collector 正反例通过；早前三个负例失败方向未合入 |
| generic-selected-blocked-bindings.log | 隔离实现其他绑定/上下文/目标返回/SAM/原 lambda 49 项通过，131.432 秒 |
| generic-selected-blocked-frozen-22/receipt.json | 隔离实现 22 组独立冻结投影全部相等；不是 native 或真实项目证据 |
| generic-selected-blocked-lambda-frozen/receipt.json | 新 lambda-map 与冻结参考目标多重集 4/4 相等，不补第二个 Getter |
| generic-selected-product-collector.log | 正式源码合入后 15 项通过，57.145 秒，包含字段类型修改及 Getter 删除的 full/delta 全事实等价 |

隔离诊断中的 generic-selected-tristate-collector.log 曾在暂存编译失败后运行了原类，不能作为新实现通过证据；此方向已由检查 javac 成功且校验实际覆盖类哈希的新运行替代。上述隔离回执不替代正式同版本完整回归。

新 LambdaMap fixture 已加入原独立冻结对照，最低计数使用实际参考的 4 项；旧隔离脚本猜测的 6/16 不是基线。native 正在重建。S1 的真实 MAIN 差分计数仍采用上一版 92/1/30，待新版真实复验后更新，不提前宣称消除。


## 泛型选中声明修正后的正式验收（f6436ee）

| 正式回执（target/java-alignment/evidence/） | 结果 |
|---|---|
| native-fixtures-20261003-020708 | 113 项通过，389.765 秒，无源码/产物漂移 |
| functional-reference-20261003-020708-88024 | 23 组冻结调用投影全部一致，包含 lambda-map 的 4/4 |
| type-graph-bindings-native-20261003-021409 | 实际 C ABI 183 项通过，435 次图请求、406 次 Rust 输出校验；821.391 秒，无漂移 |
| core-service-main-test-generic-selected-20261003 | MAIN/TEST/XML 发布及查询通过；431683 事实，780.891 秒，配置恢复，无非预期信号 |

真实发布 revision 为 rev_0b2a7e938a31459cb16af289e8749bc2。core-service-call-diff.json 的声明 8332、调用点/归属 35813 零差异；目标 35735/35827，缺 122、多 30，其中 30 对同目标置信度差异，即 92 个独立缺失、0 个独立额外目标。上版唯一独立额外 Getter 已消除，独立缺失总数未减少；四个旧缺口消除、四个新缺口出现，不能宣称绑定完全对齐。core-service-type-diff.json：SOURCE 类型仍缺 2、多 3；反射 20、extends 528、implements 152 零差异。

最初 call-diff.json/type-diff.json 未设置 module，误把局部产品和完整参考相比，保留但不作为局部验收结论；正式局部结论使用带 `--module app/core/service` 的 core-service 两份差分。完整 40 模块、框架/类型证据、容量与 release 尚未通过；同版本完整 JVM 回归仍待终态。

binary-graph-isolated-frozen-reference.log 为独立目录原型直接调用冻结 JavaBinaryReader/JavaBinaryTypes 的五组投影相等：方法遮蔽、递归 class formal、交叉边界、通配符/数组/primitive、50 层泛型。不构成产品接入证明。generic-selected-product-entry-chain-frozen/functional-lambda-map/receipt.json 对更长链条为 7/9，仍缺内层 Collection.stream 和末尾 Optional.orElse；日志末尾读取回执的路径错误不改变已保存的失败差分。新的递归 modeled-input / blocked 传播仅在独立覆盖类目录探测，尚未合入产品，不混入上述正式回执。


## 接收者模型证据与 lambda 置信度修正

f6436ee 的正式 JVM 全量回执 generic-selected-full-jvm-20261003-020708 已终态：44 个命令、337 项 unittest，通过，2125.629 秒，无源码/产物漂移；其中图模式 JVM 绑定 type-graph-bindings-jvm-20261003-023200 为 194 项，634.023 秒，无漂移。core/service TEST 的最新独立投影继续零差异：声明 2924、调用点/归属 17612、目标 17632、SOURCE 类型 1705、反射 20、extends 226、implements 26。

新的实现按冻结 JavaModeledCallRecovery 的 modeledInputs/modeledExpression/Blocked 规则，递归读取失败接收者下面的实际模型输入；失败的本地选择不能把原 javac 表达式类型当作后继接收者。lambda 的返回兼容性与是否含模型输入分别判断；生成构造器仍保留自己的目标，但不因其返回实例类型未变而把整条外层调用链降为 possible。删除另一个重复的 modeledBody 遍历，复用 expression/block 两个证据谓词。

| 阶段证据 | 结果 |
|---|---|
| transitive-modeled-input-stage4-entry-frozen | 更长 entry chain 9/9 严格相等；旧版为 7/9；后续 lambda 修正仍维持 9/9 |
| type-graph-bindings-jvm-20261003-023543 | 接收者传播隔离覆盖类：194 项、626.228 秒，无源码/产物漂移；非正式产品产物 |
| stable-lambda-constructor-confidence-stage5-verified | 构造器/Optional/Stream fixture 10/10 严格相等；旧版存在 7 对状态差异及 1 个额外目标 |
| stable-lambda-model-stage5-frozen-23 | 原 23 组独立冻结调用投影全部一致，无覆盖类或产品类漂移 |
| stable-lambda-model-stage5-bindings-receipt.json | 隔离覆盖类 64 项 Collector/绑定/上下文/SAM/目标返回/lambda 正反例全部通过，覆盖类哈希不变 |
| stable-lambda-model-stage5-entry-delta.log / constructor-delta.log | 1+2 项类型修改、构造器签名修改、Getter/构造器注解删除的全事实 full/delta 等价通过 |
| receiver-evidence-product-collector.log | 合入正式源码后 18 项通过，53.306 秒，包含三项新增回归 |

两组新来源已纳入正式独立冻结对照，最低目标数使用真实 9/10；入口共 25 组。stable-lambda-constructor-confidence-stage5 的第一次暂存编译失败，随后的原类探测已用 rejected-evidence.json 明确拒绝；正式阶段证据为验证覆盖类哈希、编译成功后才启动的 stage5-verified。隔离阶段不替代 native、发布查询和真实项目差分；本次实现后的 native 与真实 MAIN 差分待重新验证，92/0/30 仍是上版正式计数。


## 接收者模型证据真实复验：回归未关闭

639d530 的正式 opt-in 图绑定 JVM 197 项（837.114 秒）和 native 186 项（913.430 秒）通过；默认 native 116 项（573.969 秒）及冻结功能调用 25 组通过，均无输入/产物漂移。这些局部门禁不替代真实项目语义差分。

core-service-main-test-receiver-evidence-20261003 发布/query 成功，431673 事实、863.135 秒，revision 为 rev_5ab25492812647d0b458bc798db8c00b；配置恢复、无非预期进程信号。严格 module=app/core/service、scope=MAIN,TEST 投影的声明 8332、调用点/归属 35813 零差异；目标缺 110、多 8，其中 7 对置信度差异，实际为 103 个独立缺失、1 个独立额外目标。SOURCE 类型仍缺 2、多 3；反射、extends、implements 零差异。相较 f6436ee 的 92/0/30，置信度改善但独立缺失回归；新增缺失包含两个 TEST Mockito.thenReturn，不能沿用旧版 TEST 零差异结论。

隔离诊断已复现同名但无关的生成 Getter 令候选失败反复重算，100000 工作步骤耗尽后屏蔽原 javac 已解析调用。冻结参考的 resolve 会缓存失败结果；候选按相同 provisional 上下文隔离适配，最小自包含调用投影 14/14 相等。产品未合入、正式完整门禁仍待终态。另一个隔离方向仅在 javac 已选中且候选有 UNKNOWN 时保留表达式，避免把证据不全当成明确不适用；需独立负例和真实复验，不提前认定通过。

binary-signature-graph-candidate 尚未合入。独立冻结签名六组和图身份/边界检查通过，但完整消费者到第 48 项发现恢复 CAPTURE 子节点不符合公开图节点形状（TYPE_GRAPH_KIND）。首次读旧 binary type_parameters 位置的 fixture 错误已改为读取真实 raw member formal，但保留 owner 断言；两份失败回执保留，均不能计作产品图通过。


## 失败结果缓存与 UNKNOWN 接收者：正式复验（77d3a1e）

| 正式证据目录 | 结果 |
|---|---|
| native-fixtures-20261003-034253 | 118 项，572.233 秒，all_ok=true，无输入/产物漂移 |
| type-graph-bindings-native-20261003-034253 | 实际 C ABI 188 项，928.033 秒，all_ok=true，无输入/产物漂移 |
| functional-reference-20261003-034253-59048 | 27 组冻结调用投影相等，无产物漂移；新同名 Getter 链 14/14、cast 引用链 12/12 |
| core-service-main-test-recovery-cache-20261003 | 发布/query 成功，823.151 秒；配置恢复，无非预期进程信号 |

真实 revision 为 rev_8d77d53e623e4a69bf27c5e0180925d8。core-service-call-diff.json：声明 8332、调用点/归属 35813 零差异；目标 35746/35827，缺 87、多 6，即 81 个独立缺失、0 个独立额外及 6 对置信度差异。对比 639d530 的 103/1/7，新回归已修复并消除更多旧缺口。core-service-test-call-diff.json 的声明 2924、调用点/归属 17612、目标 17632 再次全部零差异。SOURCE 类型仍缺 2、多 3；反射 20、extends 528、implements 152 零差异。不得将局部 core/service 发布提升为全 40 模块验收。

隔离二进制图修正的 type-graph-bindings-jvm-20261003-034701 为 199 项、810.6 秒，all_ok=true；binary-signature-graph-candidate-graph-functional-27 为显式 opt-in 图模式 27 组独立冻结投影相等。恢复 CAPTURE 导出为带原始捕获身份的 TYPEVAR，未知上界保持不完整；候选中未推断的方法变量上界不能当作具体接收者的否定证据。更早的 48/62 项失败回执保留。另一个未开启 graph 的 27 组只计默认兼容性，不冒充图验证。上述图候选仍未合入、未默认切换。

MAIN 回调补充证据的独立取证：相同全部 MAIN 源码范围下，补充编译证明替换基线造成 2 缺/3 多，与真实 ForwardInterfaceTypeHandler 形态一致。隔离分离后 MAIN 基线 3/3，相应 TEST 四组仍严格相等；补充完整证明保留在独立关系。首次错误逗号 source-root 枚举 0 文件的失败回执保留，不作为产品缺陷或能力证明。


## MAIN 补充类型证明分离：产品 JVM 验证

`supplemental-separated-product-regressions.log`：10 项通过，25.354 秒；新增 MAIN 原始/恢复关系保留、独立完整证明、字段类型修改及 Getter 删除 full/delta 等价。`supplemental-reference-20261003-041123-69026/receipt.json`：五组冻结对照全部通过，无输入/产物漂移，MAIN Getter 3/3、TEST Map 链 13/13、Getter 4/4、Builder 3/3、SuperBuilder 3/3。fixture 的使用方 scope/world 已改为真实 TEST，与冻结参考源根一致。

产品 MAIN 的新证明保存为 `supplemental_type_reference`；基准 `type_reference` 不被替换，证明中的原 javac 证据、诊断、签名 hash/归属保留。此为 JVM 阶段终态，当前安装 native 尚未包含该修正；真实 SOURCE 类型差分仍使用上一版缺 2/多 3，待重建复验。


## 全类型根转换与深层调用：隔离阶段取证

旧二进制图基础的全类型根候选正式 JVM 回执 `type-graph-consumers-jvm-20261003-041123`（102 项、8 项失败）与 `type-graph-bindings-jvm-20261003-041123`（201 项、17 个失败事件）保留为失败：简写 DECLARED 缺少 arguments、WILDCARD 缺少 absent bound 字段。转换补齐旧模型原有空列表/空边语义，不补 Object、不降低校验；`all-roots-graph-v2-module-focus.log` 的 26 项原断言通过。新版完整门禁另行执行，候选尚未合入。

冻结 Anatomist 对 80 层 Getter 调用链产生两个 possible 目标，旧图候选为 0/2，失败回执保留。独立栈确认无变化的类型替换和 mentionsFormal 树递归耗尽深度预算。隔离修正对图节点迭代遍历，原调用递归/总工作预算保留；无待替换变量时复用原不可变根，类型身份从平面闭包取证，避免为 visited key 展开整棵树。`graph-operations-deep-regression-corrected-2.log`：21 项通过（20 原 Collector 回归加 1 项 graph 深层变更/删除 full-delta 证明），120.589 秒。最初诊断脚本继承测试类并遮蔽 targets 导致工具错误，相关失败日志保留；不将其记为产品失败或通过。`graph-operations-functional-27/receipt.json`：27 组显式图冻结调用投影相等，无产物漂移。完整同版本图消费者/native 尚待复验。

`type-graph-consumers-jvm-20261003-042022/receipt.json`：全类型根转换 v2 的 102 项 JVM 消费者通过，453.844 秒，275 次图请求、258 次 Rust 校验，无输入/产物漂移。该候选包含简写模型规范化，但尚未包含随后隔离的深层调用运算修正；后者完整绑定与消费者另跑，不能混成同版本通过。


## 二进制与全部类型根：通过候选合入

`type-graph-bindings-jvm-20261003-042058/receipt.json`：201 项、463 次图请求、433 次 Rust 校验，815.809 秒，all_ok=true。`type-graph-consumers-jvm-20261003-042852/receipt.json`：102 项、275 次图请求、258 次 Rust 校验，405.225 秒，all_ok=true。两份均无输入/产物漂移；与 graph-operations-functional-27 同一隔离候选，现已合入正式源码，并补永久原始签名 oracle、formal/非法语法、根规范化及深层调用 full-delta 回归。产品重编译/native 尚待重新取证。默认图及版本尚未切换。

真实图探针的 compiler 512 MiB 堆耗尽（Java OutOfMemoryError，退出 1），不是外部杀进程；提高为 1 GiB 的隔离请求被 INVALID_JAVA_COMPILER_HEAP 拒绝，未修改产品上限。逐源释放 CompilerTypeGraph 的编译器对象索引，以及真正深层 formal 替换仍仅隔离验证，不混入上述通过候选；不据此宣称 iopengw 图模式通过。

正式源码 test-compile 后，`all-type-roots-product-focused.log` 的二进制签名 2、深层图调用 1、默认 Collector 20、MAIN/TEST 补充证明 10 项全部通过；SDK8 的 `all-type-roots-sdk8-model-regressions.log` 九项通过。原始冻结签名 Java oracle 位于独立 frozen-test 源集，仅专项编译，不进入产品依赖。

隔离 `graph-transfer-lifecycle-candidate` 的原 512 MiB 编译器 MAIN/TEST 已终态退出 0；独立冻结数据库直接读取的九个已知差异源码调用多重集为 1411/1440，缺 35、多 6（匿名别名未统一），与旧 JVM 探针的原始目标完全相等。原先从旧差分反推参考的脚本计数错误已标为 rejected，正式诊断用 independent-call-diff.json，不使用反推结果。此不是全 module/native/容量验收。归档释放与逐源图回收未合入；首次完整候选门禁与产品 test-compile 重叠，已受控停止并保留 rejected-overlap 回执，编译完成后重跑。


## 545f28a 正式 native 回执与真实项目复验

| 同版本证据 | 结果 |
|---|---|
| native-fixtures-20261003-045126 | 默认 native 121 项通过，538.513 秒 |
| type-graph-bindings-native-20261003-045128 | opt-in 图绑定 191 项通过，467 次图请求，773.642 秒 |
| type-graph-consumers-native-20261003-045131 | opt-in 图消费者 84 项通过，215 次图请求，440.194 秒 |
| supplemental-reference-20261003-045326-40835 | MAIN/TEST 五组独立 native 冻结投影相等 |
| core-service-main-test-all-type-roots-20261003 | 发布/查询通过，722.852 秒；rev_5bef6ed65ad748c0bb7c63ffd1a131f5 |

上述正式回执无输入/产物漂移，真实项目配置已复原，无非预期进程信号。真实调用声明 8332、调用点/归属 35813 零差异；目标缺 87、多 6，仍为 81 个独立缺失及 6 对置信度差异。反射 20、extends 528、implements 152 零差异。**SOURCE 类型缺 179、多 76，未通过**：MAIN 一律隔离补充证明的策略扩大了真实模块差分；五组简化 fixture 未覆盖 Maven 上游有效生成签名边界，不能以局部通过声称该缺口已关闭。接下来按独立上游模块的有效声明/完整 Lombok 覆盖判定修正，不降低差分要求。全 40 模块/52 项/容量仍待验收。

隔离归档释放候选的 type-graph-bindings-jvm-20261003-045133 为 202 项通过、714.126 秒；type-graph-consumers-jvm-20261003-050645 为 102 项通过、212.679 秒，均无输入/产物漂移。graph-transfer-lifecycle-sdk8-model.log 九项 SDK8 模型/损坏/取消回归通过，3.442 秒。候选尚未合入，不能与正式 native 回执合并计为同一产品版本。


## 图归档生命周期：已通过的候选转入产品源码

编译器在逐源码声明/观察扫描后释放 TypeMirror/Element 身份索引；最终归档仅编码平面图节点，不重复保留节点元数据。编译器独占的 RowSpool 在每节转移后释放，失败路径仍由编译器 finally 关闭剩余 spool；普通 ModelArchive.write 不消耗调用方输入。图 ID 不进入公共事实身份，协议版本未改变。

同一候选的 graph-lifecycle-concrete-functional-27 为 27 组显式图冻结调用投影相等、无产物漂移；type-graph-bindings-jvm-20261003-051542 为 202 项通过、460.556 秒、无输入/产物漂移，其中实际深层 formal 替换仍为隔离 TypeOperations 候选。归档生命周期另有前述 202/102 项及 SDK8 九项。产品 graph-transfer-lifecycle-product-sdk8.log 十项通过（9.529 秒），新增 1001 节点/两节共享图转移与普通写入等价、输入所有权、预算/取消失败后的 compiler 清理。测试最初用重复 canonical 闭包及保留巨大图构造负例导致两次 64MiB 探针 OOM，保留 rejected 日志；修正只针对测试遍历和负例构造，不提高产品堆或预算。无外部杀进程。正式 native/全项目验收仍须新产物重跑。


归档生命周期追加产品 bridge 重建复验：编译器深图/错误邻居通过，但两档案同时读回的 10001 节点所有权探针在 64MiB 再次 OOM，graph-transfer-lifecycle-product-bridge-tests.log 保留失败，不记通过。所有权探针缩为 1001 节点并逐节点比较；原 ModelGraphProbe 的 10001 深度/共享/损坏/取消断言保持不变。新回执独立记录，不提高产品堆或更改输入所有权语义。


graph-transfer-lifecycle-product-final-sdk8.log：重建后的产品 bridge 与十项 SDK8 模型/转移回归全部通过，4.997 秒；10001 节点旧深图测试保持原断言。正式 native 尚待更新。模块签名隔离探针新增六项原始 ERROR 保留、同 world SOURCE 边界、部分 Lombok、错误参数、坏邻居隔离及 full/cache/delta/移除等价，module-abi-boundary-negative-cache.log 全部通过（12.382 秒）；前一版列表顺序断言失败已保留，最终按完整 Fact ID 映射/排序比较，不删字段。maven-generated-builder-variable-proof 的独立真实两模块 Maven 项目为 4/4 调用相等；更早不读局部证明的版本仅 1/4，失败回执保留。MAIN 十个选定真实文件的 418 条 lambda 类型投影相等；仍非 native/全项目证明。新的 MAIN/TEST 图调用探针在原 512MiB 编译器 annotation_types 阶段 GC overhead limit exceeded，退出 1；保留 calls-stderr.log，未提高上限，未声称全图通过。


## Lombok 注解复制覆盖：产品修正

不再按 NonNull/NotNull 等名称后缀推断未完成能力；使用冻结实际复制名单，Deprecated 单独遵循原政策。独立 lombok-copy-policy-frozen/receipt.json：七个调用严格相等，Validation 的完整覆盖及 CopyCandidate 的 annotation_propagation 部分覆盖逐字段相等，无产物漂移。产品 test-compile 成功；lombok-copy-policy-product-regressions.log 的新正/负例、Builder、SuperBuilder 共 22 项通过（31.366 秒）。实际复制候选继续明确 partial，未降低原门禁。native 重建复验待完成。


## 上游有效 ABI 与局部变量：产品 JVM 验收

Java 以真实 world 和完整声明覆盖选择辅助 NoCode 输入，不再按 MAIN/TEST 一刀切换回调关系。原 javac 属性/诊断保留；已验证的生成嵌套类局部变量另存 supplemental_variable，恢复目标仍为 possible。一个模块的解析/编码失败、部分 Lombok 或不可投影 ABI 保留 SOURCE，不丢弃其他健康模块的证明。模块缓存仍保存原始目录，full/cache/delta 全字段等价；通用 Rust 缓存迁移未完成。

module-abi-boundary-product-regressions.log：34 项产品 JVM 回归通过（85.260 秒）；额外解析失败模块负例 1 项通过（1.465 秒）。supplemental-reference-20261003-053848-18473：五组冻结 MAIN/TEST 投影相等；MAIN fixture 修正为真正单 world，并捕获与 oracle 相同的 Lombok 配置。module-abi-reference-20261003-054001：实际 Maven 两模块的完整 Builder 4/4、部分 Delegate 1/1 对照通过，两份均无输入/产物漂移。永久模块边界七项已接入 JVM、opt-in 图消费者和 native fixture 门禁；Maven oracle 支持 native 独立重跑。

该阶段尚未更新 native；真实 core/service SOURCE 差分仍使用 545f28a 的缺 179/多 76，调用仍为 81 个独立缺失及 6 对状态差异。418/418 的隔离 MAIN 回调不能替代完整新版本真实对照。


## 模块边界 native 复验（2026-10-03）

产品 Java 修正为 545213b；验收入口/预算反例提交为 5050d36。新 native 已构建，并将 compiler-bridge、Maven 元数据桥、共享库、manifest 成套安装；旧目录保留。安装回执：module-abi-boundary-installed-bundle.json。

| 同版本正式回执 | 结果 |
|---|---|
| native-fixtures-20261003-054614 | 128 项通过，470.82 秒；源码/产物漂移为空 |
| type-graph-consumers-native-20261003-054614 | 91 项通过，377.629 秒；源码/产物漂移为空 |
| module-abi-reference-20261003-054614 | 原版真实两模块 Maven 对照：完整 Builder 4/4、部分 Delegate 1/1 相等 |
| supplemental-reference-20261003-054614-43395 | 五组 native 独立原版调用投影相等，all_ok=true |
| type-graph-bindings-native-20261003-054614 | 仍在运行，尚无终态 |
| core-service-main-test-module-abi-verified-inputs-20261003 | 重新导出 40 模块输入清单后，MAIN/TEST/XML 运行中；尚不能更新正式差分计数 |

第一次真实项目运行明确 JAVA_METADATA_STALE：安装后的 Maven 桥 SHA 与旧清单不同，worker 未启动。第二次运行发现导出器 output 相对于执行目录，清单未在项目目录；保留失败回执，将外部导出的清单复制至项目并核对选定模块/桥 SHA 后重新运行。两次均无外部杀进程，未把失败改为成功。

隔离候选：deep-concrete-current-boundary-values.log 三项实际泛型叶子、候选隔离及 full/cache/delta/删除通过；deep-concrete-reference-20261003-055019 的三个独立原版回执为 2/2、0/0、4/4 相等，聚合包装器最初 KeyError 的日志保留，由 verified-case-receipts.json 汇总已存在的回执，不算正式产品验收。深层 NoCode 专项揭示 BINARY_SIGNATURE_BUDGET（64 层）及 graph 源码注解层数重复累计的 SOURCE_TYPE_DEPTH_BUDGET，隔离修正中，仍未合入。完整语义、默认图、全项目、容量和发行门禁未关闭。


## 模块边界完整局部差分终态

core-service-main-test-module-abi-verified-inputs-20261003：652.956 秒完成发布/query；revision rev_0b78e359b5634b1fb48ff96a34e81533；配置恢复，源码/产物/输入清单无漂移，无异常进程信号。严格回执 core-service-call-diff.json 与 core-service-type-diff.json：

| 投影 | 结果 |
|---|---|
| 声明、调用点、归属 | 8332 / 35813 / 35813，全部零差异 |
| SOURCE 类型引用 | 12188 / 12188，缺 0、多 0；包含 MAIN/TEST，不再仅选定 lambda 样本 |
| 反射成员、extends、implements | 20 / 528 / 152，全部零差异 |
| 调用目标 | 产品 35748、参考 35827；缺 85、多 6，即 79 个独立缺失、0 个独立额外、6 对置信度差异 |
| 高级绑定 native 回归 | type-graph-bindings-native-20261003-054614：192 项通过，681.772 秒，无源码/产物漂移 |

上版类型回退 179 缺/76 多已在本次正式 native/daemon 局部范围消除；调用独立缺失从 81 降为 79。尚未关闭剩余调用、全 40 模块、完整扩展语义、默认类型图、Rust 通用缓存迁移、容量或发行门禁。

深层二进制额外核验：冻结 Anatomist 自身在 80 层 JavaBinaryTypes 抛出 binary type depth exceeds budget（deep-graph-binary-frozen-80.log）；因此未合入将二进制预算扩到 128 的隔离试验，不把超出原版上限记为对齐修正。仅在原版范围内继续验证 50 层 NoCode 与 TYPE_USE；deep-graph-nocode-frozen-boundary-50.log 九项通过，仍是隔离候选，待正式合入及 native 复验。


## 深层泛型代入与 NoCode 产品修正

已合入实际替换值不含 TYPEVAR/CAPTURE 的图代入；原图/未改变子树/声明泛型身份保留，其他替换继续保守递归。50 层 NoCode 头与 TYPE_USE 路径用图遍历，源码注解包装不重复计算类型层数。新图 NoCode 写入前复用 BinarySignatureGraph 现有严格语法及原版 64 层/4096 节点上限，80 层只保留 SOURCE；未扩大二进制预算。

| 当前正式源码 JVM 证据 | 结果 |
|---|---|
| deep-graph-product-signature-regressions.log | 10 项通过，36.305 秒；包含 50 层类型/注解精确 full/cache/delta，80 层拒绝缓存仍继续解析 |
| deep-graph-product-call-regressions.log | 4 项通过，25.849 秒；80 层实际 String/Number 叶子、候选隔离、原 T 保留、full/cache/delta/删除 |
| deep-graph-product-binary-invariants.log | 原版六形状、形式变量 owner/递归及三个非法签名负例通过；2 项，1.641 秒 |
| deep-graph-product-sdk8-model.log | 原版 SDK8/10001 节点/预算/归档/移交的十项保持通过，7.512 秒 |

功能独立参考新增三项深层泛型正/负例，默认/native 两个入口均使用显式图上下文。正式产品 native 仍需重建验收。CallRecovery 补充回调输入的隔离候选虽五组原版对照通过，但选定真实文件只达到与已合入模块边界相同的 1413 目标（原始匿名未归一投影缺 33、多 6），没有证明额外进展，不合入。第一次隔离调用投影探针把全部声明属性放入 Map，1GiB JVM OOM；改为仅保存签名字符串后同限额成功，失败日志保留，没有外部杀进程。

发现条件表达式参数原 javac kind=NONE 即使子表达式已有完整 String/boolean，恢复选择仍保留 NONE；隔离候选正在检查原版严格目标和负例，尚未合入。
