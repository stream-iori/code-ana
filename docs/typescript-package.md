# JS/TS 安装与分发

| 入口 | 前置 / 作用 |
|---|---|
| `just build-typescript-native SOURCE GO_HOME SDK EVIDENCE` | 固定来源、Go 1.26.2 和生成工具；生成真实共享库、标准库、许可、manifest 和构建回执 |
| `just test-typescript SDK EVIDENCE` | 当前真实 SDK 的 provider、CLI、资源和故障门禁；证据目录须为新路径 |
| `just install-typescript-native /absolute/sdk` | 用当前 worker 验证全部文件和运行身份；安装到 target/lib/typescript/versions/摘要，再原子切换 current |
| `just test-typescript-install SDK FIXTURE_SDK EVIDENCE` | 安装/重复安装、SDK 搬迁、原 inode 保留、错版本/平台/缺文件/坏 SHA 与 fixture 拒绝 |
| `just package-typescript /absolute/new/package` | 锁定 release 构建、独立 Python workspace、完整包；SDK 来自 CODEANA_TYPESCRIPT_SDK 或已安装的 current |
| `python3 scripts/package-typescript.py --sdk SDK --output /absolute/new/package` | 已完成 release 构建时显式选 SDK 打包 |
| `just test-typescript-release /absolute/package` | 核对完整清单；独立 home、空 PATH、临时隐藏开发二进制和 TS 源码；结束后恢复并核对 SHA |
| `just test-typescript-package-matrix PACKAGE JAVA_SDK EVIDENCE` | 实际包四后端、资源及故障；Java SDK 单独提供 |
| `just test-typescript-performance-once PACKAGE EVIDENCE` | 固定输入单轮测量；仓库排他 sentinel 防止重跑，不挑选成功轮次 |

```text
package/
 ├─ bin/               CLI、daemon、四后端 host、Rust/Python 静态准备器
 ├─ lib/typescript/
 │   ├─ versions/SHA/   Go 共享库、标准库、header、来源与许可
 │   └─ current → versions/SHA
 ├─ fixtures/          本仓库自建 Rust/Python/Java 输入
 ├─ docs/
 ├─ manifest.json
 └─ SHA256SUMS
```

先核对 `SHA256SUMS`，再运行 `bin/codeana-cli --help`。JS/TS 不需要 Node、Go 工具链、全局 types 或包管理器；只读取显式准备的项目/依赖。依赖应由外部准备，产品不自动安装或构建。

Java SDK 与目标 JDK 单独安装；测试四后端时，把已验证的 Java SDK 放在运行前缀的 lib/java，配置目标 Java 8／17 工具链。包内包含 Java host，不代表已捆绑 Java SDK。Rust/Python 的静态准备工具按各自文档要求使用，不在 TS 分析中运行。

安装器从不覆盖已存在版本目录的库；重新验证相同版本后可复用。current 是相对符号链接，切换后旧版本继续可读，已打开的库 inode 不变；旧版本不会自动删除。坏 SHA、错 ABI/私有分析版本、错平台及开发 fixture SDK 都在选中前拒绝。语义身份包含库/header/标准库/协议摘要，不包含安装目录。

支持范围为本机 macOS ARM64。真实 CLI、故障、组合和实际包的阶段回执见 [当前实现](current-state.md)；不把开发构建或单机成功解释为正式 Native、Linux、Java 25 目标或容量稳定性放行。
