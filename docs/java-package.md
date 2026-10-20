# codeana Java 8 / macOS

Native 默认采用 G1，worker 进程内复用一个 isolate；显式 `CODEANA_NATIVE_GC=serial` 可构建 Serial。

当前 bundle 为逻辑 schema 34 / adapter `anatomist-4a903272-codeana-graph4-maven-fallback1`，图库采用紧凑 storage schema 11。旧 schema 10 及更旧查询及增量明确要求 `index --full`；保留旧文件，成功全量发布后才切换 head，不进行原地迁移。GRA1 protobuf 行和 ABI 4 保持不变；worker 升为 21，analysis request/result 为 10，旧 adapter bundle 拒绝混装。

包内包含 CLI、daemon、Java host worker、Rust 语言 worker、GraalVM 共享库、Java 8 编译器桥和 C header。目标 JDK 由本机 SDKMAN 提供，不随包分发。混合语言使用同包 Rust worker 与 `codeana-export-rust-inputs`；Rust 输入导出需要 Python 3.11+，Java 输入导出仍由 Rust CLI 执行。

开发验收可用 `scripts/package-java.sh --from-build --output /new/package/path` 从已冻结产物打包，先检查 native/SDK8 桥 SHA，不重新编译；指定输出目录必须尚不存在。`python3 tests/java_release.py` 检查完整包校验清单，并从包内 bin/lib 执行真实 CLI、daemon、Java worker 的类型化图、增量/删除、XML、取消/崩溃/OOM 恢复及错误 JDK 用例；当前测试组和回执决定实际执行范围。该门禁不替代全部能力差分和独占容量验收。

## 验证并运行 fixture

在解压目录执行；示例复制到独立临时工作区：

```sh
shasum -a 256 -c SHA256SUMS
demo_workspace=$(mktemp -d "${TMPDIR:-/tmp}/codeana-demo.XXXXXX")
cp -R fixtures/base "$demo_workspace/project"
mkdir -m 700 "$demo_workspace/home"
cat > "$demo_workspace/home/config.toml" <<CONFIG
schema_version = 1
kind = "user"
[limits]
max_workers = 1
max_worker_memory_bytes = 536870912
[toolchains.java8]
language = "java"
home = "$HOME/.sdkman/candidates/java/8.0.492-zulu"
CONFIG
./bin/codeana-cli --home "$demo_workspace/home" --project "$demo_workspace/project" index
./bin/codeana-cli --home "$demo_workspace/home" --project "$demo_workspace/project" calls main
./bin/codeana-cli --home "$demo_workspace/home" --project "$demo_workspace/project" source main
./bin/codeana-cli --home "$demo_workspace/home" daemon stop
```

若已安装其他 SDKMAN Java 8，请将 `home` 改为对应绝对路径。默认 CLI 自动启动同目录 daemon。Java 17/25 目标、Linux、Gradle、完整框架推断与大型项目容量不在当前验收范围。

Maven 项目先由用户显式准备 metadata：

```sh
./bin/codeana-export-java-inputs --project /absolute/project --output /absolute/project/.code-ana/java-inputs.json
```

导出由包内 Rust CLI 执行，需要 Maven 与 SDKMAN Java 8，无需 Python。一次 Maven 会话通过 core extension 导出有效模型和已调解依赖，并在生命周期前退出；可能下载依赖。索引只消费已有输入，不调用 Maven、不运行处理器、不生成业务 class。项目配置与能力范围见 [现行配置](configuration.md)，实际验证见 [当前验证范围](current-state.md)。

开发回归使用 `just test-java-native` / `just test-java-release`；开发构建不替代完整迁移验收。测试需保留原执行路径时，可使用 `python3 tests/java_release.py --bin-root "$PWD/target/debug" --stage-release`；先校验包与原路径二进制/SDK 字节完全一致，再运行用例，执行路径会进入回执。`--stage-release` 要求原路径无活跃进程，归档并临时原子安装 release 字节，测试后恢复原二进制。

分发回归同时校验并暂存 CLI、daemon、Java host worker 和 Rust 语言 worker，避免混合语言测试借用包外旧 worker。
