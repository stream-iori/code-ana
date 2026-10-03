# Java-worker：Java 8 实施跟踪

参考 code-ana `d77dc70`；当前 Anatomist 基线 `dev/replace-parser@dff2e3954096853352ec91fcbfc6b995fbd4f390`，历史回执保留旧 SHA。以 SDKMAN Java 8 完整闭环为首期，原生库由 `.sdkmanrc` 的 GraalVM 25 构建。

| 阶段 | 工作 | 状态 |
|---|---|---|
| J0 | 固定参考、复用审计、独立 fixture | 已完成；独立实现，不复制生产源码 |
| J1 | Rust → C ABI 共享库 → Java 8 javac → 独立模型，取消/回收 | Java 8 实际产物通过；桥字节码 52 |
| J2 | 协议、冻结配置/输入、工具链、生命周期 | worker 10 / ABI 2 已接入；Acked、取消与进程清理通过 |
| J3 | 真实核心语义 → 发布 → 查询 | 结构化类型/绑定 facet、ASM 外部 JAR 类头/成员通过；更完整的类型与派发推断仍待补齐 |
| J4 | Maven metadata、Lombok model、Spring/SOFA | metadata schema 3 的 profile/settings 摘要与现有有界框架子集通过；复杂运行时规则不推断 |
| J5 | 真实增量与 full 等价 | owner 级替换、失败查找传播、串/并行等价通过；安全单文件修改可缩减 javac 输入 |
| J6 | 故障、资源、SDKMAN Java 8 分发验收 | macOS arm64 release 包内 9 项及 SIGSEGV 注入通过；native probe/并行基准与 J9 压力矩阵通过 |
| J7 | 文件级 javac 工作量缩减与 NoCode 签名缓存 | 安全子集已实现；简单方法体编辑仅分析改动文件，损坏/声明变更/复杂结构回退完整 context；广泛语法与缓存上限仍待验证 |
| J8 | 跨任务热 worker 复用 | 默认开启；单个已确认发布的 worker 跨任务复用，60 秒空闲过期；长轮次结果见验证记录 |
| J9 | 长时压力与故障矩阵 | 20 轮、64 辅助源码压力；OOME、重复取消/强杀、真实受限磁盘 ENOSPC、debug 与包内 release 的 daemon 原生 SIGSEGV 已通过；大型项目容量仍待另测 |

## 契约

- javac 唯一后端；禁止 JNI、目标项目自动构建、annotation processor 和业务字节码生成。
- 原生库消费独立模型，不持有 Tree/Element/TypeMirror；桥和共享库分别构建。
- semantic inputs、publication sources、replacement owners 分开；MAIN 不读 TEST。
- daemon 捕获输入并发布，Java 决定语义与失效。未知能力明确拒绝。
- 调用点保留多重性；确认声明、证据完整和绑定来源独立；UTF-16 位置转换为捕获 UTF-8 字节。
- full/冷 delta 比较事实多重集、coverage、诊断及 provenance；不删除 unresolved 凑等价。
- Java 17/25、Gradle/Linux 属后续。NoCode 缓存和热复用已默认开启但限有界安全路径；双 context 并行只在有效 RSS 预算至少 1 GiB 时开启。

## 验收

J1 先验证 native 真实可行性，再推进语义移植。J0/J1 对应 P0-01/04a，J2/J3 对应 P0-02/03/04b/06/07，J4/J5 对应 P1-02/03/06/07，J6 对应 P1-08、分发及 T01–T30 的适用项目。各阶段证据记录于 docs/implementation，未执行不得记为通过。

## 当前范围

实现和运行方式：[Java 实现说明](../docs/java-implementation.md)。实际证据和剩余能力：[验证记录](../docs/implementation/java-validation.md)。阶段表只描述本次 Java 8 范围，不把完整类型系统、框架、容量与旧 P0/P1/P2 总表自动勾选。

## Anatomist Java 8 能力对齐

[完整能力差距、源码证据与实施门槛](../docs/implementation/java-anatomist-alignment.md)，覆盖 Java 解析、扩展机制、Lombok/Spring/SOFA、Maven 扫描、签名与容量；已完成 J0–J9 不等于这些能力已对齐。

| 后续阶段 | 范围 | 状态 |
|---|---|---|
| A0 | 固定逐能力 fixture 与模型/扩展/manifest 契约 | 部分：52 项矩阵、统一 gate 与日志回执；未完整验收 |
| M1 / M2 | Maven 外部发现、同会话依赖调解、物理模块/scope、回执与预算 | 部分：6 项 Maven fixture、真实 MAIN/TEST 导出；标签映射及更完整失败矩阵待完成 |
| A1 / A2 | 独立模型/事实、绑定恢复、覆盖/派发、受限反射 | 部分：读写、类型引用、注解值、控制/语法、10 项有界反射 fixture 及 native/daemon 通过；新增失败分类/访问保护和类型变量 owner，完整类型运算/恢复/派发仍缺 |
| E1 / E2 | 扩展接口与失败隔离；Lombok/Spring/SOFA 静态规则 | 部分：五类 SPI、项目暂存视图、单声明事务；16 项扩展、12 项 Java 框架 fixture。XML 配置树、位置/成员和 MsgBroker 有新增 fixture；独立预算/selector、完整 DI/XML/Builder 与类型恢复仍缺 |
| A3 | 复杂 NoCode 签名、模块归档/声明目录、增量等价 | 部分：复杂文件签名和 LRU/私有 pin/损坏修复/并发/取消 fixture 通过；模块归档及生产/容量重测待完成 |
| A4 | 目标 javac JVM 会话复用与规模 fixture 验收 | 部分：1/2/4/8 池及隔离通过，显式并行配置与内存预约已接入；iopengw 输入捕获通过；全语义/差分与容量尚未通过 |

Anatomist 的 Maven 输入 worker 在生命周期前导出并退出，不等于构建目标项目；可借鉴到外部显式导出器，daemon 继续只消费 inputs_manifest。不改变禁止自动目标编译/处理器及独立 Rust worker/C ABI 边界；Lombok 仅对齐静态模型，compile 模式单列架构差异。Java 17/25、Gradle、Linux 仍排除。

## 当前实施切片（2026-09-30，S0/S1）

| 已落地 | 尚未完成 |
|---|---|
| 52 项门禁读取实际执行结果、实现状态和剩余验收项；错误的 passed 标签不能通关；旧 todo 参考提交与 mock 现状已纠正 | 每项完整正反例及真实项目差分、容量/release 总门禁仍缺 |
| worker 7 / ABI 2 / compiler-model 5；compiler 模型行归档与共享类型表；syntax child roles/symbol；Java 事实汇总、native 编码、host/daemon 基线与结果采用任务暂存 | context 内存模型、全项目扩展视图、模型类型运算/绑定恢复、模块缓存、完整静态框架仍需完成 |
| SQLite 候选库按行校验、owner 替换、strict 后 CAS；基线与候选 pin；取消丢弃、恢复遗留目录；报告摘要有界 | 5 百万事实/4 GiB/16 GiB scratch 的显式预算和 2 GiB 总 RSS 门槛未验收；默认上限未提高 |
| Maven resource roots 捕获完整文件 inventory，并封存 MAIN/TEST 归属；内置 provider 继续筛选 XML | Maven 物理模块/逻辑发布标签、lombok.config/selector 捕获及完整失败矩阵尚未完成 |

这张表记录实现切片，不代表 S0/S1 或 52 项整体完成。当前修改的完整验证结果以 [验证记录](../docs/implementation/java-validation.md) 最新回执为准。

## S1/S3/S4 继续推进（2026-09-30）

| 实现 | 验证与边界 |
|---|---|
| ASCII 零逐字符数组的 UTF-8 稀疏偏移；构造器调用名称避免携带匿名类方法体 | 300 组随机 Unicode/代理对边界通过；整体容量未据此标记完成 |
| binary 泛型边界/通配符/数组/嵌套模型，成员截断数量披露 | 解码错误/预算和 2100 成员真实 Java 8 JAR fixture 通过；统一类型目录仍缺 |
| 全部 context 的 Java/call 输出先暂存，再执行项目资源阶段；按 world 可见性过滤、上游扩展变更失效 | 跨 context、MAIN/TEST 可见范围、串并行及 delta/full fixture 通过；selector/独立预算/声明隔离仍缺 |
| Spring 注入字段、方法/构造器/工厂参数、单构造器、javax/jakarta、Qualifier/Resource 与集合形状；Bean Primary/Qualifier | 12 项框架 fixture 通过；只披露静态类型和注册，不确定运行时 Bean 选择；完整 XML 仍缺 |
| 有界反射常量与句柄流、SOURCE/binary 精确方法/构造器查找、分支/循环/数组别名/闭包/构造器逃逸失效；dispatch 解释来源 | 8 项反射 fixture、Rust 查询反例通过；新增 native/daemon 验收正在完整门禁中执行；J13 仍为 partial |

本轮仍未完成 52 项整体对齐、大型容量、冻结 iopengw 全语义差分或最终 release 验收。

## S1/S4 配置、声明与容量推进（2026-09-30）

| 已实现 | 验证/剩余 |
|---|---|
| Getter/Setter 的访问级别、static/boolean/命名冲突；Value 修饰模型与 javac 证据分开 | Lombok 独立 fixture；完整下游类型恢复仍缺 |
| 三类构造器、Data/Value、equals/hashCode/toString/canEqual；只允许替换 compiler-generated 构造器 | 生成声明不作 javac confirmed，方法体不建模；泛型/枚举/冲突完整矩阵待验收 |
| 八类 logger；生成注解、Deprecated/NonNull 的静态复制；逐能力/声明 summary 与 strict | 更完整成员/TYPE_USE 复制、Builder/SuperBuilder/UtilityClass 仍缺 |
| daemon 捕获祖先/源码目录 lombok.config；逐源覆盖、stopBubbling、有限键与 hash 漂移拒绝 | worker 只读 sealed resources；Maven/MAIN/TEST 产品矩阵待验收 |
| 可选单声明模型/Java analyzer SPI；独立事务、subject 诊断、成功声明保留、关闭/失败清理 | 16 项扩展 fixture 通过；内置分析器迁移和独立预算仍缺 |
| NoCode catalogue 比较按基线声明位置建立一次文件索引 | 1000 文件热增量由 296.633 秒降至 29.368 秒；没有提高默认结果/堆上限 |
| 增量保留事实惰性读取；原生 Error 预留终态，避免线程已退出但任务 running | 真实小堆 OOME/子进程清理测试通过；3000 文件完整增量重测及全容量门禁待完成 |

容量历史回执显示 100/1000 文件的八阶段通过，3000 文件全量/无变化通过后在热增量发生原生 OOME；不能将该轮记为 3000 完整验收。52 项总体仍未完成。

后续重测 `capacity-baseline-20260930-143434`：3000 文件八阶段全部通过，54,002 事实；最大进程树 RSS 933,986,304 字节，热/冷方法体 delta 为 60.925/64.459 秒。完整 reactor/JAR/XML、取消/cleanup、大预算和模块缓存仍缺。`20260930-142240-41325` 的 14 个生产回归 gate 全部通过；后续缓存与调用证据修改另行验收。

## S3/S4/S5 后续实现（2026-09-30）

| 新实现 | 专项证据 / 剩余 |
|---|---|
| 文件缓存 LRU、锁内校验/私有 pin、损坏替换、崩溃临时目录清理、可取消等待 | 4 项真实 javac fixture；模块归档、统一缓存预算与生产容量仍待完成 |
| 调用失败代码/选中声明、参数/构造器类型、静态上下文与变量 owner；private/static getter 恢复保护 | 3 项正反例；一般 SOURCE/建模成员、泛型/捕获/继承/重载/varargs 与回调恢复仍缺 |
| Spring XML 配置树、UTF-8 span、alias/parent/abstract/factory、嵌套值/引用及 SOURCE 成员关系 | 6 项 fixture；默认/继承配置、binary 查询、完整门禁/真实差分待完成 |
| SOFA 内部/public XML：service/reference、publisher/consumer、binding/transport/channel/event/listener 与多重性 | 4 项 fixture；native/daemon/真实项目完整验收待完成 |

