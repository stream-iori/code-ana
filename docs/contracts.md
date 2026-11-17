# 公共契约：身份、图记录与协议

> worker 25 正在迁移：Discover 使用互斥配置并在解码前拒绝重复变体。此前 worker 24 的产品、Native 与分发回执不代表本次协议验收；当前进度见 [当前实现](current-state.md)。

现行契约对齐 2026-10-20。版本总表见 [当前实现](current-state.md)；过期 Fact IR / CAF1 契约已删除。

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

当前 graph model 6、worker 25、analysis request/result 13、C ABI 4、daemon handshake 7；javac-model-v51、compiler batch 2。图库物理 user_version 17，上游逻辑 schema 36、GraphSemantics 28，adapter `anatomist-4a903272-codeana-graph6-resource2-analysis`。catalog 为 2。业务语义标注与独立人工笔记已删除；protobuf 的 graph 枚举号 6 和 TaskRequest 字段号 20 保留为 reserved。

SDK manifest 使用公共 schema 4；Java Native 3 / JVM 2 等旧格式要求重建。公共字段为 `version/worker_protocol/fact_ir/adapter/graph_schema/graph_encoding/os/arch/files`，`backend` 为类型化对象：

| 身份 | 专属字段 |
|---|---|
| `backend.kind=java` | analysis_request、compiler_batch_protocol、java_model、target_java、compiler_bridges、runtime |
| `backend.runtime.kind=native` | abi、gc、build_mode；缺 GC 不默认 Serial/G1 |
| `backend.runtime.kind=jvm` | 不接受 Native 的 GC/ABI 字段 |
| `backend.kind=typescript` | 独立 abi/analysis_request、upstream_commit、go_version、build_options、standard_library、header_sha256；不接受 Java 字段 |

已知身份缺字段、未知变体/字段、重复 JSON 键均拒绝，包括 files/标准库及开放元数据中的键。SDK JSON 最多 1 MiB、65536 个值/键节点、128 层；Rust 在构造类型化 SDK 对象前用 serde 流式预检。额外 producer 元数据只放 `producer_metadata`，不选择执行后端。TypeScript 目前仅有 SDK 契约测试，不表示生产 SDK 可用。doctor 的 `java_sdk_state` 区分 missing/invalid/present_unverified，后者只验证元数据，不把未核实文件摘要写成 SDK 已就绪；错误另列 `java_sdk_error`。

manifest 仍沿用 `fact_ir` 键，daemon 仍声明历史能力标签 `fact-ir-v1`；这些名字不表示产品继续传输旧 Fact JSON。混装 bundle/worker/ABI 在分析前拒绝。

内部 Rust 状态、操作、端点和错误码使用枚举，protobuf 与 JSON 边界显式映射；这些类型不改变线上字段拼写。未知 producer 扩展透传，稳定语义字段类型错误明确拒绝。

C1 内部 javac 模型当前为 v51：每个源码请求附带从现有累计账户预付的 work/nodes/payload 上界，编译器录制器只能消费该范围；响应在声明帧之后交付类型化注释范围、正文、正文到原始源码的分段映射及文档声明语法身份。该内部模型的范围仍是 Java 原始 UTF-16 半开偏移，由 JavaCommentEvidence 在模型关闭前转为 GRA1/public comment 的 UTF-8 偏移。声明所有权来自主 javac 的同一 comment token；不按同名或最近声明推测。核心模型只保存自有记录，不保存编译器对象。GRA1 与公开注释查询已接通，compiler batch/daemon/ABI 现为 2/6/4；匹配的 v51 开发 Native SDK 已安装。

| 内部编码 | 兼容约束 |
|---|---|
| `FactAttributes` | 稳定语义字段有明确类型；`Field<T>` 区分缺失、null 和具体值；未知字段由 `OpaqueMetadata` 透传 |
| 冻结页与任务记录 | 新事件带投影版本 3 和类型化 `Record`；仅接受当前带标签记录；不存在旧格式解码或旧事件回放分支；renderer 不输出内部标签 |
| `Record::Display` | 仅承载已呈现运维/历史数据，不提供语义端点访问 |
| 图身份与 profile | 公开 ID、hash/ordinal、输入摘要及 profile 编码保持；既有命名盐不表示现行协议版本 |
| `DomainError` | 错误码独立于上下文和文案；类型化 cause 保持读取/取消身份；外部 producer 代码显式保留，故障注入文本保持原拼写 |

