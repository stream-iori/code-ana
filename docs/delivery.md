# 实施与验收

对齐 2026-10-13：先 Java fixture，再冻结真实项目；开发构建、产品回归、真实项目观测与正式验收分开。最新范围见 [当前实现](current-state.md)，原回执见 [存储实施记录](implementation/anatomist-storage-migration.md)。

## 1. 当前状态

| 已落实 | 仍需完成 |
|---|---|
| Rust CLI/daemon/gRPC、任务与取消、不可变 revision/CAS | 规模化公平性、内核级资源隔离 |
| Java 8 javac、Java 25 Native/G1、C ABI、NoCode、框架静态图 | 目标 Java 17/25、Gradle、Linux、完整框架覆盖 |
| GRA1 行交付、物理 schema 7、逻辑 schema 33、FTS | 当前版本完整语义/来源覆盖验收；过期 raw transport 测试已删除 |
| 查询/pipeline、源码、快照/diff、报告、GC、独立人工标注 | 标注接入 typed pipeline、跨语言语义 |
| 冻结 iopengw 一轮：214.28 s、facts.db 233.49 MB | 独占容量/稳定性能承诺；native_accepted/performance_accepted 仍 false |
| 独立 mock 产品回归 | rust-analyzer syntax/semantic 语言分析 |

旧版本 complete 回执不能放行新版本。图 fixture 不替代 raw model/AST 门禁；Hello 只证明接入，build-only 只证明构建与其实际运行过的测试。

## 2. 验证入口与顺序

| 顺序 | 命令 | 验证内容 / 前提 |
|---|---|---|
| 1 Java fixture | just test-java | SDKMAN Java 8 + 固定 GraalVM 25；桥、模型、组件测试 |
| 2 Rust 产品 | just test-rust | DDL 生成一致、fmt、workspace、构建、图/文本/标注 E2E、clippy；无 JDK |
| 3 Native/G1 | just test-java-native | 开发构建并安装 SDK、manifest、ABI 生命周期、GRA1 图、真实崩溃 |
| 4 原路径产品组 | just test-java-native-product EVIDENCE | group=graph；需已构建且兼容 bundle，不签完整迁移验收 |
| 5 分发 | just test-java-release | 构建 release、SHA/包检查、图/错误 JDK/自带 fixture；原 Cargo 路径临时 release 后恢复 |
| 6 冻结真实项目 | scripts/java-compact-storage-single.py 的参数以 --help 为准 | HEAD 267c7ced4993aa88613f8f17a8099943fec98159，MAIN+TEST，仅已提交内容 |
| 正式构建前置 | scripts/check-java-jvm-acceptance.py | 仍要求有效完整 JVM 回执和源码 SHA；删除旧门禁不等于当前正式迁移通过 |

`./scripts/build-java-native.sh --build-only` 走完整开发构建，默认 G1；不带参数要求有效 `CODEANA_JVM_ACCEPTANCE_RECEIPT`。安装使用 `python3 scripts/install-java-native.py`，检查原路径无活跃进程、校验 SHA、归档旧 SDK 并原子替换。

JVM 图产品门禁同样使用原 `target/debug/codeana-*` 路径；新构建的 bundle 校验后安装到 `target/lib/java/jvm`，旧 bundle 归档在本轮证据的 `jvm-install/previous-jvm`。替换前要求无活跃进程，失败恢复旧 bundle。

`just smoke` 调用正式 Native 构建，只验 gRPC/FFI Hello；不应作为当前开发构建的默认推荐。实际入口以 [justfile](../justfile) 与脚本 help 为准，过期 Fact IR/CAF1 门禁、测试和旧文档已删除；共享进程工具位于 tests/product_support.py，当前主门禁使用 rust_graph_e2e.py。

## 3. 正确性门禁

| 场景 | 必须成立 |
|---|---|
| full / delta / healthy worker 复用 | 同输入/profile 的逻辑多重集、重复、NULL、coverage、诊断及来源一致 |
| 修改/新增/删除/停用 producer/XML 删除 | 清理相应归属；失败查找和依赖传播不留下旧事实 |
| MAIN / TEST / module / world | 语义读取与发布范围分开，身份不串；不支持的图明确拒绝 |
| 字典/整数引用 | 公共 ID/hash/ordinal 不变；NULL/空路径分开，完整外部目标元组不被简化 |
| metadata | 只合并原始相同字节；摘要碰撞仍核对完整内容，JSON 不重排 |
| 元注解 | 首次位置、归属状态、删除与跨 producer 观察保留；定义共享不丢来源 |
| FTS / 查询 / pipeline | 整数 node_pk 映射公开 ID；固定 revision；上游缺口传播；续页不漂移 |
| 错块/错序/错摘要/EOF/End/引用 | candidate 拒绝，不发布半图 |
| G1 / 取消 / OOM / 真实崩溃 | 每进程一次 isolate；失败换进程，旧 head 保留，所属 javac 回收 |
| 提交窗口/断连/重复请求 | catalog commit 裁决；发布后故障不伪称未提交，不重复写入 |
| 普通/历史 source | 普通当前文件 hash 不匹配拒绝；兼容历史图读捕获 blob，无需当前 JDK |
| 旧 graph schema | 查询/delta 明确要求 index --full；全量成功再切 head，旧文件保留 |
| GC / 标注 / 报告 | 引用保护；人工正文保全；报告失败与 publication 分开 |
| 输入准备 | index 不运行目标构建/处理器；缺输入给 BuildAdvice；外部准备后重新捕获 |

fixture 的预期来自源码语义、人工断言或固定参考多重集；必须记录来源 commit/hash。`lang-java-adapotr/e2e/fixtures/*/AGENTS.md` 是参考场景输入，不改写成 code-ana 开发指令。

## 4. 性能口径

```text
固定输入 / 成套产物 / 预算
   → 独立空应用状态
   → 一轮 Native/G1 全量索引
   → 完整逻辑图校验 + 公共查询
   → index 秒 / 同时刻进程树 RSS / 磁盘峰值 / 发布 facts.db 大小
```

Native 没有冷热分类。应用缓存状态与 worker 复用是独立条件，记录即可；本次只做一轮，不要求三轮测量。启动时间、全量、增量和查询分别解释，阶段重叠不能直接相加。

214.28 s 是本轮唯一实际索引流程，随后同库查询修复未重索引。233.49 MB 是发布的单个 SQLite 图，不是 GRA1 传输字节、分析临时目录或整个 home；索引时存在捕获、归档、candidate、backup 和旧 revision，磁盘峰值另记。比较不同 GC/后端/源码版本的单点不能全部归因到单项优化。

当前 854 个 partial 文件保留，不能写成完整语义覆盖；当前 native_accepted/performance_accepted 未关闭。

## 5. 文档校验

| 变更 | 检查 |
|---|---|
| 路径/导航 | 项目内链接存在；参考仓库链接标明历史来源；已删路径改作历史文字 |
| 格式 | Markdown 围栏完整、章节锚点有效、表格可读 |
| 配置 | TOML 可解析，cargo test -p codeana-config --lib 验证 schema 示例 |
| 版本/命令 | model 常量、ABI、.proto、DDL、.sdkmanrc、justfile 和脚本一致 |
| 证据 | 历史数字/失败不重写；已通过范围与未完成门禁分开 |

只修改文档时运行相关校验，不因文档同步重跑大型索引或 Native 构建。
