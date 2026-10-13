# Java 适配层重写

> 历史记录（2026-10-13 文档对齐）：按 dff2e395 冻结的适配层重写记录；旧模型/协议/暂存格式不是当前产品契约。现行版本、验证范围及未完成门禁见 [当前实现](../current-state.md)，最新结果见 [存储实施记录](anatomist-storage-migration.md)。

参考 Anatomist dev/replace-parser@dff2e3954096853352ec91fcbfc6b995fbd4f390。Java保持文件级独立模型、上下文查询、文件级扩展事务；Rust保持捕获、调度、缓存、存储、发布、查询。

| 决定 | 内容 |
|---|---|
| 执行 | SDKMAN Java25/GraalVM native → Rust C ABI worker → SDKMAN Java8 javac；无JNI、处理器或业务编译 |
| 范围 | AST静态声明与调用证据，配置off/model；不扩展compile、lombok.config、Gradle、Linux、目标JDK17/25或独立容量验收 |
| 兼容 | 用户允许破坏性修改，删除旧产品索引和Java派生缓存；保留冻结输入、参考库和失败回执 |
| 当前版本 | compiler9/model9/worker12/request6，ABI2/Fact IR1不变，混装拒绝 |

## 实施进度

| 部分 | 状态 |
|---|---|
| JavaContextModel | 文件身份、声明位置句柄、成员索引、显式歧义；不保留全部解码声明 |
| JavaUnitModelExtension | Lombok按文件执行/校验/封存，最后统一提交；失败不留下部分修改 |
| UnitDeclarationRows | 合并原始行与封存overlay，可追踪写入、稳定snapshot、任务租约及缓存回收 |
| 原Lombok路径 | 已删除产品全上下文candidate/original容器；规则持有当前文件，父类通过共享查询读取 |
| 模型导航 | 原声明目录改为位置索引，避免保留所有解码对象 |
| 源码位置 | 按文件惰性读取，4文件缓存；坏编码保持原字节和明确诊断 |
| AST验收入口 | 新增--lombok-ast，两侧均开启；参考保留真实跨文件上下文 |
| JavaSemanticModel | 文件内声明/语法/绑定视图；上下文声明、类型和成员目录只保存位置；跨文件依赖按需读取 |
| 调用/反射pass | 调用恢复按文件新建状态；反射只保留当前文件调用；覆盖关系不物化全部方法 |
| 声明/绑定pass | FactExtractor分离声明与绑定投影，按文件执行；调用点与反射关联限定同一文件 |
| 类型/注解pass | 写明类型的节点目录随文件切换；meta annotation与补充ABI使用位置查询；不保留全部解码声明 |
| JavaUnitAnalyzer | 冻结上下文查询、文件内body、文件输出事务；Lombok/Spring/MVC/Handlers/SOFA注解均已接入，失败文件隔离、拒绝外文件事实；完整回归待最终收口 |
| 规则实现 | 保留本项目已移植的推断与证据规则，文件边界改造不能替代真实语义差分；wire Map仍留在规则输入边界 |
| 产品数据清理 | 默认~/.code-ana不存在，无默认产品catalog可删；新验收采用隔离空home，保留参考库 |
| 完整验收 | 007d79d native模型17项、发行4门禁通过；完整回归、iopengw发布、两套80上下文、14域未完成；尚未替换dist |

## 证据与边界

