# Java 8 实施验证记录

日期：2026-09-29。平台：macOS / arm64。参考 Anatomist `dev/replace-parser@2dfc7977cb03095d14894f069ff30985551457b1`，未运行 Anatomist 兼容测试。

| 工具链 | 本次实际版本 |
|---|---|
| native 构建 | SDKMAN `25.3.4+1.r25-graalce`；Java `25.0.4.1`，GraalVM CE `25.3.4.1+1.1` |
| 目标编译器 | SDKMAN `8.0.492-zulu`；Java `1.8.0_492`，macOS aarch64 |
| 编译器桥 | 全部 class major version 52；原生项目仍为 Java 25 |
| 协议 | 当前 worker 5、Fact IR 1、native ABI 1、compiler 3、Java model 3；下表部分旧记录对应 worker 3/4 |

```text
包内 CLI → gRPC daemon → 独立 Rust host → 共享库 C ABI → Java 8 javac
                                    ← Fact IR ← 独立模型 ← parse/analyze
             ↓
     candidate 校验 → 原子发布 → resolve / calls / call-sites / source
```

## 历史验收（2026-09-29）

| 入口 | 结果与证据 |
|---|---|
| `cargo test --workspace --locked` | 27 项通过；含 metadata 过期、环、生成输入证明、建议关闭、protobuf 分配预算 |
| `cargo clippy --workspace --all-targets --locked -- -D warnings` | 通过 |
| `python3 tests/rust_e2e.py` | 14 项通过；含发布故障、取消、replan、GC/读者、独立 mock 无 JDK 路径 |
| `scripts/test-java.sh` | 7 项通过；UTF-8、重复调用、缺失/歧义、初始化块/lambda/local、Lombok/Spring、SOFA、Bean 未知名字、冷增量 |
| `python3 tests/java_native_probe.py` | 实际共享库通过；环境中错误 JAVA_HOME/PATH/JAVA_TOOL_OPTIONS 不影响显式 SDK；取消清理；处理器陷阱未执行；版本与 hash 篡改拒绝 |
| `python3 tests/java_maven_export.py` | 真实 Maven effective-pom/build-classpath 导出通过；只在测试显式运行，未生成业务 class |
| debug `python3 tests/java_e2e.py` | 5 项通过；真实发布查询、修改/no-op 冷增量=full、多模块 MAIN/TEST、XML 删除、partial/strict、错误 JDK、真实取消/强杀及子进程清理 |
| 成套 release | `just test-java-release` 通过；macOS arm64 包内 7 项通过（最终 32.230 秒）；含最后源码删除、显式 generated 输入、Spring 关闭清理、8 MiB RSS 超限保留旧 head；全部 SHA256SUMS 校验通过 |

期望来源是本项目 fixture 源码和独立断言；cold delta/full 比较完整事实（含 coverage、诊断、provenance），不是只比数量。Java JVM 模型测试与真正共享库/daemon 测试分开记账。

## 修复过的接缝问题

| 证据 | 修复 |
|---|---|
| daemon 启动 worker 被 SIGKILL，系统日志命中 AspectService `kEdrRuleProcessCreate` DENY | 用户经本机安全管理放行后重跑成功；没有修改安全软件或绕过规则 |
| delta 出现重复 replacement owner | Java owner 去重改用固定字段元组，避免依赖 Rust 重编码后的 JSON map 顺序 |
| no-op XML 事实被误删除 | 框架事实携带 context，按真实变更的 context 清理 resource owner |
| 本机 `/var`→`/private/var` 导致 metadata 路径校验失败 | 先规范化项目根，再校验 containment |
| Bean destroyMethod 被误当名称、SOFA 被 Spring 开关挡住 | 仅识别 name/value；未知名字披露 partial；当前静态 SOFA XML 清单独立启用 |

## 能力边界与后续

| 已覆盖 | 尚未作为通过项 |
|---|---|
| Java 8 fixture 的真实 native 链路与核心查询 | Java 17/25、Linux/其他架构、JPMS/Gradle、混合 JDK |
| 显式 plain、外部 Maven metadata、有序 JAR、源码/资源捕获；schema 2 记录 effective model、active profiles 和外部 settings 摘要 | Maven 任意自定义布局、所有隐式环境变量的重验、任意逻辑模块标签映射 |
| javac 确认绑定、错误保留、结构化类型树与 `java.type`/`java.binding` facet、SAM/方法引用基础模型、ASM 9.8 外部 JAR 类头/成员签名/注解/常量/异常 | 全部高级泛型推断查询、完整派发推断 |
| Lombok getter/setter 声明子集、Spring/静态 SOFA XML | 完整 Lombok 方法体、复杂元注解、Spring 容器/AOP/条件选择、SOFA 运行时装配 |
| 模块×scope 增量、owner 级差异交付、上游输入变化传播；安全单文件编辑使用 NoCode 缓存缩减 javac 输入；默认热 worker；预算 ≥1 GiB 时双 context 并行 | 泛型/注解/嵌套源码的缓存投影、任意 Maven 布局及大型项目容量 |
| 受限缓冲、堆设置、进程组 RSS 门禁；20 轮压力和 OOME/取消/强杀/磁盘满及 daemon SIGSEGV 专项 | 大型项目容量和更长周期的资源趋势 |

这些限制是实际实现边界，不能把本表等同于旧 `P0/P1/P2` 所有复合任务通过。未支持的 framework 推断保留 partial，不补造 confirmed 证据。GraalVM 构建输出中运行时自身的 JNI registration 不代表应用使用 JNI；应用边界仅 C ABI。

## 分发复现

`just test-java-release` 构建并校验 `dist/codeana-java-Darwin-arm64/`，随后设置 `CODEANA_TEST_BIN` 指向包内 `bin` 运行同一组 7 项生产测试。包内含单独的 README、fixture、工具链前置条件、header、bundle manifest 与总 SHA256SUMS。release 没有 debug 构建目录回退。

本轮 release 首次重跑时再次出现启动阶段 SIGKILL；用户确认本机进程处理恢复后，未改 worker 分析实现即 7 项全部通过。该失败不记为语义通过，也未通过改路径/停安全服务规避。

## 扩展实现记录（历史）

| 验证 | 本轮结果 |
|---|---|
| `scripts/test-java.sh` | 14 个模型 fixture 通过：结构化泛型/选中绑定、ASM 外部 JAR、Lombok/Spring/SOFA、失败查找、owner 级 delta、串/并行等价 |
| `cargo test --workspace --locked` | 28 个 Rust 单测通过；schema 2 settings 变更拒绝 |
| `cargo clippy --workspace --all-targets --locked -- -D warnings`、`python3 tests/rust_e2e.py` | 无警告；14 个 Rust 产品回归通过 |
| `python3 tests/java_maven_export.py` | schema 2 真实 Maven 导出、显式 profile 与 active profile 身份通过 |
| `python3 tests/java_parallel_benchmark.py` | 5 次预热、20 次计时，452 个事实逐次相等；串行中位数 0.804 秒，并行 0.537 秒，比例 0.668 |
| `python3 tests/java_native_probe.py` | native → Java 8、取消清理、禁用处理器、bundle 拒绝、两个 javac 子进程同时运行与串行事实等价通过 |
| `just test-java-release` | macOS arm64 成套构建、全部 SHA256SUMS 校验、包内 7 项 daemon 验收通过（33.551 秒） |

上述 benchmark 只覆盖两个独立 fixture context；并行默认门槛仍受 1 GiB 进程组 RSS 限制，不把它当作大型项目容量证明。当时 owner 级 delta 只减少传输和发布替换；下节记录新增的文件级缓存和热复用验收。

## 本轮实现与验证（2026-09-30）

| 验证 | 实际结果 |
|---|---|
| `./scripts/test-java.sh` | 19 个 Java 模型 fixture 通过；新增 SAM/方法引用、二进制元数据、文件级缩减、缓存关闭/损坏/声明变化/泛型与注解回退、Spring 上下文等价 |
| `cargo test --workspace --locked`、`cargo clippy --workspace --all-targets --locked -- -D warnings` | 29 个 Rust 单测通过；Clippy 无警告 |
| `python3 tests/rust_e2e.py` | 14 个 Rust 产品回归通过 |
| `python3 tests/java_native_probe.py` | 真正共享库 → Java 8，通过文件级增量/full 等价；伪造 C ABI 共享库在独立 host 中触发实际 SIGSEGV，host 以 signal 11 退出 |
| `python3 tests/java_maven_export.py` | 真实 Maven effective model 和有序 classpath 导出通过 |
| `python3 tests/java_e2e.py` | 9 个生产 daemon 测试通过（308.902 秒）；热 worker PID 连续、发布查询、delta/full、Maven/框架、重复取消/强杀、8 MiB javac OOME、真实受限 HFS+ 卷 ENOSPC、RSS 门禁及错误 JDK；故障均保留旧 head 并回收子进程 |
| `python3 tests/java_native_crash_e2e.py` | debug 与包内 release 各严格确认 `exit=signal: 11 (SIGSEGV)`；替换为经 manifest 校验的 C ABI 共享库，在分析入口触发崩溃；daemon 保留旧 head、继续响应、回收 worker。包内首轮曾收到 `signal: 9 (SIGKILL)`，用户处理本机进程拦截后重跑通过 |
| `python3 tests/java_stress.py --rounds 20 --helpers 64` | 20 轮同一热 worker PID；4 次增量/full 等价与 GC 后历史读取；空闲 RSS 86,851,584–116,654,080 字节，波动小于 128 MiB；耗时 359.727 秒 |
| `just test-java-release` | macOS arm64 包构建、所有 SHA256SUMS 和包内 9 个 daemon 用例通过（43.452 秒）；同包原生 SIGSEGV 注入随后严格复测通过，已纳入 release 验收入口 |