## 2. GRA1 图记录

源码：[graph.proto](../proto/codeana/v1/graph.proto)、[archive.rs](../crates/graph-io/src/archive.rs) / [reader.rs](../crates/graph-io/src/reader.rs)、[AnatomistRows.java](../lang-java-adaptor/java-bridge/src/main/java/dev/codeana/adapter/AnatomistRows.java)。

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

表族为 NODE、EDGE、DECLARATION、ANNOTATION、ANNOTATION_META、UNRESOLVED_CALL、FILE_CACHE、FILE_DEPENDENCY、SYMBOL_DEPENDENCY、DIAGNOSTIC、COVERAGE、PROVIDER、PROJECT_META、COMMENT、COMMENT_LINK。数据库物理布局可变，GRA1 逻辑列不携带紧凑库整数主键。

Rust 和 fixture producer 使用 `contracts::graph_rows` 中的具名行结构，通过 `GraphRow::into_row` 集中编码逻辑列。整数转换检查范围，`Option` 区分 NULL 与空字符串，metadata 保留原始文本。此构造边界不改变 GRA1 列顺序、字节编码、hash/ordinal、协议或数据库版本；Java producer 原始行不经这些结构重编码。

## 3. CLI-daemon 与 worker

| 边界 | 当前实现 |
|---|---|
| CLI-daemon | HTTP/2 + protobuf gRPC；本机 loopback、token 校验和用户 home 单实例锁 |
| 任务 | SubmitTask / WatchTask / GetTask / CancelTask；传输失败与执行/发布终态分开 |
| 幂等 | 同 request ID + 同参数返回原任务；同 ID 改参数拒绝 |
| daemon-worker | 独立 FD 上的 u32be 长度前缀 protobuf Envelope；stdout/stderr 不承载协议 |
| 握手 | Hello 核对 worker/model/producer、签发实例 grant 与 deadline，回传首个用量水位；Discover 返回上下文或 BuildAdvice |
| 分析 | Analyze → Begin → 连续 Chunk(seq, rows) → End(chunks,count,checksum) |
| 重规划 | Replan / Failure 终止当前 attempt 的交付；不发布半图 |
| 确认 | daemon 成功提交后发送 PublishAck，worker 回 Acked；确认失败披露 warning、实例不复用，已发布 head 保留 |

Begin/Chunk 均为 provisional。worker 核对归档长度/EOF 或流式生成终态、SHA 与预算后才发 End；daemon 独立核对序号、行数、摘要、来源/类型/引用、coverage 和门禁后才能完成候选。结果大小、行数、scratch 预算见 [配置](configuration.md)。

持久 `FrameReader` 保存帧前缀/正文进度；异步读取被 select 中断不能丢半帧或重复收费。业务帧先为大正文缓冲预留，再以 wire 扫描核算解码记录、字符串及容器容量，全部准入后才交给 prost；发送端先预留编码缓冲再编码。启动握手上限 4 KiB；终态/确认/复用/取消使用独立、最多 16 KiB 的控制通道，缓冲放在堆上，额度耗尽仍能送出失败回执。Java FFI 期间只接受最多 1 KiB 的 Cancel，控制/回执探针成本在转交 seed 前预留，不能与 Java 同时修改同一本累计账。取消后的剩余业务帧流式丢弃，不再分配模型；已读半帧继续到正确边界后读取终态。固定控制通道是有界运行开销，不代表业务可免预算。

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

CLI 默认 text：按文件/所属者共享上下文，声明使用规范签名，连续源码使用可变长度反引号围栏，不逐行加编号；末行 `end` 仅确认本次回执闭合。原文围栏关闭前恰有一个 framing LF，移除该 LF 后保留真实源码的结尾，包括无换行和 CRLF。

Java 成员表达为 `method greet(java.lang.String) -> java.lang.String [public] @范围`，文件与 owner 在共同 header；参数名单列，只有无法由规范签名还原的泛型参数类型才额外保留。不重复 name/display_name/signature/metadata。投影仅删除已知 DTO 的辅助字段，不递归删生产者扩展中的同名键。

显式 `--format json` 输出一个标准文档：`records`、`result`、`evidence`、`delivery`。JSON 用于测试和结构化导出；不根据 TTY 改格式。watch 使用 `events`，不会同时重复 `records`；事件逐条编码，暂存不超过当前输出预算，最终计入头部和终态预算后闭合。`after_seq` 是观察起点，`last_seq` 是可续接的已交付前缀，`observed_seq` 是已观察的流位置。因预算省略正文时，两者可以不同；重连用 `last_seq`。

