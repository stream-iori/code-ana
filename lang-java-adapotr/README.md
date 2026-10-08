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

## 绕开 Rust 的直接索引诊断

以下命令在工程根目录执行，消费已有的冻结 Maven 清单，不运行目标项目构建。先用 SDKMAN 的 GraalVM 25 打包本模块，然后运行探针：

```sh
(source "$HOME/.sdkman/bin/sdkman-init.sh"; cd lang-java-adapotr; sdk env; mvn -q -DskipTests package)
probe_work=$(mktemp -d "$PWD/target/java-direct-XXXXXX")
mkdir "$probe_work/classes"
python3 tests/java_direct_index_request.py \
  --project target/java-alignment/iopengw \
  --manifest target/java-alignment/iopengw/.code-ana/main-test-inputs-frozen.json \
  --output "$probe_work/request.json" --scratch "$probe_work/scratch" --cache "$probe_work/signatures"
"$HOME/.sdkman/candidates/java/25.3.4+1.r25-graalce/bin/javac" \
  -cp lang-java-adapotr/native-bridge/target/native-bridge-0.1.0.jar \
  -d "$probe_work/classes" tests/java-migration/DirectIndexProbe.java
"$HOME/.sdkman/candidates/java/25.3.4+1.r25-graalce/bin/java" -Xmx2048m \
  -cp "$probe_work/classes:lang-java-adapotr/native-bridge/target/native-bridge-0.1.0.jar" \
  DirectIndexProbe "$probe_work/request.json" "$probe_work/progress.json"
```

探针使用实际 `AnalysisSession`，记录捕获、javac、原始模型提取、声明库存和 Fact IR 投影的计时；不转储所有模型、不经过 Rust worker、不发布索引数据库。XML 取清单中的资源根，同时捕获源码祖先目录的 `lombok.config`；完整 Rust 捕获还有其他控制/资源文件，因此该入口用于耗时归因，不替代语义验收。最后追加整数参数可在处理对应数量的上下文后停止，结果为 `diagnostic_prefix_complete`，不表示全项目成功。阶段存在嵌套，不能直接相加；异常中断的阶段可能仅记录已完成部分。进度时间是最近检查点，完整耗时应读取进程运行回执。排查结果见 [性能诊断](../docs/java-performance-diagnosis.md)。

`native-bridge/src/maven-worker` 是从旧适配器保留的显式输入导出边界，仅由 `java-inputs export` 调用。分析任务不执行 Maven、业务构建或注解处理器。

迁移范围、协议变更及未完成的验收见 [迁移记录](../docs/java-anatomist-migration.md)。旧实现的验收记录不作为新实现的通过证据。
