# Java 参考实现审计

当前冻结 Anatomist `dev/replace-parser@dff2e3954096853352ec91fcbfc6b995fbd4f390`；旧回执保留各自提交。实际复制和改编文件的提交、SHA 与修改记录见 [复用来源清单](java-reference-sources.json)。

| 参考 | 决定 |
|---|---|
| core/java/JavaBinding、JavaAnalysisContext | 采用脱离编译器模型、范围分离和三维绑定证据；公共身份另定 |
| src/worker/java/JavacWorker | 参考 parse/analyze、空 SOURCE_PATH、诊断上限；本项目桥独立实现，移除处理器/generate 分支 |
| JavacWorkerSession/BatchProtocol | 采用有界结果文件、身份/摘要校验；不复制 wire 版本、进程管理和缓存 |
| JavaModelExtractor、Java*Facts | 映射独立模型与 Fact IR，任务归档分段读写；不引入 ExtractionResult/数据库依赖 |
| JavaFrameworkContext/LombokJavaModelExtension | 不引入旧 ProjectConfig；仅允许 model 声明层，不复制 compile 行为 |
| JavacMavenInputs/MavenInputsWorker | 产品仅消费 metadata；Maven 启动仅可出现在外部显式导出工具 |
| StagedGraphStore/incremental | 语义失效留在 Java；发布与通用校验复用 Rust |
| CLI/application/query/JDBC | 不引入 |

| 已复用部分 | 适配 / 依赖边界 |
|---|---|
| WorkerSourceAnnotations / WorkerTypes | Java 8 SOURCE TYPE_USE 恢复与 written annotation 检查；仅依赖 JDK 编译器公共 API 和独立模型 |
| LombokAnnotationCopies / copy candidates | 静态注解复制策略，适配 detached map；候选资源按原内容复制，不运行 processor |
| JavaCallFacts / JavaIdentities | 枚举合成调用过滤、this/super 位置、方法引用 callback 和字段初始化 owner；保持本项目身份编码 |
| JavaModelAnnotations | 不可变 selector 的有界路径缓存、计数器与来源单元预算；实例有效属性逐次重算，预算截断不缓存 |
| JavaBinaryReader / JavaBinaryRepository | 完整成员、有界加权缓存、binary name 校验和嵌套名查找；声明注解显式值、默认值、参数与 TYPE_USE 位置。不加载目标类；ASM 原始注解值不冒充 javac 有效值 |
| JavaReflectionFacts | switch 默认分支与 fallthrough 合并；相同值的常量/句柄在合并时忽略不同来源，保持 modeled 证据 |
| JavaModeledCallRecovery / JavaOverrideFacts | 生成方法的 SOURCE/JAR 覆盖关系、类泛型代入、raw 擦除、协变返回与跨包访问路径；非法覆盖丢弃整个方法的候选，不覆盖已有 javac 证据 |
| JavaTypeFacts | 生成声明的字段/返回/参数类型引用、数组组件、重复泛型实参和深度 5 投影；本地模型与事实身份独立实现，引用源码区间来自生成来源，仍为 modeled/possible。36 条独立关系含坐标相等 |

生产库不依赖整个 anatomist-core；Rust 继续负责 CLI、daemon、Maven 导出编排、worker host、事实校验、存储与查询。来源仓库未发现项目级 LICENSE/NOTICE；fixture 中第三方许可证不作为这些生产文件的许可证。该事实保留在来源审计中，不推断未声明的许可。
