# 当前实现与验证边界

对齐日期：2026-11-18。源码为准，历史回执按其提交和产物解释。

| 边界 | 当前实现 | 核查入口 |
|---|---|---|
| 服务 | Rust CLI → loopback gRPC → 用户级 Rust daemon | `crates/cli/`、`crates/daemon/`、`proto/codeana/v1/task.proto` |
| 分析 | Rust 独立 worker → C ABI → Java 25 Native Image → 目标 Java 8／17 javac 子进程 | `crates/java-worker/`、`lang-java-adaptor/` |
| 后端 | javac 唯一后端；显式 JVM 后端供开发/诊断，无自动回退 | `crates/application/src/java.rs` |
| GC | Oracle GraalVM G1 默认；每进程仅创建一次 isolate，健康任务复用，故障后换进程 | `crates/java-worker/src/native.rs` |
| 输出 | 内部 GRA1 + protobuf Row；CLI 默认 text，显式 JSON 用于测试与结构化导出 | `crates/application/src/presentation.rs`、`proto/codeana/v1/task.proto` |
| 存储 | 不可变 SQLite revision；私有 candidate 校验、落盘后 catalog CAS | `crates/storage/src/graph_store.rs`、`crates/storage/src/lib.rs` |
| 紧凑布局 | 整数引用、路径/外部目标字典、重复大 metadata 共享、元注解定义/归属分离 | `crates/storage/src/compact-schema.sql`、`compact-meta.sql` |
| 配置 | 显式启用 Java；plain 显式源码根，Maven auto 需已导出的 inputs_manifest；仅解析字段另标注 | `crates/config/src/lib.rs` |
| Rust 源码分析 | rust-analyzer 0.0.349 独立 worker；冻结输入、声明/引用/调用、world 与增量替换；sysroot/过程宏/动态派发仍有缺口 | [Rust worker](modules/rust-worker.md) |
| Python 源码分析 | Pyrefly 1.4.0 独立 worker；显式冻结输入、声明/引用/调用、保守增量与多语言发布；动态目标保留未知 | [Python worker](modules/python-worker.md) |

## TypeScript T3 等价清单与普通计划（2026-11-18）

| 已实现 / 验证 | 结果 |
|---|---|
| 旧式 JS/TS 原生清单定位 | 内容先规范化比较；位置、JSON 排版和文件枚举顺序不形成新 context；两份已声明清单均捕获认证 |
| 真实闭包 | 五组实际 SDK 清单改用两份等价定位，公共 Rust capture 全部通过，无漂移 |
| 普通计划 | 使用实际冻结单元 ID；两个原生 context 保留两个实例，共用 TypeScript 后端参与者 |
| 内部路径 | scratch 由任务内部短序号命名，不把公开 ID 当作相对路径 |
| 聚焦 / CLI | 应用 68、配置 64、链接 13、观察 5、TS 输入 13 与聚焦 clippy 通过；真实 CLI 8/8，通过无漂移 |

真实回执为 `target/typescript-c-abi/t3-inventory-normalization-real-first/receipt.json`、`t3-inventory-normalization-provider-fixed/receipt.json`。首次 CLI 的 CAPTURE_UNIT_VIEW_MISSING 原日志保留；已补普通多 context 计划测试并修正。首次三语言普通矩阵 9/10：三后端更新触发 Java 主机账户 payload 份额上限；三个 worker 均正常完成。单例对照只将长摘要目录名改为短序号，原 fixture/预算不变，全部更新与全量等价断言通过，Java 主机最终用量 44,399,190 / 44,730,549 字节。原报告保留于 `t3-inventory-normalization-quota-audit`，对照报告在 `t3-inventory-normalization-short-scratch-audit`。修正后的三语言普通矩阵 10/10、workspace JS/TS CLI 8/8 及完整 just test-rust 均通过，无产物漂移。汇总回执为 `target/typescript-c-abi/t3-inventory-normalization-sealed/receipt.json`。清单必须语义等价；不同内容仍拒绝，不将不同发布选择自动合并。

## TypeScript T3 多配置与单元选择（2026-11-18）

| 已实现 / 验证 | 结果 |
|---|---|
| 多 tsconfig、同文件多上下文 | CLI 7 场景通过，两个 context 的 unit/world 独立；同一模块策略可共享，冲突仍拒绝 |
| 普通单元选择 | 冻结视图保留对应原生 context；按 unit 选择后仅带本 context，按后端选择多个 context 明确拒绝歧义 |
| 当前任务预算 | 单元使用捕获后当前共享任务策略；不会沿用旧预算副本 |
| 完整 Rust 门禁 | just test-rust 通过，含 workspace、CLI/图/源码/注释和 clippy |
| workspace 实际二进制 | JS/TS provider 7/7、ABI 12 场景 / 20 检查通过，无漂移 |
| 聚焦与既有后端 | 应用 67、配置 64、链接 13、观察 5、TS 输入 12 项通过；Native Java/Rust/Python prepared 19/19，通过无漂移 |

汇总回执为 `target/typescript-c-abi/t3-multicontext-sealed/receipt.json`，完整 Rust 日志为 `t3-multicontext-full-rust.log`。workspace 二进制的专项为 `t3-multicontext-provider-workspace/receipt.json`、`t3-multicontext-abi-workspace/receipt.json`。先前聚焦构建的专项证据为 `target/typescript-c-abi/t3-multicontext-provider-first/receipt.json`、`t3-multicontext-prepared-first/receipt.json` 及 `t3-multicontext-focused-policy.log`。原私有字段编译失败与预算副本回归失败保留，修正后验证通过。这里只共享完全相同的脚本模块策略，不合并 compiler context；同根存在多个不同脚本模块时明确拒绝，文件级多模块归属仍需补齐。

## TypeScript T3 project references（2026-11-18）

| 已实现 / 验证 | 边界 |
|---|---|
| 原生 reference 配置与输入闭包 | 同一内核保留有序 reference 路径、读取引用配置、映射源码与声明产物；引用输入保持辅助角色 |
| 有声明产物 | 真实调用绑定至引用项目 `.d.ts`；删除活输入后仍按冻结视图重放 |
| 缺声明产物 | 记录真实失败查找，两次调用都保持 unknown；不进行源码重定向，不生成声明、不执行目标构建 |
| 发布隔离 | 引用输入不能作为消费者发布内容；伪造 reference 顺序/身份会被拒绝 |
| 新 SDK 与 Rust host | adapter 修订 inputs3，旧 inputs2 SDK 已实际验证拒绝；ABI / 引擎协议仍为 1 |
| 回归 | 5 组真实公共捕获、6 个 CLI provider、5 个独立准备、12 场景 / 20 ABI 检查；模型/契约/配置/worker 聚焦与 clippy 通过 |

本轮 SDK：`target/typescript-c-abi/t3-references-sdk` 与 `t3-references-fixture-sdk`。汇总 `t3-references-sealed/receipt.json`，专项为 `t3-references-public-first/receipt.json`、`t3-references-abi-first/receipt.json`、`t3-references-provider-first/receipt.json`、`t3-references-prepare-first/receipt.json`；原始构建/测试日志保留。

五组共享 fixture 增加有声明产物的 reference；发布源数量按独立 fixture 真值核对。空根 solution、多配置编排、缺产物的产品 BuildAdvice/partial、冻结输入交接以及 T4—T8 仍待实现。CLI 尚不发布 JS/TS 语义图；无正式 Native、分发或性能放行。

## TypeScript T3 解析闭包真值（2026-11-18）

| 自建 fixture | 通过的行为 |
|---|---|
| extends / paths | 基础配置与路径别名进入实际捕获闭包 |
| pnpm / exports | 相对符号链接保留，types 条件选择声明文件，依赖保持辅助角色 |
| NodeNext / imports | package.json 的类型与 imports 条件选择正确声明目标 |
| typesVersions | CommonJS / node10 按版本映射选择声明文件 |

同一份 `overlay/engine/testdata/resolution.json` 驱动 Go 与公共 Rust 捕获测试。Go 每组删除全部实际输入后，冻结 Checker 仍给出两次正确调用目标；重复发现 inventory 完全一致。Rust 每组校验控制文件及失败查找变化会拒绝旧准备清单，删除活输入后仍能读取全部已封存正文。两个入口均实际执行四组，无跳过；不表示公开调用图已交付。

回执：`target/typescript-c-abi/t3-resolution-shared-go/receipt.json`、`t3-resolution-public-first/receipt.json`。Go 为准备构建（未生成新 SDK），公共捕获使用已校验 inputs2 SDK，四组均捕获 4 个正文；pnpm 与 typesVersions 各发布 1 源，另两组各 2 源。references、多配置、缺声明产物及产品冻结输入交接继续实施。

## TypeScript T3 真实准备入口（2026-11-18，继续实施）

| 已验证 | 证据目录（target/typescript-c-abi 下） |
|---|---|
| 正常 / fixture inputs2 SDK，各 27 项 Go 测试 | t3-sdk-preparer2-build / t3-sdk-preparer2-fixture-build |
| 独立 worker 准备 5 场景 | t3-prepare-entry-real-first |
| CLI provider 6 场景，共享 JS/TS 单元与别名、冲突保留清单 | t3-provider-matrix-first |
| 新 SDK ABI 12 场景 / 20 检查，真实公共 capture | t3-preparer2-abi-matrix / t3-preparer2-public-capture |
| Native Java/Rust/Python prepared 19/19；Python 产品 7 项 | t3-catalog3-prepared-fixed / t3-report5-python-product.log |
| 完整 just test-rust、Python 独立核心/clippy | t3-provider-full-rust-first.log / t3-provider-python-core.log |
| 应用 66、配置 63、链接 13、观察 5、TS 输入 12、存储 87，聚焦 clippy | t3-catalog3-consumers-focused.log / t3-provider-consumers-clippy.log |

公共准备清单升级为 3，准备摘要 2，索引报告 5；单元显式区分 backend/source_languages。旧清单拒绝；历史注释不可用输入按实际源码语言归属。原 CLI 路径错误、报告版本与旧注释字段失败均保留，不覆盖旧回执。

CLI 通过新 worker 调真实 Go 准备，不在 CLI/daemon 装编译器；大小写规则按目标卷探测，`.code-ana` 不进入逻辑目录。当前准备成功后语义执行仍明确返回 TYPESCRIPT_INPUT_NOT_READY；references/pnpm/复杂配置和 T4—T8 继续实施。没有重新构建 Java Native 或进行性能测量。汇总回执：`target/typescript-c-abi/t3-provider-sealed/receipt.json`。以下小节保留各检查点验证范围。

## TypeScript T3 配置与公共 capture（2026-11-18，部分出口已验证）

| 当前实现 | 实际证据 |
|---|---|
| 显式 JS/TS 配置 | 独立 enabled、共用一份原生清单和有序依赖根；禁用标签不能发布；缺清单时报错，FILES 无需语义清单 |
| 单元和冻结 | 每个原生 compiler context 拥有独立捕获视图；文件、控制、辅助角色与 world 保留；发布过滤不移除 Checker 必需字节 |
| 文件系统观察 | 公共捕获器区分 file/directory/other/缺失；目录存在不等于完整枚举；精确外部缺失观察不授权相邻文件内容；封存时复核 |
| 验证 | 配置 63、声明链接 13、类型观察 5、JS/TS 输入 11 项通过；完整 `just test-rust` 与 Python 独立 workspace 核心/clippy 通过 |
| 真实清单 | 固定 Go SDK → protobuf inventory → 原生 JSON → 公共 Rust capture；3 个必要正文封存后删除活文件，全部捕获正文仍可读取；2 个发布源、1 个实例，产物无漂移 |

证据：`target/typescript-c-abi/t3-config-capture-sealed/receipt.json`，真实门禁为 `t3-actual-public-capture-first/receipt.json`。独立真实编译器用例在无 Go 的普通 Rust 门禁显式 ignored，在 `just test-typescript-inputs` 中强制执行，已单独通过；Java 的既有真实 G1 teardown 用例仍按 SDK 专项入口验证，不计为本轮 Native 放行。原测试编译错误及多上下文整份/单份清单比较失败保留。

T3 尚未完成：references/pnpm/复杂配置真值和完整闭包仍待接入；后续 T4—T8 公开语义、产品、故障和实际分发继续实施。配置/捕获成功不代表现在可用 CLI 索引 JS/TS。本轮没有重新构建 Java Native 或进行性能测量。

## TypeScript T3 输入发现组件（2026-11-18；产品接入继续实施）

| 已验证 | 证据 / 边界 |
|---|---|
| 同内核 prepare | 显式允许根、实际配置/模块解析、文件摘要、目录存在与完整枚举、链接、失败查找；只捕获编译器实际使用的内容，不复制整个依赖目录 |
| 冻结重放 | 捕获后删除活输入仍得到两次真实调用绑定；大小写使用固定内核规则，保存原始拼写；目录矛盾、链接越界、未观察读取和写入尝试均拒绝 |
| 本轮验证 | 两个新 SDK 各 25 项 Go 测试；Rust 聚焦 contracts 37 / runtime 41 / worker 1 与 clippy 通过；空 PATH 实际 ABI 组件 12 项、ABI 检查 20 项通过，产物无漂移 |
| 待办 | 配置显式开关、公共 capture/provider、多配置、references/pnpm 真值与产品路径；当前不支持 CLI JS/TS 索引 |

证据：`target/typescript-c-abi/t3-input-component-sealed/receipt.json`；实际组件回执为 `t3-components-prepare-first/receipt.json`。原 fixture SDK 构建在生成器版本列表请求遇到代理 504；失败日志保留。构建器改为使用同一已校验模块锁构建生成器，不再查询无关版本列表，两个 SDK 重建通过。本轮没有重跑完整 Rust/Python 或 Java 门禁，也不表示 T3 全部、正式分发或性能验收完成。

### T3 原生清单与身份组件

`model::typescript::Inputs` 已与私有 protobuf 分开，包含内容身份、上下文、发布/辅助/控制角色、目录完整性、链接和失败查找。SDK 的实际库/header/标准库/proto 摘要进入语义身份，不包含安装位置或准备 generation。枚举顺序规范化；root files/reference 顺序保留。单一上下文允许同时发布 JS/TS；同文件可有独立 world，内容冲突拒绝。

Rust 在 prost 解码 inventory 前限制大小、字段数、重复单值字段、必需字段、枚举与 bool，再映射到严格原生模型。实际 ABI Prepare 已生成并校验 `native-inputs.json`，核对库摘要来自实际 SDK。模型 79、contracts 39、TS worker 3 项聚焦测试与 clippy 通过；当前二进制复验 12 个组件场景和 20 个 ABI 检查通过。证据：`target/typescript-c-abi/t3-native-inputs-sealed/receipt.json`。保留原枚举变体编译错误和 clippy 失败；配置/provider/capture 生产调用仍待接入，未完成整个 T3。

## TypeScript T2b 组件（阶段历史，2026-11-18）

Go SDK 与独立 Rust worker 已接通共享 Owner/Notification/BudgetHandoff/graph-io。固定来源构建、同源 Go/Rust protobuf 与双 header 检查通过；Go 核心/会话 16 项、Rust contracts 37 / worker-runtime 41 / TS worker 1 项通过。实际组件门禁 11 项、ABI 边界 20 项通过，无源码/SDK/二进制漂移，见 `target/typescript-c-abi/t2b-components-fixture-first/receipt.json`。

组件验证覆盖真实 Checker 的两次别名调用、UTF-8 位置真值、5 行原始 GRA1、累计回执、预算失败、非法请求和取消；隔离 fixture SDK 在真实 Go goroutine 中触发 panic，并以 barrier 验证运行中关闭 Pending 时不释放所有权。普通 worker 拒绝 fixture SDK。当前图仅为显式 partial 的绑定诊断，不冒充声明/调用语义；无产品索引、正式分发或性能放行。本轮完整 `just test-rust`、Python 独立 workspace 核心/clippy 通过；Rust 既有 G1 重建专项按其入口 ignored，未当作当前 Native 放行。汇总证据为 `target/typescript-c-abi/t2b-sealed/receipt.json`。输入准备与冻结闭包（T3）、图语义（T4/T5）、产品及任务/分发（T6—T8）继续实施，详见 [TypeScript worker](modules/typescript-worker.md)。

