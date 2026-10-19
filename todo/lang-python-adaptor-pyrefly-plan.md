# Python 语义适配器实施计划：Pyrefly 首选

> 2026-10-17；状态：P0–P5 在声明范围内完成。仓库 fixture、隔离 Release、多语言发布及冻结 diorama-sdd 25 项导航通过；单轮 38.458 s / 2.034 GiB，保留 partial 和全部原失败。证据见 [最新回执](../lang-python-adaptor/validation/2026-10-17/receipt.json) 与 [当前实现](../docs/current-state.md)。
> 代码检查基线：`4630a8b790e4bb1152fc4831e99f04617b2e0b94`。

> 2026-10-19 修复：已实现可选 pyproject / schema 2、完整诊断与反向依赖归因，修复读取引用和异常别名声明。相同冻结 615 文件、25/25 导航通过；30 complete / 585 partial，源码上下文剩 5 个真实外部缺失名称，辅助依赖缺失另列。无新增正式性能测量，见 [v2 回执](../lang-python-adaptor/validation/2026-10-19/receipt.json)。

## 1. 结论与范围

| 决策 | 方案 |
|---|---|
| 语义后端 | 首选 Pyrefly；首轮固定 1.4.0、提交 `feeee254e65844f23f973a0a29cae02d09931068` |
| 接入方式 | 独立 Rust `codeana-python-worker`，调用 `lang-python-adaptor`，由窄接口封装 Pyrefly Rust 内核 |
| 先验证什么 | 内核可构建，以及声明、引用、调用、未解析原因能否完整提取；CLI/LSP 探测成功不等于该接口已经可用 |
| 第一版 | 显式 Python 配置、冻结输入、全量分析、GRA1 图交付、现有查询、失败不切 head |
| 后续 | 多语言一次发布、保守增量、发布包与真实项目验收；不能把不支持的组合静默降级 |
| 不引入 | ty/Tree-sitter 自动回退、第二套 Python 解析树、LSP 逐位置查询作为全库索引主通道 |
| 执行边界 | 不 import 目标代码、不安装目标依赖、不执行构建或 `.pth` 中的代码；不使用 JNI/GraalVM |

Pyrefly 同时承担语法与语义分析。若内核接入有缺口，先记录缺失能力并补最小上游补丁，不自动更换后端。大规模维护上游分叉须重新评估成本。

## 2. 研究阶段依据及其边界（实施前快照）

| 证据 | 结果 | 能证明什么 |
|---|---|---|
| diorama-sdd 固定提交 | `31259b761a5ea77672d0e90bc9e2776e4dfe397e`；选定 615 个 Python 文件、121256 行 | 真实项目样本，不能代表所有 Python 项目 |
| 基础调用导航 | ty / Pyrefly 均 21/21 | 基础场景均可用 |
| 无返回注解工厂的属性/方法导航 | ty 0/4、Pyrefly 4/4 | Pyrefly 更符合本项目大量无注解代码的需求 |
| 9 个定向探针 | ty 7/9、Pyrefly 9/9；包含正确保留未知目标的 Any 场景 | 不等于全库调用图准确率 |
| Pysa 单独导出 | 覆盖全部 615 个目标文件；定向调用目标核对 23/23 | 有可复用的语义能力，不保证公开 Rust API 覆盖全部事实 |
| Glean、Pysa 同时导出 | 原配置及修正搜索根配置均 panic：`missing solutions`；分开导出可完成 | 第一版禁止组合报告通道；精确内部根因仍待定位 |
| 依赖环境 | 从已有 Python 3.14 环境冻结依赖，分析目标为 3.12/darwin；5 个外部模块缺失 | 不是完整、干净的 Python 3.12 验收环境 |

研究产物保留在 `target/python-adaptor-eval-20261016/`，包括 `summary.json`、`evidence-sha256.json`、导航回执、Pysa 核对与原始崩溃日志。沿用已有路径与二进制名称，避免重复触发此前的安全拦截。该目录可被构建清理，正式验收须另外保存固定输入清单及回执，不能只引用本机 target。

既有约 1 秒的检查时间只是单轮类型检查测量，不是 code-ana 建图性能；诊断数量不是准确率。尚未验证 Rust 内核嵌入、完整事实映射、增量、取消和长期稳定性。已有上游源码解包不完整，仅核对过所查文件；P0 必须取得完整固定版本源码。