上述历史轮次的文件级缓存只投影可证明完整的简单声明，缓存项 8 MiB、总量 256 MiB；满额停止写入并回退完整分析。泛型、注解、嵌套等更复杂形状尚未纳入该优化。本轮故障注入以 20 轮 fixture 和本机受限卷为界，尚不构成大型项目容量证明。

## Anatomist 对齐实施与当前验证（2026-09-30，后续轮次）

以下记录覆盖前面的历史边界；不将历史 release/daemon 通过当作当前修改的验收。参考冻结为 `dev/replace-parser@2dfc7977cb03095d14894f069ff30985551457b1`。

| 项目 | 已执行与实现 |
|---|---|
| Maven | schema 3；一次 Java 8 Maven 会话，生命周期前停止，Resolver 调解后输出 reactor/外部依赖；selection/nonce/hash/control 回执、timeout/取消、有界日志。1 个导出和 6 个调解 fixture 通过 |
| NoCode | 6 个测试：泛型/通配符、成员嵌套构造器、注解/枚举/常量、TYPE_USE、可选写失败、未改源码的依赖常量漂移；full/delta 完整事实等价。声明目录与基线校验，不完整回退源码 |
| 目标 JVM | 3 个测试：8 请求复用 1 JVM、1/2/4/8 调度等价、诊断无跨上下文泄露；产品显式并行值有内存预约校验 |
| 注解 | 8 个测试：源码/二进制元注解、AliasFor、编译器常量名、冲突、缺失目标成员、循环/占位符；javac Attribute.Error 不冒充名称，合法 `"<error>"` 常量仍保留 |
| 扩展 SPI | 五类接口、registry 与 report/execution；12 个测试覆盖顺序/指纹、无效注册和 producer、禁用依赖、只读模型、归属/证据/schema/图校验、阶段事务、取消/strict、版本/配置失效及关闭清理 |
| 框架规则 | 10 个测试：MVC 类/方法路径和 HTTP 元数据/参数/组合注解；生命周期/事件、工厂最近成员与未解析情况；SOFA 注解及未知 API/占位符/多接口；框架关闭、NoCode 方法体变长后的注册位置与未变 Bean/lifecycle 依赖均验证 delta=full |
| Rust | 34 个单测、格式及 Clippy 通过；捕获 blob pin/回收、流式 JAR、classpath 次序/非法索引、并行内存预约与未知 facet 拒绝 |
| Native / IPC | 真正 GraalVM 共享库构建、Native probe、Rust worker IPC 通过。IPC 用 test-owned socketpair，验证 full/delta、校验和、Fact IR 校验、Acked 与热 host；新增 MVC/handler/SOFA facet 实际经过产品校验 |

上述 fixture/native gate 的第一份统一回执为 `target/java-alignment/evidence/20260930-103133-59889/receipt.json`（10 项通过）。随后补充 native IPC 对 Lombok overlay/possible getter/call evidence/XML resource 的明确断言，并收紧 producer/声明校验；最终轮次的回执另记于末尾。`tests/java_alignment.py` 现在保存输入文件摘要，执行期间代码漂移使 gate 失败，并记录 bridge/native/host 的产物摘要。

### 冻结 iopengw 的输入验收

只读取原仓库，在本项目 `target/java-alignment/iopengw` 由冻结提交的 Git archive 验收。HEAD 为 `267c7ced4993aa88613f8f17a8099943fec98159`，未提交修改不纳入。Maven extension JAR 单独固定，避免后续本项目构建使外部控制摘要漂移。

| 证据 | 实际结果 |
|---|---|
| 已提交文件 | 5,039 个文件逐项重算 Git blob hash，全部一致；已提交 Java 4,217 个 |
| MAIN + TEST 有效根 | 40 个模块、4,214 个 Java 输入；3 个已提交 Java 不在 Maven 有效根内 |
| 依赖 | 933 个唯一 JAR、680,141,315 字节；捕获总量 727,314,551 字节 |
| 捕获验证 | 5,308 个 input records、5,303 个唯一 blobs 全部重算 hash；73.281 秒，子进程峰值 RSS 131,203,072 字节 |
| 回执 | `target/java-alignment/iopengw-input-receipt.json`；显式 `semantic_index_accepted=false` |

**这是 Maven 输入和生产捕获的验收，尚未通过完整语义索引。** 无目标编译、注解处理器或业务字节码生成。

### 尚未通过的完成门槛

| 剩余 | 当前边界 |
|---|---|
| Java 能力 | 完整类型运算/恢复、全部回调与类型位置、覆盖/派发、受限反射及逐项独立差分仍待补齐 |
| 扩展与框架 | 当前扩展视图按 compiler context；完整项目视图、单声明失败隔离、更完整 DI/XML 与 Lombok 构造器/Builder/配置等未完成 |
| 缓存与容量 | 模块 NoCode 归档、LRU/pin、全部复杂 TYPE_USE；结果仍有 64 MiB/100,000 事实上限，模型/结果/存储完整流式化及 100/1,000/3,000 容量测试未完成 |
| 真实项目 | iopengw 的 MAIN/TEST 语义全量、无变化/编辑/删除、Anatomist 差分与发布查询未验收 |
| 当前环境 | daemon 回归在启动监听阶段返回 Operation not permitted；这轮 daemon/release 未记通过。socketpair IPC 不替代 daemon 发布/恢复测试 |

能力矩阵是 `tests/java_alignment_matrix.json`。完整 gate 只有所有逐项门槛和 daemon/真实项目都通过才允许声明对齐完成；native 级 gate 通过仍返回 `alignment_complete=false`。

补充回归发现并修复：NoCode 缩减以前保留整个框架 producer，方法体变长会留下旧 MVC span。现在在含未变声明/Bean 的完整暂存视图上重跑变更源码的扩展，更新其 owner；未变源码只用于语义查找，不获得发布权限。两个新增回归覆盖位置移动、未变 controller、Bean 注入与工厂生命周期依赖。

### 最终执行回执

| 本轮最终检查 | 结果 |
|---|---|
| 统一 native gate | `target/java-alignment/evidence/20260930-104909-29831/receipt.json`：10/10 通过 |
| 源码冻结 | 150 个输入文件保存 SHA-256；所有 gate 的 changed_inputs 为空；bridge/native/host 产物也保存摘要 |
| Java fixture | 7 组：19 + 4 + 6 + 3 + 8 + 12 + 10，共 62 项通过 |
| Rust / Maven | 34 个 Rust 单测、fmt/Clippy/build；Maven 1 + 6 项通过 |
| 真正 native | NoCode/MVC span 的 native full/delta 等价、取消/清理、处理器陷阱、bundle 完整性、实际 SIGSEGV 隔离通过 |
| 产品 worker IPC | 五类扩展点实际执行；Lombok overlay/possible getter/call evidence、XML inventory/分析、MVC/handler/SOFA facet 经 Rust 契约校验；full/delta/ACK/热 host 通过 |
| 对齐完成状态 | E01/E02 通过；其余 50 项为 partial/pending。alignment_complete=false；完整真实语义/差分 runner 和当前 daemon/release 验收仍未完成 |

过程中 `20260930-103858-90492` 回执的 Clippy 失败源于验收例子的 get/is_some 写法；修正后重跑通过。`20260930-104247-6034` 的 native gate 通过后，补充检查发现 NoCode 框架位置问题；不能用那份回执替代上述最终回归。

最终代码另行重跑 `python3 tests/rust_e2e.py`：14 项均在 setUp 的 daemon 启动阶段报 `Operation not permitted (os error 1)`，耗时 1.119 秒；日志/摘要记在最终回执的 supplemental_checks。没有到达语义断言，未记通过，未将其归因于本机安全产品。

## S0/S1 与模型、项目视图、反射的固定输入回归（2026-09-30）

| 回执 | 实际结果 |
|---|---|
| target/java-alignment/evidence/20260930-124124-57004/receipt.json | S0/S1 首轮 14 项 gate 全部通过；43 Rust 单测、Java daemon 9 项、native/IPC 与真实 SIGSEGV；50 能力及容量/差分/release 仍阻止整体完成 |
| target/java-alignment/evidence/20260930-131710-64436/receipt.json | 当前切片 14 项 gate 全部通过，全部 changed_inputs=[]；44 Rust 单测；Java fixture 19+6+8+6+3+8+13+12=75，另有 archive/Unicode/binary 解码 probe；Java daemon 10 项含反射 dispatch/full-delta；真实 native/IPC/崩溃链路通过 |
| target/java-alignment/prototypes/capacity/{100,1000,3000}/receipt.json | 简单生成源码的 native+owned javac 预检通过：1802/18002/54002 个事实、零诊断，峰值 342622208/583761920/803536896 字节。100 ms 调度采样；不含 daemon，未证明复杂 reactor/JAR/XML、完整增量/取消和 2 GiB 总容量 |

第二轮回归后新增二进制接口不继承 Object 方法、参数分支合并和缺父类 declared lookup 反例，正在复测；不能将上面的固定输入回执当作这些后续改动的验证。

当前仍为 alignment_complete=false。已打通全 context 暂存项目视图，新增 Spring 注入及 Primary/Qualifier、有界反射、binary 泛型树与成员截断披露；context 模型内存视图、一般 SOURCE/建模恢复、完整 Lombok/DI/XML、Maven 标签/配置/selector、模块缓存、规模完整验收和 iopengw 差分仍未完成。

## 声明、配置及容量故障修复（2026-09-30）