## TypeScript T2a 与基础准备（阶段历史，2026-11-18）

C ABI 1 header、私有 analysis protobuf 1、状态/所有权和公共设施对接已定义；SDK 的 TypeScript 变体强制校验子模块提交、编译器版本及 analysis/graph proto SHA，Java 变体保持。Rust 绑定由同源 protobuf 构建生成，普通 workspace 构建无需 Go。契约 35 项、Python SDK 校验 4 项、contracts clippy 与 workspace 构建通过。证据：`target/typescript-c-abi/t2a/receipt.json`；详见 [TypeScript worker](modules/typescript-worker.md)。该回执仅表示当时的 T2a，不替代上方 T2b 或后续产品门禁。

T2b 基础已增加 production.lock、固定来源克隆/生成工具、Go 累计账本、wire 预解码及 GRA1 写入。实际固定 Go/protoc 生成绑定后 8 项核心测试通过，Rust 契约 35 项补验通过；`target/typescript-c-abi/t2b-foundation-version/receipt.json` 状态为 prepared、sdk_created=false。该基础轮次当时未建立 ABI/Checker 组件；最新组件状态见上方 T2b，产品仍未接入。

## R5 既有三语言重构验证（2026-11-18，本机通过）

普通与 prepared 已共用分析单元、冻结视图、预算、运行监督、图交付、候选合并和一次 CAS；Java/Rust 保留自身增量策略，Python 保守全量。旧组合执行器已删除。TypeScript T2b 组件已通过；T3—T8 继续实施，当前不能索引 JS/TS。

| 当前证据 | 实际结果 |
|---|---|
| Java fixture / Rust / Python 核心 | Java 727 项、零跳过；完整 `just test-rust` 与 `just test-python-core` 通过 |
| 组合与准备 | 单语言、两两、三语言与五种更新结果共 10 项；prepared 全部 19 项通过，零跳过、无产物漂移 |
| Java 产品 | Native graph 24、JVM graph 23、Native 生命周期 10；Java 17 Native/JVM 各 3、更新后的注释归属各 2 项通过 |
| 注释及故障 | 混合注释 3 项；真实 SIGSEGV 保旧 head、Unknown 水位、worker 回收、原 SDK 恢复后索引通过，恢复前后产物 SHA 相同 |
| 实际分发 | Rust 包 33、Python 包 55 项通过，完整包文件 SHA 无漂移；Java 包验证及原开发二进制恢复通过；五个 release 可执行文件的资源/故障矩阵 42 项通过并恢复 |
| 修正与失败保留 | 原生描述符统一登记 PREPARATION，等价普通/prepared 可 Reuse；共享测试 helper 不再借旧测试类的方法，显式包路径不借开发源码补 fixture/导出器；余数分片断言按总额守恒校验。原失败全部保留 |

证据：`target/typescript-c-abi/r5/receipt.json`。实际包和每组门禁各保留独立 SHA、用例清单、日志与范围排除。Native SDK 复用同一 Java 源码的匹配 build-only 产物，本阶段未重新构建 Native Image；Java fixture 重新构建 JVM bundle。未签正式 Native/跨平台放行，没有性能结论。协议保持 daemon 7 / worker 25 / graph 6 / Java analysis 13 / ABI 4 / SDK 4，图库物理 17 / 逻辑 36，单元归属 v2、交付策略 6。

## 多语言重构与 TS 接入准备（阶段历史，2026-11-16 起）

下表记录各子阶段当时的范围及剩余项；当前既有三语言状态以上方 R5 记录为准。

准备证据：`target/typescript-c-abi/run-20261116-r0/receipt.json`；当前 T0 探针门禁见 `target/typescript-c-abi/probe-20261116-fresh/receipt.json`，Java 基线见 `target/typescript-c-abi/baseline-20261116-probe/receipt.json`。T0 已通过，不代表生产 TS ABI、公共重构或产品验收通过。

R1 身份/Discover 子阶段证据：`target/typescript-c-abi/r1-identity-wire25/receipt.json`，状态仍为进行中；worker 25 Java 基线及 JVM 产品回执分别位于 `r1-java-worker25/`、`r1-jvm-product25-retry/`。原失败及补验日志见回执映射，不覆盖原失败。

SDK schema 4 子阶段证据：`target/typescript-c-abi/r1-sdk4/receipt.json`。Native 与 JVM 已重建/安装当前格式；Native 为 `build-only` 开发 SDK，不签正式放行。完整 R1 仍未完成。

