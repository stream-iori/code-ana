# Python worker

现行 adapter 为 v3：统一 `index --prepare` 默认静态检查各入口 pyproject，支持隔离/共享环境与显式模块依赖，输出 schema 3（兼容 1/2）。原独立导出器仍默认 schema 2、必须显式 --pyproject 才读取元数据。配置与行为见 [统一输入准备](inputs.md)；下文独立导出示例继续有效。


Python 通过独立 Rust worker 接入固定的 Pyrefly 内核。验收范围及回执见 [当前实现](../current-state.md)；历史失败证据保留在 `lang-python-adaptor/validation/2026-10-16/`。

```text
显式清单 → capture 校验/冻结 → 独立 Python worker
                                 ↓
                     Pyrefly 1.4.0 固定内核
                                 ↓
                 GRA1 → 私有候选 → End 校验 → CAS
```

| 项目 | 约定 |
|---|---|
| 后端 | Pyrefly `feeee254e65844f23f973a0a29cae02d09931068`，无上游补丁，无自动回退 |
| 构建 | `lang-python-adaptor/` 是独立 Cargo workspace，worker 位于其 `worker/`；保留两个锁文件 |
| 分析线程 | 固定 10 MiB 栈，与上游默认一致；单个 blocking 分析任务，不读取 PYREFLY_STACK_SIZE |
| 原因 | Pyrefly 要求 unicode-ident ≥1.0.25；现有 Rust 适配器固定 1.0.24，不能合并解析依赖或静默更换 Unicode 版本 |
| 协议 | worker 21、graph model 4、物理 schema 11 / 逻辑 schema 34；Java ABI 4、analysis 10 保持 |
| 环境 | 首轮目标 Python 3.12 / darwin；冻结的有序源码根和辅助依赖根，标准库随固定后端封装 |
| 执行 | 不执行目标模块、解释器探测、构建、安装、editable hook 或 `.pth` |
| 来源 | UTF-8（含 BOM）及 ASCII；其他编码明确拒绝；声明使用 UTF-8 字节范围，图列坐标为一基 UTF-16 |
| 隔离 | 私有目录只恢复清单字节；关闭环境探测、上行配置、搜索根启发式和 fallback；临时路径不得进入公开身份 |
| 增量 | manifest 与 profile 完全一致才复用；任一 Python 输入/依赖/环境变化全量重算，不使用 Rust 状态表 |
| 多语言 | Python 参与的组合全量协调；各 worker 独立校验 End 后合并、一次 CAS；任一失败不发布 |
| 查询 | 使用已发布静态目标；Any / union 未知分支保留原因；Python CHA 和运行时可实例化查询明确拒绝 |
| 取消 | 捕获/发流/入库检查取消；内核正在计算时由 supervisor 的 2 秒取消截止与进程退出兜底 |

项目配置：

```toml
schema_version = 1
kind = "project"
[languages.python]
enabled = true
[languages.python.discovery]
inputs_manifest = ".code-ana/python-inputs.json"
[resources.index]
python_result_bytes = 67108864
python_fact_limit = 100000
python_scratch_bytes = 536870912
```

外部准备依赖后导出；导出器只列文件并计算哈希：

```sh
python3 scripts/export-python-inputs.py --project /path/to/project \
  --source-root src --dependency-root /path/to/prepared/site-packages \
  --python-version 3.12 --platform darwin
```

没有外部依赖时省略 `--dependency-root`。多次参数保留各角色内的根顺序；项目根总在辅助 site-packages 根之前。同名模块、stub 优先级由 Pyrefly 处理。`py.typed` 入清单但不当作声明文件。导出器跳过 `.venv`、缓存和构建目录；不解释 `.pth`。源码变更后必须重新导出，旧哈希会拒绝捕获。

`pyproject.toml` 可选，不存在也能使用上述显式流程；默认不读取，不向父目录查找。启用方式如下（运行导出器的 Python 需 ≥3.11）：

```sh
python3 scripts/export-python-inputs.py --project /path/to/project \
  --pyproject pyproject.toml --extra dev --scope TEST \
  --dependency-root /path/to/prepared/site-packages \
  --python-version 3.12 --platform darwin
```