| 已执行 | 证据/结果 |
|---|---|
| 独立模型/框架/扩展回归 | Java fixture 与 Rust workspace 单测通过；增加单声明事务、source 构造器拒绝删除、config 级联/漂移/strict 与生成注解正反例。新增字段隔离/泛型构造器反例另行复测；本节不替代冻结输入完整 gate |
| 真正小堆 OOME | `tests/java_native_heap.py`：真实模型分析在 24 MiB JVM 堆耗尽后返回 NATIVE_HEAP_EXHAUSTED，task_close/javac/临时目录回收通过；不是产品 fault switch |
| daemon 容量历史回执 | `target/java-alignment/evidence/capacity-baseline-20260930-135024/{100,1000}.json`：各 8 阶段通过，full/无变化/方法体热冷 delta/声明 delta 及 full 等价；3000 文件只通过 full/无变化 |
| 3000 全量 | 54,002 事实，123.145 秒；daemon+worker+javac 峰值 873,398,272 字节，daemon 峰值 78,725,120 字节；100 ms 调度采样最大实际间隔 125.992 ms |
| 性能根因与修复 | 每个未改源的 catalogue 比较都解码整份基线。改为一次索引声明位置后，1000 文件热增量由旧回执 296.633 秒降到 29.368 秒；未增加默认堆/结果上限 |
| 3000 热增量故障 | 分析线程已退出、任务仍 running；终止这一个 test-owned worker 后，从 supervisor 取得原始 stderr：codeana-analysis 的 OutOfMemoryError / Garbage-collected heap size exceeded。候选未发布，旧 revision 保留；失败写入该轮 3000.json，不记容量通过 |
| 后续修复 | 原生 Error 采用预留响应并检查死线程终态；增量保留的基线事实及扩展前缀改为惰性位置视图，不累积整图 Map。真实 3000 完整增量正在重验收 |

该历史容量轮次没有完整 reactor/JAR/XML、取消/cleanup 或 iopengw 差分证据。完整容量 gate 与 52 项对齐均仍未完成；后续 native/daemon 与冻结输入 gate 回执需单独记录。

## 声明、配置及 OOME 修复的生产回归（2026-09-30）

| 回执 | 实际结果 |
|---|---|
| `target/java-alignment/evidence/20260930-142240-41325/receipt.json` | 14 个 gate 全部通过，changed_inputs 全为空；47 Rust 单测、91 Java fixture（含真实小堆终态）、archive/Unicode/binary probe；11 Java daemon 用例、IPC、真正 native 和 daemon SIGSEGV 通过 |
| `target/java-alignment/evidence/capacity-baseline-20260930-143434/receipt.json` | 3000 文件、54,002 事实八阶段全部通过；artifact 摘要起止一致，无诊断/partial/业务 class；full/无变化/方法体热冷 delta/声明 delta 及 full 等价 |
| 3000 全量 / 无变化 | 116.334 / 42.620 秒；各 phase 最大进程树 RSS 933,986,304 字节，低于 2 GiB；daemon、worker、owned javac 一起采样 |
| 3000 方法体热 / 冷 delta | 60.925 / 64.459 秒；对应峰值 478,789,632 / 472,236,032 字节；修复前 native OOME 未重现 |
| 3000 声明 delta / full | 87.028 / 83.462 秒；完整事实多重集、coverage、provenance 等价 |

回执固定的是当轮产物，不覆盖随后新增的缓存驱逐、私有 pin、调用失败分类及类型变量 owner 导出。100/1000 的先前八阶段回执仍单独保留。基础源码容量通过不能代替宽/深 reactor、JAR/XML、取消/cleanup、大预算、模块缓存和 iopengw 完整语义差分；final_capacity_accepted 与 alignment_complete 均仍为 false。

## XML、缓存与调用模型的后续生产验收（2026-09-30）

| 回执 | 实际结果 |
|---|---|
| `target/java-alignment/evidence/20260930-151613-14445/receipt.json` | 14 gate 全部通过；47 Rust 单测、108 Java fixture/probes、12 Java daemon 用例，包含新 XML 树/span/成员/资源修改删除、缓存与调用失败证据；native/IPC/daemon SIGSEGV 通过 |
| `target/java-alignment/evidence/capacity-baseline-20260930-153111/receipt.json` | 100/1000 文件八阶段通过；3000 文件前七阶段通过，最后 declaration-full-equivalence 返回 NATIVE_HEAP_EXHAUSTED；旧 head 保留，不能记完整容量通过 |
| 3000 实际峰值 | 已完成阶段最高进程树 RSS 975,192,064 字节；RSS 门槛没有触发，失败来自原生 256 MiB 堆；没有提高默认堆/结果上限 |
| 后续堆峰值修复 | FactExtractor 逐行封存到 task-owned archive；只保留 ID/记录位置，调用关联只留 ID，输出按 ID 惰性读取；避免整个 context 模型与全部事实 Map 同时常驻。native 容量需重测 |
| 新调用恢复 | 11 项 JVM 绑定 fixture：owner/递归边界、泛型与 enclosing/继承投影、捕获写入、boxing/widening、重载/varargs、SOURCE/binary 链式输入、SAM 方法引用及缺证据拒绝；Rust 查询 4 项通过。新增生产用例另行完整 gate |
| UtilityClass | 对齐参考的有界规则：仅顶层且成员已经显式 static、已知生成策略；private modeled 构造器与 effective final；拒绝实例/嵌套/显式 ctor/组合生成器/未知策略。Builder/SuperBuilder 仍缺 |

矩阵当前 2 passed / 45 partial / 5 pending；模块缓存、大预算/完整容量、Maven 标签、完整框架/推断及 iopengw 语义差分尚未完成。

## 链式恢复、事实封存与二进制查询（2026-09-30）

| 实际证据 | 结果 |
|---|---|
| `target/java-alignment/evidence/capacity-baseline-20260930-180030/receipt.json` | 逐行 FactExtractor 产物的 3000 文件八阶段全部通过；54,002 事实，产物摘要起止一致；全量/无变化/方法体热冷 delta/声明 delta 及 full 等价 |
| 3000 进程树 RSS | 最高 892,633,088 字节；daemon、worker、owned javac 一起按 100 ms 调度采样；没有提高默认堆/结果上限 |
| 时间口径 | full 113.461 秒、方法体热/冷 delta 80.673/146.305 秒、声明 delta/full 180.346/163.902 秒；该轮与完整 gate 并行运行，只作为正确性/自身进程树容量证据，不作为独占性能基准 |
| `target/java-alignment/evidence/20260930-175543-40559/receipt.json` | 13/14 gate 通过；新 daemon 恢复测试使用签名作为 CLI selector 而失败。修正 selector 后实际进入 delta，发现源码变化但相同扩展 owner 未替换，候选被 CHANGED_SOURCE_OWNER_NOT_REPLACED 拒绝、旧 head 保留 |
| owner 替换修复 | 根据基线 coverage 和新封存 source hash，强制替换所有涉及变更源的旧 owner，包括内容相同的扩展结果；保留 storage 发布校验。JVM 绑定回归已验证替换集合与 full 等价；完整生产重测进行中 |
| 声明身份 | callable 参数统一使用 binary name，数组递归、泛型参数擦除；嵌套 SOURCE/JAR/NoCode 等价及反射参数测试通过；文件签名缓存格式升至 v6-model5，拒绝复用旧身份目录 |
| BinaryIndex | 有序包目录、最多 8 个打开 JAR/64 个解析类缓存；目录超过 262144 链接或 8 Mi 字符时精确顺序查询；256 JAR 在 96 FD 上限下验证驱逐、优先级、平台类、缺类、取消、关闭、失败重试与目录超限降级 |

本节专项为 7 项语义、11 项绑定、11 项反射与低 FD JAR probe。后续产物正在完整 gate；上述容量回执不覆盖后续 owner/身份/JAR 改动，也不代表完整 reactor/JAR/XML 容量、模块缓存或 iopengw 差分通过。

`target/java-alignment/evidence/capacity-baseline-20260930-183644/receipt.json`：后续 owner/嵌套身份/v6 缓存/BinaryIndex 改动产物的 100/1000 文件各八阶段已通过，起止产物无漂移；完整容量仍未接受。完整 gate 中新增的嵌套身份/NoCode 和恢复调用/dispatch/声明 delta daemon 用例已经通过，整体 gate 尚未结束。

| 后续完成回执 | 实际结果 |
|---|---|
| `target/java-alignment/evidence/20260930-182942-26323/receipt.json` | 14/14 gate 通过，changed_inputs 全为空；47 Rust 单测、120 Java 单测与辅助 probe、14 Java daemon 用例及真正 SIGSEGV 隔离。整体 alignment_complete 仍为 false |
| `target/java-alignment/evidence/capacity-baseline-20260930-183644/receipt.json` | 100/1000 文件各八阶段通过，最高进程树 RSS 403062784/642580480 字节，无产物漂移 |
| `target/java-alignment/evidence/capacity-baseline-20260930-184913/receipt.json` | 同一 owner/身份/JAR 产物的 3000 文件八阶段全部通过，无产物漂移；仍非完整 reactor/JAR/XML 容量 gate |

该固定输入之后继续补齐显式泛型调用：javac 桥记录实参类型模型，恢复核对数量/完整性、种子替换与递归边界；涵盖 Number/Integer boxing、null、引用类型数组、非泛型方法忽略合法类型实参及真实 JAR 泛型 shadowing。未归属的显式方法引用实参仍为 UNKNOWN/unresolved，不按文本猜类型。新产物生产 gate 正在重新执行，以上旧回执不覆盖这项追加改动。

| 新完成门禁 | 实际结果 |
|---|---|
| `target/java-alignment/evidence/20260930-185933-31344/receipt.json` | 显式泛型实参产物 14/14 gate 通过；121 Java 单测、47 Rust 单测、14 Java daemon 用例、native/IPC 与真正 SIGSEGV 隔离；changed_inputs 全为空。整体 alignment_complete=false |

