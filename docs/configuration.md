# config.toml：配置与当前执行边界

**公共配置管扫描、预算和版本；语言配置管分析语义；模块与 world 表达同仓库中的不同构建上下文。**

状态：用户已确定语言需显式启用、目标项目构建只返回建议、全新产品无旧配置兼容。Java 后端固定 javac（D16）；执行方案见 D17。严格 Rust 加载器与捕获已实现；Rust 语言分析仍是后续设计，Java 8 支持显式 plain 和外部 Maven metadata，现行边界见 [当前实现](current-state.md) 和 [Java worker](modules/java-worker.md)。示例见 [项目 config.toml](config.toml) 和 [用户 config.toml](examples/user-config.toml)；混合语言示例展示后续扩展，当前可运行配置以 Java 8 示例为准。

## 1. 配置分层

```text
用户 config.toml                          项目 config.toml
  daemon / 资源硬上限                       scan / build / query / versions
  本机 toolchain 别名                       languages.java / languages.rust
          |                                modules -> worlds
          +--------------------+-------------------+
                               |
                  daemon ConfigResolver + 校验
                               |
                   发现真实模块 / 解析 auto
                               |
                  冻结 EffectiveConfig 与来源
                               |
          +--------------------+--------------------+
          |                    |                    |
     JavaAnalysisConfig   Rust 配置（后续）    Capture/QueryPolicy
          |                    |
       Java host           Rust adapter（未实现）
```

Java 原生适配器固定使用 Java 25 + GraalVM；目标 JDK 编译器桥单独构建，当前 Java 8 桥字节码为 52，与 Java 25 Native 分开验证；`languages.java.analysis.source_version` 描述**被分析源码**的语法版本，`analysis.toolchain` 描述用于该项目解析的工具链。修改这两个字段不会重编适配器或切换它的构建 JDK。

## 2. 文件位置与归属

| 文件 / 输入 | 建议位置 | 允许内容 |
|---|---|---|
| 项目配置 | `<project>/.code-ana/config.toml` | 可移植的分析、模块、world、查询默认值、版本策略 |
| 用户配置 | `~/.code-ana/config.toml` | daemon 生命周期、用户级资源上限、本机工具链位置 |
| 运行时 endpoint | 用户运行目录中的生成文件 | 实际 gRPC endpoint、进程身份；不是项目配置 |
| CLI overrides | 一次 TaskRequest | 明确存在的参数映射、选择范围与预算；不写回文件 |

项目与用户文件均使用 `schema_version = 1`；`kind = "project"` / `"user"` 区分允许字段。当前 CLI 支持 --project 与 --home；默认用户 home 为 ~/.code-ana，运行 endpoint 在所选 home 下生成。

每个任务根据明确 project root 只选择一份项目配置；首版不递归读取子目录配置、不隐式向父目录找配置、不支持任意 `include` 配置链。monorepo 的差异在 `modules` 中表达。

用户文件不存语言语义默认值，避免不同机器悄悄改变项目分析。项目也不能覆盖 daemon 的监听范围、安装产物位置或用户资源硬上限。配置中不提供 worker 可执行文件/共享库任意路径；这些由安装 manifest 决定。

| 用户配置键 | 类型与语义 |
|---|---|
| `daemon.idle_timeout_seconds` | 整数秒；默认 600，0 关闭空闲退出；仅无活动任务/读者时退出 |
| `daemon.java_warm_workers` | 布尔值，默认 true；仅复用已确认发布且身份匹配的单个空闲 Java worker，60 秒过期 |
| `daemon.java_signature_cache` | 布尔值，默认 true；启用安全子集的 NoCode 签名缓存，缺失/损坏自动回退完整源码分析 |
| `limits.max_workers` | 1–8，默认 2；全部项目合计的顶层 worker 槽位；每槽允许的编译器子进程数另由执行策略固定，不能无限派生 |
| `limits.max_worker_memory_bytes` | 正整数字节；每 worker 进程树的最大可申请预算（Java host/原生库与目标 JDK 子进程合计） |
| `limits.max_total_worker_memory_bytes` | 正整数字节；worker 合计预算，默认 4 GiB，调度时按任务预约；不包含 daemon 自身 RSS |
| `toolchains.<alias>.language` | 已知语言 ID，别名匹配时检查语言一致 |
| `toolchains.<alias>.home` | 已安装工具链的绝对根目录；解析后记录实际版本/产物身份，而不只记录路径 |