## 3. 目标结构

```text
显式 Python 配置 + 外部准备的依赖/搜索根清单
                       |
                capture 冻结及校验
                       |
CLI --gRPC--> daemon / application
                       |
           按已启用语言调度独立 worker
                       |
               codeana-python-worker
                       |
                lang-python-adaptor
               /        |          \
       Pyrefly facade  图事实映射  完整性/诊断
               |
        固定版本 Pyrefly 内核
                       |
       Begin / Chunk(GRA1) / End / PublishAck
                       |
        私有候选图 -> 校验 -> 一次 CAS 发布
                       |
           已发布图库 -> 现有 query 服务
```

| 模块 | 职责 | 不负责 |
|---|---|---|
| config / capture | 配置、输入清单、路径和内容冻结 | 推断语义、自动安装依赖 |
| Pyrefly facade | 适配固定上游版本，提供自有类型的语义事实 | 泄漏上游内部对象到公共协议 |
| records / extract | 公共 ID、来源位置、图行、覆盖率 | 运行时真实调用猜测 |
| Python worker | 协议、预算、取消、进程内分析 | 切换 revision、修改目标工作区 |
| application / storage | 多语言协调、候选校验、原子发布 | 重做语义解析 |
| query | 查询已发布事实、解释未知与候选 | 启动 Pyrefly 或套用 Java CHA |

## 4. 实施前代码接入点与缺口

| 已检查位置 | 现状 | 实施动作 |
|---|---|---|
| [Cargo.toml](../Cargo.toml) | workspace 无 Python crate | 加入适配器、worker；先验证 MSRV、依赖冲突和许可证 |
| [config/schema.rs](../crates/config/src/schema.rs)、[config/lib.rs](../crates/config/src/lib.rs) | Languages、Effective、发现、扩展名和 capture 分支只覆盖 Java/Rust | 增加 Python 专用配置和输入类型，支持 `.py` / `.pyi`；不塞入 Java classpath 或 Cargo 配置 |
| [config/rust_inputs.rs](../crates/config/src/rust_inputs.rs)、[model/rust.rs](../crates/model/src/rust.rs) | 已有冻结、校验和 Rust 输入模型 | 复用通用校验机制，新增 Python 模型；不能照搬 Rust crate DAG 校验，Python 允许循环 import |
| [indexing.rs](../crates/application/src/indexing.rs)、[rust_indexing.rs](../crates/application/src/rust_indexing.rs) | 入口按 rust_inputs 分流；Rust 路径兼管 Java/Rust 混合任务 | 提取最小公共调度与发布生命周期，避免新增第三套双语言组合分支 |
| [worker.rs](../crates/application/src/worker.rs) | 启动参数、二进制选择、阶段日志含语言专用分支 | 显式语言选择与描述符；加入 Python worker、阶段统计和资源错误映射 |
| [worker.proto](../proto/codeana/v1/worker.proto) | Discover 有 JavaConfig / RustConfig | 增加 PythonConfig，携带冻结输入描述、哈希、大小和预算；拒绝不匹配配置 |
| [graph.proto](../proto/codeana/v1/graph.proto)、[graph_rows.rs](../crates/contracts/src/graph_rows.rs) | 已有声明、引用、调用、覆盖率等 typed rows | 逐字段核对 Python 映射；确认 language/provider/world 在每类事实中的传播 |
| [graph_store.rs](../crates/storage/src/graph_store.rs) | UnresolvedCall 插入默认 Java；SourceState / AnalysisDependency 落入 Rust 专用暂存表 | 修正未解析调用语言归属；Python 增量不得写进 Rust 状态表 |
| [compact-meta.sql](../crates/storage/src/compact-meta.sql) | 增量状态与依赖有 Rust 专用视图/表 | 增量阶段新增带语言隔离的 Python 状态，或统一成通用模型并迁移；先作 schema 设计再实现 |
| [calls.rs](../crates/query/src/calls.rs)、[dispatch.rs](../crates/query/src/dispatch.rs)、[navigation.rs](../crates/query/src/navigation.rs)、[hierarchy.rs](../crates/query/src/hierarchy.rs) | 已有调用/导航/层次与 Java 分派策略 | 按语言选择策略；Python 只使用已发布的绑定、候选和继承事实 |
| [doctor.rs](../crates/application/src/doctor.rs) | 已有语言能力及分发检测 | 展示 Python worker、后端提交、输入状态、能力限制和资源预算 |
| [package-rust.sh](../scripts/package-rust.sh)、[package-java.sh](../scripts/package-java.sh)、[justfile](../justfile) | 现有包与门禁围绕 Java/Rust | 增加 Python 制品、版本清单、安装验证与测试入口；保留已有组合 |

