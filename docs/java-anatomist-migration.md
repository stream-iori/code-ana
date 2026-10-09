# Anatomist Java 迁移

2026-10-07：Java 实现迁入 `lang-java-adapotr/`，直接复用 Anatomist 核心。旧 `lang-adaptor/` 已删除；历史记录仅用于追溯。

2026-10-09 最新计划核对：**SIGKILL 已复验消失；v15 修复已合入，最终完整替换验收仍在进行**。

| 项目 | 实际状态 |
|---|---|
| 核心迁移与 Rust/C ABI 边界 | 主目录源码、worker 和安装 bundle 均为 v15；冻结参考 JAR 不变 |
| v13 最终发布 | `single-project-v13-final` 发布、查询成功；业务语义 partial 原样保留 |
| v13 全量语义 | `semantic-v13-final` 8/14 通过；六个未通过领域保留原始失败 |
| v13/v14 模型收集 | 均已结束 160/160，严格通过 93；输入无漂移，原失败回执保留 |
| v14 候选 | 依赖模块 XML Bean 预登记、旧 DI 工具适配、完整调用差分、模型参考失败后继续独立后端验证、分层报告、移除无查询使用的 name 索引 |
| v14 基础验证 | JVM 97 项、native 12 项、Rust workspace 和 clippy 通过；冻结 fixture 10×14 类零差异；验证工具 23 项通过 |
| AST 模型差分根因 | 63 个 native raw.declarations 差分对应验收请求漏捕获 lombok.config；补齐祖先配置后，最小失败上下文的 14 类模型全部相等，原始声明逐条相等 |
| 私有真实库验证 | 2,451,465 条事实 ID/body 全量相等，5 组查询结果及计划相等；删除旧 name 索引产生 207.59 MiB 可复用页，原发布库不变 |
| v14 发行复验 | 用户修复宿主启动问题后四门禁通过；早期 SIGKILL 失败回执保留 |
| v15 验证 | JVM 100、native 15、Rust 113 项通过；clippy/fmt 通过；冻结 fixture 10×14 类零差异；发行四门禁通过 |
| v15 release 增量验证 | DI 修改/删除、跨模块 MAIN/TEST 两项通过；显式使用最终 release daemon/worker |
| v15 完整发布 | 发布与查询通过，2,447,969 条事实，1504.234 秒；无 SIGKILL，源码/产物无漂移，配置恢复；业务语义 partial 保留 |
| v15 调用核对 | 调用点 151,342、目标 151,381、归属 151,342 全部零差异；严格 calls 域仍因已知 1 条构造器声明差异失败 |
| v15 全量语义 | 14 类已结束，9 类通过；calls/declarations 的同一构造器差异，以及 XML bindings/wiring/graph 原失败保留 |
| v15 全量模型 | 160 个模型上下文继续运行；新请求捕获 Lombok 配置，并封存原始后端日志和配置摘要 |
| XML 连线后续修复 | 6 条缺失全部复现为同上下文限定/原始 Bean ID 冲突；隔离修复 JVM 101、native 16 项通过；新回归在原 v15 JVM/native 均失败，未修改 v15 模型输入 |
| 本轮范围 | 用户要求先完成模型问题汇总与下一轮修复方案；暂不考虑性能和三组独占冷启动 |

验收口径：用户已确认分层验收，分别报告迁移交付、可比范围与严格全量证明。
参考 JAR 的固定 512 MiB 解码失败和已证实的构造器身份缺陷单列；独立证明不覆盖原始失败回执。
新增报告只展示进度，未将 `full_semantic_diff_accepted`、容量或性能字段改成通过。

当前主目录证据：`target/java-migration-optimization/single-project-v13-final/receipt.json`、
`semantic-v13-final/receipt.json`、`project-models-v13-final/receipt.json`。
候选源码：`target/java-migration-implementation/work/`；证据：同级 `candidate-v14-*` 和
`layered-v13-progress.json`。历史版本回执只证明各自版本。

v15 同版本证据见 `target/java-migration-implementation/applied-v15/`；完整发布为
`target/java-migration-optimization/single-project-v15-final/`，模型收集为 `project-models-v15-final/`。
v13 原始源码已保存于 `target/java-migration-implementation/sealed-v13-source/`，旧安装 bundle
保留于 `target/lib/java-v13-retained-final/`。模型签名缓存仅用于正确性收集，按后端/模式分开；
APFS 副本具有独立 inode 且逐文件 SHA 相等，不继承旧上下文的通过结论。