用户数值未配置时使用明确缺省值；默认每 worker 256 MiB，可申请 16 MiB–4 GiB，项目请求仍受用户上限约束。不同平台的内存计量、主动停止与进程级限制需实测，TOML 设置本身不代表已经实现精确 OS 硬限制。

## 3. 公共与语言命名空间

| 节 | 主要字段 | 归属 / 规则 |
|---|---|---|
| `[project]` | `name` | 展示名，不参与 ProjectKey 的唯一性判断 |
| `[discovery]` | `mode = "auto" / "explicit"` | 仅在显式启用语言内发现模块，或只分析列出的模块入口；不用于发现/启用语言 |
| `[scan]` | `scopes`、`include`、`exclude`、`text_only` | 各语言共用范围；scope 的语言映射必须披露 |
| `[build]` | `suggestions = true / false` | 默认 true，缺产物时返回执行建议；false 只隐藏命令建议，仍报告缺口；两者都不运行目标项目构建 |
| `[index]` | `mode = "auto" / "full"` | 增量策略，不容纳 Java 专属字段 |
| `[resources.index]` | worker/heap/并行/模型/结果/暂存预算 | 已实现，见下表；受用户上限约束，显式大预算不代表容量验收通过 |
| `[resources.query]` | `diff_spill_bytes` | schema 接受，但当前执行路径未读取该字段，不能作为生效的 diff 暂存预算 |
| `[query]` | `scopes`、`max_output_bytes` | schema 接受，scopes 会补 ALL；当前查询未应用这些 TOML 默认，以 CLI/TaskRequest 的 scopes 和 max_output_bytes 为准 |
| `[observability]` | `mode = "off" / "basic" / "full"` | 当前默认 basic；旧拼写 `detailed` 暂作为 full 的兼容别名 |
| `[search.text]` | `backend`、`include` | include 已控制工作区文本范围；backend 当前不切换引擎，实际使用 Rust 匹配与可选 FTS 缓存 |
| `[versions.capture]` | `include_ignored` | 增补 WORKTREE 捕获范围，不绕过 excludes |
| `[versions.gc]` | `auto`、`keep`、`max_bytes`、`max_age_days`、`include_caches` | auto 默认 true、keep 20、max_age_days 30、max_bytes 0；引用保护。include_caches 当前未接入执行路径 |
| `[languages.<id>]` | `enabled`、`mode` | 语言能力选择；支持的 mode 由版本化语言描述符声明 |
| `[languages.<id>.discovery]` | `build_system`、Java `inputs_manifest` | Java schema 为 auto/maven/gradle/plain，支持 plain 与外部 Maven inputs_manifest；Gradle/Rust 仍未实现 |
| `[languages.<id>.analysis]` | 语言专属分析参数 | 由对应 adapter 的 schema 校验，不能塞回全局 index |
| `[languages.java.lombok]` | `mode = "off" / "model"`、`strict` | 用户确定直接挂在 Java 下；Lombok 模型不经过 extensions 中间层 |
| `[languages.<id>.extensions.<id>]` | 框架参数 | Spring/SOFA 等框架配置归所属语言，不出现在其他语言命名空间 |
| `[languages.<id>.external]` | `exclude_patterns` | 匹配规则由语言定义；Java 包前缀规则不自动套给 Rust |
| `[[modules]]` | `id`、`language`、`root`、`manifest` | 一个语言构建入口；同目录不同语言必须分开列出 |
| `[[modules.worlds]]` | `id` 及该语言 world 参数 | 显式语义实例，不自动生成 features 的组合矩阵 |

### 已执行的索引预算

