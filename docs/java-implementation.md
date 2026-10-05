# Java 8 实现与验收

Java 代码位于 `lang-adaptor`：`src/compiler/java` 为独立 Java 8 编译器桥；`analysis` 为独立模型到事实、框架和冷增量；`bridge` 为 Java 25 native C ABI。没有 JNI 应用调用、JavaParser 或 JVM 产品回退。

```text
CLI → daemon → 独立 Rust host → Java 25 共享库 → SDKMAN Java 8 javac
                    专用 FD       C ABI             独立 JVM
                                      ← 独立模型 ← parse/analyze
                ← Fact IR ← 原生提取/框架/冷增量
```

## 构建与运行

| 入口 | 内容 |
|---|---|
| `just test-java-alignment` | 全部 gate 与逐能力门禁；未验收能力存在时返回非零，并保存日志回执 |
| `just test-java-alignment-fixtures` / `-native` | 分别验证 fixture 或 fixture + native/专用 IPC；不宣称整体对齐完成 |
| `just test-java` | SDKMAN GraalVM 25 + Java 8 桥编译；独立模型、绑定、文件级缩减和 full/delta 等价测试 |
| `just test-java-native` | 真正共享库 → Java 8 模型与生命周期测试 |
| `just test-java-maven` | 显式运行真实 Maven 导出 fixture；不属于产品索引流程 |
| `just test-java-e2e` | 生产 daemon → native → javac → 发布/查询；覆盖热复用、故障保留旧版，需先构建共享库 |
| `just test-java-stress` | 长轮次热 worker、历史读者、GC、RSS 和 full/delta 等价 |
| `just test-java-release` | 成套 release 产物校验和包内二进制验收 |
| `just test-rust` | 独立 mock 与 Rust 回归，不要求 JDK |

原生构建始终执行 `lang-adaptor/.sdkmanrc` 的 `sdk env`；桥默认 `$HOME/.sdkman/candidates/java/8.0.492-zulu`，可用 `CODEANA_JAVA8_HOME` 指定另一套 SDKMAN Java 8。SDK 真实版本以 `release` 为准，不以别名猜测。源码包的普通 JVM 测试只用于开发，产品没有 JVM fallback。

用户 home 的 `config.toml`：

```toml
schema_version = 1
kind = "user"
[limits]
max_workers = 1
max_worker_memory_bytes = 536870912
[daemon]
java_warm_workers = true
java_signature_cache = true
[toolchains.java8]
language = "java"
home = "/absolute/path/to/.sdkman/candidates/java/8.0.492-zulu"
```

如需调整 javac 堆，在**项目** `.code-ana/config.toml` 中设置 `[resources.index] java_compiler_heap_bytes = 134217728`；它不能写入用户配置。

复制 `tests/fixtures/java-native/base` 到独立目录，使用上述 home 启动 daemon，运行 `index`、`calls main`、`call-sites main`、`source main`。开发版从本项目构建目录寻找 native bundle；release 从 `bin/../lib/java` 装载，缺包明确失败。mock home 与生产 home 仍隔离。

## 输入

plain 使用显式 source_roots。Maven 使用外部导出 metadata：

```toml
[languages.java.discovery]
build_system = "maven"
inputs_manifest = ".code-ana/java-inputs.json"
```

`discovery.mode="auto"` 只展开外部准备的 schema 3 manifest。物理目录来自 MavenProject.getBasedir；清单保存有效 reactor、profile/model 摘要、生成根来源、MAIN/TEST 的已调解依赖及有序 classpath。外部制品的 coordinate/classifier/extension/path/hash 保存在 classpath；dependency_order 通过索引引用制品，避免重复身份。schema 2 须重新导出。settings 只捕获路径与摘要。

显式入口：`codeana-cli --project /project java-inputs export --output /project/.code-ana/java-inputs.json`。导出编排、校验、预算和进程回收由 Rust 实现；原 `scripts/export-java-inputs.py` 只转发到 CLI。发布包的 `codeana-export-java-inputs` 直接调用 CLI，不需要 Python。支持 `--settings`、重复 `--profile`/`--module`/`--scope`、`--offline`、`--source-only`、`--timeout`。SDKMAN Java 8 下的一次 Maven 会话先导出有效模型，再接收模块/scope 选择并调用 Resolver；core extension 在生命周期前终止，不运行目标插件或处理器。未安装 reactor 使用索引占位 JAR。外部 classifier 保留身份，reactor classifier/非 JAR 明确拒绝。输出不得覆盖旧清单；不自动生成源码或执行 resource filtering。

源码、资源、metadata、Lombok 控制配置和 JAR 均捕获到任务私有文件，再按哈希校验、物化和持久化。 Rust 捕获不重新编码 Java 字节；编译器桥将非 UTF-8 文件标记为 SOURCE_ENCODING_INVALID/partial，保留 source owner/hash，继续健康输入；strict 拒绝含该错误的候选发布。克隆捕获对象会 pin 临时目录；最后一个持有者释放后回收。JAR 通过流式复制捕获和发布，daemon 不累积依赖字节数组。

| 预算 | 上限 |
|---|---|
| 源码/普通文件、IPC 单帧 | 8 MiB |
| Maven manifest | 32 MiB |
| 单个依赖 JAR / 全部捕获输入 | 256 MiB / 2 GiB |
| 模型 / 结果 / 事实 | 默认模型64MiB、结果64MiB、100,000事实；可显式配置至模型1GiB、结果8GiB、5,000,000事实。按行暂存、类型图共享同一不可变字典，单context及真实容量仍需最终验收 |
| 编译器堆 / 原生堆 | 128MiB默认，可配8–512MiB / 256MiB默认，可配8MiB–1GiB；worker整体仍受用户RSS限制 |

原生分析的 OOME/StackOverflow/Error 使用预留终态响应，不让已退出线程保持 running；该 worker 失败后由 supervisor 回收。整个 worker 进程树仍受用户 RSS 限额约束。输入捕获通过不代表大项目语义索引通过。`[daemon]` 热 host 默认开启，保留一个已确认发布的空闲 worker，60 秒过期。

## 能力边界

| 能力 | 实现与限制 |
|---|---|
| 核心 | 声明、调用点/目标、字段读写、类型引用、控制上下文、独立语法节点关系、注解常量/默认值、继承/覆盖、诊断与 coverage；生成方法覆盖检查 SOURCE/JAR 泛型、raw、返回与访问规则，作为 possible 保存；错误选中不当成确认目标 |
| 坐标 | 原始 UTF-16 → 捕获 UTF-8；生成声明无执行位置，不造 L0C0。生成签名的类型引用标注 `span_origin`，借原始生成来源的声明区间定位 |
| 类型 | 默认桥输出平面类型图，已接入 SDK8、归档、公共事实、扩展和 Rust 发布校验；compiler8/model7/worker11/request5 已原子切换。内部旧树仅显式 fixture 使用；新版完整真实项目语义仍待验收。覆盖声明、泛型、通配符、数组、类型变量、方法类型与调用选中类型；`java.type`/`java.binding` facet 可查询。48 层 / 4096 节点预算截断向上传播 `complete=false`、告警及文件 partial，真实循环保留身份；5/20/40 层及 7 个结构化字段投影与冻结参考相等，尚未覆盖全部类型图及高级推断 |
| 二进制 | ASM 9.8 只读 classpath JAR 与目标 Java 8 `rt.jar` 的类头/成员签名、父类型、注解、常量及异常，附在被引用外部符号；不加载业务类 |
| 扩展 | 五类构建期 SPI 与 registry；版本/有效配置/依赖指纹；声明和事实分别暂存并校验。回调失败丢弃输出、披露 partial，strict/取消继续终止；project resource 只消费封存 inventory。全部 context 的 Java/call 输出先暂存，项目阶段按 world 可见性读取；可选单声明 SPI 有独立事务与诊断，内置分析器迁移及 selector/预算仍待补齐 |
| Lombok model | Getter/Setter 访问级别、静态与 boolean 命名/冲突；Data/Value、三类构造器及 equals/hashCode/toString/canEqual 签名、八类 logger；有效修饰符独立保存。daemon 封存 `lombok.config` 祖先/目录层，支持 stopBubbling 和有限键；生成注解及 Deprecated/NonNull 有静态模型。Builder/SuperBuilder/UtilityClass 的有界子集已实现，完整 TYPE_USE 复制和一般建模恢复仍待补齐；只提供声明签名，不运行处理器或生成方法体，候选不当确认调用 |
| Spring | stereotype/RestController、Bean/数组别名；字段/方法/构造器/工厂参数注入及 javax/jakarta，共享有界元注解/AliasFor。MVC、生命周期/事件与工厂成员查找。XML 采用封存 UTF-8 的有界 SAX 模型，保留 alias/parent/abstract/factory/init/destroy、嵌套 bean、集合值与 ref/idref、精确字节 span 和 SOURCE 成员关系；默认属性/完整继承配置及 binary 查询仍缺。注册只表示静态 possible，不生成确认调用 |
| SOFA | XML service/reference、内部 4.19.1 publisher/consumer、binding/transport/channel/event/listener 配置树、重复出现和位置；明确识别内部/既有公共命名空间。SofaService/SofaReference 注解及接口形状。动态 contract/uniqueId、abstract/parent、未知 transport 保留 partial；独立于 Spring 启用，不证明运行时绑定 |
| 增量 | 单个源码仅改方法体、上下文输入不变且其他源码可安全投影时，用磁盘 NoCode 签名分析改动文件；声明/注解或外部引用变动、缓存损坏、不完整签名则回退完整 context；泛型替换/边界、注解默认值、枚举/常量、嵌套构造器和部分 TYPE_USE 已验证。验证过的源码声明目录恢复原始声明证据，避免将 NoCode 的二进制来源发布为源码事实。签名只供索引，不含业务方法体；缩减分析也会在完整暂存视图上重跑变更源码的扩展，更新注册位置与依赖；未变源码无额外发布权限。资源字节变更、删除或 producer 禁用仍整组替换相关 owner，包括模块 NoCode 路径 |
| 会话/并行 | 任务内复用目标 Java 8 JVM，每次创建独立 javac 上下文并关闭文件管理器；1/2/4/8 调度 fixture 已验证。产品默认按 RSS 预算选择 1/2，可显式配置 `java_parallelism=1..8`，必须通过 host/JVM 内存预约校验；取消/退出回收池中进程 |
| 文件/模块缓存 | 共用 256 MiB 磁盘 LRU 与 256 MiB 任务私有输入预算；验证后复制到 task-owned 输入，原项驱逐不影响在途 javac。文件 catalogue 累计 64 MiB；模块保存扩展前的原始声明，消费后重新应用 Lombok。损坏、并发写、取消和 SOURCE 回退已有 fixture。父/消费模块有序 CP 不同或模型有诊断时保守回退，消费 CP 签名与完整真实容量仍待补齐 |
| 当前范围 | Java 8、macOS arm64、同版本 Maven reactor；17/25、混合版本、JPMS、Gradle 后续。文件级缩减限安全子集；复杂语义和容量仍需扩展验证 |

