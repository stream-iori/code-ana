# Anatomist 存储迁移

实施记录，2026-10-13。参考 `../anatomist` 的 `dev/replace-parser`，固定提交 `dff2e3954096853352ec91fcbfc6b995fbd4f390`。保留 Rust catalog、源码 blob、不可变 revision、snapshot/diff 和人工标注；语义图、索引和 staging 迁入 Rust。

```text
javac 8 → Anatomist 原始记录 → Java 25 / Native C ABI 4
                                  │ GRA1 + protobuf Row
                                  ▼
Rust worker → 有界流 / 校验 End → Rust staging → Anatomist schema 32
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
| 版本 | graph model 2 / worker 18 / request 9 / C ABI 4 / daemon 3；product user_version 6 / Anatomist schema 32；旧图须全量重建 |

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

该比较包含测量后 overview、增量继承摘要和本次 worker 防护等版本差异，不能把全部变化归因于 GC。单轮没有显示 G1 的性能收益，构建默认仍为 Serial；当前产品测试安装保留 G1 供继续验证。测量命令使用原 `target/debug/codeana-*` 路径，release 构建 SHA 逐文件绑定；旧 dev 二进制及 Serial 库分别归档，没有改名执行。完整结果见 `target/g1-build-diagnostic/regression-receipt.json` 与 `iopengw-single-01/phase/receipt.json`。
