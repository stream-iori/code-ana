# codeana Java 8 / macOS

包内包含 CLI、daemon、Rust worker、GraalVM 共享库、Java 8 编译器桥和 C header。目标 JDK 由本机 SDKMAN 提供，不随包分发。

开发验收可用 `scripts/package-java.sh --from-build --output /new/package/path` 从已冻结产物打包，先检查 native/SDK8 桥 SHA，不重新编译；指定输出目录必须尚不存在。`python3 tests/java_release.py` 检查完整包校验清单，并从包内 bin/lib 执行真实 CLI、daemon、Java worker 的核心及 Maven 资源模块正反例。该门禁不替代全部能力差分和独占容量验收。

## 验证并运行 fixture

在解压目录执行；以下 `demo-home` 应为新建目录：

```sh
shasum -a 256 -c SHA256SUMS
mkdir -m 700 demo-home
cat > demo-home/config.toml <<CONFIG
schema_version = 1
kind = "user"
[limits]
max_workers = 1
max_worker_memory_bytes = 536870912
[toolchains.java8]
language = "java"
home = "$HOME/.sdkman/candidates/java/8.0.492-zulu"
CONFIG
./bin/codeana-cli --home "$PWD/demo-home" --project "$PWD/fixtures/base" index
./bin/codeana-cli --home "$PWD/demo-home" --project "$PWD/fixtures/base" calls main
./bin/codeana-cli --home "$PWD/demo-home" --project "$PWD/fixtures/base" source main
./bin/codeana-cli --home "$PWD/demo-home" daemon stop
```

若已安装其他 SDKMAN Java 8，请将 `home` 改为对应绝对路径。默认 CLI 自动启动同目录 daemon。Java 17/25 目标、Linux、Gradle、完整框架推断与大型项目容量不在当前验收范围。

Maven 项目先由用户显式准备 metadata：

```sh
./bin/codeana-export-java-inputs --project /absolute/project --output /absolute/project/.code-ana/java-inputs.json
```

导出由包内 Rust CLI 执行，需要 Maven 与 SDKMAN Java 8，无需 Python。一次 Maven 会话通过 core extension 导出有效模型和已调解依赖，并在生命周期前退出；可能下载依赖。索引只消费已有输入，不调用 Maven、不运行处理器、不生成业务 class。项目配置与能力范围见 [实现说明](docs/java-implementation.md)，实际验证见 [验收记录](docs/implementation/java-validation.md)。
