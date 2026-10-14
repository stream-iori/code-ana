# 诊断、报告与人工标注

> 对齐 2026-10-14。现行版本与支持范围见 [当前实现](../current-state.md)；以下验收表是需保持的行为约束，未完成能力单独注明。

当前 `doctor` 返回生效 generation、字段来源与能力；报告支持 off/basic/full、诊断计数/分组/样例上限、可比指标差值，导出只写新路径。标注库单独迁移并保存 annotation revision 与事实锚点；list 固定一次标注读取和指定图 revision。真实 javac 诊断已进入图与报告，包含当前冻结项目的 partial 结果；标注进入 typed pipeline、完整诊断/迁移覆盖仍待完成。

**执行、质量、门禁、发布和报告是五个独立结果；人工内容不属于可重建分析事实。**

当前独立 annotations.db 保存正文/版本/锚点；list 固定一次标注读取与所选图 revision，graph 重建不删除正文。

报告、diff、doctor、标注绑定分别使用结构化参数和结果，不根据 JSON 字段推断业务状态。标注绑定只依赖所需图读能力；GC/recovery 返回类型化结果。人工正文、版本、锚点与 snapshot 不因图重建删除。

## 1. 状态模型

| 维度 | 例子 | 不得推导 |
|---|---|---|
| execution | 分析完成 / worker 崩溃 / 取消 | 执行完成不等于门禁通过 |
| assessment | 解析缺口、classpath 缺失、producer 失败 | 放宽 health policy 不会改善质量 |
| gate | 所选策略通过 / 拒绝及 blocking codes | gate 拒绝不等于程序崩溃 |
| publication | 未提交 / 已发布 revision / 冲突 / 保留旧版 | 发布后报告失败不能写成未发布 |
| report | 保存成功 / 写入失败 / 关闭 / 已过期 | 无报告不代表无索引事实 |

对外按本项目独立定义的结构与退出码表达；字段与默认值见现行公共契约，不继承旧文本协议。内部 unknown/interrupted 需通过恢复查明发布结果后再给出确定结论。

## 2. 组件与接口

| 组件 | 输入 | 输出 |
|---|---|---|
| DiagnosticCollector | producer 诊断事件 | 总量、分组、样例、被省略数量 |
| HealthEvaluator | 原始质量事实、policy | assessment + gate，质量本身不变 |
| DoctorService | revision/snapshot、检查范围 | 配置、就绪性、能力、完整性和恢复动作；配置明细按语言/module/world 返回 EffectiveConfig、字段来源与生效 generation |
| ReportStore | 提交身份、阶段状态、计时、诊断 | 版本化内部报告，可选完整文件级诊断基线 |
| ReportReader / compare | 保存的报告 ID | 有界阅读和可比性结果，不重新执行索引 |
| AnnotationStore | 用户文本、来源、锚点、绑定要求 | 独立 annotation ID、内容版本、绑定状态 |

报告里的身份必须来自本次提交返回值，不能在输出时重新读取 active head，否则可能描述另一次发布。计时父子阶段有重叠时不直接求和。

### javac 诊断与执行记录

| 记录 | 要求 |
|---|---|
| 原始诊断 | 保留 code、severity、parse/analyze 阶段、模块/world/source、原始 span、消息及重复次数；无位置诊断归上下文 |
| 绑定证据 | 声明选中、局部证据完整、javac/model/source 恢复来源分别呈现；诊断不机械否定所有绑定 |
| 截断 | 样例截断与编译器诊断收集耗尽分开；后者若影响可靠绑定判断应终止，不能宣称零错误或完整 |
| 失败分类 | 源码错误/模型不完整进入 assessment；编译器不可用、协议损坏、解码超限、崩溃进入 execution failure |
| 执行度量 | 进程 generation、task/attempt/context、输入/发布文件数、classpath 顺序、编码/解码量、签名命中和源码回退原因 |
| 时间与内存 | 子进程单调时间仅同一进程内直接比较；堆池峰值之和不当作同时堆峰值；进程树 RSS 同时采样 |

无诊断、处理器未执行、模型可用、成功编译是不同命题；产品 javac 分析不执行注解处理器，也不出具构建成功证明。

## 3. 输出和报告契约

| 行为 | 约束 |
|---|---|
| stdout / stderr | 数据命令使用本项目文本契约；进度/人工诊断走 stderr；quiet 的实际参数以 CLI help 为准 |
| 报告级别 | off/basic/full 保留含义；内部调度 ID 不强制公开成 run_id |
| 自动报告 | 采用本项目声明的大小/数量预算；独立于语义事实生命周期 |
| 显式导出 | 新路径、禁止覆盖；不参与自动报告 GC |
| 写报告失败 | 返回失败状态/非零退出；同时保留真实 published revision |
| 报告对比 | project/profile/扫描策略满足可比性；缺基线明确失败 |
| 报告过期/格式不支持 | 明确 unavailable/unsupported，不重跑写操作 |

报告字段、预算和退出码用本项目 fixture 冻结；[Anatomist output-contract](../../../anatomist/docs/output-contract.md) 只作设计参考。

## 4. 人工标注模型

```text
Annotation（独立正文与用户来源）
       |
       +-- Anchor：LogicalSymbolKey + 原 revision + world + 位置/内容证据
       |
       +-- Binding：目标 revision + symbol + 状态 + 匹配依据
```

| 状态 | 含义 |
|---|---|
| bound | 已确认绑定到指定语义实例 |
| ambiguous | 多个候选，不自动选一个 |
| orphaned | 声明已消失或缺少可验证对应 |
| unavailable | 所需历史事实不可用，但正文保留 |

重建只更新派生 binding，不删除正文。自动同名/重命名重绑暂不承诺；显式锚点映射也须附依据。跨 world 不能默认共享标注。

后续若将人工标注接入 pipeline，ReadContext 需另固定 annotation revision，避免中途混入用户新编辑；当前仅独立 annotate list 固定读取；图 revision 不被人工编辑重写。历史默认返回针对所选 revision 可证明的绑定，并披露 annotation revision；是否提供标注本身的历史回溯由 D10 决定。

人工标注从本项目创建时独立保存；重建保全验证只针对本项目数据，不实现 Anatomist 标注导入或旧库迁移。

## 5. 验收

| 场景 | 期望 |
|---|---|
| 分析完成但门禁拒绝 | assessment 不变，旧 revision 标为 retained |
| 发布成功后报告磁盘写失败 | 非零退出与 published=true 同时可解释 |
| basic/full 报告对比 | 能力不同正确拒绝，不重跑分析 |
| full rebuild / schema migration | 人工正文与 ID 保留 |
| 符号删除/重命名/多 world 歧义 | 绑定失效可见，不丢内容、不乱绑 |
| 标注 list 与编辑并发 | list 固定一次标注读取；标注接入 pipeline 仍未实现 |

Maven 自动降级时，导出 warning 与索引/报告的 `analysis_mode=source_only`、`classpath_status=unavailable` 对齐，图中的 `CLASSPATH_UNAVAILABLE` 保留模块、POM、可用坐标和脱敏原因。模块级来源 `*` 的缺口影响 semantic evidence；`--strict` 拒绝保留旧 head，普通发布不代表完整语义。
