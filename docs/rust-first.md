# Rust 实现与本地验收

**Rust 主链路可用独立 mock-worker 验证。集成测试按场景隔离，并验证发布崩溃窗口、迁移回滚和报告失败；diff 保持原行为，暂不扩展。真实 Java 8 的实现和验收见 [Java 实现说明](java-implementation.md)。**

```text
clap CLI ── authenticated gRPC ── Rust daemon
                                  ├─ 配置 / 捕获 / 任务 / 取消
                                  ├─ worker 专用 FD，长度前缀 protobuf
                                  │       └─ Rust mock-worker：匹配 fixture 输入 SHA-256
                                  ├─ SQLite candidate → immutable revision → catalog CAS
                                  └─ 查询 / pipeline / snapshot / diff / report / annotation / GC

保留：Rust java-worker ── C ABI ── Java 25 Native Image Hello
生产：真实 Java 8 javac Fact IR producer；mock 保留独立回归
```

## 运行

不需要 JDK：

```sh
just test-rust
just demo-rust
```

手动运行时，两个终端使用同一个绝对 `--home`。必须复制 fixture；随意增加文件、改配置或源码都会导致输入哈希不匹配。

```sh
cargo build --workspace --locked
cp -R tests/fixtures/java/base /tmp/codeana-example-project
./target/debug/codeana-daemon \
  --home /tmp/codeana-example-home \
  --dev-mock-fixtures "$PWD/tests/fixtures/mock.json"
```

另一终端：

```sh
cli=./target/debug/codeana-cli
"$cli" --home /tmp/codeana-example-home --project /tmp/codeana-example-project index
"$cli" --home /tmp/codeana-example-home --project /tmp/codeana-example-project calls main
"$cli" --home /tmp/codeana-example-home --project /tmp/codeana-example-project call-sites main
```

默认生产目录为 `~/.code-ana`。普通 CLI 可启动同目录的 daemon；生产模式使用已安装的 Java bundle，缺失时返回 `UNSUPPORTED_ADAPTER`，不会自动启用 mock。mock 必须显式指定独立 home，home 内模式标记禁止与生产数据混用。

## 命令

所有查询可使用 `--revision <revision-id>` 或 `--revision snapshot:<name>`。省略 revision 时使用当前 checkout 最新入口；`source` 校验当前文件，历史读取捕获 blob。

| 命令 | 示例 / 行为 |
|---|---|
| index | `index --full`；默认兼容 profile 才走 delta；`--strict` 拒绝 partial coverage 发布 |
| doctor | 输出生效 generation、有效配置、字段来源、模块与当前能力；无语言项目也可运行 |
| resolve / search-code | 精确身份优先；名称选择；`--unique` 拒绝歧义；search-code 为字面子串 |
| structure | `structure app --depth 3` |
| calls / call-sites | `calls main` 去重关系；`call-sites main` 保留每次调用；`--direction in` 反向 |
| references / annotations | 引用出现；已发布的 Java 注解 facet |
| type-relations / callable-relations | 继承、实现、覆盖事实；可指定 `--relation` |
| runtime-implementations / dispatch | 静态可能候选，附 proof；明确 open-world 限制；dispatch 输入 call-site ID |
| trace | 有界调用遍历，`--depth` 上限 32 |
| source / search-text | 源码按 UTF-8 字节范围分块；工作区文本搜索独立捕获，可由 `search.text.include` 选中 `text_only`，无需语义索引；历史只搜原快照文件 |
| overview | 固定修订的事实计数、profile、coverage 证据 |
| pipeline | `pipeline /path/stages.json`，最多 16 个类型校验阶段 |
| snapshot | `snapshot before --name files` 可不启用语言；`--name analysis` 仍需适配器；`--target HEAD` 捕获 Git commit；保存有效配置与捕获策略 |
| snapshot-list / snapshot-remove | 列出/删除命名快照引用 |
| diff | 旧命令保持：`diff --base before --target after --name files`；另有 declarations / relations / impact，本轮只做回归，不扩展能力 |
| report | `report <task-id>`；`--base <task-id>` 比较同 profile/捕获策略/级别的指标，旧版无 summary 报告明确拒绝比较；`--export` 只写新路径 |
| annotate | `annotate app --name add --text reviewed`；list 可用 `--revision` 固定图，返回 annotation revision 与绑定；update/remove 用 `--base <note-id> --expected-revision <version>` |
| gc / recover | 默认预览；显式 `--execute` 修改；recover 要求无其他活动任务 |
| task | `task status/cancel/watch <task-id>`；watch 可用 `--after-seq N` |
| daemon | status / drain / stop / restart；mock 重启需显式 fixtures |

