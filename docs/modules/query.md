# 查询与 typed pipeline

> 对齐 2026-10-15。现行版本与支持范围见 [当前实现](../current-state.md)；以下验收表是需保持的行为约束，未完成能力单独注明。

当前调用与声明遍历按相邻 ID 分批查询 SQLite，最多保留 10,000 条查询记录并报告工作/深度截断。search-code 按公开 ID 递增、每批 512 条定向读取，完整结果流入 SQLite 冻结页库；不受上述遍历记录上限截断。续页以固定 page ID/offset 读取有界记录，参数与 revision 固定，页大小可变；引用和 TTL 随 catalog 管理。其他操作仍先计算有界结果，再冻结到同一页库。dispatch 从已发布的 call-target/overrides 事实扩展静态可能目标，披露开放世界；完整 Java 正确性仍待门禁。本轮不扩展 diff 查询。

**查询从固定版本取事实，输出关系及其证据边界。下游成功不能洗掉上游缺口。**

当前 query 经 `GraphAccess` 等窄接口读取 Anatomist 逻辑投影；CLI/pipeline 使用本项目输出契约。类型/方法关系与 CHA 已有固定图回归，不在查询时重跑 javac。

`QueryRequest` 与 `Record` 是核心参数/结果；pipeline 使用枚举端点取输入，转移上阶段记录集，不复制完整向量或按 JSON 键取字段。`Record` 区分事实、调用、派发、路径、源码、文本和聚合。稳定属性由 `FactAttributes` 表达，Map 保留索引/分组用途；历史页兼容见 [公共契约](../contracts.md)。

读能力分为 `SourceContext`、`GraphAccess`、`SourceLocator`、`AnnotationReader`、`OverviewReader` 和 `DependencyReader`。扫描方法必须显式实现，生产读取使用有界 SQL；FILES 文本/源码只需 `SourceContext`，不实现语义图能力。

## 1. 查询上下文

```text
TaskRequest / pipeline spec
          |
      类型与能力预检
          |
      ReadContext（固定 revision / snapshot / profile / world）
          |
      selector -> query plan -> 定向存储读取
                        |
                   语言查询策略
                        |
                   typed records
                        |
          有界阶段帧 + 证据 -> 下一阶段
                        |
                终止证据 + 本项目文本输出
```

| ReadContext 字段 | 作用 |
|---|---|
| project / revision / snapshot / profile / world / scope | 隔离事实身份与选择范围 |
| read handle / reader reference | 固定数据库和源码清单，生命周期内保护对象 |
| query/stage/subject/page identity | 分页与阶段证据归属 |
| deadline / work / output budgets | 有界计算与输出，耗尽原因互相独立 |
| capability registry / policy version | 确定操作是否可用、语言派发如何解释 |

跨请求续页固定原 page 对象、revision 和查询参数；活动入口变化不切换版本。过期明确失败，不自动重算。单条 pipeline 内只选择一次身份；diff 的上下文含两个固定端点。

## 2. 模块与端口

| 组件 | 输入 | 输出 / 约束 |
|---|---|---|
| SelectorResolver | 本项目 selector、公共 key、entity/关系记录 | 精确实体或候选；`--unique` 歧义失败 |
| OperationRegistry | 命令、记录类型、能力 | explain/check；缺事实明确不可用 |
| QueryPlanner | 选择范围、预算 | 有序读取与遍历计划 |
| GraphTraversal | 批量邻接、深度/状态预算 | 路径、调用关系及实际截断原因 |
| LanguageQueryPolicy | 已发布公共事实和语言 facets | dispatch、type/callable relations、proof |
| EvidenceAccumulator | 各阶段 page/semantic/output | 保留缺口并按归属合并 |
| SourceReader | declaration/site、捕获范围 | 校验后的源码片段，见 versioning |
| OutputRenderer | typed records、证据、最终字节预算 | 完整记录文本、恢复动作与终止标记 |

Rust 中的 Java 策略负责解释已解析类型关系和静态派发规则，不重新运行 javac 或 Java 名称解析。P0 若发现现有 Java 查询需要 worker 未输出的信息，先补事实契约；若仍无法表达，应重新评审 D06，不能隐式启动分析弥补。

## 3. 操作族

