# 存储与原子发布

**先持久化不可变对象，再提交入口引用；只有 catalog 提交代表发布成功。**

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

这是本项目独立目录草案，不提供 Anatomist 存储读取或迁移。catalog/graph/annotations/report 分别版本化；跨文件没有天然事务，必须遵守下述持久化顺序。

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
