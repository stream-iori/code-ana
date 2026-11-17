# 多语言输入准备

`index --prepare` 在 CLI 本地准备配置中的入口，成功或明确降级后再提交索引。普通 `index` 只消费冻结清单；`doctor` 展示静态计划，不运行 Maven/Cargo。

```text
doctor                         检查配置与准备入口
index --prepare                CLI 准备 → daemon 捕获 → 分析 → 一次发布
index                          校验现有输入 → 分析 → 一次发布
report <id>                    全局 JSON 报告
report <id> --format markdown   同一数据的 Markdown 展示
```

## 配置

```toml
schema_version = 1
kind = "project"
[languages.java]
enabled = true
[languages.java.analysis]
source_version = "8"
toolchain = "java8"
[languages.rust]
enabled = true
[languages.python]
enabled = true

[inputs]
manifest = ".code-ana/inputs.json" # 默认值

[[inputs.entries]]
id = "backend-a"
language = "java"
root = "backend-a"
manifest = "pom.xml" # 相对入口根；标准名可省略
[inputs.entries.java]
offline = true
profiles = []

[[inputs.entries]]
id = "backend-b"
language = "java"
root = "backend-b" # 独立 reactor，不要求共同父 pom

[[inputs.entries]]
id = "engine"
language = "rust"
root = "engine" # 原生 workspace 由 Cargo metadata 展开

[[inputs.entries]]
id = "tools"
language = "python"
root = "tools"
[inputs.entries.python]
dependencies = ["common"]
extras = []
dependency_roots = [".code-ana/prepared/site-packages"] # 仓库相对或绝对路径
python_version = "3.12"
platform = "darwin"

[[inputs.entries]]
id = "common"
language = "python"
root = "common"
```

每种启用语言必须有入口，上限 256。入口 ID 最长 64 字节，使用字母、数字、下划线或连字符。根在项目内，不接受 symlink 路径；不递归猜测其他项目。配置入口按原生工作区展开。

`modules` 仍表达源码归属、扫描范围及覆盖；按语言和项目相对物理根匹配。显式 module.id 保持，自动子模块从入口 ID 和原生相对路径派生稳定身份。重复模块身份与矛盾归属拒绝；不同实例可使用相同物理根，前提是模块/world 身份独立且文件内容一致。不按目录、同名包或坐标合并独立上下文。

### Python

默认静态读取入口根的 pyproject；显式 entry.manifest 优先，不向父目录搜索。不存在时使用显式源码根，例如：

```toml
[[modules]]
id = "tools"
language = "python"
root = "tools"
source_roots = [{path = "src", scope = "MAIN"}]
```

显式根完整覆盖推导根并保留顺序。支持标准字段、setuptools 常规布局及 TEST 的 pytest pythonpath；不执行动态元数据、目标解释器、pip 或 `.pth`。缺少可确定根时可降为文件/文本。

默认每入口隔离。dependencies 显式引入其他 Python 入口，允许循环、稳定去重；其源码供解析，不冒充消费者发布内容。MAIN 不读取 TEST，TEST 可读取 MAIN。目标仍限 3.12/darwin。

相同 entry.python.environment 表示共享环境，成员按配置顺序组织，各文件保留实际 module。目标和有序外部依赖根必须一致；冲突拒绝。只有显式共享或依赖才会互相可见。

Python schema 3 增加根 module、模块元数据、上下文 publish_roots/gaps，只接受 schema 3。adapter 为 pyrefly-1.4.0-codeana-v5-comments，后端提交不变。不提供旧格式默认值或迁移；归属进入 world/profile。

### JS / TS（准备与捕获已接入，语义索引待接入）

```toml
[languages.javascript]
enabled = true
[languages.javascript.discovery]
inputs_manifest = ".code-ana/typescript-inputs.json"
dependency_roots = []
[languages.typescript]
enabled = true
[languages.typescript.discovery]
inputs_manifest = ".code-ana/typescript-inputs.json"
dependency_roots = []
```

两个源码开关独立，共用一份 TypeScript 原生清单和有序依赖根；不创建两份后端预算。清单 schema 1 为 `model::typescript::Inputs`，包括 SDK 内容身份、每个 compiler context 的文件/角色/大小写/目录/链接/失败查找。每个 context 形成自己的冻结单元。SDK 安装路径和准备清单位置不进入语义身份，实际库摘要进入；禁用语言后必须重新准备，不能沿用仍发布该标签的清单。

