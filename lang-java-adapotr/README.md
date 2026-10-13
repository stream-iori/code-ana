# Java 适配器

当前产品默认 Native/G1；源码、模型与框架迁入 Anatomist 核心，Rust 拥有调度、图存储和查询。目标 Java 8，Java 25/GraalVM 构建环境与目标 JDK 分开。

```text
Rust daemon → 独立 Rust worker → C ABI 4 → Java 25 Native/G1
                                             │ 独立模型 / 框架 / GRA1 rows
                                             └→ Java 8 javac 子进程
Rust 私有候选 → schema 7 紧凑图 → 校验 / catalog CAS → 不可变 revision
```

| 入口（仓库根目录） | 范围 |
|---|---|
| just test-java | 构建 JVM、Java 8 桥并执行组件测试 |
| just test-java-jvm-fixtures EVIDENCE | 当前组件与 GRA1 图门禁；显式 JVM 开发/诊断，无自动回退 |
| ./scripts/build-java-native.sh --build-only | 完整开发构建，默认 G1，不签正式验收 |
| just test-java-native | 安装 SDK、manifest/生命周期、类型化图、错误 JDK 和真实崩溃 |
| just test-java-release | 成套打包、原执行路径验证与恢复 |
| python3 scripts/check-java-migration.py | 源码来源清单与 SHA 核对 |

当前 daemon 3、worker 18、graph model 2、analysis request/result 9、C ABI 4、compiler batch 2 / javac-model-v50。物理 storage schema 7、逻辑 schema 33 / GraphSemantics 26；详见 [现行说明](../docs/current-state.md) 和 [Java worker](../docs/modules/java-worker.md)。

G1 一个进程只创建一次 isolate，健康任务复用；失败/取消恢复换进程。显式 Serial 支持。Oracle GraalVM 固定在 .sdkmanrc；目标 JDK 由本机 SDKMAN Java 8 配置选择。只进行禁用处理器的 javac parse/analyze，不执行目标构建、处理器或业务代码，无 JNI。

过期 lang-adaptor / CAF1 / Fact JSON / raw transport 测试和入口已删除，当前 Python 进程工具在 tests/product_support.py。upstream-sources.json、upstream-archive.json 与原始 Java 参考测试属于来源/覆盖审计输入；保留来源并不证明全部覆盖。正式构建仍由 check-java-jvm-acceptance.py 核对完整回执和源码集合；build-only、fixture 与本次清理均不关闭完整语义/来源覆盖或容量/稳定性能验收。