后续调用根因已闭合：3493 个额外调用点全部对应三个投影缺陷——3251 个无 identifier
有效位置的枚举合成 NEW_CLASS、240 个显式 this/super 的表达式/语句范围不一致、2 个数组构造引用。
`calls-extra-evidence/site-shape-evidence.json` 保存每个原始 tree_id 和节点形状，未过滤原始差分。
独立 `callsite-candidate/` 按冻结 `JavaCallFacts` 的 eligibility 和 constructorStatement 规则修复，
保留原始 java.syntax/type 证据；调用语句包含分号，绑定 facet 仍保留原始表达式范围。
JVM 100 项及 native 15 项通过；三项新增 native 回归在未修复 v14 上全部失败，修复后全部通过。
该副本未改动主目录或 v14 收集输入，且仅为兼容宿主保留 v14 ABI 身份；不能代替最终同版本验收。
该补丁现已合入 v15，并完成重新编译和发行；v15 全量发布与差分继续验证，原 v13/v14 回执保留。

v14 的 keep-going 已实际继续参考失败后的两个后端：`raw app/test@TEST` 的 reference、
migrated_jvm、migrated_native 三者均触发相同固定 512 MiB 解码上限，独立后端比较也不可用。
三份原始错误和日志摘要见 `target/java-migration-implementation/all-backend-raw-decode-limit.json`。
该 raw 见证使用 Lombok off；v13 正式发布使用 Lombok model，发布库没有解码预算错误诊断。
不能把此处称为“仅参考端限制”，也不能据此断言正式产品发布失败；AST 与其余上下文继续收集。

v9 的 JVM 91 项、10 组 × 14 类冻结参考 fixture、Native 11 项与发行 4 门禁通过；Rust 工作区和 clippy 通过。v10 增加原始关系重复出现序号：参考库中 368 次完全相同位置/目标的类型引用重复出现，v8 投影 ID 去重丢失，现已定位并添加真实 stream/lambda fixture 回归。v10 JVM 92 项、参考 fixture 10×14 类、Native 11 项（40.796 秒）和发行 4 门禁（30.648 秒）通过；完整发布及查询已通过（1323.686 秒、partial、输入和产物无漂移）；并发诊断耗时不计入性能验收。160 上下文运行保留首个通过记录后已主动中断，等待修复后的最终冻结版本。

v10 正式发布图差分已结束：字段（47003）、注解/meta、Bean 注入、SOFA XML、DI 连线通过；其余域仍失败，raw/AST 完整模型证明缺失，整体门禁为失败，输入无漂移。证据：`target/java-migration-optimization/semantic-v10/receipt.json`。

v11 JVM 95 项（零失败/跳过）、Rust workspace 和 clippy 已通过；Native 11 项通过（52.372 秒），发行 4 门禁通过（34.008 秒、输入/产物无漂移），冻结参考 fixture 10 组 × 14 类零差异；完整发布和查询已通过（1340.346 秒、partial、输入/产物无漂移、配置已恢复）；并发诊断不计性能验收。模型收集和 14 域差分运行中。用户明确选择保留冻结参考 JAR，不调整解码预算；后续 `--keep-going` 收集剩余上下文，完整模型门禁仍按实际失败登记。

v11 本轮修复：

| 修复 | 验证边界 |
|---|---|
| Lombok 生成声明的类型引用 | 模型推导保留 `possible` / `lombok_model`，不标记 javac 已确认；新增 Getter 返回泛型类型回归 |
| 原始调用点控制上下文 | 在原始调用点写出前读取冻结核心的已解析/未解析调用上下文，保留 if/try/catch；新增混合解析状态回归 |
| 同名条件 XML bean 构造器 | 按核心提供的发现行选择唯一 bean occurrence；同一行仍有多个候选时保留歧义，禁止猜测 |
| 注册审计和冻结 JAR | 注册点读取原始 site 名；子审计显式使用传入的冻结 JAR |
| SQLite 发布缓存 | 8 MiB 调整为有界 64 MiB，保留现有索引、事务和取消检查；12 万行合成诊断 7.529 → 2.694 秒，正式三轮性能仍未通过 |

v12 已合入 14 条条件 XML member 绑定的归属修复、setter/构造器审计读取修复，以及仅用于任务私有去重库的 OFF 日志 / 64 MiB 缓存。发布数据库仍为 DELETE / FULL。主目录 JVM 95 项、Native 11 项（46.289 秒）、发行 4 门禁（23.084 秒）、冻结 fixture 10×14 类零差异；Rust workspace、clippy 与格式检查通过。XML 配置模型以独立冻结解析器为主对照（279/279），参考 SQL 数据库覆盖差异单列，符合能力对齐约定。v12 完整发布和 160 raw/AST 收集运行中；本轮从开始固定共享签名缓存策略，正式性能仍须冷缓存。v11 模型最终保留 7 个上下文记录（6 通过、1 冻结参考预算失败），因新归属修复已停止该版本收集，不充当 v12 证明。