watch 逐个检查连续序号，缺失、乱序、未知投影或缺少终态时不给闭合回执，不用 GetTask 终态代替正文。TaskStatus.operation 保存原业务操作，使运行中的 watch 也能选用正确公开投影。任务事件仅使用 Progress／Record／Result，持久终态不包含 protobuf。

语义证据、计算边界、执行、门禁、发布、报告与交付状态独立。分页不修改 `evidence.enumeration`；交付 strict 拒绝不覆盖分析门禁或已提交事实。stdout 放结果/必要观察事件，stderr 放未形成回执的参数/连接/断流错误，不重复打印终态错误。退出码 0 成功、1 错误、2 参数错误、3 strict 拒绝、124 超时、130 取消。

启动恢复若仅能核实 durable commit，则保留 `publication=published` 与真实 revision，同时记录 execution=failed、assessment/gate/report=unknown、delivery=failed、DAEMON_RESTARTED；不能从提交推断原质量或报告成功。`No matches.` 仅在语义充分、计算及交付完整时成立；未完成枚举不提供结果总数。

数据入口支持 `--format text|json`、`--detail`、`--export NEWPATH`。导出预检先于任务受理；同目录临时文件 fsync 后原子不覆盖发布，stdout 仅返回导出回执。`report --format markdown` 必须显式导出；`java-inputs export --output` 是准备产物，`--export` 是 CLI 回执。后台与导出/查询 cursor 互斥。

`diff --section files|declarations|relations|impact`；`snapshot NAME --kind files|analysis`。pipeline 是 UTF-8 文本文件，每行一个阶段，`--from entity|caller|callee|site|target` 映射类型化端点，不执行 shell 内容。无关参数本地拒绝；daemon/task/recover 不接受伪装项目范围的 `--project`。

diff 的 DeliveryContext.comparison 冻结双方名称、快照类型、revision 或 manifest 身份，续页不读取同名快照的新值。impact 仅从变更关系涉及的声明/外部目标反向传播调用，保留 static_possible_impact / relation_change_roots_only；--depth 仅用于 impact，尚未实现的 world/module/scope 筛选拒绝。

TaskRequest 字段 51–54 保存格式/详情 presence 与显式参数。新 OutputRecord 事件携带投影版本和带枚举标签的 Record 编码，正文不是已格式化 stdout；Terminal 的 Delivery 保留交付上下文。应用内持久终态采用 TaskResult，在协议边界映射 protobuf。daemon 协议 7 拒绝旧 CLI；worker/图/ABI 和兼容图库保持。

查询 cursor 保存投影/排序版本、原请求、项目、格式与详情以及冻结输出和保护引用；只凭 cursor 续页，可调整页大小/字节预算，不能重复 selector/筛选或切换格式。过期或不兼容页显式拒绝，不自动重新执行。分页器使用最终格式编码计量，包含 header、必要状态、cursor 和 end，不按 JSON 估算 text 大小。

pipeline 固定一个 ReadContext；分页固定结果对象，24 小时内续页不重新读取当前工作区。普通 source 验证当前源码 hash，显式历史 source 读捕获 blob。兼容图历史查询无需 JDK；旧物理格式仍要求重建。

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

流式 search-code 保持公开 ID 顺序，search-text 保持文件/行顺序。冻结页同时校验 projection_version=3 和 order_policy，兼容页按保存顺序交付，不重新排序；旧投影 cursor 明确拒绝。新查询首页可能与旧 JSON 排序不同，记录内容及身份保持不变。

## 7. Maven 外部输入

导出 XML `maven-inputs-v2`、manifest schema 4；旧 schema 3 要求重新导出。三态 `resolved` / `source_only_requested` / `source_only_fallback` 与 `resolved_classpath` 一致，源码模式不允许外部 classpath。失败模式必须包含模块、实际 POM、坐标、原因、轮次及失败回执 SHA。schema 4 的 inputs SHA 在降级时指向本次已验证 metadata；失败回执另有独立 SHA。

worker 25 的 JavaConfig 与 analysis request/result 13 携带冻结的依赖状态及类型化失败信息，旧 bundle 在分析前拒绝。当前 C ABI 4、graph model 6、图库 schema 17、compiler batch 2。模块拓扑已捕获与外部 classpath 可用性分别表达；不以缺 jar 否定 reactor 源码上下文。

