# JS / TS：typescript-go C ABI 接入实施计划

用途：交给干净的 main 分支从零实施。所有任务从 T0 开始，不依赖预先存在的 JS/TS 探针、来源锁、构建脚本、SDK 或本机产物。先核对 main 的 AGENTS、现行架构/契约和实际代码，再按各阶段出口推进；新增路径和命令均在实施阶段创建。

本计划只定义实现方法、验收条件和避坑要求，不包含已执行记录。执行状态与回执由实施者另行登记到现行文档及新建的证据目录。

## 1. 结论与范围

采用独立 Rust worker 装载 Go 共享库；Go 库内嵌固定版本 typescript-go，并完成语义事实提取。复用现有 capture、worker 协议、计费、GRA1、候选校验和一次 CAS 发布；不把 Go runtime 装入 daemon。

```text
CLI → Rust daemon
        ├─ 配置 / capture / IndexPlan / supervisor
        │
        │ 独立 FD：长度前缀 protobuf
        ▼
     Rust TypeScript worker
        ├─ SDK 校验 / 任务账本 / 取消 / 终态
        │ C ABI：整数句柄 + 有界字节缓冲
        ▼
     Go 共享库（Go runtime 随库装入 worker）
        ├─ 捕获输入的只读 VFS
        ├─ typescript-go：Program / Parser / Binder / Checker
        └─ 自有提取器 → 封存 GRA1 + 有界结果描述符
        │
        ▼
     Rust 校验归档 → Begin / Chunk / End
        ▼
     daemon 私有 candidate → 校验 / fsync → catalog CAS
```

| 决策     | 首版边界                                                                                |
| -------- | --------------------------------------------------------------------------------------- |
| 语言     | JS/JSX/MJS/CJS、TS/TSX/MTS/CTS 和声明文件；两种语言显式启用，共用一个后端               |
| 分析     | 项目上下文中的声明、引用、导入导出、静态调用、继承/实现、诊断、coverage、注释           |
| 语义     | 依赖编译器绑定；类型声明与运行时实现分开，未知目标保留未知                              |
| 输出     | Go 先写封存归档，Rust 有界读取并交付；首版不增加 Go→Rust 热路径逐节点回调               |
| 生命周期 | 每次执行启动新 worker；会话结束后进程退出。跨任务复用留到取得内存和清理证据后           |
| 增量     | 首先全量；随后只做冻结输入与 profile 完全相等的复用。任一变化先保守全量                 |
| 目标项目 | 只做 parse/analyze；不执行 npm/pnpm/yarn 安装、构建、业务代码、配置插件或外部转换器     |
| 后续能力 | Vue/Svelte 虚拟文件、打包器特有解析、框架调用规则、跨函数数据流、细粒度增量另列后续任务 |
| 平台     | 先验证本机 macOS ARM64；Linux 等平台独立验证，不能由本机成功推定支持                    |

Go 的 `c-shared` 是方案 C 的构建方式。共享库仍含 Go runtime/GC，不能据此承诺内存更低或速度更快。输入冻结仍由 Rust capture 负责，Go 只适配只读视图。

## 2. 当前代码依据与实际缺口

| 当前入口                                                                                                                                                                | 已有能力 / 本次要处理的缺口                                                                                                                                                               |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Java native](../crates/java-worker/src/native.rs)、[actor](../crates/java-worker/src/actor.rs)                                                                         | 已有动态装载、专用线程、有界调用队列、异步完成 FD、分块读取。Graal isolate、Java 符号、GC、SDK 校验属于 Java，不能整体改名复制                                                            |
| [Java product](../crates/java-worker/src/product.rs)                                                                                                                    | 已有 Hello/Discover/Analyze、累计用量转交与归档交付；配置、producer、缓存及请求类型均有 Java 耦合，不是现成通用 host                                                                      |
| [result](../crates/java-worker/src/result.rs)、[delivery](../crates/java-worker/src/delivery.rs)                                                                        | 已校验 GRA1 路径、长度、EOF、计数、SHA，并有界切行；结果 envelope 绑定 Java analysis 版本。OutputLimits 已定义于公共 graph_archive，Java 路径只是 re-export；后续共享设施直接使用公共类型 |
| [contracts](../crates/contracts/src/lib.rs)、[frame budget](../crates/contracts/src/frame_budget.rs)、[analysis budget](../crates/contracts/src/analysis_budget.rs)     | 现有 wire 准入、控制通道、半帧恢复、用量核对可复用；语言编码仅接受现有三种语言                                                                                                            |
| [capture](../crates/config/src/capture.rs)、[CapturedBlob](../crates/config/src/capture/blob.rs)                                                                        | 已有 manifest、内容摘要和任务拥有的 spool；没有 JS/TS 配置、解析输入闭包和路径映射                                                                                                        |
| [输入准备配置](../crates/config/src/inputs.rs)、[准备用例](../crates/application/src/inputs.rs)                                                                         | 已有显式 prepare 和多工作区清单；provider 分支、语言过滤、缺输入降级均需扩展                                                                                                              |
| [IndexPlan](../crates/application/src/index_plan.rs)、[analysis unit](../crates/application/src/analysis_unit.rs)、[执行](../crates/application/src/index_execution.rs) | 已有按实例准备和生命周期归属；部分策略仍专门处理 Rust/Java 组合，不能再复制一套组合排列                                                                                                   |
| [worker](../crates/application/src/worker.rs)、[WorkerService](../crates/application/src/worker_service.rs)                                                             | worker 可执行文件、Discover 配置、producer 检查、阶段统计及健康池有语言分支；需登记 TS 后端，首版不进入 Java 健康池                                                                       |
| [配置 schema](../crates/config/src/schema.rs)、[预算参与者](../crates/model/src/analysis_allocation.rs)、[查询策略](../crates/model/src/language_policy.rs)             | 都只有 Java/Rust/Python；JS/TS 的公开语言标签与共用后端参与者需要分开                                                                                                                     |
| [DDL](../crates/storage/src/anatomist-schema.sql)、[紧凑附表](../crates/storage/src/compact-meta.sql)、[图库版本](../crates/storage/src/graph_store.rs)                 | 声明种类/type_kind/visibility 有约束；不能只向 language 写入新字符串。Rust 增量状态表不作为 TS 状态表使用                                                                                 |
| [bundle](../crates/contracts/src/bundle.rs)、[构建入口](../justfile)、[架构门禁](../scripts/check-architecture.py)                                                      | bundle 有 Java 专属字段；Go SDK 需要明确的自身身份和验证，普通 Rust 构建不应隐式要求 Go/JDK                                                                                               |

