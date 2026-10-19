# Python v2 配置与完整性归因修复

范围验收通过，见 [receipt.json](receipt.json)。可选 pyproject 不成为项目的前置条件；不安装依赖、不执行目标代码或动态配置。

| 验证 | 结果 |
|---|---|
| Java fixture | 641，通过；JUnit 原字节无损 gzip 和汇总保存在 `java-fixtures/` 与 `java-fixture-summary.json` |
| Python | 核心 20（含 subprocess helper）、导出器 5、隔离 Release CLI 7、9 个冻结导航探针通过 |
| Rust / 混合 | 共享门禁、Native Java/Python 与三语言发布/复用/失败原子性通过 |
| 真实项目 | 固定 diorama-sdd 提交 `31259b761a5ea77672d0e90bc9e2776e4dfe397e`，615 文件，25/25 导航及 resolve/structure/calls 通过 |
| 完整性 | 30 complete / 585 partial；3 个本地路径缺失解决，5 个源码外部缺失保留；25 个辅助缺失名称单列 |
| 归因 | 378 文件的依赖缺口均有来源证据；未知影响上下文 0，无证据传播 0 |
| 提取 | 写入不记读取引用；异常别名补 289 个声明，未提取声明缺口涉及文件由 220 降至 153 |

`product-py312-repair/` 保存异常别名修复前的功能通过结果；`product-py312-repair-final/` 为最终字节的功能结果。两者均 `measured=false`。原 v1 性能回执留在 `../2026-10-17/`，本轮没有新增正式测量，不将不同 scope、搜索根和二进制的时长作性能比较。

`logs/` 保留所有原失败：源码未捕获的后端诊断、CONTROL 分类、旧 daemon 不识别新清单、测试 fixture 路径和断言修正，以及包内 Python/Rust worker 的 SIGKILL。安全放行后使用同一执行路径和字节重试；异常别名是先失败再修复。并发阶段既有 Maven 超时测试曾失败，单独复验及 `RUST_TEST_THREADS=1 just test-rust` 通过，没有放宽测试阈值。

真实项目重放沿用原路径，仅写冻结副本的 `.code-ana`，不修改 sibling 工作树：

```sh
python3 tests/python_real_project.py \
  --project target/python-adaptor-eval-20261016/source \
  --inventory target/python-adaptor-eval-20261016/input.json \
  --selected target/python-adaptor-eval-20261016/check-files.json \
  --dependency /absolute/prepared/dependencies-py312 \
  --source-root skills/diorama/scripts --source-root . --source-root scripts \
  --source-root tests --source-root fixtures/e2e/layout13 \
  --pyproject pyproject.toml --extra dev --scope TEST \
  --cases target/python-adaptor-eval-20261016/navigation-real-cases.json \
  --cases target/python-adaptor-eval-20261016/navigation-real-extra-cases.json \
  --reference-case reads --reference-case searches \
  --evidence /absolute/new-evidence \
  --bin dist/codeana-python-Darwin-arm64/bin
```

其他机器先恢复同一 Git archive，并按原提交核对源码清单；调整导航输入中的冻结副本绝对前缀。输入快照在 `inputs/`，依赖 wheel 版本和 SHA 沿用原 27 个文件；控制文件与 archive 一致的证据见 `control-commit.json`。最终输入的 CONTROL 与元数据在 `product-py312-repair-final/python-inputs.json`。

`coverage-audit.json`、`semantic-diagnostics.json.gz`、`extraction-samples.json` 和 `comparison.json` 保存具体归因与剩余缺口；报告和事件使用无损 gzip，原字节哈希在 `compressed-artifacts.json`。源码 SHA 基于根基线及改动集合，历史验证目录和无关 todo 不计入。大数据库与旧包备份继续保留在本机 target，不提交到仓库。

剩余样本中，`knowledge_model.py` 的 `exc.code` / `exc.message` 指向 `result.py` 中实例属性赋值的名称位置；当前还没有为这类属性生成声明，仍保留 `reference_target_not_extracted`。`__name__` 等隐式名称、动态表达式属性和合成 builtin callable 也保留未知。它们没有被改写成 complete，不属于本轮承诺的系统语义补齐。
