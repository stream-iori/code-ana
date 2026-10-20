# 多语言输入准备

`index --prepare` 在 CLI 本地准备配置中的入口，成功或明确降级后再提交索引。普通 `index` 只消费冻结清单；`doctor` 展示静态计划，不运行 Maven/Cargo。

```text
doctor                         检查配置与准备入口
index --prepare                CLI 准备 → daemon 捕获 → 分析 → 一次发布
index                          校验现有输入 → 分析 → 一次发布
report <id>                    全局 JSON 报告
report <id> --format markdown   同一数据的 Markdown 展示
```

## 配置

```toml
schema_version = 1
kind = "project"
[languages.java]
enabled = true
[languages.java.analysis]
source_version = "8"
toolchain = "java8"
[languages.rust]
enabled = true
[languages.python]
enabled = true

[inputs]
manifest = ".code-ana/inputs.json" # 默认值

[[inputs.entries]]
id = "backend-a"
language = "java"
root = "backend-a"
manifest = "pom.xml" # 相对入口根；标准名可省略
[inputs.entries.java]
offline = true
profiles = []

[[inputs.entries]]
id = "backend-b"
language = "java"
root = "backend-b" # 独立 reactor，不要求共同父 pom

[[inputs.entries]]
id = "engine"
language = "rust"
root = "engine" # 原生 workspace 由 Cargo metadata 展开

[[inputs.entries]]
id = "tools"
language = "python"
root = "tools"
[inputs.entries.python]
dependencies = ["common"]
extras = []
dependency_roots = [".code-ana/prepared/site-packages"] # 仓库相对或绝对路径
python_version = "3.12"
platform = "darwin"

[[inputs.entries]]
id = "common"
language = "python"
root = "common"
```

每种启用语言必须有入口，上限 256。入口 ID 最长 64 字节，使用字母、数字、下划线或连字符。根在项目内，不接受 symlink 路径；不递归猜测其他项目。配置入口按原生工作区展开。

`modules` 仍表达源码归属、扫描范围及覆盖；按语言和项目相对物理根匹配。显式 module.id 保持，自动子模块从入口 ID 和原生相对路径派生稳定身份。重复模块、重叠工作区拒绝，不按同名包/坐标连接独立工作区。

### Python

默认静态读取入口根的 pyproject；显式 entry.manifest 优先，不向父目录搜索。不存在时使用显式源码根，例如：

```toml
[[modules]]
id = "tools"
language = "python"
root = "tools"
source_roots = [{path = "src", scope = "MAIN"}]
```

显式根完整覆盖推导根并保留顺序。支持标准字段、setuptools 常规布局及 TEST 的 pytest pythonpath；不执行动态元数据、目标解释器、pip 或 `.pth`。缺少可确定根时可降为文件/文本。

默认每入口隔离。dependencies 显式引入其他 Python 入口，允许循环、稳定去重；其源码供解析，不冒充消费者发布内容。MAIN 不读取 TEST，TEST 可读取 MAIN。目标仍限 3.12/darwin。

相同 entry.python.environment 表示共享环境，成员按配置顺序组织，各文件保留实际 module。目标和有序外部依赖根必须一致；冲突拒绝。只有显式共享或依赖才会互相可见。

Python schema 3 增加根 module、模块元数据、上下文 publish_roots/gaps，兼容 schema 1/2。adapter 为 pyrefly-1.4.0-codeana-v3，后端提交不变。旧格式默认值不改变旧 world 编码；新归属进入 world/profile。

## 输入与降级

公共清单 schema 1 登记原始/规范化语言清单及配置视图的路径、长度和 SHA。Maven 回执、Cargo 图分别保留，不拼成虚构的共同构建。准备使用项目锁和独立 generation；配置未变、规范化视图验证通过后原子切换。索引再次验证并捕获，之后不借活文件补全。

| 情况 | 处理 |
|---|---|
| Maven 模型可用、依赖失败 | 现有 source-only fallback，保留真实回执 |
| Maven 模型或 Cargo 图不可得 | 文件/文本可用、语义不可用，保留日志 |
| Rust 依赖 | 默认 offline/locked，不自动下载 |
| Python 缺包/指定依赖根缺失 | 分析现有输入，披露缺失导入或上下文缺口 |
| 源码错误/生成输入缺失 | 保留可提取结果与诊断，不自动运行生成器 |
| 配置冲突、损坏回执、哈希失败 | 任务失败，不以降级绕过校验 |
| 超时、取消、超限、工具崩溃 | 任务失败，回收所属进程 |

Java plain 使用已有显式模块根，不运行 Maven，仍需正确的 Java 8 分析配置。Maven 准备按 offline/settings 配置可能访问仓库，不运行编译生命周期。无证据时不把失败归因于网络。

普通索引允许可用入口与明确降级入口一起发布，整体 partial；strict 拒绝 partial、保留旧 head。全部语义不可用时文件/文本仍可发布。入口不可用有持久 coverage；无法解析选择器时返回 SEMANTIC_INPUTS_UNAVAILABLE，不能冒充确定的 SYMBOL_NOT_FOUND。

新模式保守全量或完全相同输入复用；模式切换清除旧语义事实。全部输出通过 VerifiedEnd/候选校验后一次 CAS。恢复依赖后重新 index --prepare，不静默借旧图。

## 报告与兼容

报告 schema 2 提供执行、质量、门禁、发布、报告五个状态，范围、输入模式分布、主要原因和日志/CLI 定位。JSON/Markdown 同源；不增加 JSONL，不嵌入完整配置、逐文件诊断或日志全文。入口样例上限 20，另给总量及省略量；详细排查继续使用 CLI/日志。

失败、取消、strict 拒绝也保存可用摘要；global.measurements_available=false 表示最终分析计数不可得，零值不能解释成无问题。报告关闭仍有终态；报告失败不撤销发布。旧报告可读。

无 inputs 配置时保持旧入口；新公共清单与启用语言的旧 inputs_manifest 互斥。原导出器、java-inputs export 保留，Python 原脚本默认不读 pyproject，显式 --pyproject 不变。独立导出器可只准备、不索引。

当前版本为 worker 21、daemon 4、Java ABI 4、graph 4、物理 schema 11。准备与索引不是跨进程事务，报告以实际捕获和提交为准。分析 worker 和 Java SDK 仍须按安装说明具备。

验证入口为 tests/prepared_inputs_e2e.py；CODEANA_TEST_REAL_JAVA=1 显式启用 Native 三语言及两个独立 Maven reactor。旧路径继续由现有 Rust/Python 门禁覆盖。
