# 公共 worker 运行设施

```text
worker IPC → FrameReader（保留半帧进度）→ contracts wire 准入
                      │
           Session：Hello → Discover → Analyze → Ack / Reuse
                      │
   Java / Rust / Python 引擎 → graph-io Encoder → IPC
                      │
   daemon Receiver：身份 / 阶段 / 序号 → graph-io Verifier
                      │
               核实终态用量 → VerifiedEnd → 候选
```

| 模块 | 职责 |
|---|---|
| ipc | 帧读写、控制额度、半帧恢复、取消后的有界 drain；帧布局与预解码规则仍在 contracts |
| session | 三语言共用请求状态机，核对 attempt、合法阶段、一次 Replan、发布确认及复用策略 |
| analysis | Hello/Discover 额度策略，失败/取消回执、累计用量核对；纯 DTO 转换留在 contracts |
| delivery | daemon 接收状态机，核对冻结 manifest/profile/baseline/producer，拒绝乱序和重复终态 |
| ffi | 有界专用 owner 线程、完成通知、唯一累计账本交接凭证；不装载 SDK 或解释引擎对象 |

FreshProcess 完成后拒绝新任务；Reuse 回执后仍等待 daemon 的关闭控制帧。HealthyReuse 只在确认完成后重新接受 Hello。Java isolate 是否健康、G1 一进程一次创建和故障换进程由 Java worker/application supervisor 决定。

```text
HostOwned ──预留编码/解码/控制开销，交出 seed──→ EngineOwned
   ↑                                              │
   └──── 唯一凭证核实单调累计回执 ─────────────────┤
                                                  └─缺失/坏回执/凭证丢弃→ Unknown
```

所有 AnalysisContext 克隆共享所有权状态。EngineOwned/Unknown 禁止修改同一账户；取消与截止时间仍可观察，但不伪造引擎消费。Unknown 保留最后核实水位，不得认证成功；重复回执不能重复归还。Java 在交账前编码请求并预留回执解析额度，持账期间只做有界回执探测，核对后才恢复业务解码和归档计费。

owner 队列最多 8 项，创建/调用/销毁可持有非 Send 引擎状态且始终位于同一 OS 线程；stop 不等待队列容量。完成通知要求一个值为 1 的字节，EOF/错误 token 拒绝，具体终态仍由后端验证。owner 超时不证明原生任务已停止，进程监督与输入释放由 application 负责。

Java 内部 EngineRequest/EngineAnalysisReceipt 和 JSON 解码位于 java-worker；contracts 不保留兼容导出。worker-runtime 不依赖 application/storage、SDK 装载器或任何编译器。存储使用的有界诊断上下文保留在 contracts::performance，不引入运行层反向依赖。

application/supervisor 持有真实 child 与输入租约；回收线程独立于任务 runtime，未确认停止保留输入。worker-runtime 继续只管协议/FFI 机制，不拥有进程或 scratch。

Receiver 的 RequestBinding 包含单元 Scope；Begin 校验 replacement，Chunk 在交给临时候选前逐行校验范围。任一范围错误进入 Failed，忽略错误后继续发送正确 Chunk/End 也不能恢复成功。范围不替代候选的列形状、引用、coverage 或发布 CAS 校验。
