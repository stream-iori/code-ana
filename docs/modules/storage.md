# 存储与原子发布

对齐 2026-10-20：图库物理 `user_version=11`、Anatomist 逻辑 schema 34；catalog 为 2。实现见 [graph_store.rs](../../crates/storage/src/graph_store.rs) 和 [graph_compact.rs](../../crates/storage/src/graph_compact.rs)，测量及验证记录见 [当前实现](../current-state.md)。

Rust 存储按 blob、task、object、maintenance、page、graph 分模块，共享协调状态、读者引用、候选封存及 CAS 事务。对象使用明确 DTO；分页接收 `Record`，内部编码及旧页兼容见 [公共契约](../contracts.md)。

## 1. 布局

```text
<user-home>/                     默认 ~/.code-ana
  catalog.db                    project / checkout / target / refs / tasks / objects
  revisions/<id>/facts.db        不可变语义图
  candidates/                   私有未发布候选
  blobs/<content-hash>           捕获源码 / 输入内容
  pages/                        冻结查询页
  reports/                      报告附件
  cache/java-signatures/        可重建的有界 NoCode 缓存
```

home 共享 catalog，以身份隔离项目；不是每仓库一套 catalog。manifest、snapshot、diff、报告等对象及引用由 catalog 保存。catalog 使用事务升级。

## 2. 物理布局与逻辑读取

| 物理表 / 对象 | 逻辑入口 | 压缩与不变量 |
|---|---|---|
| graph_nodes | nodes | 公开 id 不变；整数 node_pk 支撑关系、注解和 FTS；UPSERT 保留未变节点主键 |
| graph_edges | edges | 节点引用为整数；保留 fact_hash / fact_ordinal，边 source_file 独立保存 |
| graph_call_site_owners / graph_call_sites / graph_call_site_targets | call_site_owners / call_sites / call_site_targets | 调用点与目标分离；不额外展开方法 CALLS 边 |
| graph_annotations | annotations | Java 源码注解；引用节点和路径字典 |
| annotation_meta_relations / annotation_meta_sources / annotation_meta_owners | annotation_meta_owned | 定义复用、归属独立，owners 为 WITHOUT ROWID |
| graph_file_cache / graph_file_dependencies / graph_symbol_dependencies | file_cache / file_dependencies / symbol_dependencies | 源文件字典 + 增量契约/依赖 |
| graph_index_diagnostics / graph_analysis_coverage | index_diagnostics / analysis_coverage | 零事实文件与 partial 缺口仍保留 |
| index_providers / project_meta | 同名表 | provider 身份、逻辑 schema 与计数 |
| graph_rust_source_states / graph_rust_analysis_dependencies | rust_source_states / rust_analysis_dependencies | world/key 字典与整数引用，WITHOUT ROWID；依赖只保存一次，baseline 批量还原 |
| source_files | 路径逻辑列 | 全局复用路径，NULL 与空字符串分开 |
| external_targets | 外部目标逻辑列 | 四 nullable 字段完整身份，NULL/空不能混并 |
| metadata_values | metadata 逻辑列 | 仅原始 UTF-8 ≥256 bytes 且重复至少两次共享 |
| node_names | FTS5 | external-content 为 graph_nodes，以 node_pk 连接；4096 字节页 |

逻辑视图负责恢复字符串列，私有候选的写触发器负责翻译整数引用。查询只读逻辑投影，解码稳定属性时不输出 `site_pk`、`owner_pk` 等物理列。`facts(body)`、整条 Fact JSON、Java raw type/binding/syntax 表均不在产品图库中。schema 11 是 code-ana 的物理格式，不承诺与 Anatomist 物理数据库兼容。

DDL 生成入口：[generate-compact-schema.py](../../scripts/generate-compact-schema.py) 从 [anatomist-schema.sql](../../crates/storage/src/anatomist-schema.sql) 和 [compact-meta.sql](../../crates/storage/src/compact-meta.sql) 生成 [compact-schema.sql](../../crates/storage/src/compact-schema.sql)。修改生成输入并运行生成器，不手改生成文件；`--check` 已纳入 `just test-rust`。