全局 coverage 使用 `*` 来源；模块级缺口同样影响 semantic evidence，不计作真实源码文件。SQL NULL 来源仍受现有图库 NOT NULL 约束拒绝。增量替换全局降级覆盖与诊断，不随任务次数重复累加；模式切换全量重建。报告新增可选 `analysis_mode/classpath_status`，当前报告必须提供其必需字段。

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

物理 schema 17 沿用 schema 9 引入的 Rust 路径/world/key 内部整数引用，依赖只存在类型化表中；状态 JSON 不再包含 dependencies。baseline 批量关联重建完整 SourceState，公开 ID、worker 25 和 graph model 6 保持。旧图必须全量重建。

## 10. 取消与提交的裁决

任务级 PublicationFence 只有一个所有者。Preparing 阶段取消赢得裁决则不能开始提交；Committing 阶段等待真实事务结果，再以 durable commit 生成终态。取消/超时不丢弃仍可能写存储的 future，也不提前释放项目/执行许可。取消发生在事务提交前的文件准备阶段时，旧 head 保留；提交开始后的取消只终止后处理，成功提交必须公开 published。

重复 request ID 在新任务准入前检查规范化参数摘要；重试可在通道饱和或 drain 时读取原任务。不同参数拒绝，新请求无名额时不登记任务。daemon/worker/ABI/GRA1 编码和 schema 版本保持。

## Python 输入与版本

worker 25 的 `Discover.python` 与 Java/Rust 描述符互斥，携带冻结配置文件的路径、SHA-256、长度及结果/事实/scratch 预算。PythonConfig 不改变 Java ABI 或请求版本。Python world 包含显式上下文和根身份顺序；公共位置是一基 UTF-16 列，偏移来自原始 UTF-8 字节。

Python 当前输入 schema 3，仅接受 3；新增根 module、模块元数据和上下文发布范围/缺口。独立导出器仍可用 --pyproject 读取可选静态配置，统一准备默认检查入口根；没有 pyproject 不构成前置条件。adapter v5-comments 与固定后端提交严格核对，旧 worker 拒绝；新 profile 不复用旧结果。诊断仍按实际反向依赖归因，辅助/后端来源和未知影响保留。公共准备清单 schema 2、报告 schema 4 独立版本化，不升级 worker/daemon/GRA1/图库协议。详见 [统一输入准备](modules/inputs.md)。

物理 schema 17 在保持 schema 9 紧凑表结构的基础上扩展 Python 声明约束，逻辑 schema 36。Python 只发布已绑定目标，不按同名函数跨 world/语言补边。未解析调用继承已验证 source 的 language/provider。缺模块即使被源码 ignore 也计入完整性；严格模式拒绝 partial。旧 revision 查询/增量必须先 `index --full`。详见 [Python worker](modules/python-worker.md)。

查询资源能力标签为 `query-resources-v1`，直接递归投影为 `call-recursion-v1`。Evidence 新增可选 resource_usage；正常输出省略。protobuf 采用新增字段号，daemon/worker/ABI 版本不变。账本快照按根操作写入，不把共享快照相加；冻结记录仅接受当前投影契约。

### 分析截止时间与 Python 累计回执（已接入）

任务排队、分析和发布共享同一单调截止时间；Hello.deadline_ms、Discover.deadline_ms 与 Analyze.deadline_ms 显式发送剩余时长；三者均用optional字段区分缺失与明确0（无任务截止时间），缺失拒绝。Rust/Python 使用公共 AnalysisTaskMeter 保留 grant、用量和首个截止时间，后续 attempt 不能延长或清除截止时间。TIMEOUT、CANCELLED、ANALYSIS_RESOURCE_LIMIT 维持独立身份。PublicationFence 在提交准入前原子检查截止时间；一旦进入提交，后续取消/超时不能撤销已准入的提交。

Python 普通/组合/prepared 入口加入 analysis-resources-v1，End/Failure 回传累计用量；host 与同语言多实例严格使用各自分片。当前严格使用worker25/Java13；生产Hello/Discover/Analyze拒绝缺失或语言错误的grant，Java生产请求还要求累计seed和deadline。公共 IPC 帧已在分配/解码前准入；语言内部投影与 codec 仍按各自审计边界验收。

### Java 同账户累计回执（已接入）