pipeline 示例：

```json
[
  {"operation":"resolve","selector":"main"},
  {"operation":"call-sites"},
  {"operation":"source"}
]
```

同一 pipeline 固定一个 ReadContext。非法类型连接在读取前拒绝；下游空结果不会清掉上游 coverage 缺口。

分页通过 `--cursor` 续读；保持原命令和选择参数，可调整展示条数和字节预算。结果冻结 24 小时，工作区变化、发布新修订都不改变续页。过期明确失败，不自动重跑。GC 清理过期页及其引用；任务输出事件保留 24 小时，任务身份/发布终态继续保留，过期 watch 返回 TASK_EVENTS_EXPIRED。报告默认保留至少 30 天。

## 输出与任务

```text
codeana-text-v1
record { ... }
terminal { "execution":..., "publication":..., "evidence":... }
```

`record`、`terminal` 后为单行 JSON，用于固定文本 v1 的转义和机器读取；RPC 任务终态仍是类型化 protobuf。stdout 为结果，stderr 为进度。退出码：0 成功、1 错误、2 CLI 参数错误、3 strict 门禁拒绝、124 超时、130 取消。

| 状态边界 | 实现 |
|---|---|
| 幂等提交 | 同 request ID + 同参数返回原任务；参数变化拒绝 |
| 断连 | 提交/最后一个 watcher 断开后 10 秒宽限；`--background` 保留任务 |
| 调度 | 每项目同时一项分析、同目标写入串行；只读查询可并发；全局有界执行槽与 8 任务队列 |
| 发布 | 所有块、计数、摘要、owner、引用、coverage 校验后才发布 |
| 发布后中断 | catalog commit 优先，响应超时/ack 失败不能撤销发布 |
| 重启 | 未提交任务失败终止；已提交任务恢复 published，不重复执行 |
| 认证 | 动态 loopback 端口、随机 token、0700 home、flock 单实例 |

## 模块和数据

| crate | 职责 |
|---|---|
| model | 身份、Fact IR、owner 替换与证据 |
| contracts | task/worker protobuf、大小受限的帧、领域映射 |
| config | 严格 TOML、显式语言、模块/源码范围、输入捕获 |
| application | 调度、worker 监督、发布编排、历史与运维 |
| storage | SQLite、blob、catalog、任务事件、人工标注 |
| query | 面向 FactReader 的查询和 pipeline，不依赖 SQLite |
| cli / daemon | 命令行解析 / gRPC 接入 |
| mock-worker | 已知输入回放与协议故障注入，不包含 Java parser |
| java-worker | Java 8 native/javac 已接入；范围见 Java 实现说明 |

当前 home 共享一个 catalog，以 project/checkout/target 隔离；每个 revision 独立 SQLite。catalog schema 2、annotation schema 2 会事务升级旧库；旧标注正文与版本保留，旧记录没有可靠锚点时显示 `unbound`。GC 删除中断留下的 `cleanup_pending` 由 recover 重试。配置、报告、快照和冻结页是 catalog 中的版本化对象。

## 有界执行及首版限制

