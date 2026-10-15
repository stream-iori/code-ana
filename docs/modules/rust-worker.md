# Rust 源码分析

Rust 使用 `lang-rust-adaptor` 中的 rust-analyzer 0.0.349，由 `crates/rust-worker` 独立进程承载。CLI、任务调度、GRA1、图库、历史版本和查询沿用公共实现。

```text
显式 Cargo metadata / 已有 build messages
                   │ export-rust-inputs.py（不编译）
                   ▼
.code-ana/rust-inputs.json + 项目内依赖/生成源码
                   │ 校验 controls SHA256，冻结源码
                   ▼
codeana-rust-worker → rust-analyzer HIR → bounded rows / IPC
                   │
Rust-only ────────────► GraphStream → private candidate → 校验 → CAS
Java + Rust archives ─► 合并 candidate → 校验 → CAS
```

## 选型与依赖

| 方案 | 适用性 |
|---|---|
| rust-analyzer syntax + HIR | 采用；同一数据库处理模块、crate 图、cfg、重导出、类型绑定；声明宏仅保留源码定义 |
| rust-analyzer LSP 进程 | 适合编辑器；本项目已有 worker 生命周期和冻结输入协议，不额外引入 LSP 工作区加载行为 |
| syn / tree-sitter | 适合语法提取；不能单独承担跨 crate 名称、类型和调用绑定 |
| rustc 内部 API | 语义最贴近编译器，但工具链耦合及编译驱动不适合本项目的输入/执行边界 |

`ra_ap_*` 固定为同一个 `=0.0.349`，兼容当前 Rust 1.96。Salsa 与其宏固定 `0.28.2`；`unicode-ident=1.0.24` 与 lexer 的 Unicode 17 匹配。升级时成套运行 workspace、真实 Rust E2E 和 dogfood；不把内部 API 当稳定接口。数据库构造集中在 `facade.rs`；不启动 Cargo loader 或 proc-macro server。

## 配置与运行

```toml
schema_version = 1
kind = "project"
[languages.rust]
enabled = true
mode = "semantic"
[languages.rust.discovery]
inputs_manifest = ".code-ana/rust-inputs.json"
[resources.index]
worker_memory_bytes = 268435456 # 256 MiB
[scan]
scopes = ["MAIN", "TEST"]
include = ["**"]
exclude = ["**/examples/**", "**/benches/**"]
[languages.rust.dependencies]
include = ["**"]
exclude = ["tests/**", "examples/**", "benches/**"]
```

```sh
# 先由开发者准备 Cargo.lock 和依赖；以下导出只执行 metadata/rustc 查询。
python3 scripts/export-rust-inputs.py --project /path/to/project
# 如已显式构建，可带 --build-messages /path/to/cargo-messages.json
codeana-cli --project /path/to/project index
codeana-cli --project /path/to/project resolve answer
codeana-cli --project /path/to/project calls main
```

输入清单为 schema 2；旧 schema 1 需重新导出。导出默认 `cargo metadata --offline --locked`。`--cargo-config` 可重复传入 Cargo 的命令行配置，不读取或回显用户凭据。依赖源码和已有生成 `.rs` 复制到清单旁的项目内目录。索引只消费冻结文件；不运行目标项目的 `build.rs`、过程宏或 Cargo 编译。Cargo 控制文件或 Rust 输入选择策略变化要求重新导出；仅修改预算/报告设置不要求重新导出，也不改变语义 profile。缺失清单返回 `BUILD_INPUT_REQUIRED`。显式 `modules.worlds` 的 target/features/default_features/cfg 参与导出，未指定时使用 host/default features。

`analysis.toolchain` 当前仅接受 `auto`；可用 `--cargo` / `--rustc` 显式选择执行文件。目标平台内置 cfg、test 和 feature 不允许通过额外 cfg 覆盖。带 build messages 时只接受一个配置 world，且调用方必须提供与该 world 对应的已有构建消息；当前不验证其完整构建来源链。

## 已实现与限制