事实协议继续使用 Fact IR 1 的既有通用事实组；worker 协议 11，编译器桥 8 / Java 模型 7，native ABI 2 / analysis request 5。版本未知先拒绝，历史事实仍可读。基线独立分块、校验总数和摘要；结果仍由 Rust 统一校验并发布。`PublishAck` 后 worker 回 `Acked`，daemon 才将同身份 worker 放回热池。

## 证据

实际执行记录见 `docs/implementation/java-validation.md`；`todo/08-java-worker.md` 分别记录实现和未验收范围。fixture 通过不等同于完整框架覆盖或真实项目容量达标。


### Collector、菱形构造器与建模输入（2026-10-02）

| 项目 | 当前证据 |
|---|---|
| 实现 | 参数通配符捕获复用；无上界变量转换返回 UNKNOWN；复制冻结 WorkerOverloadEvidence，仅改包名，SDKMAN Java 8 编译；调用节点选择与选择区间诊断分离；保留原 javac 证据并拒绝错误建模目标；候选内 diamond 目标类型、类边界与访问校验 |
| 回归修复 | 首次实际 common 差分发现 13 个原调用误丢和 1 个构造器状态变化，失败证据 iopengw-common-collector-diamond-20261002 保留。按真实恢复结果识别 lambda 输入，允许已验证的 javac diamond 类型满足无类形参约束的目标，并保留原构造器状态；未以 fixture 成功替代真实差分 |
| 完整 JVM | collector-diamond-full-jvm-20261002-151200/receipt.json：34 组 / 267 项通过、无漂移。它早于后续输入判定修复；当前修正版 corrected-input-promoted-jvm.receipt.json：64 项通过、源码和产物无漂移 |
| 当前 native | native-fixtures-20261002-153237/receipt.json：64 项通过、源码与产物无漂移。functional-reference-20261002-153421-48738：13 组独立差分全部一致，含 collector 14、diamond 7、lambda 输入 23 个目标；状态、位置、多重性一致 |
| C ABI 与 daemon | corrected-input-native-cabi-probe.log：SDKMAN Java 8、完整/增量缓存一致、处理器陷阱、损坏包拒绝、取消/清理及主动 SIGSEGV 隔离通过。corrected-input-release-daemon.log：实际 release 发布、查询、增量/full 组合用例 13.679 秒通过 |
| 真实模块 | iopengw-common-corrected-input-20261002：178.607 秒，发布、查询成功；输入/产物无漂移、配置恢复、无异常 signal。声明 3862/3862、调用点 5206/5206、目标 5206/5206、递归归属 5206/5206，四项核心投影零差异；详见 corrected-input-common-diff-receipt.json |
| 下一项证据 | SOURCE/回调类型关系独立样本：当前产品 31 条、参考 23 条，含 body/import 导航混入和隐式回调遗漏。仅在 target 隔离原型验证声明签名、写明类型与回调参数投影；未合入或记作完整语义通过 |
| 仍未完成 | 全 SOURCE/回调类型关系、平面类型图、全 reactor MAIN/TEST/XML、全部扩展语义差分、3 冷/3 热独占 2 GiB 及最终 release。52 项仍仅 E01/E02 完整验收通过；局部核心对齐不替代这些门禁 |

所有耗时为观测值，不作为新增性能阈值；正确性门禁未完成前不进行最终性能验收。真实项目只使用冻结已提交内容，未运行业务构建或注解处理器。主动崩溃隔离测试不记为安全软件杀进程。


### SOURCE 与回调类型关系（2026-10-02 续验）

| 项目 | 证据 / 边界 |
|---|---|
| 已合入 | SourceTypeReferences：声明签名、throws/类型边界、隐式/显式回调及参数、继承叶位置；body/import/new/cast 导航使用 type_navigation；未知/歧义不作 complete，恢复关系保留 possible 和原 javac 证据 |
| 专项 | source-types-first-jvm.log：7 项通过，含修改/删除 full-delta、Unicode/CRLF、歧义、恢复参数去重、单声明预算及文件 partial；source-reference-callback-current.receipt.json：隔离原型 64 项旧绑定回归通过且无漂移 |
| 独立证据 | type-reference-20261002-160748-49785/receipt.json：隔离 JVM 的 7 组 SOURCE 差分一致，含位置、context、重复和状态；正式 tests/java_type_reference_reference.py 已接入 JVM/native gate。只对照 App.java 的 SOURCE 与回调，生成签名另验，不等于全语义通过 |
| 产品验证 | SDKMAN native 构建通过；native 专项、实际 release daemon、完整 JVM 与真实 common 差分正在执行，按后续实际回执记账 |
| 仍待 | 平面类型图、所有 MAIN/TEST/XML 与扩展语义、真实全项目独立差分、独占 3 冷/3 热 2 GiB 和 release；52 项仍只有 E01/E02 完整通过 |


### 生成类型过滤与 TEST 编码隔离（2026-10-02）

| 项目 | 当前证据 |
|---|---|
| SOURCE 产物已验 | native-fixtures-20261002-161132：70 项通过；type-reference-20261002-161650-81736：8 组 native 差分一致；完整 C ABI/AP trap/缓存/取消/主动 SIGSEGV 隔离通过；source-types-release-daemon-final.log：查询、增量/full 和删除通过 |
| 完整 JVM | source-types-full-jvm-20261002-161650：36 组执行 350 项、无源码/产物漂移；其中 lambda 脚本误收集 Models/Lombok 的 73 项重复测试。已改用模块导入，后续只执行本组 3 项，不把重复项当新增能力 |
| 真实 common | iopengw-common-source-types-20261002：128.361 秒，rev_2d6d5792c334480dabb9092430e99dfa，74061 事实，发布/查询/配置恢复通过且无漂移/异常 signal；声明 3862、调用点/目标/归属各 5206，核心零差异 |
| 类型续验 | SOURCE 核心端点 REFERENCES 无缺失，多 408：380 条生成 JDK 类型，28 条匿名合成类型头。两项已隔离证明并合入；生成过滤保持源码写明类型，匿名显式字段/方法保留；10 组隔离参考含 13/13 生成关系和 3/3 匿名成员，当前 native/真实重新验收尚未完成 |
| 全范围输入 | MAIN/TEST 外部导出 40 Maven 模块，80 source/resource roots；2857 MAIN、1357 TEST Java；另 3 个已提交 Java 位于资源/非 reactor 目录，不作为 MAIN/TEST Java 输入。只读取元数据，不运行业务生命周期/AP |
| 冻结编码问题 | 1 个 TEST 源码不是合法 UTF-8，Maven 声明 UTF-8。原字节 Git blob 29ba1645c442d65c05a15dabddd55de5009ca6ee，与 HEAD 完全一致；未修改。Anatomist 首轮 full MAIN/TEST/XML 因 decoded hash 不同误报 source changed，原失败保留 |
| 健康参考 | anatomist-main-test-xml-valid-20261002-163141：明确排除仅此无效 UTF8 文件，4213 个 Java 的参考索引 0，MAIN/TEST/XML 有输出，配置恢复/JAR 未变。它不是所有文件语义验收通过，缺失文件按输入错误单列 |
| 编码实现 | 原字节、hash、source owner 保留；单文件 SOURCE_ENCODING_INVALID ERROR/partial，健康输入继续；全部无效也给 partial。strict 拒绝发布，修复/删除/无效字节变化 full-delta 等价。encoding-isolation-staged-corrected.log：4 项通过；已合入，新 native 构建通过，实际门禁待完成 |
| 剩余 | 平面类型图、最新全范围产品/严格独立语义与扩展差分、独占容量/release；52 项仅 E01/E02 完整验收通过 |


