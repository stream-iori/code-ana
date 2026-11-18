# TypeScript worker

JS/TS 使用独立 Rust worker 装载固定 typescript-go 共享库。普通与 prepared 索引已经接入公共单元执行器；输入或 profile 改变时全量，相同输入通过 Reuse/Reused 认证后复用。每次任务启动新进程，不保留跨任务 Program。

```text
CLI / daemon → 单元 capture / supervisor → Rust TypeScript worker
                                                │ C ABI
                                        Go Program / Checker
                                                │ 封存 GRA1
                         candidate ← 公共行交付 ← 原始归档校验
                             │
                         一次 catalog CAS → Ack → close / 退出
```

| 契约 | 当前版本 / 边界 |
|---|---|
| 公共协议 | daemon 7、worker 25、model 7、物理/逻辑 schema 18/37；SDK manifest 4 |
| TS SDK | 最小适配器 inputs7；固定 upstream.lock.json 与 production.lock.json；普通 Cargo 不调用 Go |
| C ABI | 1；整数句柄、调用方缓冲、一次非阻塞完成通知后 EOF，不返回 Go 指针 |
| 私有分析 | protobuf 2；Go/Rust 从同源生成；Analyze 必填 publication_root |
| 原生输入 / 冻结描述符 | Inputs schema 1 / FrozenInputs schema 2；后者包含整个项目的 publication_root |
| SDK 身份 | worker/graph、ABI/analysis、上游与 TS 提交、Go、标准库、库/header/两份 proto SHA 严格核对；fixture SDK 不进入产品模式 |

BuildContext 的 adapter 字段封入完整 CompilerIdentity 摘要。主机独立核对当前捕获的 compiler 身份，SDK 安装位置不参与该摘要。Go 的配置根可以是子目录，公开 source 路径始终相对于 capture 项目根，避免子项目的 `a.ts` 与全局 `nested/a.ts` 混淆。单元 ID 接受最多 128 字节的有界安全字符。

| 输入 / 发布 | 行为 |
|---|---|
| 显式语言 | javascript、typescript 分别 enabled；同一配置上下文只建一个 Program 和预算实例；未启用来源保留辅助角色 |
| 准备 | index --prepare 的 CLI provider 启动独立 worker，读取真实配置/解析观察；不安装依赖、不运行目标构建、处理器、业务代码或插件 |
| 上下文 | 多 tsconfig、references、空根 solution、paths/extends、NodeNext、exports/imports、typesVersions、pnpm 文件型链接；根外依赖须显式声明 |
| 封存 | 目录/缺失查找、大小写、链接、文件 SHA 先验证；分析只读捕获 spool，缺失内容不能回退活源码或全局缓存 |
| 私有状态 | 所有声明根的 .code-ana 都不属于编译器视图；嵌套 Program 不观察父项目的准备 generation |
| 所有权 | RunningUnit 持有 capture 与描述符/scratch；确认进程树停止后释放；首版不加入 Java 健康池 |

| 语义 | 支持与限制 |
|---|---|
| 声明 | 21 种声明、6 种可见性；值/类型/namespace 合并组保留每份声明与重载来源；参数属性分开 lexical parameter / instance property |
| 引用 / 模块 | 编译器符号、读写/类型/namespace/typeof 角色、别名与真实 module export 表；JS JSDoc typedef/callback/import、CommonJS、重导出 |
| 调用 | 有证据的实现、声明、辅助/标准库 external、unknown 分开；参数/接口/.d.ts/any/union/错误恢复不凭名字补实现；隐式构造器仅保留 class 声明证据 |
| 关系 | extends/implements、静态调用；JSX 只有组件引用，不补运行时调用；不提供 Java CHA/runtime implementations |
| 注释 | AST/JSDoc 与内核 token 共同确定归属；正文映射保留原 UTF-8；BOM、CRLF、emoji、U+2028/U+2029 使用 JS/TS UTF-16 坐标规则 |
| 质量 | SOURCE_DEFS / REFERENCES / CALLS / COMMENTS / AGGREGATE 分别按 Program、诊断和缺口判定；普通类型错误不使注释不可用；tagged template 等明确不支持项保持 partial |

Go 写封存 GRA1，Rust 的 graph-io 独立验证路径、大小、EOF、count、SHA 与行准入，再用唯一公共 Encoder 交付。原始校验失败不认证 End，候选不能提交；任一单元或 strict 质量失败保留原 head。旧 revision 的源码、注释和续页使用捕获内容。

资源使用公共 grant/usage/receipt 与 BudgetHandoff：HostOwned → EngineOwned → HostOwned 或 Unknown。编译器工作空间按实际读取文件预付，不一次预收全部内嵌标准库；失败水位粘滞，拒绝后不继续读取。输出资源为 typescript_result_bytes（默认 64 MiB）、typescript_fact_limit（默认 100000）、typescript_scratch_bytes（默认 512 MiB）。scratch/RSS/硬截止时间继续由 supervisor 监督；逻辑额度不代表 RSS。

| 验证入口 | 范围 |
|---|---|
| build-typescript-native | 固定来源、标准库、同源绑定、当前 66 项 Go 测试、c-shared、header/符号/SHA |
| test-typescript-semantics | 强制核对当前 35 项语义/资源/隔离真值；SDK 构建同轮日志可核对执行名单，不算第二次执行 |
| test-typescript-core | Rust 聚焦检查、实际普通/fixture ABI、取消/生命周期组件 |
| test-typescript-inputs / handoff | 公共 capture；删除活输入后真实 Go 重放 |
| test-typescript-prepare / provider / resolution / spelling | 准备、普通/prepared 产品、多上下文与解析观察 |
| test-typescript-graph | 独立实际 SDK 归档通过 Store/query；不能替代 CLI 产品门禁 |
| test-typescript-product | 真实 CLI 子目录/JS↔TS 调用、源码/注释、历史/续页、strict、复用、删除 |
| tests/multilang_analysis_e2e.py | TS 与 Java/Rust/Python 的实际组合；公共单候选/一次 CAS，与最后全量对照 |

本机阶段证据见 [当前实现](../current-state.md)。任务故障矩阵和实际分发仍在实施；组件、build-only、历史回执不代表当前正式 Native、跨平台或性能放行。