当前常量：daemon 7、worker 24、graph model 6、Java analysis 13、Java C ABI 4、图库物理/逻辑 13/36。实施时重新核对；不提前占用下一版本号。

## 3. 模块与契约落点

下列均为拟新增路径。T0 先创建独立诊断探针与来源锁，再建立生产 overlay、共享设施、worker 和 protobuf。诊断探针使用独立 ABI 前缀和 DTO，不占用生产契约版本。

```text
lang-typescript-adaptor/
  upstream.lock.json        上游 URL/commit、Go 工具链、依赖和标准库身份
  probe/                    T0 诊断 overlay、独立 Rust host、header 和双文件真值
  overlay/                  放入固定上游同一 Go 模块的桥与提取器
  patches/                  确有必要的最小上游修改及理由
  include/codeana_ts.h      自有 C ABI 定义
  fixtures/                 自建语义真值与 Go 核心验证

crates/typescript-worker/   Rust host、SDK 校验、C ABI 装载、异步控制
crates/worker-support/      仅承载确有两个调用方的 host 共享设施
proto/codeana/typescript/v1/analysis.proto
                           内部分析请求、结果描述符和结构化失败
```

| 边界        | 约定                                                                                                                                                                           |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Go internal | 在固定上游模块内叠加桥代码，再执行 `go build -buildmode=c-shared`；普通外部 Go module 加 replace 不能绕过 internal。构建目录在 target，不修改用户的上游 checkout               |
| Rust 依赖   | 新 worker 依赖 model/contracts 和必要的共享设施；daemon/application 不依赖 Go AST、共享库或 Java worker 二进制 crate                                                           |
| 双语言      | 公共事实使用 `javascript` / `typescript`；后端统一使用 `AnalysisLanguage::TypeScript` 和一个 producer。输入准备将同一项目上下文合并为一个执行/预算实例，不能因两种标签分析两次 |
| 显式启用    | 两种语言分别配置 enabled；未启用语言不得静默发布语义。必要辅助文件须有显式辅助角色与 coverage 规则；同一 Program 内允许有证据的 JS↔TS 绑定                                     |
| world       | 由稳定项目上下文、module/scope 和配置角色定义；内容摘要与工具链进入 profile，不把随机 scratch 路径或临时地址写入身份                                                           |
| 内部模型    | 编译器对象不跨 ABI。只输出自有 DTO、状态、GRA1；不为首版构造可公开查询的整份 AST/type graph                                                                                    |
| 编码        | 内部分析契约使用类型化 protobuf；从同源 .proto 生成 Go/Rust 绑定，设置生成包路径，不手写第二套 wire codec，不复用 Java JSON 请求                                               |
| SDK         | 独立 TS manifest，登记 worker/图协议、TS ABI、内部请求版本、上游提交、Go 版本/构建参数、OS/arch、库/header/标准库 SHA。共用校验工具不强迫 Go 填写 Java JDK/GC 字段             |

### 3.1 C ABI 与生命周期

自有符号前缀为 `codeana_ts_`，TS ABI 独立版本化；不使用 Java 的 ABI 4、isolate 指针或 GC 参数。最终签名在 T2 固定，接口职责如下：

| 接口族                                          | 要求                                                                                                                  |
| ----------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `abi_version` / runtime identity                | 装载后分析前核对；与 manifest 严格匹配                                                                                |
| `session_create` / `input_chunk` / `seal_input` | 固定宽度整数句柄；输入按上限接收，返回前完成必要复制，Go 不保留 Rust 缓冲指针                                         |
| `set_completion_fd` / `analyze_start`           | start 有界返回，计算在 Go 内异步进行；先复制并登记通知 FD，终态可见后发送一次通知                                     |
| `poll_event` / `read_result_chunk`              | 通知后读取终态；按调用方容量写入 Rust 提供的缓冲，明确错误与 EOF，结果是有界描述符而非整个图 JSON                     |
| `cancel`                                        | 线程安全、短调用、幂等；提取器协作检查，编译器内核不能及时取消时由 supervisor 终止 worker                             |
| `publish_ack` / `session_close`                 | ack 表达已发布确认，不能反向改变图；关闭须等待所属任务停止后释放对象/FD/scratch 引用，不与仍运行的 goroutine 竞态释放 |

