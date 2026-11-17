# 公共图交付

```text
Java JSON envelope → Java codec ─→ read_descriptor → 原始 GRA1 EOF/count/SHA
                                                   │ provisional Row
Rust/Python 提取器 ──────────────────────────────────┤
                                                   ▼
                        公共 Encoder → 有界队列 → worker IPC
                                                       │
                        runtime Receiver / Verifier ← Chunk / End + 核实用量
                              │
                         VerifiedEnd → 候选校验 → 发布
```

| 模块 | 职责 |
|---|---|
| contracts::graph_delivery | 纯 Descriptor/OutputLimits；Java 配置解释使用 java::output_limits |
| graph-io::archive | GRA1 基础读写、临时归档所有权、逐行 wire 准入与校验 |
| graph-io::reader | 指定任务根内的归档准入、原字节 count/size/SHA/EOF 校验及成功消费 |
| graph-io::delivery | 公共 Encoder/Verifier/Projection；失败为粘性状态，错误的 chunks/count/SHA 不提供 VerifiedEnd |
| graph-io::stream | 4 个消息槽和 8 MiB 解码占用配额；Begin owners、Row/Cell/String 容量与终态元数据计入队列，超大合法消息独占 |
| graph-io::scope | 按来源列契约校验单元 source/world/module/scope/语言及 replacement owner；唯一来源 world 解析；原传输和主机投影使用独立 SHA |
| graph-io::producer | Rust/Python 的阻塞提取入口，复用同一个 Encoder |
| graph-io::records | 主机分配的 Unit/Host/Fixture 来源；逐个读取原主机归档，保留来源、原 count/SHA 和输入租约，不产生二次合并归档 |

普通批次最多 64 行、目标 256 KiB；单条较大合法行可以独占一批，但仍受帧上限与输出总量限制。队列租约保留到消息处理完成；消费端关闭会唤醒生产端，不能靠扩大队列掩盖输出回压。

归档路径必须是所给根的绝对后代路径，限制 16 KiB/64 层。读取使用根目录 FD 和逐级 openat，禁止内部 symlink/父级穿越；普通文件、声明长度、魔数、逐行准入、count、尾随字节与原始 SHA 全部核实后才返回 VerifiedArchive。仅成功的 consume 删除原文件；坏 SHA、截断、取消或消费错误均保留输入。原始 protobuf 顺序与规范重编码不同也必须按原字节验证。

Begin/Batch 均为临时数据。VerifiedEnd 由公共编码或验证路径生成，字段在 crate 外不能构造；daemon 另核对实例/attempt、合法阶段及最终用量，随后才向候选发送证明。行队列作为 iterator 消费时拒绝意外 Begin、提前关闭和 count 不符。

SDK 装载、IPC 会话状态、进程监督和数据库发布不属于本 crate。Java 13 JSON 回执/owner/envelope 解码仍在 Java worker；公共会话、接收状态与 FFI 交账机制见 [worker 运行设施](worker-runtime.md)。阶段验收范围见当前实现，不能将单个子阶段当 R2—R5 或 TS 产品放行。

主机 `Record` 的来源不在 worker protobuf 中：真实 worker 的来源来自已验证 Discover 单元，mock 仅显式 Fixture，准备说明和缺口 coverage 标为 Host。应用存储端口保留该来源，存储入口按 revision 中 Ready 单元的完整 inputs 再验一次来源；Host 不允许写语义节点或边。存储已据此持久化 diagnostics、coverage、providers 和 project_meta 的单元归属；无来源节点等剩余字典与任意单元混合 SQL 合并仍待完成。

混合、Python 和 prepared 结果依次读取原主机 spool，每次只打开一个文件。每份归档单独验证声明长度、count、原 SHA 和 EOF，错误后不进入后续单元或 Host 尾部；读取器保有临时目录租约，解码与读缓冲从原 host 账户预付。总输出额度保持一个 GRA1 魔数加所有帧和 Host 尾部的原上限。`records::POLICY_VERSION=5` 纳入项目及单元语义 profile；旧策略第一次更新重新分析，公开业务 ID、worker/ABI 与 GRA1 不因此变化；当前物理 schema 17 的逐单元 metadata、节点归属及引用边界也由该策略身份覆盖。

主机按单元视图验证原始 Chunk，再把能唯一定位的空 world 补入存储投影。原始 GRA1/IPC 的 count/SHA/EOF 仍独立核实；Projection 逐行单独计费、计算投影 SHA，原始 End 成功且投影的行数/块数一致才封存。范围歧义、原始 SHA 错误、投影超限或缺行均无证明，不能用投影 SHA 代替原始 SHA。同路径多 world 的文件缓存等无 module/scope 行必须显式携带 world，不做任意选择。

重复边与注解的 hash/ordinal 先按稳定 backend/unit ID 排序，再保留单元内出现次序。混合暂存中 Reuse 先载入，不得改变未变输入的 occurrence 归属或派生元注解 ID；私有 unit_pk、插入次序不参与业务排序。
