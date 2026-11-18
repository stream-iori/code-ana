# TypeScript worker

当前完成 T0、T2a、本机 T2b 及 T3 输入发现组件验证。独立 Rust worker 已通过生产前缀的异步 Go ABI 调用真实 Checker，并由 graph-io 校验原始 GRA1；Go 显式准备入口已记录编译器输入，显式配置/公共 capture 已接入，CLI prepare provider 已接入，公开图语义与产品 IPC 尚未接入。不能使用组件验证替代产品索引。公共重构的落点以 [多语言计划](../../todo/multilang-analysis-refactor-plan.md) 为准。

```text
Rust worker → Request protobuf → Go 独立会话 / goroutine
                                    │
                              只读冻结 VFS
                                    │
                            封存 GRA1 / inventory
                                    │
             Result protobuf ← 终态可见后通知 FD
                     │
              graph-io 验原始归档
```

| 契约 | 落点与约束 |
|---|---|
| C ABI 1 | `lang-typescript-adaptor/include/codeana_ts.h`；整数句柄、调用方缓冲、无 Go 指针；与 Java ABI 4 独立 |
| 引擎协议 1 | `proto/codeana/typescript/v1/analysis.proto`；请求、运行身份、冻结视图、准备清单、归档描述符、失败；Go/Rust 均从同源生成 |
| 累计预算 | 复用 worker 的 grant/usage/receipt 消息及现有主机账户；seed、deadline 必填，显式 0 表示无截止时间 |
| SDK | 公共 manifest 4 的 TypeScriptIdentity；核对 ABI、内部版本、worker/graph、上游/TypeScript 子模块提交、编译器版本、Go、构建参数、标准库、两份 proto 及 header SHA，不填写 Java JDK/GC |
| 输入 | Analyze 只接受内容封存的 FrozenProject；Prepare 独立接受显式 live roots，写 protobuf PreparationInventory，Rust 再校验捕获 |
| 输出 | Result 仅有有界描述符/失败；图和准备清单分别封存文件，不将整个项目或图塞入通知/结果缓冲 |

`ProjectContext` 表达稳定实例、逻辑根、配置、发布语言和目录/缺失查找/链接观察。`FileIdentity` 将路径、内容身份、角色与 module/scope/world 分开；`FrozenFile` 加入对应内容。准备清单只含身份，不把未捕获输入当成分析正文。标准库来自锁定 SDK；分析不从活文件或全局缓存补缺失输入。

| 状态 | 所有权与允许操作 |
|---|---|
| Created / Feeding | Go 已复制接收的输入；Rust 可立即覆盖自己的缓冲；累加输入受 session 上限约束 |
| Sealed | 输入禁止追加；请求版本、身份、必需预算及操作变体必须通过准入，不能默认补齐 |
| Running | start 已返回；Go 持有任务对象、取消令牌、复制的非阻塞通知 FD；Rust 账户为 EngineOwned |
| Complete / Failed / Cancelled | 先固定终态和有界结果，再发送一次字节 1 并关闭复制的 FD；通知不是成功证明 |
| Acked | 仅成功结果可确认发布；不修改已封存图；仍需 close |
| Closed | 计算已停止才删除注册项并释放输入/FD；句柄永久失效；close 超时返回 Pending，保留所有权 |

关闭、取消和清理不覆盖原失败。可恢复 panic 转结构化失败；无法核实用量时只保留最后可信水位并标 Unknown。fatal runtime/OOM/原生崩溃交给 supervisor 换进程。Go runtime 与动态库保留到进程退出，首版每个任务新 worker，不池化、不通过 dlclose 重置。

| 设施 | 对接方式 |
|---|---|
| `worker-runtime::ffi::Owner` | Rust 会话串行控制；不声称 Go 有 Graal 线程绑定要求 |
| `worker-runtime::ffi::Notification` | 有界 socket 完成通知；后端仍须检查终态、EOF、重复 start 和结果身份 |
| `worker-runtime::ffi::BudgetHandoff` | 原累计账户 HostOwned → EngineOwned → HostOwned/Unknown；先预留输入/解码/结果编码再交接 |
| `graph-io` | 验证 Go 原始归档的路径、EOF、count、SHA 和行准入；只有 VerifiedEnd 可进入公共交付 |
| application supervisor | 自有进程组、RSS、硬截止时间与输入租约；确认 worker 停止后释放捕获及 scratch |