- 原c5任务38615.869秒后NATIVE_HEAP_EXHAUSTED，app/test@TEST Lombok owner阶段，1GiB native堆；未发布、无异常signal。已越过原core/service commit。
- 文件级新路径重放已保存模型：message-protocol-component约6.63秒；app/test的502MiB模型约9.82秒，512MiB JVM堆。单上下文重放不等于完整产品验收或性能达标。
- Lombok16/Builder14/SuperBuilder8基础回归和版本切换后归档回归通过；参考测试失败保留，修复后新回执才有效。
- JavaUnitModelProbe检查歧义、非法句柄、整体回滚、写入、幂等和租约；旧raw80不能认证AST。
- 文件模型新增64MiB惰性声明目录检查：1200个每次读取附带128KiB payload的声明，跨文件查找、body归属、路由写入与关闭后拒绝均通过；不认证项目容量。
- 48b0ba6 native模型17项对照通过。该提交未包含后续semantic pass改造，不认证最新代码；旧65fe23d真实raw已通过6个上下文、AST已通过5个，均主动停止以等待最终同版本验证。
- 65fe23d及007d79d首次产品启动收到SIGKILL9，代码签名校验有效，系统日志未证明具体杀进程来源。用户已处理，007d79d重试进入compiler_model/事实提取；完整发布尚未完成。

## 移植出处

| 参考类 | 采用逻辑 |
|---|---|
| JavaModel/JavaUnit | 文件边界、上下文引用、找到/歧义/缺失及成员查询；底层使用本项目归档 |
| LombokJavaModelExtension | 待处理文件、文件Overlay、依赖查询、重复处理保护 |
| JavaModelExtensions | 候选验证和失败原子性 |
| JavaModelExtractor/JavaCallFacts | 文件body状态、跨文件声明查询、按文件扩展隔离；事实采用本项目Rust契约 |

参考源码固定commit，直接复制文件须登记SHA和原版权声明。已证明参考缺陷不复制，保留raw差异及独立证明。

## 007d79d 同版本收口

| 项目 | 已执行结果 | 边界 |
|---|---|---|
| JVM模型API / native模型API | 各17项通过，独立native回执无输入/产物漂移 | fixture对照 |
| 文件扩展失败隔离/归属 | 旧树及图模式专项均通过；失败文件回滚、健康文件继续、冷delta相等 | 初次测试路径误写已纠正 |
| JVM高级绑定 | 227项测试成功；581次请求、546次Rust校验 | 汇总输入指纹因测试路径修正漂移，不能作最终总门禁 |
| 发行功能 | 4门禁通过；包内自动启动/索引/calls/source，MAIN/TEST/XML和错误JDK检查；无包/源码/运行时漂移 | 包保留在候选验收目录，最终dist交付仍待真实语义收口 |
| 完整验收 | 另一个007d79d固定副本运行完整回归；本副本执行完整发布及raw/AST各80上下文 | 未闭合52项，未认证容量 |

产品重试与发行回执位于`target/java-alignment/rewrite-007d79d/target/java-alignment/evidence/`；旧SIGKILL回执保留，不能覆盖成成功。

## 内置框架文件事务补齐（2026-10-07）

| 项目 | 结果 |
|---|---|
| 已查证差异 | Anatomist JavaModelExtractor按文件调用全部JavaModelAnalyzer；此前本项目五个内置Java分析回调仍按上下文提交，文件接口仅fixture使用 |
| 改造 | 五个回调采用JavaUnitAnalyzer；当前文件声明/绑定参与规则执行，共享跨文件声明位置与注解元数据查询；XML/DI项目分析保持项目边界 |
| 输出/失败 | 文件级候选校验后提交；失败仅撤销该文件候选、健康文件继续；strict与取消仍终止任务；原有按source生成的事实ID保持 |
| 身份 | registry v14-unit-framework；Lombok13、Spring8、MVC/Handlers/SOFA注解3；compiler/model/wire/ABI不变 |
| 已验证 | 框架14项、扩展20项JVM回归通过；64MiB探针验证20万旧事实不解码、多文件事实与原投影相等、跨文件目标排名及5个文件回调清单 |
| 可观察性 | 调用恢复增加override与当前源码进度；不改变事实或预算 |
| 待验证 | 以上变更晚于007d79d固定验收；不能把旧native/发行/iopengw结果登记为新版成功，需另建同版本固定运行目录 |

007d79d启动重试未再次被杀，已越过Lombok提交；common@MAIN调用恢复耗时较长。系统取样显示计算与归档读取，尚无证据判定死循环或最终成功。

## de8c99b 固定版本验收