```text
Created → Feeding → Sealed → Running → Complete / Failed / Cancelled
                                             │
                               校验结果 → PublishedAck（成功路径）
                                             │
                                           Closed
```

输入失败、取消和异常也必须能转入清理。恢复不了的进程由 supervisor 回收，不能在后台继续运行。

- Go 对象只存在 Go 侧句柄注册表中；跨 ABI 仅传整数和有界字节，不返回 Go string/slice/map/AST 指针。
- 每个导出入口和后台计算 goroutine 都建立 panic 终态处理；可恢复 panic 转结构化失败，不承诺拦截 fatal runtime、OOM 或原生崩溃。
- Rust 保持单会话串行控制，可沿用专用 owner 线程；这是 host 的生命周期策略，不声称 Go 具备 Graal 的线程绑定要求。
- Go runtime 与动态库保持到 worker 进程退出；不依赖 dlclose 重置 runtime。首版不做任务间库重载、池化或强制 GC 充当释放证明。
- 完成通知采用现有非阻塞 socket 机制，检验重复 start、重复终态、EOF、错误字节、FD 提前关闭。不能把现有 Java actor 的 stop 行为直接当作 Go 协作 cancel。

### 3.2 输入闭包与冻结视图

```text
外部已准备依赖
  → 用户显式 index --prepare
  → 同一编译器内核的静态输入发现
  → 输入清单（文件/目录/配置/解析角色及摘要）
  → Rust capture 再校验并封存
  → Go VFS 仅读封存视图
```

prepare 由 CLI 本地启动新 worker 的显式准备入口，调用相同 Go 库；daemon 不执行发现工具。prepare 只读配置/文件、做模块解析，不调用包管理器、目标脚本或插件。分析阶段不重新发现活工作区。

| 输入         | 必须记录 / 验证                                                                                                                              |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| 配置         | tsconfig/jsconfig、extends 链、project references、有效 compilerOptions、root files、JS 文件准入及 checkJs 策略；配置错误保留诊断            |
| 项目文件     | 源码、声明文件、选定 MAIN/TEST 发布归属；辅助分析集合与发布集合分开。allowJs/checkJs 不静默改写原项目配置                                    |
| 模块上下文   | package.json 的 exports/imports/type/types/typesVersions、paths/baseUrl、module/moduleResolution、JSX 模式、显式环境类型入口；交给编译器解析 |
| 依赖         | 实际参与模块解析的源码/声明/metadata 与标准库；固定 package 根、顺序、版本证据和内容，不复制无关整个 node_modules                            |
| 文件系统语义 | 逻辑路径、大小写、目录枚举、symlink/realpath 与失败查找；将 package metadata 和目录缺失也纳入失效依据                                        |
| references   | 逐 tsconfig 建立上下文，区分项目源码重定向与已生成 .d.ts；缺必要构建输出给建议/partial，不自动执行 tsc -b                                    |
| 包管理模式   | 首版以可枚举的文件型依赖为准；pnpm symlink 需有真值 fixture。需要执行 loader 的 PnP、无法冻结的外部路径或解析模式明确拒绝/报告缺口           |

封存前校验文件摘要与路径视图；目录/失败查找不一致时要求重新 prepare。必要输入缺失必须是显式缺口，不能静默读本机全局类型缓存或路径祖先的配置。

优先通过 manifest/blob 驱动 VFS 保留原始逻辑路径，以免 scratch 改变路径别名和包边界。磁盘恢复如用于调试，也必须受同一清单约束；不允许读取后回退 live FS，不把只读权限或 hardlink 当成不可变内容证明。标准库也按 SDK 身份固定并保持任务期内容不变。

工作区 capture 仍是逐文件捕获，不宣称同一物理时刻的原子快照；需要精确 commit 时从对应 Git 内容构造输入。

### 3.3 图语义与查询

| 事实    | 提取 / 映射要求                                                                                                                                                                                                |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 声明    | 先建声明表，再取关系；覆盖 module/class/interface/function/method/constructor/property/variable/parameter/type_alias/enum/namespace/import 等，先确定公共 declaration_kind/type_kind/visibility 映射再修改 DDL |
| 身份    | 自有稳定 ID；处理匿名函数、解构、同名遮蔽、重载与声明合并。编译器 ID、指针、临时绝对路径不得输出；值/类型空间和合并符号保留多声明来源                                                                          |
| 引用    | 按语法角色与编译器绑定提取，区分读写及类型使用；别名归一与导入事实分开。GetSymbolAtLocation 的结果需按节点语境校验，不能每个 identifier 都生成读取边                                                           |
| 调用    | 明确区分直接实现、声明目标、外部目标和 unknown。函数参数、接口签名、.d.ts、union/any、重载错误恢复不能凭名字或签名候选补成精确实现                                                                             |
| JS 特性 | JS 推断/JSDoc、CommonJS、对象成员、动态属性保留编译器可证范围；JSX 先交付组件引用，不把每个 JSX 节点伪装为源码直接调用                                                                                         |
| 关系    | extends/implements 来自编译器符号与类型；结构类型满足关系不等于运行时继承/override，也不引入 Java CHA                                                                                                          |
| 位置    | 公共字节区间为原始 UTF-8 半开范围，行列为一基 UTF-16；Go AST/API 坐标分别确认，定位声明真实 token，不把前置 trivia 算入名字                                                                                    |
| 注释    | 使用同内核 scanner/JSDoc 证据，产出正文映射及 documents/enclosing_callable；缺语言规则时不靠最近声明猜归属                                                                                                     |
| 质量    | 语法恢复、缺模块、辅助依赖缺口和提取不支持单独记录；普通类型错误不自动等同所有绑定失败。影响不明则标上下文缺口，strict 拒绝 partial                                                                            |
| 查询    | 新增两种语言的显式策略，静态调用使用精确端点与证据；CHA/runtime 明确 unsupported。查询只读发布图，不调用 Go 补算                                                                                               |

