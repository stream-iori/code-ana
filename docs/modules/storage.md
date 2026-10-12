# 存储与原子发布

> 2026-09-29 实现状态：Rust 产品链路与独立 mock 已落地；具体命令、预算和首版限制见 [Rust 实现说明](../rust-first.md)。本页未验收的 Java/规模化设计仍保留为目标。

当前 catalog schema 2、annotation schema 2 使用事务升级；旧标注无锚点时保留正文并显示未绑定。GC 删除 revision 前登记 `cleanup_pending`，重启后 recover 重试；单库与整个 home 的容量验收仍待做。

**先持久化不可变对象，再提交入口引用；只有 catalog 提交代表发布成功。**

文件式源码 blob 捕获逐文件复制、sync_all、hash 校验后重命名，整批完成再同步 blobs 目录，随后才允许提交入口。成功复用已有 blob 的批次也同步目录，确保失败批次残留的重命名持久化；内存式 blob 写入保留同一复用屏障。批次失败不允许发布引用，未引用的部分文件可重试或由 GC 回收。

状态：SQLite、原子发布、失败保旧版为原文要求；不可变 generation 是 D03 建议，P0 必须验证复制和磁盘成本。

## 1. 建议布局与数据表

```text
<new-storage-home>/<repository>/<project>/
  catalog.db               入口、revision、snapshot、构建、比较、引用
  revisions/<id>/facts.db   不可变 SQLite 图
  manifests/<id>           捕获输入清单
  blobs/<content-hash>      源码/所需输入内容
  candidates/<attempt>/    未发布事实库与接收产物
  comparisons/<id>/        冻结 diff 结果和证据
  annotations.db           人工内容与绑定状态
  reports/                 内部报告及诊断附件
```

存储设计和实现逻辑参考 Anatomist Java，再由 Rust 实现；数据库文件兼容和历史数据导入不属于当前迁移。catalog/graph/annotations/report 分别版本化；跨文件没有天然事务，必须遵守下述持久化顺序。

| 逻辑表组 | 建议索引 / 用途 |
|---|---|
| source_units / declarations / external_symbols | world+path、SymbolKey 唯一、parent、kind、检索字段 |
| occurrences / call_sites / call_targets | owner+位置稳定排序、call site、target 的反向查询 |
| structural_relations / facets | kind+source、kind+target、类型化可查询字段 |
| owners / dependencies | owner 到事实、依赖到消费者、未解析查找桶 |
| diagnostics / coverage / provenance | world/scope/producer/owner/code，支持零事实 owner |
| graph_identity | Fact IR、schema、semantics、profile、source snapshot；不放入口特有 Git selector |
| catalog | revisions、snapshots、heads、build attempts、comparison results、对象引用和恢复状态 |

复杂 facet 可保留版本化 payload，但高频查询字段需结构化索引，不能把所有语义塞进无法检索的 JSON。具体 DDL 在 P0 回放验证后冻结。

## 2. 定向端口

| 端口 | 契约 |
|---|---|
| open_read(identity) | 在同一协调区内解析入口并登记引用，返回固定 ReadContext |
| lookup_symbols / read_declarations | 精确/候选匹配、声明树；稳定排序 |
| read_call_sites / read_targets / batch_adjacency | 批量正反向读取，避免每条边一次数据库往返 |
| read_coverage / read_provenance | 保留选择范围、缺口和零结果证据 |
| export_baseline | 固定 revision/profile 的事实与依赖摘要 |
| create_candidate / apply_delta | 仅操作私有未发布事实库；按 owner 删除并替换 |
| validate / publish | 返回提交身份或明确失败；不向调用者泄露部分可见结果 |

存储不推导 Java 派发、不解释健康策略、不执行 pipeline，也不暴露万能 ORM。

