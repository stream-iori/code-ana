# code-ana 文档导航

先读 [当前实现与验证边界](current-state.md)，再查架构、契约和模块。以下现行指南对齐 2026-10-13 代码；历史记录保留原提交、指标和失败，不作为当前操作说明。

```text
当前实现 → 架构 / 契约 → 配置 / 模块 → 验证与分发
                              ↑
                  历史方案与回执：用于追溯
```

| 现行指南 | 内容 |
|---|---|
| [当前实现](current-state.md) | 版本、G1、紧凑存储、最新单轮结果和未完成范围 |
| [总体架构](architecture.md) / [公共契约](contracts.md) | 进程、身份、GRA1、取消和发布边界 |
| [配置](configuration.md) | 显式语言、外部 Maven metadata、预算和优先级 |
| [Java fixture](examples/java-fixture-config.toml) / [用户配置](examples/user-config.toml) | 目标 Java 8 与本机工具链；使用前替换真实路径 |
| [完整配置 schema 示例](config.toml) | 混合语言请求形状；Rust 分析尚未实现 |
| [CLI / daemon](modules/runtime.md) / [索引](modules/indexing.md) | 任务、调度、冻结输入和增量 |
| [Java worker](modules/java-worker.md) / [Rust worker 设计](modules/rust-worker.md) | 已实现 Java 链路与后续 Rust 能力 |
| [存储](modules/storage.md) / [查询](modules/query.md) | 物理 schema 7、逻辑 schema 33、只读投影和 pipeline |
| [版本与源码](modules/versioning.md) / [诊断与标注](modules/operations.md) | 冻结历史、diff、引用保护和用户内容 |
| [决策登记](design-review.md) / [实施与验收](delivery.md) | 已落实决策、剩余门禁与实际测试入口 |
| [Rust 回归](rust-first.md) / [Java 分发](java-package.md) | 无 JDK 的 mock 回归与真实 Java 包运行 |
| [存储实施记录](implementation/anatomist-storage-migration.md) | GRA1 与紧凑布局的实现、单轮测量、原失败及修复回执 |

## 历史设计与证据

| 文档 | 用途 |
|---|---|
| [旧公共契约](history/contracts-fact-ir.md) | Fact IR / CAF1 与旧 worker/ABI 的逐轮演进 |
| [Java 旧实现](java-implementation.md) / [最初验证](implementation/java-validation.md) | 已删除 lang-adaptor 的历史实现与回执 |
| [Java 迁移](java-anatomist-migration.md) / [对齐执行](implementation/java-alignment-execution.md) | 各版本能力、差分和迁移门禁记录 |
| [复用审计](implementation/java-reuse.md) / [能力审计](implementation/java-anatomist-alignment.md) / [重写](implementation/java-adaptor-rewrite.md) | 借用来源和阶段性缺口；清单保留各自冻结提交 |
| [早期存储对照](storage-anatomist-migration.md) | 紧凑图迁移前的 Rust 存储设计 |
| [SIGKILL 排查](java-sigkill-diagnosis.md) / [性能诊断](java-performance-diagnosis.md) | 特定旧产物的诊断，不能推断当前状态 |
| [Native 性能分析](implementation/native-performance-analysis.md) / [改造记录](implementation/native-performance-upgrade.md) / [Anatomist 对照](implementation/performance-anatomist-comparison.md) | CAF1 阶段瓶颈与优化；最新结果读存储实施记录 |

## 维护约定

| 规则 | 做法 |
|---|---|
| 现行文档 | 改代码时同步版本、能力、命令和失败处理；代码常量/DDL/.proto 为依据 |
| 历史文档 | 在入口标明历史口径；原失败回执不能改成成功，原测量不能重标新产物 |
| 参考仓库 | 记录固定 commit、文件 SHA 和改动；同级 Anatomist 链接是研究线索，不能当作当前分支快照 |
| 性能 | Native 不分冷热；本次只测一轮，写清输入、缓存状态、产物与预算 |
| 验收 | 区分开发构建、fixture、真实项目测量和正式放行；不把未完成项写成通过 |

开发约定见 [AGENTS.md](../AGENTS.md)，常用命令以 [justfile](../justfile) 为准。