### TEST 编码口径与扩展类型共享修正（2026-10-02）

| 项目 | 状态 / 证据 |
|---|---|
| 用户确认的编码处理 | 无效 UTF-8 文件保留原字节、Git blob 与 SHA，记录 SOURCE_ENCODING_INVALID/partial，继续其他文件；不计入语义解析通过数，strict 仍拒绝候选 |
| 全范围实际失败 | iopengw-main-test-xml-rust-encoding-fixed-20261002/receipt.json：1433.887 秒，在 app/core/service@TEST 的 model.lombok 返回 NATIVE_HEAP_EXHAUSTED；没有发布，无源码/产物漂移，配置恢复，无异常 signal。这是失败证据，不是完整验收通过 |
| 局部内存根因 | ExtensionContext.mutableMap 把 ModelArchive.ImmutableType 深拷贝为可变 Map。overlay-sharing-original-jvm.log：8000 个生成声明、35 层类型树耗尽 64 MiB；相同隔离修正通过 overlay-sharing-fixed-jvm.log。该证明不等于真实项目 OOM 唯一根因 |
| 已合入修正 | ModelOverlay 使用专属 pendingMap 共享不可变编译器类型，公共 mutableMap 保持可编辑完整副本；生成声明及普通嵌套容器仍隔离。首轮 RecordArchiveProbe 发现兼容性问题，已据此修正；新增 OverlaySharingProbe 验证提交完整、类型不可变、producer 和相邻声明不互相污染 |
| 匿名继承位置 | SourceTypeReferences 对匿名类 extends/implements 使用声明主体区间；保留显式成员和导航。隔离冻结参考 anonymous-hierarchy-staged-reference-corrected.log：7/7；当前正式 native 仍待重验 |
| 无效对照已终止 | core-service-test-native-original-20261002/receipt.json：TEST-only classpath 误配 MAIN,TEST scan，主动终止自建 harness；604.967 秒，配置恢复、无输入/产物漂移，不作为修正效果证据 |
| 下一门禁 | 成套 native 构建、overlay/Lombok/匿名位置回归、完整 classpath 的 TEST 定向验收，然后全 reactor MAIN/TEST/XML 和全语义差分。平面类型图原型尚未接入；容量和 release 未通过 |

所有耗时只作观测，正确性通过前不作最终性能判定。


| 扩展兼容性续验 | 结果 |
|---|---|
| 首轮归档回归 | RecordArchiveProbe 要求 mutableMap 的类型副本可编辑；旧直接共享修正抛 UnsupportedOperationException，失败日志保留 overlay-anonymous-archive-jvm.log |
| 修正后的隔离证据 | overlay-sharing-compatible-jvm.log 与 overlay-sharing-compatible-archive-probe.log 均通过：仅 ModelOverlay.pendingMap 共享封存类型，mutableMap 原行为保持 |
| 首轮 SOURCE 对照 | type-reference-20261002-174614-93677：11 组逐项一致，但运行中源文件变更，all_ok=false；不计作冻结验收，须以修正后成套产物重验 |


### overlay 兼容修正后的正式产物回归（2026-10-02）

| 检查 | 实际结果 |
|---|---|
| SDKMAN native 成套构建 | overlay-compatible-anonymous-native-build-20261002.log：0，类型共享仅作用于 ModelOverlay.pendingMap；公共 mutableMap 原约定保持 |
| 完整 JVM | overlay-compatible-full-jvm-20261002-175032/receipt.json：37 组、284 项，1014.636 秒，all_ok=true，源码/产物无漂移 |
| native 回归 | native-fixtures-20261002-175107/receipt.json：77 项，472.254 秒，all_ok=true，源码/产物无漂移 |
| native SOURCE 冻结参考 | type-reference-20261002-175107-42012/receipt.json：11 组，all_ok=true，源码/产物无漂移，冻结参考未变；包含匿名主体继承坐标 |
| release daemon | overlay-compatible-release-daemon.log：编码隔离/raw bytes/strict 旧 head、查询/full-delta/删除两项通过，50.213 秒 |
| native C ABI | overlay-compatible-native-cabi.log：真实 SDKMAN Java 8、缓存/full、处理器陷阱、取消/清理、主动 SIGSEGV 隔离、损坏包拒绝通过 |
| 补充隔离证明 | anonymous-generic-hierarchy-native-isolated.log：匿名泛型类 4/4（类型与继承）全部一致；尚未扩大正式该组的历史 3 项投影 |
| 平面图原型 | type-graph-staged-budget-cancel.log：SDKMAN Java 8 深度超限回滚、取消后健康根保留通过；尚未接入产品或升级协议 |
| 真实项目 | core-service-test-overlay-compatible-20261002 正在运行：已越过 model.lombok，仍需发布、查询和与冻结参考的真实语义差分。全 reactor 与最终容量/release 仍未通过 |

本轮有其他验收并行，耗时不作为性能证据。真实验收的终态以对应 receipt.json 为准，不把阶段完成记为全项目通过。

2026-10-02 调用恢复修正：优先使用 javac 已归属的完整 lambda 参数类型，仍保留候选局部 descriptor 覆盖；仅生成调用引起的完整实参类型实际变化才影响外层选择；隐式泛型工厂接受候选参数的目标类型约束，显式实参、边界、不适用和候选隔离仍检查。隐式匿名 Object 不再产生 extends 边，显式 extends Object 保留。正式 38 组/294 JVM、87 native、16 冻结调用和 13 冻结 SOURCE 对照及 3 daemon 回归通过；core/service 新 MAIN/TEST/XML 发布与全语义仍执行中，完整 reactor/类型图/框架差分/容量/release 未完成。

## 真实 core/service 发布与第二轮调用修正（2026-10-02）

| 项目 | 结果 / 边界 |
|---|---|
| `core-service-main-test-contextual-20261002/receipt.json` | MAIN/TEST/XML 发布及查询通过：775 个健康 Java，431508 事实，revision `rev_374679be84a644b1a5e4cb4cea4dadc3`。1335.257 秒，进程树峰值 958726144 bytes；配置恢复、无漂移和异常信号。coverage 仍 partial，完整语义未通过。 |
| 旧版严格多重集 | 声明8332、调用点及归属35813均零差异；目标缺188/多29，SOURCE类型缺182/多75；反射成员20、extends528、implements152零差异。TEST单独投影目标缺2/多0，类型缺3/多2。新版必须重跑，不把旧版数值当作新版结果。 |
| 根因分类 | `missing-target-classification.json`：188个缺口中，no_applicable_candidate=86、no_modeled_input=30、recovery_budget=24；其他48个含状态差异、回调描述符和不完整候选。缺少目标与状态改变保留严格差异，不按目标名称忽略。 |
| 同返回类型生成方法 | constructor 类型未变化的处理仅限 constructor；普通生成方法即使返回类型不变，仍参与外层恢复。`println(value.toString())` 原产物漏目标，修正后 possible；新增正例及 full/delta/注解删除，11项绑定通过。 |
| 第一轮新版 native | `native-fixtures-20261002-193044/receipt.json`：89项、274.259秒、all_ok=true，无输入/产物漂移；`functional-reference-20261002-193047-35495/receipt.json`：17项冻结参考全部一致。`root-expressions-release-daemon.log`：编码原字节/strict旧head、SOURCE查询、回调full/delta共3项通过（35.112秒）。这些证据早于下面的非泛型候选修正。 |
| 匿名构造器根因 | 冻结的实际 TEST/OpengwMessage/MessageType 源码加 fixture 生成构造器后，原实现复现 JSONObject 目标被 recovery_budget 阻断。trace定位到非泛型构造器仍请求父调用目标类型，重复进入 Assert 重载/getData 泛型推断并耗尽工作步骤。仅含方法形式类型参数的候选才请求该上下文；不调高100000/64预算。原失败和修正通过日志均保留。 |
| 可重跑回归 | `tests/java_real_binding_alignment.py`：校验冻结HEAD的三份原字节、锁定TEST依赖SHA，测试原始匿名构造器与 full/delta/生成构造器删除。`non-generic-context-frozen-source-staged/receipt.json` 两项通过；它是隔离源码回归，不替代真实 reactor。非泛型修正已合入并构建native，正式新版门禁待回执。 |
| 编码政策 | 用户确认保留非法UTF-8原字节、SOURCE_ENCODING_INVALID/partial、继续其余文件；strict拒绝候选并保留旧head。不猜编码，不把坏文件计入解析成功。 |