| 项目 | 状态 |
|---|---|
| native 构建 / 模型API | 新库构建成功，17项独立对照通过，无输入和产物漂移 |
| 冻结框架参考 | native对照通过，无输入和产物漂移 |
| 完整回归 / iopengw | 分别在rewrite-gates-de8c99b与rewrite-de8c99b固定副本运行；已越过Lombok，文件调用恢复进度可见，尚未发布 |
| raw / AST | 各80上下文清单重新执行，不能复用旧版本通过数；完整清单和发布均通过后自动触发14域严格差分 |
| 发行功能 | 前3项通过；包内CLI自动启动daemon收到SIGKILL9，同包路径重试仍失败，签名有效，已通知用户；iopengw独立daemon/worker运行正常 |
| 旧007d79d | native高级绑定216项通过，546次Rust事实校验，无漂移；属于旧版本证据，不认证de8c99b。其发布/raw/AST/完整回归主动SIGTERM并保存审计，非安全软件异常 |

最新版入口：target/java-alignment/evidence/latest-java-correctness-run.json。发行失败源证据在de8c99b验收目录的shipped-fixture/index.stderr；原测试finally报告DAEMON_STOP_FAILED，实际先发生DAEMON_START_FAILED/SIGKILL。失败回执保留，不改写为成功。最终dist仍未替换，容量未验收。

## iopengw 串行耗时重跑（2026-10-07）

用户要求重新索引并对比Anatomist，原de8c99b完整验收在30248秒主动SIGTERM，配置已恢复、无异常signal。新实验复用同一固定native版本，不改Java分析规则。

| 条件 | 设置 |
|---|---|
| 源码 | 从267c7ced4993aa88613f8f17a8099943fec98159重新git archive；5039个提交文件逐字节校验 |
| 范围 | MAIN/TEST/XML，Lombok AST，单并发；共同排除已知1个坏UTF-8 TEST文件，原字节保持，不能作为该坏文件验收 |
| 顺序 | Anatomist先运行并退出，再捕获code-ana新Maven输入、全量索引；无旧分析进程竞争 |
| 缓存 | 新索引与独立产品/参考home，复用本机已有Maven依赖；不清OS缓存 |
| 内存/JDK | 分析器1GiB、javac512MiB；SDKMAN Graal25宿主/Java8目标 |
| Maven | 离线元数据；参考wrapper只允许扩展validate并拒绝目标编译生命周期；code-ana显式导出40模块的新输入 |

| 首批结果 | 实测 |
|---|---|
| Anatomist | 成功提交；进程墙钟约110.211秒（5秒轮询上限误差），内部index105.037秒；4213个Java，55399 nodes、168038 edges，调用点等独立存储不包含在普通edges数里 |
| code-ana Maven输入 | 15.033秒，40模块 |
| code-ana全量索引 | 仍在运行，未发布；完成后自动记录索引和含Maven输入的总时间及耗时倍率 |

实验入口`target/java-alignment/evidence/latest-iopengw-timing-run.json`；实际回执`rewrite-de8c99b/target/java-alignment/iopengw-timing-20261007-094626/comparison.json`，code-ana逐秒进度在其`codeana/receipt.json`。Anatomist配置覆盖单独记录并在参考结束后恢复；原提交文件再次校验。该试验不认证全范围语义对齐、坏文件处理或容量。

原未完成运行的阶段日志统计：调用恢复25529.961秒（约85%）、Lombok1928.987秒（约6%）、事实提取730.966秒；不是完整索引性能成绩，也不能据此宣布根因已修复。

## 调用恢复瓶颈修复（2026-10-07，验收中）