| 项目 | 本轮结果 |
|---|---|
| Java 基线 | `just test-java` 在显式 JDK 8/17/25 与 Homebrew Python 环境下通过；原默认系统 Python 不支持 `str \| None` 的失败保留于 `java.log`，通过日志为 `java-python313.log` |
| 来源 | `lang-typescript-adaptor/upstream.lock.json` 固定 typescript-go `89d5d5b2849a0db0957065889ca58536fa6d2e4a`，核对 TypeScript gitlink `5848bc5157b22ff7f4e3369f4645a514a433b15f`；Go 1.26.2 下载包已核对官方 SHA |
| 标准库 | 固定子模块上使用 `GOTOOLCHAIN=local` 执行 `go generate ./internal/bundled` 成功；来源锁记录生成文件及标准库逐文件 SHA |
| 基线工具 | 新增 `scripts/multilang-baseline.py --evidence target/NEW_DIRECTORY`，使用 Python 3.10+，显式选择 Rust 1.96.0；成功/失败/超时回执及旧目录拒绝测试通过 |
| T0 探针 | 独立 Rust workspace 调用 Go c-shared 的 ABI 1；双文件人工 expected 验证别名、两个相同调用现场、UTF-8 字节与 UTF-16 列、中文/emoji、CRLF 和无末尾换行；捕获后改删原文件、输入缓冲覆盖、坏句柄、缓冲 canary、分块/EOF/关闭检查通过 |
| T0 可重放入口 | Python 3.12+ 执行 `scripts/test-typescript-probe.py`；默认自行准备固定源码、Go 和标准库并运行 Java 基线。可显式复用同 HEAD/源码/输入/日志 SHA 核对过的基线。已有源码模式与自行下载模式均通过；后者仅复用已核对的 Java 基线 |
| T0 构建/退出 | 同模块 overlay、header 编译、导出符号/动态依赖、Rust fmt/clippy、空 PATH 主/工作线程调用、runtime 初始化后 SIGINT/SIGTERM 退出均通过；锁定来源及 SDK，不修改上游 tracked 文件 |
| R1 身份/协议 | 已拆 `SourceLanguage` / `AnalysisBackend`，JS/TS 共用后端；计划单元使用稳定 `AnalysisUnitId`，拒绝空/重复 ID。worker 升到 25：Discover 使用 oneof，缺失、重复/混合变体、错后端及同后端错实例拒绝。四后端预算守恒通过；尚未注册 TS 产品执行器 |
| R1 当前验证 | 聚焦 171 项测试及另补的同后端错实例测试通过。Rust 门禁的架构/DDL/fmt、workspace、图/CLI/Rust 产品与工具测试通过，末尾 clippy 的两处冗余默认值已修正并补验；原失败保留，不能把原 `just test-rust` 的退出码 101 改为 0 |
| worker 25 真实链路 | Java fixture/当前 JVM bundle 重建通过；JVM graph 产品门禁 23 项通过。Python 核心及独立 workspace clippy、产品 7 项、注释 4 项通过；prepared 8 项通过，Native 专项 1 项按入口要求跳过。Native/G1、实际分发及完整组合矩阵仍待本轮验收 |
| 原失败 | oneof 的 prost boxing 路径、四后端预算仍限三份、两处测试结构的冗余默认值均已定位并修正；JVM 第一次安装因同批 prepared 活进程被保护门禁拒绝，保留日志，等待退出后新目录重跑通过。未绕过活进程安装保护 |
| R1 剩余 | 公共上下文及启动/重绑定实例校验已迁移；完整单元可用性与普通/prepared 计划归一、TS 原生输入类型仍待完成；统一执行随 R4 继续；SDK 公共格式/校验和公共运行设施已迁移；运行时就绪/启动装配随 R4 归一。本轮身份/oneof/SDK 格式验证不代表 R1—R5 完成。worker 24 SDK 与 worker 25 不混装，需按对应阶段重建和验收 |
| R1 配置归一子阶段 | ProjectConfig 公共策略拆为共享 TaskConfig，输入改为类型化 UnitInput；prepared 单元保存扁平 ResolvedContext，移除递归 EffectiveUnit。公共清单 schema 2 的原生配置只含 discovery/languages/modules，不允许注入公共扫描或资源策略；旧 schema 1 要求重新 prepare |
| R1 配置阶段验证 | 配置/应用聚焦 93 项、完整 Rust 门禁、Python 独立 workspace/core/clippy、普通 Python 产品 7 项及注释 4 项通过；真实三语言 prepared 10 项、Native 图/资源/注释 24 项通过，无该 prepared 入口的 Native 跳过。最终已解析配置入口的补验与源码/日志摘要见 `target/typescript-c-abi/r1-config-unit2/receipt.json`；早期编译借用错误和最终无用导出 lint 失败均保留 |
| R1 公共上下文 | BuildContext 必需稳定 unit、源码语言、模块/world、语义摘要和类型化后端详情。Java JDK/桥、Rust toolchain/adapter、Python 目标/adapter/后端提交及 TS 编译器/提交分开；Discover 实例须匹配 grant，daemon 启动与 Java 池重绑定核对身份/producer/world/摘要，旧字段和重复详情/实例拒绝 |
| R1 报告 | schema 4 的 contexts 按实际已发现实例列出后端和发布语言；普通混合与 prepared 均保留所有运行单元，不再伪造 multi 上下文；Markdown 同源增加实例表。FilesOnly 仍在全局输入状态中披露；上下文就绪不等于已发布语义 |
| R1 上下文验证 | 聚焦 model/contracts/application 共 176 项通过（含四后端/JS+TS、错实例/world/producer、摘要、重复/旧 wire 字段）。真实 Native graph 24、显式 JVM graph 23 通过且无产物漂移；最终公共门禁和 prepared/Python 补验见 `target/typescript-c-abi/r1-context/receipt.json`。保留大 enum clippy、Python 独立 workspace 字段补齐及测试断言迁移错误的原日志；不表示 R1—R5/TS 生产与正式分发完成 |
| R2 graph-io 子阶段 | 新增 graph-io，迁出 contracts 的 GRA1 IO/队列/生产器并删除 Java 独立 delivery。三语言共用 Encoder，daemon 共用 Verifier；仅成功编码/校验路径生成不可在 crate 外构造的 VerifiedEnd。Java JSON envelope 保留在 Java worker，纯 Descriptor/OutputLimits 位于 contracts::graph_delivery |
| R2 归档与队列 | 原字节 count/size/SHA/EOF 校验与 IPC 规范编码摘要分开；任务根 FD/openat 禁止内部 symlink、父级逃逸、非普通文件及路径放大，消费前核对 inode/dev，替换路径不误删。晚到 SHA 错误/预算/取消不发 VerifiedEnd；Begin owners 与 Row/Cell/String 容量进入同一 8 MiB 队列配额，批次统一 64 行/256 KiB |
| R2 架构门禁 | 扫描所有成员生产源码的嵌套模块与 Python 独立 workspace，解析 Cargo 依赖别名并检查传递路径；测试模块后的生产代码仍被检查。11 项反例测试通过；共享层禁止编译器依赖，graph-io 禁止 SDK/IPC/进程和语言 envelope |
| R2 当前验证 | graph-io 16 项、Java worker 9 项（默认 G1 专项忽略 1，未重标旧实际验收）、应用 52 项聚焦通过；最终完整 Rust 门禁、Python core/独立 clippy、Python 产品 7/注释 4、真实 prepared 10、Native graph 24/JVM graph 23 全部通过。源码/产物/原失败见 `target/typescript-c-abi/r2-graphio/receipt.json`；Native 仍为 build-only，不作正式分发放行 |
| R2 worker-runtime | IPC 帧进度、三语言请求状态、daemon 接收状态、终态回执、FFI owner/通知和唯一账本交接凭证已接入；Java 内部 EngineRequest/EngineAnalysisReceipt 迁入 Java worker。contracts 仅保留 wire/纯 DTO 准入，无兼容导出。EngineOwned/Unknown 禁止 Rust 修改累计账户；可信回执才能归还，缺失/非法回执保留最后核实水位 |
| R2 运行阶段验证 | worker-runtime 38、模型 56、Java worker 9（默认 G1 忽略 1，另以真实库执行 1 项通过）、应用 52 项聚焦通过；完整 Rust、Python core/独立 clippy/构建通过；prepared 10、Python 产品 7/注释 4 通过。当前稳定产物 Native graph 24/JVM graph 23、Native 生命周期 10 和真实 SIGSEGV/SDK 恢复再索引通过；最终产品门禁无产物漂移。首轮 Reused/Cancel 失败及首轮 JVM 产物漂移原回执保留；原失败未改为通过。详见 `target/typescript-c-abi/r2-runtime/receipt.json` |
| R2 阶段边界 | graph-io、worker-runtime、三语言交付/会话/账本交接与 Java 内部 codec 已迁移并完成本机阶段回归。Native 仍为 build-only 开发 SDK，未签正式分发放行。完整单元可用性/启动装配随 R3/R4 归一；R3/R4/R5 与 TS T2—T8 继续实施，不能以 R2 替代完整计划 |
| SDK 公共格式 | schema 4 必需公共协议/平台/摘要及类型化后端身份，取消旧 manifest 默认值与扁平 Java 字段；Java Native/JVM 身份与 TS Go/上游/标准库身份分开。启动、安装、doctor、打包及测试工具已迁移；TS 只验证契约，不表示生产 SDK 已接入 |
| SDK 拒绝与诊断 | 缺字段、旧格式、未知字段、重复键（含开放元数据/文件摘要映射）拒绝；SDK JSON 有字节/节点/深度上限。doctor 区分 missing/invalid/present_unverified，不把元数据有效写成文件摘要已核实；两项诊断测试通过 |
| SDK 当前实际验证 | `r1-sdk4-java-baseline` Java fixture 通过；schema 4 JVM graph 23、Native graph 24、Native C ABI lifecycle 10、真实 G1 teardown 后禁止重建 1、真实 SIGSEGV 保旧 head/Unknown/回收/恢复通过。真实三语言 prepared 9 项全部通过，无该入口的 Native 跳过 |
| R3 进行中 | 三语言内容/来源登记接入 capture/registry；相同内容共用只读 spool，同文件多 world 保留各自辅助/发布角色，矛盾内容/归属拒绝。prepared 语义摘要排除 generation/日志/artifact 定位及 Maven receipt；业务来源与 PREPARATION 完整性文件分开比较。新增真实重复 prepare 用例通过：跨 generation 的 profile/语义稳定并取得 Reused，源变化后拒绝旧清单、重新准备后重算。本子阶段最终完整 Rust/Python core/独立 clippy、prepared 11（含三语言重复 prepare）、Python 产品 7/注释 4、Native graph 24/JVM graph 23 通过，最终产品门禁无产物漂移。真实 Native SDK 搬迁取得 Reused，随后实际全量的 profile/图库保持一致；可重放入口为 `just test-java-native-relocation`。原 Native 堆池身份、FILES 降级及 clippy 失败保留，修正后补验通过；见 `target/typescript-c-abi/r3-registry-semantic/receipt.json`。一次读取/实例视图/文件系统观察仍待 R3 后续完成 |
| R3 捕获读取子阶段 | 已接入目录 FD/逐级 NOFOLLOW、显式外部根/单文件权限、捕获阶段单次 live copy（含 hardlink 复用）、分块取消与截止检查、文件/目录/缺失查找/root realpath 观察和封存复核。Java 同 SHA 的各声明路径仍逐一核实。配置 52/应用 53 项聚焦、完整 Rust、Python core/clippy 已通过；外部 Maven 身份控制只流式验 SHA、不保存内容的回归通过。当前产物 prepared 11、Python 产品 7/注释 4、Native graph 24/JVM graph 23 通过，最终产品无产物漂移；回执见 `target/typescript-c-abi/r3-reader/receipt.json`。静态配置解析读取尚未合并；声明链接映射和实例视图仍待 R3 后续，Native 仍不是正式放行 |
| R3 解析/捕获单次读取子阶段 | 创建 CaptureReader 后再解析配置；根配置、prepared catalog/artifact、Java/Rust/Python 清单与控制验证通过 InputReader 共用捕获缓存。新增测试验证解析/捕获只读一次、解析后改写导致封存失败，以及独立诊断对 FIFO/目录/链接/超限输入的拒绝。配置 55/应用 53 项聚焦、完整 Rust、Python core/clippy/构建通过；当前产物 prepared 11、Python 产品 7/注释 4、Native graph 24/JVM graph 23 通过，无产物漂移。Java 源码 SHA 与既有已验证基线相同，本轮未重跑 Java fixture 或重建 SDK；Native 仍为 build-only。见 `target/typescript-c-abi/r3-resolution-reader/receipt.json`；实例独立视图和声明链接映射仍待完成，不代表完整 R3/TS 接入通过 |
| R3 prepared 实例视图子阶段 | config 按稳定实例构造 FrozenUnitView，隔离 manifest、blob 引用与观察索引；解析和捕获操作均记录实例路径。公共扫描先保留 FILES，占位资源可提升为控制；各实例独立登记辅助/发布角色。允许不同模块/world 的独立上下文共享物理根，重复模块身份和归属/内容冲突仍拒绝。删除 application 的全局 scope 筛选函数；Java 依赖保留在自己的视图。真实同根双 Maven 上下文验证索引、重复 prepare 复用、双 world 注释与删除其中一个后保留另一归属；Native/JVM 各执行专项通过。发现并修正私有候选对未填写 world 的 Java 行只按路径取首个来源的问题，现按行 module/scope 匹配，显式 world 不放宽。当前配置 60/应用 53/存储 43、完整 Rust、prepared 12、Native graph 24/JVM graph 23 通过，无产物漂移。见 `target/typescript-c-abi/r3-unit-views/receipt.json`；普通入口视图归一、声明链接映射、R4/R5/TS 生产仍未完成，Native SDK 未重建或正式放行 |
| R3 普通视图 / R4 运行单元基础 | 普通与 prepared 共用 unit_plans/capture_units；CaptureScope 区分 Task/Unit，后端上下文仅构造自身语言/模块/输入并共享 TaskConfig，原生解析观察也按实例登记。语言准备拒绝跨后端视图；Java Analyze/Discover 使用同一单元清单，候选绑定全项目清单。保守/prepared 使用 Vec<RunningUnit> 直接拥有会话状态、描述符/输入守卫、manifest 与捕获引用；删除 One/Pair/Sessions、平行数组和 Deref。计划/启动统一按后端、稳定实例 ID 排序。新增真实中途 JDK 准备失败用例：前序单元已有 Discover 消耗，失败保旧 head、无残留 worker，修复后恢复；Native/JVM 均通过。XML 根资源遗漏曾导致两组图门禁失败，保留原回执；修正 Java 根 XML 选择并补视图测试后，当前配置 61/应用 55/存储 43、完整 Rust、prepared 13、Python 产品 7/注释 4、Native graph 24/JVM graph 23 通过，无产物漂移。见 `target/typescript-c-abi/r4-owned-units/receipt.json`。声明链接映射、完整 PlannedUnit/更新策略与归属账本、单 Java/Rust 增量流程的公共执行、R5/TS 生产仍未完成；不签正式 Native/分发放行 |
| SDK 公共门禁 | 完整 `just test-rust`、`just test-python-core` 通过；SDK codec、安装保护/回滚、稳定路径及升级工具单测通过。追加开放映射 JSON 重复键预检后，聚焦 SDK 3 项、Python SDK 4 项、workspace clippy、当前实际 SDK runtime-info 补验通过 |
| SDK 原失败/未覆盖 | 保留 serde JVM 单元变体忽略额外字段、旧安装 fixture/导入路径、fmt 空行、误用 G1 exact 过滤导致 0 项执行的原日志；已逐项修正并实际补跑。未重标旧回执；Java 8/17/macOS ARM64 范围以本轮实际产物解释，正式 Native、跨平台、性能与分发仍未放行 |
| R3 声明链接组件 | 公共 InputCaptureReader/DeclaredLink/SealedInputs 已提供按单元隔离、允许根约束、精确 readlink 目标、循环/跳数/路径上限与封存复核。缓存命中不借用其他单元链接权限；同路径链接与内容观察均保留，外部目标根权限进入所属单元视图；相对目标和中途 `..` 按物理目录处理。配置 63 项与公开端口文件系统集成 13 项聚焦通过，覆盖改写、缺失查找、目录变化、身份只读、取消和重复缓存访问。本轮完整 Rust、真实 Native prepared 与 Python 产品/注释门禁结果见 `target/typescript-c-abi/r3-declared-links-stable/receipt.json`；审查期间的早期日志与源码漂移回执另保留在 `r3-declared-links`。既有三语言清单仍拒绝源链接，尚无产品清单传入声明映射；pnpm/TS 闭包须由 T3 验证，不作正式 Native/分发放行 |
| R4 公共运行所有权 | Rust 普通及 Java+Rust 迁入 RunningUnits：单元直接持有会话、描述符/输入守卫、manifest 和捕获；删除 RustWorkers、RustAttempt 平行输入字段与专属清理，新增副本先预留既有 host 预算。Java 准备失败发生在 Rust Discover 之后的真实断言验证保旧 head、可信已知用量、无残留 worker 与恢复索引。应用 63 项、架构检查、应用 clippy、Rust 产品 5/注释 2、Python+Rust 原子失败 1、Java+Rust 注释 1、真实资源失败/取消/截止时间 3、Native/JVM Java+Rust 各 1 项均通过；源码及 16 项产物无漂移，见 `target/typescript-c-abi/r4-common-executor/receipt.json`。本轮为当前改动的聚焦验证，未重跑完整 Java 组或签正式放行。后续工厂迁移见下行；全局 ExecutionPolicy 与任意结果混合仍待完成，不作 R4/R5/TS 完成声明。 |
| R4 单元启动工厂 | 单 Java 的 worker/prepared/view 改由 RunningUnit 直接持有，旧 IndexSetup 删除，JavaAttempt 只保留单元与增量策略。现行三语言普通/保守/prepared 只经公共 owner 调 WorkerService 工厂装配 LaunchSpec；Java 池重发现、失败后禁止重领 grant、显式 mock 策略和装配预算在工厂内。只有可信结束账户、释放 scratch 且进程健康时可交给池，随后 owner 为 Released，不能继续提供流/重启。新增门禁拒绝策略直接启动、重发现或越过 owner；错后端绑定在领取 grant 前拒绝。应用 64/架构反例 13、完整 Rust/clippy、Python 核心/clippy/构建通过；同批产物 Native graph 24/JVM graph 23、prepared 18、Python 产品 7/注释 4 通过。扩大的资源门禁暴露旧固定额度、未变深度用例与 Removed 份额隔离假设，保留 `r4-unit-factory-final/receipt.json` 原 6 处失败；修正为真实分析、独立 home 和实际解码放大用例后，完整资源矩阵 42、真实 SIGSEGV/SDK 恢复再索引通过，见 `target/typescript-c-abi/r4-unit-factory-resources-sealed/receipt.json`。Java 17 Native/JVM 各 3 项补验通过，资源门禁 progress 终态另以 1 项验证。源码及 16 项产物无漂移；Java 编译器源码保持既有摘要，沿用匹配 build-only SDK，不作正式 Native/分发或全局执行器/R5/TS 完成声明。 |
| R4 合并范围契约 | model 新增 UnitMergePlan，验证逐单元 Full/Delta/Reuse/Removed/FilesOnly；完整来源范围包括删除和零输出，拒绝跨单元/world 替换。辅助输入的发布身份变化时要求消费者重分析，整项目复用禁止 Ready/FilesOnly 互换。存储复用接入公共校验并从原 host 账户预付分配/工作，资源失败保留错误码与旧 head。模型 71/存储 55、完整 Rust、Python 核心/clippy/构建通过；Native 定向 4、JVM 复用 1、多实例/资源/Python/三语言 13 项通过，无源码或 16 项产物漂移。原错误测试类选择保留，缺项单独补验；见 `target/typescript-c-abi/r4-unit-merge/receipt.json`。无来源元数据归属、任意混合 SQL 合并、全局执行器/R5/TS 生产仍待完成，本轮不签正式放行。 |
| R4 图记录来源与原归档 | 单 Java/Rust 流、混合、Python 和 prepared 通过公共 Record 把主机分配的 Unit/Host/Fixture 身份送到存储，再按 Ready 单元输入复核；未知/不可用单元与跨单元发布拒绝。多单元逐份验证原主机归档 count/size/SHA/EOF，读取器持有目录租约，每次一个打开文件；删除二次合并归档，保留输出上限、Host 行准入及原 host 解码账户。交付策略 1 纳入项目/单元 profile，单元 profile 同时纳入注释规则；旧策略更新重新分析。应用 64/图交付 29/存储 56、架构反例 14、完整 Rust、Python 核心/clippy/构建通过；Native 24/JVM 23、资源 42、prepared 18、Python 产品/注释 11、Java17 Native/JVM 各 3 和独占真实 SIGSEGV/恢复通过。源码与 16 项产物无漂移，见 `target/typescript-c-abi/r4-record-origins/receipt.json`。来源目前到达候选校验点；无来源 metadata 的持久化归属、任意单元混合及公共执行器/R5/TS 仍未完成，不签正式放行。 |
| R4 SQL / 基线 / 宿主资源 | 私有任务 VFS 在文件增长前检查匿名/已 unlink 临时盘；SQLite VM、I/O、backup、cache/工作区及调用点/依赖/wiring/metadata 的已知分配使用原 host。工作区保留已付容量按同时存活对象复用，不退账；巡检缓存完整路径且每次 stat 活文件，避免收取没有发生的分配。单元基线在 SQL 中按 path/world 筛选并使用自身后端账本；首次错误不被后续取消/清理覆盖。新增 `host_scratch_bytes` 默认 2 GiB，含零 Ready 路径。完整 Rust、Python 核心/clippy、存储 87、应用 64、配置 63、架构反例 16 项通过；当前产物 Native 24/JVM 23、资源 42、Python/prepared/五模式 40 项全部通过、无跳过。真实 SQLite 3.53.2 spill、原始预算/容量/错误覆盖反例及修复后源码/产物摘要见 `target/typescript-c-abi/r4-sql-budget/receipt.json`；660 项 Java 来源未变。物理 17 / 逻辑 36 / 交付策略 6 不变；逻辑额度不是 daemon RSS 硬隔离。R5 完整矩阵/分发与 TS T2—T8 未完成，不签正式 Native、分发或性能放行。 |
| R4 公共执行器与五模式产品 | 交付策略 6：普通/prepared 共用逐单元 Full/Delta/Reuse/Removed/FilesOnly 及一次候选/CAS，删除全局组合分支；单执行单元继续流式，Java 可信 Reuse/Ack 后可健康入池，Rust 增量基线按单元读取。真实六单元 fixture 的规划及已验证结果覆盖全部五模式，只发布一个 revision，与 Full/历史对照一致。修正 Java 增量 descriptor 将控制文件误作删除 owner，绑定上下文仍完整保留。完整 Rust 核心门禁后，补实际结果事件再验应用 62、workspace clippy/build；模型 73、查询 35、存储 78。Python 核心/clippy/构建、prepared 19、Python/注释 11、Native 图 23+1/JVM 22+1、资源 39+3、Java17 Native/JVM 各 3、真实 SIGSEGV 保旧 head/回收/恢复 SDK 后索引均通过。原启动顺序/预算向量失败及定向补验保留；660 项 Java 来源未变，16 项产物无漂移，见 `target/typescript-c-abi/r4-common-executor/receipt.json`。SQL VM/cache/匿名临时磁盘及完整 host 计费仍待审计，R5 完整矩阵/分发和 TS T2—T8 未完成；不签正式 Native、分发或性能放行。 |
| R4 元注解查询上下文与重复事实身份 | 交付策略 5：直接 occurrence 的 hash/ordinal 定位主机 unit，元注解读取及缓存限定本单元；未知直接类型不按名字推断，路径经过不确定边不再恢复完整证据。重复事实按稳定 backend/unit ID 排序，混合 Full+Reuse 与 Full 保持 occurrence/派生元注解 ID；公开属性不再泄漏内部整数行号。应用 64/查询 35/存储 78、完整 Rust（含 clippy）与 Python 核心/clippy/构建通过；真实 Native/JVM 各 1 项覆盖独立同名 Java 上下文、复用、单侧修改、历史与 Full。1613 项非文档源码及 16 项产物无漂移，原串单元/证据恢复/ID 漂移反例保留，见 `target/typescript-c-abi/r4-annotation-context/receipt.json`。Java/SDK 源码与产物未改；公共执行器、SQL 预算、R5/TS 仍未完成，不签正式放行。 |
| R4 元注解、辅助缓存与依赖归属 | 物理 schema 17 / 交付策略 4：元注解来源及 cache/显式依赖按 unit 保存，Full/Delta/Reuse/Removed/FilesOnly 使用各自保留规则；辅助输入按单元/来源/表替换快照，复用单元不受影响。依赖重建保留显式观察，标记推导行，调用点及注解/元注解依赖只用声明绑定；原始路径依赖按本单元目标 world 集合保守展开，拒绝未声明目标。新增共享辅助输入、全量等价、删除/复用、首位置、显式/推导并存与越界反例，当前存储 73/交付 29/契约 35 和 workspace clippy 通过。Java fixture、完整 Rust、Python 核心/clippy/构建、Native 24/JVM 23、资源 42、prepared 18（旧 16 拒绝/full 重建）和 Python 产品/注释 11 均通过；Java 来源族 660 文件未变，JVM SDK 重建并在冻结前安装，Native SDK 沿用原产物，源码及 16 项产物无漂移。见 `target/typescript-c-abi/r4-unit-aux/receipt.json`。元注解查询的全局 FQN 闭包仍待按直接观察单元隔离；SQL 预算、公共执行器/R5/TS 仍未完成，不签正式放行。 |
| R4 五模式混合暂存组件 | 存储新增独立单元更新入口，复核固定基线上的 UnitMergePlan，再构造一次私有候选。Full/Delta/Reuse/Removed/FilesOnly 同时存在的人工行 fixture 与最后全量对照一致，包含无来源节点/diagnostics/coverage/providers/project_meta、零输出、删除到零与旧 revision；拒绝 Reuse 新交付及 Delta 辅助输入借用其他单元替换范围，首/中/末失败与预算拒绝保旧 head。共享语义比较工具已拆出，最终存储 70/模型 71、workspace clippy 与架构反例 14 项通过；同一基线读者贯穿验证与候选，共享无来源节点保留复用成员，定义冲突拒绝且保旧 head。组件证据见 `target/typescript-c-abi/r4-mixed-merge/receipt.json`。该入口尚未接入公共执行器；元注解、辅助缓存/依赖、SQL 预算审计、R5/TS 仍未完成，不作混合产品或正式放行声明。 |
| R4 节点归属与引用边界 | 物理 schema 16 / 交付策略 3：持久化逐单元节点最终定义摘要、边/注解/调用点 owner；精确引用和 Java 补全限定到本单元及声明输入，辅助输入不能发布 owner。活动单元无来源定义按快照替换，Reused 保留原归属；基线 hash 沿用，修复默认值导致复用重复边。存储 67 项通过，含真实 Full→Reuse→Delta→Full 对照、跨单元全局定义冲突保旧 head；首轮失败日志保留于 `target/typescript-c-abi/r4-unit-nodes/`。Rust 功能链、存储 67 与 clippy 通过；Native 24/JVM 23（共享旧版本断言导致的 7/6 项已定向补验通过）、资源 42、prepared 18、Python 产品/注释 11 及核心/clippy/构建通过。三处等价写法修正后，存储/clippy/构建和 Native/JVM 复用各 1 项再验通过；各轮冻结输入与产物无漂移，见 `target/typescript-c-abi/r4-unit-nodes/receipt.json`。未重签 Native SDK、分发或性能放行；元注解、辅助缓存/依赖、任意混合及公共执行器/R5/TS 仍未完成。 |
| R4 持久化单元观察 | 物理 schema 15 / 交付策略 2：diagnostics、coverage、providers 与 project_meta 按主机分配的 unit 保存，活动单元零输出也清理，未活动观察保留。公开列和业务 ID 不变；provider 保留原始逐单元 profile，公开能力取交集、限制按单元、不同 profile 按稳定 ID 聚合；任一 source_only／unavailable／fallback 不被正常单元覆盖，冲突 world 上下文拒绝。新增候选全量/增量/复用/再次全量对照及预算回滚、私有 PK 无关性等 6 项测试，存储 62 项通过。首轮独占锁导致基线读取失败的回执保留，修正加锁顺序后补验通过。完整 Rust、Python 核心/clippy/构建、Native 24/JVM 23、资源 42、prepared 18（含旧 14 拒绝与 full 重建）、Python 产品/注释 11、Java17 Native/JVM 各 3、独占 SIGSEGV/恢复均通过；源码及 16 项产物无漂移，见 `target/typescript-c-abi/r4-unit-metadata/receipt.json`。旧 schema 14 及更早须显式 full；无来源节点、元注解、辅助缓存/依赖、单元引用补全、任意混合及公共执行器/R5/TS 仍未完成，不签正式放行。 |
| R4 world 替换 | 物理 schema 14：source/world 精确替换与保留，声明/边/注解/元注解/缓存/coverage/诊断、重复调用、注释/FTS、Rust 状态与依赖覆盖，跨 world 公开 ID 冲突拒绝；文件依赖另存目标 world，避免同路径反查串世界。Receiver 对原流和隐式 world 投影分别校验 SHA；输入歧义明确拒绝。已引入显式 Reused 暂存输入，独立验证逐单元语义输入并保留全局诊断/coverage；单 Java 删除合成空归档，使用同一入口。原误删、Native 汇总诊断重复及 Python 复用丢诊断失败保留于 `target/typescript-c-abi/r4-world-merge/`，Java 清单边界漏判复用的 Native/JVM 原失败另存于 `target/typescript-c-abi/r4-world-merge-verified/`；现按单元 inputs 判定，可信 Reused 后继续健康池，并收窄增量基线节点/关系/反向依赖到声明输入。修正后存储 55/应用 63 项、完整 Rust/clippy、Python 核心/clippy/构建、Native graph 24/JVM graph 23、prepared 18、Python 产品 7/注释 4、真实 SIGSEGV 保旧 head/回收/恢复 SDK 再索引均通过，源码及 16 项产物无漂移，见 `target/typescript-c-abi/r4-world-reuse-sealed/receipt.json`。Java 660 文件源码保持既有已验证摘要，沿用 build-only SDK，不签正式 Native/分发放行。旧 schema 13 及更早需显式全量重建，已有公开 ID/逻辑 36/worker 25/ABI 4 保持；无来源 metadata 的完整单元归属及统一结果合并仍待 R4 后续，TS T2—T8/R5 未完成 |
| R4 基线与来源边界 | 主机元数据清单升级 v2：完整单元输入/辅助角色和声明 scope，v1/缺清单增量明确要求全量重建。一次固定 head，元数据租约贯穿索引且阻止 GC，后续策略不重新选择基线；旧图可通过显式全量迁移。Receiver 对 replacement producer/world/path 和图行 source/world/module/scope/语言做实例边界校验，错误后无 VerifiedEnd。原失败与定向测试记录于 `target/typescript-c-abi/r4-baseline-scope/`；真实回归发现 Java 基线夹带 FILES owner、Python 汇总 coverage 原生模块漏登记，已修正。修正后完整 Rust/clippy、Python 核心/clippy、prepared 17、Python 产品 7/注释 4、Native 图 24/JVM 图 23、真实 Native SIGSEGV/SDK 恢复再索引均通过，源码/16 项产物在门禁中无漂移，见 `target/typescript-c-abi/r4-baseline-scope-final/receipt.json`。Java 660 文件源码摘要保持，沿用匹配 build-only SDK，不作正式分发放行。无来源字典归属、存储按 world 精确替换、统一结果混合及 TS 生产继续待办 |
| R4 单元归属与可用性 | 新 revision 持久化 unit_ownership v1，记录稳定实例、后端/语言、非辅助发布范围、零输出来源与独立语义/输入身份；旧基线缺清单时要求 index --full，storage 创建候选前校验，随同一次 CAS 提交。PlannedUnit 保留 Ready/FilesOnly/Removed，普通/prepared 共用静态实例分片，非执行份额封为可信零用量且不可借出/重领；移除全部单元不启动 Java 兜底，首次无语言索引仍拒绝。真实旧清单重建/保旧 head 和禁用全部单元两项聚焦已通过；本轮完整门禁见 `target/typescript-c-abi/r4-unit-ledger/receipt.json`。单 Java/Rust 独立策略、任意 Full/Delta/Reuse 混合、LaunchSpec/公共 supervisor、无法确认停止时的输入保留及 R5/TS 生产仍未完成；Native SDK 未重建或正式放行 |
| R4 输入租约 / 回收监督 | 所有现行普通/混合/prepared 启动迁入携带 InputLease 的 LaunchSpec。Process 在 Hello/Discover 前取得捕获 spool 与 Java/Rust/Python scratch 共享所有权；独立回收登记在停止失败、future 丢弃及任务 runtime 关闭后继续固定输入。先 kill 所属组，确认无非僵尸成员后回收 leader；停止两秒超时仍保留输入并继续回收。可信 Acked 才清除任务租约/scratch 观察，错误 ACK 保留；旧磁盘检查持有旧租约且不影响下一任务。新增 5 项真实进程边界与有效/错误 ACK 测试，应用 62 项聚焦通过；macOS 瞬态 EPERM 原失败日志保留。完整 Rust、Native/prepared/Python/JVM 与真实 SIGSEGV 恢复结果见 `target/typescript-c-abi/r4-input-supervisor/receipt.json`。完整后端装配/公共监控、固定计划基线、发布范围证明与任意混合执行仍待 R4；R5/TS 生产与正式放行未完成 |
| 当前剩余 | 既有三语言 R5 本机门禁见上方；TS T2—T8 生产 ABI、冻结输入、语义、产品与分发仍未完成。T0 同步诊断 ABI 不作生产复用。没有性能或正式放行结论 |