同一上下文的 JS↔TS 边按编译器绑定交付；不按语言字符串相同与否裁掉真实边，也不向 Java/Rust/Python 按同名补边。重复调用点、相同文本出现次数、hash/ordinal 必须保留。

### 3.4 预算、归档与发布

| 环节     | 要求                                                                                                                                 |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| 账本     | 复用 AnalysisTaskMeter / AnalysisContext、现有 host/worker 静态份额及同一截止时间；JS/TS 共用后端实例不重复领取                      |
| FFI 转交 | Rust 先预留请求编码、Go 解码及独立控制/失败回执额度，再把累计 seed 交给 Go；Go 活跃期间 Rust 不并发修改同一累计账户                  |
| Go 计量  | 输入/VFS、自有遍历/DTO、编码/归档在分配前预留 work/nodes/payload/depth；结构化 protobuf 解码也要先做 wire 扫描准入，不能仅检查总字节 |
| 内核边界 | 不宣称对编译器内部每步精确计费；内核由总截止时间、进程树 RSS 约束。Go 内存限制参数只作软提示，不能冒充 RSS 硬上限                    |
| 返回     | Go 在完成结果编码预留后封账，Rust 核对累计回执再继续归档解码/传输计费；缺回执或水位不可信只能记录 unknown，不认可成功                |
| 封存     | Go 逐条写 GRA1，记录 bytes/count/SHA；结果描述符只引用本任务 scratch。Rust 独立校验路径、文件类型、长度、EOF、行类型和 SHA           |
| 交付     | 复用共享有界队列及帧上限；Begin/Chunk 仍为 provisional，只有 VerifiedEnd 后才允许候选完成                                            |
| 多语言   | 接入公共 analysis units，逐单元验证后合并、一次 CAS；任一语言失败保留所有原 head。停止所有 worker 后才删除输入与 scratch             |
| 提交后   | ack、报告、断连和清理失败只记录实际状态，不能撤销已提交 revision                                                                     |

## 4. 实施顺序与阶段出口

```text
T0 基线/真实语义探针 → T2a 生产契约草案 → T1 共享拆分 → T2b 组件闭环
                                      ↓
T3 冻结输入 → T4 图模型/基本事实 → T5 语义/注释真值
                                      ↓
T6 产品及多语言 → T7 故障/预算/复用 → T8 实际包与收口
```

### T0：固定依据，确认方案 C 的实际可构建性

- [ ] 重新核对实施时 main 的 HEAD、AGENTS、架构/契约和版本；先运行 `just test-java` 建立基线，保留失败日志，修复环境问题后复验；基线未通过不得进入后续改造。
- [ ] 按第 7 节固定来源 commit、TypeScript 子模块、标准库、Go 精确版本、C 编译器及 macOS SDK，新建 `lang-typescript-adaptor/upstream.lock.json`；登记源文件 SHA、overlay/patch 理由与许可。
- [ ] 新建 `probe/`：Go 桥叠加在固定上游同一模块内，Rust host 使用独立 Cargo workspace；不修改产品 workspace 或提前拆 Java 设施。
- [ ] 用真实 Parser/Program/Checker 构建 c-shared，Rust 动态装载后分析双文件自建 fixture；包含导出函数、别名导入、两个重复调用现场、中文/emoji、CRLF 和无末尾换行。声明目标、调用签名及 UTF-8/UTF-16 范围使用人工 expected，不能从实际输出自动生成真值。
- [ ] 使用独立 `codeana_ts_probe_` 前缀和探针 ABI 1，实现版本查询、同步分析、整数结果句柄、有界结果分块读取和关闭。允许具名诊断 DTO 使用标准 JSON codec；不返回 Go 对象指针，不沿用 Java JSON 请求，不将探针接口视为生产契约。
- [ ] 验证空/错误/超大输入、坏/关闭句柄、重复关闭、单字节 canary、多块读取及 EOF；捕获后修改/删除临时原文件、调用返回后覆盖 Rust 输入缓冲，语义结果仍须符合 expected。
- [ ] 将手写固定宽度 C 声明与 cgo 实际生成 header 一起编译，记录导出符号和动态依赖；分别从 Rust 主线程、工作线程装载并调用。保持库到进程退出，不使用 dlclose 重置 Go runtime。
- [ ] 在独立子进程内验证 runtime 初始化后的 SIGINT/SIGTERM 退出及无残留进程组；用空 PATH 运行已构建探针，验证不依赖 Node、Go 或开发源码补输入。
- [ ] 新建统一入口 `scripts/test-typescript-probe.py`，串起 Java 基线、来源准备、标准库生成、共享库/host 构建、header、fmt/clippy 和上述验证；子进程超时必须回收所属进程组，每次运行使用新的证据目录。