| 修改 | 边界 |
|---|---|
| 发布文件恢复 | 仅恢复 publish=true 文件的调用/callback/enum；辅助源码仍供类型、生成成员和继承查询 |
| 成员索引 | 一次筛选，owner保留位置；查询不再重复筛选，保留顺序和修改可见性 |
| 解码复用 | 任务内64MiB逻辑预算，声明/当前文件语法各32MiB；按归档帧字节计费，超大行绕过，文件切换清语法租约；不是实际Java堆字节 |
| 原行租约 | 保留ArchiveRows原State和别名；snapshot仍不可变，不把全上下文AST放回内存 |
| 计时 | recovery-metrics.ndjson记录完成区间、调用/预算失败/缓存计数；独立1MiB诊断上限，scratch清理前保存；Fact IR/ABI不变 |
| 缓存指纹 | registry v15-published-recovery使旧扩展组合失效；Java8桥新增租约工具单独编译 |

首批验证：模型归档回归、长链8项、模块overlay9项、验收保护26项通过；新增64MiB小堆缓存别名/snapshot/超大行绕过、辅助文件恢复边界探针通过。Java8桥构建通过。

真实上下文诊断：common/service/integration@MAIN共754源码/82发布源码，JVM重放恢复2.401秒、完整分析与输出53.635秒；旧实现同请求89.342秒。两边35952事实ID集合相同，只有一条provenance的extension_fingerprint随registry版本变化。属于隔离诊断（开发构建与旧索引仍在运行，未认证产物冻结），不替代native、完整上下文或发布验收。证据target/java-alignment/recovery-context-profile。

旧版全项目运行保留诊断；开始修复后与开发测试有资源竞争，其后耗时不作性能门禁。最终冻结新产物，先同版本语义验收，再同机同范围交替串行三轮全新索引（含Maven输入/传输/发布），中位耗时不超过Anatomist两倍。raw/AST全部80上下文、14域差分、发行及性能门禁尚未通过，不关闭剩余能力项。

第二批修复：SyntaxRows加入原行租约，当前文件语法缓存实际生效；SupplementalTypes的ABI资格/投影复用有上限的声明租约并单独标记阶段。计时诊断上限改8MiB，覆盖4213发布文件，原phase日志仍1MiB。

| 新证据 | 结果与边界 |
|---|---|
| 7448877 native fixture | 178项全部通过，600.558秒；源码、产物、Rust校验器无漂移 |
| 首版真实native诊断 | integration MAIN调用恢复5.881秒；整体在worker测试入口固定130秒NATIVE_TIMEOUT，未观察SIGKILL，不算完整成功 |
| AST剩余差异 | 原64/65上下文都是声明差异；字段NotBlank等注解未解析，参考拒绝生成其访问器/构造参数，产品原仅检查字段类型完整性 |
| 对齐修复 | 字段注解/原written type完整性参与访问器和构造参数资格；Lombok14/registry16 |
| 独立fixture | 模型API参考18例（新增缺失字段注解、健康相邻字段）、Lombok45项、小堆语法租约探针通过；补充签名12/模块overlay9在投影缓存修改后通过，完整新版native仍待 |
| 真实针对性复测 | JVM复用冻结原javac归档，MAIN8537声明、48621AST、406源码、441类型查询严格相等；TEST8569/44797/409/444严格相等。MAIN26.074秒、TEST19.984秒，源码/输入无漂移；坏编码原字节保留并明确记录。不是新版native80/80证明 |

### 72af189 固定产物验收

| 项目 | 实际结果 |
|---|---|
| native fixture | 178项通过，645.298秒；源码和产物无漂移。回执：`target/java-alignment/recovery-stage2/target/java-alignment/evidence/native-fixtures-20261007-111436/receipt.json` |
| 独立模型API | JVM/native各18例通过，无漂移；native回执：同证据目录下`model-api-reference-20261007-111543-86440/receipt.json` |
| 单模块产品链路 | integration MAIN发布36244条事实，结构查询退出0；全验收流程286.077秒，不能当作纯索引耗时。回执：`recovery-selected-product/receipt.json`；存在发布后Ack超时警告，仍需诊断缓存收尾 |
| native诊断入口 | 调用恢复3.362秒，缓存167077次命中/49860次未命中；分析完成后被测试入口64MiB结果上限拒绝。产品流式交付采用独立4GiB上限，单模块已通过；这不是SIGKILL |
| 全项目 | 新空索引MAIN/TEST/XML运行中，保留坏编码文件；回执：`recovery-v16-full-product/receipt.json` |
| 完整模型清单 | raw 80上下文运行中，结束后接着AST 80；回执：`recovery-v16-model-raw/receipt.json`、`recovery-v16-model-ast/receipt.json` |