Rust application 可向 staging 提交 `CandidateDerivation`。存储在私有候选中按规则声明的 producer/aggregate ID 清理旧派生 owner，提供关系读取、按 ID/源码 world 签名读取、捕获单元查询和受 owner/manifest 校验的插入；规则完成后仍执行全库 dangling/coverage/完整性校验。关系含义由 application 决定。派生 aggregate 不交回语言 worker，普通 revision 导出继续保留。取消、磁盘预算、派生失败和 CAS 冲突均不能改动旧 head。

## 3. 发布协议

```text
active = r10
   |
   +--> query Q 固定 r10，登记读者引用
   |
   +--> full: 新建 candidate
        delta: SQLite backup r10 -> 私有 candidate -> 应用 delta
                    |
             完整接收 + 校验 + 门禁
                    |
             关闭写连接，处理 sidecar，校验候选
                    |
             持久化 blobs / manifest / facts.db
                    |
             移至不可变 revision 路径并保证持久化
                    |
             catalog 事务：检查 active == r10
                插入 ready revision + 更新入口 + 提交记录
                    |
                 active = r11

Q 继续读 r10；新查询固定 r11；Q 释放前 GC 不能回收 r10。
```

事实、coverage、profile、输入身份必须在同一候选内准备完成。发布事务只引用已经耐久化的产物，不能先让 catalog 指向未落盘的文件。文件与目录的同步、同文件系统移动、SQLite backup/checkpoint/关闭要求需平台故障测试验证。

只在 candidate 中写入，已发布图永不修改。不使用“复制正在写入的 SQLite 文件”代替 backup API。D03 的磁盘复制成本包括增量写放大，不能误称零拷贝。

## 4. 崩溃与升级

| 崩溃 / 故障点 | 恢复规则 |
|---|---|
| 接收未完成 | candidate 为中断状态；旧 head 不变 |
| 文件已落盘、catalog 未提交 | 是未发布孤儿对象；核对构建记录后清理，不能自动补发 |
| catalog 已提交、响应未发 | revision 已发布；按任务提交记录返回相同结果 |
| expected revision 冲突 | 候选不发布，重新规划，不覆盖后来写入 |
| blob 损坏 / 磁盘满 | 校验或持久化失败；旧版保持可读 |
| 本项目未来 graph schema 演进失败 | 保留原目录与恢复信息，不先删除已发布数据；旧 schema 读取能力另作版本声明 |
| catalog migration 失败 | 事务回滚；重大不兼容用影子 catalog 与恢复入口验证后接替 |
| 清理失败 | 登记 pending，后续恢复重试，不将已发布 revision 改报失败 |

GC 与 open_read 必须共享引用协调机制，避免“先查到路径、尚未登记读者就被删除”的窗口。daemon 重启后失效进程引用只能在确认拥有者已结束后解除；固定快照和持久比较引用不随进程退出消失。

## 5. D03 取舍与验收

| 方案 | 收益 | 成本 / 限制 |
|---|---|---|
| 建议：不可变 generation | 查询、历史、恢复共用身份；发布不干扰旧读者 | 增量可能复制整库；磁盘和写入耗时必须测量 |
| 备选：live SQLite WAL + 历史物化 | 活动增量写放大可能更低 | 长读者、WAL 回收、历史固定和发布恢复更复杂 |

不依据理论大小直接切方案。验收覆盖每个崩溃窗口、GC/读者竞争、并发 CAS、跨 schema 失败保旧版、增量/全量相等及实际库规模下的复制成本。SurrealDB 只实现同一端口参与独立实验。

## Java 分析缓存边界

公共事实和 Java facets 按 revision 保存绑定来源、证据完整性与诊断；不能只落“resolved”一个布尔值。javac 上下文对象不进入数据库。

NoCode 模块签名与声明目录属于可重建分析缓存，不是已发布事实库或目标构建产物。缓存损坏/缺失只能触发源码重算或明确失败，不得降低事实要求；在途签名 lease 必须参与缓存回收协调。存储不承担 Java 模型恢复或名称解析。


