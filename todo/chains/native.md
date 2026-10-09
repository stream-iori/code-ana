# Native：共用 bridge → C ABI → .dylib → 等价验证

[入口](../README.md) · [共用契约](../contracts.md) · [追溯](../traceability.md)

**ACC-03 的 JVM 全链路及冻结 iopengw 门禁通过后，才构建和验证 native。前期可整理接口清单。**

```text
已通过的同一解析源码 / java-bridge
  → native-bridge C entry points
  → GraalVM .dylib + bundle
  → Rust worker isolate/FFI
  → 同一 CAF1 / store / CLI / 验收场景
```

<a id="nat-01"></a>

## NAT-01 薄 C ABI 和完整发行资源

| 项目 | 落地要求 |
|---|---|
| 状态 | 待实施 |
| 行为 | 封装同一 JVM 已验证解析核心，产物可独立加载并拒绝混装。 |
| 调用链 | java-bridge → native-bridge → GraalVM JDK 25 shared library → Rust worker C ABI。 |
| 开发前置 | ACC-03 |
| 联调前置 | 无；本任务独立组件验证 |
| 实施 | 只增加 C entry/isolate/资源与构建装配；沿用 ABI 2/request-result 8/CAF1（若变更按公共升级规则）。复制所需 worker/XML/反射资源元数据，剔除被裁剪 JDBC/CLI 的无关初始化。build/test/e2e 与 JVM 入口分离，使用实际 bundle/共享库/worker/daemon 摘要。 |
| RED / 基线 | 先补缺 ABI/header/资源、错版本/旧 bundle、无 worker、反射或 XML 资源丢失的失败检查，再装配；真实 Java 8 worker 及 major 52；已有 C ABI 生命周期测试直接回归。 |
| 完成条件 | 包内 fixture 可运行且不依赖兄弟仓库，C header/ABI/manifest/SHA 一致，无 JNI/隐式 JVM 回退；原解析及公共 bridge 没有分叉。 |
| 追溯 | A6/A11；旧 01 P4；03 发行；T4/V4 |

<a id="nat-02"></a>

## NAT-02 native 全链路与 JVM 等价

| 项目 | 落地要求 |
|---|---|
| 状态 | 待实施 |
| 行为 | 相同输入下 native/JVM 模型、事实和公开查询等价，原生生命周期通过。 |
| 调用链 | 真 CLI → daemon → Rust worker → C ABI/.dylib → CAF1 → Rust 发布/查询 → 冻结 iopengw。 |
| 开发前置 | NAT-01 |
| 联调前置 | 无；按完整 native 门禁验证 |
| 实施 | 公共运行器增加显式 native backend；复用全部适用组件、fixture/框架/增量/故障、I0–I8/Q1–Q10。补 native 崩溃/isolate/线程/取消/超时/释放；JVM/native 按运行和 revision 分开回执。 |
| RED / 基线 | 先确认 ABI/资源新缺口失败再做薄适配；JVM 原预期不变，上游已知缺陷独立标记，不能掩盖 native 新差异。 |
| 完成条件 | fixture 后真实项目通过；原模型/Fact IR/CLI 的内容/跨度/状态/次数/证据等价，ABI/资源/发行/生命周期完整，产物身份准确；未执行不算通过，最终性能由 ACC-04 完成。 |
| 追溯 | 旧 P4/T4/V4；03 native 专项与全链路 |

进入条件和最终回执见 [统一验收](../acceptance.md)。本链不重新设计解析语义，不绕过 JVM 未完成项。