矩阵为 2 passed / 41 partial / 9 pending；仅 E01/E02 implementation_complete。新增实现正在统一完整 gate，不能用历史产物回执代替。

## S1/S3 后续验收与修复（2026-09-30）

| 实施 | 验证/剩余 |
|---|---|
| 文件缓存、调用失败/变量 owner、Spring/SOFA SAX 配置树 | 151613 回执 14 生产 gate 全部通过，108 Java fixture、12 daemon 用例；52 项不因此自动完成 |
| SOURCE/建模/JAR 候选统一恢复、泛型/捕获/继承、重载/varargs、链式调用和 SAM 方法引用 | 11 项 JVM 正反例与增量等价、Rust 查询通过；lambda/LUB/显式类型参数、复杂混合继承与完整生产验证仍缺 |
| Binary formal owner、方法 shadowing、ASM enclosing 类/方法；递归 SOURCE 类型变量保留 owner | 内外部泛型嵌套 fixture 通过；统一大目录/预算仍缺 |
| 顶层显式 static UtilityClass | 参考有界范围已实现；Builder/SuperBuilder 仍缺 |
| 容量回归与逐行核心事实封存 | 153111：100/1000 八阶段通过，3000 最后 full 原生堆耗尽；已减少事实常驻 Map，native 重测前不能称容量通过 |

最新矩阵 2 passed / 45 partial / 5 pending，implementation_complete 仍仅 E01/E02。

## S1/S3/P04 当前推进（2026-09-30）

| 已实现 | 验证与边界 |
|---|---|
| 逐行核心事实封存 | `capacity-baseline-20260930-180030` 的 3000 文件八阶段通过，最高完整进程树 RSS 892633088 字节；不代表完整 Maven/JAR/XML 容量 |
| SOURCE/binary/NoCode 嵌套参数身份 | 方法参数改用 binary name，包含数组递归与泛型擦除；缓存目录格式 v6-model5；7 语义/11 绑定/11 反射专项通过 |
| BinaryIndex 有界查询 | 按需打开最多 8 JAR、64 类缓存；有序包目录超限精确回退；256 JAR 在 96 FD 限额下验证驱逐/顺序/取消/关闭/错误重试 |
| 变更源强制 owner 替换 | 修复语义相同扩展 owner 被跳过导致 daemon delta 发布拒绝；storage 校验保留；生产 gate 正在重跑 |
| Spring XML 对齐范围纠正 | Anatomist 保留 parent 属性及关系，不进行运行时配置继承合并；后者移出对齐缺口，binary 查询与语义差分仍待完成 |

模块 NoCode/LRU、物理 Maven 模块与发布标签、大预算与 context 惰性模型、完整 Lombok/推断/扩展预算、真实 iopengw 差分和最终 release 仍未完成。

补充执行：`20260930-182942-26323` 14/14 gate（120 Java、47 Rust、14 daemon）通过；`capacity-baseline-20260930-183644` 的 100/1000、`capacity-baseline-20260930-184913` 的 3000 均八阶段通过，产物摘要一致。随后加入显式泛型实参模型/数量/边界/替换及 SOURCE/JAR/null/数组反例，12 绑定 fixture 与最新 native/IPC 通过；新完整 daemon gate 正在执行。未归属的方法引用实参保留 UNKNOWN，目标约束/poly 推断仍缺。

## S2/M08 与 F05 追加（2026-09-30）

| 实施 | 验证与剩余 |
|---|---|
| 显式泛型实参及 owner/嵌套身份/有界 JAR | `20260930-185933-31344` 14/14 gate 通过，121 Java、47 Rust、14 daemon；整体能力对齐未完成 |
| Java source_roots.label 与物理 world 解耦 | 49 Rust/clippy、JVM 和真实 daemon 跨标签 SOURCE 绑定、MAIN/TEST、外部 JAR、改名与歧义拒绝通过；TEST-only/前置 MAIN 与模块 NoCode 联动仍缺 |
| Spring XML 纯 JAR factory/constructor/继承 setter/lifecycle | 已补 SOURCE 优先、成员截断/步骤预算保守降级；独立外部元数据不伪装 javac confirmed。完整新门禁正在执行；XML 参数类型匹配和参考差分未完成 |

最新矩阵：2 passed / 46 partial / 4 pending。模块 NoCode、context 惰性模型/统一大预算、完整 Lombok/推断/扩展预算、iopengw 语义差分和最终 release 仍未完成。

## S1 真实输入传输（2026-09-30）

| 项目 | 结果 |
|---|---|
| M08/F05 追加完整生产门禁 | 194120 回执 14/14 gate，125 Java、49 Rust、16 daemon 通过，无漂移 |
| 冻结 iopengw 全语义预检 | 194733 回执 FRAME_BUDGET，未进入 javac；66 contexts 重复携带 95253 来源/41907 JAR 引用 |
| 修复 | worker 8 共享输入/JAR 表和有序 context 索引，native request 3 惰性输入视图；边界、发布、顺序、歧义及预分配校验已接入，保持 8 MiB 单帧 |
| 验证 | Rust 有界大清单与 JVM 等价/非法输入通过；新原生、daemon 和真实预检还需重测。context 惰性模型、5M/4GiB/16GiB 预算、模块缓存仍未完成 |

## S1 语法归档与模型预算推进

| 实现 | 验证与剩余 |
|---|---|
| 共享资源保留空/XML 等非 Java 标签，禁止 Java 源混入资源 | 修复原 IPC fixture 兼容问题；Rust/JVM 反例通过，native/产品重跑 |
| 模型工作记录数显式配置，默认 100000、最多 1000000；超限携带 context/phase/source/count/limit | iopengw 已越过传输预算，下一阻塞为模型记录限额；需按冻结输入重验 |
| 语法按行归档、不可变 callback 视图、提取/反射按文件消费、失败/回退/完成关闭；项目阶段按 context 打开归档 | 不丢语法行；声明/观察仍整体可变；完整容量与模块 NoCode 未完成 |
| 关闭项目扩展时跳过项目模型与事实重复暂存 | 原子发布/owner 替换规则保留；统一门禁待回执 |

## J03 真实项目根因修复

| 实施 | 证据/剩余 |
|---|---|
| javac 初始化块内部 MethodSymbol 无方法类型，局部变量/lambda 参数身份改为对应源码归属；未知身份不生成无归属 key | 冻结 iopengw 上游 enum 与四种最小合法反例复现；JVM full/delta 已通过，native/17 个 daemon 用例重验 |
| 编译器失败包含有界 context/phase/source 与堆栈；OOME fixture 改为实测 12 MiB | 旧 24 MiB fixture 优化后成功；54 Rust、16 daemon 与其余 127 Java 已通过同一产物补充检查；新统一门禁待回执 |


## S1/J02/F05 后续实现

| 工作 | 当前证据 / 剩余 |
|---|---|
| 初始化块、共享输入/模型预算/语法消费 | 205618 完整 14 gate、129 Java、54 Rust、17 daemon 通过；50 项能力及三个最终门禁仍未完成 |
| TYPEVAR null 下界与捕获编号/身份 | 真实 iopengw NPE 已复现；合法/缺失/失败类型、稳定捕获/上下界/字面量和 delta 专项通过，新 native/18 daemon 验收待回执 |
| 编译器逐行模型归档 | 写前共享类型、最多 64 类型解码缓存、最新声明补充、注解索引与关闭清理；等价专项通过。iopengw 推进至明确 64 MiB 模型归档限额，统一大预算仍缺 |
| Spring XML 类型提示 | SOURCE/JAR 的显式/Bean ref 提示、参数索引、重载/静态约束、同返回类型产品和 delta 专项通过；Spring 4，生产/参考差分待验收 |

## S1/E05 预算与资源扩展继续实现

| 实施 | 证据 / 剩余 |
|---|---|
| 上轮稳定捕获、编译器 spool、XML 类型提示 | `20260930-213115-40956`：14/14 生产 gate、132 Java、54 Rust、18 daemon 通过，无输入漂移 |
| 容量基线 | `capacity-baseline-20260930-213611`：100/1000/3000 各八阶段通过；3000 最大进程树 RSS 700989440 字节。与其他检查并行，不能用作独占性能比较 |
| 真实项目阻塞 | `iopengw-semantic-preflight-20260930-213607`：64 MiB compiler 声明归档明确超限；未发布，语义/差分未验收 |
| 当前新增 | 封存资源 selector、失败/取消隔离、递归不可变类型共享；context core 汇总后立即释放 |
| 显式预算链路 | worker 9 / analysis request 4 / compiler 6 / model 5 / ABI 2；模型字节、native 实际堆、javac deadline、事实数量、结果字节、任务 scratch 接通；默认结果 64 MiB / 100000 条 |
| scratch | 捕获/副本、compiler/私有 pin/native/Rust 归档、候选 SQLite；100ms 监控/备份逐步/候选逐批及发布前校验，采样可短暂超限。完整 5M/4GiB/16GiB 与 2GiB RSS 容量需实测 |

新产物完整门禁待执行；模块 NoCode/TEST-only、高级推断/反射、完整 Builder/SuperBuilder/DI、扩展独立预算、Maven 完整失败矩阵、真实参考语义差分与最终 release 仍需推进。52 项不能据此标记完成。

预算专项：116096 facts / 141971801 正文字节的真实 full 已通过，峰值进程树 656850944 bytes；大基线 unchanged/body delta/full 新增待门禁。单文件 1 万方法的 Element TreePath 重复遍历已修复，6.094 秒编译器专项及 owner/reference 回归通过。222230 门禁因审计发现遗留 100000 硬上限主动中断，不算完整通过；新统一门禁需重新冻结执行。

## S1 真实项目容量修复（2026-10-01）

| 变更 / 证据 | 结果与边界 |
|---|---|
| `20260930-230512-1817/receipt.json` | 上轮 14/14 gate，通过 137 Java、59 Rust、21 daemon；大基线 unchanged/body delta/full 相等。仅 E01/E02 完成，不能证明本轮新增 |
| `iopengw-semantic-preflight-20260930-231328` | 冻结真实项目仍 COMPILER_TIMEOUT；未发布、语义/差分未验收 |
| 诊断范围索引 | 从每个调用遍历全部诊断改为源码 URI/位置查询，保持原始诊断顺序；隔离真实 context 2624 SOURCE/698 JAR 在 49.233 秒完成 parse/analyze/归档 |
| 数字与 SHA | 精确小数/指数/宽整数、配置整数边界、非有限常量标记和 SHA 向量专项通过；解决真实 float/double 归档回读失败 |
| 惰性可写模型 | 声明/观察使用 64 行缓存，写入与跨行别名保留；快照/缓存驱逐/列表移动/entry 写入/回写/关闭专项通过 |
| 真实模型隔离证明 | 730908827 字节、1165954 物理行模型在 512 MiB JVM 堆内打开；原整体加载 OOME。只证明模型读取，不能代替 native 提取与发布 |
| 显式模型工作预算 | 默认 100000，上限扩为 5000000；物理归档仍独立限制 2000000 行及字节预算。31 项 Rust 配置/契约检查通过 |

上述代码已接入；新 native、daemon 全链路门禁及真实项目预检待执行。模块缓存仍为隔离原型，Builder/SuperBuilder、TEST-only、高级恢复、完整 Maven 失败矩阵和最终参考差分继续待实现/验收。

## 模块缓存与旧 ZIP64（2026-10-01）

| 实现 / 实测 | 边界 |
|---|---|
| MAIN 模块 NoCode / SOURCE 目录 | 按上游顺序建立，精确校验源码、CP 顺序/hash、profile/桥；MAIN/TEST 消费、泛型/注解/嵌套事实恢复、冲突/诊断回退 SOURCE。并行模式保留完整 SOURCE |
| 模块缓存生命周期 | 磁盘/任务私有 pin 各 256 MiB，目录解码重量 LRU 256 MiB；在途租约不驱逐、卸载后按需重读；损坏与遗留提交恢复。7 项专项通过，native/daemon 待新门禁 |
| `iopengw-semantic-preflight-20261001-001921` | 226.241 秒失败在旧 ZIP64；进程树 1142161408 bytes，无源码/产物漂移，未发布；已越过编译器超时和整体模型 OOME |
| 真实 JAR | aspectjweaver-1.8.9.jar，SHA-256 `5e41d39eca300e2d8e6067f5660d70dcc66ec2da9cbd46a3d5985e609d1e6ecf`；Java25 拒绝冗余 ZIP64 中央目录，Java8 parse/analyze 已接受 |
| 移植参考 ZIP 读取 | FrozenArchive 在中央目录 ZipException 后校验原始本地头，再顺序读取；最多 50000 entries / 8 MiB 名称 / 256 MiB 解压，保留 CRC/取消/关闭和 CP 顺序。真实 JAR 987 entries / World.class 36101 bytes 可读，反例专项通过 |
| OOM 回归更新 | 惰性模型让旧 12 MiB / 1000 简单类场景成功；改为 6 MiB 与更多方法，仍触发真实任务 OOM，错误返回及 javac 回收通过 |