出口：本机真实共享库被 Rust 调用并返回可核对的语义结果；构建链和内核可用性有证据。未完成则停留在 T0，不能把架构推演写成可用产品。

依赖准备与构建要求：

| 环节         | 实现方式 / 需规避的问题                                                                                                                                                      |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 来源         | Git 获取固定 commit，从该提交读取 TypeScript 子模块 gitlink，再获取对应提交；不能只下载父仓库归档后假定子模块已齐全。优先同模块 overlay；确需上游 patch 时单独登记理由与 SHA |
| 标准库       | 从固定子模块执行 `go generate ./internal/bundled`，枚举生成文件、记录数量和逐文件 SHA，嵌入共享库；不要运行全仓库 `go generate ./...` 引入无关 Node 工具                     |
| macOS C 编译 | `CC` 使用 `xcrun --find clang`，同时设置 `SDKROOT=$(xcrun --show-sdk-path)`；仅指定 Clang 绝对路径可能缺系统头文件。锁定 SDK 版本并记录 SDKSettings SHA                      |
| Go           | `CGO_ENABLED=1`、`GOTOOLCHAIN=local`；使用 `-mod=readonly -trimpath -buildmode=c-shared`，记录实际依赖和源文件摘要                                                           |
| Java 基线    | 构建的 `CODEANA_JAVA8_HOME` / `CODEANA_JAVA17_HOME` 与 fixture 的 `ANATOMIST_TEST_JDK_8/17/25` 分别显式设置，避免自动选中失效的本机别名                                      |
| 下载         | 下载需有超时和明确错误；默认沿用现有代理，提供可选 `--direct-network` 仅移除该次子进程的代理变量，不修改系统代理；下载后仍校验来源身份，不能因网络失败静默换版本或镜像       |
| 中断与复验   | Git 中断可能遗留锁；先回收所属进程，再换新目录或确认无活跃进程后处理锁，不盲删锁。保留失败日志；Rust host 以独立 workspace、绝对 manifest 路径和 Cargo.lock 构建/检查        |
| fixture 字节 | CRLF、BOM 和无末尾换行用原始字节保存并校验；用 `.gitattributes` 防止 checkout 自动换行破坏 expected 偏移                                                                     |

诊断探针与生产实现的边界：

| T0 验证范围                                     | 生产阶段必须另行实现与验证                                                                                            |
| ----------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| 两个 TS 文件中的命名函数、别名和普通调用正例    | JS、重载、声明合并、`.d.ts`、接口、动态目标和错误恢复分别建立真值；不能仅凭 GetResolvedSignature 的声明发布精确实现边 |
| 可为诊断简化身份                                | `file#name` 不能作为生产稳定公开 ID；匿名、同名遮蔽、类型/值空间与合并声明须在 T4 建模                                |
| 只读内存 VFS、固定标准库、捕获后修改/删除原文件 | T3 实现 prepare 输入闭包、目录/失败查找失效、symlink、依赖包、project references 和配置变化后的重捕获                 |
| 同步调用、整数结果句柄、有界诊断 DTO            | T2 实现生产 protobuf、异步完成 FD、Running 状态取消/关闭、用量回执和 GRA1；不能将探针接口改名前缀后直接当作生产 ABI   |
| Rust 工作线程及 runtime 初始化后的进程信号退出  | 分析完成后发送信号，只能验证基础退出；计算中协作取消、超时、panic/fatal/崩溃须在 T2/T7 单独注入验证                   |
| 小 fixture 上限与空 PATH 运行                   | 累计预算、RSS、实际包和容量/性能分别验收；共享设施或生产协议改动后执行受影响 Rust/Java/Python 门禁                    |

### T1：拆出确实可复用的 Rust host 设施

- [ ] 依据 T0 探针和 T2 生产契约草案，从 Java actor/result/delivery 拆出确有需要的 owner 队列、通知 FD、归档读取/行交付，放入新 worker-support；复用 contracts::graph_archive::OutputLimits，contracts 继续拥有 wire、图行和准入规则。
- [ ] 共享设施接受类型化参数与窄接口；Java JSON envelope、Graal isolate、JVM 诊断后端、Java SDK 验证、derived cache 留在 Java。
- [ ] 不复制 product.rs 大状态机；先让 Java 调用共享设施，再让新 worker 使用。同步 Cargo 依赖门禁、导入、打包与测试覆盖映射。
- [ ] 验证 Java fixture、受影响 Rust/Native 生命周期、归档截断与计费失败路径，证明拆分保持现有行为。

出口：Java 已迁入共享设施且回归通过，接口仅覆盖 T2a 草案确定需要的能力；T2b 接入第二个实际调用方，不提前建设无人使用的通用框架。

### T2：固定 TS ABI、内部模型和预算交接

本阶段分为两个出口：完成 T0 后先做 T2a，再做 T1，最后进入 T2b。

| 子阶段         | 必须产出 / 出口                                                                                                                                                             |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T2a：T1 前完成 | 生产 header 草案、同源 protobuf 请求/结果/失败/用量定义、会话状态与所有权表、SDK 身份字段，以及与现有 host 账本/通知/归档的对接清单；明确哪些设施已有公共实现、哪些确需抽取 |
| T2b：T1 后完成 | 独立生产 worker 接入共享设施，取得“捕获 fixture → Go 异步分析 → 累计回执 → GRA1 → Rust 校验”的成功、失败和取消证据                                                          |