T3—T8 的闭包、公开语义、产品、任务故障和实际分发继续实施。当前 SDK 仅输出明确 partial 的组件绑定诊断，不发布 TS 声明/调用能力；正常 CLI 尚不能索引 JS/TS。

T2b 基础代码已加入 `overlay/engine`：累计 Meter、反射驱动的 protobuf 预解码准入和 GRA1 写入。独立 production.lock 固定 protobuf 运行库/生成器与 protoc，构建脚本先克隆固定上游再生成 Go 绑定，不修改原 checkout。`--prepare-only` 只校验来源、生成绑定和执行 Go 核心测试；原基础轮次只做准备、不建立 SDK，不能替代后续组件出口；基础证据为 `target/typescript-c-abi/t2b-foundation-version/receipt.json`。


## 本机 T2b 组件验证

| 入口 / 证据 | 实际范围 |
|---|---|
| `scripts/build-typescript-native.py` | 克隆固定上游、生成标准库和同源 Go protobuf、Go 16 项核心/生命周期测试、c-shared、双 header 编译、符号/依赖与 SDK 文件 SHA |
| `tests/typescript_abi_e2e.py --sdk /absolute/sdk --fixture-sdk /absolute/fixture-sdk --evidence /absolute/new/evidence` | 11 项组件场景及 20 项实际 ABI 边界；空 PATH，不借开发源码补运行依赖 |
| `just test-typescript-core sdk fixture_sdk evidence` | Rust 聚焦单测、构建、clippy 与上述组件门禁；SDK 由显式构建入口提供 |

实际组件包括：真实两个别名调用绑定与人工 UTF-8 范围、原始 GRA1 EOF/count/SHA、累计 seed 交接/归还、预算拒绝、非法请求、启动前取消，以及独立 fixture SDK 中的真实 Go goroutine panic 和运行中关闭。panic/无可信终态保持 Unknown；关闭 Pending 时句柄、输入与 EngineOwned 账户不释放，明确 barrier 放行后才结束。通知验证一次字节 1 后 EOF；canary、长度、坏/关闭句柄、重复操作、非阻塞 socket FD 及原 FD 所有权均有实际 ABI 检查。

fixture SDK 只在显式 `--dev-abi-fixture` 构建时对本仓库 overlay 增加测试入口，adapter/构建选项明确标记；普通 worker 拒绝加载，不能混入分发包。不修改固定上游或正常 SDK，不执行目标代码。它不替代后续 Go fatal、真实任务取消/旧 head 和分发验证。

最新证据：`target/typescript-c-abi/t2b-components-fixture-first/receipt.json`。正式 Native/分发和性能接受均为 false。SDK 内核版本来自 `internal/core.Version()`，固定为 `7.1.0-dev`；TypeScript 子模块包版本为 `6.0.0`，两者不能混用。

## T3 输入发现组件

`ObservedFS` 仅在显式 Prepare 中访问允许根，使用同一内核解析配置与模块，并记录实际文件、目录、链接和失败查找。正文件捕获摘要；目录存在与完整枚举分别记录，不能靠“目录存在”推断其子项不存在。Prepare 写入确定性 protobuf inventory，Rust 经真实 C ABI 校验文件大小、count 与原字节 SHA。组件探测实际任务目录所在卷的大小写规则，不借用可执行文件卷的规则。

`FrozenFS` 强制显式 case_sensitive，使用内核 GetCanonicalFileName（保留特殊 Unicode 大小写规则）并返回捕获拼写。目录元数据复制后封存，正/负观察与完整枚举矛盾即拒绝；未观察访问、链接越界/循环、写入尝试均使任务失败。WalkDir 不跟随链接，支持文件 SkipDir 和 SkipAll。Checker 返回的大小写/链接路径映射回捕获身份。

最新 `t3-components-prepare-first/receipt.json` 为 12 场景、20 ABI 检查；两个锁定 SDK 各 25 项 Go 测试，包含删除活输入后的真实重放和大小写路径绑定。汇总为 `target/typescript-c-abi/t3-input-component-sealed/receipt.json`。T2b 的 11/16 数量属于其历史回执。

该组件检查点的 Prepare 对 references 和 live WalkDir 明确拒绝；配置错误尚未输出产品诊断。尚未完成公共配置/provider/capture、依赖模式/多上下文真值，不能把本组件写成 T3 或 TS 产品全部完成。生成器现在从已校验 production.mod 构建，与 protobuf runtime 共用锁；原代理 504 构建失败继续保留。

## 原生输入模型