`20261001-001606-88977` 因旧 OOM 场景已成功而停止，保留失败/中断回执；其余检查不能当作完整通过。新增 module 与旧 ZIP daemon 用例等待新产物统一验收。全部 52 项对齐仍未完成。

## 语法容量、来源与 Builder 继续实现（2026-10-01）

| 工作 | 已取得证据 / 仍缺 |
|---|---|
| `20261001-003522-81819` | 12/14 gate，通过 fixture/native/IPC/Rust；23 个 daemon 中 22 个通过。模块用例和崩溃用例失败，不能记成完整通过 |
| 模块 daemon 用例 | 修正默认并行用例设置；schema 3 是完整调解输入，原 fixture `app→mid` 漏了 lib。独立修正清单后，native full/损坏恢复/delta/full 均通过；永久用例已同步 |
| NoCode 头 | 去掉不属于本类型的 InnerClasses 属性，避免不必要的递归加载；模块缓存格式 v2、文件 v7。7 模块与 6 签名专项通过；原注解断言的根因是 fixture 缺上游输入，不能只归因于头投影 |
| 真实超大语法 | 单文件 @1 记录 24311071 字节超过 8 MiB；新增 @2 分片。25.6 MB / 6000 节点 / 25 片在 128 MiB JVM 堆内无丢失，full/delta/缩小/取消与 Rust 范围校验通过 |
| 输出体积 | 外部行不再重复完整 CP；context 保存有序 CP，SOURCE/JAR/platform 记录准确来源。来源/遮蔽/delta 专项与 256 JAR 读取通过 |
| `iopengw-semantic-preflight-20261001-013142` | 新产物 721.519 秒后仍 NATIVE_HEAP_EXHAUSTED；进程树峰值 1481064448，daemon 262717440 bytes，无产物/源码漂移、保旧 head。语义/差分仍未验收 |
| 反射 AST | 后续发现仍按整个文件解码；改为位置索引、64 节点缓存、惰性子节点、ID 队列。97.2 MB / 60000 节点在 64 MiB JVM 堆内无截断、取消通过，11 原反射专项通过；新 native/真实验收待执行 |
| OOM 定位 | 模型/二进制/扩展/恢复/反射/提取/缓存/汇总/写结果前预分配 context/phase 回执；待真实 native 再取证 |
| Builder | 类/构造器/方法、Default、命名/访问/toBuilder、泛型 owner、定制成员、删除失效；9 新 JVM 与 12 原 Lombok 用例通过，递归 DECLARED 仍保留 incomplete 但补实际 element 身份；Lombok builtin 3 |
| 独立参考 Builder | 冻结 Anatomist 真正执行 basic fixture；10 生成声明的签名/修饰符/参数/返回/no-body/unconfirmed 与原型相等。不是 52 能力或 iopengw 最终差分 |
| 崩溃隔离 | 副本 daemon 专项仍 SIGKILL；macOS AMFI 有签名错误，本地重新签名/换目录未解决。直接 original 与 workspace copy 的 C ABI 入口确实 SIGSEGV。仍保留 daemon 专项失败，不放宽断言 |

SuperBuilder、完整 TYPE_USE/DI、高级恢复、TEST-only 隐藏 MAIN、模块 overlay/并行/统一预算、Maven 完整矩阵、容量和最终参考差分/release 均继续待完成。新实现必须重新冻结跑完整门禁。


## SuperBuilder / TEST-only / 扩展容量继续实施（2026-10-01）

| 工作 | 当前结果 / 剩余 |
|---|---|
| S1 扩展归档 | Framework/注解/DI/XML/SOFA 改为位置索引、64 行缓存；217.9 MB / 60000 声明在 128 MiB 堆内通过。原真实 OOM 已推进至 1830 秒超时，完整容量未通过 |
| S1 CPU 定位 | 真实 MAIN 模型 JFR/线程栈证实 published 遍历复制大量不发布的完整类型树；改用 header 预选，专项及新 native/真实预检待回执 |
| S2 隐藏 MAIN | worker 10 显式 auxiliary/prerequisite；封存真实 MAIN 输入及 CP，前置编译/NoCode 而不发布 MAIN；Maven TEST-only、Rust/JVM 拒绝误发布、缓存损坏/上游失效/并行 SOURCE 等价已验证；daemon 待验收 |
| S4 SuperBuilder | 冻结参考无显式选项子集、泛型继承/抽象父类/Default/self/build/有限 CAPTURE；8 新 fixture通过，4 独立参考 fixture / 63 生产 JVM 声明形状相等，Lombok builtin 4。完整 TYPE_USE/DI/独立预算和生产完整矩阵仍缺 |
| S7 真实输入 | Anatomist MAIN 89.913 秒通过；MAIN+TEST 被提交内 1 个 GBK 文件与声明 UTF-8 不符阻断，参考报 hash 变化。保留原字节，产品补准确 SOURCE_ENCODING_INVALID。完整语义差分仍缺 |

下一步：冻结当前代码重建 native，验收 SuperBuilder 与 TEST-only daemon；重跑真实预检并完善超时 RSS/进程回执。S3 高级推断/反射、S5 overlay/并行/统一缓存预算、S6 完整 Maven/扩展失败矩阵、S7 独立语义差分与 S8 release 继续实施；不能把 167 fixture 或 MAIN 子集扫描当作全部 52 项完成。

补充当前产物验证：`auxiliary-native-build.log` 构建通过；`auxiliary-superbuilder-daemon.log` 的 SuperBuilder / TEST-only 两项真实 daemon 128.738 秒通过，包含错误实参、上游失效、删除与 delta/full。header 后续 12 框架 / 18 SPI / 所有归档专项通过。单 MAIN JVM 隔离 243135 Java-stage facts 与前轮数量相同；62.137 秒仅诊断取样，与并行检查重叠，不作为性能门禁。`native-crash-failure-20261001-100159` 捕获 worker PID 64888、实际启动参数和 SIGKILL；仍未到达 SIGSEGV gate，原因未确定，已通知用户。

编码校验进一步提前到语义捕获：逐个读取已封存 Java blob 检查 UTF-8，错误保留原始 hash/path/context；FILES/文本搜索继续可读这些原字节。避免为已确定的坏输入重复执行整套编译。该失败仍不等于全 scope 语义验收通过。

冻结 iopengw 最新全 scope 预检：`iopengw-semantic-preflight-20261001-100543` 在 40.606 秒明确 SOURCE_ENCODING_INVALID，命中上述 GBK TEST 文件及原始 SHA；未启动 worker/javac、进程树峰值 171229184 bytes、未发布，无源码/产物漂移。这证明错误定位与前置拒绝，仍不是全 scope 语义或容量通过。


## 并行模块缓存与 Maven 常量正确性（2026-10-01）

| 工作 / 回执 | 结果与边界 |
|---|---|
| `20261001-100811-78975` | 13/14 gate 通过，包括真实 daemon 崩溃隔离的 SIGSEGV、保旧 head 和进程回收。java-daemon 在发现下述 CP 语义问题后由 agent 主动中止，exit 124；无输入漂移，不能记成完整门禁通过 |
| Maven 版本调解反例 | 父模块 CP 常量为 1、消费模块 CP 常量为 2；原缓存错误得到消费常量 1，完整 SOURCE 得到 2。现要求父/消费有序 CP 的文件名与内容 SHA 一致，否则回退 SOURCE；不递归扩张已调解依赖 |
| 并行模块实现 | 按依赖就绪批次、同批独立父模块并行；消费等待父完成。共享解码目录/发布/准备/租约恢复加锁，环依赖保留 SOURCE。新增 8 用例涵盖真实重叠、三层泛型、重复 binary name 隔离、delta、损坏、隐藏 MAIN 与不同 CP |
| 正式 Java 回归 | `parallel-production-fixtures.log`：175 项全部通过。三层归档计数修正为 mid 消费 lib 一次、app 消费 lib/mid 两次，总计 3；事实与源码数量等价断言保留 |
| overlay 缓存 | 原型 8 新用例及 17 原模块用例通过后接入；格式 v3-original，扩展前只读快照保存原始 SOURCE 与 NoCode，消费后重新应用 Lombok。Getter/Builder/SuperBuilder/Data/Value/UtilityClass、配置变更/删除/损坏/隐藏 MAIN 与 generated possible 均检查；正式回归和 native/daemon 待执行 |

本轮尚未出现新的异常 SIGKILL。之前 PID 64888 的失败回执保留；后续真实 daemon SIGSEGV 已通过一次，不推断自动杀进程的软件已永久放行。仍只把 E01/E02 标记完成，其他 50 项与三个最终门禁继续实施。


## 泛型返回目标约束（2026-10-01）

| 工作 | 证据 / 边界 |
|---|---|
| 原始扩展缓存正式回归 | `overlay-production-fixtures.log`：183 项全部通过；随后新增泛型目标约束，不把该回执当成后续代码已验证 |
| 返回约束实现 | 读取独立语法/声明中的变量初始化、赋值左值及方法返回类型；推断未绑定的方法形式参数、检查上下界/参数与返回转换，保留原 javac 诊断和 possible SOURCE/binary 恢复证据 |
| 无目标类型 | 保留原形式参数，不猜具体类型；强转不作为调用的 poly 目标。`T[]` 的返回目标推断按冻结参考现有限制保留未恢复 |
| 原型检查 | `target-return-proof/tests.log` 5 项与 `existing-bindings.log` 12 项通过，含参数/显式类型/边界冲突、位置、delta/full 与拒绝条件 |
| 独立参考差分 | 真正冻结 Anatomist fixture，6 个 SOURCE 调用位置/目标/heuristic→possible 规范化相等；参考数据库未暴露完整返回类型树，不宣称类型树协议相等。正式/native/daemon 待新回执 |

完整 invocation 目标、多态 lambda/SAM、raw 和混合继承仍继续实施；本节不代表 J02/J05/E06 全部完成。


本轮当前代码补充验证：`cache-target-current-fixtures.log` 的 188 项 JVM 回归全部通过；native 构建 `cache-target-return-native-build.log` 通过。`cache-target-daemon-20261001-110153/receipt.json` accepted=true，7 daemon 专项 414.885 秒通过，覆盖不同 Maven CP 常量、独立父模块并行/损坏/上游变化、Lombok 原始签名配置变更/删除/possible、返回目标约束、旧模块目录、SuperBuilder 与 TEST-only；随后真实 C ABI SIGSEGV daemon 22.910 秒通过。源码/产物漂移均为空。本轮无新的异常 SIGKILL；这份专项回执仍不替代完整 14 gate、真实容量和最终参考差分。


## 共享缓存 / Maven 补齐 / JSON 与事实索引（2026-10-01）

