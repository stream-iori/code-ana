# TypeScript worker

当前完成 T0、T2a、本机 T2b 及 T3 输入发现组件验证。独立 Rust worker 已通过生产前缀的异步 Go ABI 调用真实 Checker，并由 graph-io 校验原始 GRA1；Go 显式准备入口已记录编译器输入，配置/公共 capture、公开图语义与产品 IPC 尚未接入。不能使用组件验证替代产品索引。公共重构的落点以 [多语言计划](../../todo/multilang-analysis-refactor-plan.md) 为准。

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

当前 Prepare 对 references 和 live WalkDir 明确拒绝；配置错误尚未输出产品诊断。尚未完成公共配置/provider/capture、依赖模式/多上下文真值，不能把本组件写成 T3 或 TS 产品全部完成。生成器现在从已校验 production.mod 构建，与 protobuf runtime 共用锁；原代理 504 构建失败继续保留。