- [ ] 建立 overlay、可重放构建脚本、独立 TS SDK manifest 和 Rust typescript-worker，使用 T1 共享设施；普通 `cargo build --workspace --locked` 无需先运行 Go 构建，真实 ABI 门禁由显式入口运行。
- [ ] 固定 3.1 的函数签名、所有权、状态和错误码；Go 入参复制、句柄校验、结果缓冲写入和 FD 归属做跨语言边界测试。
- [ ] 定义并生成内部 protobuf 请求/描述符/失败/用量模型；C ABI 与内部请求各自版本化，预留字段号而非沿用 Java 字段。
- [ ] 接入累计 seed、deadline、预解码准入和结构化失败；完成“捕获 fixture → Go 异步分析 → GRA1 → Rust 校验”的组件闭环。
- [ ] 验证 cancel、panic、运行中 close、无效句柄、错误 ABI/库摘要、超大输入、重复通知与 EOF；首版任务结束即退出进程。

出口：编译器对象没有跨 ABI，成功/失败/取消都有可核对终态；Go 无可信回执的失败不会冒充计量完整。

### T3：输入准备、配置与冻结 VFS

- [ ] 在 config 增加 JS/TS 显式开关、有效输入描述、语义摘要和预算；注册 prepare provider，合并同一 tsconfig 的双语言入口并检查冲突。
- [ ] Go 准备入口使用同内核读取配置/解析模块，导出 3.2 的闭包和缺口；保留外部依赖准备建议，禁用插件、自动类型安装和目标构建。
- [ ] 扩展 capture 与 application 的输入构造，校验清单、目录/缺失项、摘要和辅助角色，按 blob 恢复 VFS；阶段失败及时清理。
- [ ] 覆盖 tsconfig extends、paths、ESM/CJS、node 类型、pnpm symlink、项目引用、同文件多上下文及缺声明产物；无法支持的模式明确报错。
- [ ] 验证捕获后修改/删除活文件仍读取捕获内容；配置、依赖或原失败查找变化拒绝旧准备清单，重新 prepare 后结果更新。

出口：相同封存输入在不同 scratch 路径得到相同公开身份与事实，分析无清单外 live FS 回退。

### T4：图模型、存储与最小语义事实

- [ ] 定义 TS/JS 声明、可见性、类型/值空间、合并符号、重载和外部目标映射；新增 model 类型、producer/profile 和两种语言的显式能力策略。
- [ ] 同步改 anatomist-schema.sql / compact-meta.sql、生成器及 compact-schema.sql；校验入库、引用、语言/world 归属与对外投影，不把 TS 类型硬塞成 Java method/class。
- [ ] Go 先完成确定性声明表，再实现引用、直接调用、导入导出及 provider/coverage/diagnostic 行；从同源 graph.proto 生成 Go 绑定。
- [ ] 核实图 hash/ordinal、替换 owner、source 与声明范围；新增能力所需版本按第 5 节同步修改。

出口：最小真实图通过公共 storage/query 读取，声明 ID 和静态目标符合人工真值；不是仅有 parse 成功。

### T5：补齐首版语义与注释真值

- [ ] 按 3.3 补齐成员、构造器、继承/实现、匿名/箭头函数、解构、默认导出、重导出、类型导入与 CommonJS。
- [ ] 用 fixture 明确 interface/.d.ts/重载签名与实现的区别；验证 any/union、动态 require/属性、函数参数和错误恢复不会产生假精确边。
- [ ] 接入 JSDoc、普通注释、正文到原文映射和语言归属；测试 UTF-8/UTF-16、BOM、CRLF、无末尾换行、前置 trivia 及重复调用点。
- [ ] 锁定实际支持的 compilerOptions/模块解析模式和未支持清单，Checker 按固定内核要求获取/释放，不混用不同 Checker 的 Type；首版按上下文串行提取。

出口：所有已声明能力有绑定目标、位置和缺口真值；无证据的动态关系仍可定位但保持 unknown。

### T6：接入产品与多语言发布

- [ ] 在 worker.proto Discover 增加 TS 描述符并验证与其他描述符互斥；补齐 contracts 的 wire 准入、预算语言转换及新 worker 的严格握手。BuildContext 的后端语言为 typescript，能力明确允许的 JS/TS 源标签；核对 producer、上游提交、TS ABI、内部版本和 SDK 摘要，不能按任意 metadata 选择后端。
- [ ] 扩展 Worker::start_language、SDK 就绪检查、IndexPlan、PreparedInput、分析参与者、进度/报告和实例资源分配；JS/TS 合并为一个后端执行单元。
- [ ] 将 TS 走公共 Conservative/Prepared 编排；只抽实际需要的语言组合公共部分，避免增加 TS×Java×Rust×Python 的独立策略。
- [ ] 完成单 TS、单 JS、JS+TS、独立多 TS 上下文和 Java/Rust/Python 组合；验证项目删除到零输出、普通/prepare 入口一致、FILES/text-only 降级。
- [ ] 验证真实 CLI 的实体搜索、源码、引用、caller/callee、调用点、注释、报告、snapshot/diff 和历史续页；help/doctor 正确展示支持、就绪、partial 和 unsupported。

出口：所有单元通过验证后只提交一次；任一单元失败不发布半图，旧 revision 和冻结查询保持。

### T7：故障、资源与保守复用

