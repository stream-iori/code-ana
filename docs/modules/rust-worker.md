# Rust worker

**syntax 与 semantic 是两种明确能力；不能用语法结果冒充完成名称解析。**

状态：rust-analyzer syntax/HIR 路线继承提案；版本、集成 API、首批语义能力在后续 Rust 阶段验证；当前 Java fixture 优先。关联 D06/D07/D09/D11。

## 1. 能力与模块

| 模式 / 组件 | 输入 | 输出和边界 |
|---|---|---|
| rust-syntax | 捕获的 Rust 文件 | 声明、范围、属性、模块语法和未绑定出现；模块文件发现缺口须披露 |
| rust-semantic | crate graph、toolchain、依赖、target/features/cfg | 已解析目标、类型/trait 关系、宏来源及各项能力状态 |
| ProjectDiscovery | manifest、lockfile、已有构建信息 | crate/world 清单；无法确定的条件不假定成立 |
| AnalyzerFacade | 锁定版本的 rust-analyzer API | 隔离依赖升级，向公共 IR 转换 |
| MacroProvenance | 展开结果与输入来源 | expansion 链、原始源码锚点、生成文本标签 |
| CapabilityReporter | 缺依赖、未展开宏、cfg 不确定 | 按 world/owner 的 coverage 与原因码 |

rust-analyzer 的具体可嵌入 API 与兼容版本尚未验证；本设计不假设任意最新版本均可直接集成。

配置入口为 `languages.rust`、Rust modules 与 `modules.worlds`，见 [多语言配置](../configuration.md)。mode 显式区分 syntax/semantic；target/features/default_features/cfg 是 world 请求，实际 crate graph 解析后的条件进入 BuildContext。worker 消费冻结的 RustAnalysisConfig，不自行加载 TOML；请求 semantic 时不能静默改成 syntax。

## 2. world 与身份

```text
src/lib.rs 的同一声明
     |
     +-- crate A / target X / feature foo -> SymbolKey(world_1)
     +-- crate A / target X / feature bar -> SymbolKey(world_2)
     +-- crate A / target Y / feature foo -> SymbolKey(world_3)
```

| 情况 | 规则 |
|---|---|
| 同文件多 crate/target | 每个 world 独立事实，查询显式选择或逐 world 返回 |
| features/cfg 改变 | 建立对应 world；不能把互斥实现简单合并成同时成立 |
| 依赖内容/工具链改变 | profile 和输入身份失效；LogicalSymbolKey 不机械包含整个依赖 hash |
| 泛型 | 保存源码级语义与约束，首版不枚举所有单态化实例 |
| trait object | 查询返回静态可能目标，附开放世界、缺类型和预算限制 |

Rust 语义查询策略属于 Rust query 模块，读取 worker 已发布的 trait、impl、类型与绑定事实。缺少足够信息时返回能力缺口，不在查询期间隐式重启分析器。

## 3. 构建和宏

| 场景 | 行为 |
|---|---|
| 已有可验证 crate graph/产物 | 记录摘要并分析 |
| 需要 build.rs / 过程宏执行 | 返回供 Agent/人类执行的建议，不由产品自动运行；后续请求仅消费可验证的预生成结果 |
| 缺少可用生成结果 / 外部执行失败 | 保留可用事实并披露 semantic 缺口；不能为补齐能力私自执行代码 |
| 宏生成声明 | 保留 expansion 与原始调用来源；生成文本不标成真实源文件 |
| 无可读生成文本 | source 返回原始输入及限制，不捏造完整宏展开代码 |

构建建议与 Java/Maven 使用同一 BuildAdvice 契约。过程宏若必须运行才能展开且无可验证产物，明确披露缺口；进程隔离不改变“产品不自动执行目标项目构建代码”的约束。

## 4. 后续 Rust 验证集

| 夹具 | 验证目标 |
|---|---|
| 两 crate workspace，跨 crate 函数调用 | module/world/外部符号与 IR 能表达真实目标 |
| 同文件两个 feature 组合 | 不串 world，不错误去重 |
| trait 静态调用和 trait object 调用 | resolved 与 possible 清楚区分 |
| 声明宏生成函数、未展开过程宏 | 来源链、源码入口与缺口可见 |
| 新增声明使原未解析引用可解析 | 失败查找依赖能驱动增量失效 |
| 缺依赖、禁用构建脚本 | syntax 可用，semantic 不虚报完整 |

P0/P1 优先 Java fixture，并保留 language/world 的公共扩展边界；这些 Rust 用例在后续阶段验证，不阻塞 Java 闭环。P3 只交付明确列出的 Rust 能力，不以原型完成代表全语言支持。