Java worker 下发同一个 grant、当前累计 analysis_usage 和剩余 deadline_ms；Java AnalysisContext 从该水位继续累计，成功/失败结果均可返回 grant_id、usage、phase。Rust 先核对身份、阶段、额度和不倒退，再接续归档读取、编码及 IPC 消耗。该接续只允许无子作用域的 worker 根账户，不能重置失败或额度；回执本身不证明归档有效，成功 End 仍要求原始归档 EOF/SHA 校验通过。

Java 普通、Java/Rust、Java/Python 和 prepared 入口已接入；健康 worker 收到发布确认后结束旧账户，新任务重新签发。无法取得可信 Native/JVM 最终回执时，Failure 保留原错误、账户标 Unknown，不把未知消耗写成 0。真实 Native SIGSEGV fixture 已断言旧 head、Unknown、进程回收及恢复 SDK 后的新索引成功。

JDK SAX 先拒绝深度时，记录平台实际限制：固定工具链当前为 100，比任务默认 256 更小。通过 JAXP00010006 与已接受 SAX 深度识别这次拒绝，不提高 JDK 限额；资源异常继续使用 HardResourceLimit，超时/取消保持独立身份。实现身份为 anatomist-4a903272-codeana-graph4-resource2-analysis。

当前已切换worker25/Java13，旧握手/请求/结果与混装bundle拒绝；后续语义 JSON、签名投影、混合与分发的本机验证见 current-state 的对应轮次，正式跨平台与容量边界保持。


### Discover 与未变复用累计终态（已接入）

生产入口在小型 Hello 中签发静态实例grant、初始化账本并核对首个水位，再准入 Discover 业务帧；三种worker均拒绝缺失/语言错误的grant。Discover描述符和配置文件读取/解码预留计入同一个AnalysisTaskMeter，BuildContext.analysis_usage回传累计水位；该字段只用于传输，不进入领域BuildContext或语义profile。Analyze必须接续同一个grant、累计用量及截止时间。

完全未变输入通过 Reuse → Reused(analysis_usage) 结束分析账户，phase=reused，不发送发布 Ack。Java 在清除任务状态后才回复 Reused，可继续进入健康池；其他后端随后退出 worker。报告保留实际 Discover 消耗，不能把复用记成 0 或留下 Reserved。Java 健康 worker 也可在普通发布 Ack 后复用；已选中的warm worker rediscover失败直接保留错误，禁止另发grant重试。

进程丢失最终回执时，state=unknown、complete=false；usage若存在，只表示最后核实水位。当前真实Native崩溃场景保留Discover水位，不证明FFI最终消耗；Markdown同步提示不完整。所有发送的End/Failure/Replan/Reused必须携带回执和显式usage_known（protobuf optional bool，缺失拒绝）；FrameReader 在业务缓冲与 prost 解码前核算额度；取消排空不解码业务模型。


worker25的usage_known=true表示该回执时刻的累计用量已核实；false仅允许Failure携带最后核实水位，host登记Unknown，不能认证成功或重规划。已死亡/失联的进程没有最终消息时，仍保留之前水位并标Unknown。控制取消核对attempt，回执写入有短时限；回压导致无法送达时不阻塞退出，也不伪造完整终态。

Java13请求显式要求analysis_grant、analysis_usage的四项整数水位及deadline_ms；缺失时不创建默认生产账本。成功结果要求version13、usage_known=true及analysis_usage；预分配OOM/Stack/Fatal错误结果使用version13、usage_known=false，Rust保留原错误并给出Unknown回执。C ABI保持4。

### Java 签名与 JSON 传输准入

声明指纹/压缩视图/源签名投影显式接续任务上下文；局部work/depth拒绝保留根账本粘性失败。异常类型擦除不再无界递归。ASM内部存储在创建ClassWriter前依据完整detached模型保守预留；不声称逐条衡量ASM内部CPU/RSS。

两套Java JSON facade均使用Jackson：领域JSON保持既有数值规则；Native/JVM传输JSON保留Long/BigInteger/BigDecimal和canonical UTF-8规则，拒绝重复键/无效UTF-8，读值节点上限1000000、数值文本1024、控制输入64MiB、深度256。Rust在FFI前通过serde结构访问为将解码的值节点计费，并预留16个之后写入seed的节点；不先创建JSON树。Java先完成结果编码预留，再快照analysis_usage，避免回执漏记编码消耗。失败回执使用独立有界控制输出。未知codec若不能提供显式budget转换，生产序列化拒绝，不能回退到无账本转换。