私有 `PreparationInventory` 只在 worker 内解码。先执行有界 wire 准入，再显式映射到 `model::typescript::Inputs`；model/config 不依赖引擎 protobuf。model 的 Context/文件角色/目录/链接/缺失观察均为严格类型，SDK 语义身份包括实际库摘要，不只比较 adapter 名称。清单没有安装路径或 generation 字段；文件/观察枚举规范化，编译器 root/reference 顺序保留。

当前实际 Prepare 组件写出 `native-inputs.json`，验证其库 SHA 与 SDK 文件清单一致。12 场景、20 ABI 检查、模型 79 / contracts 39 / worker 3 项聚焦及 clippy 通过，见 `target/typescript-c-abi/t3-native-inputs-sealed/receipt.json`。公共 config/provider/capture 尚未消费该模型；下一步接生产路径，不将类型测试算作输入闭包全部验收。

## 公共 capture 接入

JS/TS 各自显式启用，共用原生清单与依赖根。普通输入按 compiler context 拆成独立单元，分别持有 manifest/观察/正文；同一文件可在不同 world 出现。共享捕获器新增 EntryKind 元数据观察，与完整目录枚举分开，封存时复核；精确缺失路径即使父目录尚未存在，也不授权相邻文件读取。Go 只消费后续从这些正文构造的冻结视图，不从活工作区补缺。

最新真实门禁 `tests/typescript_inputs_e2e.py` 从正常 SDK 调用 Go Prepare，再用其实际原生清单进入公共 Rust capture，删除 3 个活输入后验证全部捕获正文。普通 Rust 门禁不要求 Go，专项用例显式 ignored；`just test-typescript-inputs SDK EVIDENCE` 必须实际执行，已通过且无产物漂移。完整 `just test-rust` 同轮通过。汇总：`target/typescript-c-abi/t3-config-capture-sealed/receipt.json`。

当前仍需补齐 references/pnpm/多配置闭包与冻结 Go 分析的产品链路，随后完成 T4—T8。历史段落所列“未消费”是对应组件检查点状态，不表示本轮已完成整个 T3。

## CLI 与独立 worker 准备入口

`--prepare-project/--prepare-config/--output/--scratch` 启动真实准备会话；显式 unit/module/world/scope 与发布语言进入原生清单。Rust 校验累计回执、inventory 长度/count/SHA、wire 和类型，再以预算约束的 JSON 缓冲写出新文件。会话关闭、进程停止后才释放 scratch。fixture SDK 禁止用于该入口。

| 门禁 | 本轮结果 |
|---|---|
| 两个 inputs2 SDK | 各 27 项 Go 测试通过 |
| `just test-typescript-prepare SDK EVIDENCE` | 5 个真实入口场景通过，空 PATH、重复准备、语言过滤、外部链接与配额 |
| `just test-typescript-provider SDK EVIDENCE` | 6 个 CLI 场景通过，共享后端、别名/冲突及旧清单拒绝 |
| ABI / 公共 capture | 12 场景、20 ABI 检查及实际公共捕获通过，无产物漂移 |
| 既有语言 prepared | Native Java/Rust/Python 19 项通过；首次报告版本/历史注释字段失败日志保留 |

目标卷大小写探测由共享 input-io 完成，Rust capture 再核对；`.code-ana` 从编译器目录视图排除。adapter `inputs2` 要求重建旧 SDK；ABI 和引擎 protobuf 仍为 1。上述门禁属于准备阶段，不能作为公开图、正式分发或性能放行。最新证据入口见当前实现。

## 解析闭包专项

`just test-typescript-resolution SDK NEW_EVIDENCE` 必须执行真实准备与公共 Rust capture 的五组 fixture：extends/paths、pnpm/exports、NodeNext/imports、typesVersions、reference 声明产物。普通无 Go 门禁显式忽略该真实编译器测试，专项入口强制执行并核对五组证明。相同 JSON fixture 也进入构建时 Go 测试，验证捕获后删除活文件的实际 Checker 重放及重复调用目标。references 闭包及多配置准备已接入；缺声明产物的产品 BuildAdvice 仍待接入。

## project references 的冻结闭包

inputs3 SDK 保留 compiler 配置给出的有序 references，按内核的引用映射区分消费者输入与引用项目的源码/声明产物。已有 `.d.ts` 可作为辅助解析输入，不能由消费者发布；缺产物记录真实失败查找，不改用引用源码充当实现目标，不自动构建。引用配置的 content mapper 仍明确拒绝。Go 冻结重放校验 references 与封存配置一致。