| resources.index 字段 | 默认 | 值域 / 约束 |
|---|---|---|
| worker_memory_bytes | 用户分配值，默认 256 MiB | 项目不超用户上限；同时刻进程树 RSS 采样超额终止，不是内核硬隔离 |
| java_native_heap_bytes | 256 MiB | 8–2048 MiB，按 MiB 对齐 |
| java_compiler_heap_bytes | 128 MiB | 8–512 MiB，按 MiB 对齐 |
| java_parallelism | 预算允许时 2，否则 1 | 显式 1–8，预约 native heap + 256 MiB + N×(javac heap + 64 MiB) |
| java_model_record_limit | 100,000 / context | 1–5,000,000 |
| java_model_archive_bytes | 64 MiB / context | 64 KiB–1 GiB |
| java_model_decode_bytes | 512 MiB / context | 64 KiB–2 GiB，保守分配累计估算 |
| java_compiler_timeout_seconds | 120 / context | 1–3600 秒 |
| java_result_bytes | 64 MiB | 64 KiB–8 GiB，控制结果 / GRA1 归档分别受限 |
| java_fact_limit | 100,000 | 1–5,000,000，全任务行/归属计数 |
| java_scratch_bytes | 2 GiB | 64 KiB–32 GiB，捕获副本 / 模型 / 候选暂存 |

当前 schema 固定 Java/Rust 命名空间，未知语言/字段拒绝；不从项目下载插件。扩展描述符/新语言注册是后续设计，Rust 字段被解析不能证明 Rust adapter 已存在。

| 语言专属键 | 值域 / 解释 |
|---|---|
| Java `analysis.source_version` | `"auto"` 或十进制版本字符串，如 `"8"`；当前产品只支持目标 Java 8，schema 接受其他数字不等于可执行 |
| Java `analysis.toolchain` | `"auto"` 或 Java 工具链别名；解析出的 JDK 必须满足所请求的分析能力 |
| Java `analysis.classpath` | `"existing"` 或 `"none"`；none 仅禁用项目依赖类路径，javac 所需目标 JDK 平台类型仍必需 |
| Java `analysis.vm_classpath` | boolean；javac semantic 只接受 true，false 在分析前拒绝；不取宿主 GraalVM JDK 冒充目标 JDK |
| Java `lombok.mode` / `lombok.strict` | off/model 与 boolean，默认 off/false；model 为独立 Java 声明模型补全，strict 将不完整模型纳入质量门禁，不触发 Lombok 编译执行 |
| Rust `analysis.edition` | `"auto"` 或 adapter 支持的 edition 字符串；显式值须与有效构建声明一致，冲突拒绝 |
| Rust `analysis.toolchain` | `"auto"` 或 Rust 工具链别名；实际版本与产物内容进入 profile |
| Rust world `target` | `"host"` 或工具链支持的目标平台名；分析前解析能力与依赖可用性 |
| Rust world `features` / `default_features` | feature 字符串数组 / boolean；按 manifest 和构建图校验 |
| Rust world `cfg` | 规范化字符串数组，元素为 `name` 或 `name="value"`；语法与冲突由 adapter 校验，不允许覆盖保留的目标平台内置项 |

首版只接受 Rust semantic 模式的显式 `modules.worlds`；syntax 模式保留源码上的 cfg 条件但不解释 feature world，因此显式配置 semantic world 时拒绝不匹配模式，避免“写了配置却未生效”。

## 4. 默认值与最小配置

当前 Java fixture 的显式模块示例见 [java-fixture-config.toml](examples/java-fixture-config.toml)。下方最小配置只启用 Java，模块在该语言内发现仍需已导出的 Maven inputs_manifest；plain 需显式源码根和目标 Java 8。它不启用 Rust 或其他语言。

```toml
schema_version = 1
kind = "project"

[languages.java]
enabled = true

```