该回执之后追加 M08 标签映射与 F05 纯 JAR XML 查询。M08 的最长根/歧义、metadata recapture/default 标签与 49 项 Rust 单测、clippy、JVM full/delta、真实 daemon 跨标签绑定/TEST→MAIN/外部 JAR/改名/歧义旧 head 保留已通过。F05 的 SOURCE/JAR factory/constructor/继承成员及 edit/delete full/delta、外部元数据证据约束 fixture 正在回归；新原生/生产统一门禁正在执行。旧回执不覆盖这两项追加。

F05 补充反例：2050 个成员触发二进制截断，2000 成员/80 factory 配置触发独立查询步骤上限，均保留配置事实并 partial；SOURCE 同名类优先，不穿透到 JAR 父类。full/delta 发现 XML/调用恢复的 Map.of 修饰符顺序跨 JVM 不稳定，已排序；新统一门禁将覆盖这项修复。

| 完成回执 | 实际结果 |
|---|---|
| `target/java-alignment/evidence/20260930-194120-86058/receipt.json` | M08/F05 追加产物 14/14 gate 通过；125 Java 单测、49 Rust、16 daemon、native/IPC/SIGSEGV；无源输入漂移，整体对齐仍未完成 |
| `target/java-alignment/evidence/iopengw-semantic-preflight-20260930-194733/receipt.json` | 冻结 HEAD 全项目生产语义预检失败：FRAME_BUDGET；尚未进入 javac，旧 head 保留；峰值进程树/daemon 401342464/391348224 字节，源/产物无漂移；不是语义验收 |
| 同目录 context-cardinality.json | 66 个非空 MAIN/TEST contexts，95253 个源码引用、41907 个 classpath 引用；单帧重复清单超过 8 MiB |

根据真实预检补 worker 8 共享来源/JAR 清单、context 有序索引和独立发布标志；native request 3 使用惰性来源视图。保持原单帧和语义上限。Rust 66×3200 源/933 JAR 引用的压缩帧、顺序/身份/发布/越界/packed 预分配检查通过；JVM 共享输入与展开输入的 SOURCE/MAIN/TEST/资源/JAR/full-delta 等价及非法角色拒绝通过。新原生/daemon 与 iopengw 重测尚未完成。该追加不受上述旧回执覆盖。

| 共享输入后续回执 | 实际结果 |
|---|---|
| target/java-alignment/evidence/20260930-201405-22297/receipt.json | Java fixture、Rust、native probe、Rust daemon、真正 SIGSEGV 等通过；IPC 因原有 XML 资源 language=xml 被空语言校验错误拒绝而失败。已停止该轮 Java daemon 长回归以先修复，退出 1；不得引用为完整通过 |
| target/java-alignment/evidence/iopengw-semantic-preflight-20260930-201944/receipt.json | 冻结全项目已越过 FRAME_BUDGET、启动 Java 8 javac，失败于 MODEL_RECORD_BUDGET_OR_CANCELLED；88.634 秒，进程树/daemon 峰值 758497280/243187712 字节，1 worker/1 javac，无产物/源漂移，未发布 |

追加修复资源语言标签：共享资源允许非 Java 标签（含空值/XML），拒绝把 Java 源作为资源；原标签不改写。补显式 java_model_record_limit，默认仍为 100000，范围 1–1000000，具体 context/阶段/源码/计数诊断与配置/IPC 边界检查。语法按归档行惰性读取并保持不可变、生命周期关闭；FactExtractor/Reflection 按文件消费，减少整树副本。无项目扩展时跳过项目模型和事实重复暂存；启用资源扩展时逐 context 打开/关闭语法归档。声明/观察仍整体可变，未宣称整个模型已流式化。新增归档、JVM 模型预算、11 项反射和 Rust 配置边界专项通过；统一原生/产品和真实项目重验待新回执。

| 后续实际证据 | 结果 |
|---|---|
| target/java-alignment/evidence/20260930-203540-51487/receipt.json | 13/14 gate 通过，54 Rust、16 Java daemon、native/IPC/SIGSEGV 无漂移；java-fixtures 在 24 MiB OOME 旧预期处失败：优化后 1000 文件已能完成。不是完整通过 |
| target/java-alignment/evidence/java-fixtures-no-build-20260930-204352/receipt.json | 同一产物其余 127 项 Java 单测全部通过，源和产物无漂移；12 MiB 下的真实 OOME/终态/子进程清理专项通过，随后更新 fixture 的堆限额 |
| target/java-alignment/evidence/iopengw-semantic-preflight-20260930-203816/receipt.json | 显式 1000000 模型工作记录的冻结全项目预检越过记录上限，失败于编译器桥 NPE；114.189 秒，进程树/daemon 1050427392/262062080 字节，未发布/无漂移 |
| target/java-alignment/evidence/iopengw-semantic-preflight-20260930-204201、204644 | 只增加上下文/堆栈的隔离诊断包；定位 app/core/service@MAIN 读取上游 LogFieldEnum 的初始化块 lambda 参数时，javac typeless MethodSymbol.getParameters NPE；不作为生产验收 |

最小反例进一步证明合法静态/实例初始化块的普通局部变量也会触发相同 NPE，不是只能在错误代码中出现。修复使用 SOURCE TreePath 对应的初始化块/lambda/字段初始化归属，避免枚举 typeless 方法参数；未知身份不伪造根 key。加入 A/B 同偏移不同类、静态/实例/字段初始化、enum Collectors.toMap、引用身份和 full/delta 等价 fixture，JVM 已通过，native 与 daemon 用例追加待统一门禁。编译失败回执保留有界 context/phase/source 与 12 个堆栈帧。以上新追加不能引用旧产物回执证明。


## 初始化块统一回归与真实类型/容量根因

| 证据 | 结果 |
|---|---|
| `target/java-alignment/evidence/20260930-205618-6840/receipt.json` | 14/14 生产 gate，通过 129 Java fixture、54 Rust、17 daemon、native/IPC/真实 SIGSEGV，无输入漂移；整体对齐仍未完成 |
| `iopengw-semantic-preflight-20260930-210053` | 初始化块修复后生产预检在 Comparator.comparing lambda 的 TYPEVAR null 下界处 NPE；91.819 秒，进程树/daemon 1151238144/273055744 字节；未发布，无漂移 |
| 隔离 `210817` | 下界修复越过 NPE，512 MiB Java 8 堆 OOME；178.638 秒，进程树 1140277248 字节；不是生产验收 |
| 隔离 `211523 / 211920` | 逐行归档及先共享类型后落盘，失败分别推进到不同源码的明确 64 MiB RECORD_ARCHIVE_BUDGET；无 OOME，不代表大预算/容量通过 |

新增 TYPEVAR 下界缺失反例保留诊断和健康声明。测试进一步发现 javac capture# 编号随进程复用漂移，现按源码内稳定顺序映射，保留不同捕获身份/实际上界，下界缺失仍 incomplete；合法 wildcard、缺类型/推断失败、源码顺序、源码字面量与 full/delta 专项通过。编译器侧声明/语法/观察按行类型共享归档，lambda 最新行补充后持久化、只存注解查找索引，原始 javac 图在最终归档组装前释放。归档等价/类型缓存驱逐/最新行补充/关闭清理专项通过；native/daemon 新门禁待回执。

F05 依据固定参考 XmlBeanGraphExtractor.typeHint / ProjectFactView.resolve：补显式参数类型、引用 Bean 产品类型、constructor-arg 索引、setter/constructor/factory 重载筛选，以及多个工厂重载返回相同类型的产品类型保留。未知/静态约束不符/歧义保守披露；不将可能成员或产品类型作为运行时确认。明确不同全限定类型同简单名不互相选择。SOURCE/JAR full/delta 专项通过，Spring 扩展版本升至 4；统一原生/产品验收待新回执。


## 213115 / 213611 基线与当前 S1/E05 追加

| 回执 | 真实结果 |
|---|---|
| `target/java-alignment/evidence/20260930-213115-40956/receipt.json` | 14/14 生产 gate，132 Java、54 Rust、18 daemon；稳定捕获、初始化块、编译器逐行归档、XML 类型提示；无源/产物漂移。50 项及最终门禁未完成，runner 按约定退出 2 |
| `target/java-alignment/evidence/capacity-baseline-20260930-213611/receipt.json` | 100/1000/3000 各八阶段全部通过；3000 54002 facts、峰值完整进程树 RSS 700989440 bytes；并行执行只证明正确性与该进程树 RSS，非独占性能比较 |
| `target/java-alignment/evidence/iopengw-semantic-preflight-20260930-213607/receipt.json` | 103.022 秒、完整树峰值 1081982976 bytes；64 MiB `RECORD_ARCHIVE_BUDGET`，context `app/core/service@MAIN` declarations；无发布/漂移，语义索引/差分均 false |

追加资源 selector 在项目事务内执行、type 递归不可变共享、context core 立即清理、显式模型/堆/超时/事实/结果/scratch 全链路，协议 worker 9 / analysis 4 / compiler 6 / model 5 / ABI 2。当前专项 JVM 模型/扩展、实际 Graal isolate 的 512 MiB 堆读数及 59 Rust 检查已通过；生产新产物完整 gate 尚待执行，不能沿用上述基线回执证明新增实现。

