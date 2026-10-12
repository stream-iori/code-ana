# 增量：基线 → 失效 → 重解析 → owner 替换 → 发布

[入口](../README.md) · [共用契约](../contracts.md) · [追溯](../traceability.md)

```text
固定 baseline/profile + 新捕获输入
  → Java 决定语义失效 / 安全回退
  → 旧事实视图 + 本轮解析/框架
  → owner/producer 替换 + 派生/投影/反向依赖更新
  → 发布 → ack → 基线/缓存提交
```

**先以固定事实验证存储替换和读取；Java 从保守上下文失效起步，再做等价性证明。**

<a id="inc-01"></a>

## INC-01 反向依赖端口与 owner 原子替换

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：owner 事务替换、文件/符号反向依赖及 unresolved 消费者批读已接真实 SQLite；Java 失效集合的文件端口联调未结束 |
| 行为 | 给定失效 owner 后，旧事实/投影/依赖准确替换，基线可按需导出。 |
| 调用链 | 固定依赖图 → baseline export / 反向索引 → candidate owner/world/producer 替换 → 完整校验。 |
| 开发前置 | IDX-01 |
| 联调前置 | 无；本任务独立组件验证 |
| 实施 | 记录文件/符号依赖及 unresolved 消费者，增加批量反向查询/定向读取端口；Java 负责选失效集合，Rust 只按给定关系查询和写入。替换覆盖事实、FTS、投影、依赖，producer 关闭/文件删除允许显式空输出；缺依赖证据明确回退上下文/full。 |
| RED / 基线 | 手工循环依赖、MAIN/TEST、删除/重命名、kind 改变、未解析消费者和关闭 producer；先复现反向读缺项与替换残留；变小 syntax/type 分片不得留旧片。 |
| 完成条件 | 真实 SQLite 独立通过，给定依赖精确读写，baseline 导出保留来源/版本/证据。已有 catalog CAS、旧 revision、pin/GC 测试复用，不新增本机环境测试。 |
| 追溯 | S3/S5；旧 02 反向依赖；03 基线/增量 |

<a id="inc-02"></a>

## INC-02 复制失效和缓存策略，接通基线确认

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：共享 JVM 基线/cancel/ack；固定缓存键字段顺序后，跨进程复用/损坏修复回归通过 |
| 行为 | 冷增量/缓存路径与相同输入的全量一致；不安全复用明确回退。 |
| 调用链 | baseline/profile → 原 IncrementalIndexer/Javac*Cache → ProjectAnalysisRunner/FactView → 重解析/框架 → 发布 ack。 |
| 开发前置 | JVM-03、FW-02 |
| 联调前置 | INC-01、IDX-02 |
| 实施 | 复制上游失效/缓存算法；先按 module/scope 保守上下文失效，源码/依赖/JDK/插件/配置/profile/格式参与判断。旧基线与新声明/类型/Bean 合并，关闭 producer 显式删除。Rust 管持久缓存/校验/生命周期，Java 决定语义 key/任务内复用；ack 后才提交基线，桥不重写失效算法。 |
| RED / 基线 | 方法体/签名/导入/依赖变化、源码 hash 漂移、缓存损坏/格式变化/插件变化、锁等待取消、未 ack 重启、JDK/配置变更；复制原用例并用独立 full 预期。环境问题记 blocked，新增桥契约才 RED。 |
| 完成条件 | 不缺证据复用或跨任务污染，缓存可用性不改语义；失败/取消不提交基线，producer 停用不残留。冷增量和可选缓存路径分开回执，无法证明安全则 full。 |
| 追溯 | A2/A4/A9；S5；旧 01 增量；03 冷增量/基线 |

<a id="inc-03"></a>

## INC-03 真实 full/delta 与历史读取回归

| 项目 | 落地要求 |
|---|---|
| 状态 | 实施中：既有 full/delta 回归执行中；历史对完整覆盖未结束 |
| 行为 | 相同目标输入/profile 的 full/delta 模型、事实、查询一致，固定旧 revision 可读。 |
| 调用链 | fixture 基线/目标对 → JVM full/delta → Rust 发布 → 全页查询/历史源码 → 重启。 |
| 开发前置 | INC-02、FW-03、QRY-02、QRY-05 |
| 联调前置 | 无；按 JVM 完整场景验证 |
| 实施 | 将既有场景改成后端共用：新增/修改/删除/重命名/未解析转已解析、XML 修改删除、Lombok 配置、producer 停用、跨 MAIN/TEST；记录旧 revision 捕获 hash。读者/分页/快照/GC 回归复用，受本次行为影响才加 RED。 |
| RED / 基线 | 迁移缺口先最小 fixture；模型/事实用独立规范化多重集，查询读全部页。复用 CAS 冲突、候选校验/schema 失败、提交前后崩溃和孤儿恢复，补实际变化窗口。 |
| 完成条件 | full/delta 内容与次数相等，行数相同不够；提交前失败 head 不变，提交后响应/ack 失败不倒退。旧读者/快照/分页不漂移，有效引用不被 GC 删除；这是既有产品回归，不是磁盘节约 TDD。 |
| 追溯 | A2/A9；S5/S6；旧 03 §3；I6/I7/Q10 |

真实工程历史变更对统一见 [ACC-03](../acceptance.md#acc-03)，不编辑 iopengw 基准；native 复用本链场景与预期。

2026-10-10：FactReader 增加 reverse_dependencies / unresolved_consumers，每批最多 500 个键、1024 行，按固定 revision 和 ID keyset 读取；CancelableReader 转发并检查取消。实际 SQLite 100003 个调用点超过全表 select 预算时，定向批读仍准确返回 83 行，EXPLAIN 使用 unresolved partial index 且无临时排序。循环依赖、MAIN/TEST、重复关系、删除后的 owner 原子替换、固定旧 revision 回归通过；源码 `crates/model/src/lib.rs`、`crates/storage/src/lib.rs`，证据 `target/implementation-evidence/reverse-consumers-green-02.log`、`reverse-owner-green-03.log`、`rust-workspace-06.log`。Java 仍负责失效集合，完整文件依赖/基线输出联调未验收。

显式 JVM 的 semantic_identity 现在加入宿主 JDK 25 的 bin/lib/release 内容摘要，并在提交前校验捕获字节；worker 与 daemon 共用宿主路径选择。`jvm-host-profile-01.log` 证明路径迁移身份相等，启动器/模块/动态库/release 变化失效；`rust-clippy-09.log` 通过，完整 CLI 增量复验待完成。

文件依赖 `file-dependencies-red-01.log` 复现缺表；首次 GREEN 编译失败保留，`file-dependencies-green-02/03.log` 共三项通过。候选 schema 4 在 owner 替换/派生的同一事务重建，依赖投影合并重复边，原事实不合并；覆盖注解/元注解 provider、循环、自文件排除、MAIN/TEST、跨 world 端点、删除、固定旧 revision 和分页。100003 条投影的反向读取准确返回 83 行，EXPLAIN 使用 file_dependencies_reverse。此证据只证明存储端口，不宣称 Java 已使用精细文件失效算法。