| 工作 | 本轮证据与边界 |
|---|---|
| S5 共享缓存 | 文件/模块共用进程锁与两目录外的文件锁；磁盘与任务 pin 各合计 256 MiB。双向淘汰、evicted pin 继续供真实 javac 消费/恢复、两 JVM 并发写、取消和符号链接防护，`shared-cache-production-tests.log` 4 项通过；跨目录废弃提交清理已检查 |
| S6 Maven | 外部 test-jar classifier、provided/runtime 可见性、物理路径不同于 artifactId、profile/settings/parent 摘要、nonce/entity/响应预算、真实 Maven 超时回收；`maven-expanded-production-tests.log` 13 项全部通过，未执行业务 lifecycle/处理器 |
| S1 元数据索引 | 每份事实归档附带最多 64 MiB 平坦磁盘索引，只保留分页偏移；耗尽后完整解码，不截断事实，scratch 计入并在关闭时清理。Java 8 / 32 MiB 堆消费 197574784 bytes / 6000 条事实，边界/取消/清理通过；128 MiB 框架归档专项通过 |
| S1 JSON 热点 | 既有真实模型 JFR 3776 个执行样本中 JSON parser.value 出现在 2644 个栈；普通字符串改为连续片段处理，保持转义与错误边界。`json-chunks-proof/golden.log`：10000 组新旧读写/字节变异、100 组流式编码相等；新增 Python 独立字符串字节 oracle，最终门禁待新回执 |
| 真实模型事实等价 | `fact-headers-real-proof/receipt.json`：完整 243135 条 Java-stage 事实的有序内容 SHA 相同，无扩展失败；单 JVM 512 MiB 的阶段结束约 59.96 → 51.38 → 38.06 秒，仅诊断取样，不能代替生产/容量门禁 |
| S7 最新 MAIN 预检 | `iopengw-main-semantic-preflight-20261001-111216/receipt.json`：1830.073 秒后由 harness 超时清理，进程树峰值 1079050240 bytes，无源码/产物漂移。Lombok off/XML on，与参考配置不同；未通过语义或容量验收，不记录成外部软件杀进程 |

`shared-headers-production-fixtures.log` 的 192 Java fixture 通过后继续补了 FactExtractor 索引转发与 JSON 片段优化；该日志不冒充最终代码完整门禁。需重新冻结执行完整 native/daemon 门禁。仍只完成 E01/E02，其他 50 项、完整 iopengw 差分和 release 继续待验收。简单跳过未发布源码 AST 的原型会丢失隐藏 Lombok 调用恢复的 external_symbol，已拒绝合入。


## XML 增量所有权与字段反例修复（2026-10-01）

| 工作 | 证据与边界 |
|---|---|
| 上轮完整门禁 | `20261001-120717-29721`：13/14 gate 通过；30 daemon 用例中 29 通过，Maven scope/XML 删除报 `CHANGED_SOURCE_OWNER_NOT_REPLACED`。没有输入漂移；真实 SIGSEGV 隔离门禁通过，不把该回执记为完整通过 |
| XML 根因与修复 | `ContextResult.reduced` 同时表示文件缩减和模块 NoCode；后者仍执行项目资源阶段。旧逻辑却跳过资源 owner，导致删除残留。现在仅跳过未分析源码 owner，受影响资源 owner 保守整组替换，覆盖字节变化但事实相同、删除和 producer 禁用；Rust 发布校验不放宽 |
| XML 专项 | `xml-owner-red.log` 在旧代码失败；`xml-final-owner-tests.log` 10 项通过，包括真实模块 NoCode、仅附加注释、独立关闭 Spring/SOFA、删除及 delta/full 等价。产品 daemon 用例新增同语义字节变更检查 |
| 字段写入修复 | 穿过多层括号识别赋值、复合赋值和四类 ++/--；数组元素写入仍只读取数组字段和索引。过滤 javac 的 this/super、数组 length 伪字段及无源码位置的枚举伪引用，真实 length 字段保留 |
| 字段专项 | `field-parentheses-red.log` / `field-pseudo-red.log` 复现旧缺陷；`fields-final-semantic-tests.log` 10 项通过，含 SOURCE/JAR、静态导入、继承、枚举、delta/full。新增两个 daemon 用例，待当前完整门禁验收 |
| 冻结参考 | `field-access-final-production-proof/receipt.json`：8 次括号写入计数对齐，产品语言预期通过；冻结参考 ++/-- 只投影 writes，产品输出 read_write，因此 raw_fact_equivalence=false，不能宣称字段原始事实完全相等 |

新完整门禁输出为 `xml-fields-final-full-gate.log`；执行结束前仍标记待验收。仍只完成 E01/E02，50 项能力、iopengw 完整差分、容量和 release 不据专项自动完成。当前未发现非预期进程被杀；harness 超时清理与故障 fixture 的主动终止单独记录。


### 冻结 iopengw Maven 输入的独立参考对比（2026-10-01）

| 比较 | 结果与边界 |
|---|---|
| MAIN Java inventory | 冻结参考 `file_cache` 与外部 manifest 的 2857 个文件路径及源码 SHA 全部相同；`target/java-alignment/iopengw-main-input-comparison.json` |
| MAIN 模块输入 | 实际调用冻结 Anatomist JAR 的 `JavacMavenInputs.resolve`，SDKMAN Java 8 Maven worker 在生命周期前停止。36 个有源码模块的有序外部 JAR/内容 SHA、reactor 图、穿插依赖顺序全部相同；`iopengw-main-reference-inputs/comparison.json` |
| MAIN / TEST 完整 reactor | 再执行冻结参考的 MAIN/TEST Maven 输入导出：40 个物理模块、80 个 scope 行、938 个外部 archive。按物理 basedir 映射身份（根模块参考 `.` 对应产品 `iopengw-parent`），上述三项比较全相同；`iopengw-main-reference-inputs/all-comparison.json` |
| 范围 | 只证明依赖输入；不代表完整 Maven 能力矩阵、Java 语义、XML、容量或最终差分通过。仅按全 manifest 求 MAIN CP 并集会错误包含无 MAIN 源码的 `app/test`；有效 MAIN CP 实际为 781 个，已纠正比较方法，不修改依赖调解输出 |

当前完整门禁 `20261001-125534-87997` 的 195 Java fixture 和 native/IPC 已通过，daemon 尚在执行。真实 MAIN 候选 `iopengw-main-model-preflight-20261001-130642` 使用 Lombok model、无 XML inventory，与回归并行，因此不作为独占性能比较；发布和语义结果尚待回执。


## XML/字段修复复验与空资源扫描优化（2026-10-01）

| 工作 / 回执 | 结果与边界 |
|---|---|
| 完整门禁 `20261001-125534-87997` | 13/14 gate；195 Java fixture、两个新增字段 daemon、真实 SIGSEGV 隔离通过。32 daemon 中 31 通过；XML 用例错误要求字节变化后 core `context_digest` 不变，保留失败回执 |
| XML 测试校正 | 比较 XML resource owner 的语义事实，同时保留字节/provenance 摘要变化。`xml-fields-corrected-production-native.log`：真实生产用例 109.246 秒通过，覆盖注释/关闭 producer/删除/delta-full，不把补跑合称单次完整门禁 |
| 空输入/输出优化 | Spring/SOFA 无资源时直接返回；FactSink 零输出时仍校验事实数量预算，省去既有 ID 扫描。新增永久 `EmptyOutputProbe`，20 万条不可解码既有事实的 sink/Spring/SOFA 三模式通过，范围/超额拒绝及非空校验保留 |
| 真实模型等价 | `empty-resource-real-proof/receipt.json`：留存 app/core/service 编译模型，优化前后 243774 条 Java/project 事实的有序 SHA 一致，扩展失败为零。回归并行、单模型 JVM；不代表性能提升或生产容量通过 |
| 新冻结完整门禁 | `20261001-135517-16772`：14/14 gate 全部通过；195 Java fixture、32 Java daemon、真实 SIGSEGV 隔离通过，无输入漂移。程序返回 2 表示其余 50 项和 capacity/real-semantic-diff/release 尚未完整验收，不是本轮回归失败 |
| MAIN 真实预检 | `iopengw-main-model-preflight-20261001-130642`：1830.087 秒 harness 超时并主动清理，无源码/产物漂移；未发布，语义/差分/容量未通过。进程树峰值 1677197312 bytes 含 daemon，不冒充独占性能验收 |
| 模块缓存取证 | `iopengw-main-module-cp-guard.json`：36 个 MAIN 模块，331 对有源码上游/消费依赖的有序 CP 均不同，当前规则全部回退 SOURCE。留存 730908827 bytes / 2624 源模型含 4299 诊断；该模型不是上述 native 任务的全部模型，不能推断所有诊断原因。不得直接放宽 CP/完整性保护 |

下一步需验证消费 CP 下的签名和原始证据、完整 Maven/扩展反例，再做冻结真实项目语义差分、容量与 release。仍仅 E01/E02 完整验收，其他 50 项及最终门禁未完成。本轮没有新的非预期杀进程证据；harness 超时清理与故障 fixture 主动终止分开记录。


### 冻结真实 facade 模块差分取证（2026-10-01）

| 检查 | 结果 / 待实现 |
|---|---|
| 真实 native 发布 | `iopengw-facade-model-preflight-20261001-143444`：81 个 MAIN 源、336 个有序依赖 JAR，157.421 秒发布 16090 条事实，assessment=partial。进程树峰值 660406272 bytes，无产物/源码漂移；只验证一个无上游 reactor 源码依赖的模块，不是全项目容量/完整语义差分 |
| Builder 缺口 | 冻结参考有 5 个生产缺失的 Builder 类型；生产 java.lombok facet 明确 `builder_parameter_default_or_annotation`。原型允许普通声明注解后，其中 2 个恢复，另 3 个仍受 TYPE_USE 拒绝；原型未接入生产，不能记为已修复 |
| 注解基本类型身份 | `@NotBlank int/boolean` 的生成 setter/constructor 参数 key 含注解展示文本，违反参数擦除身份约定；需修正 primitive erasure，同时保留类型树中的注解证据 |
| 枚举隐式声明 | 此模块的参考库存有 20 个 `values/valueOf` 方法，当前 SOURCE 声明库存未发布这些隐式方法；需补生成身份和无直接源码位置的证据，不伪造方法体 |
| 调用点约定 | 生产 751、参考 398 个调用点，差值 353 等于该模块 ENUM_CONSTANT 数量；仍需按位置/目标核对枚举初始化调用。不能单凭计数删掉合法调用来制造等价 |
| 表示差异 | 参考 varargs 使用 `...`，产品身份使用数组 `[]`；声明比较统一该表示与嵌套名称/构造器名称。其余差异保留，`builder-annotation-real-proof/receipt.json` 仍 accepted=false，完整差分未通过 |

本轮 production 的最终冻结回执仍为 `20261001-135517-16772`，14/14 gate 通过。新发现的 Builder/TYPE_USE、注解参数身份和枚举隐式声明尚待实现；原型与诊断文件不改变已验收生产源码。当前没有新的异常杀进程。


## 当前实施续篇（2026-10-01，执行中）

| 工作 | 进度 |
|---|---|
| A0 参考与复用 | 冻结最新 dff2e395；新增 `java-reference-sources.json` 记录直接复制与改编来源；MAIN 验收范围入矩阵 |
| A1 声明/注解 | primitive key、Java 8 written TYPE_USE、枚举隐式成员和合成调用过滤已实现；缺失写法注解禁止 NoCode，新增正反例 |
| A3 Lombok | 真实 facade 缺失从 126 降至 3；最后 3 项显式/默认选项问题已修复，待新 native 差分 |
| A4 Maven | 导出编排迁到 Rust；包内入口不依赖 Python；12 调解 fixture、显式导出与 4 进程/协议单测通过；真实 MAIN manifest 比较执行中 |
| A5 容量 | 辅助源码模型省去未发布方法体输出；完整输出与精简输出事实/增量等价回归通过。跨 CP 的模块 NoCode 仍保守 SOURCE |
| A6 真实验收 | 最新 Anatomist MAIN 索引完成；旧候选 facade 可发布但评估 partial。完整 MAIN、逐项差分、3 冷/3 热容量及最终 release 门禁尚未完成 |

当前全部 52 项完成门槛不变；专项修复或部分发布不自动勾选完整能力。


### MAIN 容量失败与证据共用修复（2026-10-01）