| 项目 | 当前实现 / 限制 |
|---|---|
| 输入 | 配置文件最多 1 MiB，每文件/IPC 帧 8 MiB，单捕获/结果 64 MiB，最多扫描 100,000 项、捕获 10,000 文件、接收 100,000 事实；符号链接输入拒绝 |
| 并发 | 默认 2 个 worker，最多 8 个活动/排队任务；用户 max_workers 上限 8 |
| worker 内存 | 默认每 worker 256 MiB；按独立进程组 RSS 每 100ms 调度采样，超额终止；不是内核级硬隔离 |
| 超时 | 默认 120s，最大 3600s；捕获、查询、发布和 Git 读取在阻塞线程运行；查询在选择器/源码读取边界检查取消，正在执行的单条 SQLite 语句尚无中断钩子 |
| 输出 | 默认 100 条、32 KiB；最多 10,000 条、8 MiB；至少 2 KiB 用于终态和记录 |
| 发现 | mock 只支持显式 Java source_roots；`discovery.mode=auto` 索引明确报不支持。缺输入返回协议 3 的结构化 BuildAdvice，cwd 指向用户 checkout；Maven/Gradle 有效模型和真实工具链识别仍待 Java 适配器 |
| 增量 | Rust 真正执行 backup + owner 替换；mock 用全 owner 替换验证冷基线，不宣称语义失效算法 |
| 高级语义 | 消费已有 overrides/implements 等事实；不在 Rust 重做 javac 类型推理。派发候选、impact 明确可能性 |
| Git | 只读解析 commit/tree/blob；不运行 checkout、构建、钩子或 annotation processor |
| diff | 文件 hash；声明/关系语义多重集；不推断重命名。位置独立 ID 的完整跨版本对应仍需要 Java producer 契约 |
| GC | 引用可达性回收 revision/blob；按项目 keep/年龄/图文件字节预算回收，失败登记待清理并恢复；命名快照/未过期报告/读者保护引用，预算不足返回 budget_unmet。字节预算当前只计 facts.db，不是整个 home 的硬配额 |
| 标注 | 精确事实 ID、world、来源 hash 锚定；目标图变化时显示 bound/ambiguous/orphaned/unavailable。标注进入 typed pipeline 尚未实现 |
| 报告 | off/basic/full；诊断总量、分组、至多 100 条样例与省略数；自动报告上限 4 MiB。显式导出不可覆盖；没有 Java 原始诊断时 mock 不能验证诊断质量 |
| 平台 | 本轮 macOS 实测；Linux 分发未验收；Rust 源码分析仍为 P3 |

`docs/config.toml` 保留目标完整 schema 示例。schema 接受不等于对应 adapter/自动 GC 能力已验收；doctor 与本页列出当前能力。真实 Java 的 BuildContext、JDK 8/17/25、Lombok/Spring/SOFA、签名缓存、native/compiler bridge 均不能用 mock 测试代替。

## 验证入口

| 验证 | 命令 / 证据 |
|---|---|
| Rust 单元/文档测试 | `cargo test --workspace --locked` |
| 格式与静态检查 | `cargo fmt --all -- --check`；`cargo clippy --workspace --all-targets -- -D warnings` |
| 真实进程闭环 | `python3 tests/rust_e2e.py`；各用例独立 home/daemon，覆盖查询、增量/全量等价、历史、Git、分页、认证、故障、取消、恢复、重订阅和队列上限 |
| 本地演示 | `scripts/demo-rust.sh`，独立临时目录和进程，退出后清理 |
| Rust 分发 | `just test-rust-release`，构建 release 包、验证全部 SHA256SUMS，以包内二进制和 fixture 运行无故障注入的端到端测试；manifest 标出 mock-only |
| Java Hello | `scripts/smoke.sh`，需已配置 GraalVM JDK 25；与 Rust 验收独立 |

fixture 原始输入和人工编写的查询预期见 `tests/fixtures/`；`build_mock.py` 仅生成固定测试数据，不是从任意源码提取语义的解析器。发布、迁移和报告故障点仅在 debug 构建的显式 mock home 下响应 `CODEANA_TEST_FAULT`；release 构建无此路径。

## 本轮实测记录（2026-09-29，macOS arm64）

| 项目 | 结果 |
|---|---|
| workspace 单元测试 | 24 个通过；所有 doc-test 通过 |
| clippy / fmt | 零警告通过 / 格式通过 |
| CLI 真实进程回归 | 14 个独立场景，开发版全部通过；release 包 8 个适用场景通过，6 个 debug 故障注入场景跳过 |
| Java 原生 Hello | GraalVM CE 25.3.4.1+1.1，构建共享库成功；两条 Hello 均通过 |
| Rust release 包 | `just test-rust-release` 通过；`dist/codeana-rust-Darwin-arm64/` 全部 SHA256SUMS 验证通过，manifest 标明 mock-only |

本次重跑已确认 `127.0.0.1` 绑定成功，debug 与 release 门禁均通过。上述结果没有验证真实 Java 语义或大型项目性能。