这些结果不认证全项目语义差分、最终发行或两倍耗时门禁。正确性验收期间有开发回归与旧版诊断进程竞争，运行时间只作诊断。

发行后续：7e55fe5修复包manifest仍写旧worker11/request5/compiler8/model7的问题，Java协议字段直接取已校验的native manifest，worker为12。独立`recovery-release`副本的`release-v16/receipt.json`四项全部通过（35.618秒、源码/产物/包均无漂移）：基础产品、Maven XML MAIN/TEST及增量、错误JDK拒绝、包内CLI自动启动daemon后索引/calls/source。包位于该证据目录的`package/`。这是发行功能通过，不代表全项目语义或性能门禁通过；尚未替换最终dist。

Rust外围：`just test-rust`全部通过，包含workspace测试、产品端到端、格式及clippy；日志`target/java-alignment/recovery-rust-regression.log`。没有扩张Java/Rust职责边界。

## 第二轮重复工作修复（registry v17，2026-10-07）

| 用户要求 | 实施边界 |
|---|---|
| 排除竞争 | 主动SIGTERM旧诊断、v16发布、raw/AST和差分等待器；保留原回执和停止审计`recovery-isolation-stop.json`。新链路按fixture→原生→单次全项目发布→raw→AST→差分串行，完整对照不与索引同时执行 |
| 共享索引/缓存 | 事实提取只建一个64MiB逻辑租约会话；扩展共享模型位置索引、声明/注解索引和未变化的事实头索引；Java阶段身份校验索引增量更新。注解查询预算/诊断各扩展独立，可关闭的导航句柄保持独立，旧事实视图不看到新声明 |
| 辅助与空发布 | 辅助声明头、类型查询和XML成员保留；绑定/源码类型投影提前限定发布文件。空发布事实阶段不读body，逐文件扩展跳过；仅逐文件Java分析器及明确声明只需头部的项目扩展允许跳过补充javac/调用/反射，旧全模型扩展不缩窄 |
| 补充分析与缓存核查 | 每上下文保存完成阶段区间和进入次数，含初次/补充javac、解码、缓存写入；项目模型归档单列，避免误算为模块缓存。缓存资格、SHA和NoCode证明不放宽 |
| Lombok额外根因 | 每文件新建规则对象，使Generated默认策略重复扫描同一classpath。改为上下文内一次，包括未知版本的负结果；保留classpath顺序、manifest读取预算与源级显式策略优先级。声明租约32MiB，替换单位时清旧租约；不复用其他请求的状态 |

单上下文串行JVM重放：integration MAIN的754源码/82发布文件、619 JAR、319待Lombok单位；v16为44.254秒，新版24.221秒（约45%减少）。Lombok阶段18.745→2.679秒，事实提取3.884→约2.713秒，框架3.802→约1.137秒。35952事实ID和语义内容相同，唯一provenance差异为registry指纹。证据`target/java-alignment/recovery-stage3-profile-v2`及`recovery-stage3-profile-v4`；后者包含策略复用修复。这是JVM上下文诊断，不是native全项目或两倍性能门禁。

已完成JVM验证：归档/小堆/租约/别名、类型引用11、框架14、扩展21、XML12及新增空发布XML用例、模块覆盖9、模块签名9、缓存4；Generated策略修复后Lombok17/Builder14/SuperBuilder8/模块覆盖9、独立模型API18通过。独立模型API保留两例已复现参考缺陷，不把raw不同改写为相等。完整新版native、全项目发布、完整raw/AST、14域差分与三轮性能仍待实际回执，最终dist尚未替换。

## registry v17 同版本验收进度（2026-10-07）

