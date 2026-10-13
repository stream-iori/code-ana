# SIGKILL 排查与修复（2026-10-08）

> 历史记录（2026-10-13 文档对齐）：特定旧产物的 SIGKILL 排查；系统原因未确证，不能据旧失败判断当前产品。后续原路径重试和真实崩溃验证见最新实施记录。现行版本、验证范围及未完成门禁见 [当前实现](current-state.md)，最新结果见 [存储实施记录](implementation/anatomist-storage-migration.md)。

**旧产物混用和 native 原地覆盖已修复。原始 SIGKILL 的系统原因仍未确认，daemon 端到端尚未复验。**

| 证据 | 判断 |
|---|---|
| `target/lang-java-adapotr-v2-local-product.log`：worker PID 24686 / 24976，exit=SIGKILL，stderr 空 | 进程被杀；单凭此记录不能判断 OOM、系统安全规则或代码签名 |
| 冻结 debug worker 拒绝协议 13、接受协议 12；release 接受 13；源码常量为 13 | 验收目录确实混入旧 Rust 产物；普通 Cargo 构建还报告 Fresh |
| 清理 `codeana-model` 构建缓存后重编译；`--runtime-info` 返回 worker=13 / request=7 / ABI=2 | 已修复当前 debug 产物；冻结与打包会拒绝旧 worker |
| 新构建前后 inode 改变，旧打开句柄 SHA256 不变，新 manifest 校验通过 | native 库完成后原子替换，运行中的旧映射不会被构建改写 |
| 与产品一致的独立进程组、FD 3、protobuf、实际 C ABI、全量/增量、完整事实校验、Ack、3 轮热 host | 已通过；未观察 SIGKILL |
| 局部变量 fixture 经独立 worker 3 轮，每轮 337 条事实，正常 exit=0 | 局部声明/引用与原始跨度通过；缓存 Ack 无错误 |
| daemon 索引引擎直接调用真实 supervisor，3 轮均发布，增量与重新全量事实相等 | 索引引擎链路恢复；未复现 SIGKILL，HTTP 启动另验 |
| 当前环境 daemon 启动返回 `Operation not permitted`；系统日志工具返回 `Cannot run while sandboxed` | 尚未进入 daemon 分析；原始系统终止原因缺日志证据 |

```text
已冻结的源码 ≠ 已编译的二进制
          ↓
编译 → 实际 worker 版本 + 成套 bundle SHA 验证 → 冻结/打包

native-image → 私有临时目录 → 链接完成 → 原子替换库/manifest
                                        ↓
                               旧 worker 继续读取旧 inode
```

| 修改 | 文件 |
|---|---|
| 实际 worker 版本查询 | `crates/java-worker/src/main.rs`：`--runtime-info`，验证 bundle 后返回编译版本 |
| 冻结/发行前版本检查 | `tests/java_product_runtime.py`、`scripts/package-java.sh` |
| 原子发布共享库及 manifest | `scripts/build-lang-java-adapotr.sh`、`scripts/java-adapotr-manifest.py` |
| 缓存提交隔离 | captured backend 的 worker JAR 放在 `.runtime/`，Rust 不把它当签名条目 |
| daemon 索引引擎验收 | `crates/application/examples/java_engine_smoke.rs`：真实捕获、supervisor、发布、局部导航、delta/full 相等，无监听端口 |
| 生产 IPC 验收 | `crates/application/examples/java_worker_ipc.rs`：新目录、协议 13、独立进程组、3 轮、正常取消退出 |
| 受限环境的开发测试 | JVM 测试缓存和 native probe 临时目录使用工程 `target/` |

| 已执行门禁 | 证据 |
|---|---|
| 旧 worker 冻结前拒绝 | `target/java-sigkill-stale-runtime-check.log` |
| 原子替换和旧句柄保持 | `target/java-sigkill-atomic-build.json` |
| JVM：74 项通过 | `target/java-sigkill-jvm-tests-local.log`、Maven surefire reports |
| Rust worker/model/contracts：28 项通过；workspace clippy 通过 | `target/java-sigkill-rust-tests.log`、`target/java-sigkill-clippy.log` |
| 原生：8 项通过 | `target/java-sigkill-native-tests.log` |
| daemon 索引引擎：3 轮发布和 delta/full 相等 | `target/java-sigkill-engine-smoke.log` |
| 包内生产 IPC：3 轮 337 条事实，exit=0 | `target/java-sigkill-package-ipc.log` |
| Rust 实际生产 IPC | `target/java-sigkill-rust-group-ipc.log` |
| 局部变量独立进程组 IPC：3 轮正常退出 | `target/java-sigkill-group-ipc.log` |
| 发行包版本/19 个文件摘要 | `target/java-sigkill-package/` |

复现索引引擎验收（worker 与示例须同目录，与产品启动约定一致）：

```sh
cargo build --workspace --example java_engine_smoke
cp target/debug/examples/java_engine_smoke target/debug/codeana-java-engine-smoke
target/debug/codeana-java-engine-smoke
```

独立真实模型诊断的 81 文件上下文 raw/AST 均通过，参考/JVM/native 14 类原始数据零差异，输入和产物未漂移：`target/java-sigkill-model-diagnostic/receipt.json`。全项目门禁在参考的首个 `app/core/service@MAIN`（2624 个输入/547 个发布源码）报 `JAVAC_ANALYZE_FAILED: java.lang.NullPointerException`；证据为 `target/java-migration-models-full-signatures/receipt.json` 和 `raw-0/reference.log`，不登记为完整通过。

旧失败回执保留；不通过改路径、关闭安全服务或降低断言把 SIGKILL 改记为通过。真实项目 raw/AST 各 80 上下文、14 个发布语义域和 3 轮独占性能仍单独验收。