- [ ] 验证取消/截止时间、Go panic、fatal 退出、真实 native 崩溃、归档截断/篡改、控制断连与提交竞争；使用隔离 fixture 进程，不破坏用户服务。
- [ ] 验证输入、解码、遍历深度、事实数、结果字节、scratch、RSS 及大行上限；混合任务账本静态分配，重试不续期/重置额度。
- [ ] 校验失败前已知消耗保留、最终未知水位明确；控制通道在业务预算耗尽后仍可发送终态；ack 失败不撤销提交。
- [ ] 输入和 profile 完全相同时才走现有 Reuse/Reused 与候选复用；源码/目录/缺失查找/依赖/工具链/规则任一变化全量，处理删除及零输出替换。
- [ ] 不接入 Rust 专属 SOURCE_STATE/ANALYSIS_DEPENDENCY 充当 TS 增量，也不在本阶段引入跨任务常驻 Program。

出口：资源/故障回执、旧 head 保留、所有线程/FD/scratch 归属均有实际证据。

### T8：实际分发、文档和收口

- [ ] 新增并登记 build/test/package/release 的 just 入口与脚本；区分已有命令和新增入口，包内含 worker、Go 库、标准库、manifest、许可与校验和。
- [ ] 固定安装版本目录，校验后原子安装，禁止活跃 worker 使用中的库替换；开发路径、安装路径、发行路径都核对身份，拒绝旧协议/错架构/坏 SHA。Java SDK 保持独立安装，release 测试暂存/恢复原 Cargo 路径，不能混装开发产物冒充实际包。
- [ ] 执行第 6 节门禁；独立 home 从实际包运行 JS/TS 与混合 fixture，验证不依赖开发源码、Node、全局 types 或自动下载工具链。
- [ ] 更新 AGENTS、docs/architecture、contracts、configuration、config 示例、inputs/indexing/query/runtime、TS worker 模块、分发/覆盖映射和当前实现；仅把实际取得证据的范围写成完成。
- [ ] 逐项勾选、列出失败/跳过/未覆盖平台。性能按固定自建 fixture 单轮取证，保留原失败；不将 C ABI 推定为比 IPC 更快。

出口：本机当前实际包通过首版声明范围；跨平台、细粒度增量、运行时动态分析和容量稳定性能各自保留未完成边界。

## 5. 版本与兼容修改规则

| 对象                                           | 实施规则                                                                                                                                           |
| ---------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| worker 协议                                    | 新 Discover 描述符、语言参与者及能力校验涉及全体生产/消费端；从实施时版本递增，所有 worker 同步重建，混装在分析前拒绝                              |
| TS ABI / 内部分析 / 输入清单 / SDK             | 各自首版独立版本；每个成功请求必须带明确版本和语义身份，缺失不默认接受                                                                             |
| graph model / 物理逻辑 schema / GraphSemantics | 新语言声明约束和可观察语义按实际修改递增；同步 DDL 生成器、门禁、导入和 package 覆盖。旧库按当前规则要求 index --full，新 revision 成功后才切 head |
| Java ABI / Java analysis / javac model         | 不因新增 Go 库而递增；若共享拆分实际改变其契约则单独处理，不能给 TS 假用 Java 的版本号                                                             |
| daemon / 公开投影 / 报告                       | 仅当公开输入/能力/记录/状态契约实际变化时递增，并同步客户端、报告和测试；不把新增 worker 等同必然改变所有版本                                      |
| 公开 ID                                        | 既有三语言算法保持；新语言身份有 fixture。执行预算、临时目录、内部整数主键不进入公开身份                                                           |

## 6. 验证矩阵与完成标准

仅使用本仓库自建 fixture；不以 iopengw 或其他业务项目作为前置。以下区分 main 的现有入口与必须新建的入口；新增命令只有在对应阶段实现后才能执行，不能假定已有探针或本机回执可复用。

| 顺序 | 入口 / 产物                                                                                                  | 证明范围                                                                                           |
| ---- | ------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| 1    | 已有 `just test-java`                                                                                        | Java fixture 基线优先；共享拆分后复验相关链路                                                      |
| 2    | T0 新建 `python3 scripts/test-typescript-probe.py`                                                           | 固定来源、Java 基线、标准库、真实 c-shared、人工语义真值及探针 ABI/线程/信号；不能替代后续生产门禁 |
| 3    | 拟新增 `just test-typescript-core`                                                                           | Go fixture、完整绑定/范围真值、生产 C ABI 生命周期和预算/异步/GRA1 组件闭环                        |
| 4    | 已有 `just test-rust`                                                                                        | 架构依赖、生成 DDL、workspace、图/文本、clippy；新增 Rust host 单测并入                            |
| 5    | 拟新增 `just test-typescript`                                                                                | 真实产品 CLI、预算/故障、JS↔TS、多上下文和一次发布                                                 |
| 6    | 已有 `just test-java-native`、`just test-python` 及受影响组合                                                | 共享设施和全 worker 协议升级后的既有语言回归；不得用历史回执替代改造后验证                         |
| 7    | 拟新增 `just package-typescript /absolute/new/package`、`just test-typescript-release /absolute/new/package` | 在实施阶段生成的实际分发包上验证全部首版核心场景、安装/错版本/崩溃及混合路径                       |

T0 脚本应提供以下目标调用方式；先创建脚本和所需源码，再从仓库根目录运行：

```sh
# 默认自行准备固定来源并运行 Java 基线，不依赖预先生成的产物。
python3 scripts/test-typescript-probe.py
# 网络环境需要直连时，显式选择；不修改系统代理。
python3 scripts/test-typescript-probe.py --direct-network
```

