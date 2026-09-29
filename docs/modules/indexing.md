# 索引编排与增量

> 2026-09-29 实现状态：Rust 产品链路与独立 mock 已落地；具体命令、预算和首版限制见 [Rust 实现说明](../rust-first.md)。本页未验收的 Java/规模化设计仍保留为目标。

当前 Discover 响应可为 BuildContext 或结构化 BuildAdvice；mock 仅按已知输入 hash 回放，不读取 Maven/Gradle 有效模型。`discovery.mode=auto` 索引明确失败，需等待真实发现 provider；FILES 和工作区文本搜索不进入语言发现。

**daemon 决定任务与发布；adapter 决定语言依赖如何失效。增量正确性以同输入全量结果为对照。**

状态：全量/增量职责继承提案；具体计划、owner 替换和重试策略为建议，关联 D05/D07/D09/D11。

## 1. 端口与状态

| 端口 | 输入 | 输出 |
|---|---|---|
| ConfigResolver | project root、用户配置 generation、项目 TOML、显式 CLI overrides | 经校验的配置请求与字段来源；发现实际环境后冻结 EffectiveConfig |
| InputCapture | project、Git/WORKTREE、扫描策略、已有构建产物 | 冻结输入清单与私有路径 |
| Adapter.discover | 捕获的项目文件、配置 | BuildContext、world、所需依赖和资源 |
| BuildAdviceService | adapter 披露的缺失产物与构建上下文 | 给 Agent/人类的执行建议；不启动目标项目编译、不自动重新索引 |
| BaselineReader.export | revision/profile、所需范围 | 足够本轮分析的 BaselineFacts |
| Adapter.analyze | 输入、基线、计划、预算 | 完整 full/delta，或 ReplanNeeded |
| CandidateValidator | 输入身份、结果、基线 | 完整性、引用、coverage 与门禁结论 |
| Publisher.commit | validated candidate、expected revision | committed revision 或 conflict |

```text
PREPARING -> CAPTURING -> PLANNING -> ANALYZING -> RECEIVING
                            ^            |           |
                            +-- REPLAN --+           v
                                               VALIDATING
                                                    |
                                               COMMITTING
                                                    |
                                               PUBLISHED

提交前任意失败/取消 -> 不发布；释放构建引用，候选等待清理
提交后报告/清理失败 -> revision 保留；后处理状态独立
```

## 2. 输入冻结

| 输入 | 捕获要求 |
|---|---|
| 源码、XML、配置、生成源码 | 清单列出包含/排除规则、相对路径、字节 hash、scope/world；分析读取捕获副本 |
| EffectiveConfig | 多语言/module/world 有效值、字段来源、配置版本及语义/捕获摘要；不以整个 TOML hash 代替语义指纹 |
| 依赖 JAR / metadata | 内容摘要与可用状态；分析用不可变副本或已验证的不可变内容对象 |
| JDK / Rust toolchain | 版本、位置身份和实际分析所需产物指纹；执行期间不可变约束可验证 |
| BuildContext | 从已有输入解析模块图、源码根、classpath/crate graph、target/features/cfg；缺信息返回 BuildAdvice，不代执行编译 |
| adapter / plugins | producer、版本、规则、启停和配置进入 profile |

Git commit 可提供树身份；WORKTREE 捕获声明为一次文件清单与字节的冻结集合，不声称 OS 全目录原子快照。捕获期间发现清单/字节变化则重试或失败；严格时点一致的需求另用只读 checkout。捕获后编辑工作区不影响本次分析。

discover 可提出补充输入需求，但必须先由 daemon 捕获并最终封存 manifest 再分析；分析中发现新输入则重新规划，不能悄悄读活工作区。Agent/人类外部编译产生的文件在新请求中先核验并封存再分析；产品不启动目标项目构建。

PREPARING 固定项目配置字节及用户配置 generation；discover 解析 auto 与真实构建图后，PLANNING 冻结 EffectiveConfig 和各语言分析请求。不同 worker 只收到自己语言的配置、世界条件和公共预算，不读取 TOML。配置详情见 [configuration](../configuration.md)。

发现阶段无法取得完整 classpath/生成产物时，返回 BuildAdvice；能分析的部分按覆盖与门禁处理。产品不运行 Maven/Gradle/Cargo 编译、构建脚本或过程宏。外部准备后必须新建任务，重新核验源码、产物和工具链身份；不能将上次未完成分析就地改成已发布结果。

### javac 分析范围与发布范围

编译计划按物理模块 × MAIN/TEST 建立依赖图；SOURCE 标签不改写 Maven 依赖方向。MAIN 不读 TEST，TEST 可读本模块 MAIN，GENERATED 按冻结构建上下文归属。不支持的混合目标版本/JPMS 图及依赖环明确拒绝。发现须排除工具缓存、内部历史 checkout 和 review worktree，避免把副本当模块。

