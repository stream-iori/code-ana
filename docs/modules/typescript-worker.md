# TypeScript worker

当前完成 T0 探针和 T2a 生产契约草案；生产共享库、worker、输入准备与图语义尚未实现。不能使用探针替代产品索引。公共重构的落点以 [多语言计划](../../todo/multilang-analysis-refactor-plan.md) 为准。

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

T2b 仍需真实 Go 异步成功、失败、取消、panic、运行中关闭、句柄/FD/缓冲边界及 Rust 归档验证。T3—T8 的闭包、语义、产品、故障和实际分发不能由本页或 protobuf 编译通过代替。
