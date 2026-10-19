# 公共契约：身份、图记录与协议

现行契约对齐 2026-10-16。版本总表见 [当前实现](current-state.md)；过期 Fact IR / CAF1 契约已删除。

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

当前 graph model 3、worker 21、analysis request/result 10、C ABI 4、daemon handshake 3；javac-model-v50、compiler batch 2。图库物理 user_version 10，上游逻辑 schema 33、GraphSemantics 26，adapter `anatomist-4a903272-codeana-graph2-maven-fallback1`。catalog/annotations 均为 2，彼此独立。

manifest 仍沿用 `fact_ir` 键，daemon 仍声明历史能力标签 `fact-ir-v1`；这些名字不表示产品继续传输旧 Fact JSON。混装 bundle/worker/ABI 在分析前拒绝。

内部 Rust 状态、操作、端点和错误码使用枚举，protobuf 与 JSON 边界显式映射；这些类型不改变线上字段拼写。未知 producer 扩展透传，稳定语义字段类型错误明确拒绝。

| 内部编码 | 兼容约束 |
|---|---|
| `FactAttributes` | 稳定语义字段有明确类型；`Field<T>` 区分缺失、null 和具体值；未知字段由 `OpaqueMetadata` 透传 |
| 冻结页与任务记录 | 新记录带 `record_type`；旧无标签记录作为 `Record::Display` 保持重放/续页，不作为新 pipeline 的语义输入；renderer 不输出内部标签 |
| `Record::Display` | 仅承载已呈现运维/历史数据，不提供语义端点访问 |
| 图身份与 profile | 公开 ID、hash/ordinal、输入摘要及 profile 编码保持；既有命名盐不表示现行协议版本 |
| `DomainError` | 错误码独立于上下文和文案；类型化 cause 保持读取/取消身份；外部 producer 代码显式保留，故障注入文本保持原拼写 |

## 2. GRA1 图记录

源码：[graph.proto](../proto/codeana/v1/graph.proto)、[graph_archive.rs](../crates/contracts/src/graph_archive.rs)、[AnatomistRows.java](../lang-java-adaptor/java-bridge/src/main/java/dev/codeana/adapter/AnatomistRows.java)。

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

Rust 和 fixture producer 使用 `contracts::graph_rows` 中的具名行结构，通过 `GraphRow::into_row` 集中编码逻辑列。整数转换检查范围，`Option` 区分 NULL 与空字符串，metadata 保留原始文本。此构造边界不改变 GRA1 列顺序、字节编码、hash/ordinal、协议或数据库版本；Java producer 原始行不经这些结构重编码。

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

Begin/Chunk 均为 provisional。worker 核对归档长度/EOF 或流式生成终态、SHA 与预算后才发 End；daemon 独立核对序号、行数、摘要、来源/类型/引用、coverage 和门禁后才能完成候选。结果大小、行数、scratch 预算见 [配置](configuration.md)。

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

新查询按业务字段稳定排序，不将整条记录序列化为 JSON 比较。跨记录族顺序为 Fact、Call、Trace、Dispatch、Source、Text、Project、Group、Display；同键保留原顺序与重复次数，可选字段 None 在 Some 之前。

| 记录族 | 排序键（从左到右） |
|---|---|
| Fact | world、公开 ID |
| Call | from、to、kind、depth、调用点列表 |
| Trace | from、to、节点列表、逐跳调用关系 |
| Dispatch | world、call_site、target、binding、candidate、algorithm |
| Source / Text | path、start、end / path、line |
| Group | module、package、target_package、relation |
| Project / Display | 保持输入顺序 |

流式 search-code 保持公开 ID 顺序，search-text 保持文件/行顺序。新冻结页记录可选 `order_policy`；旧页缺此字段仍按已保存顺序续页，不重新排序。新查询首页可能与旧 JSON 排序不同，记录内容及身份保持不变。

## 7. Maven 外部输入

导出 XML `maven-inputs-v2`、manifest schema 4；旧 schema 3 要求重新导出。三态 `resolved` / `source_only_requested` / `source_only_fallback` 与 `resolved_classpath` 一致，源码模式不允许外部 classpath。失败模式必须包含模块、实际 POM、坐标、原因、轮次及失败回执 SHA。schema 4 的 inputs SHA 在降级时指向本次已验证 metadata；失败回执另有独立 SHA。

worker 21 的 JavaConfig 与 analysis request/result 10 携带冻结的依赖状态及类型化失败信息，旧 bundle 在分析前拒绝。当前 C ABI 4、graph model 3、图库 schema 10、compiler batch 2。模块拓扑已捕获与外部 classpath 可用性分别表达；不以缺 jar 否定 reactor 源码上下文。

全局 coverage 使用 `*` 来源；模块级缺口同样影响 semantic evidence，不计作真实源码文件。SQL NULL 来源仍受现有图库 NOT NULL 约束拒绝。增量替换全局降级覆盖与诊断，不随任务次数重复累加；模式切换全量重建。报告新增可选 `analysis_mode/classpath_status`，旧报告缺字段仍可读取。