| 工作 | 实际结果 / 边界 |
|---|---|
| release MAIN `iopengw-main-release-preflight-20261001-181019` | 1774.129 秒时失败：1,819,751 条 / 4,294,965,934 bytes，下一条超过 4 GiB；RSS 峰值 1,659,961,344 bytes，1 worker/1 javac，无源码/产物漂移。正常预算拒绝及任务清理，不是异常杀进程 |
| 容量根因取证 | 留存单模块 588,941,753 bytes 事实含大量辅助局部记录，调用类型图在调用点/绑定/目标重复。仅按新布局投影为 423,792,208 bytes（约 72%）；这是离线估算，不是生产容量通过 |
| 已实现 | 删除未引用的辅助局部外部符号，保留类型/成员头及完整 javac 输入；CallSite 保存完整类型证据，java.binding@3 与 resolved CallTarget 指向它，possible 仍有自身恢复/反射证据；Rust 流式和完整发布均校验共享 owner |
| 方法引用 | 补 METHOD_REF/SAM/类型/模式/范围；数组构造 intrinsic，无伪造调用。独立最新 Anatomist fixture 确认 receiver qualifier 调用归属回调，产品按证据纠正，补 callback_creation 时机属性 |
| 真实 facade JVM 差分 | 核心声明双方 2177，398 调用的位置及目标差异均为 0；不包含完整 owner/type/框架/native/容量验收 |
| 待执行 | 最终代码完整 fixture/native/daemon；相同 4 GiB/1800 秒 MAIN，逐项语义差分、3 冷/3 热容量与 release。仅 E01/E02 完成标记保持不变 |


### 30 分钟超时后的容量修复（2026-10-01）

| 工作 | 证据 / 边界 |
|---|---|
| `iopengw-main-release-preflight-20261001-191838` | 1811.665 秒返回 TIMEOUT，最后取样 29 个上下文 / 3.25 GB；未发布。RSS 峰值 1666646016 bytes，1 worker/1 javac，无源码/产物漂移；自身任务截止清理，没有异常杀进程 |
| 项目阶段更正 | `SpringInjection` 是项目阶段，即使没有 XML 也必须运行；不能据空 XML 跳过整段。独立原型的空阶段断言失败揭示此约束，未按错误断言修改生产行为 |
| 已接入修复 | FactHeaders 的编码记录复制路径跨 core/拼接/项目视图保留顺序与重复，避免任务累积/项目暂存的反复 JSON 解码编码；逐条 byte/count/cancel/close/seal 校验保留，不嵌套两归档锁 |
| 框架暂存模型 | Spring DI 保留 declaration/external/annotation_types/source，省去框架不读取的 syntax/observations/diagnostics；仅无输入的 XML 内置回调跳过；custom/legacy 默认保留完整模型与空 inventory 回调 |
| 反例 | 错误数组方法引用仍保留 intrinsic 导航，不再报告绑定成功；嵌套 receiver lambda 保持 deferred，receiver 求值单独标 callback_creation |
| 专项回归 | `raw-transfer-archive-tests.log`：约 198 MB / 6000 事实、32 MiB 堆，原始字节 SHA 相等、视图顺序/追加/预算/取消通过；Java DI/legacy/full model/空 XML 阶段通过。`raw-transfer-framework-semantic-tests.log`：61 项通过 |
| 最终验证 | `20261001-201312-42970`：14/14 gate、210 JVM fixture、32 Java daemon 和真实 SIGSEGV 隔离通过，无输入漂移；不含其后字段 owner、注解路径与 JSON 读取变更 |

50 项能力与真实语义差分、3 冷/3 热容量及 release 完成标记仍待实际验收。

### 字段 owner 生产验收及下一轮容量修复（2026-10-01）

| 工作 | 实际进度 |
|---|---|
| 字段 owner | 生产 JVM/native/release daemon 专项通过；真实 facade 的 2177 核心声明、398 调用位置/目标/递归 owner 与参考相等（JVM 证据） |
| MAIN 再验 | `iopengw-main-release-preflight-20261001-210607`：1811.913 秒 TIMEOUT、未发布，无源码/产物漂移，RSS 1694138368 bytes；自身截止清理，无异常杀进程 |
| 注解预算对齐 | 复用 Anatomist 的路径缓存、selector、来源单元预算和计数器；真实 facade 消除 142 条误报预算缺口，保留真实 javac/Lombok 缺口。JVM fixture 与预算/淘汰/隔离反例已通过 |
| 事实读取 | 只解码需要的元数据，仍严格校验完整 JSON；1500 独立 oracle、32 MiB / 197 MB 归档及真实模块输出 SHA 通过 |
| 待验收 | 新代码完整 JVM/native/daemon、同一 4 GiB/1800 秒 MAIN、全量独立语义差分与 3 冷/3 热容量、最终 release；不据局部提速或专项修复勾选 50 项完整能力 |


### ASM 元数据、反射状态与 SOURCE 导航继续对齐（2026-10-01）

| 工作 | 当前证据 |
|---|---|
| 最近 MAIN | `224025`：1808.879 秒 TIMEOUT、未发布；RSS 1628356608 bytes；源码/产物无漂移，正常截止清理，无异常杀进程 |
| 二进制完整头 | 取消 2048 成员截断，加权有界缓存、大类不缓存、嵌套名 header 校验；冻结 Anatomist reader 5 类 / 2118 成员注解/默认值/类型路径/参数元数据零差异。XML 查询总步骤预算继续保守降级 |
| 反射 | switch 默认覆盖与 fallthrough 合并、常量/句柄来源归一；13 项 JVM fixture/full/delta 通过，补入 native probe |
| Rust 导航 | source_origin 五元组 + 唯一已发布 SOURCE 声明；保持 consumer world/target，二进制/隐藏前置/歧义拒绝。70 Rust 单测与真实 daemon 专项通过 |
| 容量取证 | task-owned 阶段时间日志：4096 行 / 1 MiB、并发/单调/Unicode/清理/日志失败隔离通过；下一轮 MAIN 保存日志，预算不增加 |
| 尚未完成 | poly 调用/方法引用目标约束及 modeled override 的剩余差距；本轮修改后的完整 native/daemon、真实 MAIN 发布和全语义差分、3 冷/3 热容量、release。专项通过不勾选其余 50 项 |

本轮最终专项：215 JVM fixture / 23 组、真实 native 新案例、包内 7 daemon（96.575 秒）、40 模块 Rust Maven MAIN 输入一致均通过。待下一轮 MAIN 阶段日志定位与发布；不替代完整 32 项统一门禁、真实语义差分、容量和 release。

### 安全拦截恢复及 modeled override（2026-10-02）

| 工作 | 当前进度 |
|---|---|
| MAIN 安全拦截 | `000714` 的 worker PID 99842 被 SIGKILL，14.213 秒、未发布；用户确认恢复。release core 8.251 秒复测通过 |
| modeled override | 已改编参考遍历/代入/访问/非法覆盖规则，补 SOURCE/JAR 泛型/raw、协变返回、跨包与 delta/full；16 个绑定 fixture 通过 |
| 独立参考 | 4 类 fixture 覆盖全部 15 条参考边；三类零差异，JAR 另两条 raw 覆盖经 SDK8 编译确认合法，明确记录参考缺失 |
| 剩余 | 新增覆盖的完整 JVM/native/daemon；poly invocation/lambda/SAM 的实际实现差距；完整 MAIN 发布、独立语义差分、3 冷/3 热容量和 release |

专项不改变只有 E01/E02 完整验收的状态。

缓存反例补充：首次新覆盖完整 JVM 门禁在 Data/Value/Builder/SuperBuilder 模块复用发现额外外部符号；`ModuleSignatures` 不再保存/恢复 modeled-override-v1 产物。`modeled-overrides-overlay-tests-fixed.log` 的 8 项及 `modeled-overrides-restored-source-tests.log` 的恢复父类/删除旧覆盖测试通过；完整门禁正在重新执行，原失败日志保留。

`modeled-overrides-jvm-full-gate-fixed.log`：缓存修复后的完整 23 组 / 220 项 JVM fixture 全部通过，suite 合计 513.766 秒；归档/FD/原始 ASM 独立对照和各预算探针通过。原失败日志保留。新 native/daemon 与 MAIN 仍待执行。

本轮新产物：`modeled-overrides-release-package.log` 构建完成且 19 项 SHA 通过；native SHA c320b5cb5545dd8731b532c1009dcd6916722277938ce8bb5f36fad493f96191，bridge SHA c85007279961bbb0254c59ef0d023c23f72cd3cce7dc129297904cdd274773ff。`modeled-overrides-final-native-probe.log` 的 C ABI/SDK8、SOURCE/JAR 覆盖和模块缓存新案例、主动 SIGSEGV/取消/处理器 trap/完整性通过；包内新增 daemon 覆盖关系/派发/full-delta 测试通过（10.565 秒）。40 模块 Rust Maven MAIN 输入一致，5 项外部控制及三处 native/bridge SHA 验证通过。MAIN 同预算预检已启动，完整 33 个 daemon 和全能力/语义/容量门禁尚未完成。

MAIN `iopengw-main-release-preflight-20261002-010213`：1808.100 秒 TIMEOUT、未发布，36 个模块于阶段时间 1723.505 秒完成，进入项目阶段后截止。core/service 的 java_extensions 412.114 秒，javac/model 20.520 秒、事实提取 29.417 秒。峰值 RSS 1349287936 bytes；输入/产物无漂移，无安全拦截或异常杀进程。阶段日志与线程样本保留；约 98 MB 的完成后 core/service 声明模型已捕获，不能冒充完整 Java 阶段模型或容量通过。


### 扩展读取容量修复（2026-10-02）

已接入调用头筛选、类型/world 有界路由索引、FrameworkAnalysis 惰性声明索引与保留元数据的不可变视图；新增每个内置扩展的阶段日志。未知记录/重开/配额用满均保守回退，CAF1 字节和 1800 秒/4 GiB/512 MiB 预算不变。新代码最终 fixture/native/daemon 和真实 MAIN 尚待验收；不据修复勾选能力。

扩展读取的最终 23 组 / 220 项 JVM、真实 native 与包内 2 项 daemon 通过；40 模块 Maven MAIN 输入等价。native/bridge 三处 SHA 相同。`extension-routing-main-release.log` 已启动同预算 MAIN；完整 33 daemon、真实发布/语义/容量/release 和 poly 推断差距仍待处理，不改变其余 50 项完整验收状态。

MAIN 022345：36 模块由 1723.505 秒降至 1096.603 秒、core/service Java 扩展由 412.114 秒降至 156.990 秒；1733.294 秒完成项目扩展并进入 result_write，1807.578 秒 TIMEOUT 未发布。正常截止清理，无安全拦截；峰值 RSS 1351942144 bytes，输入/产物无漂移。继续候选 ID 摘要筛选和严格编码输出修复；最终回归/native/MAIN 及完整语义/容量/release 待验收。


033143 MAIN 首次发布：原预算 1603.791 秒、树 RSS 1432829952，调用点位置 61527/61527 相等；CLI 随后被 1000 条覆盖原因撑爆 32 KiB 文本终态，Rust 证据明细投影修复已过工作区/clippy/14 daemon/192 文件 Java 回归。真实 CLI、匿名类归一后的完整调用/声明/类型/扩展差分、poly/SAM 实现、3 冷/3 热独占容量及最终 release 尚待；保持其余 50 项未全验收。原 receipt 与 catalog 发布证明分别保留，详情见 docs/implementation/java-validation.md。

续验：旧原生包完整 35 daemon / 2066.102 秒通过、产物无漂移。匿名类统一后，全部 61527 调用点及递归归属相等；16 对真实 Lombok 命名差异已修复，17 个生成方法独立模型对照相等。继承 6 条、poly/lambda/调用目标 6 类 / 32 条、字段/源码注解 4 类 JVM 独立对照通过。已补枚举 case 读取和 builder 注解源码映射，保留 javac 原绑定及有效 JDK 常量。完整泛型 LUB/复杂块完成性/数组返回、当前 native/daemon/真实 MAIN 差分、独占容量及 release 仍待；不勾选其余 50 项。当前源码全 JVM 门禁正在执行，受限环境拒绝 ps，不能替代容量采样。


深层类型续验：已纠正 16 层泛型截断但根 complete=true 的真实缺口，5/20/40 层与冻结参考一致；7 个结构化字段类型逐节点投影相等。新增未知/预算传播、去重告警/文件 partial、40 层 TYPE_USE、健康源码隔离及 full/delta；真实循环不冒充截断，递归 Builder 已修复并通过。上版 JVM 230 项中 229 通过，1 项最后 ps 孤儿检查被当前沙箱拒绝；新完整门禁正在执行。类型树仍是 48 层 / 4096 节点有界表示，不等于参考的全部平面类型图。新 native/daemon/真实 MAIN/完整差分/独占容量/release 待验证，50 项完整验收状态不变。