```text
封存的语义输入（可大于改动集合）
    -> javac 上下文 -> 独立 Java 模型 -> 提取 publication sources
    -> 资源/producer 分析 -> 完整 replacement owners -> delta
```

小 delta 不保证小编译上下文。未变事实是项目逻辑视图的一部分，但 BaselineFacts 本身不能作为 javac 的类型输入；P0/P1 先以足够源码上下文保证正确，P2 再评估模块签名投影。必需输入被排除/缺失时明确披露或重规划，不偷偷读取活工作区。

可选签名缓存须保留源码身份、SOURCE 注解和独立声明目录；NoCode 只供索引，不可执行、不证明构建成功。键包含源码、依赖内容及顺序、目标 JDK、编译器桥/模型版本、Lombok 规则/配置。缺签名、冲突或完整性不成立时有界退回封存源码；最终仍须通过 full/delta 等价验收。

## 3. 失效与传播

```text
比较 input manifest / profile
          |
    变更、新增、删除、环境变化
          |
    adapter 计算初始 owner 集
          |
    分析受影响文件并比较声明契约
          |
    成功绑定依赖 + 失败查找桶 + 类型/导入/资源依赖
          |
    扩大受影响集，直到固定点或预算上限
          |
    (baseline - 被替换 owners) + 新事实
          |
    重新绑定与框架资源分析 -> 冻结 delta
```

| 变化 | 处理要求 |
|---|---|
| 方法体修改 | 重算当前 owner 的调用、引用、局部来源；声明契约不变可减少消费者重算 |
| 新增/删除/修改公开声明 | 传播到成功绑定消费者及可能受影响的未解析名称查找 |
| 导入、模块、classpath 顺序 | 可能改变同名解析；不能只比较已绑定 symbol |
| 父类型/接口/Bean 声明变化 | 更新层级、派发依据、Bean 与资源依赖闭包 |
| 删除资源或关闭 producer | 显式替换为空，清除旧关系、依赖、诊断和 coverage |
| 解析/扩展失败 | 丢弃本轮部分输出，清旧 owner；写不完整 coverage，由门禁决定是否发布 |
| toolchain/adapter/规则变化 | 根据能力声明决定失效范围；无法证明局部安全就全量 |
| 仅一种语言的语义配置变化 | 按模块/world 失效；有证明的其他语言事实可复用，新的项目 revision 仍统一原子发布 |
| 语言禁用、模块/world 删除 | 新候选清除相应 owners 并更新 coverage；既有历史保持不可变 |
| 仅报告、输出、GC 配置变化 | 更新相应策略，不单独触发语义事实重建 |

daemon 不自行实现 Java 名称求解或 Rust 宏失效。adapter 负责解释依赖种类并返回闭包；daemon 检查它声明的输入、范围和基线是否自洽。

## 4. 基线不足和发布竞争

| 情况 | 做法 |
|---|---|
| 冷 worker，无 AST | 从固定 revision 导出类型/符号/Bean、依赖/查找桶、必要未变事实及其证据 |
| 不知道最小基线 | P0 先使用足够大的基线验证正确性，再缩小并测量 |
| 分析发现额外需求 | 返回 ReplanNeeded；当前 attempt 不交付可发布 delta；扩范围重跑 |
| 反复扩展或 delta 太大 | 达到明确配置上限后全量；全量仍超预算则失败保旧版 |
| 基线不兼容 / 无输入证据 | 不使用旧事实，转全量或明确失败 |
| 发布时 active revision 已变 | CAS 冲突；重新取得基线重新分析，不自动把旧 delta 套到新版 |
| worker 会话结果未提交 | 不可作为下一次基线；丢弃或保持隔离直到 PublishAck |

## 5. 发布前校验和验收

| 校验 | 失败处理 |
|---|---|
| begin/end、段计数和摘要完整 | 接收失败，旧版本不变 |
| 输入、profile、baseline 与请求一致 | 拒绝身份漂移 |
| 每条事实有合法 owner；替换范围覆盖新事实 | 拒绝遗漏与越界替换 |
| 合并后引用可解释，重复身份无冲突 | 合法 unresolved 保留，不静默删除悬挂事实 |
| diagnostics/coverage 包含失败和零事实 owner | 缺披露拒绝；已披露缺口按健康策略评估 |
| 同输入 full/delta 规范化一致 | 验收失败；不以行数近似代替内容比较 |

规范化比较保留重复调用、诊断总数、coverage 和 provenance 的语义内容，只排除 task ID、耗时、数据库行号等非语义字段。测试必须覆盖删除、重命名、未解析转已解析、插件停用、资源依赖、OOM、中断和发布冲突。
