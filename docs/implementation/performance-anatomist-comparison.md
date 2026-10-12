# 性能改造与 Anatomist 设计对照

2026-10-12 最新分段实测及下一步方案见 [Java Native 性能分析](native-performance-analysis.md)。JVM 冷/热 681.276/649.956 秒，Native 995.621/970.591 秒，完整图一致；Java 内部解释约 98% 的后端差距。Native GC 约 180 秒、归档 SHA 约 25 秒；FFI 轮询 CPU 仅约 0.24 秒，protobuf 编码约 0.38 秒。当前事实交付已有异步分块，但 Java 封存和完整校验前有全量屏障。

本机 Oracle `25.3.4+1.r25-graal` 平台源码和静态库支持 macOS/aarch64 G1，Anatomist 的同版 SDKMAN/macOS profile 默认 G1。项目配置已按用户要求切到该 Oracle 版本；本轮测量仍为 Community/Serial，不重标旧产物。后续先做同 Oracle 编译器的 Serial/G1 对照，再定位 typed 数据、分配、SHA 和 SQLite 的成本；G1 性能及 Native 减半仍未通过。

以下为 2026-10-11 历史对照，详细旧回执保持原样。

2026-10-11。依据本机 `../anatomist` 源码和本项目同源码 release 单轮结果。参考设计不替代 Fact IR、gRPC 或不可变版本契约。

| 边界 | Anatomist 证据 | code-ana 现状与动作 |
|---|---|---|
| 查询上下文 | `anatomist-app/.../cli/SemanticExecutionContext.java`：一个流水线持有同一读锁、SQLite 连接、语义身份 | `Store.read` 已生成一个 ReadContext，查询共用；没有证据支持新增连接池或修改锁边界 |
| 输出与任务存储 | CLI 在同步语义帧内累积 seeds/emitted/complete/truncated | 本项目需持久化任务事件。已将一次已渲染输出的 16 KiB 事件块合并为一个 FULL 事务，保留每块事件和序号、断点续读、终态边界；失败全部回滚 |
| staging | `StagedGraphStore` / `StagingSchema`：原始事实落盘，随后准备身份和引用 | 全量批量入库/投影/索引已实现；增量保留 owner 替换。不能照搬其 nodes/edges schema，Fact IR 还承载其他语言与事实种类 |
| 身份准备 | `FactIdentityPreparer`：2,048 条分页、预编译语句、批量更新，再建事实索引 | worker 归档校验去重交付仍约 122 秒，应分开量化解析、规范化、去重 SQLite 和交付；已有事实摘要、冲突检查和 EOF 校验均保留 |
| 原子发布 | `IndexFileSwap`：checkpoint，完成替换，失败恢复旧库及 sidecar | 保留不可变 revision、catalog 原子提交、旧 head 可读和崩溃恢复；不删 integrity_check 或降低持久性换速度 |
| JSON 编码 | `json/Json.java`：紧凑写入直接使用 StringBuilder，普通 maps 保留插入顺序；canonical 单独排序 | 本项目保留规范自然键序。隔离 JVM 实验中 StringBuilder 静态重载慢 5.1%，500 个随机样本和预算回归通过但未采纳；没有 Native 收益证明 |
| 历史文本查询 | `SemanticExecutionContext` 固定数据库和语义身份 | 已发现并修复历史 search-text 的多余当前工作区捕获；旧应用在当前配置损坏时失败，新回归要求 revision、snapshot 查询保持冻结结果，工作区查询仍校验配置 |
| 源码 blob | `SourceBlobStore` 校验 hash 后临时文件原子移动；未见显式文件/目录 fsync | Source06 改为每个文件仍 sync_all/hash 校验，整批结束同步目录。成功复用也执行目录屏障，覆盖失败批次残留；入口仍最后提交 |
| 事件观察 | 本项目 daemon `watch_task` 每 30 ms 轮询 SQLite | 有可确定的轮询延迟与额外连接开销；若进一步改造，采用提交后通知 + 先订阅后读 + 定时兜底，保留多个 watcher 和重连语义。尚未实施，不能断言是全部查询回退根因 |

## 本轮瓶颈与差距

