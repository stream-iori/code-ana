# Anatomist 存储迁移

> 现行入口：[当前实现](../current-state.md)、[存储模块](../modules/storage.md)、[公共契约](../contracts.md)。本页按轮次保留演进和原始失败，最新结果在“紧凑存储实施与唯一一轮验证”；此前 schema 32 / 606.65 MB / Serial 等按各自产物解释。

实施记录，2026-10-13。最初参考 `../anatomist` 的 `dev/replace-parser` 提交 `dff2e3954096853352ec91fcbfc6b995fbd4f390`；紧凑存储继续同步 `4a903272c22083585107e982f2899ed03a280aa2`。保留 Rust catalog、源码 blob、不可变 revision、snapshot/diff 和人工标注；语义图、索引和 staging 迁入 Rust。下文旧轮次指标保留，最新紧凑存储结果见文末。

```text
javac 8 → Anatomist 原始记录 → Java 25 / Native C ABI 4
                                  │ GRA1 + protobuf Row
                                  ▼
Rust worker → 有界流 / 校验 End → Rust staging → compact schema 7 / 逻辑 schema 33
                                                   │
                                        WAL + 关闭 + fsync + catalog CAS
                                                   ▼
                                          不可变 revision / 查询
```

| 项目 | 当前实现 |
|---|---|
| 数据 | 节点/声明、非 CALLS 边、调用点和目标、注解/元注解、语义标注、依赖、诊断、覆盖与 provider |
| 编码 | 固定列类型；NULL 与空字符串分开；UTF-8 直接写复用的 protobuf 缓冲；不构造完整 Fact JSON，不调用每条记录的 toByteArray |
| 传输 | GRA1 魔数 + 大端 32 位长度 + protobuf Row；SHA 覆盖原始 framing；单行 8 MiB，最多 34 列；控制帧拒绝旧格式和重复 oneof |
| 批次 | Java 32 文件或 10,000 事实刷新；Rust staging 10,000 行事务、2048 行 hash 分页；有界通道保留背压 |
| 全量 | 私有 OFF 临时库；保留唯一约束，暂停非唯一索引与 FTS 触发器；导入后重建索引/FTS |
| 增量 | SQLite backup；保留未变化的 hash/ordinal；源文件替换和受影响调用点刷新；重算派生 wiring 与依赖 |
| 查询 | 直接查询 Anatomist 表及 FTS；实体按需组装；raw type/binding/syntax 明确不支持 |
| 发布 | 完整校验；重新打开连接切换 WAL/NORMAL、checkpoint、关闭；沿用 fsync/catalog CAS，失败保旧版 |
| 版本 | graph model 2 / worker 18 / request 9 / C ABI 4 / daemon 3；product user_version 7 / 上游逻辑 schema 33 / GraphSemantics 26；旧图须全量重建 |

| 验证 | 现有结果 |
|---|---|
| Rust 存储 | 26 项通过：非法来源/类型、未封口数据、取消、CAS、旧读者、GC、文件依赖、分页和源文件替换 |
| Rust 检查 | `just test-rust` 全部通过：fmt、workspace tests、4 项图端到端、2 项文本搜索、4 项标注、Clippy all-targets -D warnings；额外 all-targets example 的历史 SIGKILL 单独记录 |
| Java 组件 | 633 项测试通过，无失败、无跳过 |
| JVM 产品 | 4 项通过：全量/修改/删除与全量逐表一致、旧 revision、搜索/调用/源码/外部目标、XML module/scope、未变更图 |
| Native 产品 | 对应 4 项通过；C ABI 生命周期 7 项、原进程路径 SIGSEGV 隔离通过 |
| iopengw JVM | 已生成 606,650,368 字节图库；参考 604,135,424 字节，增加 0.416% |
| 一轮 Native 性能 | 已完成唯一一轮：582.84 → 231.52 秒（-60.3%）；不区分冷热，不进行三轮或 Native warmup |

完整表字段采用逐行校验，保留重复次数与 NULL。JSON 对象字段顺序、自动主键、事实 hash 和创建时间不作为跨进程语义差异；诊断中的 `classpath_order_sha256` 反映实际执行路径，单独记录。调用点、目标、注解/元注解、文件依赖、provider 等已与参考一致。