| 能力 | 行为 |
|---|---|
| 声明 | 函数、struct/enum/union、trait、impl、字段、variant、类型别名、const/static、模块、声明宏；保留 Rust kind/visibility |
| 引用与调用 | HIR 已绑定的跨文件/跨 crate/别名/重导出目标；外部目标单独表示；未绑定调用保留调用点 |
| cfg/world | 同一物理文件按 crate/target/feature/test 建独立虚拟文件和 ID；semantic 不发布 cfg 禁用声明 |
| trait/impl | impl 节点到 trait 的 `IMPLEMENTS`；直接可绑定调用发布目标 |
| 声明宏 | 保留源码宏定义；不主动展开或发布生成事实，调用记录 `RUST_MACRO_EXPANSION_DISABLED`；HIR 内部仍可能按需展开，过程宏不执行 |
| 过程宏 / trait object | 不执行过程宏；不能确定派发时保留未解析调用和 coverage 缺口，不猜测运行时目标 |
| syntax | 仍需输入清单，输出语法声明并标 `RUST_SYNTAX_ONLY`；不宣称语义完整 |
| 标准库 | 当前导出器尚不装载 sysroot 源码，明确 `RUST_SYSROOT_MISSING`；标准库类型和调用可能无法绑定 |
| 缺构建输出 | `RUST_BUILD_OUTPUT_MISSING`；已有显式 build messages 中的 cfg/env/OUT_DIR 可冻结输入 |
| 混合项目 | 合并两种语言的已封口 archive，一次发布；Java 子任务当前全量分析，Rust 可输出 delta |

coverage/diagnostics 按文件和 world 保存。任何 partial 使 `index --strict` 拒绝发布并保留旧 head。`derive` 保守标为过程宏缺口；不将缺少诊断视为完整编译检查。查询只读取已发布图，不现场重启分析器。

## 增量与测试

`rust_source_states` 保存源摘要、接口摘要、声明名；`rust_analysis_dependencies` 保存已绑定文件/符号和成功/失败查找。接口、宏或 impl 环境变化使依赖方失效；普通函数、impl 方法及内联模块中的函数体修改可仅替换所属文件。同一文件的全部 world 一起替换，以适配公共 store 的文件替换边界。删除/零输出仍有 replacement。

输入未变且 profile 相同时跳过 HIR/事实生成，正常备份、验证及发布候选；其他任务重新建立 HIR 数据库，扫描源码声明、导入和宏定义接口，再只对受影响 owner 提取引用和调用；未变 owner 复用持久化依赖摘要与原图。函数体修改有单文件验证；接口/导入变化保守传播至下游 crate。不跨进程复用 Salsa，不承诺跳过 HIR 内部的全部依赖计算。

| 验证 | 入口 |
|---|---|
| 单元 / 解析集成 | `cargo test -p codeana-rust-adaptor` |
| 真实 fixture | `fixtures/rust/workspace`：双 crate、别名/重导出、trait、宏禁用、cfg；`dependency-depth`：项目 → A → B → C，仅保留项目与 A |
| 公共产品路径 | `python3 tests/rust_language_e2e.py`：真实 worker、world、查询、delta/full、strict、过期清单；build.rs 陷阱证明未编译 |
| 自身源码 | `scripts/rust-source-dogfood.py`；当前源码功能回执 `optimized-functional`，固定旧输入 512 MiB 单轮 `optimized-512m`；256 MiB 失败 `memory-v2` 保留 |

物理 schema 9、graph model 3、worker 20。Java 公开 ID 算法不变；旧图库需 `index --full`，旧 bundle 必须按当前协议重建。开发回归和 dogfood 不等于 Java Native 正式验收、Linux 放行或完整 Rust 语义支持。

## 输入与预算边界

```text
已选项目 target → Cargo 直接依赖（只辅助解析）
                         ╳ 不加载其下游源码
```

导出器先选模块/scope/world，再计算一层依赖；未选 TEST/example/bench 不创建独立实例。保留集合内部已有的依赖边保留，不沿边递归扩张。截断上下文记录 `RUST_DEPENDENCY_DEPTH_LIMIT`，无法绑定的重导出和调用保留 unresolved；不把它们当完整语义。

项目 `scan` 按项目相对路径匹配；`languages.rust.dependencies` 按依赖包内路径匹配，include 默认全部、exclude 默认空，exclude 优先。模块 scan 叠加约束。规则在读取/复制源码前执行；schema 2 保存辅助源码白名单，索引不扫描依赖复制目录，旧副本不进入 HIR。项目 `text_only` 也不加载到语义数据库。必要模块被过滤记录 `RUST_INPUT_FILTERED`；所有项目根被过滤返回明确错误。

