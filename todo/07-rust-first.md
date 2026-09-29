# Rust 优先实施与验收

**本轮先完成可由 mock 验证的 Rust 职责；diff 保持现状，暂不开发。Java-worker 继续使用独立 mock，不开展 P3 Rust 源码分析。**

原 P0/P1/P2 文档继续约束真实 Java 接入。本页记录 Rust 交付，不把 mock 结果计为 javac/native 通过。

| 阶段 | Rust 产出 | 状态 / 证据入口 |
|---|---|---|
| R0 | model、task/worker proto、owner 与完整交付校验 | 已实现；worker 协议 2 增加结构化 BuildAdvice，Fact IR 仍为 1 |
| R1 | 配置、显式捕获、files/text 独立入口 | 已实现；无语言的 files/text、text_only 独立文本搜索有端到端验证；auto 发现明确拒绝索引 |
| R2 | gRPC、任务、mock 监督、取消与调度 | 已实现基础链路；每项目一项分析、同目标写入串行，捕获/查询/发布移出接入线程；RSS 仍为采样限制 |
| R3 | candidate、backup delta、CAS、固定读者、迁移/恢复 | 已实现；catalog/annotation schema 事务升级、GC pending 恢复及旧标注保全有存储测试 |
| R4 | 查询、pipeline、证据、文本、预算和分页 | 已实现 fixture 范围；调用/声明邻接分批读取，遍历和输出有界；大图性能尚未验收 |
| R5 | 关系/派发/遍历、files/analysis/Git snapshot | 已实现已有事实的静态查询、无语言 FILES 与配置冻结；diff 此轮不改 |
| R6 | doctor/report、人工标注、GC 与恢复 | 已实现能力/来源报告、诊断汇总、off/basic/full、版本化锚点及绑定、GC pending；标注进入 typed pipeline 尚未实现 |
| R7 | fixture、真实进程回归、演示与 Rust mock 分发 | 14 个独立端到端场景覆盖查询、报告、队列、重订阅、并发发布和故障恢复；另有人工预期、并发 GC 单测和 release 包门禁；发布包只含 mock，不是 Java native 产品包 |

```text
Rust 验收：CLI → daemon → mock process → 实际 SQLite → 查询/历史
Java 验收：同一 worker 协议 → Rust host → native Java → javac bridge
            第二条未通过，不能用第一条代替
```

## 后续门禁

- [ ] 真实 javac/native producer 接入；D17 可行性、UTF-16→UTF-8、绑定与多模块/版本语义验收。
- [ ] Maven/Gradle 只读 BuildContext 与目标 JDK/依赖内容验证；不执行目标构建。
- [ ] Java 语义增量失效闭包、Lombok/框架 producer 所有权与冷基线等价。
- [x] GC keep/年龄/图文件预算、事件/报告过期与保护引用；catalog/annotation 迁移和待清理恢复。
- [ ] 标注参与 typed pipeline、持续查询中 SQLite 语句级中断、大图容量与发布成本测量；RSS 采样不是硬内存隔离。
- [ ] diff 扩展、比较落盘与相应验收；用户要求暂缓，本轮仅保留旧命令回归。
- [ ] Linux 与完整 Java native 成套分发。

操作方法、当前限制和测试命令见 [Rust 实现说明](../docs/rust-first.md)。原 P0/P1/P2 中包含上述 Java 门禁的复合任务保持未完成，不整体勾选。