新增预算专项已取得证据：`target/java-alignment/large-budget-proof.log` 实际生产链路发布 116096 facts，事实正文合计 141971801 字节（超过旧 100000 / 64MiB），完整进程树峰值 656850944 bytes、daemon 124354560，1 worker / 1 javac；未生成业务字节码。该专项当时仅含默认拒绝与大预算 full，新增大基线 unchanged/body delta/full 待新门禁执行，不代表 5M 全容量通过。`scratch-budget-proof.log` 超限杀拥有的进程组、旧 head 保留和任务目录清理通过。

`large-class-path-cache-proof/receipt.json`：单文件 10000 方法编译器分析 6.094 秒、41479826 字节模型，无业务字节码；原生产用例同规模触发默认 context 120 秒超时。缓存只取声明 TreePath 并保留变量 owner/span；`large-class-regression.log` 10000 locals / 20000 references 专项 6.850 秒通过。独占性能/多尺度容量与所有生产门禁仍待执行。`20260930-222230-47492` 在发现核心提取器固定事实限额时由本次审计主动中断（已通过的9个 gate 不代表完整验收），随后补齐预算遗漏和大规模正反例。

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


## SuperBuilder、隐藏 MAIN 与真实项目编码证据（2026-10-01）

| 变更 / 回执 | 实测结果和边界 |
|---|---|
| SuperBuilder 与捕获边界 | 8 新 JVM 用例：泛型继承/抽象父类/Default/静态嵌套/新形式参数名/父源码顺序/拒绝条件/错误实参/删除失效；恢复保留 possible，不生成处理器或业务字节码 |
| 独立参考差分 | `superbuilder-proof/production-compare.log`：真正冻结 Anatomist 的 4 个 fixture，与生产 JVM 输出 63 个生成声明形状相等；规范化嵌套名称、字段擦除与方法书写参数，不证明完整泛型协议或真实项目差分 |
| 惰性扩展 | Framework/注解/DI/XML/SOFA 位置索引；`superbuilder-production-fixtures.log` 的 164 项与后续 `auxiliary-production-fixtures.log` 的 167 项均通过；新 header 优化另行专项重验 |
| 大归档 | 60000 声明、217875564 字节归档在 128 MiB 堆内完成 Java/project；20000 声明、62946698 字节与 1000 Lombok callback 在 128 MiB 堆内完成、保持只读。仍不是完整 iopengw 容量 |
| TEST-only | SourceUnit auxiliary、MAIN prerequisite、Rust/JVM 发布拒绝、MAIN/TEST CP/源码、NoCode/完整 SOURCE 等价、损坏恢复、上游变化 delta/full 已实现；Rust与 Maven 7 专项及 Java 167 项通过。native/daemon 与统一冻结门禁待新回执 |
| 上轮真实 OOM | `iopengw-semantic-preflight-20261001-021225`：551.755 秒，app/migration/core/service@MAIN / java_extensions；进程树峰值 1418903552 bytes，无漂移、未发布。随后改为惰性框架索引 |
| 最新真实超时 | `iopengw-semantic-preflight-20261001-090306`：1830.242 秒、CLI TimeoutExpired，未取得终态、未发布；产物/源码摘要未漂移。这份回执未采到最终 RSS，不能据此判定内存通过 |
| 隔离取样 | `framework-real-stages`：冻结 app/core/service@MAIN 的 730908827 字节模型，512 MiB JVM；241518 core、243135 Java-stage facts 无截断。JFR/线程栈证明 SpringMvc 在 ExtensionContext.freeze / IndexedRecords / published 中反复复制完整类型树；随后按 header 预选发布声明，完整性能门禁仍缺 |
| 参考 MAIN | `anatomist-iopengw-main-preflight-20261001-092854`：冻结 jar + 原提交 MAIN 扫描 89.913 秒、退出 0；仅 MAIN，不是 MAIN+TEST 或语义差分通过 |
| 参考全 scope | `anatomist-iopengw-preflight-20261001-092217`：30.727 秒、退出 2，报源码变化。实际仅 1 个 TEST 文件为 GBK；原字节与冻结 git blob 一致，SHA ff23ba56bde3d109a4bc29abd0ca1590920bd92a06c8ae54357d030e18853761；POM 声明 UTF-8，参考对 javac 替换解码文本计算哈希后与原字节比较 |
| 编码诊断 | CompilerBridge/FactExtractor 返回带 context/path/hash 的 SOURCE_ENCODING_INVALID；GBK 负例验证原字节未改与子进程/归档清理。不改写冻结源码或参考 jar 来制造全 scope 通过 |

此前 SIGKILL 崩溃隔离用例仍未解决，保持原 SIGSEGV 必须到达 C ABI 的断言；若重跑出现进程被杀，立即向用户报告 PID/命令/信号。只完成 E01/E02 的能力状态继续保留，其余 50 项及真实语义/容量/release 最终门禁仍需完成。

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


### 本轮共享绑定证据 / 方法引用 / Rust Maven（2026-10-01）

| 证据 | 已验证 / 范围 |
|---|---|
| `capacity-callback-full-fixtures.log` | 208 个 JVM fixture 通过；最后的回调 qualifier 归属与求值阶段修正发生在其后，不记为最终代码一次完整通过 |
| `capacity-callback-final-jvm-tests-fixed.log` | 最终 SDKMAN Java 8 编译器桥的 35 个语义/绑定/签名 fixture 通过，含新增嵌套 lambda 时机反例 |
| `capacity-callback-rust-workspace-final.log` / `capacity-callback-clippy-final.log` | Rust workspace 与 clippy 全通过；完整/流式发布校验绑定证据的 parent、kind、owner、source，错误链接不更新 HEAD |
| `capacity-callback-native-build.log` | 新 native 完整构建通过。其后仅修改独立 Java 8 bridge 的 qualifier 求值阶段，重新构建 bridge 并按实际 native/bridge 校验和生成 bundle manifest |
| `capacity-callback-final-native-probe.log` | 真实 C ABI/native → SDKMAN Java 8；共享类型图、METHOD_REF/数组 intrinsic/嵌套 lambda、增量与全量、取消/处理器禁用/故障隔离/bundle 校验全部通过 |
| `capacity-callback-final-native-ipc-fixed.log` | protocol10、共享输入、全量/增量、Fact IR 校验、ACK/hot host 通过。旧 debug worker 和生成新 bridge 尚未刷新 manifest 的两份失败日志保留，不属于异常进程被杀 |
| `capacity-callback-final-release-daemon-core.log` | 最终 release native/bridge 的真实 daemon 核心全量/增量/worker 复用通过；不是完整 daemon matrix |
| `callback-reference-proof/comparison.json` | 最新 dff2e395 的独立 fixture：5 METHOD_REF、4 调用；范围、模式、navigation、intrinsic array 和调用 owner 无差异，不代表完整模型协议相等 |
| `capacity-facade-call-diff.json` / `facade-current-model-proof/receipt.json` | 冻结 facade JVM：2177 核心声明、398 调用的位置/目标与最新参考相同；完整类型/框架/native/整项目差分未据此通过 |
| `capacity-current-maven-bundle-receipt.json` | Rust 导出 40 模块；MAIN 源/资源根、有序 CP/内容 SHA、reactor 和输出根与冻结 manifest 一致。runtime staged 的 native/bridge 校验和已验证 |
| `iopengw-main-release-preflight-20261001-181019/receipt.json` | 1774.129 秒失败，4 GiB 归档预算拒绝（1819751 条事实）；总 RSS 峰值 1659961344 bytes，1 worker/1 javac，无源码/产物漂移，任务正常清理 |

`capacity-shared-evidence-main-release.log` 的 MAIN 重验已结束，见下表。此前失败不能记为通过；完整语义差分、3 冷/3 热容量与最终 release 均继续待验收。

### 编码归档复制与 owner 差分取证（2026-10-01）

| 证据 | 结果 / 边界 |
|---|---|
| `iopengw-main-release-preflight-20261001-191838/receipt.json` | 1811.665 秒 TIMEOUT；末次取样 29 个上下文、3.25 GB；未发布。RSS 峰值 1666646016 bytes，1 worker/1 javac，无源码/产物漂移；自身截止清理，无异常杀进程 |
| `raw-transfer-archive-tests.log` | CAF1 编码直接复制：约 198 MB / 6000 事实、32 MiB 堆；SHA、顺序/重复、视图、追加、预算、取消、关闭、fallback header 与项目阶段视图检查通过 |
| `raw-transfer-framework-semantic-tests.log` | 61 项通过；空 XML 时仍运行 Spring DI，custom/legacy 项目扩展默认保留完整模型；内置框架暂存只去除不读取的 observations/syntax/diagnostics |
| `raw-transfer-final-maven-bundle-receipt.json` | Rust release 导出 40 模块，MAIN 根、CP 顺序/内容 SHA、依赖、物理路径与输出根均与冻结输入相同；native/bridge staging SHA 已核对，不代表语义或容量通过 |
| `raw-transfer-diff-reader-current-jvm-facade-normalized.json` | 当前 facade JVM：2177 核心声明、398 调用位置/目标相等。按 callback 的 kind、范围和递归父链统一身份表示后，仍有 14 处字段初始化 owner 差异；没有据计数隐藏差异 |
| `field-owner-proof/facade-call-diff.json` | 隔离 Java 8 bridge 原型改用 FIELD/ENUM_CONSTANT 作初始化 owner 后，声明、调用位置、目标和 owner 四项均相等；原型尚未合入生产，不是 native/完整语义/容量通过 |
| `field-owner-proof/semantic-fixtures.log` / `field-owner-fixtures.log` | 原型 17 项语义回归及新增字段/枚举/callback/真实初始化块的缓存、delta/full 检查通过 |

冻结完整回归 `20261001-201312-42970` 已完成：14/14 gate、210 JVM fixture、32 Java daemon 均通过，真实 SIGSEGV 保旧 HEAD、daemon 存活与回收通过，无输入漂移。退出 2 表示其余 50 项及 capacity/real-semantic-diff/release 未完成，不是本轮回归失败。