| 验收 | 本轮证据 / 状态 |
|---|---|
| native fixture | `target/java-alignment/evidence/native-fixtures-20261007-123015/receipt.json`：all_ok=true，源码/产物无漂移，521.909秒 |
| 独立模型 API | `model-api-reference-20261007-123902-83663/receipt.json`：18组通过，源码/产物无漂移；两项已复现参考缺陷继续明确记录 |
| 独立框架 / XML | `framework-reference-20261007-123954-84568/receipt.json`、`xml-graph-reference-20261007-124008/receipt.json`通过；空发布XML的真实native用例也通过 |
| 发行功能 | `recovery-stage3-serial/release/receipt.json`：all_ok=true，包/源码/产物无漂移，24.024秒；不代表全项目或容量通过 |
| 完整项目正确性 | `recovery-stage3-serial/receipt.json`严格顺序：单次MAIN/TEST/XML索引→raw全部80上下文→AST全部80上下文→14域差分；当前索引运行中，后三项未验收 |
| 磁盘清理 | 按历史证据清理授权，旧de8c99b的raw/AST归档与大清单释放48.51GiB，旧回执及日志保留；这些回执的已删除输入不能再作为有效验收证明。当前版本、参考JAR、参考事实库及项目冻结输入保留 |

上述相对证据路径均位于`target/java-alignment/evidence/`。三轮性能不包含在本轮1/2/3任务内；须等完整正确性通过后另行执行。

## 全项目补充签名投影的重复解码（2026-10-07）

| 取证 / 修复 | 结果 |
|---|---|
| 首次完整索引 | 51个上下文后，在core/spanner MAIN累计1843860事实时触及4GiB归档预算；回执`recovery-stage3-serial/single-project/receipt.json`，无异常信号、无源码/产物漂移。重试仅将显式result预算改为已支持的8GiB |
| 8GiB重试 | 已越过旧失败点、完成57上下文；core/service MAIN的supplemental_projection超过20分钟。worker接近单核满载，模型424MiB、归档4.54GiB。采样无内部符号，只能定位阶段，不能把某个方法宣称为已确认唯一热点；随后主动SIGTERM并保留`recovery-stage3-serial-8g/intentional-stop.json` |
| 代码中的重复工作 | NoCode为每个类再读全部类型的attributes；SupplementalAbi在判断是否需改写前先枚举复制所有属性。缓存一次MEMBER类型的标量classfile元数据，保留原词典序、自身/直接成员/祖先前缀筛选；不需改写的声明直接使用原只读头。NoCode资格、图/签名校验、处理器策略不变 |
| 字节等价探针 | 517个类型、嵌套interface/enum/annotation及字面$前缀，旧/新版SHA256均为0b534a76bbe86477b07ac662a5c05d73fccbb3efaa1b6edbd0bb0b9e5315f900；attributes读取268840→2068。64MiB验证惰性未改头不被枚举复制 |
| 回归 | 补充签名12项、模块签名9项及完整归档/小堆探针通过；新native、core/service实际重放和完整1/2/3仍需新同版本回执，不复用已中断全项目结果 |

## Rust 入库预算重复扫描（2026-10-07）