剩余顺序：新版 fixture/native/daemon → core/service 真实重验与全量差分 → 生成模块签名受控再归属、类型图与其余语义缺口 → 全 reactor MAIN/TEST/XML/框架 → 独占容量和release。仍只有 E01/E02 完整通过，不把局部发布、原型或专项相等提升为全部完成。

## 新版回归与真实验收失败记录（2026-10-02）

| 门禁 | 结果 |
|---|---|
| `non-generic-context-full-jvm-20261002-194037/receipt.json` | 38组、296项全部通过，1378.881秒，无输入及产物漂移。并行耗时不作为性能证据。 |
| `native-fixtures-20261002-194017/receipt.json` | 89项真实native通过，854.737秒，无输入及产物漂移。 |
| `functional-reference-20261002-194021-3791/receipt.json` | 17项冻结参考一致。 |
| `non-generic-context-frozen-source-native/receipt.json` | 冻结原始匿名构造器和full/delta两项真实native通过。 |
| `non-generic-context-release-daemon.log`、`non-generic-context-native-cabi.log` | release daemon三项及C ABI隔离门禁通过。 |
| `core-service-main-test-non-generic-20261002/receipt.json` | **失败**：1407.671秒，TEST/model.lombok 报NATIVE_HEAP_EXHAUSTED，配置native heap为512MiB。未发布revision，旧head保留；无异常信号、源码/产物漂移，配置恢复。不能复用旧版发布回执证明新版通过。OOM的具体对象/保留链尚待取证。 |
| `collector-target-staged-frozen-18/receipt.json` | 隔离嵌套collector修正与冻结参考18个专项全部一致，新样本从5/6变为6/6；8项collector正反例和full/delta通过。修正已写入产品源代码，待新构建/native/真实门禁。 |

非法UTF-8政策继续按用户选择执行：保留原字节，明确失败，继续健康文件。完整40模块语义验收、生成模块签名受控再归属、类型图集成与独占容量仍未完成。

## Collector修正的正式门禁与新版真实差分（2026-10-02）

| 证据 | 结果 |
|---|---|
| `non-generic-context-full-jvm-20261002-201250/receipt.json` | 新collector修正：38组、300项全部通过，679.814秒，无输入/产物漂移。目录名沿用runner历史前缀，产物SHA以本回执为准。 |
| `native-fixtures-20261002-201245/receipt.json` | 93项真实native全部通过，246.148秒，无输入/产物漂移。 |
| `collector-target-native-frozen-18/receipt.json` | 18个冻结native专项全部一致，新增collector目标6/6。 |
| `collector-target-native-cabi.log`、`collector-target-encoding-daemon.log` | 原生SDK8/C ABI/取消/故意SIGSEGV/AP陷阱/包完整性通过；非法UTF-8保留原字节、partial、strict保留旧head、修复删除delta通过。 |
| `core-service-main-test-collector-target-1g-worker2g-20261002/receipt.json` | 实际MAIN/TEST/XML发布查询通过：775健康Java、431533事实，revision `rev_ecd764196b9a4a6fb08328a262fa1527`，500.008秒，进程树峰值812482560 bytes。native heap=1GiB，compiler=512MiB，总worker配额=2GiB；隔离runner仅改总配额，见run-profile.json。旧512MiB失败回执保留，未宣称该配额问题已解决。配置恢复，无漂移及异常信号。完整语义仍未通过。 |
| `call-diff.json` | 严格调用投影：声明8332、调用点/归属35813零差异；目标35693/35827，缺166、多32。原版188/29已降低缺口，但状态差异也保留，不按目标名称忽略。 |
| `test-call-diff.json` | 严格TEST投影：声明2924、调用点/归属17612、调用目标17632均零差异；不代替类型/框架或全reactor。 |
| `supplemental-abi-expanded-staged.log`、`supplemental-abi-super-builder-recursive-staged.log` | 生成getter、泛型Builder、SuperBuilder递归自类型NoCode再归属的三个隔离样本诊断从1降到0。递归TYPEVAR只有查证到完整声明formal才允许ABI引用；缺失/不完整类型不能放行。仅隔离原型。 |
| `supplemental-integrated-getter-staged.json` | 隔离管线保留原javac/recovered_callback，并附第二次javac/签名SHA证据；Consumer、Leaf generic_arg/parameter_type三个SOURCE类型关系完整，原诊断仍保留。未合入native，缓存失效、反例及真实EchoRecord仍待验证。 |

资源harness新增显式native/worker配额与Rust预留公式校验，13项恢复/边界测试通过。manifest的output相对命令工作目录，新的导出采用项目内完整路径；两个未启动索引的路径/配额失败回执也保留。时间和单次峰值不作为最终容量验收。

## 长Builder、identity工厂与生成签名再归属（2026-10-02）

| 项目 | 本轮结果 / 限制 |
|---|---|
| 产品源码 | 长Builder仅预处理生成Builder接收者链；无参泛型工厂采用目标wildcard约束并递归推断wildcard bounds。不增加100000/64推断预算。 |
| 生成MAIN辅助签名 | 独立NoCode仅包含声明，无业务Code；同目标SDK8 javac禁用处理器，PRIMARY回调按原路径/位置/身份对齐。保存独立signature/输入SHA及原始证据，原诊断和调用possible不变。不完整ABI保守保留SOURCE。 |
| 产品专项 | `builder-supplemental-product-collector.log`11项、`builder-supplemental-product-chain.log`3项、`builder-supplemental-product-signatures.log`6项通过；覆盖反例、依赖类型变化、getter删除、泛型Builder及递归SuperBuilder的full/delta/cache。 |
| 独立严格对照 | `supplemental-reference-20261002-204724-35719/receipt.json`：Getter4/4、泛型Builder3/3、SuperBuilder3/3，按相同原字节span、owner、类型上下文、置信度多重集全部相等，无漂移。上一轮测试源目录错误导致Builder参考0项的失败回执保留，未作产品结论。 |
| 既有真实项目 | collector版本SOURCE类型仍缺182/多75，TEST类型缺3/多2。最新生成签名native/真实EchoRecord效果、整体MAIN残余目标及全40模块尚待新版验证。 |

本轮尚不勾选52项完整对齐；先完成新版native/daemon/真实语义差分，再执行独占容量。

新版真实native回归已通过：`native-fixtures-20261002-204914/receipt.json`105项、398.953秒，无输入/产物漂移；`functional-reference-20261002-204914-43546/receipt.json`20项调用专项，`type-reference-20261002-205107-55370/receipt.json`13项类型专项，以及`supplemental-reference-20261002-205024-49694/receipt.json`三项MAIN/TEST生成签名严格对照全部一致。`builder-supplemental-native-cabi.log`覆盖SDK8/取消/缓存/处理器陷阱/故意SIGSEGV/包校验；`builder-supplemental-release-daemon.log`两项原字节坏编码及SOURCE查询/full-delta通过。完整JVM与新版真实core/service仍运行，不计作终态通过。

额外发现14层含生成getter实参的Builder链仍触发预算；`builder-getter-depth-product.json`原产物29/30，隔离修正30/30，并通过`builder-getter-depth-staged-frozen-21-corrected/receipt.json`21项冻结对照。该修正尚未合入，当前真实验收产物保持固定。

## 新版真实core/service终态与严格差分（2026-10-02）

| 核对项 | 新版事实 / 证据 |
|---|---|
| JVM | `non-generic-context-full-jvm-20261002-204914/receipt.json`40组312项，869.18秒，all_ok=true，无输入/产物漂移。 |
| 发布与查询 | `core-service-main-test-builder-supplemental-exported-1g-worker2g-20261002/receipt.json`775健康Java、431667事实，revision `rev_1bbfb284749b4e92bce3c7d0b226ed78`，671.572秒。native1GiB/worker2GiB；配置恢复、无漂移和异常信号，coverage仍partial。新正式harness显式资源参数，无隔离配额runner。 |
| 严格调用 | 声明8332、调用点/owner35813零差异；目标35728/35827，缺130/多31。TEST声明2924、调用点17612、目标17632全部零差异。 |
| 严格类型 | SOURCE类型12180/12188，缺11/多3（之前缺182/多75）；TEST类型1705全部零差异，实际EchoRecord回调已对齐。反射成员20、extends528、implements152零差异。 |
| 剩余类型根因 | 8个缺口是原javac归属成Object而SAM仍complete的回调，当前supplemental触发条件未重验此类；另ForwardInterfaceTypeHandler产品有新的javac签名证据而参考仍possible，造成2缺/3多的严格差异，未忽略或改标签。 |

