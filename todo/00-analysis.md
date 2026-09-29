# 现状、缺口与实施判断

**本页记录当前证据与剩余缺口；Rust 进展见 [07-rust-first.md](07-rust-first.md)。真实 Java 语义/原生风险尚未由 mock 验收解除。**

## 1. 代码核对

| 能力 | 实际证据 | 实施含义 |
|---|---|---|
| gRPC | [hello.proto](../proto/codeana/v1/hello.proto)、[daemon](../crates/daemon/src/main.rs)、[CLI](../crates/cli/src/main.rs) 已有 SayHello 与 TaskService | 握手、幂等、事件流、取消和状态持久化已有真实进程测试 |
| daemon 发现 | 动态 loopback endpoint、私有 home、token、flock | 已实现自动启动、drain/stop/restart；CODEANA_PORT 仅保留 Hello 测试入口 |
| Java host | [main.rs](../crates/java-worker/src/main.rs) 动态装载库，手写 3 个函数类型，创建/销毁 isolate | 已有进程内 FFI 入口；没有 daemon→host IPC、ABI manifest、分析会话或取消 |
| Java 分析器 | [HelloBridge](../lang-adaptor/src/main/java/dev/codeana/bridge/HelloBridge.java) 返回整数 25 | 没有 javac、独立 Java 模型、ASM 或框架分析实现 |
| 原生构建 | [pom.xml](../lang-adaptor/pom.xml) 只声明 nativeimage SDK；[脚本](../scripts/build-java-native.sh) classpath 只有 `target/classes` | 引入依赖后必须补完整 classpath、资源/反射配置和成套产物记录 |
| 工具链 | `.sdkmanrc` 为 `25.3.4+1.r25-graalce`，SDK 依赖为 `25.0.3` | 字符串不同不能证明不兼容；P0 需记录实际 JDK/native-image/header 与 SDK 组合并验证 |
| 平台 | [smoke.sh](../scripts/smoke.sh) 与 [justfile](../justfile) 固定 `.dylib` | 首批 macOS 与现状一致；尚不能宣称 Linux 可用 |
| 存储/配置/查询 | 已新增 model/config/application/query/storage/mock-worker | Rust 主链路、SQL 发布、固定查询与历史已有 fixture 测试 |
| 运行证据 | Rust 单元/文档测试、真实 CLI 回归、release 打包、两条 Hello smoke | Java 语义仍为 mock，不能替代真实 javac/native 验收 |

2026-09-29 修订：用户确定 javac 为唯一后端，不考虑 JavaParser。Java 复用基线更新为 `dev/replace-parser@7c66251479cfc7556451be35cfbc76e94454425b` 已提交快照，未重跑其测试。该分支已删除旧 AST 链路；旧的 parser/SymbolSolver 复用调查不再作为任务。

| 新风险 | 先验证什么 |
|---|---|
| D13 原生库与目标 JDK JVM 多一层进程 | P0-04a 验证桥启动、解码、取消、退出，不能拿参考 native executable 或 Hello 代替 |
| 泛型/绑定/诊断被公共 IR 简化 | P0-02 单列独立 Java 模型，区分选中声明、局部证据与恢复来源 |
| 小 delta 缺完整编译上下文 | 编译器语义输入、发布源码、owner 替换分开；P0/P1 先足够上下文 |
| 误搬产品行为 | 不引入参考项目 Maven 启动、Lombok compile 开关、双后端兼容或真实项目容量门禁 |

## 2. 从不变量确定先后顺序

```text
可信查询
  = 可追溯输入 + 可解释事实 + 固定版本 + 完整性证据

因此先做：
  捕获/身份 -> Fact IR/owner -> 校验/原子发布 -> 固定 ReadContext
  再接：真实分析 -> 框架 -> 增量 -> 更多查询/历史
```

| 不变量 | 先做什么 | 放晚了会出现什么 |
|---|---|---|
| I01 输入可追溯 | 捕获源码、依赖、工具链身份，普通 source 检查 hash | 分析和返回的源码不是同一份 |
| I02/I03 候选完整且原子发布 | ResultEnd、owner 校验、门禁、catalog 提交裁决 | 半张图或缺失 coverage 变成活动版本 |
| I04 查询固定版本 | reader 引用与 ReadContext，pipeline 共用一次选择 | 前一阶段与后一阶段读取不同事实 |
| I05 增量等价 | 保留失败查找依赖，full/delta 多重集比较 | 新增声明后漏更新，删除后残留事实 |
| I06 证据分轴 | enumeration / semantic / limits 从首个查询实现 | 空结果被错误解释为不存在 |
| I07 调用点为事实源 | 每次调用保留 occurrence，calls 只是投影 | 重复调用丢失，source/diff 无法回溯 |
| I08～I10 保全 | 失败保旧版、统一引用机制、独立人工正文 | 恢复/GC/重建损坏已发布或用户数据 |