v11 的 14 域正式差分已完成，7 域通过（类型、字段、注解/meta、Bean 注入、框架注册、SOFA XML、DI），输入无漂移；完整模型证明仍未完成。调用控制上下文缺失已清零，剩余 3493 个额外原始调用点未忽略。XML 审计漏读 176 个 setter 请求、构造器位置读错，已在隔离副本补严格读取和 2 项回归；同时实际发现 14 条条件同名 bean 的 config_member 边挂在外部占位符上，隔离修复按原始发现行选择唯一配置节点，Java fixture 已通过。上述候选和私有去重库优化尚未合入 v11，旧回执不能用于这些变更后的产物。模型收集原缓存策略已严格恢复；切换缓存策略被恢复器拒绝，失败日志保留。

参考库证明独立核对冻结 JAR、生成数据库、40 模块 Maven 依赖/源码目录、工具链与已提交源码。4217 个已提交 Java 文件中，4214 个属于配置的源码目录，1 个无效 UTF-8，预期分析 4213 个；另 3 个位于资源/未配置源码目录。macOS 的 `apiStandard` / `apistandard` 路径别名仅在唯一匹配且 `samefile` 证明成立时映射，不能按字符串小写任意归并。失败的生成回执保留，禁止覆写为验收通过。

SQLite 发布的父/目标检查改为覆盖索引上的 distinct endpoint 校验，重复引用只查询一次；悬空父/目标仍拒绝发布。新增测试覆盖重复引用、两个悬空错误以及补齐端点。合成 12 万行诊断从 0.188 秒到 0.102 秒，不能代替冻结项目三轮独占性能验收。


旧 14 域诊断只通过 beans/injections，其余没有通过。已有明确验收问题：字段访问审计只查旧 `reference` 而新核心边投影为 `relation`，部分脚本缺失字段时报错；匿名类型别名只读旧 `nesting_kind`；整域入口将 SQLite 参考数据库摘要与冻结参考 JAR 摘要比较。须先建立正确、独立的读取和来源校验，再判断剩余差异属于审计不兼容还是产品缺口，不能直接忽略或登记通过。

| 模块 | 来源与责任 |
|---|---|
| `analysis-core` | JavaUnit/JavaTypes/JavaBinding、javac worker/批处理、ASM、声明签名、JavaModelExtractor、静态 Lombok及必要依赖 |
| `framework-spring` | Spring 模型、注解元数据、XML 解析和成员解析 |
| `framework-sofa` | SOFA 注解及 XML 模型 |
| `native-bridge` | Rust 捕获输入、上下文/增量生命周期、证据投影、CAF1 输出、C ABI |

源代码清单固定 Anatomist `dff2e3954096853352ec91fcbfc6b995fbd4f390`。清单记录每个文件的原始 SHA256 与迁入摘要；静态 Lombok、框架和 Java 推导逻辑保留原实现。适配集中在显式输入、日志、进程超时和预算、Lombok 配置注入。参考测试随源码迁入。跨任务签名缓存的适配还包括捕获相对路径、任务内重定位和独立条目布局；204 个来源文件中 9 个有边界适配，摘要由清单校验。

| 协议 | 新值 | 兼容处理 |
|---|---|---|
| worker protobuf | 14 | 上下文携带实际 source_roots；拒绝旧 worker |
| C ABI | 2 | 调用签名、分块、取消、释放不变 |
| analysis request/result | 8 | 原生结果使用封存 CAF1 描述符；拒绝旧 facts 数组协议 |
| bundle manifest | 2 | 校验 adapter identity、协议、图 schema 与产物摘要 |
| javac batch / model | 2 / `javac-model-v50` | 使用迁入代码原生协议 |
| 公共 Fact IR | 1 | UTF-8 范围、owner、图式类型和发布校验继续由 Rust 管理 |

```text
Rust 捕获文件和上下文
  → hash 校验 → 任务私有输入目录
  → javac 原始绑定与类型图
  → 原 Anatomist 提取/框架分析
  → Fact IR 身份与 UTF-8 坐标投影
  → Rust 校验 → 发布成功后确认
```

| 约束 | 实现 |
|---|---|
| 可见输入 | 使用 Rust source/publication/classpath/module 清单；新入口不重新发现源码或解析 POM |
| 构建副作用 | `-proc:none`，不生成业务 class；签名缓存仅用于索引 |
| Lombok | 只用静态模型；本地与祖先配置均从捕获资源注入 |
| 原始证据 | javac 绑定与后续恢复边分别保留，恢复不会改写原绑定 |
| 类型与语法 | 有限类型图；树节点分片，保留原树 ID、字段和父关系 |
| 生命周期 | 每次请求独立 scratch，完成/失败/取消后回收；结果输出有事实数和字节上限 |
| 增量 | 按 context digest 替换 owner；新 adapter identity 不复用旧分析 provenance |

