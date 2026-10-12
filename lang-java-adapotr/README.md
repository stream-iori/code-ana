# Java 适配器

显式 JVM 开发入口已接通，完整迁移仍在实施。native 必须等完整 JVM fixture 与冻结 iopengw 新验收通过。

```text
Rust daemon → Rust worker → JVM runner
                              ↓
                         java-bridge
                 ┌────────────┼────────────┐
             analysis-core  Spring       SOFA
                              ↓
                      Java 8 javac 子进程
```

| 入口 | 命令（仓库根目录） |
|---|---|
| 独立 JVM 构建 | `just java` |
| 组件门禁（证据目录必须不存在） | `just test-java-jvm-components target/evidence/components` |
| 共享适配器 JVM 门禁 | `just test-java-jvm-probe target/evidence/probe` |
| CLI→daemon→JVM→store 门禁 | `just test-java-jvm-product target/evidence/product` |
| 完整 JVM fixture | `just test-java-jvm-fixtures target/evidence/jvm-fixtures` |
| 完整 JVM 验收封存 | `python3 scripts/java-jvm-acceptance-gate.py --manifest /path/to/receipts.json --output /path/to/jvm-gate.json` |
| 原生共享库构建 | `CODEANA_JVM_ACCEPTANCE_RECEIPT=/path/to/jvm-gate.json ./scripts/build-lang-java-adapotr.sh` |
| 实际 C ABI 生命周期 | `just test-java-native-lifecycle target/evidence/native-lifecycle` |
| 独立冻结参考构建 | `just java-reference` |
| 原模型/提取差分 | `python3 tests/java_anatomist_reference.py` |
| 源码摘要核对 | `python3 scripts/check-java-migration.py` |

产品验证显式设置 `CODEANA_JAVA_BACKEND=jvm`。宿主默认固定 GraalVM 25，可用 `CODEANA_JAVA_HOST_HOME` 指定；目标 Java 8 由项目用户工具链配置选择。原始绑定和模型完整保留，`external.exclude_patterns` 通过原提取器控制语义引用，不删除原始绑定证据。

| 契约 | 当前值 |
|---|---|
| CLI/daemon handshake | 2（混装明确拒绝） |
| worker/Fact IR | 16 / 1 |
| Java request/result | 8 / CAF1 |
| compiler batch/model | 2 / javac-model-v50 |
| 产品目标 Java | 8；上游 17/25 用例保留并独立验证 |

`upstream-sources.json` 保存冻结提交 `dff2e3954096853352ec91fcbfc6b995fbd4f390` 的逐文件摘要和边界适配原因；`coverage-inventory.json` 包括尚未迁入和需要 Rust 对接的测试，不把复制或部分通过算作完整验收。普通 package 不包含 native-bridge。

JVM 汇总清单要求组件、探针、产品 fixture、全部160原模型、产物等价、真实发布、14语义域及精确已知缺陷分类、只读审计、公开查询和真实提交历史共15份回执。native 构建先核对其全部输入 SHA 和当前 Java 生产源码集合。重建或修改这些输入后须重新取得对应门禁；单独模型通过不能放行 native。

`upstream-archive.json` 逐项记录已由 Rust 接管的 Java 产品实现和过期资源；`rust-coverage-mappings.json` 单独记录原测试预期及实际替代用例。归档产品实现不等于原测试通过。

新的参考包从冻结 Git tree 独立构建，`target/frozen-reference/manifest.json` 绑定源码、工具链和 JAR；旧 JAR、旧验收摘要和旧 native 回执不用于本次门禁。