| 省略项 | 默认语义 |
|---|---|
| 没有项目文件 | 不发现/启用任何语言；语义 index 返回配置缺失及示例建议；已有 revision/历史只读不因此失效 |
| 有项目文件但未启用语言 | 不发现/启用任何语言；语义 index 返回语言未配置；只有报告配置或检测到 manifest 都不构成启用 |
| 显式启用语言 | 仅 `languages.<id>.enabled=true` 的语言参与索引；Java 的 auto 模块展开需已导出的 inputs_manifest；无 metadata 的 auto 明确拒绝；modules.language 不隐式启用语言 |
| files/search-text | 无语言声明也可按显式文件范围读取；不触发语言发现、解析或编译 |
| language.mode | Java 为 semantic；Rust 为 syntax；Rust semantic 必须显式选择，执行中不静默降级 |
| scan.scopes | MAIN + GENERATED；query.scopes 默认 ALL，含义仅为已捕获范围 |
| include / exclude / text_only | `["**"]` / `[]` / `[]`；建议 include=[] 表示所有已选源码根内路径 |
| Java source_version / toolchain | auto；版本来自已验证模块声明；工具链只从用户登记的别名中选唯一匹配项，零个或多个匹配都要求显式指定；来源与最终版本必须披露 |
| Java 版本声明缺失 | 不猜 Java 8；需要显式 source_version 才能开始 semantic |
| Java classpath | existing；已有可验证依赖缺失则按 coverage/门禁处理，不自动执行构建 |
| Java 扩展 | Lombok off、strict=false；Spring enabled=true、xml=false；SOFA enabled=true；当前静态 XML 接口清单可独立于 Spring |
| Rust edition / toolchain | auto；从有效 manifest/toolchain 输入解析，无法确定则披露能力不足 |
| Rust world 未列出 | 后续 semantic 设计为 host / default_features=true；当前没有 Rust adapter，不发生真实 crate graph 解析 |
| 数值资源预算未设置 | 使用用户级服务策略并披露实际值；不能把省略理解为无限资源 |

开启缺少已安装能力的语言时，在运行前返回 unsupported；不能默默只分析另一种语言。语义失败可产生 partial coverage，由健康门禁决定能否发布，与无效配置的直接拒绝区分。

### javac 工具链与能力边界

| 层次 | 当前规则 |
|---|---|
| 后端 | 固定 javac，无 parser 切换字段、JavaParser 依赖或失败回退；未知旧解析器配置按未知键拒绝 |
| 构建 JDK | 原生库由锁定的 GraalVM JDK 25 构建；不参与目标版本自动推测 |
| 执行 JDK | D17 使用目标 Java 8 JDK 启动编译器桥；检查 java/javac、平台/编译器产物和内容身份 |
| 目标版本 | 当前仅目标 Java 8，并要求完整 SDKMAN Java 8 JDK；17/25、跨版本 --release 和 preview 未支持 |
| auto | 不读取 PATH、JAVA_HOME 或宿主版本作隐式回退；用户别名的实际版本与内容摘要冻结到请求 |
| 多模块 | 首版同目标版本 Maven reactor；混合目标版本、多个命名 JPMS 模块或未验收语法明确 unsupported，不能扁平化后伪装成功 |
| Lombok | 使用 off/model；model 是声明覆盖层，不执行处理器、不生成业务方法体。没有 compile=true 产品开关；缺真实产物给外部建议 |
| 执行预算 | P0/P1 先串行；编译器堆、任务数、帧/解码/临时磁盘与进程树预算由执行策略冻结，不照搬参考项目数值 |

项目源码本身存在错误时可输出带诊断的部分事实；缺少可用编译器、版本不匹配或内部协议失败则终止。不得混淆两种失败。

## 5. 模块、源码根与 world