深层类型最终 JVM：231 项中 230 通过，1 项 ps 孤儿检查仍受限；归档边界测试更新后整套归档通过，约 197 MB 原始输出、约 218 MB 扩展及各预算/取消/不可变模型探针保持。没有跳过孤儿检查，原失败日志保留；产品源码无漂移，新原生包构建中。

权限恢复后：原 231 项连同归档/Sessions 重测全部通过；上版真实 C ABI、17 专项、Rust IPC 与组合 daemon 已通过。094716 的 MAIN 在 1023.735 秒因 core/service 原生堆耗尽失败，未发布，非安全软件杀进程。已改只读类型共享与任务归档快照；真实模型逐行值相等，JVM 单次快照存活堆约 349 MB → 274 MB，仍需新 native/MAIN。生成签名类型引用补齐，36/36 独立参考关系及源码区间一致；不替代全部类型/框架差分。其余 50 项、完整 daemon、同预算 MAIN、独占容量和 release 仍待验收。

### 正确性优先与可靠回执（2026-10-02）

| 项目 | 当前证据 / 决定 |
|---|---|
| 新产物回归 | 232 JVM fixture、18 native 专项、完整 C ABI、Rust IPC、包内组合 daemon、12 Maven 调解与 40 模块 MAIN 输入导出均通过；详见 type-sharing-runtime-receipt.json，未据此关闭全能力验收 |
| 105400 MAIN 中断 | 36 个模块完成 file_cache，最后阶段为 1711.113 秒的项目 Spring DI；整个执行链消失，catalog 仍 running、没有 commit/head、没有结束回执。原因未知，不能记作 TIMEOUT 或安全拦截；现场及配置恢复记录见 interruption-observation.json |
| 正确性优先 | 先完整发布和独立语义差分，再补能力；性能放最后。任务保护默认 3600 秒，不是性能达标线。用户确认沿用 2 GiB 内存门槛，3 冷/3 热实测耗时，不新增时间达标线 |
| 正式验收脚本 | tests/java_real_acceptance.py：配置锁/写前备份/条件恢复、PID+启动身份、持续原子回执、阶段日志、发布后 catalog/查询核验；下一次启动能恢复遗留运行，不覆盖用户改动、不杀仍存活的旧任务 |
| 回归 | tests/java_real_acceptance_tests.py 的 9 项通过，含真实主动 SIGKILL、配置冲突、损坏备份、并发、PID 复用与仍存活子进程拒绝恢复；接入统一 gate |
| 剩余 | 当前产物 MAIN 完整发布；SOURCE 参数类型引用归属、poly 输出泛型约束、平面类型图；全范围 XML/TEST、52 项独立差分、最终容量/release 仍未完成 |

### MAIN 发布与实际语义差分（2026-10-02，122353）

| 验证项 | 实际结果 |
|---|---|
| 冻结输入 | iopengw 267c7ced4993aa88613f8f17a8099943fec98159；5039 个已提交文件校验，MAIN 2857 Java / 36 非空上下文，40 Maven 模块；原业务 checkout 未修改 |
| 发布 | rev_58a30c16dc4f416f98e4da99fd4e530f；1493967 事实；CLI 0，catalog commit/head 一致。2262.382 秒为非独占正确性运行；树 RSS 1259667456 bytes、daemon 364134400 bytes；无输入/产物漂移或异常 signal |
| 查询检查 | 原验收脚本缺 selector，得到 SELECTOR_REQUIRED；原 receipt 保留失败。补充只读 daemon 使用已发布 CLASS selector 和显式 revision，查询 0；证据 publication-query-proof.json。脚本已修正并区分 publication_accepted 与 semantic_index_accepted；partial 不能冒充完整语义 |
| 核心差分 | 声明 33792/33792、调用点和递归归属 61527/61527 零差异；目标 59500/61511，缺 2261 / 多 250，其中 228 是同目标状态不同；缺失主要为 possible 2175、resolved 86 |
| 关系诊断投影 | implements 1025、inherits 1253、overrides 6773、writes 2675 均零差异；reads 缺 4 个 binary 枚举 case / 多 2 个 ++ 读取（产品保留 read_write）；SOURCE 注解多 5 个 Builder.Default；类型引用缺 18108 / 多 52862。此投影只比较核心声明端点，不是完整 occurrence/类型/框架证明 |
| 本轮已合入源码 | SOURCE 参数类型引用保留 callable 归属；泛型 lambda/member-reference 输出约束与 unbound 接收者、候选隔离；按冻结参考补同类型/null 三元分支、字符串拼接和比较/布尔表达式；Builder.Default 不投影到生成存储字段。该 MAIN 的产物早于本轮源码合入，不能把修复后的 fixture 结果当成真实项目差分已消除 |
| 局部证据 | 7 项 functional/source-owner、4 项 expression JVM 回归通过；独立冻结 JAR 多重集 19/19、4/4、10/10、48/48，含状态/源码区间/重复；加入正式 reference gate。新版 SDKMAN native 已构建，完整 JVM/native/daemon 回归进行中 |
| 验收记账 | 恢复回归 12 项、gate 记账 4 项通过；ps 查询权限失败拒绝自动恢复；记录真正加载的 release 同级 lib/java 产物。52 项仍仅 E01/E02 完整通过；平面类型图、全范围 TEST/XML、完整语义差分、独占 3 冷/3 热及 release 未关闭 |

完整证据目录：target/java-alignment/evidence/iopengw-correctness-20261002-122353-28341。原失败及修正后的日志分别保留。


### 生成重载与静态导入续验（2026-10-02）

| 项目 | 状态 / 证据 |
|---|---|
| 上一批完成验证 | functional-expression-full-jvm-receipt.json：28 组 / 243 项通过、源码无漂移；21 项 native 专项、完整 C ABI、SDK8/AP trap/取消/完整性和冻结独立 native 对照通过。真实 MAIN 尚未包含这些修复 |
| 真实缺口复现 | MessageEnvelope 源码已有 getExtraContent(String)，导致 javac 将生成 getExtraContent() 报 inapplicable；真实缺失分类中该 getter 168 条属于此原因。fixture 修复前失败、修复后通过，不代表 168 条已全部消除 |
| 已合入修复 | javac ambiguous/inapplicable veto 延后至候选确认之后，仅真实生成声明或经过检查的 modeled input 可恢复；原始诊断和 possible/evidence-incomplete 保留。静态导入只按 javac_selected_declaration 及源码/JAR static 标志选择 owner；正常访问、参数、泛型检查继续执行 |
| 冻结独立对照 | functional-reference-20261002-133119-44851：19/19、4/4、7/7、2/2、10/10、48/48 调用/参数关系一致；静态导入全部 reads 5/5 一致，Builder.Default SOURCE 注解 1/1。原失败 binary-enum-reference 证据保留；额外 binary enum case 推断没有合入 |
| 回归与剩余 | 隔离绑定 16 项、functional/source-owner 9 项、静态导入 3 项通过；新版 native、产品包和实际 daemon 正在验证。仍须平面类型图、全范围 TEST/XML、完整语义差分、3 冷/3 热独占 2 GiB 容量和 release。52 项仅 E01/E02 完整通过 |


### 当前修复完成验证与 Maven selection（2026-10-02）

| 项目 | 实际证据 |
|---|---|
| 当前 Java 回归 | candidate-veto-full-jvm-receipt.json：29 组 / 248 项全部通过、源码无漂移；candidate-veto-native-receipt.json：26 项通过、产物无漂移；完整 C ABI/取消/AP trap/SIGSEGV 隔离与 Rust IPC 通过 |
| 冻结 native 对照 | functional-reference-20261002-133735-50048：19/19、4/4、7/7、2/2、10/10、48/48 一致，静态导入 reads 5/5、Builder.Default 注解 1/1；不替代真实全项目语义对照 |
| Maven 缺口与修复 | 只选 component/common 时仍独立分析未选中 migration，缺 CP 触发 DIAGNOSTIC_BUDGET，旧失败回执保留。Rust 只调度 selected 分析根，必要依赖源码仍封存并进入所选消费者；不靠提高诊断预算修复 |
| 当前 Rust / daemon | 75 工作区测试、Clippy、fmt、release 编译通过；Maven 未选中坏模块反例、依赖 getter/返回类型变化及删除 full/delta、TEST-only 依赖与 MAIN/TEST/XML 三项 daemon 共 43.560 秒通过 |
| 真实选定模块 | iopengw-common-selection-fixed-20261002：115.130 秒发布；CLI 0、查询 0、源码/产物无漂移、无异常 signal、配置恢复；独立调用差分正在执行。该模块成功不代表整 reactor 成功 |
| 产品包与输入 | 19 项 package SHA 校验；新 native SHA 4267e9ad8d896147a1bd9b5e3546b56f8bd7481f4a7898d50443e2198fd499a1，三处相同；新 Maven MAIN 输入 40 模块、5 外部控制/40 项目控制校验，冻结 MAIN 投影零差异 |
| 未完成 | 平面类型图、SOURCE/回调全部类型关系、剩余高级绑定与完整框架差分、全 reactor MAIN/TEST/XML、新完整独立语义门禁、3 冷/3 热独占 2 GiB 及 release。52 项仍仅 E01/E02 完整通过 |

真实 component/common 独立对照：声明 3862/3862、调用点及归属 5206/5206 零差异；目标由旧产物 5153/5206、缺 62 / 多 9，变为当前 5174/5206、缺 37 / 多 5。5 对相同目标含 resolved/possible 状态差异；剩余含泛型回调/SAM/预算，不将模块发布或核心投影当作完整语义通过。详见 maven-selection-common-diff-receipt.json 和 missing-call-classification.json。


### 泛型方法引用、静态上下文与构造器（2026-10-02 续验）

| 项目 | 证据 / 状态 |
|---|---|
| 已合入 | SAM 第一参数投影泛型接收者；保留静态/实例歧义与原 javac 诊断；invalid.mref 分类；调用区间结束位置改为不包含；静态方法内的实例方法引用不再误当直接实例调用 |
| 构造器 | 泛型构造器从已检查的 SAM 结果投影实际类型并检查类边界；被 Lombok 替换的构造器引用进入外层候选输入；上下文检查失败时，原表达式类型不作为已验证的方法结果 |
| JVM | generic-boundary-full-jvm-20261002-142053/receipt.json：31 组 / 253 项全部通过、源码/产物无漂移；该完整回归早于静态/构造器后续修复。当前 constructor-reference-final-jvm.receipt.json：54 项绑定/泛型/构造器/边界/表达式专项全部通过、无漂移 |
| native | native-fixtures-20261002-144415/receipt.json：当前 54 项通过；新增 tests/java_native_fixture_alignment.py 正式 gate，覆盖完整/增量及缓存。完整 C ABI/SDK8/AP trap/主动 SIGSEGV 隔离等上版探针通过，不记作异常杀进程 |
| 独立对照 | functional-reference-20261002-144415-48704：10 组全部一致，含 15/15 泛型引用、10/10 诊断边界、9/9 静态引用、20/20 构造器正反例；状态、源码位置、多重性一致。当前组合 release daemon 的发布、查询、增量/full 用例 14.919 秒通过 |
| 真实模块 | iopengw-common-constructor-reference-20261002：106.465 秒，发布/查询 0，输入/产物无漂移、配置恢复、无异常 signal。声明 3862/3862、调用点及递归归属 5206/5206 零差异；目标 5202/5206，缺 4 / 多 0。未把局部核心对照记作完整语义通过 |
| 正在隔离验证 | 3 个 collector 缺口：无上界 TYPEVAR 的转换在原值上重复递归；已试验未知返回和参数通配符捕获，54 项旧绑定回归通过。另一个显式泛型错误实参样本，参考仍保留外层唯一 collect 声明的 possible，而产品拒绝；13/14 原差分保留，尚未关闭外层声明分类差异 |
| 仍待 | diamond 参数目标类型（剩余一个 setter）；collector 修复的正式/native/生产验证；平面类型图、SOURCE/回调全部类型关系、全 reactor MAIN/TEST/XML、全部框架/语义差分、3 冷/3 热独占 2 GiB 和 release。52 项仍仅 E01/E02 完整通过 |