SOFA与Spring一样使用JDK SAX与XmlResourceBudget；SOFA保留更小的16MiB输入上限、JDK更严格的深度拒绝，超限不合并当前文件的局部事实。

### 扩展与内部 DTO 的构造前检查

JavaModelExtension生产调用显式接收任务AnalysisContext，未实现带账本入口的旧回调拒绝执行。Lombok模型/生成成员/类型索引/配置层/调用证据继续使用该上下文；扩展元数据在不可变复制前做有界迭代检查，局部64层/100000值限制耗尽保留HardResourceLimit，不能转成可发布的扩展失败。方法ID保留canonical嵌套名，通过活动类型表访问避免每个参数复制完整类型图。

Rust/Python配置与Rust/Java基线JSON由serde流式预检值节点/深度后再解码类型化DTO；临时字符串与容器容量提前预留。配置和请求投影复制也走公共admit_owned_copy/clone_with_budget，不因重试另建账本。取消/截止时间与额度耗尽保持各自错误身份。

JVM/Native合并JAR必须逐类匹配本次reactor编译结果；父构建强制重建薄JAR，防止重复shade沿用旧依赖类。此检查先于Native Image或JVM bundle清单生成，产物SHA不代替源码到类的核对。

注释事实与关联使用 CommentFact/CommentLink、UTF-8 正文映射和 CommentReader 端口；Java 公共链路及生命周期已验证，Rust/Python 提取由 C3 接入。详细规则见 todo/comment-code-indexing.md。


## 源码注释契约（C1 已交付，版本以本页开头为准）

C1 的版本与原验收按历史记录解释；当前版本以本页开头及 current-state 为准。注释模型的规则如下，本轮 CLI 展示重构不改变其图协议或已有 SDK 身份。

GRA1 新增 COMMENT=17、COMMENT_LINK=18；保留 reserved 6。两类行必须给出捕获源码的显式 world；注释 ID 由 producer、language、module、scope、world、相对路径、source hash、原始 UTF-8 起止偏移及 kind 的 JSON 数组摘要产生。正文映射、来源范围和声明引用在同一捕获文件/world 内校验；存储不推断语言归属。公开坐标为一基 UTF-16，字节区间为 UTF-8 半开区间，内部整数主键不输出。

TaskRequest 字段 48 为 search_in；49/50 及名称 context_before/context_after 保留 reserved。CLI 要求 comment-search-v2；删除 --context-before/--context-after 和 [query.comments]，旧参数／配置明确报错。CommentMatch 仅包含 comment、hits、links，不附带源码窗口；links 只接受 documents/enclosing_callable 和 language_rule。源文件仍按捕获 hash 验证，以计算原始命中范围。

冻结页统一采用公开投影版本 3，不再另设注释兼容版本。旧投影游标拒绝并要求显式重新查询，不自动重算。prepared 清单删除展示配置例外，config_sha256 精确匹配原始配置字节；旧例外清单需要重新 index --prepare。artifact、来源和语义 profile 校验保持。

JavaConfig 必填 target_java（8/17），附带 actual_java_version 与 toolchain_aliases；analysis 13 请求必填目标，缺失或非法值拒绝。SDK manifest 4 在 Java 后端身份中登记 target_java=[8,17]、compiler_bridges 映射及每个文件摘要；缺桥、错误摘要和混装 SDK 拒绝。javac-model-v51、compiler batch 2、C ABI 4 保持；Native 构建 JDK 25 与目标 JDK 分开。新语义与旧图库不原地迁移，index --full 建成后切 head。

prepared 输入清单当前为 schema 2。单元配置 artifact 仅接受 discovery/languages/modules，不包含根 schema/kind、扫描、资源或报告策略；任务公共策略从项目 TOML 一次归一，由原生单元共享。schema 1 明确拒绝并要求 index --prepare，不提供旧配置视图转换。完整性仍以根配置原始 SHA 和各 artifact 原始字节校验。

worker 25 的 BuildContext 使用必需 unit、source_languages、modules/worlds、semantic_digest、producer 和图能力；后端由 details oneof 的 Java/Rust/Python/TypeScript 变体确定。Java 的目标版本/classpath/JDK/桥、Rust toolchain/adapter、Python 目标/adapter/后端提交与 TS 编译器/提交均进入各自类型化详情，不能由开放元数据选择后端。Discover 必须显式给出 unit 和发布源码语言，实例须匹配 grant；daemon 在启动和池复用时核对实例、后端、语言、worlds 及摘要。旧通用 source_version/classpath、缺失/重复详情和重复 unit 在 wire 解码前拒绝；用量回执不进入领域上下文。上下文摘要包括稳定实例/语言/模块/world/后端详情，排除源码正文和 IPC 用量；规范化 SDK 定位等完整 profile 稳定性继续由公共捕获阶段处理。