## 删除产品旧格式兼容（2026-11-09，本机验证完成）

当前 daemon 7、公开投影 3、报告 schema 4。页、Record、任务事件、报告、资源回执、语言配置及输入仅保留当前路径；删除清单见 [单一当前格式](../design/legacy-removal.md)。原协议 6 的验收记录保留于下节，不作为本轮产物验证。

本轮证据：`target/legacy-removal/receipt.json`，仅以该回执列出的最终日志为依据。

| 检查 | 本轮结果 |
|---|---|
| Java fixture / 来源清单 | `java.log`、`java-source-verified.log` 通过；NodeKey 三段读取删除，保留原来源 SHA，适配记录 87 |
| Rust 完整门禁 | `rust-sealed.log`：workspace 331 通过、默认 G1 1 项跳过；图 22、CLI 15、Rust 产品 5、注释 2、架构/DDL/fmt/clippy 通过 |
| G1 补跑 | `g1.log`：更新 SDK 上真实 teardown 后拒绝重建 1 项通过 |
| Python | 核心 32、产品 7、注释 4、当前报告及 unknown 水位 1；prepared 8 通过、真实 Java 组合另行显式通过 |
| Native / JVM | 新 SDK 构建；Native 图 8、注释 3；JVM 图 7、注释 3 均通过 |
| Maven | 唯一 CLI 入口上的 alignment 21 项和真实导出 fixture 通过 |
| Rust 实际包 | 标准 release 门禁与 SHA；CLI 15 中 2 项 debug 故障点跳过，对应 debug 已验证；图 22 中 9 项同类跳过 |
| Java 实际包 | 校验和、仅保留 CLI Maven 入口、新包上的实际 Native 查询 fixture 1 项通过；未扩写为全部 Java release 门禁通过 |
| Python 实际包 | 校验和、产品 7、注释 4、prepared 8 通过；Java 专项 1 项按包边界跳过，开发路径已显式验证 |

原失败保留：Maven 回执缺轮次、Rust 示例残留 Java 字段、旧格式正向断言、测试编辑语法错误、旧包装入口的错误文案预期、Java 适配清单缺理由，均已修正并复验。Native 为 build-only SDK 与功能验证，不签正式 Native、跨平台或性能放行；本轮无性能测量。

## CLI 输入、text 交付与用例路由（2026-11-09）

| 交付 | 当前行为 |
|---|---|
| 输入/help | 35 条路径按有效参数展示及本地拒绝；保留显式参数；diff --section、snapshot --kind；pipeline 使用受限文本规格 |
| 正文 | 默认 text，文件/owner 共享，一种规范声明；必要参数名与泛型类型保留。原文不逐行编号，可还原 CRLF/无末尾换行/多字节；回执末行 end |
| 身份与证据 | 正常查询省 revision/producer/profile/hash 与默认 Next；现场次数、关系证明、可能/确认、未知和语义缺口保留 |
| 分页 | 只凭 cursor 恢复原项目/请求/格式/来源；最终 codec 核算字节；只交付完整单元，未完成枚举不提供虚构总数 |
| diff | 四种 section；固定两端 revision/manifest 身份。impact 仅变更关系根的静态反向调用传播，不能解释为通用源码/数据流/运行影响 |
| 事件/恢复 | 投影 2 的类型化记录及内部 TaskResult；watch 检查连续序号、终态和观察边界；旧 stdout 正文不可安全重放时明确不可用，保留真实提交 |
| 独立状态 | 语义、计算、执行、门禁、提交、报告、交付各自保存。恢复只核实提交时，质量/门禁/报告保持 unknown；交付 strict 不改写分析门禁 |
| 操作效果 | 原子导出新路径；竞争/写出失败不掩盖已发布。stop 等待原进程退出，restart 再确认新 generation；超时返回实际 draining |
| 应用边界 | OperationRouter 调用查询/历史/维护/就绪/报告；查询核心继续使用窄读端口。部分服务仍持完整 Store，未宣称完成全部 DDD 模块化 |
| 兼容 | daemon 5→6；worker 24、graph model 6、analysis 13、ABI 4、物理/逻辑 schema 13/36 保持。旧 cursor 拒绝，已有兼容图不因显示升级重建 |

本轮证据根目录：`target/cli-refactor-20261109/`。先运行 `just test-java`，再使用 Rust 1.96.0 运行 Rust/Python 检查；Java 源码与 Native SDK 未因 CLI 重构修改。完整命令、产物摘要和范围见该目录的 `receipt.json`。

| 本轮验证 | 证据及范围 |
|---|---|
| Java fixture | `java-baseline.log`；先行检查通过，未重建 Native |
| Rust 完整门禁 | `verified-final-rust.log`；workspace 334 通过，1 项真实 G1 测试按默认规则跳过；架构/DDL/fmt、图 22、CLI 16、Rust 产品 5、注释 2、工具及 clippy |
| CLI 关键边界 | 35 help、参数拒绝无任务；原文最小预算、冻结页/同名替换、导出竞争、事件缺口/旧正文、提交/取消、停服超时/重启；含真实进程与持久提交断言 |
| Java 产品 | JVM 图 7 / 注释 3；Native 图 8 / 注释 3。最后另用 Native 验证四种 diff 及实际 text：`native-diff-verified.log` / `native-diff-receipt.json` |
| Python | 核心 32 与 fmt/clippy：`final-python-core.log`；产品 7 / 注释 4：`final-python-product.log`；prepared 9 中 1 项需显式真实 Java，已补跑通过 |
| prepared 显式组合 | 首次因 PATH 缺 Maven 失败保留于 `mixed-failure/`；配置 SDKMAN Maven 后 `prepared-mixed-with-maven.log` 通过，未把失败记作成功 |
| Rust 实际包 | `verified-final-package.log`：SHA256 全部核对，图 22 中 9 项 debug 故障点跳过，Rust 产品 5、注释 2；`package-cli-verified.log`：CLI 16 中 2 项 debug 故障点跳过，这些均由 debug 门禁实测 |
| 新旧接入 | `protocol-mismatch.json`：真实新客户端/旧 daemon 与旧客户端/新 daemon 均拒绝，原服务未被替换 |

固定三个 agent 任务，对照 HEAD `39290fc` 的独立实际二进制与本轮二进制，使用 `tiktoken 0.12.0 / o200k_base`。计入 help、命令输入、输出和续页；双方相同的准备索引不计入，业务结果逐项相等。不是 Native 性能测量。

| 固定任务 | 原 token（输入＋输出） | 本轮 token | 调用数（原→本轮） |
|---|---:|---:|---:|
| 定位并读源码 | 3,585 | 847 | 4→4 |
| 检查调用与现场 | 2,581 | 1,156 | 4→4 |
| 冻结分页后读取 | 1,165 | 453 | 3→3 |
| 合计 | 7,331 | 2,456 | 11→11 |

该任务集总 token 减少约 66.5%，没有新增补查调用；原始输出、35 help、二进制 SHA 与逐步数据在 `agent-baseline/`、`agent-current/`、`agent-comparison.json`。只证明这三个固定任务的本次结果，不外推全部场景的节省比例。

原失败与中间版本日志均保留；最终结果以上述指定日志和 receipt 为准。本轮完成 CLI 与 Rust 本机分发验证，未重签 Java/Python 正式分发或 Native、跨平台、容量、稳定性能放行；历史测量不重标。

## 注释收敛与 Java 17 联合改造（2026-11-08，本机 fixture 范围完成）

| 边界 | 当前源码与已取得证据 |
|---|---|
| 当时版本 | daemon 5、worker 24、graph model 6、analysis 13、物理 schema 13、逻辑 36、GraphSemantics 28；v51 / batch 2 / ABI 4 / catalog 2 保持 |
| 注释 | 三语言只产生 documents/enclosing_callable；删除源码窗口、专用参数／配置与 prepared 展示指纹例外；旧图库要求 index --full，旧注释游标要求重新查询 |
| Java 17 | SDKMAN 匹配；冻结目标、实际版本、别名、规范化路径、JDK 内容及桥身份；8／17 分桥构建，SDK manifest Native 3 / JVM 2 显式登记桥映射和摘要 |
| 已验证 | 当前完整 Java fixture 727 项通过（0 失败／错误／跳过），含新增 Java 17 模型专项；JVM／Native 各 3 项 Java 17 产品测试通过，含取消及 worker 崩溃保留旧 head，Native 注释查询 3 项、JVM 注释生命周期 8 项、Native ABI 生命周期 10 项、Native 图 8 项、Native 注释生命周期 8 项通过；Python 核心、产品 7 项、注释 4 项、prepared 9 项和显式三语言组合通过 |
| 源码读取 | 修复 Java 含末字符坐标到半开 UTF-8 范围的转换，JVM／Native 长函数、多分块、多字节与历史源码产品测试通过 |
| Rust | 完整 just test-rust 已通过；真实 G1 teardown 后禁止重建的独立 Rust 测试通过，默认门禁中的该项跳过不冒充实测 |
| 原失败 | 默认 Rust 1.95、旧 SDK 混装拒绝、旧 schema 断言、迁移摘要漂移、测试中的旧 revision 假设、Native Java 17 资源缺失均保留日志；未重标失败 |
| 分发与故障 | Rust、Java、Python 当前实际包通过；Java 包 8 组门禁包含 Java 17，Release 共享资源 42/42 与组合注释 11 项通过；原二进制恢复且 SDK 字节未变 |
| 完成边界 | 仅本机自建 fixture、产品与分发；未测真实项目／性能，不签 Linux、目标 Java 25、完整语义／来源或容量稳定性放行 |

汇总回执为 `target/java17-plan-check/joint-receipt.json`；原失败日志和各阶段回执均保留。Java 17 扩充真值覆盖 record 隐式／紧凑／显式／重载构造器、accessor、构造器内回调、泛型方法引用及歧义／非法模式变量；同时检查脱离模型、提取后的 CALLS 与已发布图库的精确目标。Python 已删除仅供语句关联使用的 body 集合缓存，最终核心、实际包及共享 Release 矩阵使用更新后的 worker。按用户要求仅用本仓库 fixture，不安排真实项目或性能测量。下列旧验收与测量保持原产物含义。