冻结项目还有已定位的差异：保留与同名方法分开的合法隐式构造器（1 个节点、1 条 symbol dependency、修正 CONTAINS 终点）；无效 UTF-8 文件明确交付 1 条诊断和 2 条覆盖记录。不能把这些差异写成“全图完全相等”。旧 raw model/AST 门禁的已知构造器问题也不因迁移存储自动消失，`native_accepted` 暂保持 false。

证据目录：`target/implementation-evidence/anatomist-storage-migration-01`。首轮清理已释放实际约 189.72 GiB；保留参考库、冻结源码、原 Native 基线和小型回执。后续清理只保留最终验证所需闭包，不保留废弃大库副本。

## 单轮测量与后续查询修正

| 项目 | 旧存储基线 | Anatomist 存储 |
|---|---:|---:|
| Native 全量索引 | 582.84 秒 | 231.52 秒（-60.3%） |
| 图库 | 9,210,683,392 字节 | 606,650,368 字节（-93.4%） |
| 进程树峰值 RSS | 1.69 GiB | 1.56 GiB |
| 测量目录逻辑峰值 | 13.11 GiB | 2.69 GiB |

六项固定查询中五项更快，原始 `overview` 0.203 → 0.413 秒。修正无筛选的重复 JOIN 后，再将一次计算的统计放入既有 `project_meta` 的 `codeana.overview_counts`；带筛选查询仍按 SQL 读取。用同一图库私有副本回放得到 0.148 秒，输出计数与独立 SQL 一致、原图库 SHA 不变。这个回放没有再次运行索引，不能冒充第二轮 Native 测量。

性能回执认证的是上述单轮测量，不自动设置 `performance_accepted` 或 `native_accepted`。测量后补了 overview 和增量继承摘要，已保存测量当时的二进制、库和源码快照；最终产物与测量产物分别记录。没有把正式门禁的历史未完成项标成通过。

已移除旧的 CAF replay / Fact wire 示例和对应存储路径。`just test-rust` 改跑类型化图的 4 项端到端场景，并保留文本搜索与人工标注测试；旧 Fact ID / body SQL 的 fixture 断言属于退役格式。

收尾又释放约 9.89 GiB；完成时可用空间约 336.6 GiB。已删除单轮基线的大库及私有查询回放副本，保留基线 runtime、参考库、新图、完整回执、原始测量与最终源码快照。最终封存见 `implementation-receipt.json`。

## 测试脚本清理与参考耗时

移除未被当前门禁或测试导入的临时辅助脚本：

| 脚本 | 退役原因 |
|---|---|
| `tests/java_direct_index_request.py` | 生成旧 request 8 的独立诊断请求，当前为 request 9 |
| `tests/java_fixture_suite.py` | 针对旧 `lang-adaptor/target` 的已构建产物回放，未接入当前门禁 |
| `tests/java_parallel_benchmark.py` | 旧 Fact JSON、多轮预热基准，与当前类型化图和单轮 Native 测量不符 |

同时移除指向已删除 `fact_replay` 示例的 `just replay-java-facts`。保留仍有门禁、导入或能力验收引用的测试；历史验证记录继续保留。

冻结的 Anatomist `dev/replace-parser` 参考索引进程耗时为 **323.604 秒**，回执全流程为 375.907 秒（包含准备和校验，不能当作索引耗时）。证据：`target/implementation-evidence/iopengw-fresh-reference-01/receipt.json` 的 `execution.seconds`。相对当前 code-ana 231.525 秒，索引耗时减少约 **28.45%**；参考使用 JVM，code-ana 使用 Native，因此属于不同后端的观测比较。两者均使用上述冻结 iopengw 提交。

## G1 生命周期修复与验证

2026-10-13：Oracle GraalVM 25.3.4.1 在 macOS ARM64 成功生成 G1 共享库（57.31 秒）。首次直接执行完整 C ABI suite 时，第二次创建 isolate 触发 `GC doesn't support multiple isolates at the moment.`，首次 teardown 后重建也不支持。