以下为迁入初期的历史验收；当前状态见文首，历史回执不自动证明新产物通过：

| 门禁 | 状态 |
|---|---|
| 迁入 JVM 单元与集成测试 | 77 项通过；覆盖 Java、Lombok、Spring、类型/注解、签名、并行上下文和 capture 显示编号稳定性 |
| Rust workspace 单元测试、clippy 和 Rust 端到端测试 | 104 项单元测试、clippy、15 项端到端测试通过 |
| 冻结参考独立进程对照 | 9 组 fixture × 14 类原始模型/提取数据，零差异；包含 Spring/SOFA/XML |
| 新共享库编译、Java 8 major 校验 | 已通过 |
| C ABI 原生 fixture | 8 项通过：跨进程签名缓存复用/损坏恢复、XML 嵌套模型、真实 javac、类型/绑定/语法输出、取消、预算、处理器陷阱、输入摘要与旧协议拒绝 |
| daemon 产品回归 | 已通过基础全量/增量、80/81 层类型图、Maven MAIN/TEST XML-only、空项目删除、嵌套类型签名、无效编码/strict、取消与崩溃保留旧版；其余旧框架 schema 对照未全部通过 |
| 独立 release 包 | 编译、打包、SHA256 全量校验和包内基础全量/增量回归通过 |
| 冻结 iopengw raw/AST 各 80 上下文及 14 域差分 | 未完成；历史适配器回执不计入 |
| 交替 3 轮独占性能与中位数 < 2× 参考 | 未完成 |

尚不能宣称完整替换验收通过。跨任务签名缓存已接入 Rust 发布 Ack 后的配额/LRU 管理，原生 fixture 验证重定位、复用和损坏恢复；编译器临时文件排除在可提交条目外。XML 嵌套模型、参数/局部变量和 source 签名投影已补齐；XML/DI wiring 产品对照通过，局部变量生产 worker 协议已验证。XML 项目视图按捕获输入过滤可见性并恢复增量继承关系。

2026-10-08 的 SIGKILL 排查发现 debug 二进制仍使用 worker 12，而源码/release 使用 worker 13。已清理模型构建缓存重编译，并在测试运行目录与发行包冻结前检查实际二进制版本。native 库改为构建后原子替换，避免原地写入已被进程映射的签名代码。原始两次 SIGKILL 的系统原因尚无本轮日志证据。直接运行 daemon 索引引擎的真实捕获/supervisor/发布已通过 3 轮并验证 delta/full 一致；后续 HTTP 端到端已启动成功：capture 修复后局部变量/Lambda 的全量、增量与重新全量一致性通过（54.175 秒），XML wiring 与冻结参考的对照和增量通过。本轮 worker PID 18433 的 SIGKILL 经用户确认是安全拦截，放行后同一产物重试通过。见 [SIGKILL 证据](java-sigkill-diagnosis.md)。

历史实现和历史验证说明保留在 `docs/java-implementation.md`、`docs/implementation/java-validation.md`；旧 JVM 回归入口为 `scripts/test-java-reference.sh`。新默认 JVM 回归入口为 `scripts/test-java.sh`。

本次本地证据（均在忽略的 `target/` 中，命令可重跑）：

| 证据 | 文件 |
|---|---|
| JVM 与共享库构建 | `target/lang-java-adapotr-final-build.log` |
| 原始模型/提取独立差分 | `target/lang-java-adapotr-reference.json` |
| 原生 C ABI | `target/lang-java-adapotr-native-final.log` |
| 产品基础、编码、嵌套签名、取消/崩溃 | `target/lang-java-adapotr-product-final.log` |
| 深层类型和 MAIN/TEST XML | `target/lang-java-adapotr-product-tests-5.log` |
| Rust | `target/lang-java-adapotr-rust-tests.log`、`target/lang-java-adapotr-rust-e2e.log`、`target/lang-java-adapotr-clippy.log` |
| release 包路径、完整性、产品回归 | `target/lang-java-adapotr-package-path.txt`、`target/lang-java-adapotr-package-check.log`、`target/lang-java-adapotr-package-test.log` |

2026-10-08 独立真实模型诊断：`app/common/service/facade@MAIN` 的 81 文件，raw/AST 与参考/JVM/native 原始数据均零差异。完整 raw/AST 门禁首个 `app/core/service@MAIN` 在冻结参考报 javac NPE（2624 输入/547 发布源码），回执 `target/java-migration-models-full-signatures/receipt.json` 保留失败；14 个发布语义域与性能不登记为通过。

2026-10-08 继续验收：