| 证据 / 修复 | 本轮结果 |
|---|---|
| 实际Java重放 | 8356dc5的core/service MAIN补充签名投影4.79秒（此前超过20分钟未完成），上下文489.84秒，事实提取212.10秒；MAIN/TEST均完成。selected core仍不是完整项目门禁 |
| Rust热点 | `recovery-stage4-serial/core-service/daemon-finish.sample.txt`：后台100ms预算监控与候选暂存/派生的DiskBudget::directory_bytes占主要忙栈。CandidateFacts只读查询逐行调用包含全目录遍历的检查；候选DB增长到约1.52GiB后仍在收尾。随后主动取消旧runtime，保留868624538字节native结果作诊断；异常中断的配置租约通过身份核对及恢复日志清理，不能认定发布通过 |
| 目录检查 | 任务内共享有界目录成员清单，每次仍读取活跃文件大小和类型；目录变更刷新、删除清理、缓存满回退；不缓存字节总量，不放宽symlink/overflow/总量检查，100ms监控保留 |
| 派生读写 | 只读查询逐行保留取消检查；派生写入每256条复核磁盘，阶段前后复核最后不足256条的批次。事实验证、重复/悬空引用、原子发布均保留 |
| 验证 | 预算4项覆盖文件追加、目录嵌套变更/删除、候选库增长、共享克隆、符号链接、缓存满回退；暂存8项覆盖逐行取消、批次预算和提交前尾批检查、失败保留旧版。`just test-rust`全部通过，日志`recovery-budget-rust-regression.log` |
| 正式门禁 | 新Rust发行包、完整MAIN/TEST/XML、raw/AST各80上下文、14域差分仍需新回执。Java源码未再变化，沿用8356dc5成套native和模型探针；新正式流程重新冻结全部源码/产物并复核，不把旧中断的发布证据当通过 |


## 两倍耗时门禁改造（实施中）

stage5 全项目于3667.177秒失败，57个上下文完成；core/service MAIN第二份模型解码 NATIVE_HEAP_EXHAUSTED。无SIGKILL/漂移，未发布；raw/AST80和14域没有启动。57个已完成上下文共3431.244秒：javac757.101、解码334.723、事实590.703、Lombok372.646、文件缓存270.161、项目归档172.260、资格199.703；45个上下文二次编译。

本次实现 model9 二进制归档、64KiB编码页与缓冲帧索引、250ms文件进度节流、上下文主/补充模型统一释放及删除输入、每请求SOURCE/载荷/编译/解码计量、类型闭包64MiB有界字节缓存、签名资格生成结果复用、文件缓存只写自有world/先查后投影及外部位置索引。compiler9/worker12/request6和ABI2/Fact IR1不变；模型及签名缓存格式升级，旧目录不混装。独立模型API旧租约仍保留。

已验证：会话10、模块签名9、文件缓存4、补充签名12、框架14，归档/小堆/损坏输入均过（分别对应当时实现，不替代最终组合）。integration单上下文新JVM约24.117秒、35952事实，旧v17约24.221秒；没有证明整体性能明显改善，最终新组合独占native仍待。新增类型闭包及签名资格复用的最终回归继续执行。

进一步查证：iopengw847个依赖MAIN/TEST配对仅1个有完整相同classpath；当前ModuleSignatures的整份classpath相等条件会拒绝其余配对，不能只删检查。必须建立依赖声明的有效输入证明/一致性用例，保留原始javac与补充证据，不能为提速放宽语义。MAIN/TEST80输入的可见源码出现量114415、发布4214（含坏编码1）；这是优化前可见量，不是实际javac数量。

完成标准：同版本全项目发布/查询、raw/AST各80上下文、14域；其后独占三轮交替串行索引，含Maven/捕获/传输/缓存/发布，每轮与中位倍率均小于2。当前尚未完成，不更新最终dist或关闭性能门禁。

新增任务内32MiB有界JAR包目录/SHA缓存：绑定补全、调用恢复、反射及减量路径共享；不共享带classpath位置的来源或可变类声明。输入身份变化撤销缓存，预算满回退原有精确顺序查找。256-JAR低描述符探针覆盖同名遮蔽/重排、替换、取消和零预算，已通过。SDK8类型图11项通过；修复探针显式源码清单漏依赖及Java8不支持var。integration35952事实逐条比较，仅82条provenance.model_version为8→9，其余完全一致；仍不是全项目语义门禁。成套model9 native与Rust release已构建，最终组合回归和独占真实重放待验收。

同版本JVM回归首轮48组通过，第49组模型API因独立验收读取器只认CAF1而停在MODEL_API_ARCHIVE_VERSION；没有SIGKILL或输入/产物漂移，串行driver已停止，真实项目未启动。修复读取器以独立验证CAM1，保留CAF1参考/公开事实路径；新增5项字典契约/损坏输入/编码/作用域/重复投影门禁。模型API全部fixture重跑通过，产品native未变；完整新源回归继续串行重跑，失败回执不改为成功。