修复采用进程级生命周期：Rust 在调用 GraalVM 前检查 isolate 历史，拒绝同一进程再次创建 G1，返回 `G1_ISOLATE_PROCESS_RESTART_REQUIRED`；销毁不清除历史标记。正常任务复用现有 isolate，取消/致命错误退出 worker，由 supervisor 启动新进程。Serial 多 isolate 能力保留。

| G1 验证 | 结果 |
|---|---|
| ABI 生命周期 | 8 项通过：7 项单 isolate 用例各自独立进程，另验证两个进程同时持有满额 session；线程 attach/detach 单独检查 |
| Rust 重建防护 | 真实 G1 库 teardown 后再次 load 返回 Rust 错误，进程正常退出；集成测试显式执行通过 |
| 产品图 fixture | dev 5 项、release 6 项通过：完整图、修改/删除增量、旧 revision、FTS/调用/源码、XML、未变化图；取消两次及真实 SIGKILL 后重新索引成功 |
| G1 真实 OOM | 8 MiB native 堆分析 5,000 个方法，返回 NATIVE_HEAP_EXHAUSTED；保留旧 revision，恢复预算后新 worker 索引成功且图一致 |
| Serial 回归 | 8 项 ABI 通过，仍验证同进程独立 isolate |
| Rust 门禁 | `just test-rust` 通过；G1 外部库集成测试另行显式执行，不依赖默认测试提供 GraalVM |

G1 诊断库及回执位于 `target/g1-build-diagnostic`，Serial 安装已归档至其 `serial-installation`。用于产品验证的 `target/lib/java/native` 安装 G1；原 `lang-java-adapotr/target/native` 的 Serial 构建产物保留。未绕过完整 JVM 验收前置门禁，`native_accepted` 和 `performance_accepted` 不因这些回归自动设为 true。

仅新增一轮冻结 iopengw 的 G1 release 索引，未重跑三轮，也不区分 Native 冷热。该轮按相同项目配置使用 2 GiB native heap / 512 MiB javac heap。12 张语义表的逐行 multiset 摘要与既有 expected graph 一致（保留 NULL 和重复、规范化 JSON、排除执行路径 provenance）；6 项固定查询通过，输入 SHA 无漂移，查询未改变图库。

| 指标 | 历史 Serial | 本轮 G1 |
|---|---:|---:|
| 索引进程耗时 | 231.525 秒 | 248.904 秒（+7.5%） |
| 图库 | 606,650,368 字节 | 606,650,368 字节 |
| 进程树峰值 RSS | 1.56 GiB | 2.55 GiB |

该比较包含测量后 overview、增量继承摘要和本次 worker 防护等版本差异，不能把全部变化归因于 GC。单轮没有显示 G1 的性能收益，当时构建默认仍为 Serial（后续已按用户要求切换 G1）；当时产品测试安装保留 G1 供继续验证。测量命令使用原 `target/debug/codeana-*` 路径，release 构建 SHA 逐文件绑定；旧 dev 二进制及 Serial 库分别归档，没有改名执行。完整结果见 `target/g1-build-diagnostic/regression-receipt.json` 与 `iopengw-single-01/phase/receipt.json`。

## 正常构建及产品链路的 G1 复验

通过 `CODEANA_NATIVE_GC=G1 ./scripts/build-java-native.sh --build-only` 执行同一脚本的 Maven 编译/测试、Java 8 编译器桥、Maven worker、Native Image 链接和 manifest 生成。完整 JVM 迁移回执已有源码漂移，所以显式开发构建标记 `native_build_mode=build-only`；默认构建仍要求完整回执，不签署迁移验收。

本轮 633 项 Java 测试通过，无失败/错误/跳过。构建在启动 Native Image 前暴露 macOS Bash 3.2 的空数组问题：`set -u` 下 `native_monitoring[@]` 报 unbound variable。已改为始终非空的命令数组，仅在指定 JFR 时追加参数；使用已通过且源码未变化的 Java 测试结果，以 `--build-only -DskipTests` 重试后完成 G1 链接，Native Image 用时 55.9 秒。源码 bundle 的 GC 和 SHA 均已校验，并原子安装至原 `target/lib/java/native`。