以上仅core/service；40模块全reactor、剩余MAIN调用/类型、类型图集成、完整扩展差分和独占容量/release均未最终完成。导出尚未落盘即尝试读取的预检失败回执保留，未启动索引、不计产品失败；完成导出后本次正式运行已通过发布查询。

## Object回调、较短Builder与并发工厂（2026-10-02，新增源码待native重验）

| 修正 | 取证与验证 |
|---|---|
| 原SAM complete但类型为Object | 有生成AUX声明时重验PRIMARY回调，只保存与原证据不同且完整的新SAM。原证据/诊断仍保留；8项fixture通过，包含PRIMARY Unicode/方法体编辑、getter删除及cached full/delta。冻结四个MAIN/TEST样本严格相等，Map链13/13（`supplemental-reference-20261002-210839-63932/receipt.json`）。 |
| 14层含生成getter实参Builder | 预处理门槛由16改为8，仅生成Builder接收者链；推断预算100000/64不变。5项fixture通过，30/30及getter类型不适用/full-delta反例；冻结旧21项一致。 |
| ConcurrentMap工厂赋值给Map | formal与自己相等的约束不建立自映射；根据参考returnTargetSeedable/sameNestedErasure，实参子类型先推断，随后检查目标类型。14项collector与11项poly反例通过，`collector-erasure-staged-frozen-22/receipt.json`22项全部相等。已合入源码，产品native/真实重验待新回执。 |
| 拒绝的隔离改动 | `collector-chain-staged-frozen-24/receipt.json`未通过：类型变量边界registry额外恢复了参考未确认的toMap目标；没有接入产品TypeOperations。lambda/entry链仍需继续查证；不把额外目标或更高标签自动当作对齐。 |

新版完整fixture现为40组，预计319项；之前312项/105 native及真实431667事实的回执只证明上一版，不替代本次新源码验收。


## 基本类型表达式的独立 javac 证明（2026-10-03）

| 项目 | 结果 / 边界 |
|---|---|
| 实现 | 第二次 SDK8 javac 已有生成辅助 NoCode 上下文中，唯一同源位置/种类的完整基本类型只作为独立证明；不自行推广算术类型。不可变原 syntax、原调用 ERROR 类型和错误诊断保留。证明随 provenance 发布，调用恢复仍 possible。 |
| JVM / native | supplemental-expression-product-signatures.log：11 项通过；type-graph-bindings-jvm-20261003-101454：222 项、562 次图请求、532 次 Rust 校验、all_ok=true，无漂移。隔离新 native 默认 145 项（native-fixtures-20261003-102050）、专项 11 项均通过。 |
| daemon | supplemental-expression-native-proof-e2e-final.log：实际 Rust release/native 发布、dispatch、Unicode 字节 span/证明 SHA、配置删除与恢复、Integer→String 失效、完整 full-delta 相等及旧 revision 保留，1 项 32.697 秒通过；另有产品功能与跨模块 WIRES 2 项 71.007 秒通过。 |
| 失败定位 | 首次 daemon 测试没配置生成注解策略；实际 java.lombok 摘要 generated_annotations=partial / lombok_version_or_configuration_required，MAIN 世界正确保留 SOURCE。明确 lombok.config 后通过；删除配置负例仍验证整世界补充失效，未放宽 ABI 边界。 |
| 真实隔离投影 | 14 MAIN 文件 2070/ref2081，11 独立缺失、0 独立额外、3 状态对，消除 Sandbox 三项及 Thread.sleep 状态差异；不是全项目新版发布证明。 |
| 扩大独立对照 | 新表达式 frozen 类型3/3、调用7/7一致。但六组完整调用+类型对照中 recursive-super-builder 多出 Child#builder()，旧4a native也复现。失败回执保留，未掩盖候选、未记作整体通过。 |

完整40模块旧4a正式运行已发布，独立差分仍有缺口；不包含本次 Java 证明和 Rust WIRES v2。默认类型图、完整属性/框架语义差分、Rust 通用缓存、独占容量与发行门禁仍未关闭，仍只有 E01/E02 完整通过。


## 首次全40模块 MAIN/TEST/XML 发布与独立差分（2026-10-03）

| 验收 | 正式终态 / 边界 |
|---|---|
| 4a57677 全项目 | iopengw-full-main-test-xml-archive-4a57677/receipt.json：7962.354 秒，3,036,628 事实，revision rev_7005f59b51ce41cb9ad939e0b1357aa8；发布、catalog head/commit 和 CLI 查询通过。配置恢复，输入/产物无漂移，无非预期信号。8GiB 结果、32GiB scratch 为显式正确性保护配置；并行取证的 RSS 不算独占容量证明。 |
| 冻结输入 | 已提交5039文件、4217 Java；Maven范围4214 Java，其中1份非法UTF-8按既定政策隔离，其余4213健康文件与冻结参考对照。所有4214捕获Java SHA已校验；457个匿名/局部类双方唯一同原字节位置配对。 |
| 调用清单 | iopengw-full-4a-independent-calls.json：声明49938/ref49937，多1；调用点151342及全部归属完全相同；目标150424/ref151381，缺1265、多308，其中980独立缺失、23独立额外、285状态对。正确退出1，不能记作完整语义通过。 |
| 类型 occurrence | iopengw-full-4a-independent-types.json：SOURCE56095/ref56105，缺10、多0；反射182/ref182，但20对严格投影差异；extends1795、implements1173均零差异。不是完整类型属性/框架验收。 |
| 单声明差异 | IOpengwManagerServiceImplTest 的顶层隐式构造器产品存在、参考不存在；双方匿名类构造器均存在。保留差异，尚未定位参考为何缺少；未删有效产品证据凑计数。 |
| 后续优先 | 通信模块 MAIN/TEST 的调用恢复和10个回调类型缺口、反射20对差异；随后全属性、注解与扩展/WIRES/XML、默认类型图、Rust缓存生命周期及最终门禁。该运行不含本轮表达式证明和Rust WIRES v2，不能代替新版验收。 |

完整52项仍只有E01/E02关闭。新版生成接收者/递归图修复的完整回归另取终态，不能导入4a回执代替。


## SuperBuilder 独立接收者证明与递归类型图修复（2026-10-03）

| 项目 | 结果 / 边界 |
|---|---|
| 冻结参考根因 | 继承遮蔽比较完整 JVM descriptor（含返回类型）；同参数的协变选择仍须返回转换明确为 YES。具体 Parent/Child 的 builder 工厂在冻结参考中有歧义；不能将 Child#builder 额外发布为已选目标。 |
| 接收者证明 | 第二次 SDK8 javac 的完整已确认调用可为原 ERROR/UNKNOWN/NONE 提供独立生成类型接收者。两侧调用须同路径、原位置、lookup_name/mechanism 且各自唯一；生成 owner/selected 声明也须唯一，分别为 modeled CLASS/METHOD。只辅助后续调用，不确认上游歧义 factory。原类型与诊断保留，provenance 发布 UTF-8 span 和输入/签名 SHA。 |
| 类型图 | 新接收者及 readable 操作从真实 GraphRef 重新开始遍历，避免沿用祖先路径将递归形式参数误当作缺少 bounds 的摘要。既有图预算和 Rust 校验不放宽。 |
| JVM 终态 | type-graph-bindings-jvm-20261003-111508：223 项、567 次图请求、537 次 Rust 校验，690.488 秒，无漂移；functional-reference-20261003-111511-20893：40 组全部通过。 |
| native 终态 | type-graph-bindings-native-20261003-111915：212 项、566 次图请求、537 次 Rust 校验，677.992 秒，无漂移。supplemental-reference-20261003-112712-53271：8 组调用/类型严格一致，包含错误实参、Child 注解删除反例。 |
| 实际产品 | super-builder-graph-reroot-native-proof-e2e.log：两项 Rust daemon/native 回归 49.546 秒通过，覆盖两个独立证明、配置/成员删除、full-delta 相等、旧 revision 保留、不生成业务 class。 |
| 失败记录 | 修复前 native 图链只剩4个目标（参考8个），日志保留；已知失败的旧 JVM 图运行由开发者主动 SIGINT 停止，见 super-builder-receiver-graph-controlled-stop.json。没有观察到安全软件杀进程。 |

以上是新版专项和回归证明；完整40模块的已发布回执属于旧4a，不含此修复。新版全量语义、默认类型图、完整扩展、缓存与容量/发行门禁仍须继续，52项仍只有E01/E02关闭。


## 未解析书写注解与反射上下文（2026-10-03）

后续原字节补充：所有SOURCE注解使用点保存 `written_name`、`written_attributes`（原表达式）及 `written_values`（字面量值，其他表达式不求值），分别披露完整性；原javac显式/有效值保持独立。SDK8修复Unicode转义标识符的原切片起点。MVC2保存原参数写法及独立 `resolved_type`；handlers2和sofa-annotations2保存原属性，事件参数原写法独立保存。对应缓存按bridge SHA和各extension版本失效；公开wire版本不变。独立声明/注解/注册JVM对照通过，完整最新native/daemon/真实项目回执仍须完成，详见[执行记录](implementation/java-alignment-execution.md)。

