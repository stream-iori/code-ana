# 公共契约：身份、图记录与协议

现行契约对齐 2026-10-14。版本总表见 [当前实现](current-state.md)；过期 Fact IR / CAF1 契约已删除。

## 1. 身份与版本

| 身份 | 规则 |
|---|---|
| project / checkout / index target | 用户级 daemon 内隔离；查询一次固定 revision |
| module / scope / world | 源码字节相同不等于语义相同；MAIN、TEST 和构建上下文不能混并 |
| 公开节点 ID / symbol / fact hash | 按原逻辑字段构造；查询投影仍用 Fact 形状，不保存完整 Fact JSON |
| node_pk / file_pk / external_pk / value_pk | 仅当前图库内部使用，不能进入公开身份或跨库复用 |
| 重复事实 | hash + ordinal 保留重复分布；相同位置或文本不能擅自丢掉出现次数 |
| profile / 输入 digest | 包含实际工具链、依赖顺序、adapter/模型/规则及语义配置；执行预算另记录 |
| replacement owners | 源文件/资源/producer 的替换范围包含删除和零输出，避免留下旧事实 |

当前 graph model 2、worker 18、analysis request/result 9、C ABI 4、daemon handshake 3；javac-model-v50、compiler batch 2。图库物理 user_version 7，上游逻辑 schema 33、GraphSemantics 26，adapter `anatomist-4a903272-codeana-graph2`。catalog/annotations 均为 2，彼此独立。

manifest 仍沿用 `fact_ir` 键，daemon 仍声明历史能力标签 `fact-ir-v1`；这些名字不表示产品继续传输旧 Fact JSON。混装 bundle/worker/ABI 在分析前拒绝。

内部 Rust 状态、操作、端点和错误码使用枚举，protobuf 与 JSON 边界显式映射；这些类型不改变线上字段拼写。新冻结记录包含内部类型标签，文本 renderer 保持原公共形状；旧记录只作为呈现数据读取。未知 producer 扩展透传，稳定语义字段类型错误明确拒绝。见 [Rust 改造](implementation/rust-type-refactor.md)。

## 2. GRA1 图记录

源码：[graph.proto](../proto/codeana/v1/graph.proto)、[graph_archive.rs](../crates/contracts/src/graph_archive.rs)、[AnatomistRows.java](../lang-java-adapotr/java-bridge/src/main/java/dev/codeana/adapter/AnatomistRows.java)。

```text
GRA1 | u32be(record_length) | protobuf Row | ... | EOF
                 SHA-256 覆盖魔数、每条长度与原始 protobuf 字节
```

| 部分 | 约束 |
|---|---|
| Row | 固定 Table 枚举与逻辑列顺序，列类型由行契约校验 |
| Cell | text / sint64 / bytes；unset 表示 SQL NULL，空 text 与 NULL 分开 |
| 编码 | text 为 UTF-8；metadata/attributes 保留原始 JSON 文本，不是整条实体 JSON |
| 单记录 / IPC 帧 | 非空，最多 8 MiB；行最多 34 列，解码前做 wire 准入 |
| 摘要 | 校验原归档字节和顺序，不对 JSON 重排或重新编码后求摘要 |
| 缺块 / 截断 / 错序 / 错摘要 | 任务失败，候选不发布 |

表族为 NODE、EDGE、DECLARATION、ANNOTATION、ANNOTATION_META、SEMANTIC_ANNOTATION、UNRESOLVED_CALL、FILE_CACHE、FILE_DEPENDENCY、SYMBOL_DEPENDENCY、DIAGNOSTIC、COVERAGE、PROVIDER、PROJECT_META。数据库物理布局可变，GRA1 逻辑列不携带紧凑库整数主键。

## 3. CLI-daemon 与 worker

| 边界 | 当前实现 |
|---|---|
| CLI-daemon | HTTP/2 + protobuf gRPC；本机 loopback、token 校验和用户 home 单实例锁 |
| 任务 | SubmitTask / WatchTask / GetTask / CancelTask；传输失败与执行/发布终态分开 |
| 幂等 | 同 request ID + 同参数返回原任务；同 ID 改参数拒绝 |
| daemon-worker | 独立 FD 上的 u32be 长度前缀 protobuf Envelope；stdout/stderr 不承载协议 |
| 握手 | Hello 核对 worker/model/producer；Discover 返回上下文或 BuildAdvice |
| 分析 | Analyze → Begin → 连续 Chunk(seq, rows) → End(chunks,count,checksum) |
| 重规划 | Replan / Failure 终止当前 attempt 的交付；不发布半图 |
| 确认 | daemon 成功提交后发送 PublishAck，worker 回 Acked；确认失败披露 warning、实例不复用，已发布 head 保留 |