| 项目 | 修正与证据 |
|---|---|
| 公共类型显示编号 | javac `capture#数字` 仅在类型拼写字段按图内出现顺序稳定化，保留重复引用与不同 capture；原始模型、注解值及引号内文本不变。独立 witness 不使用产品 normalizer，也不再对整个 JSON 做正则替换 |
| fixture 原始模型对照 | 10 组 × 14 类，零差异；新增 capture 类型注解字符串字面量用例。`target/lang-java-adapotr-continue-reference-final.log` |
| Maven 对照输入 | 上游模块保留各自 MAIN classpath，TEST 增加本模块 MAIN 前置上下文；不再给上游套用消费模块的 JAR 列表 |
| 参考源码清单 | witness 把文件路径当目录源码根，参考 ProjectScanner 因此丢掉辅助源码。诊断定位 DomainCommunicationConfigCache.java:100 的 Lambda 参数类型为 `<any?>`，SDK8 Attr.java:4604 在校验空 vartype 时 NPE。witness 改为给冻结后端原有规划器提供完整不可变 JavaSourcePlan；修正后该上下文参考 raw/AST 均成功，分别 134.824/65.886 秒；这是单上下文诊断耗时，不作为整项目性能门禁。证据 target/lang-java-adapotr-continue-reference-source-plan.log；迁入 JVM/native 与完整 80 上下文仍须对照 |
| binary-only XML 边界 | 冻结 Anatomist 实际索引也不推断外部工厂产物和继承成员，BINDS_TO 保留 resolutionStatus=unresolved、candidateCount=0。旧 adapter 的额外能力回归不可直接当成迁入核心对齐门禁。参考数据库及记录：target/java-migration-continue/binary-xml-reference/ |
| 资源外部符号 | v4 投影将资源模型占位符与 javac 外部声明分开标识；framework_model、possible、两个证据标志为 false，保留原始 unresolved 元数据，避免把模型请求伪装成已解析声明 |

本轮最终 native、产品回归、完整 iopengw、发行包及三轮性能仍须以对应最终产物回执登记；先前 v2/v3 回执不自动证明 v4 通过。

2026-10-08 v4 产品续验：同一产物放行后局部变量/Lambda、增量/重新全量通过（42.710 秒），XML wiring 与冻结参考对照通过。新发现 XML 嵌套 bean 的公共关系和 SOFA 子元素查询投影缺失；只从原始模型已有字段补投影，不补 Java 推断算法。开发 daemon 优先使用同目录安装的 bundle，避免冻结运行目录实际加载工程中的可变开发库；无安装 bundle 时 debug 才使用开发库。新产物的对应回归待重跑。

最终源码 JVM 回归 77 项通过（target/lang-java-adapotr-continue-final-jvm.log），源码清单校验仍为 204/8。新增无自身 Java 源码的 scope 不生成重复 javac 任务：MAIN 前置仍编译，TEST XML 保留辅助 MAIN 类型查询。XML 字节跨度把 SAX 的开始标签末尾坐标转换成真实开始标签起点，原始 SAX position 单独保留；新增中文/emoji 前缀验证。

2026-10-08 v4 同产物放行后续验：

| 门禁 | 本轮结果 |
|---|---|
| 最终冻结 native / daemon fixture | native 10 项通过（26.139 秒）；daemon 3 项通过（196.503 秒），含 capture 全量/增量、XML wiring、嵌套 bean/SOFA 与字节跨度 |
| release 包启动与查询 | 同一包放行后 4 门禁通过（34.260 秒），包含核心全量/增量、MAIN/TEST XML-only、错误 JDK 拒绝和包内 fixture 自动 daemon 的 index/calls/source；包、源码和打包输入摘要无变化。回执 `target/java-migration-continue/release-verified-v4/receipt.json` |
| iopengw MAIN/TEST/XML，native 堆 512 MiB | 221.284 秒后 `NATIVE_HEAP_EXHAUSTED`，未发布；回执 `target/java-migration-continue/iopengw-v4-verified/receipt.json` |
| 同代码/输入，native 堆 1 GiB、worker 2 GiB | 518.999 秒后同样堆耗尽，进程树峰值 1,971,961,856 字节；无 SIGKILL，源码与运行产物摘要无变化，配置已恢复。回执 `target/java-migration-continue/iopengw-v4-1g/receipt.json` |

发行包基本可用门禁已通过，但完整项目发布、raw/AST 模型对照、14 域语义差分和三轮性能仍未通过。下一步须定位 native 堆的实际保留对象，再对任务级去重表、符号表、项目资源视图等候选增长点做有界化；现有证据仅证明堆耗尽，不足以断言某一个表是根因。发行验证器的输入摘要已改为 `lang-java-adapotr/` 和实际 release 二进制，不再记录历史 adapter 的产物。