公共 capture 验证字节与观察并使用任务自有 spool。目录存在和完整枚举分开；某路径不是文件不表示该路径也不是目录。外部依赖正文必须位于显式 dependency_roots，禁止用 `/` 授权整个文件系统；祖先 package/type 的失败查找仅授权精确元数据观察，不授权内容回退。过滤的源码仍可作为辅助 Checker 输入，不能发布其语义。

`just test-typescript-inputs SDK NEW_EVIDENCE` 强制执行真实 Go 准备及公共 capture，用例在普通无 Go Rust 门禁中显式 ignored；SDK 门禁不允许跳过。该入口验证公共捕获；CLI 准备另由 `just test-typescript-provider SDK NEW_EVIDENCE` 验证。公开语义索引尚不可用。

也可使用统一准备入口：

```toml
[languages.javascript]
enabled = true
[languages.typescript]
enabled = true
[[inputs.entries]]
id = "app"
language = "typescript"
root = "."
manifest = "tsconfig.json"
[inputs.entries.typescript]
scope = "MAIN"
timeout = 120
[[modules]]
id = "app"
language = "typescript"
root = "."
```

`manifest` 是入口根下的静态 compiler 配置；省略时 TS 取 tsconfig.json，JS 取 jsconfig.json。相同配置、module、scope 的 JS/TS 入口只准备一次，别名归入同一单元；冲突拒绝。CLI 启动相邻的 `codeana-typescript-worker`，SDK 使用 `CODEANA_TYPESCRIPT_SDK` 绝对路径，或安装目录 `lib/typescript/current`；不运行包管理器。准备使用显式依赖根、任务预算和超时，scratch 在目标项目外。

当前 `index --prepare` 能生成并捕获清单；后续语义执行明确返回 `TYPESCRIPT_INPUT_NOT_READY`，不会发布占位语义。pnpm 与有声明产物的 references 闭包已有真实验证；空根 solution、多配置编排及缺产物的产品建议仍需补齐。

## 输入与降级

公共清单 schema 3 登记原始/规范化语言清单及原生配置视图的路径、长度和 SHA。原生视图只含 discovery、languages 和 modules；公共扫描、资源、发布及报告策略从项目配置解析一次，由所有单元共享。视图中注入公共策略直接拒绝。Maven 回执、Cargo 图分别保留，不拼成虚构的共同构建。准备使用项目锁和独立 generation；配置未变、规范化视图验证通过后原子切换。索引再次验证并捕获，之后不借活文件补全。

| 情况 | 处理 |
|---|---|
| Maven 模型可用、依赖失败 | 现有 source-only fallback，保留真实回执 |
| Maven 模型或 Cargo 图不可得 | 文件/文本可用、语义不可用，保留日志 |
| Rust 依赖 | 默认 offline/locked，不自动下载 |
| Python 缺包/指定依赖根缺失 | 分析现有输入，披露缺失导入或上下文缺口 |
| 源码错误/生成输入缺失 | 保留可提取结果与诊断，不自动运行生成器 |
| 配置冲突、损坏回执、哈希失败 | 任务失败，不以降级绕过校验 |
| 超时、取消、超限、工具崩溃 | 任务失败，回收所属进程 |

Java plain 使用已有显式模块根，不运行 Maven，仍需正确的 Java 8 分析配置。Maven 准备按 offline/settings 配置可能访问仓库，不运行编译生命周期。无证据时不把失败归因于网络。

普通索引允许可用入口与明确降级入口一起发布，整体 partial；strict 拒绝 partial、保留旧 head。全部语义不可用时文件/文本仍可发布。入口不可用有持久 coverage；无法解析选择器时返回 SEMANTIC_INPUTS_UNAVAILABLE，不能冒充确定的 SYMBOL_NOT_FOUND。

新模式保守全量或完全相同输入复用；模式切换清除旧语义事实。全部输出通过 VerifiedEnd/候选校验后一次 CAS。恢复依赖后重新 index --prepare，不静默借旧图。

## 报告与兼容

报告 schema 5 提供执行、质量、门禁、发布、报告五个状态，范围、输入模式分布、主要原因和日志/CLI 定位。JSON/Markdown 同源；不增加 JSONL，不嵌入完整配置、逐文件诊断或日志全文。入口样例上限 20，另给总量及省略量；详细排查继续使用 CLI/日志。