字段 owner 修正发生在该冻结门禁之后，已合入 CompilerBridge；对应 JVM/native/daemon 回归已加入正式测试。正式 SDKMAN Java 8 构建的 CompilerBridge 全部 class 字节与上述已验证原型相等；最终新 bridge 的专项和 iopengw 仍需独立回执，不能把先前的 14 gate 合称为修改后一次完整验收。

### 字段归属、注解查询与元数据读取续验（2026-10-01）

| 证据 | 结果 / 边界 |
|---|---|
| `field-owner-production-semantic.log` / `field-owner-production-release-daemon.log` | 字段 owner 生产实现的 18 个 JVM 语义 fixture 和真实 release daemon 的调用/源码/全量/增量专项通过 |
| `field-owner-final-production-native-probe.log` / `field-owner-final-package-daemon.log` | 最终字段 owner native probe 与包内两项 daemon 测试通过；不含随后新增的注解路径和选择性 JSON 读取 |
| `field-owner-final-package-maven-bundle-receipt.json` | 包内 Rust 导出入口在无 Python PATH 下导出 40 模块；冻结 MAIN 根、CP 顺序/内容 SHA、依赖与输出根一致 |
| `iopengw-main-release-preflight-20261001-210607/receipt.json` | 1811.913 秒 TIMEOUT、未发布；总 RSS 峰值 1694138368 bytes，1 worker/1 javac，无源码/产物漂移。末次取样 30 个暂存模型文件、3283506752 事实 bytes；文件数不代表所有上下文已完成。自身截止清理，无异常杀进程 |
| `annotation-path-current-fixtures-fixed.log` / `annotation-path-counter-budget-probe.log` | 注解路径缓存对齐冻结参考：9 个 fixture；70k 重复负查询、selector 独立/不可变、实例属性、4096 缓存淘汰、来源单元预算隔离通过；预算耗尽搜索不写入缓存 |
| `annotation-path-facade-proof/coverage-receipt.json` | 真实冻结 facade JVM 去除 142 条 SOFA/Spring DI 的误报 ANNOTATION_QUERY_BUDGET；2177 核心声明保持参考相等。保留 6 条真实 javac 错误和 1 项 Lombok 缺口，不代表完整 native/类型/框架语义差分 |
| `metadata-projection-archive-gate.log` | Java 8 严格 JSON 选择性读取：1500 事实与 Python 元数据 oracle 一致；跳过字段的重复键/UTF-8/转义/结构/深度/节点预算仍拒绝。6000 事实、197574784 bytes 在 32 MiB 堆下 SHA/顺序/取消/预算/清理均通过 |
| `field-owner-main-model-diagnostic/facts-projection.log` | 留存真实 app/core/service 项目模型的 66772 条 / 160181258 bytes 提取：3.432 秒，基线 3.911 秒；归档 SHA 均 bca82c4208e0506cee69d02644fabe34295a18c4a2eb7e7698600a5d05151d6b。这是单个 header 模型诊断，不是整项目容量验收 |

注解路径与 JSON 读取修改后的完整 JVM/native/daemon、MAIN 1800 秒预检、完整差分、3 冷/3 热容量及 release 仍需新回执；历史 14 gate 不能覆盖之后的实现变更。

| 本轮产物专项 | 结果 |
|---|---|
| `annotation-metadata-final-jvm-gate-fixed.log` | 212 个 JVM fixture 全部通过，归档/缓存/预算探针通过；旧 FIELD owner 排斥断言修正后的重新执行。随后重复注解测试的诊断 severity 断言进一步纠正，单项重验通过 `annotation-metadata-final-annotation-diagnostics.log` |
| `annotation-path-facade-proof/call-owner-diff.json` | 注解修正后 JVM：2177 核心声明、398 调用位置/目标/递归 owner，四项独立差异均 0；完整语义差分仍为 false |
| `annotation-metadata-final-release-package.log` | release native/Java 8 bridge 与包重建；19 项 SHA256SUMS 核对通过。native SHA 3553e8bedd6a87f7529c5c36a99b7c2f982a2ef780b72a7b659ff3bc5cce7081；bridge SHA c424fcb2071920b0ac737ba6184568d8834052b998e01c8bdf222107437da32b |
| `annotation-metadata-final-native-probe.log` | 实际 native → SDKMAN Java 8，含新增重复注解查询、FIELD/callback、NoCode/full/delta、取消、处理器 trap、bundle 完整性与主动 SIGSEGV 隔离通过 |
| `annotation-metadata-final-package-daemon.log` | 包内核心与 FIELD 两个真实 daemon 专项通过（23.221 秒）；不替代完整 32 个 daemon gate |
| `annotation-metadata-final-package-maven-bundle-receipt.json` | 包内 Rust 导出无 Python PATH；40 模块 MAIN 的 CP 顺序/SHA、根、依赖顺序、物理 id、输出根一致，外部控制输入及 native/bridge 三处 staging SHA 核对通过 |

`iopengw-main-release-preflight-20261001-224025` 在 1808.879 秒结束：TIMEOUT、未发布，总 RSS 峰值 1628356608 bytes，1 worker / 1 javac，源码与产物均无漂移。预算维持 1800 秒 / 4 GiB；任务截止后正常清理，无异常杀进程。本轮专项成功不改变仅 E01/E02 完整验收的状态。


### 二进制元数据、反射分支与 Rust 源码导航续验（2026-10-01）

| 变更 / 证据 | 结果与边界 |
|---|---|
| `binary-metadata-reference-receipt.json` | 冻结 Anatomist 原始 JavaBinaryReader / DTO 独立编译读取相同 SDKMAN Java 8 fixture：5 类、2118 成员，注解显式值、默认值、参数名/注解/计数、类型引用/路径差异为 0。仅 JVM/ASM fixture，不代表 native/真实项目通过 |
| BinaryIndex | 删除 2048 成员截断；8 MiB 加权 LRU，大类完整读取且不缓存；0/8192 bytes 缓存、负查询淘汰、2100 字段末尾方法、dotted nested binary header 校验与伪造类名拒绝通过。原 2 MiB class 输入及 8 个 JAR 句柄上限保持 |
| `binary-complete-semantic-tests.log` / `binary-complete-xml-query-budget-tests.log` | 18 项语义及 XML 大类末尾 factory 读取/查询预算降级/源码优先专项通过；旧 XML 截断断言按新契约修正 |
| `reflection-provenance-production-tests.log` | 13 项 JVM 反射 fixture 通过，含 switch、default/fallthrough、不一致分支拒绝；相同常量或 Class 句柄来源不同时合并、ternary/full/delta。仍为 possible，不伪造运行时确认 |
| `phase-journal-session-tests.log` | 7 项会话 fixture 通过；阶段日志有界 4096 记录 / 1 MiB，时间单调、并发/Unicode/失败路径/清理验证；诊断文件不能改变语义结果 |
| `source-origin-rust-workspace-tests.log` / `source-origin-rust-clippy.log` | Rust 工作区单测 70 项、clippy 通过；导航核对 origin 的 path/hash/world/module/scope，要求唯一已发布 javac SOURCE 声明，保留调用原 world/target |
| `source-origin-daemon-navigation-tests.log` / `source-origin-daemon-navigation-fixed.log` | 真实 debug daemon 的纯 JAR 拒绝、TEST-only 隐藏 MAIN 拒绝通过；跨模块 NoCode 导航修正测试后通过（57.009 秒）。默认 SOURCE 拒绝已改动工作区，指定 revision 读封存 blob；查询不改变事实。此专项使用先前 native，不能验证新 ASM/反射实现 |
| 待验收 | 修改后的完整 JVM/native/daemon、新阶段日志下同预算 MAIN、独立完整语义差分、3 冷/3 热容量与 release。52 项中仅 E01/E02 完成标记保持 |


| 本轮追加回执 | 实际结果 |
|---|---|
| `binary-reflection-source-jvm-full-gate-fixed.log` | 215 项 / 23 组 JVM fixture 全部通过；归档、FD、加权缓存和预算探针、独立 binary reader 一并通过。旧 XML 截断断言修正后的完整重跑；原失败回执保留，不据其声称通过 |
| `reflection-branches-reference-proof/receipt.json` | 冻结参考 jar SHA 457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e。switch 4/4、分支常量/句柄 7/7 的 operation/class/member/parameter/UTF-8 位置一致；数据库反射调用从 call_sites 读取。共同能力 fixture 用字面量拼接；参考不恢复测试中的静态字段 NAME，本项目另保留 javac 确认的常量能力。不代表全部反射语义/native 差分 |


| 本轮最终产物专项 | 结果 |
|---|---|
| `binary-reflection-source-release-package.log` | release Rust/native/bridge/package 重建，19 项 SHA 验证；native SHA 6b1386280c2134ffb5e1213482ed7d3487fa254b139b676dad40f17224eb651a，Java 8 bridge SHA 85b1c5630301627f504aba95efcf122382da5e8439a54b453a546c3ccf47995e |
| `binary-reflection-source-final-native-probe.log` | 实际 C ABI/SDKMAN Java 8 通过，新增完整大类成员及两类反射分支案例/full/delta；主动 SIGSEGV 隔离、取消、处理器 trap 与 bundle 完整性通过，无异常杀进程 |
| `binary-reflection-source-package-daemon-tests.log` | 包内 7 项真实 daemon 专项通过（96.575 秒）：跨模块 NoCode SOURCE/revision blob、隐藏 MAIN 拒绝、纯 JAR 拒绝、标签、反射、FIELD/callback、核心与二进制 XML edit/delete。不是一次完整 32 项门禁 |
| `binary-reflection-source-package-maven-bundle-receipt.json` | 无 Python PATH 的包内 Rust 导出：40 模块 MAIN 的 CP 顺序/SHA、源/资源根、依赖顺序、物理 id、输出根与冻结输入相等；helper 外部控制 hash 重新验证、输入已刷新 |