| 配置 | 规则 |
|---|---|
| module.id | 项目内唯一稳定人工标识；不直接使用绝对 checkout 路径 |
| module.language | 一个明确语言；混合模块拆成多个配置入口，可以共享根目录 |
| module.root | 相对 project root；manifest、显式 source root 相对此 module.root |
| module.manifest | 可选构建入口；auto discovery 可按 build_system 找到，explicit 模式无歧义时也可推导 |
| auto + modules | 先发现，再以规范化 `(language, root, manifest)` 精确应用覆盖；重复覆盖或别名冲突拒绝 |
| explicit + modules | 只处理列出的入口及其构建图需要的子模块/crate；不扫描无关目录 |
| 构建入口包含多个模块 | adapter 以配置 id 为稳定前缀、构建图内相对身份为后缀生成子模块；不得吞掉子模块差异 |
| modules.analysis / lombok / extensions / external | 覆盖该语言的同类型配置；Java 模块用 modules.lombok 覆盖 languages.java.lombok；Rust 模块出现 lombok 或其他 Java 字段直接报错 |
| modules.scan | 只进一步收窄范围；不可用 include 绕过全局 exclude 或增加未启用 scope |
| modules.source_roots | 显式 `path/scope`；Java 可加 `label` 作为发布身份，省略时使用物理 module.id；GENERATED 仍需封存产物，不自动运行生成器 |
| Rust 源文件发现 | 以 crate graph 和模块语义为准；不把所有 `*.rs` 当互相独立的完整 crate |
| modules.worlds | 模块内 id 唯一；id 是显示/选择别名，真实 WorldKey 由解析后的构建上下文计算 |

```text
project
  +-- module: order-service / Java
  |      +-- MAIN + TEST source sets -> 各自语义实例
  |
  +-- module: native-engine / Rust
         +-- world: default -> host / default features
         +-- world: fast    -> host / default features + fast
                  |
             每个 crate/target 各有真实 WorldKey
```

Rust world 的 `target` 是目标平台，不能与 Cargo 的 lib/bin/test target 混淆；后者由 crate graph 和所选 scope 展开。`features`、`default_features` 和显式 `cfg` 是请求条件，实际 feature 统一结果与构建 cfg 以捕获的 BuildContext 为准。`host` 必须解析成具体 target 身份，不能将字符串 host 当跨机器稳定环境。

Java world 首版由真实模块、源码集和已解析构建上下文派生；不添加一套复制 Maven profile 语法的通用 world 字段。物理 Maven module.id/root 决定编译上下文与依赖方向。Java source_roots.label 只改变 SourceUnit.module 和 LogicalSymbolKey；SourceUnit.world 仍由物理 module.id × scope 派生。同物理模块 MAIN 的多个标签共享一次 javac，TEST 读取该模块 MAIN。最长根决定标签；同深度不同标签/物理模块拒绝。label 非空、最多 256 字节且不含控制字符，Rust 不接受该字段。MAIN 不读取 TEST，扫描排除不能被编译器隐式搜索绕过。缺少必需上下文则披露/拒绝。

实际 JDK/依赖内容身份进入 semantic profile，不机械改动 LogicalSymbolKey。

## 6. 优先级与路径规则

```text
内置默认
   -> 项目公共策略 + languages.<id> 默认
   -> 匹配 module 的专属覆盖
   -> world 的构建条件（仅其允许字段）
   -> 本次明确映射的 CLI 参数
   -> 语义校验 / 用户资源上限裁决
```

| 类型 | 合并 / 解释 |
|---|---|
| 标量 | 支持的分析/捕获字段按层覆盖；false、0 不当成“未设置”；查询保留字段不自动生效 |
| 表 | 按 schema 中的已知字段递归合并，禁止自由 map 吞未知字段 |
| 一般数组 | 显式后值整体替换，不自动拼接；空数组含义按字段定义 |
| 范围限制 | 捕获 scan scopes/include 取允许范围交集，exclude 取并集；这是数组规则的明确例外 |
| worlds / modules | 不是普通可追加配置数组；id 唯一，完整定义按精确入口匹配；不按数组下标覆盖 |
| Rust features / cfg | features=[] 表示无额外 feature，default_features=false 才关闭默认 features；cfg=[] 不清除目标平台内置 cfg |
| CLI 语言参数 | 仅发给目标语言；不能借覆盖参数隐式启用未配置语言或改变 adapter 的 Java 25 构建工具链 |
| build.suggestions | 仅控制是否附带外部执行建议，不授予编译权限；产品只消费已有可验证产物，无自动执行开关 |
| 用户硬上限 | project/CLI 超限请求拒绝并报告上限；不静默截断请求预算；省略时使用服务分配值 |
| 文件路径 | project root 在任务接受时固定；全局 glob 相对项目，module.source_roots 相对模块；换 CLI cwd 不改变含义 |
| 源码边界 | 规范化后仍须位于捕获项目内；JDK/依赖是单独身份输入，不能冒充项目源码 |
| 字符串展开 | 首版不执行 shell、不隐式展开 `$VAR`/`~`；本机路径在用户配置中用绝对路径 |