原计划模块如下；实际适配器由 `facade/frozen/records` 组成，worker 位于独立 workspace 的 `lang-python-adaptor/worker/`，未合并根 workspace 的 Unicode 依赖：

```text
lang-python-adaptor/src/{lib,facade,extract,records}.rs
crates/python-worker/src/main.rs
crates/model/src/python.rs
crates/config/src/python_inputs.rs
```

第一版不必创建空的增量框架。确认全量语义与事实契约后，再增加 `incremental.rs`。共享 worker 工具按实际重复部分拆分，不把 Java isolate 生命周期抽象成所有语言都必须实现的接口。

## 5. 输入与语义契约

### 5.1 冻结输入

| 内容 | 要求 |
|---|---|
| 显式开启 | `[languages.python]` 启用；未开启仍可 FILES / 文本搜索，不自动启用语义索引 |
| 环境 | 显式目标 Python 版本、平台、有序搜索根；首轮验收固定 3.12/darwin，其他组合另验 |
| 来源清单 | 明确项目源码、测试源码、辅助依赖、标准库/typeshed、`.pyi` 和 `py.typed`；分别记录角色与哈希 |
| 搜索规则 | 验证包、namespace package、相对 import、重导出、同名模块遮蔽、PEP 561 和 stub/source 优先级 |
| 环境准备 | 外部准备依赖路径；不自动运行 Python 解释器探测目标环境，不执行 editable hook；路径型 `.pth` 也须转成可审核的显式根 |
| 冻结边界 | Pyrefly 只能读取捕获的源码、配置和辅助依赖；禁用隐式上行搜索、主机环境补全和分析中的联网 |
| 超限/缺失 | 明确预算、缺失原因及影响范围；不能漏依赖后仍报告完整语义；严格模式拒绝不完整候选 |
| world | 环境、模块解释上下文与分析配置参与稳定标识；相同文件在不同根/环境下不得混用结果 |

输入清单与配置字段在 P1 定稿。不能把 `inputs_manifest` 当作尚未实现但已经可用的 Python 产品配置。Python import 图可以成环；依赖闭包预算也不能照搬 Rust 的 Cargo 层级规则。

### 5.2 图事实与准确性边界

| 事实 | 输出规则 |
|---|---|
| 声明 | 模块、类、函数/方法及现有模型支持的变量；嵌套作用域、重定义、overload 与实现不得碰撞 |
| ID | 使用 code-ana 公共身份规则；不输出 Pyrefly 内部编号、内存地址或暂存目录路径 |
| 来源 | 映射回原始文件；验证 Python 编码声明、Unicode、多行节点、字节偏移与现有列坐标约定；不直接照搬 LSP 坐标 |
| 引用/导入 | 区分 import 绑定、alias、重导出、源码目标与外部 stub 目标；外部辅助文件不冒充项目源码 |
| 调用 | 保存调用点、已解析目标、多候选和未知原因；静态绑定不宣称是唯一运行时调用目标 |
| 类型/继承 | 记录可证实的类型及继承关系；Protocol、union、MRO、override 按能力声明支持程度 |
| 动态场景 | Any、getattr、猴子补丁、动态 import、装饰器替换等不确定目标保留未解析/部分解析事实，不按同名函数猜边 |
| 完整性 | 区分类型诊断、语法失败、模块加载失败和事实提取缺失；类型错误数量本身不能决定是否可发布 |
| 来源追踪 | 所有适用记录正确标记 `python` / `python-core` / 后端版本与配置指纹 |

Glean / Pysa 分别用于开发时核对。生产路径直接构造 typed rows，不通过约百 MB 的整库 JSON 报告中转；也不把 Pysa 私有格式当长期协议。