过滤共用 Rust globset：显式 Python 导出器调用安装在旁边的 worker `--prepare-inputs` 只做配置/路径选择。开发目录使用稳定的 `target/debug/codeana-rust-worker`；可用 `--worker` 指定路径，需先构建 workspace。该准备入口不创建 HIR 或执行目标构建。

Rust 请求内存默认 256 MiB。项目 `resources.index.worker_memory_bytes` 可设置 16 MiB–4 GiB，必须不超过用户 home 的 `limits.max_worker_memory_bytes`（默认 256 MiB）；提高用户上限不会自动提高项目默认请求。混合任务仍按现有总预算分配，记录各 worker 实际限额。`doctor` 输出项目请求与服务上限；超限拒绝，不自动加预算。

例如为较大项目申请 512 MiB，项目 `.code-ana/config.toml` 设置：

```toml
[resources.index]
worker_memory_bytes = 536870912
```

独立用户 home 的 `config.toml` 同时允许该预算：

```toml
[limits]
max_workers = 1
max_worker_memory_bytes = 536870912
max_total_worker_memory_bytes = 536870912
```

修改用户服务上限后重启对应 daemon；只修改项目预算无需重导 Rust 输入。单轮固定预算测量保留原回执，后续提高预算的功能验证单独保存结果。

worker 内存错误包含 `limit_bytes/rss_bytes/peak_rss_bytes/stage/process_group`；峰值是 100 ms 采样结果，不能当绝对峰值。阶段包括输入、数据库加载、声明、绑定、归档和传输。任务进度同时记录 `rust_input_summary` 和成功后的 `rust_worker_memory`，失败前已有的进度也保留。

`rust_result_bytes`（64 KiB–1 GiB）和 `rust_fact_limit`（1–2,000,000）控制输出预算，不进入符号身份。`RootDatabase` 的 LRU setter 在固定版本中为空实现，不作为内存限制。旧宏生成事实因 adapter/profile 变化首次全量重建后清除；物理 schema 升至 9；worker 20 / graph model 3 不变。

## 流水线与诊断

```text
blocking HIR → 声明/绑定 → 增量与替换范围
                                  ↓ Begin
blocking 行生成 → 有界队列 → async IPC → 有界 GraphStream → blocking SQLite
                                                        ↓ VerifiedEnd
                                             校验 → blob → 原子发布 → ACK
```

不增加 HIR 并行度或常驻 Salsa。按文件缓存行首索引和声明/关系分组，任务内缓存源码定位与 canonical path；位置保持一基 UTF-16。接口摘要排除执行体，关联常量、签名、导入和 impl 头仍参与失效。尚未开始 HIR 的无变化 worker 正常退休。

队列每侧默认 4 批 / 8 MiB，最多 64 行/批，Rust 按 256 KiB 常规切批；大行独占，详见契约。`rust_stream` 披露 daemon 队列估算峰值、发送等待和消费等待；`rust_worker_memory` 包含 worker 的 phase_ms 与队列摘要。`record_validate_ingest` 包含等生产者的时间，与分析/传输有重叠，不能将阶段直接相加。

自身验证继续使用 `scripts/rust-source-dogfood.py`：`--functional` / `--measure` 分开用途，`--worker-memory-mib 512` 同时配置项目和独立用户上限，`--profile debug|release` 选择已构建二进制。`--reuse-inputs --reference-evidence <旧证据目录>` 验证并复用冻结源码及输入，不重导。已有 marker/receipt 或非空 home 一律拒绝覆盖。回执含耗时、同一时刻 daemon+worker 树 RSS 总和、独立峰值、SQLite/dbstat 与 blob 大小；保留原失败回执。

固定旧输入本轮：内部 index 54.453 s，facts.db 9,777,152 bytes（9.32 MiB），监督器 worker 峰值 339.67 MiB；公开图和 109,394 条增量依赖与旧参考逐项相等。单样本、partial，不作为稳定性能放行；原始回执及各阶段口径见当前实现文档。
