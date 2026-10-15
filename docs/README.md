# code-ana 文档导航

先读 [当前实现与验证边界](current-state.md)，再查架构、契约和模块。以下现行指南对齐 2026-10-14 代码；过期方案、旧协议测试和无效入口已删除；本机测量回执按其产物解释。

```text
当前实现 → 架构 / 契约 → 配置 / 模块 → 验证与分发
```

| 现行指南 | 内容 |
|---|---|
| [当前实现](current-state.md) | 版本、G1、紧凑存储、最新单轮结果和未完成范围 |
| [总体架构](architecture.md) / [公共契约](contracts.md) | 进程、身份、GRA1、取消和发布边界 |
| [配置](configuration.md) | 显式语言、外部 Maven metadata、预算和优先级 |
| [Java fixture](examples/java-fixture-config.toml) / [用户配置](examples/user-config.toml) | 目标 Java 8 与本机工具链；使用前替换真实路径 |
| [完整配置 schema 示例](config.toml) | 混合语言请求形状；Rust 需外部导出的冻结输入 |
| [CLI / daemon](modules/runtime.md) / [索引](modules/indexing.md) | 任务、调度、冻结输入和增量 |
| [Java worker](modules/java-worker.md) / [Rust worker](modules/rust-worker.md) | Java 与 Rust 分析链路、能力限制 |
| [存储](modules/storage.md) / [查询](modules/query.md) | 物理 schema 9、逻辑 schema 33、只读投影和 pipeline |
| [版本与源码](modules/versioning.md) / [诊断与标注](modules/operations.md) | 冻结历史、diff、引用保护和用户内容 |
| [决策登记](design-review.md) / [实施与验收](delivery.md) | 已落实决策、剩余门禁与实际测试入口 |
| [Rust 回归](rust-first.md) / [Java 分发](java-package.md) | 无 JDK 的 mock 回归与真实 Java 包运行 |

## 维护约定

| 规则 | 做法 |
|---|---|
| 现行文档 | 改代码时同步版本、能力、命令和失败处理；代码常量/DDL/.proto 为依据 |
| 内容归属 | 设计约定归架构/契约/模块，已执行验证与测量归当前实现；改造过程由 Git 历史保留，不另建实施记录目录 |
| 清理 | 过期方案与测试直接删除；共享工具先拆出，修正门禁/导入/打包/覆盖映射；测量不重标产物 |
| 参考仓库 | 记录固定 commit、文件 SHA 和改动；同级 Anatomist 链接是研究线索，不能当作当前分支快照 |
| 性能 | Native 不分冷热；本次只测一轮，写清输入、缓存状态、产物与预算 |
| 验收 | 区分开发构建、fixture、真实项目测量和正式放行；不把未完成项写成通过 |

开发约定见 [AGENTS.md](../AGENTS.md)，常用命令以 [justfile](../justfile) 为准。