## 6. 分阶段实施与退出条件

| 阶段 | 实施内容 | 交付与退出条件 |
|---|---|---|
| P0：内核可行性 | 获取完整固定源码，核对提交与许可证；验证 workspace 构建；封装 Query/类型/调用接口；核查私有 API 缺口 | Rust 小型原型直接提取声明、引用、调用位置、多个目标、未知原因；工厂返回与高阶函数 fixture 通过；记录所需上游补丁。缺关键事实则停在此阶段说明阻塞 |
| P1：契约和冻结 | Python 输入模型、配置、capture、预算、world/ID；确定 PythonConfig 与图行映射 | 可重放的冻结输入；目标文件变更/越界/哈希不符被拒绝；源码和辅助输入隔离；协议及存储变更清单经过核对 |
| P2：单语言全量 | Python worker、GRA1 流、语义映射、未解析调用语言修正、查询策略、doctor | Python fixture 完成 index→query；取消/超限/崩溃不切 head；语义缺失可解释；不支持的混合配置明确拒绝 |
| P3：多语言协调 | 提取公共 attempt 生命周期；支持 Java/Python、Rust/Python、三语言；保留 Java 生命周期与现有发布约束 | 各 worker 成功后仅一次提交；任一失败/取消不发布半份图；Java/Rust 原有路径及混合查询回归通过 |
| P4：保守增量 | 先支持输入完全不变时复用；任何 Python 源码/环境/依赖变更先全量重算；再评估模块依赖级重算 | 全量与增量图等价；明确报告全量重算原因；删除、空输出、缺失 import 后补齐、循环 import 均无陈旧事实 |
| P5：分发和真实验收 | 固定 worker 制品、依赖与许可证清单；文档、隔离安装、真实项目图核对及一轮测量 | 所有声明支持的组合通过；原失败回执保留；报告图完整性、资源及不支持项，不拿类型检查耗时代替索引耗时 |

依赖顺序：`P0 → P1 → P2 → P3 → P4 → P5`。P2 是首个可评审的单语言里程碑，P5 才是本计划范围的完成门禁。源码读取封闭性、核心事实不足或构建不可维护，均须在进入产品接入前暴露。

### 增量特别约束

- Python 函数体修改可能改变推断返回类型，继而影响调用方；首版不能把“只改函数体”当作不传播变化的依据。
- 后续细粒度失效需记录成功/失败的模块解析、符号查询与分析依赖，处理循环依赖；不能只追踪已有调用边。
- 替换集合覆盖同一物理文件的全部受影响 world，以及已删除、零事实输出的 owner。
- 若统一 Rust/Python 增量存储，必须迁移 Rust 读写、schema 生成器、旧 revision 兼容与测试；不能仅改表名。

## 7. 验收矩阵

| 类别 | 必测输入 | 判定 |
|---|---|---|
| 基础语义 | 直接调用、alias、嵌套作用域、继承、classmethod/staticmethod、重导出 | 声明/引用/目标与人工冻结预期一致 |
| Pyrefly 优势场景 | 无注解工厂、高阶返回、union、dataclass、Protocol | 目标集合与未知边界正确；不只测试 hover 文本 |
| 动态边界 | Any、getattr、装饰器、猴子补丁、动态 import、反射 | 不制造确定边；coverage 与原因可查询 |
| 模块解析 | src 布局、多根、namespace、循环 import、TYPE_CHECKING、stub/source、缺失依赖 | 与固定环境的解析预期一致；根顺序变化使缓存失效 |
| 来源和身份 | Unicode、编码声明、重复名字、同文件多 world、删除后重建 | 坐标正确、ID 符合公共规则、无跨 world 串联 |
| 冻结边界 | 修改输入、symlink 逃逸、配置上行搜索、含执行陷阱的 import/.pth | 不读取未捕获输入、不执行目标代码；失败有确定错误 |
| 完整性 | 语法错误、类型错误、缺失模块、部分提取 | 严格模式拒绝不完整候选；非严格发布遵循现行契约并可见缺失 |
| 生命周期 | Begin 后崩溃、错误 End、超限、取消、发布竞争、Ack 异常 | 沿用现有发布状态机；提交前失败不切 head，提交后的 Ack 异常不伪装回滚 |
| 多语言 | 三个单语言、两两组合、三语言；某一 worker 失败 | 无串行专用路径遗漏、无半图发布、无跨语言错误分派 |
| 增量 | 修改被调用方返回值、移除文件、新增原先缺失模块、修改 stub/搜索根 | 与同输入全量索引的规范化图一致 |
| 分发 | 独立 home、完整 release 目录、版本不匹配、缺 Python worker | doctor 明确诊断；不借开发机 target 或现成环境通过 |