| 操作 | 核心语义 | 特别边界 |
|---|---|---|
| search-code / resolve | 候选发现与身份确定 | 外部符号可以命中但没有可读源码 |
| structure | 声明树与范围 | 公共大纲可压缩，内部声明不可裁掉 |
| calls | 调用点投影成去重方法关系 | 不代表每次出现；方向与 scope 规则由本项目契约定义 |
| call-sites | 每次源码调用及目标/缺口 | 无目标也可读调用现场 |
| dispatch | 有依据的静态可能目标 | 开放世界、算法和预算必须披露 |
| references / annotations | 引用、读写、注解、配置关系 | 配置绑定不能伪装成源码调用 |
| type-relations / callable-relations / runtime-implementations | 语言关系与可实例化候选 | 命令名称不代表运行观察 |
| trace | 有界路径与依据 | 深度耗尽不能证明更长路径不存在 |
| source | 调用点/声明对应源码 | `--owner` 与目标方法体读取不同 |
| overview | 聚合视图 | 样例不是全部关系；项目摘要不支持否定结论 |
| search-text | 配置范围文本搜索 | 独立能力，不自动补语义索引或扩张历史捕获 |

## 4. pipeline 与证据

```text
search-code ---- entity ----> resolve ---- entity ----> source
   缺口 A                        缺口 B                    读取成功
                                                         |
                                  最终证据 = A + B + source 证据

call-sites ---- call_site ----> source        读取现场
calls -------- call_relation -> resolve       导航到另一端方法
```

| 约束 | 实现要求 |
|---|---|
| 类型组合 | 执行前检查 stage 输入/输出记录类型；不解析 stdout 或语义 stdin |
| 有界帧 | 达到中间记录预算就终止并披露；不靠无限列表连接阶段 |
| cursor 生命周期 | 先关闭本阶段 cursor，再交付有界帧；异常与取消也关闭 |
| 证据传播 | 空阶段仍传播上游缺口，source inspect 不修复导航 |
| 稳定排序 | 使用公共身份与明确位置排序，不依赖 rowid / hash map 遍历顺序 |
| 部分输出 | returned 只计实际完整输出；元数据超预算返回明确的本项目错误 |
| 续页 | query/stage/subject/page 身份与 replacement 语义由本项目契约冻结 |