失败、取消、strict 拒绝也保存可用摘要；global.measurements_available=false 表示最终分析计数不可得，零值不能解释成无问题。报告关闭仍有终态；报告失败不撤销发布。仅接受当前 schema 5 报告。

无 inputs 配置时保持旧入口；新公共清单与启用语言的旧 inputs_manifest 互斥。原导出器、java-inputs export 保留，Python 原脚本默认不读 pyproject，显式 --pyproject 不变。独立导出器可只准备、不索引。

当前版本为 worker 25、daemon 7、Java ABI 4、graph 6、物理 schema 17。准备与索引不是跨进程事务，报告以实际捕获和提交为准。分析 worker 和 Java SDK 仍须按安装说明具备。

验证入口为 `tests/prepared_inputs_e2e.py --backends java,rust,python --evidence /absolute/new/evidence`，显式执行全部 19 项，包括 Native 三语言及两个独立 Maven reactor；Rust/Python 分发声明 `--backends rust,python`，明确列出四项 Java 范围排除。已选后端缺失或测试 skip 均判失败。

准备清单 schema 3 只使用 config_sha256 精确校验原始配置字节；注释展示参数及 preparation_sha256 例外已删除。schema 1/2/旧例外清单、配置字节变化或输入策略变化均需重新 index --prepare；artifact、来源和任务前后配置漂移检查保持。

完整语义 profile 相同、其余源码/owner 完全相同时，普通 Python 与 prepared 可以复用图并更新 FILES 中的根配置捕获；源码、依赖或 world 改变仍沿原策略重算。

捕获边界：公共 workspace 遍历在 `config/capture.rs`；Java metadata/依赖捕获在 `capture/java.rs`，只负责 Java 输入；`capture/blob.rs` 负责冻结字节及 spool 所有权，Rust/Python 使用相同字节捕获能力。语言配置 wire 兼容与实际 Java/Rust 视图分离，公共扫描和资源配置继续共享；源 hash、owner/world、捕获顺序和资源准入规则不变。

内部配置已拆为共享 TaskConfig 和类型化 UnitInput；prepared 单元保存扁平 ResolvedContext，不再递归包含整份 Effective。运行时分配内存上限时先更新共享任务策略，再生成各单元视图。普通与 prepared 入口已使用同一单元计划及公共执行器。


## R3 捕获登记与语义摘要（进行中）

```text
文件字节 ── SHA → 只读 CapturedBlob（同内容共用，任务持有 spool）
                     ↑
world A / path → 发布角色     world B / path → 辅助角色
                     │
同一路径内容不一致 / 同一 world 归属冲突 → 拒绝
```

Java/Rust/Python 的内容与来源登记共用 `capture/registry.rs`；同一文件可以登记多个 world，每个实例内的 module/scope/language/auxiliary 必须分别一致。独立实例对同一 world/path 的辅助角色可不同；任务 FILES 清单在任一实例发布时保留发布角色，内容及 module/scope/language 仍必须一致。通用 FILES 扫描的占位视图由明确的语义视图替代，不删除其他 world。独立内容与来源引用分别设限；每个来源仍进入原有 manifest/帧资源准入，内容去重不取消引用成本。Java scratch 对相同逻辑路径只复制一次并核对 hash，避免多 world 重复覆盖只读文件。

| 身份 | 包含 | 排除 |
|---|---|---|
| 完整性 | 原始配置、catalog、artifact 的路径/字节/SHA；照常拒绝陈旧或损坏清单 | 不以语义摘要代替原字节校验 |
| 单元语义 | 解析后的配置/输入、稳定单元 ID、归属/模式、依赖内容及顺序、工具链内容身份 | generation、日志、artifact 定位、Maven 导出 nonce/receipt、资源额度 |
| 发现上下文 | producer、模块/world、能力、后端语义详情 | Java home 的安装位置；报告仍保留该来源信息 |

公共配置语义摘要清除 discovery.inputs_manifest 定位；Java 输入摘要保持依赖顺序与 SHA，排除导出回执并将 jar 定位归一为内容身份。应用 profile 使用已核实的 BuildContext.semantic_digest。prepared 按稳定单元 ID 计算摘要，不散列整份 ResolvedUnit 或原始 config/catalog。

catalog 和其已验证 artifact 在 FILES 中明确标记 scope=PREPARATION，仅限无 module/language、world=files 的完整性输入。profile 完全相同后，比较业务来源时忽略这些定位/回执文件；真实控制文件、依赖、源码和 owner/辅助角色仍严格比较。重复 prepare 因而可以更新 FILES 并保留语义；源码变化仍使原清单陈旧，重新准备后必须重算。

