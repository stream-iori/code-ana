# 查询与 typed pipeline

**查询从固定版本取事实，输出关系及其证据边界。下游成功不能洗掉上游缺口。**

状态：调用点、三轴证据和有界查询继承提案；本项目独立定义 CLI/pipeline 输出契约，无旧版兼容。端口、语言查询策略为 D06 建议。

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

跨请求续页建议显式携带 expected revision：活动入口变化时拒绝，不默默换版或改读其他历史版本。单条 pipeline 内只选择一次身份；diff 的上下文含两个固定端点。

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

Rust 中的 Java 策略负责解释已解析类型关系和静态派发规则，不重新运行 SymbolSolver。P0 若发现现有 Java 查询需要 worker 未输出的信息，先补事实契约；若仍无法表达，应重新评审 D06，不能隐式启动分析弥补。

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

公开布局、默认限制和退出码在 P0 以本项目 fixture/golden 冻结。[Anatomist 输出契约](../../../anatomist/docs/output-contract.md) 仅供设计参考，无兼容验收要求。
