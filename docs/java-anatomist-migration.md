# Anatomist Java 迁移

2026-10-07：Java 实现迁入 `lang-java-adapotr/`。`lang-adaptor/` 保留参考；新实现直接复用 Anatomist 代码，不继续维护旧适配器中的 Java 推导算法。

| 模块 | 来源与责任 |
|---|---|
| `analysis-core` | JavaUnit/JavaTypes/JavaBinding、javac worker/批处理、ASM、声明签名、JavaModelExtractor、静态 Lombok及必要依赖 |
| `framework-spring` | Spring 模型、注解元数据、XML 解析和成员解析 |
| `framework-sofa` | SOFA 注解及 XML 模型 |
| `native-bridge` | Rust 捕获输入、上下文/增量生命周期、证据投影、CAF1 输出、C ABI |

源代码清单固定 Anatomist `dff2e3954096853352ec91fcbfc6b995fbd4f390`。清单记录每个文件的原始 SHA256 与迁入摘要；静态 Lombok、框架和 Java 推导逻辑保留原实现。适配集中在显式输入、日志、进程超时和预算、Lombok 配置注入。参考测试随源码迁入。跨任务签名缓存的适配还包括捕获相对路径、任务内重定位和独立条目布局；204 个来源文件中 8 个有边界适配，摘要由清单校验。

| 协议 | 新值 | 兼容处理 |
|---|---|---|
| worker protobuf | 13 | 新增上下文的 reactor 依赖模块列表；拒绝旧 worker |
| C ABI | 2 | 调用签名、分块、取消、释放不变 |
| analysis request/result | 7 | 原始模型不再使用旧适配器私有协议；拒绝 request 6 |
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

验收记录以本工程实际命令为准：

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