报告 schema 4 的 contexts 数组保存每个已发现实例的上下文；失败在未发现上下文时为显式空数组。移除单一 context 和伪 multi 后端，schema 3 不转换、不补默认值。全局准备状态仍覆盖 FilesOnly 单元；有上下文不等于语义结果已经发布。

GRA1 的 Descriptor/OutputLimits 为 contracts::graph_delivery 纯契约；文件 IO、归档校验、行队列及生产/接收摘要统一由 graph-io 承担。Java 只解自己的 JSON envelope，再调用公共 read_descriptor；原始归档 SHA 不以重编码替代。IPC 行流独立使用同一 Verifier/Encoder 的规范编码摘要，只有完整匹配 chunks/count/SHA 才能生成不可在调用方构造的 VerifiedEnd；daemon 还须核实终态用量后才能投递证明。删除 contracts 的 graph_archive/stream/worker_rows 和 Java 专属 delivery 模块，无旧路径 re-export。

IPC 读写进度和 FrameReader 位于 worker-runtime::ipc；contracts::frame_admission 只保留 wire 规则和分配前准入。三语言使用公共 Session，daemon 使用 delivery::Receiver 核对冻结身份与结果阶段。Java 内部请求/回执位于 java-worker::engine_request，不属于公共契约。FFI 账本使用唯一 EngineAccountingLease：HostOwned → EngineOwned → HostOwned/Unknown，交接期间所有 Context 克隆禁止扣账，缺失/非法回执不得恢复计费或提供成功证明。详见 [运行设施](modules/worker-runtime.md)。

R3 的语义 profile 使用核实后的上下文 semantic_digest，Java toolchain.home 作为来源保留而不进入该摘要。Java 依赖摘要保留顺序/内容身份，排除 export receipt；prepared generation、日志和 artifact 定位不进入语义摘要。原始配置/catalog/artifact 完整性校验保持严格。scope=PREPARATION 标识无语义归属的准备完整性文件；只有 profile 完全相等且 module/language 为空、world=files、非辅助来源时，复用比较才忽略其定位/回执变化。其他来源仍比较完整 SourceUnit。当前 DTO/协议字段与版本未改变，旧 profile 因摘要变化走现有全量策略。

捕获阶段的文件系统观察由 config 的 CaptureReader 持有：显式根、逐级 NOFOLLOW、同文件身份读取去重、排序目录/缺失查找和封存复核。不可变 observations 只属于主机任务，不加入语义 profile、worker wire 或图库 schema。根配置、prepared 清单/artifact、Java/Rust/Python 原生清单与控制验证通过 InputReader 接入同一读取器；捕获阶段命中缓存，不再重复读取活文件。外部 Maven 身份只校验 SHA，仍不可升级为内容输入。公共 InputCaptureReader 可接收按单元隔离的 DeclaredLink，核对精确目标、允许根、循环与跳数，并在封存前复核；链接和同路径内容分别保留观察。既有三语言原生清单尚不传入映射，仍拒绝源链接；TS/pnpm 产品接入由 T3 验证。取消由应用任务 token 的只读回调传入；config 不依赖应用层或编译器。

普通/prepared 捕获使用主机内 FrozenUnitView，按 AnalysisUnitId 保存独立 manifest、必要 blob 引用和 ObservationSet。路径操作在配置解析和捕获时均记录实例归属；观察正文共享不可变存储，视图仅通过索引迭代自己的观察。Java 依赖不再因固定 scope 筛选而从 prepared 视图丢失。相同物理根不构成自动合并条件；模块/world/路径及角色仍严格验证。此对象不进入 worker wire、语义 profile 或图库 schema，协议版本保持；执行计划/策略归一仍由 R4 完成。

同一捕获路径有多个实例归属时，Java 既有未填写 world 的行按其显式 module/scope 选择来源，不取路径上的第一个 world。显式非空 world 仍必须精确匹配；无归属、错 module/scope 仍拒绝。仅调整私有候选的来源校验，不修改公开 ID、GRA1 列或物理 schema。

