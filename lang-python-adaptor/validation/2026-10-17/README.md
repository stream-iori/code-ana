# Python 范围验收

结果见 [receipt.json](receipt.json)。P0–P5 在 Python 3.12 / darwin、macOS ARM64 与冻结样本范围内通过；不签完整语义或稳定性能。

| 证据 | 位置 |
|---|---|
| 仓库 fixture、Rust/Java、Native、隔离分发及所有修复前失败 | `logs/`；上次安全拦截另见 `../2026-10-16/` |
| 缺依赖环境及同库查询复查 | `product-missing-release/`；原失败 receipt 不覆盖 |
| Python 3.12 依赖功能验证与同库查询复查 | `product-py312-functional-final/` |
| 唯一正式测量 | `product-py312-measured/`，`measured=true`，空应用状态 |
| 固定源码与 615 文件范围、25 项人工导航 | `inputs/`；9 个探针位于仓库 `fixtures/python/inference/` |
| 27 个 Python 3.12 wheel 的版本和哈希 | `inputs/wheels-requirements.txt`、`inputs/wheels-sha256.json` |
| 最终 Release 清单、依赖许可证与文件哈希 | `package-*` |
| 当前实现源码和证据字节哈希 | `source-sha256.json`、`sha256.json` |

大型 JSON 使用无损 gzip；`compressed-artifacts.json` 保存解压后的原字节哈希。原始本机数据库、wheel 和失败日志继续留在既有 `target/python-adaptor-eval-20261016/` 路径；本目录保留清单和回执，不提交图库或第三方 wheel。

重放先用 `git archive 31259b761a5ea77672d0e90bc9e2776e4dfe397e` 恢复 diorama-sdd；逐项核对 `inputs/input.json`，不得使用 sibling 当前 HEAD。依赖只下载并解压数据，不安装、不执行目标代码：

```sh
python3.12 -m pip download --only-binary=:all: --python-version 3.12 --implementation cp --abi cp312 --platform macosx_11_0_arm64 --dest /absolute/wheelhouse -r lang-python-adaptor/validation/2026-10-17/inputs/wheels-requirements.txt
```

核对 wheel SHA 后，按 wheel ZIP 相对路径仅提取 `.py`、`.pyi` 和 `py.typed` 到显式依赖目录；拒绝绝对路径、`..` 和冲突内容，不运行 `.pth`。该次提取 1,993 文件，完整来源清单见 `product-py312-measured/python-inputs.json`。5 个可选外部名称（jury/openai_codex）及 3 个运行时搜索路径名称（helpers/seed_remote_knowledge/update_remote_knowledge）保持缺失，不伪造 stub。

在仓库根目录、完成 fixture 门禁后运行功能重放（省略 `--measure`）；修改 case 文件中的固定副本前缀以匹配本地项目路径，目标文件与预期行号保持不变：

```sh
python3 tests/python_real_project.py \
  --project /absolute/frozen-source \
  --inventory lang-python-adaptor/validation/2026-10-17/inputs/input.json \
  --selected lang-python-adaptor/validation/2026-10-17/inputs/check-files.json \
  --dependency /absolute/dependencies-py312 \
  --cases /absolute/navigation-real-cases.json \
  --cases /absolute/navigation-real-extra-cases.json \
  --reference-case reads --reference-case searches \
  --evidence /absolute/new-evidence \
  --bin /absolute/package/bin
```

`--evidence` 必须不存在。功能通过后才能增加 `--measure` 做单轮测量；本次已经完成，不再次测量替换结果。该工具会写冻结副本的 `.code-ana/`，不会修改 sibling 工作区。