### 安全拦截恢复及生成方法覆盖续验（2026-10-02）

| 证据 | 实际结果 / 边界 |
|---|---|
| `iopengw-main-release-preflight-20261002-000714/receipt.json` | 14.213 秒失败、未发布；worker PID 99842 被 SIGKILL(9)，javac 尚未启动，源码/产物无漂移。用户确认安全拦截并恢复 release worker；此失败不能作为解析或容量通过 |
| `worker-security-restored-release-daemon.log` | 恢复后 release daemon → worker → javac 的 `test_core` 通过（8.251 秒）。使用恢复时既有 native，不包含之后新增 modeled override |
| `modeled-overrides-binding-tests.log` | 16 个 JVM 绑定 fixture 通过；新增 SOURCE/JAR 泛型/raw、协变返回、访问路径、非法 final/返回/擦除冲突及 delta/full。新增 SDK8 显式等价代码编译证明 raw JAR 覆盖合法 |
| `modeled-overrides-reference-proof/receipt.json` | 冻结 Anatomist jar 独立索引 4 类 fixture：产品覆盖全部 15 条参考覆盖边。三类签名/目标/推断证据投影零差异；JAR 类另有 2 条经 SDK8 编译确认合法的 raw 接口覆盖，参考未恢复。不代表全部类型、native 或真实项目语义零差异 |
| 待执行 | 新增覆盖的完整 JVM/native/daemon、同预算 MAIN 阶段日志与发布、全量独立语义差分、3 冷/3 热容量及 release |

缓存反例补充：首次新覆盖完整 JVM 门禁在 Data/Value/Builder/SuperBuilder 模块复用发现额外外部符号；`ModuleSignatures` 不再保存/恢复 modeled-override-v1 产物。`modeled-overrides-overlay-tests-fixed.log` 的 8 项及 `modeled-overrides-restored-source-tests.log` 的恢复父类/删除旧覆盖测试通过；完整门禁正在重新执行，原失败日志保留。

`modeled-overrides-jvm-full-gate-fixed.log`：缓存修复后的完整 23 组 / 220 项 JVM fixture 全部通过，suite 合计 513.766 秒；归档/FD/原始 ASM 独立对照和各预算探针通过。原失败日志保留。新 native/daemon 与 MAIN 仍待执行。

本轮新产物：`modeled-overrides-release-package.log` 构建完成且 19 项 SHA 通过；native SHA c320b5cb5545dd8731b532c1009dcd6916722277938ce8bb5f36fad493f96191，bridge SHA c85007279961bbb0254c59ef0d023c23f72cd3cce7dc129297904cdd274773ff。`modeled-overrides-final-native-probe.log` 的 C ABI/SDK8、SOURCE/JAR 覆盖和模块缓存新案例、主动 SIGSEGV/取消/处理器 trap/完整性通过；包内新增 daemon 覆盖关系/派发/full-delta 测试通过（10.565 秒）。40 模块 Rust Maven MAIN 输入一致，5 项外部控制及三处 native/bridge SHA 验证通过。MAIN 同预算预检已启动，完整 33 个 daemon 和全能力/语义/容量门禁尚未完成。

MAIN `iopengw-main-release-preflight-20261002-010213`：1808.100 秒 TIMEOUT、未发布，36 个模块于阶段时间 1723.505 秒完成，进入项目阶段后截止。core/service 的 java_extensions 412.114 秒，javac/model 20.520 秒、事实提取 29.417 秒。峰值 RSS 1349287936 bytes；输入/产物无漂移，无安全拦截或异常杀进程。阶段日志与线程样本保留；约 98 MB 的完成后 core/service 声明模型已捕获，不能冒充完整 Java 阶段模型或容量通过。


### 扩展读取容量修复（2026-10-02，待 native / MAIN）

| 改动 | 验证边界 |
|---|---|
| 调用选择 | JavaCallSiteEvidenceProvider 默认仍读取完整不可变调用；Lombok 先用 receiver_type 判断。200000 条拒绝调用不读取属性图；筛选失败隔离、取消传播、损坏输入终止和阶段日志不改变事实已通过 JVM 专项 |
| 类型 / world 索引 | createFacts 任务内分页 int 索引最多 5000000 项；world 字典最多 4096 项 / 1 MiB 权重。未知类型、字典/条数超限和重开归档严格解析回退；不改变 CAF1 字节或协议 |
| 框架声明索引 | FrameworkAnalysis 不再解码全部事实；用头信息按 SOURCE / world 优先级建立位置索引，仅实际查询解码。移除未被读取的 Bean 缓存；不可变 ExtensionContext 保留 FactHeaders 接口 |
| 现有专项 | 60 Lombok/扩展用例通过；32 MiB / 197 MB 归档原字节 SHA、5000 world 字典超额回退、未知类型、复制/视图/取消/关闭已通过。后续框架扫描修改的最终回归和重建 native 尚待执行 |
| 真实瓶颈证据 | 010213 的 core/service java_extensions 412.114 秒；归档头约 64 MiB 达到配额。全量解码由代码确认，尚无叶阶段耗时证明各函数占比；不宣称真实性能修复已经通过 |


扩展读取最终验证：`extension-routing-jvm-final-gate.log` 的 23 组 / 220 项全部通过（suite 423.779 秒），冻结 233 个源码输入无漂移；含 200000 条混合事实不解码无关图、SOURCE/world/重复声明排名与不可变视图专项。`extension-routing-release-package.log` 构建完成，19 项 SHA 通过；native SHA c81398d658cea16109c9cc50e60202e4044db0cdc2198b3b031a57ed8e7710b1，bridge SHA 7545fcc3d7bc48c95248c7544003f5742141db4ca11cabb142a403764cd96970。`extension-routing-final-native-probe.log` 的真实 C ABI、SDK8、缓存/full、主动 SIGSEGV、取消/清理/AP trap/完整性通过；包内 2 项 daemon 通过（17.725 秒）。40 模块 Rust Maven MAIN 输入等价、40 POM / 5 外部控制和三处 bundle SHA 验证通过；同预算 MAIN 已启动。完整 33 项 daemon、独立真实语义与 3 冷/3 热容量、最终 release 尚未验收。


MAIN `iopengw-main-release-preflight-20261002-022345`：1807.578 秒 TIMEOUT、未发布；36 模块于阶段时间 1096.603 秒完成（前轮 1723.505 秒），core/service Java 扩展 156.990 秒（前轮 412.114 秒）。全部项目扩展已完成，1733.294 秒进入 result_write 后截止。峰值 RSS 1351942144 bytes，233 源码输入及产物无漂移；无安全拦截或异常杀进程。叶阶段日志定位项目注入与最终输出：后续已接入候选身份摘要筛选（仍精确核对字符串）和严格校验后写出编码归档（Unicode surrogate 转义保留旧归一化路径）；最终 fixture/native/MAIN 验证尚待完成。


### 严格编码输出、身份筛选与首次 MAIN 发布（2026-10-02）

235 个源码输入冻结的最终 JVM 门禁：23 组 / 220 项全通过，suite 380.621 秒；`encoded-output-identity-jvm-final-receipt.json` 保存 SHA/输入无漂移。197 MB 编码归档在 SDK8 32 MiB 堆输出，与独立 CAF1 帧拼接 SHA 一致；损坏 UTF-8/重复键/数字/深度、取消与 surrogate 转义兼容通过。候选 ID 的有界分页摘要只排除不匹配项，命中仍逐字核对；200000 条只读取一次头，碰撞/悬挂/重复防护通过。

native SHA 58d40679099deccdbed0973db5f5f879aa01857e7bceaac7401d849a57aeee4d、bridge SHA 89e15201af0e2547fd31ea3f804e4fe8a02a47b641f20fe4896b10b4397b7594；19 项包 SHA、三处 bundle、真实 C ABI/SDK8/native SIGSEGV/缓存/full/取消/清理/AP trap、包内 2 daemon（17.582 秒）和 40 Maven MAIN 模块输入验证通过。

MAIN `iopengw-main-release-preflight-20261002-033143`：36 模块于 1049.001 秒进入项目准备，1289.164 秒进入结果写出；结果 3512279749 bytes。1603.791 秒已发布 `rev_2e7ed08748504f5b9c34527c8e42c98e`，数据库 5896310784 bytes，catalog/head/DB identity 相同。树 RSS 峰值 1432829952 bytes、daemon 354795520 bytes、单 worker/单 SDK8；无输入或产物漂移、无安全拦截。原 runner 因文本终态无输出记录 `semantic_preflight_published=false`；不能篡改原 receipt，另有 `catalog-publication-verification.json` 验证真实发布。CLI 返回 OUTPUT_FAILURE/TERMINAL_OUTPUT_BUDGET：1000 条覆盖原因约 151 KiB 超过默认 32 KiB，并非索引或进程被杀。

Rust 修复仅压缩文本终态证据明细，标记省略数，TaskResult/typed terminal 保留全部已收集原因；publication/revision/partial flags 不变。严格文本截断拒绝、UTF-8/转义字节预算、原失败退出码及普通协议字节兼容通过；Rust 工作区/clippy/14 daemon 与 192 缺失类型文件 Java 真实索引/查询（18.284 秒）通过。测试最初误用 defs/限定名 resolve 的两次失败原日志保留。真实 iopengw CLI 尚待重试。