metadata 共享保持原始文本，不做 JSON canonicalization。SHA-256 只是候选索引，完整字节相等才合并；同 SHA 不同内容不合并。分组使用临时 SQLite，每次处理一个值，不把全库文本装入 Rust map。仅 nodes/edges/call_sites 的合格重复值共享。

元注解保留各来源首次位置、各归属 resolution_status 和定义聚合状态。全局路径字典保留 NULL/空；元注解 identity_file 仍遵守上游无文件身份规则。删除/停用 producer 时清理对应归属，封存时删除孤儿定义。

## 3. 导入与增量

| 阶段 | 行为 |
|---|---|
| 接收 | GRA1 行长度/列数/类型/源路径/module/scope/序号受限；End 前只写私有候选 |
| staging | 原逻辑列暂存，解析引用、UTF-16 长度规则 hash、重复 ordinal、调用点目标和派生 WIRES |
| full | candidate 使用私有导入设置，保留唯一约束，暂停非唯一索引/FTS 触发器，导入后重建 |
| delta | SQLite backup 固定兼容 baseline；按替换归属删除/合并，重映射暂存整数引用，保留未变 hash/ordinal |
| 封存 | 调用点/派生关系/依赖更新，字典共享及孤儿清理，索引/FTS/统计重建 |
| 校验 | 完整归档与 End、引用、coverage、SQLite integrity、foreign_key_check 和 FTS |

candidate 的快速导入设置不能推广到 catalog 或已发布图。最终使用新的发布连接 WAL/NORMAL、checkpoint/关闭和持久化屏障；完整校验没有移除。

## 4. 发布

```text
active r10 ──► reader Q 固定 r10，引用保护
     │
     └─► full 新 candidate / delta backup(r10)
           → 完整交付 + 校验 + 门禁
           → blobs / manifest / facts.db 同步
           → 不可变 revision 路径 + 目录同步
           → catalog 事务检查 expected head == r10
           → ready revision + head + commit 记录
           → active r11

Q 继续读 r10；新读者取 r11；Q 释放前 GC 不删 r10。
```

blob 批次使用共享上限 2 的文件 I/O，复制时计算 hash、同步和重命名，整批同步 blobs 目录；复用已有 blob 同样保持目录同步屏障。只在私有 candidate 写，已发布图永不修改。delta 使用 SQLite backup API，不能复制正在写入的数据库文件冒充一致快照。

## 5. 失败、升级与回收

| 情况 | 处理 |
|---|---|
| 缺块/错摘要/取消/磁盘满/worker 崩溃 | 候选不发布，旧 head 保留 |
| 文件落盘但 catalog 未提交 | 未发布孤儿，核对归属再清理，不自动补发 |
| catalog 提交后响应/ack/报告失败 | 已发布事实保留，以 commit 记录裁决 |
| CAS 冲突 | 不覆盖新 head；当前候选不发布 |
| graph schema 6 或其他不兼容格式 | 查询/delta 返回 GRAPH_SCHEMA_REBUILD_REQUIRED；index --full 建新 revision 后切 head，旧文件保留 |
| 旧格式历史 | 文件保留不等于新 reader 支持；显式图读取同样拒绝旧格式 |
| catalog migration 失败 | 事务回滚 |
| GC | 受活动入口、snapshot、reader、build、有效结果引用保护；删除失败登记 cleanup_pending，recover 重试 |

当前 GC 字节预算只计 facts.db，不是整个 home 硬配额。backup 和不可变 revision 会占额外磁盘；233.49 MB 是单个最终图库，不能等同分析时峰值或整个 home。单轮结果和边界见 [当前实现](../current-state.md)。

活动 blob pin 使用引用计数，覆盖复制、校验到提交/回滚；并发任务共享同 hash 时不会互相解除保护，GC 同时识别活动 pin 和原有暂存保护。大文件复制、发布前流式 hash、facts.db 同步在协调锁外完成；锁内保留最终 head 检查、rename/目录同步及 catalog CAS。故障恢复和持久化屏障保持。