注释、TOML 字段顺序和无语义的格式变化不触发语义重建；但配置文件自身的文本变化仍属于文件捕获/diff 证据。

## 7. EffectiveConfig 与指纹

| 输出 | 内容 / 用途 |
|---|---|
| EffectiveConfig | 已补默认、类型化、解析 auto 后的各模块/world 配置与来源；本次任务不可变 |
| FieldOrigin | 文件/键路径/行列，或 CLI 参数/内置默认/构建发现；doctor 可解释“为何生效” |
| ConfigDigest | 规范化有效配置与 schema/默认值版本的整体摘要，描述请求，不直接等同语义 profile |
| SemanticConfigDigest | 按模块/world 切分的模式、版本、工具链内容身份、依赖顺序、扩展、语义分析预算等 |
| CapturePolicyDigest | scan、text_only、ignored 文件和控制输入选择；影响 snapshot 可比性 |
| QueryPolicy / ExecutionPolicy | 查询、报告、服务调度、GC 与资源策略；分别记录，不无故清空语言事实 |

```text
EffectiveConfig + 捕获源码/依赖/工具链 + adapter/model/compiler-bridge/rule versions
                         |
                SemanticProfile（实际分析环境）
```

`profile` 不是用户随手命名的 TOML preset。首版不另加 `[profiles.*]` 继承语法；模块/world 已能表达首批差异。features 等集合规范化排序；classpath、解析器搜索路径等有优先级的数组保留顺序。摘要不直接使用 protobuf 序列化字节。

| 改动 | 失效范围 |
|---|---|
| Java source_version / JDK / classpath / Lombok / Spring XML | 对应 Java 模块及依赖闭包；无法证明局部安全则该语言全量 |
| Rust target / features / cfg / toolchain / mode | 对应 Rust world/依赖闭包，或创建新 world；旧世界保留为历史 |
| 关闭语言 / 移除模块或 world | 新 revision 显式替换掉相应 owners，不能继续当作当前结果 |
| scan/capture 变化 | 输入清单与覆盖变化，重算受影响事实与 snapshot 请求身份 |
| CLI scopes / output bytes / report / GC / daemon idle / build.suggestions | 改变请求/执行策略，不单独使已发布语义事实失效；TOML query 默认当前未应用 |
| javac 线程数、编译器堆/进程回收策略 | 属于执行策略；成功结果必须等价。改变恢复搜索范围的预算属于语义配置，需指纹与限制披露 |
| 索引内存上限改变 | 记录执行策略；成功结果不应变义；若某分析预算影响求解结果，则必须计入语义指纹并披露 |

单个语言的配置变化不应机械重建另一语言。混合项目修订可复用有证明的未变语言分区；新 revision 原子发布所有分区引用及项目 coverage，不能让关闭语言后仍出现陈旧事实。

## 8. 加载、校验与重载

| 阶段 | 所有者 | 检查 |
|---|---|---|
| parse | Rust ConfigResolver | TOML、schema_version、kind、重复键和类型 |
| structural validate | 公共配置 schema + 随产品分发的 adapter 描述符 | 已知字段/枚举、路径、module/world id、语言归属、扩展依赖 |
| resolve | 发现服务 + adapter | 实际模块、auto 版本、工具链别名、manifest 与构建要求 |
| policy validate | daemon | 请求预算与上限、外部构建建议策略、范围冲突 |
| freeze | 索引编排 | 有效配置、来源、所读文件 hash、实际环境与 profile |
| execute | worker/query | 只消费已冻结的类型化配置，不各自重新读 config.toml |