用户要求 2 GiB 重试后，显式 native 堆上限放宽至 2 GiB，单 worker 总预算上限至 4 GiB；默认值与 Java 语义算法不变。Rust workspace lib 回归和更新后的 contracts 边界测试通过，release 构建通过，实际 isolate 堆探针返回 2147483648。冻结 iopengw MAIN/TEST/XML 的同一 Java 库重试运行 1086.611 秒（18 分 6.611 秒），未出现 SIGKILL 或堆耗尽；进程树峰值 2,594,799,616 字节。随后 `CONFLICTING_FACT_ID: symbol_c3aed7b2f276f66e41a1df95fa5f3fcce61968315953cbf62f0334247ea2815e` 使任务失败，未发布、未执行发布后查询。源码/产物摘要无变化，配置已恢复。证据：`target/java-migration-continue/iopengw-v4-2g/receipt.json`、`target/java-2g-heap-probe.log`、`target/java-2g-rust-tests.log`、`target/java-2g-contract-tests.log`。下一步应保存该 ID 的两个候选事实并定位身份/内容差异；不能仅跳过冲突，也不能把本轮登记为完整项目或性能通过。

2026-10-08 直接 Java 性能诊断：冻结 Anatomist CLI 成功索引耗时 186.295 秒；相同有效 Java / 66 个编译任务外部 classpath 的直接 JVM/native 分别在 150.280 / 633.943 秒复现同一事实冲突。native 的 unit_projection 已完成部分耗时 421.536 秒，GC 358.834 秒（约 57%，与阶段计时重叠）。重复 JSON 编码、文件被当作源码根逐个匹配、辅助声明库存反复累加已取得代码和采样证据。新增任务内诊断钩子与直接请求/探针，Java 77 项回归和 204/8 来源校验通过，协议、C ABI、适配器身份不变。原 daemon 多出的 452.668 秒尚未全部归因；完整项目发布和性能门禁仍未通过。测量边界、原版实现对照和修复顺序见 [性能诊断](java-performance-diagnosis.md)。


2026-10-08 v5 性能修复与同版本回归：

| 改动 | 边界与验证 |
|---|---|
| 构造器身份冲突 | 合法的同类名普通方法与构造器分开；所有声明引用沿用同一身份。真实冲突证据包含隐式构造器与 `void IOpengwManagerServiceImplTest()`，不能跳过冲突。冻结上游也存在该身份缺陷 |
| 源码根与编码 | Rust 传真实源码根，显式源码清单仍固定；单条事实编码一次，用相同字节计算摘要及写 CAF1 |
| 声明和类型图 | 任务内声明目录有界复用，类型图缓存按上下文释放；ProjectView 提前去重，保留辅助类型可见性和 XML-only 输入 |
| native 输出 | Java 写封存 CAF1，不构建全项目事实 JSON/去重表；Rust 逐条校验并用临时 SQLite 做全局去重，校验完整归档摘要后才允许交付。冲突保留两个事实，失败不发布 |
| 协议与缓存 | worker 14 / request 8 / ABI 2 / Fact IR 1，adapter v5；旧 bundle 和派生缓存拒绝或 miss |
| JVM / Rust / native | JVM 82 项、Rust workspace 105 项、原生 10 项通过；本轮未重新登记 clippy 或所有 Rust E2E |
| 参考 fixture | 冻结 JAR 独立对照：10 组 × 14 类，零差异 |
| daemon 重试 | 用户确认 SIGKILL 已处理后，同一产物核心、深层类型图、MAIN/TEST XML-only 3 项通过，207.723 秒 |
| 真实项目 | iopengw MAIN/TEST/XML 正在独占重跑；raw/AST 各 80 上下文、14 发布语义域、3 轮性能及最终发行仍未通过 |

当前回归日志位于 `target/java-migration-continue/perf-fix-*`。诊断显式设置 `CODEANA_JAVA_DIAGNOSTICS_DIR`，保存原始请求、初始输入/缓存快照、冻结运行产物及阶段计时；默认关闭。重放各运行必须复制初始快照，不能复用另一运行写过的缓存。大项目诊断使用 worker `--probe-archive <新文件路径>`，避免展开完整 JSON；`--probe-timeout-seconds` 仅调整诊断保护时限，不改变正式任务期限。原始历史 452.668 秒差额尚无完整证据，不按新回归倒推归因。

2026-10-08 v5 真实重试终态：iopengw MAIN/TEST/XML 513.089 秒在 CAF1 的 4 GiB 上限失败（FACT_BYTE_LIMIT），无 SIGKILL、无源码/产物漂移，未发布。声明目录79.312秒、事实投影210.860秒、javac等待80.935秒；65个batch、2958213条物理归档记录。小例子证明外部符号重复生成及原始类型/绑定投影放大；详见性能诊断的交付层复核。旧lang-adaptor已由用户删除，部分历史验收入口仍待迁移，不能关闭全量/性能门禁。