## Maven模块边界纠正（perf-v3）

独立SDK8夹具复现整份classpath相等策略的问题：上游Api以依赖v1计算VALUE=1，下游使用v2；旧产品拒绝NoCode并在下游重解析上游，seen=2，按模块分别编译得到seen=1/direct=2。证据perf-v2-cp-repro/receipt.json及javap.txt（仅编译新建fixture，没有编译业务项目）。参考JavacSignaturePlan.input消费上游自身fingerprint及声明目录，不要求consumer/producer CP相等。这纠正了前述“必须先证明consumer CP等价”的方向：正确证明对象是上游自己的源码/CP，而不是用下游环境重定义上游API。

新模块缓存格式v9保留原始javac声明，按producer源/CP进行SHA/key校验；下游维持自身CP。保存/恢复上游自有分析诊断，完整签名允许方法体错误，parse/编码失败及不完整声明头仍SOURCE，类型/生成证据不会提升置信度。目录去掉无结构子节点的局部变量，参数/注解/结构父节点保留。首批模块10项通过（实际CP两版本+SDK8编译见证、body错误事实逐条等价、缓存变更），完整专项8组仍运行。先前第二轮JVM回归及等待driver为修正模块边界主动SIGINT/SIGTERM，保留中断回执，不当SIGKILL或成功验收；native仍需重建。

冻结Anatomist JAR实际Maven模块分析已验证seen=1/direct=2，archivesUsed=1/sourceAppearances=2；新FrozenModuleClasspathReferenceProbe进入并行模块回归。更新旧CP断言及跨环境注解对照：相同CP逐条全量事实一致，不同CP按独立模块/冻结参考验证生产者API；生成调用保持possible和原始consumer错误。模块12、ABI7、overlay9、parallel8、跨模块注解11、框架14、XML和会话10专项通过，证据perf-v3-focused*/receipt.json。目录精简保留结构父节点，200个leaf变量不入依赖目录，全量事实不变。native重建及最终组合/真实项目门禁仍待。

### perf-v3 同版本验证与独立计时

aa67fa5 已重建 model9 native、Java8桥及 Rust 发行程序。同版本 JVM 完整回归 61/61 组通过，1908.491 秒，无输入或产物漂移；包含图模型消费者145项、图模型高级绑定228项。回执 `target/java-alignment/evidence/perf-v3-final-20261007-203622/receipt.json`。这是回归套件耗时，不是 iopengw 索引耗时。native fixture、发行包、core/service、全项目及 raw/AST/14域仍由 `perf-v3-serial/receipt.json` 串行验收，尚未据此关闭门禁。

独立性能入口：[java_index_paired.py](../../benchmarks/java_index_paired.py)。要求对应完整语义门禁已通过，并按发布回执核对全部生产输入和发行产物 SHA；计时工具自身 SHA 单列，文档提交不冒充运行时变更。三轮分别 Anatomist→code-ana、code-ana→Anatomist、Anatomist→code-ana，每次解包冻结 git 内容并使用新的索引/应用缓存；保留系统页缓存。两方 MAIN/TEST/XML、Lombok AST、分析器1GiB、javac512MiB、串行；性能对照均排除同一个已知坏编码文件，完整正确性验收仍保留它。新 Maven 清单仅容许项目路径搬迁，有序 classpath、字节摘要、模块及 scope 必须和语义验收输入一致。存在其他分析进程时拒绝计时。

code-ana 计时包含 Maven 导出、daemon启动、捕获、分析、传输、缓存、发布，以索引 CLI 成功退出为终点，查询与对照不计入。独立进程边界探针通过，验证模拟查询400ms被排除、Maven只计一次及环境恢复；它不代表真实性能通过。每轮倍率及两个中位耗时之比都须小于2，容量结果另按2GiB完整进程树/512MiB daemon/单worker及单javac判断。当前没有新的三轮成绩。