## Anatomist 存储逻辑迁入 Rust（2026-10-08）

用户明确要求将 Java 存储设计和逻辑一并参考迁入 Rust。以
[存储对照与验证](../storage-anatomist-migration.md) 为实施清单。

```text
Anatomist Java                         code-ana Rust
StagedGraphStore / StagingSchema  -->  CAF1 + 私有候选 facts.db
schema.sql / DataReader          -->  storage SQL + FactReader 定向读取
CallSitePersistence              -->  call_site / call_target 独立事实与索引
SourceBlobStore                  -->  SHA-256 blobs
AffectedScope / fact_hash        -->  Owner replacement + 稳定事实 ID
IndexFileSwap / IndexRevision    -->  不可变 revision + catalog CAS
```

采用其批量入库、定向索引、调用点与目标分离、出现次数保留、内容寻址和发布前校验逻辑。
Rust 仍使用版本化 Fact IR 保存完整事实；专用查询字段使用 SQLite 索引。
DDL 来源为 `crates/storage/src/schema.sql` 与 `query-indexes.sql`；旧索引仅在私有候选升级。
候选 schema 4 保留 source_units 和 declaration_projection，并增加 file_dependencies。事实正文仍为权威；声明投影通过 INSERT/UPDATE/DELETE 触发器与 owner 替换在同一事务更新。文件依赖按记录的内部关系端点及原始 provider/注解名称重建，来源为冻结 Anatomist DataWriter 的依赖 joins；不从源码推断。候选完成派生后在同一事务刷新，保存两端 world/path，去除自文件依赖并合并重复出现；原始事实保留全部出现次数。反向读取每批最多 500 个文件键、1024 行，ID keyset 续读。历史库不原地升级，缺此投影返回 FILE_DEPENDENCIES_UNAVAILABLE，由 Java 决定保守回退。导入、派生及完整性校验后，发布前逐索引 ANALYZE；每个索引前后检查取消，I/O、磁盘满和损坏错误终止发布，普通统计错误记入警告。
冻结查询结果放在 pages/<page-id>.db，catalog 仅保存结果头、总数、参数身份和 revision 引用。SQLite 页记录按 sequence 索引读取；私有 spool 在失败/取消时释放，崩溃遗留由 recover 清理，过期页及孤儿页库由 GC 回收。
全量先流式入库，再建立查询索引并派生；增量复制现有索引，重复准备不重建。
发布目录、历史读者和失败保旧版继续使用本项目已有的不可变 revision 契约。
冻结参考 JAR 的模型解码上限与存储设计迁移分开记录，不能据此跳过存储验证。


## 全量批量构建与性能计时（2026-10-11）

全量候选是新库：事实初始写入时暂停声明投影触发器，并延后 owner/source 及声明选择索引；归档全部校验、入库后批量生成声明投影、恢复全部索引和触发器，然后准备查询/FTS，随后才允许 Wiring 派生。派生新增声明仍由触发器维护。增量备份库继续使用原触发器和 owner 替换路径。表结构与 `user_version=4` 不变。

CAF1 的任务自有归档由 builder 生成规范 JSON。存储的记录迭代器仍解析 Fact、检查帧预算/尾部/摘要，并执行 FactValidator；SQL body 复用同一记录的 JSON，省去重新编码。owner 键缓存限制为 1024 项且计入字符串与保守对象开销的容量不超过 1 MiB。磁盘身份去重对新 ID 先执行 INSERT OR IGNORE，仅重复时读取首次摘要/偏移；冲突证据和批次提交语义保留。

任务报告新增 `summary.timing.storage_phase_ms`：候选准备/备份、归档校验与入库、投影/查询索引/FTS、Wiring、文件依赖与提交、全图校验和摘要、完整性检查、统计。计时不是 Fact IR；已发布事实不包含性能字段。完整图与性能验收状态见 [单轮优化计划](../../todo/chains/performance.md)。