## 历史三语言注释索引（C0—C4、A4—A7）

| 边界 | 当时源码 / 验证 |
|---|---|
| 版本 | worker 23、graph model 5、analysis 12、物理 schema 12、逻辑 35、GraphSemantics 27、javac-model-v51；daemon/ABI/compiler batch/catalog 保持 4/4/2/2 |
| Java | 主 javac comment token 与语言关联转为独立 GRA1 注释/关联行，原始 UTF-8 字节范围、正文映射、显式 world 和公开声明 ID |
| 存储 | 独立 comments / comment_links / FTS，来源校验、稳定顺序、共享分词身份；旧图要求 index --full |
| 查询 | search-comments、search-code --in、CLI/RPC/pipeline、上下文配置及冻结值；Native/JVM 真实产品各 2 项通过 |
| C1 历史验证 | Java 719/719；完整 Rust 门禁；Native/JVM 真实查询各 2、Native ABI 10、manifest 5、bootstrap 2；见 target/todo-implementation/comment-c1-final/receipt.json，不代表正式 Native/分发放行 |
| 本计划收口 | A7 架构收口与三种实际分发通过；匹配的 G1 build-only Native SDK 已安装，完整迁移/跨平台/容量仍未签放行 |
| C4 实际包 | Rust、Java、Python 通过；共用 release 资源 42/42，注释/Native 组合 11 项；原二进制恢复、SDK 未变 |
| C4 固定输入 | 128 个类、每类 8 个方法；3,328 注释、4,224 关联；五组路径限定查询及定位通过 |
| C4 单轮测量 | 索引 3.137 s；完整工作负载 5.215 s；1 s 采样峰值 269.30 MiB；图库 5.35 MiB；独立空 home，OS page cache 不受控 |
| C4 预算边界 | worker 1 GiB，payload 1 GiB，work 5,000 万，nodes 1,000 万；初始 100 万节点失败保留，不签默认预算容量通过 |

A4 已完成显式三语言查询能力、Java 派发/类型规则及存储批量补全策略；未知语言不再默认进入 Java。Rust 静态派发使用 `rust-analyzer-static`，明确拒绝 CHA/runtime；Java 与 Python 已有支持范围回归通过。A4 证据：`target/todo-implementation/a4/receipt.json`。

A5 已完成服务状态与配置类型拆分：Engine 仅装配；任务、索引上下文、worker 池、历史/分页、报告分别持有状态和窄依赖。旧配置与 prepared 指纹不变；完整 Rust 323、资源/故障 42 及九组产品回归通过，真实崩溃后的 SDK 已恢复且字节一致。证据：`target/todo-implementation/a5/receipt.json`。最终分发由已完成的 A7 回执补充。

A7 收口（2026-11-01）：todo 全部任务在本机 fixture 范围完成。证据：`target/todo-implementation/a7/receipt.json`。

| 项目 | 实际结果 |
|---|---|
| 架构 | Cargo 传递依赖、服务状态/语言编排、javac 源集、Lombok/Maven、生产预算门禁；7 个违规反例通过 |
| 单列缺陷修正 | prepared 不可用输入按冻结 roots/coverage 披露注释缺口；健康路径完整，历史 revision 不漂移 |
| 完整回归 | Rust 完整门禁；Python 核心 32、产品 7、注释 4、prepared 9；Native 综合 23、混合注释 3；G1 项显式通过 |
| Java 绑定 | Java 源码及 SDK 与 A6 当前 728 项验收一致；A7 仅改 Rust/测试/文档 |
| 实际分发 | Rust 矩阵 26、Python 矩阵 36、Java 包通过；release 共享资源 42、注释/组合 11 通过；原可执行文件恢复，SDK 字节不变 |
| 固定 fixture | 128 类、5 组功能查询通过；未重跑性能，C4 唯一测量只认证原产物 |
| 原失败 | 测试导入生成未列入清单的 pycache；保留失败包/差异/日志，修正导入后完整复验通过 |
| 保留边界 | iopengw 排除；固定上游对象缺失不冒充覆盖验证；跨平台/完整语义/容量稳定性未签全局放行 |

本轮回执：`target/todo-implementation/c4/receipt.json`；原始采样和唯一测量 marker：`target/todo-implementation/c4/fixed-fixture-validated/`。该测量仅对应 C4 固定产物，后续 A4—A7 版本不沿用为性能认证。

以下历史记录保持原产物含义；本轮按用户要求排除 iopengw，不作为开发、构建或放行前置。

## 业务标注与人工笔记裁剪（2026-10-20）

| 项目 | 当前边界 |
|---|---|
| 产品范围 | 删除图内业务语义标注、独立人工笔记及 annotate；保留 Java 源码注解、元注解与文本搜索 |
| 版本 | daemon 4、graph model 4、物理 schema 11、逻辑 schema 34；worker 21、analysis 10、ABI 4 保持 |
| 数据 | 不迁移旧图或人工笔记；不再创建和读取 annotations.db；全量建立新 revision 后切 head |
| 来源 | 选择性借鉴 Anatomist 90e405b2ddc1f9cbd1790302c027293c06af6a91，整体参考与来源清单各自提交保持 |
| Java / Rust | Java fixture 641 项、来源审计、Rust 完整门禁通过；JVM 图 7 项、Native 图与生命周期各 8 项通过 |
| Python / 分发 | 核心、CLI、输入准备、Native 三语言组合及 Rust/Java/Python 分发回归通过；Java release 原路径执行后恢复原二进制 |
| 冻结项目 | 同一 iopengw 提交；变更前精确包与本轮图的 11 张剩余逻辑表按 JSON 值一致，6 类公开查询一致；integrity=ok、FK 违规 0，仍为 partial |
| 既有差异 | 与 2026-10-16 历史图相比，变更前后均少 3,737 条框架关系边、290 条文件依赖、3,463 条符号依赖；本轮未修复该既有缺口 |
| 原始字节 | nodes / edges / index_diagnostics / call_sites 的原始 metadata JSON 键序有差异；按值比较一致，原始图摘要和查询输出均保留 |
| 放行边界 | 无新增正式性能测量；不签完整语义/来源覆盖、Native 或容量/稳定性能放行；以下历史回执保持各自含义 |

该历史轮次登记的证据路径：`target/business-annotations-removal/20261020/validation.json`、`prechange-provenance.json`、`iopengw-final/receipt.json`。当前工作区没有这些历史产物，不能作为本轮验收依据。变更前包的二进制 SHA 对齐已提交回执中的 `0c769f6`，其运行时代码与本轮开始时 HEAD 相同；未把 2026-10-16 旧图当作当前基线。

安全拦截导致的 Rust release SIGKILL、首次 protobuf 生成方式错误、Native 缺少 Row.world_ 反射配置及初次历史基线差异均保留原日志和回执；安全放行后沿原可执行路径重试。补齐反射字段后生命周期 8/8 通过。覆盖清单撤销了 4 组指向已删除测试的旧“已覆盖”映射，恢复为待补；保留映射使用本轮真实执行证据，整体覆盖清单仍未关闭。

## 统一输入准备（2026-10-19，Python v3）

| 项目 | 当前结果 |
|---|---|
| 产品入口 | 仅新增 `index --prepare`；普通 index 消费冻结输入；doctor 静态检查，不执行构建工具 |
| 配置 | `[inputs]` 声明多语言入口；独立 Maven reactor / Cargo workspace 分开展开；Python 默认隔离、显式依赖或共享环境 |
| Python 元数据 | 默认静态读取入口 pyproject，显式 module 源码根优先；没有 pyproject 仍可索引。schema 3 兼容 1/2，adapter v3；worker/graph/存储协议不变 |
| 降级 | 可信模型但依赖缺失保留 partial；无可信模型时只保留文件/文本，查询明确语义不可用；strict 拒绝保留旧 head，恢复后重建替换旧事实 |
| 故障 | 工具崩溃、超时、取消、清单损坏和配置冲突失败，不伪装为降级成功；准备清单和索引 head 各自原子提交 |
| 报告 | schema 2 全局摘要；JSON / Markdown 同源，不保存逐文件明细和完整配置，不新增 JSONL；真实项目 JSON 约 4.7 KB |
| 门禁 | `just test-rust`、Java fixture 641 项、Python 核心 20 项与导出器 5 项通过；独立工作区、三语言 Native、降级/strict/恢复、无 pyproject 显式根均有 fixture |
| 冻结项目 | 分发包经 `index --prepare` 重放同一 diorama-sdd 提交、615 文件，25/25 导航及三个公开查询通过；integrity=ok、FK 违规 0 |
| 完整性边界 | 与 v2 一致：30 complete、585 partial；保留 5 个真实源码外部缺失名称；未新增完整语义或性能认证 |

使用说明：[统一输入准备](modules/inputs.md)。本轮独立回执：[2026-10-19-unified-inputs](../lang-python-adaptor/validation/2026-10-19-unified-inputs/receipt.json)。安全机制 SIGKILL 原日志和重试日志均保留，可执行路径沿用；Java SDK 仍单独安装。v2 历史回执不改写。

## Python 配置与完整性修复（2026-10-19，v2）

| 项目 | 当前结果 |
|---|---|
| 可选元数据 | 显式 --pyproject 静态读取；无该文件时保留显式根流程。标准字段、setuptools 常规布局、TEST 的 pytest pythonpath；动态字段不执行，依赖版本不宣称已核验 |
| 冻结与兼容 | 输入 schema 2，接受旧 schema 1；读取的配置进入 CONTROL，变更要求重新导出。adapter v2 和后端提交严格核对；worker 21、graph 3、物理 schema 10 不变，v1 profile 不复用 |
| 完整性归因 | 全部诊断保留，区分 source / auxiliary / backend；后端来源不冒充捕获文件。使用实际反向依赖传播，未知影响单列上下文 partial，不广播到全部文件 |
| 提取修复 | 赋值左侧不记作读取引用；except 的 as 变量补齐声明。每 world 保存每类最多 3 个定位样本和后端目标证据 |
| 仓库门禁 | Java 641 项、Python 核心 20 项（含 subprocess helper）及导出器 5 项、共享 Rust 门禁、独立 Release 7 项 CLI、Native Java/Python 与三语言发布通过 |
| 冻结项目 | 同一 diorama-sdd 提交 31259b761a5ea77672d0e90bc9e2776e4dfe397e、615 物理文件；TEST 显式加入 tests 和 fixtures/e2e/layout13，25/25 导航与三个公开查询通过 |
| 缺失模块 | helpers / seed_remote_knowledge / update_remote_knowledge 在项目上下文中已解析；保留 jury/Codex 的 5 个外部名称。另完整暴露 25 个辅助依赖缺失名称，不能与旧的源码诊断口径混比 |
| coverage | 30 complete、585 partial；378 文件有带来源证据的依赖缺口，未知影响上下文 0、无证据传播标记 0。原先仅全局标记的 41 文件中，30 已完整，11 有实际依赖缺口 |
| 剩余提取缺口 | reference_target_not_extracted 涉及文件从 220 降至 153；Any、合成 callable 和其他未知仍明确保留，不代表完整语义或全库准确率 |
| 图规模 | 57,375 节点、264,013 条非调用边、66,775 调用位置、50,504 调用目标；4,019 诊断包含源码 1,689、辅助 2,319、后端 11；integrity=ok、FK 违规 0 |
| 性能边界 | 本轮只做功能重放，measured=false；没有新增正式性能测量，下面 v1 的 38.458 s 不能作为 v2 性能 |

最新回执：[2026-10-19](../lang-python-adaptor/validation/2026-10-19/receipt.json)。原始安全机制 SIGKILL、配置/测试失败和修复前功能回执均保留；可执行文件路径和名称沿用，旧 Release 字节另存本机备份。配置及查询边界见 [Python worker](modules/python-worker.md)。

## Python 初轮验收（2026-10-17，v1 历史）

| 项目 | 结果 |
|---|---|
| 核心实现 | 固定 Pyrefly 1.4.0，独立 Cargo workspace / worker；显式清单冻结；GRA1；Python 查询策略；多语言单次发布与完全相同输入复用路径 |
| 存储迁移 | 物理 schema 10 接受 Python 声明种类；禁止按同名为 Python 补边；旧图需 `index --full` |
| 已验证 | Java fixture（worker 21）、Native 生命周期/图库、Python CLI 六项（含九个探针）、Native/JVM 混合组合；核心和共享门禁见最新回执 |
| 分发 | 原路径 Release 构建和独立 home 的随包六项 CLI 通过；Rust/Python 包含锁文件、许可证清单、fixture 与 SHA256SUMS，Java SDK 单独安装 |
| 真实项目功能 | diorama-sdd 冻结提交 `31259b761a5ea77672d0e90bc9e2776e4dfe397e`，615 文件；23 个调用与 2 个字段引用共 25/25；Python 3.12/darwin 声明依赖的 wheel 字节冻结，不执行目标代码 |
| 失败保留 | 安全机制 SIGKILL、默认栈溢出、超时、图目标约束及验收脚本选错导航/selector 的原回执均保留；没有重标原失败 |
| 单轮测量 | 隔离 Release、空应用状态、4 GiB worker / 512 MiB 输出 / 2 GiB scratch：CLI 全流程 38.458 s，进程树峰值 2.034 GiB，图库 168.742 MiB |
| 图规模 | 57,086 节点、295,695 条非调用边、66,775 调用位置、50,233 调用目标；integrity=ok，FK 违规 0 |
| 调用分母 | 66,775 位置中 exact 48,382、heuristic 1,793、ambiguous 23、unresolved 16,577；这是存储分布，不是准确率 |
| 未关闭 | 615 文件均为 partial；8 个缺失模块名含 5 个可选外部名称及 3 个运行时搜索路径名称；不认证完整 Python 语义、Linux 或容量/稳定性能 |

capture 0.732 s；分析及入库 25.436 s；发布 12.202 s。后端 solve / facts 为 4.696 / 0.364 s；交付和写库重叠，不能与上述阶段再累加。OS 页缓存未控制，RSS 每 0.2 s 采样同一时刻 daemon 及子进程之和。仅正式测量一轮，前面的功能重放不计作性能样本。

该轮范围验收回执：[2026-10-17](../lang-python-adaptor/validation/2026-10-17/receipt.json)，包含冻结输入、wheel 哈希、原失败、同库复查和制品哈希；大型原始 JSON 无损 gzip 保存。当前 dist 已更新为 v3，不能用其字节冒充旧测量；原产物另存本机备份。

配置、能力限制和命令见 [Python worker](modules/python-worker.md)。后续历史 Java/Rust 回执保持原协议和产物含义，不构成 worker 21 / schema 10 的 Java Native 正式放行；Python 本轮仅签上述冻结范围。

## 架构整改与真实项目复验（2026-10-16）

| 改动 | 结果 |
|---|---|
| Rust 任务所有权 | TaskRunner 持有完整任务；取消/超时后等 blocking 工作退出再写终态、释放许可，避免取消后后台切换 head |
| 调度与幂等 | 分析、只读和维护独立有界通道；max_query_tasks 默认 2；先查重再准入，重复请求可取消捕获中的原任务 |
| 发布裁决 | PublicationFence 在取消与开始提交之间原子裁决；提交完成后按 durable commit 返回已发布结果 |
| Java 可维护性 | 请求/结果/上下文改为不可变 DTO；拆分调用恢复、Lombok 规则、编译器池与模型消费；保留资源所有权、诊断路径校验和规则顺序 |
| 兼容边界 | 原入口与可执行文件路径不变；公开身份、协议和图库版本不变，Rust 不重编码 Java metadata |