非流式结果使用 `Record::query_cmp` 比较各记录族的业务键，不使用展示 JSON 排序。具体键和历史页 `order_policy` 兼容见 [公共契约](../contracts.md#6-输出证据与历史)。实体身份与 world 使用不同 newtype；producer 未知状态原样保留，不按未知字符串猜测已解析。

当枚举完成、语义充分、计算和输出未限制且操作允许时，空结果才支持所选静态模型内的否定结论。不能将其升级为运行时行为不存在。

## 5. 验收

| 场景 | 期望 |
|---|---|
| 同一方法对两个调用点 | calls 一个关系，call-sites 两次出现 |
| 上游解析缺口，下游 source 完整 | 上游限制仍存在 |
| 发布发生在两个 stage 之间 | 整条 pipeline 保持原 revision |
| 阶段/源码/输出预算耗尽 | 分别记录计算、枚举、输出边界，不混成一个 truncated |
| Java 派发回放 | 按 Java fixture 的预期候选、proof、开放世界和预算行为断言 |
| 外部目标无源码 / 未展开宏 | 返回具体不可用原因，不报告完整空结果 |

公开布局、默认限制和退出码由本项目 fixture/golden 验证，见 [公共契约](../contracts.md)。[Anatomist 输出契约](../../../anatomist/docs/output-contract.md) 仅供设计参考，无兼容验收要求。

### 当前新增查询选项

`search-text PATTERN --regex --ignore-case --path GLOB --context N` 在捕获 UTF-8 字节上匹配，默认仍为区分大小写的字面量；正则采用 Rust regex 语法，模式最多 16 KiB、上下文最多 100 行。匹配和取消按有界批次执行，结果流入 SQLite 固定页；续页不重新捕获工作区。字面量至少 3 个字符时可使用内容校验的 FTS5 trigram 派生缓存，随后逐行复核捕获字节；短词、正则、忽略大小写及超过 16 MiB 的捕获走扫描路径。缓存最多占用现有派生缓存的 256 MiB 磁盘配额，任务内索引上限约 128 MiB；缓存不可用不会改变匹配语义，取消与源码 hash 漂移仍拒绝。无效 UTF-8 文件始终保留在候选中，避免丢失编码缺口。

`overview --section modules|packages|dependencies` 从 SQL 投影聚合；默认 project 保持原摘要。包依赖按已发布的引用、调用目标和关系 occurrence 计数，支持 `--direction in|out|both`、`--package-depth N`，以及逗号分隔的 `--relation`：type/reads/writes/config/all/calls、原关系名或 Fact IR 名。all 按冻结定义只合并 type/reads/writes/config；未知组或空项报错。最多 10000 个聚合组，超限明确拒绝，不把部分计数冒充完整。当前 dependency 的 module/scope/world 选择约束事实来源；包方向过滤选择源包/目标包。

`references` 默认读取 incoming occurrence；`--direction out|in` 显式选择方向，`--relation` 接受与 overview 相同的关系名和分组。显式 config 查询同时读取 Reference 与 StructuralRelation，保留配置关系 occurrence；incoming 不裁掉其他模块的调用方。CLI 通过 `reference-options-v1` 检查服务端能力。

Bean、Spring/SOFA XML、MVC 和 handler 的公开配置节点保留 Facet 类型，可按名字/签名定位、search-code、structure 和 source 读取。语法、绑定、类型辅助 facet 不参与实体发现或结构树。配置节点的选择范围和属性从当前 Anatomist 逻辑投影读取；旧物理 schema 不兼容时明确要求 index --full。raw type/binding/syntax 图查询不支持。

`annotations ENTITY --meta` 在直接注解 occurrence 上展开元注解，保留 `direct`、`meta_depth` 和 `via`。同一 occurrence 的不同路径分别返回；路径内防环，固定最多 16 层，达到深度或工作预算明确披露枚举缺口。元注解按冻结上游的 annotation/meta 对合并跨 producer 重复记录；属性值为 null，来源位置仍是直接注解现场。pipeline 对应 stage 设置 `meta: true`；其他操作设置 meta 拒绝。gRPC TaskRequest 字段 43、Stage 字段 8，以及 `annotations-meta-v1` 能力检查确保选项被传递。


### Java 层级和静态派发

| 命令 | 参数与结果 | 边界 |
|---|---|---|
| runtime-implementations | 反向遍历 extends/implements；默认只返回可实例化的 entity，`--instantiability yes|unknown|all`。`runtime_implementation` 保存理由、深度和原关系 ID 的 proof。 | 不返回种子；穿过抽象类/接口；module/scope/world 选种子，不删除其他模块的后代。未知类型不能猜成 class。 |
| type-relations / callable-relations | 默认一跳；显式 `--transitive --depth N` 逐层返回去重关系。 | 保留环边；边未枚举完时披露 depth，不能把已访问端点误当作所有边已返回。incoming 不过滤其他模块来源。 |
| dispatch | `--algorithm auto|exact|cha`；CHA 根据接收者类型、运行时具体类型和继承路径选择方法，最近的类声明优先，抽象声明遮蔽默认方法，子接口默认方法遮蔽父接口。 | 原确认绑定与 possible 候选分开；`proof` 是 callable 路径，`type_proof` 是类型路径。static/private/final/constructor/super 保留精确派发。 |
| 两类候选命令 | `--analysis-world workspace-open|workspace-closed|classpath-open`；默认 workspace-open。 | 与捕获 world 的 `--world ID` 分开；closed 是显式的工作区穷尽假设，不是运行时观测。覆盖缺口和预算仍保留。 |

层级邻接、声明和方法按最多 500 端点批读；CHA 的相关类型闭包预读后，各运行时类型的方法优先级在任务内缓存中计算。1200 个实现的独立固定图用例断言 1201 条结果及少于 60 次读取，避免按每个类型查询 SQL。最大 depth 为 32；工作/结果限制分别披露。pipeline stage 接受 instantiability/transitive/algorithm/analysis_world，非法命令组合预检失败。

协议字段：TaskRequest 44/45/46/47；Stage 9/10/11/12。CLI 检查 runtime-implementations-v1、relation-transitive-v1、java-dispatch-v1、java-analysis-world-v1；旧 daemon 缺能力时明确拒绝。

## 紧凑图库与 FTS

当前 `nodes` 是视图，没有可用于 FTS 联接的 rowid。`search-code` 先用 node_names 的 rowid 定位 `graph_nodes.node_pk`，再映射公开 id；读者只返回逻辑字符串列。FTS 仅为候选筛选，最终字面子串语义不变。公开 FTS 回归与同库六项查询已验证；内部整数主键不成为输出或分页身份。

## Python 查询边界

Python 导航/结构/调用使用已发布声明、包含、引用和直接继承事实。dispatch 保留 Pyrefly 静态目标和未知分支，不使用 Java CHA；显式 `cha` 和运行时实现枚举返回不支持。构造调用绑定类声明，不声称穷尽运行时 `__new__` / 元类行为。