主机 CaptureScope 显式区分 Task 与 Unit，单元上下文仅保留对应后端的输入/语言/模块并共享 TaskConfig；不加入 wire 或 profile 的文件系统观察。普通和 prepared 捕获共用单元构造入口。各现行路径的 RunningUnit 拥有输入守卫、捕获视图和会话状态，替代按数组位置配对；未启动/错后端/外部 manifest 拒绝，启动前核对实例 grant。计划与保守/prepared 启动按后端、稳定实例 ID 排序；普通 Rust+Java 暂沿 Rust 优先的旧增量策略，待统一执行器迁移。

revision 的主机元数据新增 `unit_ownership.version=2`：稳定实例、后端、Ready/FilesOnly、发布语言、模块/world/scope、producer、独立语义 profile/输入摘要、完整发布来源与完整输入及其辅助角色。范围从冻结输入取得，包含零输出单元/来源；辅助输入参与输入摘要，但不能取得发布归属。同一 world/path 的发布来源不能归两个单元。storage 在创建私有候选前校验清单，随 revision.json、catalog 和同一次 CAS 持久化；不改变公开 ID、GRA1 列、图库物理 schema 或 worker 协议。旧 revision 缺清单或只有 v1 仍可读取原事实，语义增量返回 `UNIT_OWNERSHIP_REBUILD_REQUIRED`，必须 `index --full` 建立新清单后切 head。

PlannedUnit 保留 Ready/FilesOnly/Removed；Removed 取自旧清单，不从当前行或 producer 推导。所有单元先静态分片，FilesOnly/Removed 由主机将未发出的 grant 封为可信零用量；份额不借出、不再领取。普通单元也使用显式后端实例 ID。没有 Ready 单元时不启动兜底 Java；禁用全部语言可在已有基线上清除旧归属，首次语义索引仍要求显式语言。普通/prepared 共用逐单元 Full/Delta/Reuse/Removed/FilesOnly 执行及一次候选/CAS；TypeScript 启动工厂仍待生产接入。


主机 LaunchSpec 必须携带任务 InputLease。Process 在启动协议前取得文件/spool/scratch 所有权，回收失败或任务 future/runtime 退出时由独立回收登记继续保有。停止确认包含所属进程组全部非僵尸成员退出及 leader 回收，不能仅以发送 SIGKILL 当作完成。有效 Acked 允许健康进程解除任务租约；错误 ACK 不解除。此为主机生命周期实现，不改变 worker 25 / Java ABI 4 或图 schema。现行三语言的 LaunchSpec 装配和 Java rediscover 已集中到 WorkerService 工厂，只能由公共单元 owner 调用；三语言任意单元合并由公共执行器驱动；TypeScript 执行装配仍待生产接入。

主机按任务固定一次 baseline 并持有 GC 元数据租约；显式全量迁移无需打开旧图。Receiver 绑定单元冻结 manifest 和 v2 的历史输入：替换 owner 的 kind、producer、world/path 必须匹配，图行的 source、world、module/scope 和语言必须落在当前单元范围内。越界返回 `ANALYSIS_UNIT_SCOPE`，接收状态粘性失败，不能获得 VerifiedEnd；原始传输 SHA 先按原行验证；主机随后只将唯一绑定的隐式 world 显式化，投影另算 SHA，只有原始 End 校验成功且投影计数一致才给出投影的 VerifiedEnd。旧 v1 缺少完整历史输入，必须全量重建，不能推测缺失的辅助来源。

主机内部暂存输入区分 Analysis 与 Reused。后者仅在 worker 交付有效 Reused 回执后请求，不能带新行；存储再检查全局及逐单元语义身份、来源与角色。它保留基线的全局诊断/coverage/字典；普通空 delta 不取得这种保留权限。外部 Begin/End 和 CLI 的既有索引模式编码保持。

候选及单元基线 SQL 接续原 host 账户：VM/I/O、连接/cache/排序工作区及派生集合须准入；单元基线计入自身后端，共享合并计入固定首个 host。工作区按同时存活对象预付，已计入 payload 的容量可在同一 scope 内复用，关闭不退还用量或重发 grant。磁盘检查接收匿名/已 unlink 文件及待增长字节，在 xWrite/xTruncate/shmMap 前拒绝超限；首次失败在嵌套映射、后续取消及清理中保持原身份。`host_scratch_bytes` 由宿主执行，不新增 worker/Java ABI 字段，也不改变 graph schema 或公开 ID。