项目配置在每个新任务接受/准备时重新检查；排队任务持有固定输入配置，变化要显式重新准备，不能分析到一半换配置。daemon 进程级配置首次启动载入，首版修改后经 drain/restart 生效；不做半生效热更新。用户工具链映射同样绑定配置 generation，新任务说明使用哪个 generation。

历史 `snapshot:<id>` 读取使用已保存的 profile 和 EffectiveConfig；当前项目文件或用户工具链变化不会重解释历史事实。files-only 和兼容图的历史只读不要求启动语言 worker，也不因缺少当前 JDK/adapter binary 而失去已有证据；旧图物理格式仍会返回 GRAPH_SCHEMA_REBUILD_REQUIRED。

| 无效输入 | 预期处理（错误码以 Rust 校验/实际返回为准） |
|---|---|
| 未知语言/字段、错误类型、未来 schema | 在索引前拒绝，给文件位置与期望类型/版本 |
| 同模块重复 world id / 重复入口覆盖 | 拒绝歧义，不按最后一条获胜 |
| Rust 模块写 java_version / Java mode=syntax | 按对应描述符拒绝无效字段或能力 |
| SOFA enabled、Spring disabled | 当前仅解析 SOFA 静态 XML 接口；不产出 Spring Bean/注入事实，不自动开启 Spring |
| world 请求 semantic、只有 syntax 能力 | unsupported；不静默降级 |
| source root 的 scope 未被 scan.scopes 允许 | 拒绝矛盾配置；不悄悄扩大扫描 |
| 显式工具链别名不存在 | 明确失败；auto 也必须披露实际选用版本 |

## 9. 多语言 scope 与文件归属

| 公共 scope / 文件 | Java | Rust |
|---|---|---|
| MAIN | 生产源码集 | 常规 lib/bin 语义实例 |
| TEST | 测试源码集 | test/harness 与 cfg(test) 上下文；同一源码可以对应多个 world |
| GENERATED | 已捕获生成源码 | 已捕获生成输入或展开来源；不能声称宏来自独立磁盘文件 |
| XML / 配置资源 | 所属框架 producer 依赖 | 无所属 adapter 时只按文件/文本证据处理 |
| 同物理文件多 world | 同一字节、不同语义身份 | 同一字节、不同语义身份 |

当前实现中 `scan.include` 筛选捕获文件，模块 source_roots 再决定语义归属；exclude 同时限制语义、文本和捕获，必需控制输入被排除则报错。text_only 不参与语义/持久捕获/diff，只有 search.text.include 另选中才进入本次工作区文本查询；历史查询仍只读取当时已捕获文件。`.code-ana/config.toml` 在文件/语义捕获中作为控制输入保留；工作区文本搜索校验它的 hash，但不把它当作搜索内容。工具自己的缓存和产物默认排除。

某语言无法精确映射 scope 时披露 unknown/partial，不能全部贴 MAIN。同一源文件被同一语言的两个模块无依据重复拥有时拒绝；真实多 world 或不同语言的显式事实各有 owner。

## 10. 外部构建建议

```text
显式语言配置 -> 读取 manifest / 已有产物
                       |
                  缺失编译依赖/生成源码
                       |
           返回缺口 + BuildAdvice（不执行）
                       |
             Agent / 人类检查并执行建议
                       |
           用户重新提交索引 -> 捕获并验证新产物
```

建议包含目标项目/module/world、工作目录、参数数组、目的、工具链要求、预期产物和重试入口。不得因显示建议就将任务标成已构建或自动重试；缺失输入时按能力与门禁返回 partial/rejected。建议可能生成文件、下载依赖或运行项目脚本，需说明实际影响。

发现阶段只读取已有 manifest/缓存/构建输出；不能借获取 classpath/crate graph 执行会触发构建或生成代码的步骤。Maven、Gradle、Cargo 编译、build.rs、过程宏和 annotation processor 等目标项目代码执行均交外部 Agent/人类。执行后新提交的任务捕获实际产物，检查与源码/工具链/选项的对应证据；不能只凭文件存在就认为可用。