| 本轮回归 | 结果 |
|---|---|
| Rust | workspace 182 项通过、1 项需 G1 的测试跳过；图 20、真实 Rust 5、文本 2、标注 4，以及 fmt / DDL / clippy 通过 |
| Java | fixture 641 项通过；源码审计通过，来源清单 561 个文件、24 个适配文件校验通过 |
| JVM / Native | JVM 图 7；Native manifest 5、ABI 生命周期 8、图 8、错误 JDK 与真实 SIGSEGV 恢复通过 |
| 分发与验收工具 | release 包、原路径产品图/错误 JDK/随包 fixture 通过；当前门禁、查询多重集和运行路径回归 8 项通过 |
| 冻结真实项目 | 12 张逻辑表及 6 类公开查询与改造前 JVM 基线一致；重复记录、NULL、数组顺序和源码位置保留 |

| Native/G1 唯一一轮 | 观测 |
|---|---:|
| index 命令墙钟 | 214.548 s |
| 发布 facts.db | 233,615,360 bytes / 233.62 MB |
| 同时刻进程树 RSS 采样峰值 | 2,715,762,688 bytes / 2.53 GiB |
| 证据目录磁盘采样峰值 | 2,439,239,085 逻辑 bytes / 2,481,790,976 分配 bytes |
| 全流程监控墙钟（含启动、查询与图校验） | 230.883 s |
| partial 文件 | 854，保留原覆盖缺口 |