脚本须提供 `--evidence` 选择新的 target 下证据目录，拒绝覆盖已有目录。可提供 `--source` 复用本仓库 target 内的 checkout，但必须重验 commit、子模块、工作区状态、许可证与标准库 SHA。可提供 `--java-baseline` 复用实施过程中生成的成功回执，但必须核对同 HEAD、基线输入和原始日志 SHA；默认行为仍是重新执行 Java 基线，复用不计作新执行。

必备真值矩阵：

| 类别       | 最少覆盖                                                                                                                      |
| ---------- | ----------------------------------------------------------------------------------------------------------------------------- |
| 输入       | 单文件无依赖、JS/TS 混合、extends/paths、ESM/CJS、exports 条件、pnpm、references、多个 tsconfig、辅助 .d.ts、缺模块、失效清单 |
| 声明/引用  | 重载、namespace/声明合并、泛型、类型/值同名、重导出/别名、默认/匿名导出、对象方法、解构、遮蔽、跨 JS/TS                       |
| 调用       | 普通函数、方法、new、箭头、高阶参数、接口/.d.ts 仅声明、union/any、可选调用、动态属性、错误恢复；分别断言精确/声明/外部/未知  |
| 注释/源码  | JSDoc、行/块注释、归属、正文映射、中文/emoji、BOM/CRLF、无末尾换行、历史 source、多次相同调用                                 |
| 产品一致性 | 全量、修改后重算、未变复用、删除/空输出、strict、混合失败原 head、diff/快照/续页、ack 后失败                                  |
| ABI/资源   | 坏句柄、错 ABI/SDK、并发 cancel、终态 FD、运行中 close、panic/崩溃、解码放大、超限、RSS/scratch、进程树回收                   |

各阶段保存命令、HEAD、工具链、SDK SHA、输入摘要、配置/预算、原始日志和 receipt 到唯一新建的 `target/typescript-c-abi/<run-id>/`。阶段文件和性能记录不覆盖失败。

性能只在正确性通过后，固定一组自建 fixture、独立空应用状态、预算和实际产物做一轮，记录索引/查询、同一时刻进程树 RSS、GRA1、图库大小和语义摘要；不分 Native 冷热，不反复择优，不宣称稳定性或方案 A/C 对比收益。

## 7. 上游来源与初始构建基线

T0 从以下固定来源开始构建，创建来源锁并独立验证。优先使用 typescript-go `89d5d5b2849a0db0957065889ca58536fa6d2e4a`，按其 Git tree 核对子模块指针 `5848bc5157b22ff7f4e3369f4645a514a433b15f` 后获取对应 TypeScript 源码。归档仓库 README 指向 TypeScript 原仓库，不能拿 sibling HEAD 或最新文档代替选定源码；如需换源或升级，显式修改锁定依据并重跑 T0。

来源锁应包含仓库 URL/commit、子模块 commit、Go 依赖锁摘要、许可证、标准库生成步骤及逐文件 SHA、工具链和构建参数、overlay/patch 身份。T2 另建生产 SDK manifest，记录实际协议/ABI 兼容性与包内产物摘要，不把来源锁当作 SDK 已就绪的证明。

| 依据 / 默认选择                                                                                                                         | 实施要求                                                                                                                                            |
| --------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| [固定 go.mod](https://github.com/microsoft/typescript-go/blob/89d5d5b2849a0db0957065889ca58536fa6d2e4a/go.mod)                          | 使用 Go 1.26.2，核对上游 Go 版本要求与依赖；禁止自动切换工具链                                                                                      |
| 初始开发工具链                                                                                                                          | Rust 1.96.0、Apple Clang 21.0.0（clang-2100.4.1.1）、macOS SDK 27.2，darwin/arm64；执行前检查可用性并写锁。替代版本须显式登记、重新验证，不静默回退 |
| 标准库来源与构建                                                                                                                        | 从固定 TypeScript 子模块生成 `.d.ts`，记录文件集合、数量和逐文件 SHA，再嵌入共享库；运行时不从用户全局环境补标准库                                  |
| [固定 Program](https://github.com/microsoft/typescript-go/blob/89d5d5b2849a0db0957065889ca58536fa6d2e4a/internal/compiler/program.go)   | Program、Checker 获取/释放、项目引用与解析上下文；不是公开稳定 C ABI                                                                                |
| [固定 CompilerHost](https://github.com/microsoft/typescript-go/blob/89d5d5b2849a0db0957065889ca58536fa6d2e4a/internal/compiler/host.go) | 可注入 vfs.FS；无需自行重写 TS 模块解析器                                                                                                           |
| [Go build modes](https://pkg.go.dev/cmd/go#hdr-Build_modes)                                                                             | 首版使用 c-shared，必须由真实 Rust host 动态装载并取得可核对语义；不以 Hello、c-archive 或其他平台的结果替代目标平台验证                            |
| [cgo 指针规则](https://pkg.go.dev/cmd/cgo#hdr-Passing_pointers)                                                                         | Go GC 与跨 C 边界指针生命周期；使用句柄和调用方缓冲                                                                                                 |

每个阶段均以实施时的源码、固定输入、实际产物与本阶段门禁判定是否完成。实施者应按实际结果更新现行文档；不能继承其他分支、其他机器或历史产物的通过状态。