这是全新配置格式：不搜索或导入 `.anatomist/config.toml`，不实现旧字段别名或旧优先级兼容。未知字段直接报错。构建 code-ana 自身和 fixture 测试准备是开发流程，与产品分析目标项目时的执行权限分开。

javac 的 `parse/analyze` 在禁用处理器、无业务字节码生成、仅使用封存输入时属于索引分析，不属于目标项目构建。Maven 有效模型读取仍只消费已有 metadata；独立 codeana-export-java-inputs 命令显式启动 Maven，通过 core extension 在生命周期前退出并导出 metadata，可能下载依赖；该外部准备不同于索引只读发现。签名缓存是工具内部派生数据，不作为“目标项目已构建”的证明。

## 11. 验收

| 场景 | 必须验证 |
|---|---|
| Java-only / Rust-only / 混合项目 | 只调度启用语言，配置命名空间不串 |
| Java 25 adapter 分析 Java 8 项目 | 构建运行时与目标语法/解析 JDK 分离 |
| 同文件两个 feature world / Java 多模块 scope | 公共身份、coverage、增量基线分别固定；混合 Java 目标版本首版明确 unsupported |
| 配置注释变化 / 字段重排 | 文件 diff 可见，语义摘要不变 |
| 只改 report/output / 只改 Rust features | 前者不重建；后者不机械重建 Java |
| 禁用语言/扩展或删除模块 | 新 revision 无陈旧 owner，旧历史仍可读 |
| 排队/执行中改配置、daemon 配置未重启 | 有效配置 generation 明确，不半途漂移 |
| 缺配置、缺 language.enabled、只有报告配置或 manifest | 不自动启用语言，给出配置建议 |
| 未知字段/错类型/冲突来源 | 明确拒绝，不静默吞参数或加载其他产品配置 |
| 缺 classpath/生成产物、建议开关 true/false | 产品不执行目标构建或处理器；true 返回建议，false 保留缺口；外部执行后重试才捕获新产物 |
| TOML 到 protobuf / FFI 输入 | 各语言收到明确类型和相同摘要，不在 worker 二次加载文件 |

示例 TOML 已由 config 单元测试读取；Rust 显式配置/捕获走端到端验收。Java 8 工具链/metadata 已有独立回归；Rust 分析、Gradle 和 Java 17/25 仍未交付。

## 12. 当前执行边界

| 配置 | 实際支持 |
|---|---|
| discovery.mode=explicit + Java plain | 显式源码根、source_version=8 和登记的 SDKMAN Java 8 工具链 |
| discovery.mode=auto + languages.java.discovery.inputs_manifest | 外部 Maven metadata 展开，索引不调用 Maven |
| Java 17/25、Gradle、Rust syntax/semantic | schema 可描述部分请求形状；产品能力仍不支持，不静默降级 |
| daemon.java_warm_workers | 健康 worker/isolate 复用开关；名字不表示 Native 有冷/热执行模式 |
| Native GC | 构建默认 G1；不在项目 TOML 运行时切 GC，切换需成套构建/安装 |

当前 Native heap 为 8–2048 MiB，必须按 MiB 对齐；同进程 G1 isolate 不重建。开发 G1 验证与单轮性能见 [实施记录](implementation/anatomist-storage-migration.md)。

### 仅解析、尚未执行的字段

| 字段 | 当前代码边界 |
|---|---|
| query.scopes / query.max_output_bytes | 配置加载/默认化，查询仍读 CLI/TaskRequest |
| resources.query.diff_spill_bytes | 配置结构接受，执行路径不读取 |
| search.text.backend | 不选择外部 grep/rg；include 独立生效 |
| versions.gc.include_caches | GC 不据此回收派生缓存；缓存有自己的有界管理 |

以上来自 crates/config/src/lib.rs 的字段定义及 application/query/storage 的使用检查，不能把 TOML 解析成功写成能力已实现。