正常 `CLI → daemon → Rust worker → C ABI → G1 → SQLite` 的 6 项 fixture 全部通过，包含连续任务复用、增量/删除与全量一致、查询、取消、SIGKILL、真实 OOM 后恢复和旧 revision。未采用同一进程反复销毁/创建 isolate 的测试流程。构建默认门禁 6 项、bundle metadata 3 项通过。证据根目录：`target/g1-normal-product-01`。

正常构建产物在冻结 iopengw 上新增一轮完整索引，结果已发布：

| 项目 | 本轮 G1 正常产品流程 |
|---|---:|
| 索引命令耗时 | 234.879 秒 |
| 图库大小 | 606,650,368 字节 |
| 进程树峰值 RSS | 2.856 GiB |
| 完整图摘要 | 12 张语义表与 expected graph 一致 |
| 固定查询 | 6 项通过 |
| isolate 错误 | 无 |

维持既有 854 个 partial 文件的覆盖结果，没有将其写成完整语义覆盖。输入 SHA 无漂移、查询未修改图库、测量时无其它分析进程。历史 Serial 231.525 秒与本轮相差 +1.45%，历史 G1 248.904 秒也只是另一轮观测；不据这些单点推断稳定 GC 收益。旧 abort 来自同进程反复创建 isolate 的测试，与此正常索引流程不同。

## G1 默认与相关测试入口

2026-10-13 按用户要求将 Native 默认 GC 统一为 G1：构建脚本、manifest 生成和默认性能变体一致；`CODEANA_NATIVE_GC=serial` / `--gc serial` 保留。无 GC 环境变量的 Native 构建已确认输出 G1，47.7 秒。Java 源码未变化，沿用上一轮 633 项组件测试，不重跑性能测量。

生命周期测试覆盖完整 suite、单个方法和 discovery；用例 ID 在子进程包装前后保持一致，正式回执核对 8/8、无漏测/重复/漂移。Serial 的进程内多 isolate 回归保留。Rust 对缺失/未知 GC 声明拒绝加载，避免错误推断 collector。安装脚本校验/归档/原子替换，并拒绝活跃 worker 期间替换 SDK。

Native 常用入口使用当前类型化图的 6 项产品回归；旧 raw model/AST 的完整迁移门禁仍独立，不能用 G1 fixture 成功关闭。打包元数据改为 graph schema 32 / GRA1 encoding / GC，清理已退役的字段要求；包的二进制和 SDK 完整校验。包副本路径启动 daemon 曾收到 SIGKILL，原路径下逐字节一致的 release 产物验证通过；用户通知放行后，同一包副本执行路径的重试也已通过，未改名或替换二进制，历史失败回执保留。

默认切换的回归记录：Native manifest 5 项、默认构建门禁 6 项、安装/回滚/活跃进程保护 3 项、runtime accounting 6 项、稳定路径 3 项和升级回滚 3 项通过；Rust 完整门禁通过；G1 ABI 8 项、Serial ABI 8 项、单方法/discovery 入口及真实 G1 重建防护通过。产品图 6 项、真实 SIGSEGV 回执、打包图/错误 JDK/自动启动索引和查询通过。release 验证以原 `target/debug` 路径暂存 release 字节并自动恢复，无源码/产物/包漂移。当前规范入口保留原路径及字节校验。完整回执：`target/g1-default-final/receipt.json`；随后原执行路径重试见下表，历史 SIGKILL 失败回执未覆盖。

| 2026-10-13 原路径重试 | 结果 | 补充回执 |
|---|---|---|
| `target/g1-default-release-validation/package/bin` | 类型图 6/6（46.611 秒）、错误 JDK 1/1；包内 fixture 自动启动 daemon 的 index/calls/source 3 步通过（2.412 秒）；全部包文件 SHA 校验通过，执行前后无变化 | `target/g1-default-release-validation/retry-receipt.json` |
| `target/release` | 同一路径自动启动 daemon 的 index/calls/source 3 步通过（2.349 秒）；二进制、已安装 SDK 与包逐字节一致，执行前后无变化 | `target/g1-default-release-canonical/retry-receipt.json` |