旧脚本执行期间更新了脚本本身，曾导致末尾 shell EOF；失败日志保留，随后用固定脚本列表的完整 JVM runner 重新执行并通过。新增 daemon fixture 最初将 Map$Entry 写成 Map.Entry，保留原失败后已纠正并实际重跑通过。均不归因为安全软件或异常进程被杀。


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


### 编码隔离的产品链路验证（2026-10-02）

| 项目 | 实际结果 |
|---|---|
| Rust 输入发现 | 原 guard 在 worker 前拒绝非 UTF-8；已移除该文本解码，捕获仍校验原始字节/hash/语言/发布角色。Java 编译器桥拥有单文件编码诊断；不是重新编码输入 |
| Rust 回归 | encoding-isolation-rust-tests.log：75 workspace 测试通过；fmt、Clippy 无告警，release 构建通过 |
| 当前 Java native | native-fixtures-20261002-164510：76 项通过；type-reference-20261002-164509-73153：10 组独立 native 类型差分通过；type-policy-encoding-native-cabi.log：完整 C ABI/SDK8/缓存/AP trap/取消/主动 SIGSEGV 隔离通过 |
| 实际 release daemon | encoding-isolation-release-daemon-fixed.log：2 项 / 15.965 秒通过；编码隔离/strict 保留旧 head/修复增量/full，与 SOURCE 类型查询/删除一起通过 |
| 原失败保留 | 全 reactor 初次仍被 Rust guard 阻止，未启动 worker、没有被杀；修复 guard 的源码变化使 source-types-full-jvm-20261002-164510 正确拒绝漂移。该 113 项执行不记为完整回归通过；新冻结全量回归重跑中 |
| 真实扫描 | iopengw-main-test-xml-rust-encoding-fixed-20261002 已进入 javac，不再停在 Rust 输入发现；完成/发布/独立差分按最终 receipt 记录 |
| 新独立诊断 | common-source-types-occurrence-v1.json：旧产物类型关系 4454/4046、缺 0 / 多 408；匿名继承和接口区间另有 13/8 对差异，参考定位匿名类体。target 隔离修正的匿名层级样本 7/7 一致；尚未合入或验证新 native |
| 完成边界 | 未关闭平面类型图、全项目完整语义/扩展、独占容量或 release；52 项仍只有 E01/E02 完整验收 |


### 当前固定产物完整回归（2026-10-02）

| 项目 | 证据 |
|---|---|
| JVM | source-types-full-jvm-20261002-165453/receipt.json：37 组、283 项、683.357 秒，all_ok=true，源码/产物无漂移，已排除误导入重复用例 |
| release daemon | type-policy-encoding-full-release-daemon.log：40 项、553.504 秒全部通过，含真实 native/SDK8、Maven MAIN/TEST/标签/依赖失效、框架/XML、strict、失败保留旧 head、查询和 full/delta |
| 平面图隔离原型 | type-graph-staging：Java 8 编译；合法循环/前向边/-1 缺省、悬空/小数/负引用拒绝、不可变与 1025 节点浅 JSON 往返通过；type-graph-staged-frozen-reference-corrected.log：20 根、116 节点和 80 泛型层与冻结 JAR 内 javac-worker 一致 |
| 原型边界 | 尚未接入编译器桥/模型归档/事实布局/缓存/消费者；当前产品仍是 compiler7/model6 和 48/4096 类型树。独立原型不关闭 J01/J02，不代表完整 TYPE_USE/捕获/绑定/性能通过 |
| 全范围产品 | iopengw-main-test-xml-rust-encoding-fixed-20261002 仍在进行；不得将前面 fixture 或原型结果写作真实项目完成 |


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

## 真实 TEST 发布与剩余绑定差异（2026-10-02）

| 证据 | 结论 |
|---|---|
| `core-service-test-overlay-compatible-20261002/receipt.json` | TEST 实际 native/daemon 发布查询通过：revision `rev_90928c2238164d70abcf7fce3a765a37`，191796 事实，1090.166 秒。非完整语义验收；完整进程树峰值 712769536 bytes，无源码/产物漂移和异常信号，配置恢复。 |
| `core-test-local-canonical-call-diff.json` | 按相等原始字节 span 对齐局部类身份后，声明 2924、调用点及归属 17612 均零差异。目标缺 22、多 3，不能把发布或局部投影记为全面对齐。 |
| `core-test-separated-type-reflection-diff.json` | 单独审计反射成员 20/20、implements 26/26 零差异；SOURCE 类型缺 3、多 2，extends 多 1。保留回调置信度差异，不通过改标签消除它。 |
| 隔离绑定修正 | javac 已归属的嵌套 lambda 参数避免再次进入外层推断；恢复未改变完整实参类型时保留外层原始选择；泛型工厂接受候选目标类型约束。三个最小样本原产物失败、隔离修正通过；九项正反例及 full/delta 通过，冻结参考三个新样本及既有 13 个样本相等。完整 JVM 回归进行中，尚未接入产品。 |
| 隔离匿名 Object 修正 | 原产物 3/2 存在多余隐式 Object 边；使用 anonymous nesting 判定后冻结参考 2/2 相等，显式 `extends Object` 和其他匿名父类边保留。尚未接入产品。 |
| 无效 UTF-8 | 用户明确选择保留原字节、标记解析失败、继续健康文件。沿用 SOURCE_ENCODING_INVALID/partial、strict 保留旧 head；不得改编码或记为已解析。 |

本轮验收执行顺序为绑定和类型关系正确性 → 产品 native/daemon → 冻结 iopengw 全 reactor MAIN/TEST/XML → 完整语义与扩展差分 → 独占容量/release。当前仅 E01/E02 完整验收通过。

补充取证：`anatomist-test-xml-valid-20261002-183645` 冻结 TEST/XML 参考 207.719 秒通过，原始坏 UTF-8 文件仍明确排除，配置恢复、参考 JAR 未改变。单独 TEST 的参考图未发布 MAIN 声明，约 5500 个调用目标变为 ambiguous；因此该运行用于范围与路径诊断，不替代 MAIN/TEST 完整参考的绑定验收。回调类型差异仍存在。参考 NoCode 中 `EchoRecordSceneRunnerImpl.class` 的 `javap` 明确含 `getRecordSceneFilters()`；产品 ModuleSignatures 则从扩展前原始编译器声明生成 NoCode，生成调用仍 possible、原诊断保留。剩余能力是对生成模块签名的受控再归属及其证据传播，不能仅修改置信度。

新最小样本的严格多重集数量为嵌套 lambda 15/15、稳定构造器 4/4、泛型工厂 3/3，见 `core-binding-reference-20261002-183821`。早一轮误设嵌套样本最低数量 20 的回执 accepted=false 已保留；这三项专项不替代完整门禁。

## 调用修正后的正式产品回归（2026-10-02）

| 门禁 | 实际结果 |
|---|---|
| `core-binding-final-full-jvm-20261002-184603/receipt.json` | 38 组、294 项全部通过，686.875 秒，无源码/产物漂移。包含新增 9 项绑定正反例及 1 项匿名 Object 继承反例。 |
| `native-fixtures-20261002-184605/receipt.json` | 87 项真实 Rust host/C ABI/native 回归全部通过，271.825 秒，无漂移。 |
| `functional-reference-20261002-184622-68445/receipt.json` | 16 个冻结 native 调用/参数归属对照全部相等，无产物漂移。 |
| `type-reference-20261002-184622-68444/receipt.json` | 13 个冻结 native SOURCE 类型对照全部通过，无源码/产物漂移；原匿名泛型案例已扩展检查父类关系（4/4），新增隐式 Object 2/2、生成 forEach 5/5。 |
| `core-binding-final-native-cabi.log` | SDKMAN Java 8、字节码 52、原生 heap 配额、取消/清理、处理器陷阱、cache/full、故意 SIGSEGV 隔离及篡改包拒绝通过；该故意信号不记为安全杀进程。 |
| `core-binding-final-release-daemon-corrected.log` | 3 项 release daemon 通过：坏编码原字节/partial/strict 旧 head/修复及删除 delta，SOURCE 引用查询及 delta，回调泛型输出和缓存 delta。 |
| `core-service-main-test-contextual-20261002` | 新版实际 MAIN/TEST/XML 索引执行中；MAIN 已完成并进入 TEST，不把中间阶段记为完整发布或语义通过。 |

新版 bridge/native/manifest/maven worker 已在 lang-adaptor/target、target/lib/java、dist 包同步，见 `core-binding-runtime-coherence-20261002.json`。java-fixtures 的保护截止从 900 秒调整为 3600 秒：已有完整正确回归超过旧截止，保护只防止进程悬挂，不设定本任务总时间预算，也不替代性能验收。新正式全量实际仅 686.875 秒，本轮有验收并行，不能作为独占性能比较。

类型图仍是隔离原型：新增每事实可达闭包的稳定编号、完整性/节点形状校验和不留下悬空引用的 suffix rollback；SDK8/64 MiB 下，10001 节点平面归档、循环/前向引用、重复/缺失 roots、checksum、取消与已有归档不可覆盖均通过。原型尚未接入 CompilerBridge/ModelArchive/公开 facet，不据此勾选类型图或大型项目能力。

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

## 平面类型图消费者接入（2026-10-03）

| 工作 | 当前事实 |
|---|---|
| 新图读取/输出边界 | 扩展按需解码单事实，保留不可变共享类型视图；验证输出后编码联合根闭包。修复 Spring 注入、SOFA 和 XML 的类型读取 |
| 原始调用证据 | 归档只编码副本，后续恢复目标可继续读取原始模型，不再重复编码 |
| 文件目录与基线 | 保留全部语义字段，移除发布封装字段后比较规范化闭包；不以引用槽位与视图对象的表示差异判失配 |
| Lombok 泛型 | 重绑定通过数值边闭包保留递归和真实 NULL 下界；已封存图的 complete 证明不重复套旧树的 32 层门槛 |
| 已通过专项 | graph-consumer-deep-getter-fact-rust.log：9 项，包括 80 层 Getter、SuperBuilder 下界与 Spring 类型 full/delta；type-graph-super-builder-rebased.log：原完整/模块缓存对照通过 |
| 初始消费者门禁 | 102 项中 98 通过、4 失败，正式失败回执保留；修复后完整 JVM/native 回执待刷新 |
| 最新默认基线 | native-fixtures-20261002-231556：112 项通过；最新 core/service 发布查询 493.894 秒，MAIN 调用缺 122、多 31，TEST 调用零差异 |
| 尚未关闭 | 默认图、原子版本升级、全绑定/所有消费者、40 模块独立语义与扩展差分、容量和 release；不标记 52 项全部完成 |

正式补充：target/java-alignment/evidence/type-graph-consumers-jvm-20261003-000855 的 102 项全部通过，无输入/产物漂移；type-graph-native-20261003-001015 的九项全部通过；默认 Builder/SuperBuilder/框架/扩展 51 项和 SDK8 八项回归通过。native 消费者的压缩请求 fixture 仍错误使用版本 2，版本 3 要求已由 ABI 正确拒绝，待修正测试入口并重跑。

2026-10-03 本轮终态：JVM 图消费者 102、native 图消费者 84、native→Rust 图证明 9、默认 native 112 全部通过，正式回执无输入/产物漂移；默认 Builder/SuperBuilder/框架/扩展 51、SDK8 模型/归档 8 已过。native 的四个失败项均已处理并纳入完整重跑：压缩请求版本、真实模型入口载荷测量、坏 XML 所需资源。target/lib/java 成套刷新，旧产物保留。S2 默认切换及整个 52 项验收仍未完成。

2026-10-03 严格补充：修正 SDK8 注解通配符边界字段（extends/super），默认签名 7 项与图模式专项通过；Rust 合并发布复用单事实 facet 校验，正常 syntax@2 与损坏分区正反例通过。图消费者 JVM 102 / native 84 包含真实 Rust 发布校验且无漂移；绑定 JVM 193 / C ABI 182 断言均过，但共享校验器被 Cargo 重建，两份正式回执拒绝，须使用任务固定副本重跑。52 项完整对齐、默认图和 40 模块语义验收未关闭。