共享 fixture 现为五组，新增 reference 声明目标；另有缺声明产物的两次 unknown 调用测试。实际 SDK / ABI / provider / 公共 capture 及聚焦 Rust 门禁均通过，见当前实现的 t3-references 回执。空根 solution 和多配置准备现已接入；产品诊断/BuildAdvice 仍待完成。当前要求 inputs5 SDK，公共协议不变。

公共 capture 的单元选择按稳定 unit ID 读取封存 context，不从后端重新选择整份清单；多个 context 不允许仅凭 TypeScript 后端猜选。CLI 多配置测试已实际验证相同文件、共享模块策略与独立 world。当前任务资源策略仍从共享 task 读取，冻结输入不会恢复旧预算。

## 等价旧式清单

JS/TS 的旧式 inputs_manifest 可以引用两份不同位置但语义等价的原生清单，也可只给一个共享位置。公共 config 规范化文件/观察顺序后比较语义身份，仍认证并捕获每份已声明清单的原字节。不同内容明确冲突，不自动合并不同发布选择。普通计划按封存单元 ID 枚举 compiler context，并以内部短序号创建 scratch。五组真实解析 fixture 与八个实际 CLI 场景已验证该路径；公开语义图仍待接入。

## 物理拼写

当前最小 adapter 为 inputs5。Darwin 准备器使用只读 FD 的物理路径，保留文件和 symlink 自身拼写，不枚举全局目录来猜测大小写；按内核 canonical 规则核对并拒绝不同物理条目的冲突。Rust 通过已封存目录名称验证文件及父路径的精确拼写，避免内容相同的改名沿用旧清单。

`just test-typescript-spelling SDK NEW_EVIDENCE` 是显式不区分大小写卷的真实门禁，缺该卷会失败而不跳过。验证 Actual.ts / Link.ts 经真实 SDK 进入公共 capture、同字节改名拒绝和删活文件后的正文；Go 测试另验证两个大小写/链接别名调用在冻结后仍绑定正确文件。其他平台的不区分大小写捕获明确 unsupported，Linux 等平台仍需独立验证。

## 捕获内容交接

`model::typescript::FrozenInputs`（schema 1）保存一个已封存单元及内容对象引用，独立于语义清单。config 按实际单元 manifest 得到发布选择；worker 先核对 SDK/实例和完整引用集合，再用预算约束的只读文件读取校验大小、SHA、UTF-8，构造私有 protobuf FrozenProject。来源路径仅用于 VFS 身份，内容来自捕获 spool；调用方保留 capture 租约。

`just test-typescript-handoff SDK EVIDENCE` 复用现有真实输入入口的 --handoff，强制运行独立 ignored SDK 用例：公共 capture 后删除活输入，经上述组装进入真实 Go ABI，再验证原始 GRA1 与两次调用目标。当前是 5 行组件诊断图，不能作为公开 TS 声明/调用图或产品索引放行。config 仅为 worker 集成测试的 dev-dependency，生产 worker 不依赖 config。

CLI TypeScript prepare 在原生发现后按文件匹配显式 module/source_roots 和当前 MAIN/TEST scope；最长路径前缀决定 owner，同深度多模块返回 TYPESCRIPT_MODULE_OWNER_CONFLICT。未覆盖文件保留辅助角色并清空 module/scope/world。模块语言选择共享后端，不把 JS/TS Program 拆成两个执行实例；world 仍由 context/module/scope 生成。此为主机清单归一，不改变 SDK、ABI 或 wire。

TypeScript inputs5 允许带 references 的空根 solution。CLI 按编译器返回的配置路径逐上下文准备，最多 256 个上下文，菱形引用只准备一次、循环引用拒绝；solution 自身作为零发布单元保留配置/观察。仅当选择的入口为空根 solution 时展开引用闭包，普通消费者仍只发布自身来源；外部引用源码需单独项目配置，当前跨项目只消费显式依赖声明。catalog 3 的同一 TS 入口可覆盖多个独立单元，其他后端仍要求一份归属；缺配置失败后不切换活动清单。旧 inputs4 SDK 必须重建，TS ABI 1 / analysis 1 / 输入清单 1 不变。公开语义与产品执行仍未接入。

跨入口的同 ID TS 上下文须经完整 semantic_digest、发布标签和模式核对才合并，入口/root 归属合并后去重；每份原配置和清单仍认证。不同上下文冲突及全局超过 256 个单元，在切换活动 catalog 前失败。
