# Java 适配器

当前 Java 实现入口。`lang-adaptor/` 保留作 Rust 边界与历史实现参考。

```text
Rust daemon / worker（捕获输入、隔离、发布、查询）
                   │ C ABI 2 / request 7
                   ▼
native-bridge（GraalVM 25 共享库、Fact IR 投影）
      ├── analysis-core（Anatomist Java 模型、ASM、提取、静态 Lombok）
      ├── framework-spring（注解与 XML）
      └── framework-sofa
                   │ Anatomist batch 2 / javac-model-v50
                   ▼
Java 8 javac 子进程：parse / analyze，禁用处理器
```

| 入口 | 命令 |
|---|---|
| JVM 回归与源码追溯检查 | `./scripts/test-java.sh`（仓库根目录） |
| 原生共享库 | `./scripts/build-java-native.sh` |
| 冻结参考独立对照（需相邻参考 JAR） | `python3 tests/java_anatomist_reference.py` |
| 原生边界回归 | `python3 tests/java_anatomist_native.py` |
| 产品链路回归 | `python3 tests/java_e2e.py JavaProduct.test_core` |
| 分发 | `./scripts/package-java.sh` |

构建使用 `.sdkmanrc` 的 GraalVM 25。编译器桥用 Java 8 编译，并逐个验证 class major=52；`CODEANA_JAVA8_HOME` 可覆盖构建 SDK。目标项目的工具链仍由 Rust 配置选择。

来源固定为 Anatomist `dff2e3954096853352ec91fcbfc6b995fbd4f390`，保持原包名、类与文件边界。`upstream-sources.json` 记录原路径、原始摘要、迁入摘要和适配原因；`scripts/check-java-migration.py` 可离线核对。构建不依赖相邻 Anatomist 工作区。

`native-bridge/src/maven-worker` 是从旧适配器保留的显式输入导出边界，仅由 `java-inputs export` 调用。分析任务不执行 Maven、业务构建或注解处理器。

迁移范围、协议变更及未完成的验收见 [迁移记录](../docs/java-anatomist-migration.md)。旧实现的验收记录不作为新实现的通过证据。