2026-10-08 v7 迁移与交付优化续验：

| 改动 | 当前证据与边界 |
|---|---|
| 外部目标复用 | 按上下文、签名、来源与完整元数据复用，4096 项 / 4 MiB；变化仍输出并由 Rust 检测身份冲突。100 次重复调用回归保留全部调用，仅生成 2 个外部声明 |
| 注解元关系复用 | 只抑制相同上下文、来源、注解、元注解与 resolution 的重复投影；32000 项 / 16 MiB，有界缓存不合并不同证据 |
| 类型图编码 | 有界缓存不可变编码，保持 JSON 字节与嵌套预算；恢复 capture 显示编号状态，避免缓存命中改变后续参数拼写 |
| XML 模板重复定义 | 真实项目同名 bean 分别出现在两个 #if 分支。按原始坐标区分出现位置，歧义引用保留候选与 possible 证据；不执行模板条件、不改写 Anatomist 原模型 |
| Java 8 桥与参考入口 | 删除旧目录依赖；桥从迁入核心的内嵌 worker 提取并校验 major 52，参考入口使用冻结 JAR 独立 witness |
| 失败证据 | Rust 保存不合法事实及偏移，重放支持 macOS /var 与 /private/var 别名；失败仍不发布 |
| JVM / 冻结 fixture | JVM 87 项通过；10 组 × 14 类原始模型对照零差异。大模型 witness 使用完整行 SHA256 的排序多重集，明确标记 sorted-row-sha256-v1；不累计大 JSON 字符串 |
| Native / 产品 / 发行包 | v7 Native 11 项通过；核心、XML-only、深层类型、冻结 XML wiring 4 项产品测试通过；发行包 4 门禁 all_ok=true，源码与产物无漂移 |
| 真实 XML 重放 | 122 个资源、6453 条事实通过 Rust 图校验，279 个 bean 的身份和嵌套坐标有效；这里只证明 XML 子集 |
| 整项目门禁 | v7 iopengw 发布及 raw/AST 原始模型仍在取证；14 域发布差分和三轮独占性能尚未通过 |

v7 阶段 adapter identity 为 `anatomist-dff2e395-codeana-v7`；worker 14 / request 8 / ABI 2 保持不变。Native 库通过原子替换安装。证据位于 `target/java-migration-optimization/`，JVM/native/Rust 日志位于同级 `target/java-migration-optimization-*.log`。


v7 完整发布续验首次失败于 16 GiB scratch 预算（1326.384 秒），不是已通过的全项目门禁。Rust owned 归档消费已改为校验、去重归档封存成功后回收原生副本；失败和探针仍保留原文件，Ack 仍在成功发布之后。7 项 worker 测试及 clippy 通过。

原始模型 witness 补充目标 module/scope 过滤：前置模块仍提供签名，不计入当前模块的 raw 统计，它们会在各自上下文单独验收；每个上下文释放 type 对象摘要缓存。最大模块 `app/core/service@MAIN`（2624 输入、547 发布源码）在参考/JVM 的同缓存口径重验中 14 类均零差异。冷缓存及 Native 重验尚未完成，不能将单模块诊断登记为完整 raw/AST 门禁。逐源码摘要和原始诊断可用 `--row-evidence` 输出；模型超时也写失败阶段和日志，避免回执永远停在 running。


2026-10-08 v7 owned 归档续验成功：冻结 iopengw MAIN/TEST/XML 全流程发布并完成发布后查询，932.997 秒，进程树峰值 1767931904 字节；源码/产物无漂移，配置已恢复。回执 `target/java-migration-optimization/single-project-v7-owned/receipt.json`。原始 javac 诊断和 partial 覆盖均保留，因此 publication_accepted=true、semantic_index_accepted=false；完整语义与性能门禁未登记为通过。发行包同版 4 门禁 all_ok=true（29.146 秒）。Native 分析 398.983 秒、投影 137.946 秒；运行有并发，不计三轮性能门禁。

随后 raw 模型对照定位到 Java 8 捕获显示编号重号：`Printer$1.capturedVarId` 实际执行对象 hash 对 997 取模。`ApiStandardConstruct.java` 的原图节点 499 与 773 是两个独立捕获，参考显示编号却相同；除显示拼写外，图结构一致。以显示字符串作为身份会错误合并。v8 投影按原始类型图节点生成显示标签，并将参数/返回类型拼写关联到对应图根；缓存只依赖字典身份与根，不再保留字符串编号状态。原始 Anatomist 模型、图边与注解/源码字面量不改写。独立 witness 自行按原图生成标签，编码标记 `sorted-row-sha256-v2-graph-captures`，不调用产品 normalizer。

