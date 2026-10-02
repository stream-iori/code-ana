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
| S1 | MAIN 调用绑定：94 个独立缺失、1 个额外目标及 30 对置信度差异 | 待完成 |
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

最新真实 revision：rev_f351d8bc30f24437bf13fee492a9d0e2。严格投影：声明 8332、调用点/归属 35813 零差异；目标缺 124、多 31（其中 30 对相同目标状态差异）。SOURCE 类型缺 2、多 3，集中在 ForwardInterfaceTypeHandler 一个回调。TEST 的调用、SOURCE 类型、反射成员和继承投影全部零差异。

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