输入为 iopengw 冻结提交 `267c7ced4993aa88613f8f17a8099943fec98159` 的独立副本，仅已提交内容、MAIN+TEST；原目录 `/Users/stream/codes/antcodes/ipay/gw-group/iopengw` 未修改。改造前源码固定 `e4230c2`，先封存 JVM 基线，再在最终冻结输入上重放。外部 Maven 导出器原字节独立封存，避免 SDK 安装覆盖控制输入；实际 5,983 个输入文件及源码/产物的 SHA 均核对。预算见 [实施与验收](delivery.md#本轮整改验收入口2026-10-16)，应用状态独立且为空；OS 页缓存未控制，进程采样 1 秒、磁盘采样 10 秒。磁盘范围含本轮证据、暂存和原二进制备份，不能与单个 facts.db 混比；RSS 是同一时刻进程树之和，不是独立峰值相加。

原实现两次重放的 nodes/edges/call_sites 原文摘要不同，取证为 JSON 对象键顺序跨进程变化；解析后的 12 表多重集相同。最终 Native 也单独记录这三表的原文差异，不宣称字节等价。逻辑比较只规范 JSON 对象键并排除运行时 classpath 摘要；数组及字段值仍严格比较，存储不改写 metadata。公开查询只额外排除 overview 顶层宿主 profile。

证据根目录为 `target/architecture-hardening/20261016T031030Z/`：汇总 `validation.json`，本轮 `iopengw-native-single/receipt.json`，原实现重放与差异 `baseline/replay/receipt.json`、`baseline/replay-equivalence.json`。安全拦截、来源清单准备失败和未启动索引的运行路径失败均保留；实际 Native 全量只有一轮，结束后原 debug 字节已恢复。运行路径准备问题随后由 manifest 直接定位原执行目录解决，并补回归；没有重跑测量或修改分析产物。

本轮完成整改和上述回归，未签正式完整语义、容量或稳定性能放行，`native_accepted=false`、`performance_accepted=false`。历史 214.28 s / 233.49 MB 回执保留，不能将不同预算的单轮差值解释为提速或回退。Linux、目标 Java 17/25、完整 Rust 语义/来源覆盖等未完成项保持。

## Java 目录命名（2026-10-15）

Java 源码统一位于 `lang-java-adaptor/`，Maven 父 artifactId 同名；底层构建和 manifest 脚本分别为 `scripts/build-lang-java-adaptor.sh`、`scripts/java-adaptor-manifest.py`。Rust 开发路径、构建/安装/分发、测试及来源清单同步使用新名称。CLI/daemon/worker 仍从原 Cargo 路径执行，协议和图库版本保持；历史本机回执按原路径和产物解释，不改写旧证据。

| 更名回归（macOS ARM64 / 目标 Java 8） | 结果 |
|---|---|
| 源码与入口 | 615 个文件移动；其中 604 个文件字节不变，其余仅修正 POM/来源清单中的拼写；Java/C/header 字节不变，546 个来源文件校验通过，现行引用无旧拼写 |
| Rust / Java / Maven | Rust 完整门禁 181 项通过、1 项需 G1 的测试跳过；Java fixture 636 项、路径工具 28 项、Maven 导出与 21 项对齐通过 |
| JVM / Native | JVM 图 7 项；新目录 Native 开发构建/安装、G1 生命周期 8 项、图 8 项、错误 JDK 和独占真实 SIGSEGV 恢复通过 |
| 分发 | release 构建、包 checksum、原执行路径图 8 项/错误 JDK/随包 fixture 通过；源码及产物无漂移，原 debug 字节已恢复 |

回执：`target/java-adaptor-rename/validation.json`。Native 重建在完整 Java fixture 通过后使用 `--build-only -DskipTests`，ABI/产品回归另行执行；不签正式迁移或容量验收。本轮不重测 iopengw。

## Rust 可维护性重构（2026-10-15）

| 改动 | 保持的边界 |
|---|---|
| Rust/fixture 图行改用具名结构，关键身份与状态改用 newtype / enum | 列顺序、NULL/空值、metadata 原文、公开 ID 和协议版本不变；Java 原始行不重编码 |
| 拆分配置与捕获、Java 准备、Maven 导出、HIR 采集和 Rust 索引分支 | 原路径/文件名、临时资源寿命、取消、增量和一次 CAS 发布保持 |
| `TaskRepository` / `IndexRepository` 收窄访问；Engine 状态私有 | 候选和基线使用关联类型；报告/历史/维护仍依赖具体存储，不宣称应用层完全解耦 |
| 结果按业务字段稳定排序，冻结页保存可选排序策略 | 新首页顺序允许变化；相同键不去重，旧页按保存顺序重放 |
| Java 分发同时携带 Rust 语言 worker 和输入导出器 | 混合语言使用同套产物，release 在原 Cargo 路径暂存并恢复 |

本轮按用户要求不做 iopengw 验收，不更新历史性能结论。验证证据位于 `target/rust-maintainability/`；具体排序和数据边界见 [契约](contracts.md)，职责与端口见 [架构](architecture.md)。

| 重构回归 | 已执行结果 |
|---|---|
| Rust | workspace 181 项通过、1 项需 G1 的测试跳过；图 13、真实 Rust 5、文本 2、标注 4，以及 fmt / DDL / clippy 通过 |
| 基线等价 | 12 个逻辑表和 5 类查询内容无差异；新查询顺序按业务键变化，旧冻结页兼容测试通过 |
| Java / Maven | `just test-java`、Maven 导出 fixture 与 21 项对齐通过；Java 源码无改动 |
| 显式 JVM | 图 7 项通过，含真实 Java/Rust 混合 full/delta 等价、两种语言保留及 strict 失败保旧 head；用例与产物无漂移 |
| Native | G1 开发构建、manifest 5 项、ABI 生命周期 8 项、图 8 项、错误 JDK 及独占真实 SIGSEGV 恢复通过；不签正式 Native 或性能放行 |
| 分发 | release workspace、完整包 checksum、原路径图 8 项、错误 JDK、随包 fixture 自动启动/index/calls/source 通过；包与源码无漂移，原 debug 字节已恢复 |

最终汇总：`target/rust-maintainability/validation.json`；完整 JVM 图、最终混合复验和分发证据分别为该目录的 `jvm-graph-complete`、`jvm-mixed-verified`、`release`。Native 完整命令日志为 `native-gate.log`，最终 Rust 门禁为 `rust-gate-verified.log`。基线比较保留图 hash/重复次数，查询按记录多重集比较；旧页兼容由应用层回归覆盖。准备过程的序列化、路径和用例登记问题保留在 `preparation-failures.json`，未将首次六项 JVM 回执当作七项通过。

## Rust 存储压缩与有界流水线（schema 9）

Rust 单语言已直接复用 GraphStream：blocking HIR/记录生成 → 4 批 / 8 MiB 队列 → async IPC → 有界队列 → blocking SQLite。独立控制读取保留半帧，满队列/写阻塞仍可取消；只有 VerifiedEnd 后才能封存。混合语言继续采用已验证归档合并。活动 blob pin 使用引用计数，文件 I/O 共享并发上限 2，复制/校验/文件同步移出全局锁，最终持久化和 CAS 屏障保留。

schema 9 将 Rust 路径/world/key 字典化、依赖表改为 WITHOUT ROWID；依赖从状态 JSON 去重，baseline 批量还原。公开图 ID、worker 20、graph model 3、逻辑 schema 33 不变；schema 8 及更旧图必须 `index --full`。实现同时加入行首索引、owner 分组、任务内源码定位/path 缓存、接口摘要排除嵌套执行体，以及无变化跳过 HIR。

| 指标 | 优化前参考 | 本轮固定输入 |
|---|---:|---:|
| 内部 index 耗时 | 66.155 s | 54.453 s（参考差值 -17.7%） |
| CLI 墙钟 | 未记录 | 55.576 s |
| facts.db | 70,434,816 bytes / 67.17 MiB | 9,777,152 bytes / 9.32 MiB（-86.1%） |
| worker 监督器采样峰值 | 340.45 MiB | 339.67 MiB |
| daemon 独立峰值 | 未采样 | 96.52 MiB |
| 同一时刻 daemon + worker 树 RSS 峰值 | 未采样 | 435.22 MiB |
| blobs | 1,511 / 18,602,179 bytes | 相同 |
| 节点 / 边 / 调用点 / 调用目标 | 2618 / 6710 / 10545 / 1257 | 相同 |
| 增量依赖 | 109,394 | 相同，包括失败 lookup |

环境为 macOS ARM64 / debug，空独立 home、512 MiB worker、300 s 期限。冻结输入 SHA 与旧参考一致，原源码/依赖逐文件对照旧 archive 后运行，仅执行一轮；新二进制 SHA、输入、源码 archive 和失败历史各自保留。回执：`target/rust-dogfood/optimized-512m/receipt.json`；公开节点、边 hash、调用点 hash、调用目标和全部依赖逐项相等：同目录 `equivalence.json`。

worker 阶段：数据库加载 0.254 s、声明 12.856 s、绑定 28.291 s、最终增量摘要 0.209 s、行生成及背压 1.831 s。daemon `analyze_ms=46.534 s` 包含候选写库完成，发布 7.633 s；SQLite 阶段合计 4.757 s，与传输重叠，不能再次累加。worker/daemon 队列估算峰值分别 942,362 / 979,625 bytes，发送等待约 0.639 / 0.112 s，消费等待约 0.244 / 0.733 s。

外部进程树采样 worker 峰值为 341.72 MiB，与监督器 339.67 MiB 因采样时刻不同；435.22 MiB 是同一采样的总和，不是独立峰值相加。0.78 MiB 的监督器差异不足以宣称显著内存收益。默认请求仍为 256 MiB，本轮不声称默认预算已跑通；历史 256 MiB 失败回执不变。

当前源码另做功能 dogfood，初次 full 产生 2,690 节点、6,895 边、10,863 调用点、1,295 目标，图库 10,203,136 bytes；回执 `target/rust-dogfood/optimized-functional/receipt.json`。它与固定旧输入测量分开。同目录 `validation.json` 已通过三个自身符号查询、无变化跳过 HIR、方法体修改仅改变一个 owner 的 source hash 且全部 interface hash 保持、delta/full 图逐项相等，以及恢复原源码后的图一致。

当前回归：Rust workspace 174 项通过、1 项需 G1 的测试跳过；真实 Rust 导出/E2E 5、公共图 13、文本 2、标注 4，以及证据保护/采样口径 2 和 bundle 版本生成 1 项通过。Java fixture 636 项、显式 JVM 产品图 6 项通过。新增半帧控制取消覆盖未消费输出，新增坏 owner/旧 schema、增量依赖往返、嵌套方法体和 GC/共享 hash 验证。fmt、clippy、DDL 与脚本检查通过。

JVM 回归曾被生成脚本写死 graph model 2 阻塞；已让 JVM/Native manifest 从 Rust 常量生成，打包/运行时按 manifest 核对，并补未来版本回归。准备失败原因保留于 `target/rust-dogfood/optimization-java-graph-preparation-failure.json`。本轮未重建 Native、未做 release/正式 Native 验收。汇总回执 `target/rust-dogfood/optimization-validation.json`，其中二进制 SHA 与单轮回执一致。

上述旧值来自功能参考记录，新轮加入更细诊断；单样本不证明稳定加速或完整语义，仍为 87 文件 partial，`performance_accepted=false`。版本边界及宏/sysroot 限制保持。

## Rust 输入收敛与内存诊断（schema 2，优化前历史记录）

当前采用项目源码加一层 Cargo 直接依赖；依赖只辅助解析，不发布自身图。项目与依赖目录规则分别配置，在复制/捕获前过滤；辅助文件按白名单加载。主动宏展开已关闭，HIR 内部仍可按需展开声明宏；过程宏不执行。旧 schema 1 输入要求重导，adapter/profile 改变后首次全量清除旧宏生成事实。

| 当前验证 | 结果 |
|---|---|
| Rust 门禁 | workspace 167 项通过、1 项需 G1 的测试跳过；真实 Rust 导出/E2E 5 项、公共图 13 项、文本 2 项、标注 4 项通过；clippy、fmt、DDL、脚本语法及 diff 检查通过 |
| 输入边界 | 项目 → A → B → C 仅保留项目与 A；过滤后的旧复制文件不进入捕获/HIR；MAIN/TEST、别名、cfg、strict、delta/full 保持回归 |
| 预算 | Rust 默认请求 256 MiB，项目配置可修改且不超过用户上限；只改预算无需重导、保持语义 profile；doctor 披露请求与上限 |
| 单轮环境 | macOS ARM64 / debug；空独立 home，256 MiB worker、512 MiB 输出、2,000,000 fact、300 秒期限；产物与冻结输入 SHA 随回执保存 |
| 实际输入 | 274 个候选实例保留 46 个，其中 12 个发布项目实例；过滤 663 个源码实例；实际 1,529 文件/实例，18,237,477 bytes |
| 测量结果 | 21.773 s 后 `WORKER_MEMORY_BUDGET`；最后阶段 `bindings`，触发采样/已见峰值 268,484,608 bytes（256.046875 MiB），限额 268,435,456 bytes |
| 发布与证据 | 无 revision 发布；`target/rust-dogfood/memory-v2/receipt.json`、输入清单及 `frozen-sources.tar.gz` 保留，不加预算、不重复测量 |
| 512 MiB 功能验证 | 同一二进制/输入 SHA，仅修改项目预算和独立用户上限；成功发布 `rev_802cbd117e25451f85ff312ba2a2b32a`，采样峰值 356990976 bytes（340.45 MiB）；87 文件 partial，三个自身符号查询通过 |

该采样是终止前已见的峰值，不能推断完成索引所需的最低内存，也不能将失败中止耗时用于速度比较。本轮尚未在默认预算下完成自身索引，`performance_accepted=false`。当前产物未重新执行 release 或 Java Native 正式验收。配置、过滤语义和命令见 [Rust worker](modules/rust-worker.md)。

512 MiB 功能回执保存在 `target/rust-dogfood/functional-512m/receipt.json`。本次未重新导出输入；`stage_graph_records`、`analyze`、`index_rust` 均可定位。功能验证与原 256 MiB 固定预算测量分别保存，不作为新的稳定性能结论。

## Rust 首版自身验证（schema 1，历史回执）

以下为输入收敛前的历史产物。采用固定 `ra_ap_* = 0.0.349` 的 syntax/HIR API，不通过 LSP 自动加载工作区。`scripts/export-rust-inputs.py` 显式生成输入；索引不运行目标项目构建脚本或过程宏。适配器通过独立 worker 交付 GRA1，复用既有存储、查询和原子发布；混合项目一次切换 head。

| 验证 | 当前证据与边界 |
|---|---|
| Rust 门禁 | workspace 163 项通过、1 项需 G1 的测试跳过；真实 Rust E2E/导出 3 项、公共图 13 项、文本 2 项、标注 4 项通过；fmt、DDL、clippy 与 diff 检查通过 |
| 解析与增量 | 双 crate fixture 覆盖跨文件模块、别名/重导出、trait、声明宏、cfg、失败查找、取消、输入漂移；函数体局部重绑定及接口/重导出失效传播 |
| 产品 | 真实 Rust worker → store → CLI；MAIN/TEST world、resolve/structure/calls、delta/full 一致、strict 拒绝和过期输入保留旧 head |
| 混合存储 | 真实 Rust archive 与 Java fixture 行合并；Rust delta 保留 Java ID，未封口失败不发布。未重测当前协议的真实 Java Native 混合运行 |
| code-ana dogfood | 冻结的自身源码快照成功发布 `rev_e24ea07adaad4e61bed26f12bcfc199b`，3,649 节点、9,063 边、10,590 调用点、1,410 调用目标；图库 77,234,176 bytes |
| 自身查询 | `stage_graph_records`、`analyze`、`index_rust` 均可经现有 CLI 定位 |
| 覆盖 | dogfood 为 partial，88 文件有缺口；sysroot 未加载、过程宏不执行、动态派发及宏展开上限均明确记录 |

dogfood 使用独立 home、4 GiB worker 预算、512 MiB 结果预算及 2,000,000 fact 上限。功能回执保存在 `target/rust-dogfood/functional-receipt.json`，包含失败尝试及成功产物 SHA；快照早于最后的模块包含关系修正，不冒充最终源码逐字验收。默认 256 MiB 的首次性能尝试以 `WORKER_MEMORY_BUDGET` 失败，原回执 `target/rust-dogfood/receipt.json` 保留；后续仅功能验证，不报告性能通过或稳定容量结论。

当前 worker 21 / graph model 3 / 物理 schema 10 需要配套重建；旧图执行 `index --full`。下文 Java Native / release / 性能回执属于各自历史产物，不构成本次版本的正式放行。

Rust 分发脚本已包含真实 worker、导出器、fixture 和语言文档，release 门禁已增加真实 Rust E2E；本轮验证 debug 构建及脚本语法，未重新执行 release 构建和分发验收。

## Rust 结构与回归

请求/状态/错误枚举、显式 protobuf 映射、类型化查询记录与稳定属性、按能力拆分的读接口、索引阶段和任务/发布端口已落地。运维、Maven/Java 控制结构有明确 DTO；FILES 不实现语义图，内部整数主键不进入查询属性。类型与职责约定见 [架构](architecture.md)，编码及旧页兼容见 [契约](contracts.md)。

以下类型改造回执属于 Maven 降级改动前的 worker 18 / request 9；新增版本回归见下一节，不将旧回执重标为新产物。

| 门禁 | 2026-10-14 类型改造阶段结果（macOS ARM64） |
|---|---|
| `just test-rust` | DDL、fmt、workspace、图 13 项、文本 2 项、标注 4 项、clippy 通过 |
| `just test-java` | Java fixture、桥与模型通过 |
| Native | G1 开发构建与安装、manifest 5 项、ABI 生命周期 8 项、图 6 项、环境检查及真实 SIGSEGV 恢复通过 |
| 显式 JVM 产品 | 图 5 项通过，无预期项漂移 |
| Rust release | checksum 通过；包内图 13 项中执行 8 项、跳过 5 项故障注入；manifest 从现行常量生成，版本为 3/18/2 |
| Java release | release workspace 构建、checksum、隔离安装运行及原 Cargo 路径恢复通过 |

Native 首次安装因活跃 JVM 触发 `STOP_RUNTIME_BEFORE_NATIVE_INSTALL:97923`，未绕过保护；等待 JVM 退出后完成安装和剩余门禁。Rust 图门禁首次发现故障注入文本多了空格，恢复 `TEST_FAULT:migration` 原拼写后完整重跑通过。新增回归覆盖 wire 枚举拒绝、上下文包装后的取消身份、属性缺失/null/扩展透传、类型化端点、旧页读取及内部主键隐藏；既有测试覆盖 full/delta、冻结历史、分页、CAS、取消、重启和发布后失败。

本机汇总：`target/rust-types-refactor/validation.json`；显式 JVM 图证据：`target/rust-types-refactor/jvm-graph`；Java release 回执：`target/java-alignment/evidence/release-package-20261014-115048-15443/receipt.json`。本次未重测冻结真实项目；以下 2026-10-13 单轮按原产物解释，不作为类型改造后的容量或稳定性能结论。

## Maven 依赖失败后的源码分析

独立导出器现在使用 `maven-inputs-v2` / manifest schema 4。有效模型已校验后，外部依赖解析失败或 jar 缺失可整次转为源码分析；保留 reactor、源码根与 MAIN/TEST，丢弃全部外部 classpath。索引只读 manifest，不启动 Maven；超时、取消、模型变化、损坏回执和清理失败仍中止。schema 3 必须重新显式导出，旧图无需因本次功能升级改物理格式。

三态依赖模式贯通 worker 19 / analysis request-result 10、报告与签名缓存。失败模式输出 `CLASSPATH_UNAVAILABLE`、`analysis_mode=source_only`、`classpath_status=unavailable`，即使未引用缺失依赖也不声明语义完整；`--strict` 返回 3 并保留旧 head。模式切换全量重建；必要的未选中 MAIN 上游只供分析，不发布。详情见 [配置](configuration.md#maven-依赖失败与源码模式)。

| 本次验证（macOS ARM64 / 目标 Java 8） | 结果 |
|---|---|
| Rust 完整门禁 | workspace、DDL、fmt、图 13 项、文本 2 项、标注 4 项、clippy 通过；含快速退出、终态冲突、超时不降级、schema 4 和全局 coverage 回归 |
| Java / Maven fixture | 初始完整组件回归通过；状态与缓存改动后 13 项关联专项通过；21 项 Maven 对齐及导出 fixture 通过。636 条组件记录为分批执行，未冒充单次最终源码完整验收 |
| JVM / Native 产品 | 最终 JVM 图 6 项、Native 图 7 项、G1 生命周期 8 项通过；覆盖失效/恢复、strict、增量/全量、历史报告、未选模块及无变更复用 |
| Native 故障与安装 | manifest 5 项、错误 JDK、独占真实 SIGSEGV 通过；安装/运行时工具单元回归通过 |
| Java 分发包 | checksum 与执行路径字节核对、release 图 7 项、错误 JDK、随包 fixture 自动 daemon / index / calls / source 通过；原 debug 二进制已恢复，源码和包产物无漂移 |

汇总：`target/maven-fallback/validation.json`；JVM、Native、ABI、分发回执分别在该目录的 `jvm-graph-sealed`、`native-graph-final`、`native-lifecycle-sealed`、`release-sealed`。原失败回执及原因保留在 `preparation-failures.json` 与对应目录：包括旧 extension、安装保护、JSON 键序比较、终态读取时序，以及误并行故障注入。真实崩溃测试新增独占检查，已串行复验；库 SHA 已恢复核对。

仅选择性借鉴 Anatomist `e8a940557c9365084cdd5318ed2be3d147697113` 的行为与测试场景，来源/覆盖清单记录补充引用，整体上游基线仍为 `4a903272...`。本次不重测冻结 iopengw，不新增容量/稳定性能结论，`native_accepted` / `performance_accepted` 仍为 false。

## 版本

| 项目 | 当前值 | 说明 |
|---|---|---|
| CLI-daemon handshake | 4 | `DAEMON_PROTOCOL_VERSION` |
| worker protocol | 23 | `WORKER_PROTOCOL_VERSION` |
| graph model | 5 | `codeana_model::VERSION`；manifest 仍沿用 `fact_ir` 键 |
| analysis request/result | 12 | Java bridge 与 Rust host 必须成套 |
| C ABI | 4 | 共享库/header/host 必须成套 |
| javac 模型 / compiler batch | javac-model-v51 / 2 | 匹配的 G1 开发 Native/JVM SDK 已安装 |
| 图库物理格式 | user_version 12 | 增加独立注释/关联表及 FTS，保留紧凑布局 |
| 上游逻辑格式 | schema 35 / GraphSemantics 27 | `project_meta.schema_version` 与物理格式不是同一个版本 |
| adapter | anatomist-4a903272-codeana-graph5-resource2-analysis | 当前上游参考 `4a903272c22083585107e982f2899ed03a280aa2` |
| catalog | 2 | 事务升级；独立人工笔记库已移除 |
| Native 构建 JDK | 25.3.4+1.r25-graal | `lang-java-adaptor/.sdkmanrc` 固定 Oracle GraalVM |

图 schema 11 及更旧格式的查询/增量返回 `GRAPH_SCHEMA_REBUILD_REQUIRED`。执行 `index --full` 创建新 revision，成功后切 head；旧文件保留，不原地迁移。历史图查询也要求兼容物理格式。

## 最新单轮结果

| 指标 | 2026-10-13 紧凑存储结果 | 口径 |
|---|---|---|
| 冻结 iopengw | HEAD `267c7ced4993aa88613f8f17a8099943fec98159`，MAIN + TEST | 仅已提交内容 |
| 索引 | 214.28 s | 一轮 Native/G1 全量；无 native 冷热分类 |
| 图库 | 233,488,384 bytes = 233.49 MB = 222.67 MiB | 发布的单个 facts.db；此前 606,650,368 bytes，减少 61.51% |
| worker 进程树峰值 | 2.699 GiB | 同时刻 host/原生库 + javac RSS |
| 目录逻辑峰值 | 2.35 GiB | 既有流程 2.69 GiB；不等于最终图库大小 |
| 逻辑记录 | MAIN / TEST 节点 38,839 / 16,561；元注解 56,957 条 | 与原图库相同；元注解 341 个定义、3,403 个归属来源 |
| 字典 | 路径 4,317、外部目标 7,182、共享 metadata 2,688 条 | integrity/FK/FTS 通过，最终无空闲页 |
| 正确性 | 12 个逻辑表多重集一致；integrity/FK/FTS 通过 | 已有 854 个 partial 文件保留，不声称语义全覆盖 |
| 验证 | Java 636 项；Rust 完整门禁；ABI 8 项；图 6 项；真实 SIGSEGV 和 release 门禁 | 本轮已执行范围 |

首次测量准备因缺少独立应用状态标记被采集器拒绝，尚未启动索引，原回执保留；补齐后仅运行一次全量索引。214.278 s 属于该次实际索引流程；发布及 12 表逻辑多重集比较成功，随后 `search-code` 因逻辑视图沿用 `nodes.rowid` 查询失败。

修复将 FTS 整数主键映射到稳定 ID，归档原测量二进制及查询源码，在同一已发布库重查 6 项通过；图库 SHA 不变，未再索引，原失败回执保留。该时长不冒充修复后测量。既有 G1 流程 234.879 s、进程树峰值 2.856 GiB，与本轮环境/变更不同，不能全部归因到数据库压缩；单点不构成稳定性能承诺。

该轮 Java 636 项组件、Rust 完整门禁（31 项存储，其中 5 项紧凑布局）、G1 生命周期 8 项、Native 产品 6 项、真实 SIGSEGV 和 release 图/错误 JDK/自动 daemon 三组验收通过。release 字节临时安装到原执行路径，验证后恢复 dev 二进制；fixture 和该次单轮不签完整语义/来源覆盖或容量/稳定性能验收。

| 本机回执 | 路径 |
|---|---|
| 原测量（保留查询失败） | `target/compact-storage-upgrade-01/receipt.json` |
| 同库查询复验 | `target/compact-query-recheck-01/receipt.json` |
| 汇总 | `target/compact-storage-upgrade-01/implementation-receipt.json` |

target 是可清理的本机证据位置，上述路径不保证在每个 checkout 存在；历史源码及文档由 Git 保留。验证入口和维护规则见 [实施与验收](delivery.md)。

## 支持范围与未完成项

| 已验证范围 | 仍未关闭 |
|---|---|
| macOS ARM64、目标 Java 8、G1 正常产品链路、全量/增量与公开图查询 | Linux 和目标 Java 17/25 |
| Maven 外部 metadata、Lombok 声明模型、Spring/SOFA 已发布静态图 | Gradle、全部框架规则和跨语言语义 |
| 紧凑存储、历史/源码/分页、故障保旧版 | 当前版本完整语义/来源覆盖验收；旧 raw transport 门禁已删除 |
| fixture 和一次冻结真实项目测量 | 独占容量/稳定性能验收；`native_accepted`、`performance_accepted` 仍为 false |

开发 build-only、Hello、fixture、旧版本完整回执均不能代替当前版本正式验收。验证顺序和命令见 [实施与验收](delivery.md)。

## 递归与资源边界治理（2026-10-31）

JSON 已改用 Jackson Core 2.21.5，保留显式 codec；活动路径防环、输出展开深度 256、读取默认深度 512，拒绝非有限数字。Spring XML 保留 JDK SAX，解析与图提取共享每文件预算；配置树用显式栈。硬资源及基础设施异常穿透扩展与可选缓存恢复边界。

Native 完整产品回归发现额外根因：缺少 Xerces `XMLMessages` 资源包，使平台深度超限变成 `MissingResourceException`，被当作普通坏 XML 恢复并发布早期 bean。已注册 Native 资源包，并将缺包异常列为不可恢复失败。新 Native C ABI 同时验证普通坏 XML 保留原恢复语义、硬超限不交付归档。全量/增量失败保留旧 head、旧 revision 可读及修复输入后重试的产品回归已通过。

Rust 已接入根操作累计工作与载荷预算，覆盖有界图读取、revision/snapshot 元数据、源码、pipeline、diff 双端/impact、文本派生缓存及冻结页。执行配置逐字段继承且下次根查询生效；不改变语义指纹。受限结果携带 `resource_usage` 和 `COMPUTATION_LIMIT`，续页不恢复计算。调用关系增加可选直接递归投影，不改图库格式或 worker/ABI 协议。adapter 实现身份更新为 `anatomist-4a903272-codeana-graph4-resource1`，原上游基准和逐项来源 SHA 保留。

本轮最终使用固定 SDKMAN `25.3.4+1.r25-graal`（实际 java 25.0.4.1 / Oracle GraalVM 25.3.4.1+1.1）、Java 8u492 和 Rust 1.96.0。完整 Java fixture 653 项，0 失败/错误/跳过；项目进程架构审计 1 项通过。Rust 完整门禁、最终 query 30 项、文本发现定向测试、fmt/clippy，以及 Python 核心/7 项产品和 prepared inputs 三语言补充用例通过。

固定工具链 G1 开发构建/安装、9 项 C ABI 生命周期、9 项 Native 图与资源产品、真实 SIGSEGV 故障保旧 head 与恢复均通过；JVM 产品 8 项通过。Serial 早期开发验证和旧 GraalVM 在 macOS ARM64 的 G1 构建失败日志均保留。最终 release 包四组门禁、执行路径校验和 dev 二进制恢复结果见本轮 `release-package-g1/receipt.json`。

按用户确认只验收自建 fixture，iopengw 排除，不作为待办。本轮额外冻结 120 个 Java 文件，在独立空应用状态仅做一次有效 release/G1 全量观测：索引 2.471 s，1,560 节点 / 3,478 事实，图库 1,982,464 bytes；1 秒采样的整个命令进程树峰值 231,620,608 bytes（包含观测包装进程，不是精确分配峰值，短命子进程可能漏采），完整性/FK 与索引后查询通过。首次观测因 fixture 位于上层仓库忽略的 target 目录，源码未进入 manifest；该失败回执原样保留，修正隔离后另建观测目录，不覆盖原结果。该小型 fixture 不能代表独占容量或稳定性能。

来源清单检查通过（564 文件 / 42 适配项），来源审计可指定冻结仓库路径；本机参考仓库缺少固定 dff2e395 对象，完整上游来源门禁未签署。G1 fixture 也不替代正式完整语义/来源与容量放行。

最终回归、源码/输入/产物 SHA 及原失败记录汇总于 `target/resource-governance-20261031/receipt.json`（本机产物，可清理）；`native_accepted` / `performance_accepted` 保持 false。

## 统一 adaptor 资源治理当前状态（2026-10-31）

| 边界 | 当前实现 / 证据 |
|---|---|
| 公共账本 | 四维限制、原子多维计费、父子作用域、活动路径、粘性失败、取消/单调截止时间；配置逐字段继承，预算不作为执行语义身份 |
| 静态分片 | 根额度扣输入准入，再按Java/Rust/Python稳定顺序分配；host向上取整1/4，其余worker；同语言实例静态分片、不借用、不因attempt续领 |
| 三个adaptor | Rust/Python/Java主要输入、遍历、投影、输出与host路径计费；JavaFFI接续同一账户，局部作用域与缓存冷暖路径已接入。Pyrefly内部solve仍由进程/RSS/supervisor约束，不宣称每一步精确计费 |
| Discover | 生产入口启动前绑定实例，在有界Hello中签发grant并核对首个水位，再准入Discover业务帧；worker拒绝缺失/语言错误grant，配置读取/解码预留计费；BuildContext累计回执只用于wire，不改变语义profile |
| 未变复用 | 普通及prepared多实例通过Reuse/Reused终止账户，phase=reused，记录真实Discover消耗；不会留下Reserved或伪造0 |
| 失败 / 报告 | 整图失败不切head；JSON/Markdown展示同一核实累计用量。丢失最终回执时Unknown保留最后核实水位，不能证明FFI最终消耗 |
| Java源码验证 | 当前Java分析协议11全量669项通过，0失败/错误/跳过；生产请求grant/seed/deadline与结果已知状态均强制校验。前阶段scope/cache证据保留原版本 |
| 本阶段公共验证 | model49、contracts38、application35、Rust worker5通过；Java worker9通过/1个既有忽略项。完整just test-rust通过：workspace256通过/1个既有忽略项，另含图22、Rust语言5及参考/文本脚本门禁；独立Python workspace clippy通过 |
| 严格边界 | 当前源码worker22、Java request/result11、ABI4；生产grant/seed/deadline必填，终态回执及usage_known必须显式存在；其余codec/签名投影/ASM、完整混合故障矩阵与release仍未关闭 |

当前22/11产品验证与产物摘要汇总在target/unified-analysis-resources/strict-protocol/receipt.json；discover-reuse回执仍代表之前21/10产物。资源E2E已扩展到29项，包含三语言普通复用、prepared Python三实例/Rust两实例复用、Discover超限保旧图及恢复。最终资源29项、JVM资源/复用/Discover/XML9项、Native图与XML9、生命周期10、manifest5、Python原产品7、显式启用真实Java的prepared三语言全套7项均通过且无跳过；真实SIGSEGV保留Discover水位、保旧图、回收及SDK恢复后索引通过。

重复执行index --prepare会创建新的generation目录，当前prepared身份包含这些产物路径；未变复用测试使用同一份已准备输入。原始两项测试输入选择错误、编译缺字段及修复后回执均保留。此前阶段原失败和SHA仍在java-scopes、java-context、python-deadline等回执中，不把旧产物改标为本阶段通过。

iopengw按用户确认排除，只使用自建fixture。本阶段不是严格协议、正式Native、release或容量/性能放行；后续完成情况见本文最终验收记录。


### 严格worker22 / Java分析11（本阶段验证通过）

旧worker握手、旧Java请求/结果与混装SDK会拒绝；Java生产请求不能因缺少grant/seed/deadline回到默认账本。回执的usage_known使用protobuf optional bool，缺失拒绝；false只允许失败携带最后核实水位，不能认证End或Replan。所有语言共用host回执核对策略，已知I/O失败不伪造资源超限，重规划仍累计计费。

取消控制核对attempt，host在既有2秒期限内接收终态；输出回压时worker只短暂尝试发送回执，不阻塞退出。Java预分配OOM/Stack/Fatal错误均带version11与usage_known=false，保留原故障。直接Native诊断fixture也显式提供grant/seed/deadline；生产入口没有自动降级。

本阶段保留原始失败：取消回执受输出回压阻塞、4项解析fixture仍用版本10、来源清单同步、归档描述符未识别新usage_known字段。描述符现显式要求已知状态，未知或缺失不能交付Begin；修复后22/11全量门禁结果见target/unified-analysis-resources/strict-protocol/receipt.json。前一节discover-reuse的21/10产物保留原含义，不能作为本阶段22/11放行。

### IPC 业务帧分配前准入（2026-10-31）

三种真实 worker 和 host 统一使用 contracts 准入层。Hello 中显式 grant/deadline 与首个回执先于业务解码；健康 Java worker 的新任务重新握手。大正文、解码记录/容器与编码缓冲在分配前预留。16 KiB 控制缓冲置于堆上，超限后仍可回传失败；FFI 控制通道在 seed 前预留且只接受 1 KiB Cancel。半帧取消保留进度，排空阶段不再解码业务模型；解码前拒绝使用有界控制前缀取得新 attempt，发送侧资源失败保留原错误码。发布确认只接收有界控制消息。

本轮原失败保留：异步大栈缓冲导致产品栈溢出、发送侧错误身份丢失、解码拒绝带旧 attempt，以及旧固定测试额度提前耗尽。证据汇总于 target/unified-analysis-resources/frame-admission/receipt.json。公共帧入口收口不代表语言内部所有投影/codec 已全部审计；后续完成情况见本文最终验收记录。

本轮最终门禁：contracts 48 项（新增帧边界 10 项）、完整 just test-rust、独立 Python 构建与 clippy、三语言 Native 资源29项、Native 图/XML9项、JVM图/XML8项均通过；产品门禁记录的源码/产物漂移均为空。Java源码未变，本轮不冒充重新运行669项Java源码测试，也不重签Native/release/性能放行。汇总为 frame-admission/receipt.json。

### Java 签名、Jackson 传输与 SOFA 边界（2026-10-31）

声明指纹/view、源签名投影与ASM写出显式接续任务账本；类型擦除与ASM signature visitor有深度保护，资源失败穿透签名恢复边界。Native/JVM传输 facade 也已迁到Jackson，已删除自写JSON解析/编码及两份无调用旧投影；固定canonical UTF-8/数字精度兼容向量通过。Rust在FFI前预留JSON解码值节点与16个seed节点，Java结果编码先预留后生成累计回执。源码slice/position、coverage与SOFA SAX/注册/元数据也已接入累计账本。

完整Java fixture688项通过，0失败/错误/跳过（新增19项），固定工具链G1 Native开发构建/安装成功；协议仍worker22/Java11/ABI4。原编译修复与测试输入先触发8MiB捕获上限的回执保留，SOFA产品用例改用低于8MiB的输入触发其64MiB局部预留，未抬高限额。最新产品验证见 target/unified-analysis-resources/java-signature-codecs/receipt.json。Lombok overlay与JavaExtensionData freeze等构造前核对、其他语言内部副本审计、完整混合故障矩阵和三语言分发仍继续，未签正式Native/release/性能放行。

本轮最终实际通过：Native资源29、Native图/XML10、JVM图/XML9、Native生命周期10、manifest5、环境1、真实SIGSEGV/SDK恢复再索引；产品门禁无源码/产物漂移，SDK恢复后的SHA一致。完整Rust门禁通过，contracts51项，独立Python构建/clippy通过。范围汇总java-signature-codecs/receipt.json仍标complete_plan=false；Lombok/元数据freeze的后续完成情况见本文最终验收记录。

### Lombok/元数据与内部副本继续治理（2026-10-31）

Java扩展、Lombok配置/生成声明/类型索引和调用证据、元数据freeze都接续原任务账本；不可变复制前拒绝超限，局部硬失败不恢复为部分图。生成方法保持canonical嵌套名，避免每个参数复制完整类型图。Rust/Python内部JSON和重试DTO通过公共serde准入，读取有界；Python路径自动机及文本扩展提前预留。

本阶段发现重复shade会沿用旧合并JAR中的依赖类：Java源码/fixture通过不证明该Native包包含新代码。现强制重建薄JAR，并在JVM/Native生成前逐类核对reactor结果；旧失败和修复记录保留。最新实际门禁以target/unified-analysis-resources/java-extensions/receipt.json为准；在最终回执封存前，本段不签整个计划或正式容量/性能放行。

### 统一资源治理八项：本机自建 fixture 验收完成（2026-10-31）

| 范围 | 最新通过 |
|---|---|
| 源码 | Java701；Rust完整门禁、contracts56；Python25与clippy；Java来源清单571/75 |
| 产品 | Native资源/混合/历史42、Native图11、JVM图10、生命周期10；prepared三语言7，全部产品门禁无跳过 |
| 分发 | Rust26、Python34；Java四组release门禁；五个实际release二进制在原路径再跑统一42项，通过后全部开发二进制SHA恢复，SDK不变 |
| 故障 | 真实Native SIGSEGV保旧图、Unknown/最后水位、回收进程、恢复SDK后再索引均通过 |

Lombok/元数据freeze和内部JSON/DTO副本准入已关闭；重复shade旧依赖和SOFA属性顺序漂移已修复。验收汇总见target/unified-analysis-resources/java-extensions/receipt.json。历史阶段记录保持原含义，不能替代当前源码/产物组合。

本轮只签资源治理和本机fixture分发验证：formal native/release/performance acceptance均为false。Linux、目标Java17/25、完整上游语义/来源和稳定容量/性能仍属于项目未完成范围；不将javac/rust-analyzer/Pyrefly内部CPU或RSS冒充自身逻辑计费。iopengw排除。首次prepared缺Maven、旧shade、超时先触及载荷、测试包导入缓存等原失败均留证。


## todo 基础边界实施（2026-11-01，部分任务完成）

开发基线为 `5d2283f37675244f67de928bdb582e6025fb1e17`，各阶段提交以 Git 历史为准；产物、源码摘要与原失败日志登记在 `target/todo-implementation/receipt.json`。A0—A3 基线采用 daemon 4 / worker 22 / graph 4 / Java analysis 11 / ABI 4 / 图库 11/34；C1 后现行版本以本文版本表为准。

| 任务 | 当前实际范围 |
|---|---|
| A0 完成 | 固定提交、版本与产物；原始 HEAD 导出副本的 application/config/storage 110 项通过；Java 基线 701 项通过，无失败/跳过 |
| A1 完成 | 公共派生缓存移至独立 crate，缓存源文件逐字节保持；Java worker 不依赖图库；scratch 预算归公共模块；普通/prepared Python 共用输入构造，保留原错误身份 |
| C0 完成 | 三语言注释领域类型、关联/位置/查询契约和目标版本表已冻结；Java 8 预期样例编译与 9 个源码锚点通过，不是提取器验收 |
| A2 完成 | IndexPlan 与公共执行层负责语言 fan-out、输入所有权、复用/确认/回收及单次发布；Java 健康池、Rust 增量、Python 保守全量和 prepared 降级保持显式策略 |
| A3 完成 | config 解析/发现/捕获/执行预算拆分，CommentReader 与内部有效参数归一化完成；公开注释参数/配置及冻结页实参在 C1 接入 |
| C1 完成 | Java 提取、GRA1、注释表/FTS、公开查询及配置已验证；完整 Java 719 项通过；证据 comment-c1-final/receipt.json |
| C2 完成 | 生命周期 Native 8+2 项、完整 Rust、预算/取消及旧冻结页兼容通过；证据 comment-c2-final/receipt.json |
| C3/C4、A4—A7 完成 | 三语言注释、实际分发、资源矩阵及自建 fixture 单轮测量通过；范围见本轮回执 |

最终 Rust 完整门禁通过，默认跳过的真实 G1 teardown/禁止重建测试另行显式执行通过。Python 核心、CLI 7 项、显式 Native prepared 7 项、混合产品 2 项、分析资源 29 项、历史 1 项及混合失败/取消/超时 12 项通过。新增计划 4、Python 输入 3、上下文归一化 2 项断言包含在对应回归中；DDL、fmt、clippy、Java 来源清单和本轮新增/调整的相对链接检查通过。历史回执链接保留原记录，不作本轮验收依据。

保留原失败：默认 Rust 1.95 低于项目要求，改用已安装 1.96；Python 独立锁文件补入共享缓存 crate；原始基线导出首次缺 include_str 使用的 docs；prepared 首次因 PATH 无 mvn 导致 Java 入口降为 files_only。补入 SDKMAN Maven 与固定 JDK 后完整 prepared 和混合验证通过，没有修改产品的降级规则。

本轮未执行分发、冻结 iopengw 或正式性能测量；Native 产品回归使用已有匹配的 build-only SDK。`native_accepted=false`、`release_accepted=false`、`performance_accepted=false`。不能把这些基础改造回归写成整份 todo 或注释功能完成。

后续 A2 普通入口：Python/混合使用公共运行输入集合，参与语言按计划裁决；单 Java setup 按 worker、输入顺序释放。输入装箱预留在 worker 启动前接续 host 账户，避免预留失败时先释放运行中 worker 的输入。

A2/A3 现已收口：47 项应用测试覆盖显式回收、非回收的存活检查、失效 PGID 保护及原有生命周期；最终二进制真实 C ABI SIGSEGV 验证旧 head 保留、unknown 用量披露、回收和恢复成功。证据见 `target/todo-implementation/execution-lifecycle/receipt.json`。分词及源码映射 8 项专项通过，见 `target/todo-implementation/comment-tokenization/receipt.json`。注释生产链路仍待 C1 接入；未执行本功能 Native 构建/分发或正式测量。

早期 C1 Java 模型（v51）验证（以下为当时状态，现由 C1/C2 收口记录补充）：完整 Java 718 项通过，0 失败/错误/跳过；JVM runner、Java 8 compiler bridge 和 Maven worker 的包内容及来源清单校验通过。17 项新增注释测试覆盖原 expected.json 的真实词法/四类关联、Unicode/CRLF/emoji、空文档、多字段文档、绑定失败、ABI 契约不变及预算边界；连同既有并行入口共 18 项专项通过。Rust Java worker 9 项及 clippy/fmt 通过，默认真实 G1 项 1 跳过。证据：`target/todo-implementation/java-comment-model/receipt.json`。已安装 Native SDK 仍为 v50，未构建/安装 v51 Native SDK，未验证当前 C1 Native 产品/分发；此前 v50 Native 回执保持历史含义。GRA1 注释行、图库/FTS、公开查询、其余版本切换和 C2—C4/A4—A7 继续待办。

C2 收口：注释增量、清空、重命名、语言/world 删除、历史与冻结页、预算/取消/真实 worker 崩溃、CAS 和 FTS 清理通过。SQLite 正文先按长度准入，保留注释再次核对捕获 owner/hash，旧游标兼容由原 HEAD 的真实序列化摘要验证。混合 Java/Rust 生命周期使用既有矩阵的 1 GiB 预算；原默认分片 128 MiB 的 Native 启动失败仍保留，不作为容量通过。证据：`target/todo-implementation/comment-c2-final/receipt.json`；正式分发与测量待 C4。

C3 收口：RA 真实注释 token/字面量 doc 属性与 Ruff/Pyrefly docstring/# token 已接入公共链路；不执行宏、包含表达式或目标代码。精确声明锚点、嵌套/匿名作用域、UTF-8 转义映射、独立 COMMENTS 覆盖、预算和冻结路径还原已验证。Rust producer 为 rust-analyzer-0.0.349-codeana-5-comments，Python 为 pyrefly-1.4.0-codeana-v5-comments。完整 Rust 314 单元通过/1 默认忽略、Python 核心 30；真实注释 Rust 2/Python 3、三对组合及三语言 3、prepared 7/Python 产品 7 通过。原 prepared 缺 Maven PATH 的降级失败及查询页大小/扫描范围测试修正日志保留。证据：`target/todo-implementation/comment-c3-final/receipt.json`。本轮未签分发或性能放行。

A6 收口：Lombok 实现与资源移入 framework-lombok；生产 AnalysisSession 与诊断入口共用 FrameworkPlugins.selected 装配。扩展必须实现带 AnalysisContext 的回调，旧两参实现编译失败；生产入口复用原累计账户。真实产品回归曾发现生产选择器漏装 Lombok，已修正并补回归，原失败保留。当前 Java/Native 728/728；Native/JVM 综合各 23/23，11 组产品/ABI/G1/崩溃恢复门禁全部通过，SDK 前后字节一致。证据：target/todo-implementation/a6/receipt.json。原来源身份不变，8 项覆盖路径迁移；本机参考仓库缺固定提交对象，未宣称重新完成上游全量覆盖验证。A6 完成，A7 继续最终门禁、分发与注释缺口修正；不重测或重标 C4 性能。