2026-10-03 固定校验器后的正式绑定终态：type-graph-bindings-jvm-20261003-013300（193 项、431 次图请求、401 次 Rust 校验，546.842 秒）与 type-graph-bindings-native-20261003-013300（实际 C ABI 182 项、430 次图请求、401 次 Rust 校验，650.691 秒）均 all_ok=true，无源码/产物漂移。11 个私有 JVM 装配测试保留在 JVM 门禁；早前共享产物漂移失败回执不改写。更新 Rust release 后的产品 IPC 完整通过。S1 残余语义差异、S2 默认图及原子版本切换、S3–S7 和 52 项总验收仍未关闭。


2026-10-03 新泛型选中声明修正已进入正式源码：约束三态与嵌套明确失败传播，泛型 callback 证据不足只保留 javac 精确选中的二进制声明，不伪造具体返回类型。隔离 14 项 Collector、49 项其他绑定、22 组独立冻结对照及新增 lambda-map 4/4 通过；正式源码 Collector 15 项通过，含类型修改/Getter 删除 full-delta。native 正在重建；新版完整回归和真实 MAIN 复验未完成，旧差分计数仍保留，S1–S7 未关闭。


## 泛型选中声明阶段终态（2026-10-03，f6436ee）

| 项目 | 已完成 / 剩余 |
|---|---|
| 正式 native fixture | 113 项通过，无源码/产物漂移 |
| 独立冻结调用专项 | 23 组严格一致，新增 lambda-map 4/4 |
| 图模式绑定 native | 183 项实际 C ABI、435 次请求、406 次 Rust 校验通过 |
| core/service MAIN/TEST/XML | 发布查询成功，431683 事实；配置恢复，无非预期进程信号 |
| 实际调用缺口 | 92 个独立缺失、0 个独立额外、30 对置信度差异；总缺 122 / 多 30 |
| 类型 / 完整验收 | SOURCE 类型缺 2 / 多 3；全 40 模块语义、默认图/原子切换、Rust 通用缓存、容量、release 继续待验收 |

详细回执及边界见 [执行记录](../docs/implementation/java-alignment-execution.md)。五组冻结二进制类型图投影与长链条 7/9 探针为隔离诊断，不计入 52 项关闭。


接收者模型证据与 lambda 置信度修正已合入 Java 分析层，Rust 架构与协议边界不变。正式 Collector 18 项通过，隔离绑定 64 项、原冻结对照 23 组及新增 9/9、10/10 全部通过。补入三项 full/delta 回归，独立冻结对照入口增至 25 组。f6436ee 的完整 JVM 44 命令/337 unittest 已全部终态通过；新修正的 native 及真实项目差分继续验收，不使用旧回执冒充新版通过。


## 2026-10-03 同版真实终态更新

| 项目 | 最新状态 |
|---|---|
| core-service MAIN/TEST/XML | revision `rev_223ccf0341994f5f9db470c902d27e81` 发布及查询通过，431718 事实；SOURCE 类型/反射/继承、声明/调用点/归属零差异 |
| S1 调用残余 | 56 个独立缺失、0 个独立额外、6 对状态差异；不能把调用或全部语义标记对齐 |
| 同版正式回归 | JVM 图绑定 210、native 默认 131、native 图绑定 199、native 图消费者 91、冻结调用对照 30 组全部通过且无漂移 |
| S2 来源图驻留 | external/written executable 任务 spool 与逐源身份释放已实现；新桥 11 SDK8、新 native 九项图证明通过。默认图/原子版本切换、真实图产品链路仍待 |
| 剩余验收 | 全 40 模块 MAIN/TEST/XML、完整模型/扩展严格差分、Rust 通用缓存迁移、独占容量及统一三个最终 gate 仍未通过；52 项继续不自动勾选 |

2026-10-03 cast 原 javac 表达式证据已进入源码，新 native 最小原版证明 8/8 与两项正反/增量/80 层图检查通过；14 个残余 MAIN 文件隔离对照净恢复 14 目标、校正 1 状态，剩余 42 独立缺失/0 独立额外/5 状态对，尚待完整正式回归和新发布差分。3b6803c 局部新版保持 56/0/6；独立全 40 模块在 migration/core/service 调用长链预热发现 contains(null) NPE，未发布、无异常杀进程，正在隔离修正。不勾选完整对齐。

2026-10-03 migration 全范围阻断修复：普通源码类没有 modeled_capability 时不再 contains(null)；8 段普通调用链 9 个 resolved、cache/full/delta 与现有 40 setter/14 getter 正反例六项通过。cast TestCase 重复发现已纠正。上一版完整 native 回归因重复测试和误清理开发产物作废，31 组独立原版严格对照仍有效；新版本在独立工作区重跑，尚未关闭全 40 模块或完整语义。

2026-10-03 cast 正式 MAIN/TEST/XML 差分确认42独立缺失/0额外/5状态对，声明、调用点/归属、SOURCE类型、反射和继承全部零差异。e8f9a88 独立默认 native 134项通过；图绑定及修复NPE后的40模块继续验收。普通表达式javac绑定已进入源码，31组独立原版和14项现有正反回归通过；选定残余14 MAIN文件隔离降到30/0/5，仍待新native正式回执。异常String+缺失函数样本另缺1，不计通过；不勾选S1或全部52项。

2026-10-03 原javac已选中声明的模型输入修正进入产品：恢复仅Unavailable时保留possible目标及原证据；blocked或budget仍拒绝。四项JVM/native检查、现有33组与新增缺失函数3/3独立对照通过；14 MAIN隔离残余24/0/5，完整门禁与真实发布待验收。

2026-10-03 5c6065e 默认native135、33组严格原版、native→Rust图9项正式通过，无漂移；图绑定/消费者和新发布仍运行。原选中声明保留候选又恢复6目标，残余隔离24/0/5，四项JVM/native新增正反增量检查通过，正在独立工作区收尾。S3新增注解实际差分：SOURCE缺0/多7（局部变量），生成facet另1872，尚未归并验证完整扩展语义；继续保留全部额外证据和未通过状态。

2026-10-03 5c6065e正式收尾：默认135、图绑定203、图消费者91、图9、冻结原版33全过；core MAIN/TEST/XML发布查询955.973秒，431772事实，正式残余30/0/5，声明/调用点/归属/SOURCE类型/反射/继承零差异。9fc3fcb已合入，34组native严格原版通过，其完整默认/图绑定仍运行；40模块e8验收未终态，全部52项仍未关闭。

2026-10-03继续：40模块e8在app/test TEST遇SDK8 AnnotatedType接口误判而失败（2601.287秒，未发布，无信号）；最小树/图复现后改按TypeKind分派，两项注解镜像/函数目标/增量检查通过。调用预算按原版分离，10链11/11、80链32/32且49blocked，34组原版严格一致；残余14 MAIN隔离22/0/5。14项相关JVM通过；真实app/test3329源码/869jar专项及新版完整native/全项目仍待验收。Rust显式任务保护上限已86400秒，默认120、javac最大3600不变，完整Rust验证通过。

2026-10-03新正确性修复：泛型含null回调不再丢既有约束，36组独立原版通过，真实残余隔离19/0/4，待native新版本。XML开始读取带source_origin的辅助源码成员，17项原XML/SOFA/依赖视图与新增构造器/Setter删除增量通过。1ea默认native140/参考36通过，图绑定206因新增fixture显式关闭图失败两项；已修测试并通过两项native+Rust图专项，完整门禁待重跑。全40模块1ea仍运行，未接受完整语义或容量。

2026-10-03 新检查点：356587f默认native141、图绑定207、图消费者92、冻结参考37组全部终态通过，无输入/产物漂移；不关闭默认图/全语义/容量/发行。aa00873固定通配符Collector包含约束已提交，joining5/5及StringBuilder正例/Integer负例、cache/full/delta通过。回调代码块按原版YES/NO/UNKNOWN恢复TRY/IF/顺序块，排除不兼容Supplier后保留Runnable；新增两项26.156秒通过，原37组严格相等、新样本4/4。14MAIN隔离残余14独立缺失/0额外/4状态对，正式最新版真实差分待跑。

2026-10-03 4a57677：1ea710c完整40模块在最终app/test@TEST汇总到1888143事实时触发4GiB归档上限，2191.629秒终态失败，无外部信号、配置恢复、无漂移。现显式结果/暂存上限8GiB/32GiB，默认预算和2GiB进程树保护不变。Rustworkspace/clippy、SDK8图13项、native图9项、冻结native参考40组、新native4项及CLI产品预算专项均通过；4a57677独立完整native/绑定/消费者及新40模块正确性验收正在运行。S1–S7与50项完整能力仍未关闭。

## Rust 静态注入候选与新版门禁（2026-10-03）

| 项目 | 实现 / 证据 |
|---|---|
| 4a57677 完整 native | 默认 144 项、图绑定 210 项（527 次 Rust 校验，1060.829 秒）、图消费者 92 项、冻结调用参考 40 组全部终态通过；输入/产物无漂移。仅 opt-in 图，不据此默认切换。 |
| S3 WIRES | Rust application 按冻结 WiringResolver / DatabaseWiringResolver 推导直接内部实现候选。字段/参数通过 Java injection registration 取实际声明，不做容器选择；全部候选、歧义、位置与来源保留。 |
| 发布 / 基线边界 | storage 只提供定向私有候选接口；按 aggregate owner 原子重算，规则版本进入 Rust SemanticProfile；Java worker baseline 排除派生 owner。原始 worker 不能冒用派生 producer。普通公开 revision 导出仍保留。 |
| 正反 / 增量 | 三项 Rust 测试验证直接实现去重、不纳入外部/间接继承、不按 qualifier 唯一化、2→1 候选/删除源 full-delta 等价、旧 revision 保留、坏来源/冒用 producer 失败保 head；公开查询保留 possible 与开放世界说明。 |
| 独立产品链路 | derived-wiring-product-e2e.log：实际 CLI→daemon→4a native，首轮 1 项 22.824 秒通过，最终重新构建后 2 项 26.656 秒通过（含非法 UTF-8 strict 保旧版）。每轮 published facts 的候选、confidence、via/source/candidates 与实际冻结 jar 的 WiringResolver 严格相等；实现变更/删除的完整事实 full-delta 相等，取消注入清空旧候选。 |
| 真实调用诊断 | 树/opt-in 图在 14 个残余 MAIN 文件均 2067/ref2081，14 独立缺失、0 独立额外、4 状态对。lambda descriptor 的归属回退尝试未改善，未合入产品。继续定位上下文约束。 |

4a57677 独立 40 模块 MAIN/TEST/XML 正确性仍在运行；该回执不含本次 Rust WIRES 政策，不能据此验收新版 WIRES 或全部 S3。完整扩展语义、默认图/版本切换、Rust 通用缓存、容量和 release/52 项仍未关闭。没有非预期进程杀死。

## 跨模块 WIRES 捕获身份（2026-10-03）

| 核对项 | 结果 / 边界 |
|---|---|
| Rust 策略 v2 | javac 的上游 external alias 仅按本 revision 的 path/hash/world/module/scope 精确匹配非 auxiliary 捕获，且存在唯一已发布 source/world/signature 声明时纳入。保留原 Java 事实；仅归一派生候选身份。规则版本更新 SemanticProfile，不复用 v1 基线。 |
| Rust 正反例 | 4 项派生测试及全部 workspace 通过。hash/world/module/scope/path 各自失配、仅 auxiliary 未发布均不能生成候选；直接/间接实现、歧义、删除、坏证据和旧 head 回归保留。clippy 全 targets -D warnings、fmt/diff check 通过。 |
| 实际产品对照 | derived-wiring-v2-product-e2e.log：2 项、29.942 秒通过。独立复制新版 Rust release 与 4a native 成套产物，artifact SHA 另存；三 Maven 模块 MAIN/TEST，跨 world 4 个歧义候选与冻结 jar 的实际 WiringResolver 相等。热缓存事实相等；实现移除后 2 个唯一候选、全量/增量完整事实相等，旧 revision 保持。同模块候选修改/删除回归也通过。 |
| 未关闭 | 4a 全 40 模块正式运行使用旧 Rust，不包含本轮 WIRES；完整扩展差分与最终 52 项仍未通过。Java 上下文重入试验虽通过 40 组冻结 fixture，对 14 个真实残余文件没有改善，未合入。 |