| 项目 | 实现 / 正式证据 |
|---|---|
| 原始注解 | 编译器 annotations 不变；未解析声明注解另存 source_annotations。唯一显式 import、已确认包类型或完整限定名可提供书写证据，含 source_options_complete；缺失/歧义 import、未知表达式不猜测。 |
| 扩展边界 | 框架使用独立书写视图；Lombok builtin 7 仍要求原始 compiler annotation 名已属于 lombok.*。缺 jar 的 import Getter 不直接生成成员，完整限定 @lombok.Getter 可恢复已知选项，严格匹配冻结参考。 |
| 缺失 CLASS 注解 | 仅捕获 javac Symbol.CompletionFailure；返回 ANNOTATION_TYPE_UNAVAILABLE、retention UNKNOWN、complete=false，健康二进制成员继续读取。其他编译错误正常传播。 |
| 反射 | 原始调用点非空 control 独立传播到 reflection_lookup.type_context；无控制块不添加属性。旧4a的20对差异均为该上下文，但新版全项目消除须实际重验。 |
| 专项 | written-annotations-fixtures-frozen-guard.log：7项通过；written-annotation-reference-20261003-115052-32706 JVM及私有validation-written-annotations/.../written-annotation-reference-20261003-115247-40664 native均8组严格一致，无漂移。 |
| native 全默认 | validation-written-annotations/.../native-fixtures-20261003-115247：153项、538.278秒，全部通过，无漂移。 |
| native 图消费者 | validation-written-annotations/.../type-graph-consumers-native-20261003-115247：100项、250次图请求、244次Rust校验，344.306秒，全部通过，无漂移。默认图仍关闭。 |
| 实际 daemon | written-reflection-native-proof-e2e.log：两项40.999秒通过，涵盖原始诊断、两个独立补充证明、配置/成员删除、full-delta、旧revision保留。 |
| 未完成 | 完整JVM回归仍运行；模块自有classpath声明证据正在隔离工作树验证，尚未合入。新版全40模块语义、全扩展、Rust通用缓存、独占容量和发行门禁仍未关闭，52项仍仅E01/E02关闭。 |

失败的宽松 import Lombok 试验及空 control 读取试验回执保留，均已纠正；没有观察到非预期进程被杀。协议版本不变：新增属性是独立可选证据，bridge SHA和builtin版本使缓存失效。


## 依赖模块自有 classpath 注解证明（2026-10-03）

| 项目 | 状态 / 正式证据 |
|---|---|
| 根因 | communication-common 的自有 Maven MAIN classpath 包含 provided Lombok，下游 HTTP classpath 不包含。按消费模块重新分析 AUX 会失去简单 import Getter/Builder 的原始编译器注解身份。冻结参考以模块自身上下文建立生成 API。 |
| Java 实现 | ModuleAnnotationEvidence 只在任务内保存 MAIN 模块自有上下文的注解证明，builtin 8；不建立持久缓存。全部 SOURCE/hash、完整声明 ABI、Lombok coverage、原始配置输入须满足；歧义、循环、坏源码、清单或配置不同不复用；32MiB 超限明确失败。注解选项脱离归档后封存，跨上下文不会借用已释放类型对象。 |
| 调度 / 原始证据 | 关闭签名缓存也按依赖顺序处理；增量补充隐藏 MAIN 前置，绝不发布其源码。原 javac annotations、ERROR 类型、诊断和选中证据保留；恢复调用仍 possible。prerequisite_annotation_proof 独立记录模块/world/context、源码 SHA、classpath SHA 和配置输入。通用持久缓存生命周期迁往 Rust 尚未完成。 |
| native 默认 / 图 | validation-module-annotation-evidence/.../native-fixtures-20261003-121302：163项、621.648秒；type-graph-consumers-native-20261003-121302：110项、268次请求、262次Rust校验、423.560秒。两者通过、无漂移，图仍 opt-in。 |
| 冻结 Maven 参考 | module-annotation-reference-20261003-121302：native三组通过，provided Getter 1/1、Builder链4/4、删除注解0/0，目标与状态严格一致，无漂移；JVM三组亦通过。 |
| 真实隔离投影 | communication-closure-proof：13 MAIN模块、13078 compiler SOURCE输入，三份HTTP源文件976/ref976目标与状态完全一致。1个匿名类按双方唯一同源SHA/UTF-8跨度配对。是JVM隔离调用投影，未作新版native全项目发布或完整类型/扩展验收。单provider中间结果945/ref976保留。 |
| 前版完整 JVM 终态 | written/reflection版本：generic-selected-full-jvm-20261003-115247，50命令、389直接测试；另外图消费者118、图绑定223，全部通过，无输入/产物漂移。不能替代本次模块证明新版的完整JVM重验。 |
| 待完成 | 选定模块导出自有 MAIN prerequisite classpath 的 Rust/Maven链路在隔离验证，尚未合入；同版本完整JVM/高级绑定/daemon/40模块全语义与容量/发行门禁仍须继续。52项仍只有E01/E02关闭。 |


## 选定 Maven 模块的 MAIN 前置 classpath（2026-10-03）

| 项目 | 实现 / 验证 |
|---|---|
| Rust 边界 | Rust 基于实际已调解的 MAIN/TEST 依赖决定需要的前置模块；前置只沿自己的 MAIN 图递进。处理外部依赖解析后才出现的 reactor，以及 provided reactor 引出的后续前置。只调度实际捕获源码的依赖闭包，保留手工旧清单 resolved_classpath 默认 true 的兼容性，绝不据此启动无关模块。 |
| 最小 Maven 桥 | 同一 Maven 进程在生命周期前分轮返回官方模型/Resolver 图，按 Rust 明确请求解析前置 MAIN classpath。模块解析结果仅在该会话内复用；MAIN 前置不解析未选中 TEST。所有轮次核对 nonce、清单、内容 SHA 和源模型快照；最大256轮，复用既有取消/时间/响应预算。 |
| 回执 | schema3增加可选 prerequisite_classpath_rounds，默认0，限制0..256；旧回执仍接受。新增 AMP1 前置交互，最终输入响应/extension SHA使缓存失效，Java native/worker其他wire版本保持不变。 |
| Maven | validation-maven-prerequisite-classpath/target-maven-fixtures-receipt-final.log：17项21.647秒通过，包含 provided/optional、未传播的TEST、无关缺依赖模块、外部依赖发现 reactor、二轮 provided 前置、scope/order/classifier及禁止生命周期。 |
| Rust | target-rust-tests-closure-final.log：全工作区85项通过；target-rust-clippy-closure-final.log及cargo fmt --check通过。新增回执兼容/预算和真实已调解scope前置图反例。 |
| daemon | target-maven-all-daemon-final.log：3项187.077秒通过；真实导出→Rust捕获/隐藏调度→native/SDK8→发布，消费模块无Lombok而前置provided有Lombok、原诊断/proof SHA、body增量、删除注解、full-delta相等、旧revision、无关坏模块和TEST-only。没有业务class生成或非预期杀进程。 |
| 限制 | 当前 daemon证明使用隔离的组合产物；根目录同版本完整JVM/native/daemon/40模块全语义仍须重验。复杂NoCode完整验收、Rust通用持久缓存、默认类型图、容量和发行门禁未关闭；52项仍仅E01/E02关闭。 |

当前专项修复已通过原始属性、元注解增量、生成覆盖跨度、字段/XML独立对照及发布确认测试；最新完整门禁尚未关闭。旧冻结缓存组合的iopengw运行在core/service MAIN第二份模型读取时OOME，未发布、未发生异常进程信号；去掉字典重复副本后已冻结新native重跑，详见[执行记录](implementation/java-alignment-execution.md)。


## 编译器类型图传输的 OOM 修复（2026-10-03）

| 项目 | 本次实测 |
|---|---|
| 实际失败 | `validation-model-api-real/.../iopengw-core-service-test-actual-compiler/receipt.json`：TEST 模型序列化 Java heap OOM，1638.028 秒、未发布；无安全软件异常信号，源码/运行工件无漂移。 |
| 修复 | 编译器 RowSpool 的类型图只保存归档位置；最终编码按字典稳定 token 复用身份，不强持有全部已解码图；模型脱离 javac 后释放重载检查器。图/模型格式、节点/字节限额及 Rust 产品边界不变。 |
| 可重复正反例 | SDK8、64 MiB：相同 75000 节点 RowSpool 转移，旧桥 OOM，新桥成功；循环节点、GC 后身份复用、两个独立字典不串图。已加入正式 archive gate。 |
| 真实请求回放 | `iopengw-captured-main-test-baseline` 旧桥同 JVM MAIN→TEST 在 TEST OOM；`iopengw-captured-main-test-archive-backed` 新桥两阶段成功，MAIN 60.525 秒、TEST 101.857 秒。同 512 MiB 堆；仅物理输入路径重映射，原源码/有序 CP/原请求摘要保留。MAIN 模型 SHA 完全相等，TEST 输出 264494003 字节。回放不代表 native 发布或整体容量通过。 |
| 回归 | 完整 archive、11 项 signature、两项 JVM 复用/诊断隔离通过。矩阵 XML 全图门禁归属纠正为 F05 Spring XML；契约的 Spring 内置版本同步为实际 7。 |
| 仍待 | 新同版本 native/daemon、全40模块 MAIN/TEST/XML 发布与14域严格差分、完整模型 API 实项目清单、独占容量及最终 release。仍只 E01/E02 完整关闭。 |