首次只读独立 MAIN 投影：调用点及 UTF-8 半开位置 61527/61527、零缺失/多余；声明 33792/33729（未归一匿名类类别/身份）、目标 59210/61511、归属 492 对差异。已确认匿名类的原源码区间一致，正在按唯一相等区间归一命名；原始差分保留。完整递归类型、扩展/高级恢复、独占 3 冷/3 热容量、完整当前 daemon 和最终 release 仍未验收，不能勾选剩余 50 项。

### 源码身份、Lombok 和调用上下文对齐续验（2026-10-02）

| 证据 | 结果 / 边界 |
|---|---|
| `terminal-evidence-budget-full-java-daemon-receipt.json` | 原生包 58d40679 / bridge 89e15201 的完整 35 项 daemon 回归通过，2066.102 秒，6 项产物 SHA 无漂移；验证 Rust 文本预算修复，不含之后的 Java 规则 |
| `encoded-output-identity-main-core-canonical-final-diff.json` | 63 个匿名类按唯一相等原源码区间归一；33792/33792 核心声明仍有 16 对 Lombok 命名差异；61527 调用点位置与全部递归归属零差异；目标缺失 2650、多余 349，仍需修复 |
| `encoded-output-identity-main-structural-canonical-diff.json` | 核心覆盖 6773/6773、写入 2675/2675 逐条相等。旧 inherits 映射与 external endpoint 缺失已修正；读取/类型/注解仍是诊断投影，不是完整关系验收 |
| `lombok-basic-reference-proof/receipt.json` | JVM：17 个生成方法的名称、参数类型/名字和返回类型与冻结参考一致；修正 oAuth/eCGen/iOpengw/xContent/Unicode titlecase 和 equals/canEqual 的 other 参数；builtin 6 使旧扩展缓存失效 |
| `inheritance-reference-proof/receipt.json` | JVM：6 条继承/实现逐条相等，匿名枚举体通过唯一源码 span 核对；去隐式 Object/Enum 图边、接口/注解 extends、显式 extends Object 保留；结构化 superclass 仍完整 |
| `poly-reference-proof/receipt.json` | JVM：6 类 fixture / 32 个调用目标的签名、possible/exact 与原源码位置零差异；包括隐式 lambda/SAM、嵌套 Function Map、已绑定 Object 方法的 modeled 替代、调用参数目标、失败候选隔离及反例。复杂 LUB/块完成性与数组返回仍保守；不能当成全部高级推断通过 |
| `source-field-reference-proof/receipt.json` | JVM：4 类 fixture 的读取及源码注解投影逐条相等；源码枚举 case 的 possible 读取、class literal 去伪字段、builder 原字段注解映射。外部字段按冻结 ExternalTypePolicy.DEFAULT 比较；产品仍保留有效 JDK 字段绑定 |
| 原始失败证据 | 参考默认过滤 java.lang.* 等字段曾导致注解元数据/Boolean.TRUE 表面差异，未以删事实冒充通过；撤回额外过滤后按同配置对照。泛型数组返回多出目标的初次差分保留，恢复相同保守边界。受限环境写 ~/.anatomist 的失败已改为测试目录 ANATOMIST_HOME；这不是进程被杀 |
| 待验证 | 该版完整 JVM 已执行 230 项：229 通过，1 项的最后 ps 孤儿检查被沙箱拒绝，未按跳过处理；新 native/package/daemon、同预算真实 MAIN/CLI、独立完整类型/扩展差分、3 冷/3 热及最终 release 尚待；当前受限执行环境拒绝 ps，不能据不完整 RSS 采样声称容量通过 |


### 深层类型树与完整性修复续验（2026-10-02）

| 项目 | 证据 / 边界 |
|---|---|
| 上一版 JVM | `source-rule-alignment-jvm-receipt.json`：230 项、23 组，229 通过，Sessions 的 ps 检查权限拒绝；236 个输入无漂移。实际强制编译器超时和任务目录清理已执行；不能替代孤儿进程检查 |
| 真实缺口 | `recursive-type-reference-proof/before-fix-receipt.json`：参考保留 20 / 40 层 List，产品仅保留 16 层，但根节点仍 complete=true |
| 修复 | 类型树 48 层 / 4096 节点；未知/截断向上传播不完整；预算去重告警并仅对应源码 partial。循环引用保持 owner/name 和真实已知身份，避免误拒递归 Builder。Java JSON/归档/扩展复制同步容器边界，字节和总节点预算保留 |
| 专项 | `deep-types-cycle-fixed.log`：深层类型、40 层 TYPE_USE、未知类型、预算披露、健康文件隔离、full/delta 和原递归 Builder 均通过。初次 Builder 连带失败日志保留 |
| 独立对照 | `recursive-type-reference-proof/receipt.json`：5 / 20 / 40 层与冻结 jar 一致；`structured-types-reference-proof/receipt.json`：7 个字段的嵌套类型/enclosing、数组、上下通配符、交集/F-bound、primitive 和源码 TYPE_USE 有效值逐节点投影相等；不包含完整 MAIN 或所有绑定 |
| 当前门禁 | `deep-types-jvm-final-receipt.json`：231 项中 230 通过，1 项 ps 孤儿检查被沙箱拒绝；初次归档旧 64 层硬编码失败后，只修改 EncodedOutputProbe 测试边界，并独立重测整套归档通过。产品源码未变，初次完整日志与后续测试 SHA 分别保留。新 native 正在构建，daemon/真实 MAIN/容量待验证 |
| 环境 | `restricted-runtime-check/receipt.json`：daemon 启动 0.14 秒返回 Operation not permitted，worker 未启动；不是异常杀进程。当前不能完成 daemon、真实 MAIN 和 ps/RSS 容量验收 |

上述类型预算仍低于参考平面类型图可表达的全部深度，不能标为 J02 完整通过。保持只有 E01/E02 全验收，不据专项勾选其余 50 项。

### 权限恢复后的验收与模型快照修复（2026-10-02）

| 项目 | 证据 / 结论 |
|---|---|
| 环境恢复 | ps、daemon 已可运行；Sessions 全 7 项通过。`deep-types-jvm-unrestricted-final-receipt.json` 合并原完整 JVM 与归档、Sessions 重测，231 项全部通过；原失败证据保留 |
| 上版原生链路 | native 645228fe / bridge 7899c8fd：17 项专项、完整 C ABI 探针、Rust IPC full/delta、包内 1 项组合 daemon 通过；不是完整 36 项 daemon 或真实 MAIN 通过 |
| 最新 MAIN 失败 | `iopengw-main-release-preflight-20261002-094716/receipt.json`：1023.735 秒、app/core/service 的 java.sofa-annotations 原生堆耗尽，未发布。原生堆仍 512 MiB，进程树峰值 1302577152 bytes；源码/产物无漂移，未观察到安全软件杀进程 |
| 内存证据 | `type-sharing-memory-proof/`：2624 源码的只读 parse/analyze 模型 258148151 bytes。512 MiB JVM 中旧单次快照存活堆约 349 MB；类型共享及归档快照后约 274 MB。前后 7 节、376303 行逐行值相等，物理归档 SHA 因共享/键序变化不同；不宣称原生峰值或 MAIN 已通过 |
| 修复边界 | 替换仅复制变化的类型路径；修改/追加行在回调入口封存到同模型字节限额的私有 RowSpool，按行解码，模型关闭清理。Java/call 回调共用完成后的模型快照，各自固定事实视图长度。原始编译器、候选隔离、可变别名、取消/超限/关闭语义保留 |
| 新语义缺口修复 | 生成字段/方法/构造器签名补类型引用，数组与泛型参数保留重复；聚合投影与参考相同，泛型深度上限 5。引用关联生成来源源码区间，不冒充 javac-confirmed。`generated-type-reference-proof/receipt.json`：36/36 返回/参数/数组/嵌套/通配符/深度边界引用，含原始 UTF-8 区间，零缺失/多余 |
| 剩余门禁 | 此轮完整 JVM、新 native/package/完整 daemon、同预算 MAIN、独立全量语义差分、3 冷/3 热容量与 release 均需继续；矩阵其余 50 项不勾选 |

本轮门禁更新：`type-sharing-full-jvm-receipt.json` 的 26 脚本 / 232 项全部通过，568.700 秒，238 个源码输入无漂移。native f0c206fb / bridge 9c4bfcfd，19 项包 SHA 与三处 bundle 一致；`type-sharing-native-receipt.json` 的 18 项专项通过，40.301 秒。完整 C ABI 探针、Rust IPC 的 40 层完整/60 层 partial、两轮 full/delta 与 Acked/热 host、包内组合 daemon 加生成引用断言均通过（daemon 8.279 秒）。40 模块真实 MAIN Maven 重导出的模块、POM、profiles、有效模型和有序 CP 全相等，仅 Maven 导出 JAR SHA 更新；无业务 class。原预算 MAIN、完整 36 项 daemon、全量独立语义差分与容量仍待。
# 正确性优先的真实项目验收

2026-10-02：增加 `tests/java_real_acceptance.py` 正式入口，默认 `--deadline-seconds 3600`；这是执行保护，不是耗时达标线。用户确认先完成正确性，再执行 2 GiB/3 冷/3 热容量测量并报告耗时。

配置采用项目锁、写前备份、条件恢复及 PID/启动身份；回执在启动前及每轮阶段取样后原子写入，下一次运行可恢复被杀 harness 留下的配置。存活旧进程、备份漂移及用户配置变化均拒绝自动恢复，不误杀或覆盖。9 项独立恢复测试通过，包括主动 SIGKILL。仍需真实项目完整发布及独立差分；不得将 running 回执、局部 fixture 或旧产物发布判为本轮对齐完成。

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