Begin/Chunk 均为 provisional。worker 核对归档长度、EOF、SHA 与预算后才发 End；daemon 独立核对序号、行数、摘要、来源/类型/引用、coverage 和门禁后才能完成候选。结果大小、行数、scratch 预算见 [配置](configuration.md)。

持久 `FrameReader` 保存控制帧前缀/正文进度；异步读取被 select 中断不能丢半帧。控制取消优先处理，有界队列和背压限制待消费记录。

## 4. Native 完成通知与 G1

| 边界 | 不变量 |
|---|---|
| FFI 所有权 | 固定 OS 线程创建、调用、关闭 isolate；线程 handle 不跨线程；Tokio 通过有界队列请求操作 |
| 入口 | session_create / input_chunk / seal_input / analyze_start / set_completion_fd / poll_event / read_result_chunk / cancel / publish_ack / session_close，统一 `codeana_java_` 前缀 |
| 数据 | 固定宽度数值、长度明确的字节缓冲和 handle；不跨 ABI 传 Java 对象，无 JNI |
| 完成 FD | start 前注册非阻塞 Unix socket；库复制 CLOEXEC FD，finally 先发布终态，再写 `0x01` 并关闭 |
| 等待 | Rust 异步等可读通知，然后读取一次终态；不是周期 poll。EOF、异常字节或非终态拒绝 |
| Darwin ARM64 | fcntl 的可变参数由固定签名 C 包装适配 |
| 取消 / 致命失败 | 协作取消与等待退出共用 2 秒期限，超时 supervisor 回收所属进程组 |
| G1 | 每进程只能创建一次 isolate，teardown 后也不重建；健康任务复用，失败实例退出后新进程恢复 |
| 防护 | manifest 必须声明 G1/serial；进程级历史在二次 G1 创建前返回 `G1_ISOLATE_PROCESS_RESTART_REQUIRED` |

G1 的 heap 不是 RSS 上限。预算按同一时刻 host/原生库与 javac 子进程树计算；目标 Java 8 编译器仍是 JVM 子进程。显式 JVM 诊断后端无自动 fallback。

## 5. 存储与发布

| 对象 | 约束 |
|---|---|
| source_files | 所有图源路径共享；全局 NULL 与空路径分开，边路径独立于源节点 |
| external_targets | fqn/symbol/language/provider 四个 nullable 字段共同构成身份，不能只按名字合并 |
| metadata_values | 原 UTF-8 ≥256 bytes 且重复至少两次才共享；SHA 只是检索提示，完整字节相等才合并 |
| annotation_meta_* | 定义、来源、归属分离；首次位置、归属状态和上游无文件身份规则保持 |
| node_names FTS | external-content 指向 graph_nodes，以 node_pk 连接；逻辑 nodes 视图没有可用 rowid |
| 全量 | 私有候选保留唯一约束，暂停非唯一索引/FTS 触发器，导入后重建并校验 |
| 增量 | SQLite backup 兼容基线，替换归属，重映射暂存主键、保留未变身份/ordinal，重建派生结果并封存字典 |
| 持久化 | 完整性/FK/FTS 检查，发布连接 WAL/NORMAL、checkpoint/close、文件与目录同步，再 catalog CAS |
| 升级 | 旧 graph 格式返回 `GRAPH_SCHEMA_REBUILD_REQUIRED`；index --full 建新库后切 head，不改旧文件 |

公开查询只读逻辑投影；raw type/binding/syntax 图查询明确不支持。存储实现见 [存储模块](modules/storage.md)。

## 6. 输出、证据与历史

| 维度 | 含义 |
|---|---|
| execution | 执行成功、失败、超时或取消 |
| assessment / gate | 原质量与当前门禁是否允许发布，互不替代 |
| publication | 是否实际提交 revision；提交后断连/报告失败不能撤销 |
| report | 保存/导出/关闭/失败，与图发布分开 |
| evidence | 覆盖、枚举、工作/深度/输出限制；空结果不能掩盖缺口 |

文本输出为 `codeana-text-v1`，record/terminal 后为单行 JSON；RPC 终态是类型化 protobuf。stdout 放数据，stderr 放进度。退出码 0 成功、1 错误、2 参数错误、3 strict 拒绝、124 超时、130 取消。

pipeline 固定一个 ReadContext；分页固定结果对象，24 小时内续页不重新读取当前工作区。普通 source 验证当前源码 hash，显式历史 source 读捕获 blob。兼容图历史查询无需 JDK；旧物理格式仍要求重建。人工标注独立保存，重建不删除正文。