## 真实项目完整模型导航对照（2026-10-03）

| 项目 | 证据 / 状态 |
|---|---|
| 真正缺口 | iopengw TEST 原完整清单在 JAVA_MODEL_SYNTAX_IDENTITY 失败：228 文件含242份共享节点出现，匿名基类型/多变量声明由 javac 共享 Tree。注解省略 value= 时，javac 插入合成赋值节点，原桥把父链误记为0；原失败和未归一清单保留。 |
| 产品修复 | API按源码节点身份只展示一次，校验内在字段相等，保留首次父链（与冻结JavaSyntax一致）；所有替代父边仍校验未知父/环/深度。只建一次按文件索引。桥跳过合成祖先、恢复注解原参数角色；声明API分清声明成员与词法归属，匿名/局部类不作为方法声明成员、lambda参数不冒充方法形参。原始事实及共享节点出现次数不删。 |
| 新身份 | registry升为 staged-project-view-v8-model-api-dag，进入模型/模块失效身份；模型/Fact IR/ABI版本不变。 |
| 独立参考 | 原Backend把该请求重排成21个编译上下文，造成假缺失；单次全源码原worker响应又触发冻结解码器全响应512MiB入场限额，失败原样保留。新测试适配器固定原workerSHA，用相同2852源码/有序778CP编译一次，委托原冻结帧/类型/声明解码函数，逐单位在同512MiB上限内消费真实JavaModel，汇总查找候选。完整响应hash/EOF/完成计数验证，不改冻结JAR/worker/模型算法、不生成业务字节码；这不是参考或产品容量验收。 |
| 正反例 | 五组SDK8导航/摘要模式全部相等：泛型嵌套、方法形参、控制语法、共享AST；重复类的参考丢失第二位置缺陷仍raw不同且复现。保护覆盖冲突节点、未知父、仅替代边成环，以及SOURCE hash/范围/次序/重复与四投影篡改。 |
| 真实完整清单 | iopengw-core-service-test-model-api-streaming-reference/receipt.json：53.435秒；声明3284/3284、AST135076/135076、原源码228/228、全部474类型查找，四投影零差异、无源码/工件漂移。每个UTF16位置及声明切片逐项摘要，不抽样；编译输入2852源码与228发布源严格按冻结Maven清单校验。仅该TEST上下文，不替代全部40模块。 |
| 上一内存修复的产品证据 | f04c5d6新共享库的177 native fixture：958.502秒全过；XML完整图三组25.027秒、DI和跨模块两项78.303秒通过。真实TEST已越过compiler_model/decode，仍在Lombok阶段；这些回执不包含之后v8 API/注解父链修复。 |
| 仍待 | v8新native/daemon/完整Java回归；全40模块MAIN/TEST/XML与14域语义差分、全上下文模型清单、独占容量和release。仅E01/E02完整关闭，未删complete_model_api_inventory最终待验收项。 |
# 全 Maven 上下文模型 API 验收入口（2026-10-03）

| 项目 | 状态 / 边界 |
|---|---|
| 全项目入口 | `tests/java_project_model_api_alignment.py` 要求完整、已解析的40个物理模块及 MAIN/TEST，逐上下文独立SDK8编译、native模型导航和冻结单次worker对照。无Java输入的scope显式保留；失败不终止其他上下文，不按抽样关闭能力。 |
| 坏UTF-8 | 产品编译请求保留原文件，归档必须恰有一条原hash的 `SOURCE_ENCODING_INVALID`，且该文件无声明/语法/观察。参考请求明确排除不能解码的文件；健康源码、位置、切片和所有查找继续严格对照，不宣称坏文件与参考解析相等。 |
| 汇总门禁 | `java_real_semantic_diff.py --model-api-receipt` 仅接收完整模型清单；要求同发布源码、同Maven manifest、同compiler bridge和native共享库SHA，并重新验证每个上下文回执及实际输入数量。未提供仍保留 `complete_model_api_inventory` 待验收项。 |
| 专项回归 | 六组JVM和native摘要导航通过，包含共享AST、坏编码、全部UTF16位置/声明切片；9项Python保护检查通过。新入口的全部40模块执行尚待完成；真实TEST仍在Rust候选写入，不代表发布/全语义/容量已通过。 |

## 辅助源码与隐式 enum 模型对齐（2026-10-03）

| 项目 | 实现 / 实测 |
|---|---|
| 真实缺口 | 全上下文类型查找发现63个辅助源码匿名类型被产品额外暴露；MAIN 模型缺14个 enum `valueOf(String)` 隐式参数。保留原失败清单，不缩小查询范围。 |
| Java 修复 | 辅助声明 API 默认只展示命名类型及声明头，`full_auxiliary_model` 明确启用完整导航；根据 javac 已验证的隐式方法参数头补齐只读参数视图。紧凑辅助 AST 保留字段初始化表达式及空方法块，省略类初始化块及方法体语句；不附加参考没有的绑定/表达式类型图。registry 为 `staged-project-view-v9-model-api-aux-headers`。 |
| 缓存正确性 | 桥继续遍历方法体收集原外部符号，仅抑制紧凑 AST 输出。第一次剪枝遍历造成冷/缓存结果差4个外部符号，原始差分保留；修复后完整模块缓存9项94.752秒通过，编译范围缩减仍有效。 |
| 模型专项 | `model-api-reference-20261003-213731-19943` JVM与`213940-46904` native共9组通过，新增隐式enum、辅助声明头、完整辅助模型。重复类型的参考位置缺陷仍明确保留为raw不同。 |
| 严格验收范围 | 实项目模型清单扩大为所有上下文源码的声明、AST、源码位置及全部类型查询；汇总拒绝仅发布源码清单。core/service MAIN 2624源的最新原生对照仍在运行；此前228个TEST发布源通过不能代替这项。 |
| 真实发布 | f04c5d6组合的core/service TEST实际native→Rust worker→daemon→存储发布成功，6097.804秒，原始回执`validation-compiler-transfer-f04c5d6/.../iopengw-core-service-test-transfer-fixed-v2`。终态为partial，语义与容量未接受；该旧组合不包含v9修复，不作最新全项目证明。 |
| 仍待 | 最新同版本完整Java/native/daemon回归、40模块MAIN/TEST/XML发布、80上下文完整模型清单、14域语义差分，以及最后的独占容量/release。矩阵仍只关闭E01/E02。 |

完整清单输出的后续取证：全部上下文源码超过测试探针的单份64MiB JSON上限，JVM在完成导航后明确报`JSON_BUDGET`；原日志及现场栈保留在`iopengw-core-service-main-complete-source-api`。测试探针改为任务独占临时文件流式编码，编码成功后才输出，测试清单上限512MiB；不改变产品JSON/worker/模型限额，不删行、不抽样。`model-api-stream-output-proof.log`复现原失败，逐行UTF-8摘要验证超过64MiB的完整输出，并验证预算失败/取消零输出、临时文件清理。原native对照主动终止并另记`superseded.json`，不是安全软件杀进程，也没有算作通过。

完整 MAIN 清单终态：`iopengw-core-service-main-all-source-stream-output` JVM与`iopengw-core-service-main-record-stream-native`原生均四投影严格相等，覆盖全部2624源码、42072声明、489941 AST、2763类型查询，源码位置和切片无差异、无输入/工件漂移；原生267.949秒（产品探针192.040秒）。两个原始清单各157445089字节。测试探针进一步逐条落盘，避免堆内持有完整清单；预算/取消/重复source保护及9组native专项通过。仍仅一个MAIN上下文，不替代全部80上下文或实际worker共享库发布。

## 辅助 AST 的 Lombok 扩展回归修复（2026-10-03）