| Native 阶段 | 冷秒 | 热秒 |
|---|---:|---:|
| Java 总分析 | 589.4 | 599.6 |
| 其中 unit_projection（包含于 Java） | 356.9 | 363.0 |
| Rust 归档校验去重和交付 | 122.5 | 123.6 |
| 存储、索引与校验 | 184.9 | 205.1 |
| 发布 | 66.0 | 9.6 |
| 正式 CLI 全索引 | 976.6 | 953.6 |
| 减半门槛 | 679.7 | 618.9 |
| 仍需减少 | 296.9 | 334.7 |

Java 投影改造较上一候选的冷投影 416.3 秒缩短 14.3%，但完整 Native 仍未减半。输入解码、Java 堆、worker 内存、结果和磁盘预算均未增加。四阶段完整图为 2,447,690 条且 SHA 相同。

CLI/store 输出批量提交的 SQLite 诊断使用真实 structure 输出 95,373 字节/6 块，30 次输出耗时 0.330→0.081 秒、180 条事件内容和序号摘要相同。范围仅为 Python/SQLite 诊断，约每次节省 8 ms；不能当作产品查询收益或把它解释为全部回退原因。

输出批量事务 Source04 已通过完整正确性聚合；7 项查询诊断中 6 项回到 5% 内，Native 热 search-text 仍回退 5.7%。全部冻结结果一致，原正式失败回执保留。后续优先修复历史查询捕获，再细分 122 秒 Rust 归档交付和 Native 投影，测冷发布的同步成本。不同语义的普通紧凑 JSON、短哈希、同步 CLI 输出不能直接替换本项目契约。

Source05 历史捕获修复的 Rust 工作区 169 项、CLI 18 项及 clippy 通过。查询诊断中 JVM 冷 search-text 0.963→0.378 秒、Native 热 1.047→0.375 秒，冻结结果一致；但 JVM 冷 structure 0.354→0.402 秒，回退 13.4%，不能签整体查询验收。原正式失败保持不变。

本机 Rust 标准库 `std/src/sys/fs/unix.rs` 的 Apple `File::fsync` 使用 `fcntl(F_FULLFSYNC)`，Python 普通 fsync 的第一组诊断不能代表产品。按相同强度重新测 5,306 个真实 blob（727,355,990 字节）：逐文件目录同步 58.681 秒、整批目录同步 34.513 秒，省 41.2%，两版全部内容 SHA 相同。范围仅为单对文件系统诊断，系统缓存未控制；Source06 实际 Rust 回放另验。

Source06 实际 Rust 存储单轮已通过：相对上一存储候选，blob 64.054→34.680 秒，省 45.9%；导出 36.594→35.871、入库校验 136.508→142.365、发布 3.083→3.125。四项合计 240.239→216.042 秒，省 10.1%。完整图 2,447,690 条/ba01dbbe…一致，原数据库 SHA 不变、无输入漂移和并发分析。Rust 170 项、CLI 18 项、clippy 均通过。该回放不含 Java，Source06 完整 Native 性能尚未测量。

## 剩余改造方向

| 优先级 | 证据缺口 / 动作 | 验证边界 |
|---|---|---|
| 1 | unit_projection 把 `graph.process`、`view.add`、`projection.unit` 合在 357 秒内，先拆计时；再决定 typed 数据或规范编码布局 | 有占比证据后修改生产者；完整字节/模型/预算门禁，新版本只跑一轮，不能由 JVM 微基准推断 Native |
| 2 | Rust 归档验证/去重与交付约 123 秒，拆解 parse、normalize、SQLite 身份检查和 protobuf 编码；参考分页与批量准备，减少重复解析和分配 | 保留冲突首记录、EOF、原始及规范摘要、64 条/字节双重分块预算 |
| 3 | 查询约 0.4 秒而 watcher 每 30 ms 轮询，单次抖动可超过 5% 阈值；按提交后通知改造，先订阅再读，保留定时兜底 | 通知不替代 SQLite；多个 watcher、重连、重启、游标顺序和终态仍以持久事件为准；不宣称已经解释全部回退 |

冷 blob 诊断约省 24 秒，热缓存收益尚未证明；Native 减半仍缺冷约 297、热约 335 秒，因此不能把 store 改造当作总目标完成。

证据：`target/implementation-evidence/performance-projection-single-01/receipt.json`、`performance-projection-comparison-01.json`、`performance-projection-stage-analysis-01.json`、`performance-output-event-batch-diagnostic-01/receipt.json`。原测量、失败回执保留；本次不宣称整体性能验收通过。