验证顺序：先跑相关 Java fixture 保住现有语义契约，再跑 Python fixture；共享 Rust 代码变更运行 `just test-rust`，按影响范围补 Java/Native 回归。新增 Python 门禁命令在实现时加入 justfile，不把计划中的命令当作现有能力。仅修改本文无需运行编译与语义回归。

真实项目继续使用已冻结的 diorama-sdd 提交与 615 文件范围，保留当前缺依赖场景；另准备完整且可重放的目标版本依赖环境，分开出具回执。核对至少覆盖既有 21 个调用、4 个额外导航与 9 个探针，补充反例与未知目标，统计解析率时明确分母和人工样本范围。

正式性能仅在功能通过后做一轮：固定输入、空应用状态、固定预算，记录 capture/分析/交付/入库/发布耗时、峰值进程树内存、事实数量、产物大小和所有失败原因。不得将 CLI 类型检查、Pysa 导出或旧 Java 回执写成 Python 产品性能。

## 8. 协议、文档与完成清单

| 当前基线 | 变更要求 |
|---|---|
| worker 20、graph model 3 | PythonConfig、语言字段或事实含义变化按兼容策略升级；所有 worker 与生成代码同步，旧组合明确拒绝 |
| daemon 3 | 仅公共 RPC/返回契约变化才评估升级，不因新增后端机械递增 |
| 物理 schema 9 / 逻辑 schema 33 | 按实际表/列/语义变更升级，修改源 DDL 与生成器后生成；旧格式按现有全量重建规则处理 |
| Java request/result 10、C ABI 4 | Python 是 Rust worker 接入，不因新增 Python 修改 Java ABI/专有协议 |

实施中同步 [架构](../docs/architecture.md)、[契约](../docs/contracts.md)、[索引模块](../docs/modules/indexing.md)、[查询模块](../docs/modules/query.md)、[当前实现](../docs/current-state.md)、[文档导航](../docs/README.md)，新增 Python 模块说明。能力、配置、预算、失败行为与 release 清单必须一致。

- [x] P0 完整源码可构建，内核事实接口和补丁范围有证据。
- [x] 冻结输入、类型化图交付、查询和完整性契约落地。
- [x] 全量与保守增量可重放，多语言发布不破坏原有行为。
- [x] fixture、取消/失败、隔离分发及冻结真实项目范围验收通过。
- [x] 文档、协议版本、schema、许可证与制品清单同步。
- [x] 最新回执记录明确未支持项；不把研究样本成功写成完整 Python 支持。

## 9. 上游参考

| 固定来源 | 用途 |
|---|---|
| [Pyrefly lib.rs](https://github.com/facebook/pyrefly/blob/feeee254e65844f23f973a0a29cae02d09931068/pyrefly/lib/lib.rs) | Rust API 暴露范围与稳定性边界 |
| [Pyrefly query.rs](https://github.com/facebook/pyrefly/blob/feeee254e65844f23f973a0a29cae02d09931068/pyrefly/lib/query.rs) | Query、callee 与位置接口；P0 核查入口 |
| [Pysa call_graph.rs](https://github.com/facebook/pyrefly/blob/feeee254e65844f23f973a0a29cae02d09931068/pyrefly/lib/report/pysa/call_graph.rs) | 已解析、部分解析、未知与 override 候选的语义参考 |
| [Pyrefly 架构](https://github.com/facebook/pyrefly/blob/feeee254e65844f23f973a0a29cae02d09931068/ARCHITECTURE.md) | 模块分析与内部状态设计 |

上述接口与报告实现是固定版本依据，不是稳定 SDK 承诺。后续升级必须重新跑语义与输入封闭性回归，并更新后端指纹。