内容/归属登记、稳定摘要以及解析/捕获共用读取器已接入。目录/缺失查找/根观察、声明链接映射与封存复核见下节；各入口实例冻结视图见下节。pnpm/TS 冻结 VFS 仍须由 T3 取得产品证据。


SDK 搬迁检查：`just test-java-native-relocation target/NEW_EVIDENCE`（Python 3.11+、当前 Rust 二进制、已安装 Native SDK 和目标 JDK 8）。入口复制同 SHA 的 CLI/daemon/Java worker 与 SDK 到独立目录；保持同一项目和历史，先要求 Reused/相同 profile，再用新位置实际全量分析并比较图库。独立 home/进程由 fixture 清理，不替换已运行的二进制或 SDK；证据保留应用/SDK SHA、profile 和诊断日志，不签正式 Native/分发放行。


## R3 捕获读取与观察（进行中）

```text
配置解析 / 原生清单验证 / 内容捕获
                    ↓
              共享 CaptureReader
                    ├─ project / 显式外部目录或单文件根
                    ├─ openat + NOFOLLOW → 一次 live copy → 只读 spool
                    ├─ 同路径 / 同 inode 身份：复用已捕获字节
                    └─ 文件 / 目录枚举 / 缺失查找 / 根的 realpath 观察
                                      │
                         封存前复核 → 不可变 observations
```

配置解析在创建任务读取器后执行，通过窄 `InputReader` 端口读取根配置、prepared catalog/artifact、原生清单与控制文件；后续 workspace、Java metadata/依赖、Rust、Python 和 Lombok 复用同一 `capture/reader.rs` 缓存与封存。诊断/显式 prepare 的独立读取使用 `LiveInputs`，不创建捕获事务。普通与 Git 历史捕获均支持任务取消；daemon 的 submit 原已在 blocking pool 执行，任务取消令牌通过窄回调传给 config，不引入 tokio/application 依赖。

| 边界 | 当前规则 |
|---|---|
| 内容 | 同一文件身份仅一次 live copy；hardlink 保留各逻辑路径观察，共用内容。缓存命中仍服从本次读取上限 |
| 路径 | 项目目录 FD 与显式外部目录/单文件权限；逐级 openat/NOFOLLOW，拒绝未声明外部根、未声明链接及非普通文件；外部单文件不长期占用一个父目录 FD |
| 配额 | 2 GiB live 读取准入（含 EOF 探测）；观察最多 400000 节点/64 MiB 估算占用，目录条目分配前准入；原内容/manifest/帧配额继续独立执行 |
| 取消/截止 | 拷贝块最多 64 KiB，分块检查任务令牌和截止时间；目录枚举与封存复核也检查。打开时的长度限定本次复制，增长/截短不能绕过预留 |
| 封存 | 复核根 realpath/身份、文件 dev/ino/长度/mtime/ctime、排序后的目录名称/类型及失败查找；变化拒绝，不能交付混合快照 |
| 生命周期 | 观察是任务拥有的不可变 Arc，不进入语义 profile 或 worker wire；封存后原文件改删不改变已捕获内容 |

Java 的每个声明 jar 路径都核对原字节，不能因相同 SHA 的另一路径已入库便跳过；仅内容存储去重。Lombok 的不存在配置与祖先查找也进入观察。

公共 `InputCaptureReader` / `DeclaredLink` / `SealedInputs` 提供声明链接端口。每个单元分别登记逻辑路径和精确 readlink 目标；先安装整张映射再验证，顺序无关。目标仍须落在已有允许根内，登记链接不授予外部权限。每单元最多 4096 个声明、一次解析最多 40 跳、路径最多 64 个分量/16 KiB，并沿用观察配额与取消检查。相对目标按物理父目录解释，`..` 不跳过缺失或非目录分量。循环、未登记的中间链接和不匹配目标拒绝；失败证明使读取器不能封存。缓存命中仍按本单元映射重新解析，不能借其他单元权限读别名。

封存前以父目录 FD 复核链接身份及原始目标，同时保留目标文件、目录和缺失查找的原有检查；同一路径的链接与内容观察均进入单元视图。Java/Rust/Python 原生清单和独立诊断继续拒绝源链接，尚未有产品清单向此端口传入链接映射。该公共组件不代表 pnpm/TS 输入闭包验收。