| 项目 | 实际证据 |
|---|---|
| 原失败 | `validation-v9-full-gates-ab33799`完整Java回归在补充签名12项中3失败/7错误。独立`aux-syntax-lombok-original-failure.json`证实Lombok模型阶段出现`EXTENSION_CALLBACK_FAILED`，生成声明被丢弃；原数据保留。 |
| 根因 / 修复 | 紧凑辅助AST的VARIABLE没有绑定`symbol`，旧Lombok初始化扫描把它当必填。仅对存在的非空绑定读取symbol；辅助字段继续使用javac声明头`has_initializer`。Lombok内置版本8→9，缓存/扩展指纹随之失效。其他语法消费者已核对可选绑定处理。 |
| 回归终态 | `aux-syntax-lombok-supplemental-fixed`12项91.835秒、`full-fixed`16项143.015秒、`module-cache-fixed`9项114.460秒、`module-overlay-fixed`9项147.976秒均通过。新增明确拒绝扩展回调失败的断言，原javac未知getter诊断继续保留。 |
| 仍待 | 含Lombok9的成套native/daemon完整回归及冻结iopengw全40模块、80上下文、14域差分。旧Lombok8正式运行主动终止并保存superseded说明，不能拼接旧回执认定新版通过；容量最后执行。 |

## Lombok 生成声明的扩展导航补齐（2026-10-03）

| 项目 | 实现 / 验证 |
|---|---|
| 生成成员 | 只读模型补齐生成参数、Builder/SuperBuilder 类型参数、父链和切片。源类型参数保留 javac 原位置，额外 C/B 与生成参数保持无跨度；默认值 provider 沿字段父链查原类类型参数。原始记录不因导航重写。 |
| 源码锚点 | Data 使用原字段/类；方法目标 Builder 的生成类及基础方法使用原类，参数字段/setter 使用原参数。来源环、父环、深度和歧义受保护。 |
| 生产缺口 | 补齐 SuperBuilder 抽象 builder 默认构造器，`modeled_implicit=true`、无源码跨度、Lombok 来源，非 javac 确认。Lombok 版本 10；registry `staged-project-view-v10-model-api-generated-members`，旧缓存失效。 |
| 独立参考 | 实际冻结 LombokJavaModelExtension 消费同一次 SDK8 编译模型，不输入产品生成声明。17 组 JVM 默认模式及 native 摘要模式全部通过，无输入/工件漂移。证据：`model-api-reference-20261003-232302-49991/receipt.json`、`model-api-reference-20261003-232302-49990/receipt.json`（均在 target/java-alignment/evidence 下）。覆盖 Data、泛型/默认值/SuperBuilder、方法及泛型方法目标。 |
| 参考缺陷 | 两例仍 raw 不相等：重复类型丢第二跨度；冻结扩展拒绝 Singular 前删除隐式构造器。独立未增强参考模型证明构造器存在，产品保留。Singular 不属于参考受支持能力。 |
| 事实回归 | Builder 14 项、SuperBuilder 8 项 JVM 回归通过，含泛型 owner、继承调用恢复、full-delta/删除及无跨度构造器的非确认状态。测试 native 可执行文件不代替共享库/Rust/daemon 验收。 |
| 门禁修复 | 旧完整回归的 41 组、391 项 Java 测试通过，随后默认导航对照失败：参考重新发现坏 UTF-8 或重规划注解依赖。工具明确使用原 SDK8 单上下文，坏文件只在参考侧排除，产品原字节和诊断继续验证；旧失败不覆盖。 |
| 仍待 | 最新同版共享库/daemon、40 模块/80 上下文/14 域与最后的容量/release。app/test@TEST 另发现图表固定 64MiB 与显式 1GiB 归档预算冲突，待修复。矩阵仍只关闭 E01/E02。 |

## 显式归档预算与图表一致（2026-10-03）

| 项目 | 证据 / 状态 |
|---|---|
| 原失败 | `validation-java-correctness-de090ea/.../iopengw-all-context-model-api/5/compiler/receipt.json`：app/test@TEST，3319 源/472 发布源，请求 1GiB 归档，却在固定 64MiB 图表预算处失败。半成品实际图节点字节累计约 64MiB，不是安全杀进程。 |
| 修复 | 编码、RowSpool、归档按需解码的平面图表统一消费所属 RecordArchive 的预算，最大 1GiB；默认归档 64MiB、单帧、节点/图数量与旧嵌套类型表保护不提高。图节点仍按行落盘，不把完整图表装入堆。桥 SHA 变化使缓存失效；模型/协议格式不变。 |
| 保护 | 新 ModelGraphBudgetProbe 含 65536 节点、超过 64MiB 的实际图；显式 96MiB 全节点/循环边往返成功，默认 64MiB 仍拒绝。旧类运行同一 probe 明确 MODEL_GRAPH_TABLE_BUDGET。JVM/native 均通过；原 75000 节点传输、GC 身份、分页、归档/取消/JSON 等完整 java_archive_alignment 回归通过。日志：model-graph-budget-{old-failure,native,archive-regression}.log。 |
| 真实编译 | `iopengw-test-test-graph-budget-fixed/receipt.json`：247.967 秒成功，3319 源、472 发布源，完整归档 526510405 字节；源、classpath、manifest、桥无漂移，无异常信号。只是实际 SDK8 编译器及归档，不是 native 产品发布或容量验收。 |
| 原生模型 | `model-api-reference-20261003-233150-7960/receipt.json`：新桥/新图表代码的 17 组 native 模型 API 专项通过。app/test@TEST 全源原生 API 清单正在对照，最新共享库/Rust/daemon 全量门禁仍待完成。 |

大上下文原生终态：`iopengw-test-test-graph-budget-native-api/receipt.json` 678.220 秒完成，全部3319源、51969声明、651055 AST、3553类型查询的四投影严格相等，无输入/工具源码漂移。新成套共享库的17组原生专项也通过（`validation-java-correctness-391f0cf/.../model-api-reference-20261003-233750-57761`），已启动该固定提交的全项目发布、80上下文及完整回归；旧de090ea清单主动SIGTERM并保存superseded回执，旧发布仍继续作为独立诊断证据。最终全语义和容量尚未通过。

## Lombok AST 对齐与任务缓存释放（2026-10-05）

| 项目 | 实现 / 验证 |
|---|---|
| 范围 | `off/model` 配置保持；`model` 对齐冻结 Anatomist `mode=ast` 的声明覆盖，包括 Builder/SuperBuilder。无处理器、编译模式、生成方法体或业务字节码；Rust 产品边界保持。 |
| 单元选择 | 按源码单元识别 Lombok，包含辅助绑定源；已有 overlay 的单元不重复生成。字段初始化优先使用 javac 的显式标记，只有缺失标记的待处理字段才读语法。 |
| 类型改写 | 泛型按 owner + name 替换；共享原图的数值边、循环、NULL 下界和未改节点，任务局部缓存最多 8 个映射。written type 无变化时返回原类型，深度边界对齐参考 128。Lombok 11 / registry v11 使旧语义缓存失效。 |
| 长任务 | 原运行在 message-protocol-component MAIN / model.lombok 停滞；原始模型 233671404 字节、请求和 2413 个输入已保留。单模块 JVM（含 binary enrich）约 12 秒、native 约 29 秒成功，不能据此断言完整任务根因已经排除。原任务通过 CLI cancel 结束，旧 head 保留。 |
| 累积缓存 | 归档租约继续保留到任务结束；上下文释放同时清理所有已租用归档及其 snapshot spool 的解码缓存。避免只清理当前源码图而保留历史 snapshot 缓存。 |
| 缓存保护 | 80 个 snapshot 的循环类型根在 32MiB 堆下通过，释放缓存后可重读，任务关闭后明确失效；同样 fixture 禁用缓存释放出现 Java heap space。证据：`target/java-alignment/evidence/lombok-stall-replay-20261005/`。 |
| 专项回归 | Lombok 16、Builder 14、SuperBuilder 8、session 10、extension 19 项通过；SDK8 桥重新编译成功。归档、新版 native 独立参考、完整 iopengw 发布 / 80 上下文 / 14 语义域和新版发行验收仍需继续，不能标为全部对齐。 |

AST 共享图的落盘保护：metadata overlay 在内存共享基础图，归档只增量写入请求根可达的闭包，多个根共享已分配节点；未改的编译器图仍用原路径。5005 节点基础图的两根只落盘 5 节点，循环、NULL 下界及不同 owner 保留。Builder 14、SuperBuilder 8、归档全组、Java 8 类型图 11 和 JVM 独立模型 API 17 组通过。前一完整回归因新测试误用 Java 8 不支持的 Map.of 失败，已修正为 singletonMap 并明确终止旧回归；不得把旧收据视为新版完整通过。早一版 native 独立 17 组也通过，最终成套版本仍须重新验收。

长任务阶段日志保护：保留原 4096 条 / 1MiB 历史上限，额外原子更新 `phase-latest.json`；采集器以当前标记为准并单独保存，最大 16KiB。历史耗尽后仍能定位后续模块/声明，不把过时历史误判为停滞。当前标记及历史不可写不改变语义执行；并发、Unicode、截断、上限和任务清理由真实 CompilerSession 回归覆盖。采集 / 恢复 25 项、session 10 项通过。因本次诊断源码更新，c1c37f4 的在途全项目、80 上下文及完整回归已主动取消并保留 superseded 记录；这是主动更新版本，不是新的安全软件 SIGKILL。