## 3. 对设计排期的细化

| 文档安排或空缺 | 本方案处理 | 原因 |
|---|---|---|
| delivery 把 source hash、能力预检、预算完善列在 P2 | P1 首个对外能力即满足基本约束，P2 扩展范围与故障组合 | D15 和 I06 是行为契约，不能阶段性失真 |
| D06 在 P0 评审，dispatch 产品操作在 P2 | P0 用复杂继承/重载/接口用例回放验证所需事实；P2 再交付完整命令 | 避免做到查询时才发现 worker 漏输出信息 |
| P0 验证冷基线，P1 冷增量，P2 增量完善 | P0 单个代表用例；P1 已声明 Java/框架范围等价；P2 扩环境、重规划、热复用和故障组合 | 明确各阶段深度，避免重复计“完成” |
| 恢复列在 P2 | P0 验证提交窗口，P1 最小启动恢复，P2 完整 GC/升级/运维 | 一旦有持久发布，就必须能判定是否成功 |
| P1 独立标注存储、P2 标注产品功能 | P1 建立独立持久边界和版本接口；P2 实现编辑、绑定和用户操作 | 不让后续用户数据进入可删除的事实库 |
| 配置 defaults 多数待冻结 | 设 P0 决策记录与参数表，超限行为先定、容量数值实测 | 示例数值和“未设置”均不能默认为无限资源 |
| 已有构建产物“可验证”缺少具体格式 | P0 定输入/选项/工具链/产物摘要与可信程度；P1 校验并捕获 | 文件存在或 mtime 不能证明对应当前源码 |

这些是实施顺序和任务细化。原有设计仍是依据；新增默认建议见[决策表](06-decisions.md)，尚未通过原型的选择不标成已确认。

## 4. Java 复用审计边界

| 本轮能确认的证据 | 仍需在 P0 审计 |
|---|---|
| 固定参考提交的 JavaUnit/JavaModel 与框架视图脱离编译器对象 | 模型/集合复制成本、上下文释放、跨任务缓存身份、进程树峰值 RSS |
| `StagedGraphStore` 以索引路径构造，设计明确其暂存中混有语义绑定 | 逐个方法划分 Java 求解、通用校验和 SQL 操作，迁移后逐条回放 |
| 固定参考提交的 javac 运行于目标 JDK 子进程，ASM 仍用于二进制分析 | 原生库启动桥的可行性、Java 8 字节码桥兼容、模型/协议、传递依赖与许可证 |

本轮只作定点源码核对，未做全量依赖审计。不得直接打包整个 Anatomist core，也不以其当前输出充当本项目正确性答案。

## 5. 模块实施边界

| 拟建位置 | 负责 | 不应混入的职责 |
|---|---|---|
| `crates/model` | 领域身份、事实、owner、证据与状态类型 | SQL、gRPC、Java parser |
| `crates/contracts` + `proto/` | wire schema、领域映射、版本协商 | 语义摘要算法依赖 protobuf 字节 |
| `crates/config` | schema、覆盖、来源、规范化和冻结 | 直接执行目标构建 |
| `crates/application` | runtime/indexing/versions/operations 用例与端口 | Java 名称解析、SQLite 细节 |
| `crates/storage` | SQLite、blob、引用/发布/恢复实现 | Java 派发与质量策略 |
| `crates/query` | selector、定向读取、语言策略、pipeline/输出 | 自动启动分析补事实 |
| `crates/daemon` / `cli` | 装配与服务入口 / 参数与输出 | 各自复制业务状态机 |
| `crates/java-worker` | 独立进程、IPC/ABI 转换、isolate/线程/缓冲 | 直接发布图或读取用户 TOML |
| `lang-adaptor/*` | Java 分析、框架、失效闭包、native bridge | CLI、JDBC 发布、Rust 查询逻辑 |

先建当前切片实际需要的模块；不为每个概念提前创建空 crate。窄端口由使用方需要驱动，SQLite 与 worker 实现通过装配接入。