| 元数据 | 行为 |
|---|---|
| 标准项目字段 | 记录名称、requires-python、运行依赖和选定 extras；默认不选 dev，不进行依赖安装、求解或版本核验 |
| setuptools | 从 packages.find.where 或空包名前缀的 package-dir 推导根；命名包重映射和其他后端要求显式根 |
| pytest | 仅 TEST 使用声明的 pythonpath；testpaths 不作为搜索根，不执行 conftest 或动态 sys.path 修改 |
| 显式覆盖 | 一旦提供 --source-root，完整保留其顺序，不混入元数据推导根 |
| 动态字段 | 记录未求值说明，不读取 attr 对应模块，不调用构建后端 |
| 配置冻结 | TOML 路径、SHA 和长度加入 CONTROL；变更后旧清单拒绝捕获，重新导出再全量重算 |

新清单 `schema_version=2` 包含 `roots[{id,path,publish,files[{path,sha256,bytes}]}]`、`contexts[{id,module,scope,python_version,platform,roots}]`、`controls[{path,sha256,bytes}]` 和可空 `metadata`。兼容读取无新增信息的 schema 1。高级配置可显式编辑 contexts 表达 MAIN/TEST 和多 world；Python 不借用顶层 Java/Rust `modules` 配置。world 包含上下文及根身份/顺序；内容哈希、元数据、适配器版本和后端提交参与 profile。重叠根中同一来源只发布一次，首个根决定主模块名；过滤文件仍可供 import 使用。辅助依赖只用于分析，不能冒充项目声明。

适配器 v2 保留全部后端诊断：捕获文件使用真实 source_file，后端文件在 sample_details 保存逻辑来源，不冒充捕获源码。诊断记录范围、world、归因状态及受影响来源；影响范围使用 Pyrefly 实际 handle 的反向依赖闭包，按来源缓存。缺口只传播到实际消费者；影响未知时使用 `source_file="*"` 上下文 coverage，严格模式仍拒绝。无关辅助错误不再广播到所有文件。查询诊断时须区分 source / auxiliary / backend。

赋值目标生成声明，不生成读取引用；异常处理器的 `as` 变量生成对应定义范围的声明。其余未知保留原因；每个 world 的 `python.extraction_samples.<world>` 项目元数据保留每类最多 3 个定位样本、每样本最多 8 个后端目标及原目标总数，用于区分提取限制与动态行为。

| 能力 | 当前边界 |
|---|---|
| 声明 | 模块、类、函数、参数、赋值目标、import alias；包含关系；重复名字按来源区分 |
| 调用 | 直接/绑定方法、构造类、工厂、高阶返回、union、classmethod/staticmethod、Protocol 类型上的静态成员 |
| 继承 | 可提取的直接基类；没有运行时 MRO/猴子补丁枚举、完整 override 图或 Java CHA |
| 未知 | Any、可调用协议无唯一来源、合成函数、无法提取的引用保留 partial；不按同名猜目标 |
| 完整性 | 语法、缺模块/源码、提取缺口使 coverage partial；普通类型诊断单独记录；ignore 注释不能掩盖缺模块 |
| 预算 | 单文件 8 MiB、输入 ≤100,000 文件 / 128 contexts；输出默认 64 MiB / 100,000 行；scratch 默认 512 MiB；worker RSS 沿用用户总预算，混合任务均分 |
| 已验收 fixture | Python CLI、九个探针、取消/崩溃/预算失败、删除/import 恢复、Rust/Python、Java/Python、三语言；Java Native 与显式 JVM 组合 |
| 分发验证 | 独立 home 的 Release 包包含元数据冻结、旧清单兼容及既有 CLI 回归；真实项目范围见当前实现 |
| 未支持 | 其他目标版本/平台、任意编码、细粒度模块增量、运行时真实调用图；单轮不能证明长期稳定性能 |

```sh
just build-python
just test-python-core
just test-python               # Java fixture → Python fixture → CLI
just package-python /absolute/new/package
just test-python-release /absolute/new/package
just test-python-mixed         # 需先安装匹配的 Native SDK
```

包包含 Java host、Rust/Python worker、CLI/daemon、fixture、两个锁文件、上游清单、依赖许可证清单及 SHA256SUMS；Java SDK 不在此包中。doctor 展示清单元数据、CONTROL 与读取错误；不验证控制文件和依赖的当前字节。`binary_present` 仅检查制品存在，`readiness=requires_index_validation` 不表示可启动或已验收。daemon 要求 worker 声明匹配的适配器 v3 和固定后端提交；v1/v2 worker 不能冒充 v3，旧图保持历史含义，profile 变化后全量重算。

验收顺序保持：仓库 Python fixture 全部通过后，才在 diorama-sdd 冻结提交 `31259b761a5ea77672d0e90bc9e2776e4dfe397e` 的独立副本做最终实验；不能直接用 sibling 当前 HEAD。缺依赖样本与完整依赖样本分开记录；功能门禁通过后才做一轮正式性能测量。