## 8. Rust 冻结输入与公共行

| 对象 | 契约 |
|---|---|
| `rust-inputs.json` | schema 2；输入策略摘要、辅助文件白名单、截断依赖；工具链版本、crate/依赖图、edition/target/cfg/env、controls/generated SHA256；所有源码路径限制在项目内 |
| `Discover.rust` | 冻结配置文件路径、长度、SHA256 和输出预算；worker 不重新读取项目配置，不执行 Cargo/build.rs/过程宏 |
| `Row.world` | Rust 必填且与捕获 source instance 匹配；Java 保持空值及原公开 ID；coverage/diagnostic 同样按 world 隔离 |
| `SOURCE_STATE` / `ANALYSIS_DEPENDENCY` | 保存 Rust 文件/接口摘要、声明名与文件/符号/查找/宏/impl 环境依赖，随 revision 原子发布 |
| 增量 | 新增、删除及零输出都有 owner replacement；同物理文件全部 world 一起替换，未变 owner 保留 |
| 混合发布 | Rust 和 Java 子任务分别封口；任一失败则不切 head，合并后只执行一次 candidate 校验和 CAS |

Rust 未绑定调用保留调用点，不伪造目标；只加载项目及一层直接依赖，截断、过滤和主动宏展开禁用均记录 coverage 缺口。strict 在 partial 时拒绝发布。Rust 原始 HIR/Salsa 数据不作为持久化契约。

## 9. 有界记录流与 Rust 增量存储

Rust 单语言由 blocking 记录生成器直接送入 IPC，daemon 复用 `analyze_into` 写私有候选。worker 和 daemon 队列各 4 批、8 MiB 解码数据预算，批次最多 64 行；Rust 生成器通常按 256 KiB 编码数据切批。预算覆盖已接收但尚未消费完的批次；单个合法超大行独占预算，仍受帧/结果上限约束。队列之外还允许一个有界待发送批次及编解码缓冲，因此队列预算不是进程 RSS 上限。

只有独立校验过的 End 才产生内部 `VerifiedEnd`；断流、错序、摘要错误或取消均不可封存候选。队列等待和终态发送可取消；worker 独立读半帧保留的控制流，写阻塞不阻止取消。混合 Java/Rust 使用已验证归档合并，全部成功后一次 CAS。

物理 schema 10 沿用 schema 9 引入的 Rust 路径/world/key 内部整数引用，依赖只存在类型化表中；状态 JSON 不再包含 dependencies。baseline 批量关联重建完整 SourceState，公开 ID、worker 21 和 graph model 3 保持。旧图必须全量重建。

## 10. 取消与提交的裁决

任务级 PublicationFence 只有一个所有者。Preparing 阶段取消赢得裁决则不能开始提交；Committing 阶段等待真实事务结果，再以 durable commit 生成终态。取消/超时不丢弃仍可能写存储的 future，也不提前释放项目/执行许可。取消发生在事务提交前的文件准备阶段时，旧 head 保留；提交开始后的取消只终止后处理，成功提交必须公开 published。

重复 request ID 在新任务准入前检查规范化参数摘要；重试可在通道饱和或 drain 时读取原任务。不同参数拒绝，新请求无名额时不登记任务。daemon/worker/ABI/GRA1 编码和 schema 版本保持。

## Python 输入与版本

worker 21 的 `Discover.python` 与 Java/Rust 描述符互斥，携带冻结配置文件的路径、SHA-256、长度及结果/事实/scratch 预算。PythonConfig 不改变 Java ABI 或请求版本。Python world 包含显式上下文和根身份顺序；公共位置是一基 UTF-16 列，偏移来自原始 UTF-8 字节。

Python 当前输入 schema 3，兼容 1/2；新增根 module、模块元数据和上下文发布范围/缺口。独立导出器仍可用 --pyproject 读取可选静态配置，统一准备默认检查入口根；没有 pyproject 不构成前置条件。adapter v3 与固定后端提交严格核对，旧 worker 拒绝；新 profile 不复用旧结果。诊断仍按实际反向依赖归因，辅助/后端来源和未知影响保留。公共准备清单 schema 1、报告 schema 2 独立版本化，不升级 worker/daemon/GRA1/图库协议。详见 [统一输入准备](modules/inputs.md)。

物理 schema 10 在保持 schema 9 紧凑表结构的基础上扩展 Python 声明约束，逻辑 schema 33 不变。Python 只发布已绑定目标，不按同名函数跨 world/语言补边。未解析调用继承已验证 source 的 language/provider。缺模块即使被源码 ignore 也计入完整性；严格模式拒绝 partial。旧 revision 查询/增量必须先 `index --full`。详见 [Python worker](modules/python-worker.md)。