本次只重试已被拦截的执行路径，没有重新构建、改名、替换二进制或增加 iopengw 性能测量；上述结果不替代完整迁移验收。

## 紧凑存储实施与唯一一轮验证

采用上游 schema 33 的元注解定义/来源/归属拆分，以及 1,024 项/1 MiB 上下文直接父注解缓存。进一步把调用点的整数归属设计扩展到节点引用、所有文件路径和完整外部目标身份。`graph_*` 是紧凑物理表；同名逻辑视图还原稳定字符串、原始 metadata 和 NULL。该设计属于 code-ana 的 storage schema 7，不宣称与 Anatomist 的物理数据库格式兼容。

节点 UPSERT 保留整数主键；全局路径 NULL 与空字符串分开，保留不同于源节点的边路径。外部字典身份包括 FQN/symbol/language/provider 的完整可空元组。metadata 仅共享至少 256 UTF-8 字节且重复两次的原文本，摘要碰撞逐字核对，不解析或重排 JSON。共享归属删除、状态刷新和孤立字典回收在私有候选事务内完成。源/目标/文件查询计划使用整数索引；FTS 外部内容直接引用物理节点主键。

| 唯一一轮冻结 iopengw（MAIN＋TEST，G1） | 既有正常流程 | 紧凑存储 |
|---|---:|---:|
| 图库字节 | 606,650,368 | **233,488,384（233.49 MB，-61.51%）** |
| 索引命令秒 | 234.879 | **214.278（-8.77%）** |
| 进程树峰值 RSS | 2.856 GiB | **2.699 GiB** |
| 本轮目录逻辑峰值 | 2.69 GiB | **2.35 GiB** |
| MAIN / TEST 节点 | 38,839 / 16,561 | 相同 |
| 元注解逻辑记录 | 56,957 | 相同；341 个定义、3,403 个归属来源 |
| 完整语义摘要 | 12 表 | 与原图库全部相等 |

全局路径 4,317 条、外部目标 7,182 条、共享 metadata 2,688 条。SQLite integrity、外键及 FTS 校验通过，最终无空闲页；既有 854 个 partial 文件保留。旧 schema 6 查询拒绝并提示 `index --full`，显式全量可替换旧格式 head；原文件不迁移。Java protobuf 直写、GRA1 行列、ABI 4、worker 18 和 request 9 保持接口，adapter 改为 `anatomist-4a903272-codeana-graph2` 以拒绝混装。

首次测量准备被采集器拒绝（缺少独立应用状态标记），尚未启动索引，原回执保留。补齐后只运行一次完整索引。索引发布及 12 表摘要成功，随后 `search-code` 发现逻辑视图沿用旧 `nodes.rowid`；原测量失败回执保留。修复只改 FTS 的整数主键到稳定 ID 查询，归档原测量二进制与查询源码，在同一已发布图库回放六项查询通过，图库 SHA 不变，未重跑索引。214.278 秒属于修复前的实际索引流程，不冒充修复后再次测量。

验证包括 Java 636 项组件、Rust 全部门禁（31 项存储，其中新增 5 项紧凑布局）、G1 生命周期 8/8、Native 产品 6/6（含公开 FTS、旧格式拒绝/全量重建）、真实 SIGSEGV、release 打包及图/错误 JDK/自动 daemon 三组验收。原执行路径保留，release 字节临时安装后恢复原 dev 二进制。正式 raw model/AST 迁移验收仍独立，`native_accepted` 和 `performance_accepted` 不据单轮和本轮 fixture 自动改为 true。

原测量：`target/compact-storage-upgrade-01/receipt.json`；同库查询复验：`target/compact-query-recheck-01/receipt.json`；最终汇总：`target/compact-storage-upgrade-01/implementation-receipt.json`。生成 schema 的维护门禁为 `python3 scripts/generate-compact-schema.py --check`，已接入 `just test-rust`。
