# Rust 类型与职责改造

对齐 2026-10-14。改造针对 Rust 产品代码；协议与公开图身份不因内部 Rust 类型变化升级。实际验证记录与未完成范围见 [当前实现](../current-state.md)。

```text
CLI / protobuf / Java JSON / SQLite
                 │ 显式映射、边界校验
                 ▼
Request DTO → TaskCommand → IndexCommand / QueryRequest
                 │
        窄读接口 / 索引阶段 / 用户内容服务
                 │
     Record / FactAttributes / DomainError / 类型化结果
                 ▼
        持久化编码 / codeana-text-v1 renderer
```

## 已落实的边界

| 层 | 实现 |
|---|---|
| model | 操作、方向、端点、算法、分析世界、任务状态、错误码、分析模式和阶段记录族使用枚举；身份 newtype 保留字符串编码 |
| 请求 | `Request` 留在接入、配置捕获与持久化边界；调度使用 `TaskCommand`，索引只接收 `IndexCommand`，查询核心只接收 `QueryRequest` |
| contracts | 领域/protobuf 映射逐字段实现，新增字段由编译器检查；不经通用 JSON 中转。Java engine request、bundle、graph descriptor 有明确 DTO |
| 查询 | `Record` 明确区分事实、调用、派发、路径、源码、文本、聚合；pipeline 通过枚举端点取输入，不读取 JSON 键，不复制上阶段完整记录集 |
| 属性 | `FactAttributes` 提供修饰符、接收者类型、源码来源、注解等稳定字段；`Field<T>` 区分缺失、null 和具体值；未知 producer 数据由 `OpaqueMetadata` 透传 |
| 读能力 | `SourceContext`、`GraphAccess`、`SourceLocator`、`AnnotationReader`、`OverviewReader`、`DependencyReader` 分离；扫描方法必须显式实现，生产使用有界 SQL。FILES 仅实现源码上下文 |
| 应用 | 索引分为 setup、analysis、publication、post-processing；阶段结构持有 worker/candidate，已发布结果与后处理分离。任务、发布通过窄端口接入 |
| 运维 | 报告、diff、doctor、标注绑定分别使用结构化参数和结果；不根据 JSON 字段推断业务状态 |
| 存储 | blob、task、object、annotation、maintenance、page、graph 分模块；保持共享协调状态、读者引用、候选封存和 CAS 事务 |
| worker | FFI 整数状态在边界解码为 `RunState`；JVM 重定位修改 DTO 路径字段；Maven export 构造 `JavaInputs`，计时记录使用明确结构 |

## 编码与兼容

| 对象 | 约束 |
|---|---|
| 协议与图库 | daemon 3、worker 18、graph model 2、analysis 9、C ABI 4、物理 schema 7、逻辑 schema 33 保持不变 |
| 文本输出 | 继续 `codeana-text-v1`；renderer 输出记录的公共形状，不输出内部枚举标签 |
| 冻结页与任务 | 新记录持久化带 `record_type`；旧无标签记录作为已呈现数据读取，保持重放/续页，不能作为新的 pipeline 语义输入 |
| 图身份与 profile | 公开 ID、hash/ordinal、输入摘要与 profile 编码保持；既有 profile 命名盐保留，不把旧盐名当作现行协议版本 |
| 内部整数键 | 查询投影不输出 `site_pk`、`owner_pk` 等物理列；公开 ID 不变 |
| 用户内容 | 标注正文、版本、锚点与 snapshot 保留；图重建不删除用户内容 |
| 错误 | 错误码独立于上下文/文案；类型化 cause 穿过读取和取消边界；外部 producer 代码显式保留。故障注入文本保持原有拼写 |
| 诊断副本 | sealed descriptor 复制当前 `graph_archive` / GRA1 到 `graph.rows`，不再访问旧 CAF 字段 |

## 动态数据允许出现的位置

JSON 仍用于传输/持久化编解码、公共文本呈现、原始协议验证和 Rust 不解释的 producer 扩展。`Record::Display` 是已呈现的运维或历史数据，没有语义端点访问能力。语言模型的原始 wire arena 继续有界验证；类型图遍历使用节点句柄和封闭的单边/多边枚举，不展开循环。

索引、缓存、分组及按名称组织的集合继续使用 Map；它们不是稳定实体字段的替代品。不得通过 `wire_map` 或 `Record::wire` 把核心业务重新改成按 JSON 键取字段。

## 验证

| 门禁 | 本次结果（2026-10-14，macOS ARM64） |
|---|---|
| `just test-rust` | 通过：DDL、fmt、workspace、图 13 项、文本 2 项、标注 4 项、clippy |
| `just test-java` | 通过：Java fixture、桥与模型 |
| Native | G1 开发构建与安装通过；manifest 5 项、ABI 生命周期 8 项、图 6 项、环境检查及真实 SIGSEGV 恢复通过 |
| JVM 产品 gate | 显式 JVM 图产品 5 项通过，无预期项漂移 |
| Rust release | checksum 通过；包内图测试 13 项中执行 8 项、跳过 5 项故障注入；manifest 版本从现行 Rust 常量生成，值为 3/18/2 |
| Java release | release workspace 构建、checksum、隔离安装运行与原 Cargo 路径恢复通过 |

Native 门禁首次安装因同时运行的 JVM 验证触发 `STOP_RUNTIME_BEFORE_NATIVE_INSTALL:97923`，未绕过保护；等待 JVM 退出后继续执行安装及剩余门禁。Rust 图门禁首次发现故障注入文本多了空格，恢复原有文本后完整重跑通过。本机汇总记录在 `target/rust-types-refactor/validation.json`，Java release 回执在 `target/java-alignment/evidence/release-package-20261014-115048-15443/receipt.json`；target 产物不保证在其他 checkout 存在。

新增回归覆盖 wire 枚举拒绝、上下文包装后的取消身份、属性缺失/null/扩展透传、类型化端点与旧页读取、内部主键隐藏。已有测试继续覆盖 full/delta、冻结历史、分页、CAS、取消、重启和发布后失败。开发构建、fixture、mock 与分发回归不表示完整语义、跨平台或容量/稳定性能正式放行；本次不重测冻结真实项目。