外部 Maven settings/工具身份使用同一根权限和封存复核，但只做流式 SHA 校验，不将原文写入 spool/内容库；哈希观察不能被隐式升级为内容输入。已捕获的项目内容与这类验证观察分别保留明确角色。

独立诊断读取在打开时设置 NOFOLLOW/NONBLOCK，并在分配文件正文前检查普通文件类型和长度；拒绝 FIFO/目录/链接，读取后复核长度与文件身份。解析期间读取的项目配置和原生控制也属于任务封存检查，不能把解析与捕获之间的改写拼成可发布输入。

## 分析单元冻结视图

```text
公共 workspace 清单（暂不分配语义归属）
           │
           ├─ 实例 A：原生配置 → 自己的 manifest / 控制 / 依赖 / 观察索引
           └─ 实例 B：原生配置 → 自己的 manifest / 控制 / 依赖 / 观察索引
                               │
                    共享只读 blob 与观察存储
                               │
                    汇总 FILES → 一次发布
```

`capture/views.rs` 按稳定 AnalysisUnitId 构造 FrozenUnitView；普通入口按后端生成稳定实例，prepared 使用显式入口实例。两者共用 unit_plans/capture_units。Task Capture 保存公共 FILES 清单；语言准备通过 `unit_capture` / `backend_capture` 取得 Unit Capture，拒绝将已选单元当作另一后端使用。删除 application 内按固定 scope/world 筛选全局 Capture 的函数，不再把所有 CONTROL/PREPARATION/JAVA_CONFIG 或全部 blob 交给每个实例。

| 内容 | 规则 |
|---|---|
| 来源 | 每个实例独立登记 module/world/scope、源码语言和辅助角色；plain Java 从公共文件清单选择自己的源根，原生输入由各语言规则捕获 |
| 控制与依赖 | 共享根配置/catalog；只持有自己的 artifact、原生控制、Java 依赖及实际适用的 Lombok 控制。通用 RESOURCE 占位条目可提升为控制，已登记控制角色之间仍检查冲突 |
| 内容 | 单元 blob map 只包含其 manifest 引用的 SHA；相同内容指向同一只读 spool，视图独立持有生命周期 |
| 观察 | 解析及捕获操作都按实例记录路径；ObservationSet 通过索引共享不可变存储，只公开选中的文件/目录/缺失查找及允许根，不复制整份目录观察 |
| 配额 | 单元 manifest 的总引用数最多 10000，重复上下文引用和公共控制也计入；原全局 manifest、内容及观察配额继续执行 |
| 冲突 | 允许相同物理根的独立实例；重复模块 ID、同实例角色矛盾、相同路径不同内容仍失败，不通过改公开 ID 消除冲突 |

普通/prepared 已共用冻结单元视图和公共执行器，分别选择 Full/Delta/Reuse 并一次发布。真实 TS pnpm/references 冻结闭包仍未完成；来源映射的 TS 产品证明随其接入验证。

普通后端上下文只保留自己的 UnitInput、语言配置和模块，并引用同一 TaskConfig；配置解析期间的原生发现也按后端登记观察，prepared 的现有实例作用域保持。Java 的流式/归档结果先按单元 manifest 核实，私有候选使用完整 revision manifest，FILES 仍保留全项目资源。

Java 单元还保留项目根模块的 XML 资源输入规则：启用 Spring XML/SOFA 时，把公共扫描选中的项目 XML 纳入该视图，跳过 pom.xml；原生资源根已有 owner 时保持该 owner，Lombok 控制仍独立捕获。不会因此把 Cargo.toml 等无关控制或其他语言源码交给 Java。根资源目录观察随单元保留，XML 内容变更继续参与增量与资源限制检查。

普通与 prepared 原生描述符均以 PREPARATION 登记，只承担字节完整性校验；有效配置进入语义 profile。pom.xml、Cargo.toml、pyproject.toml 等实际控制文件仍以 CONTROL 登记并参与失效判断。等价原生视图从 prepared 改为普通入口时可 Reuse；旧普通角色清单至多触发一次保守全量。

多个 JS/TS 入口可使用不同 compiler 配置且共享相同的模块策略；相同源文件按各 context 的 world 独立登记。重复 module ID 仅允许完全一致的脚本策略，其他冲突拒绝。当前同根多个不同脚本 module 明确报上下文冲突，尚未实现文件级多模块归属。