当前源码 adapter identity 为 `anatomist-dff2e395-codeana-v8`；JVM 87 项、冻结 fixture 10 组 × 14 类和 Rust worker 7 项通过。v8 Native 11 项通过（59.417 秒），发行包 4 门禁 all_ok=true（36.729 秒）且源码/产物无漂移；真实项目及完整语义、性能仍未完成，v7 成功不自动证明 v8 成功。


### Rust 存储迁移补充（2026-10-08）

按用户反馈，将 Anatomist Java 存储设计与逻辑纳入迁移；详情见
[存储对照与验证](storage-anatomist-migration.md)。首批已合入 Rust：DDL 拆分、定向 reference 查询、
复合与条件索引、入库后准备查询索引、私有候选旧索引升级。workspace 111 个测试、clippy、
发布包四条全流程通过；全量空间与性能尚未验收。

v12 旧模型收集在 23 个上下文后封存（中断时输入/源码无变化）；
`project-models-v12-storage` 使用新源码和原 Java v12 产物重新比较全部上下文。
只复用经过后端校验的签名缓存，不晋升旧上下文通过回执。冻结参考 JAR 保留，512 MiB 解码限制继续记录。
XML 引用出现位置的 Java v13 修复仍在隔离副本，不能将其验证结果算作当前 v12 已发布行为。


### 最终 v13 候选

已合入 FTS5、XML_REFERS_TO 源出现位置与逐位置候选计数修复、原始模型的语法/绑定编号同步归一。
Java 96/Rust 113/native 11 测试、参考 fixture 10 × 14 和发布包四条链路通过（22.656 秒）。
最终模型/全量发布证据分别为 `project-models-v13`、`single-project-v13`，仍在收集；源与产物冻结。

`app/common/service/facade@TEST` 编号差异已证实是同一语法树内部编号起点不同，修复后的 14 类模型全部相等。
`app/biz/service-impl@TEST` 原始 syntax/bindings 相等；剩余 nodes/edges/declarations 各一条变化来自
`IOpengwManagerServiceImplTest` 的隐式构造器。冻结参考赋成同类名方法 ID，与源码合法的 `void 同类名()` 冲突；
迁移端使用 `<init>` 分开两个声明。原始差分与源码证据保留，能力对齐不要求复制此 ID 冲突。


本轮 SIGKILL 恢复后的最终发布/语义回执：`single-project-v15-final/receipt.json`、
`semantic-v15-final/receipt.json`。调用点、目标、归属全部相等，lexical_controls 已通过。
完整 XML 仍失败，不晋升严格门禁：同上下文重复登记导致的六条连线缺失，
独立原始输入复现与全部缺失逐条对应，见 `xml-catalog-diagnostic/receipt.json`；
隔离修复位于 `target/java-migration-implementation/xml-own-catalogue-candidate/`。
新增回归在 v15 原 JVM/native 实现均失败；隔离修复的 JVM 101、native 16 项全部通过。
首次隔离 native 包漏生成 manifest 导致入口拒绝，原日志保留，补齐清单后复测通过。
主目录模型收集运行期间不应用该补丁；仍需合入后的 native/发行及全量验证。
冻结库两条 candidateCount=0 但目标声明非空的关系已保留 SQL 原始证据，见
`xml-reference-binding-metadata-evidence.json`，未覆盖原始严格失败。


## 2026-10-09：后续范围调整

用户要求保留当前全量模型收集，结束后合并模型与全量语义问题，给出下一轮修复方案。
本轮不执行性能优化验收或三组独占冷启动；不提前合入 XML 候选，避免改变模型收集输入。
六条 XML 连线缺失已证明是适配层将本上下文 Bean 用限定 ID 重复提供给原 XML 分析器，
原分析器用原始 ID 再登记，putUnique 因 ID 不同删除候选；跨文件 ref 随后无法得到目标类，
因此不生成 WIRES。Rust 存储不是这六条缺失的发生点。
隔离修复只让预登记目录提供其他可见上下文的依赖 MAIN Bean，本上下文由 XML 分析器登记。
原 v15 JVM/native 新用例均失败，隔离修复 JVM 101/native 16 项通过；正式合入仍需下一轮验收。


2026-10-09 用户要求停止全量分析：已检查模型分析、native 见证及自动汇总进程均不在运行。
模型收集停于 7/160、6 项严格通过，原始证据保留；修正遗留 running 回执为 interrupted，
停止等待模型结束后的自动汇总，不自动恢复或合入修复。原回执另存 receipt-before-user-stop.json。
