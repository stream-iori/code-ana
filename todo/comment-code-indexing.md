# 注释索引与代码关联开发计划

状态：注释功能阶段完成。C0—C4 已完成本机 fixture 范围的实现与验收；后续架构整改 A4—A7 继续实施。当前证据见总计划执行记录。

总排期见 [架构整改与注释索引联合开发计划](architecture-and-comments-roadmap.md)。本文保留注释功能的详细规则；架构任务、硬依赖和阶段门禁由总计划维护。实施顺序为：必要架构拆分 → 本文注释功能 → 后续架构整改。

P0 对应总计划 C0，在 A0 基线建立后即可开展；P1 对应 C1，须等待 A2 统一索引编排和 A3 配置与查询接入边界验收。P2—P4 依次对应 C2—C4，不等待后续 A4—A7。P5 改为各阶段持续同步文档，P4/C4 做最终核对。

## 1. 目标与确定规则

- [x] 支持 Java、Rust、Python 标准注释及文档注释的索引。
- [x] 引入 jieba-rs，支持通过中文关键词检索注释，导航到相关代码和所属方法。
- [x] 注释、关联、全文索引随代码 revision 原子发布，支持删除、增量、历史查询和冻结分页。
- [x] 周边代码默认显示**注释开始行之前 5 行、结束行之后 5 行**，包含完整注释；文件边界自动裁剪。
- [x] 展示窗口可以跨方法边界；语义关联仍遵守作用域，不能把窗口中的其他方法误认为注释所属方法。
- [x] 首期支持关键词全文检索；任意子串、错字容错、项目自定义词典及相关性排序留待后续。
- [x] 语义关联要求显式启用对应语言；FILES-only 场景继续使用现有文本搜索。

```text
冻结源码
   ↓
语言侧：注释提取 + 声明归属 + 附近语句关联
   ↓
Rust：统一分词 → 注释表 / 关联表 / FTS5
   ↓
校验 candidate → 原子发布 revision
   ↓
关键词查询
   ├─ 命中注释
   ├─ 上下文代码：默认上下各 5 行
   └─ 所属方法：公开身份、声明位置
```

## 2. 配置与查询接口

### 上下文配置

项目 `.code-ana/config.toml` 新增：

```toml
[query.comments]
context_before = 5
context_after = 5
```

- [x] 两项分别接受 `0..=100` 的整数，缺省均为 5；负数、超范围及未知字段明确报错。
- [x] 查询增加 `--context-before N`、`--context-after N`，分别覆盖配置；显式 `0` 必须与未提供区分。
- [x] 优先级：显式请求参数 > 项目配置 > 内置默认值。
- [x] 注释配置已贯通加载、请求归一化与执行；其他查询默认值按各自现行能力解释。
- [x] 上下文配置只影响展示，不进入语义索引指纹，不触发重新索引。
- [x] 首页查询冻结实际上下文参数；修改配置后续页仍使用原参数。
- [x] 保持已有 `search-text --context` 行为。

### 查询入口

现行查询入口：

```sh
codeana search-comments "库存不足"
codeana search-comments "库存不足" --context-before 3 --context-after 8
codeana search-code "库存不足" --in names,comments
```

- [x] `search-comments` 返回注释、命中证据、源码窗口、可选所属声明及附近语句关联。
- [x] `search-code --in names|comments|names,comments` 默认 `names`，保持已有行为；注释命中投影为可导航声明。
- [x] 文件级或无法关联声明的注释仍可由 `search-comments` 返回。
- [x] 方法结果按实体去重，保留不同注释命中；支持继续进入 `resolve/source`。
- [x] 首期沿用公开 ID 稳定排序；注释结果按路径、位置、world、公开 ID 稳定排序。
- [x] 新的注释查询输入作为普通关键词处理，转义后构造 FTS 表达式；已有名称搜索语法保持。
- [x] 新增类型化请求、结果及能力标签，贯通 CLI、gRPC、pipeline 和分页；旧服务不支持时明确拒绝。

## 3. 提取、存储与生命周期

### 语言提取和关联

| 语言 | 首期规则 | 实现边界 |
|---|---|---|
| Java | Javadoc、行注释、块注释 | 扩展现有 javac 桥及模型；保持目标 Java 8，不引入其他解析后端 |
| Rust | 内外文档注释、普通注释、字面量 `doc` 属性 | 复用 rust-analyzer 语法树；不执行宏或外部文档包含 |
| Python | `#`、标准位置的模块/类/函数 docstring | 复用现有 AST 并补词法提取；普通字符串不作为注释 |

- [x] 分别保存 `documents`、`enclosing_callable`、`leading_statement`、`trailing_statement` 关系。
- [x] 文档归属按语言规则；方法归属按最近有效作用域。
- [x] 行尾注释关联同块同行前一语句；前置注释仅在同块、无空行及其他有效 token 隔断时关联下一语句。
- [x] 位置推断标记 `heuristic`；无法确定时保留文件定位，不猜测。
- [x] 附近语句范围作为关联证据保存；源码展示始终使用配置行数，不自动扩大为整个语句块。
- [x] 文件头、嵌套函数、lambda、局部类、初始化块分别处理；无公开实体时保留范围，不伪造节点。
- [x] 注释提取及关联覆盖缺口独立披露，不能因类型绑定缺失直接丢弃可提取注释；prepared 不可用输入也按冻结 roots/module/scope/world 影响注释完整性，健康路径不受其他输入失败牵连。
- [x] 通过语言词法规则处理字符串伪注释、转义及多行边界，不采用全文件正则抽取。

### 存储与分词

| 对象（建议名称） | 内容 | 职责 |
|---|---|---|
| `graph_comments` | 公开 ID、来源身份、源码 hash、种类、原始范围、正文、分词文本及位置映射 | 保存注释事实 |
| `graph_comment_links` | 注释引用、关系类型、可选声明引用、目标源码范围、关联依据与规则版本 | 保存声明归属及附近语句关联 |
| `comment_terms` | external-content FTS5，指向注释表 | 全文检索 |
| `project_meta` | 分词器、词典、归一化及关联规则版本或摘要 | 复用与兼容性验证 |

- [x] 复用文件字典、节点整数引用和逻辑只读投影；内部整数主键不输出。
- [x] 注释正文只保存一次，不复制到方法 metadata。
- [x] 原始注释及周边代码从捕获 blob 读取；去标记后的正文保留到原始源码的位置映射。
- [x] 新增字节范围使用 UTF-8 半开区间；公开行列遵守现有 UTF-16 契约。
- [x] 注释关联限制在同一 source instance/world 内。
- [x] 注释公开 ID 在固定 revision 内可重现；不承诺位置变化后的跨 revision 身份稳定。
- [x] 普通语句只保存关联范围，首期不持久化完整 AST。
- [x] Rust 侧统一封装 jieba-rs：建索引使用搜索模式，查询使用默认分词模式，HMM 固定开启；查询词默认 AND。
- [x] 英文及代码标识符保留完整词并拆分驼峰、下划线；查询与索引采用同一归一化规则。
- [x] 固定依赖版本、内置词典及分词策略，将其摘要纳入索引身份；不提供运行时热换词典。
- [x] 高亮基于正文位置复核，不使用预分词文本的位置冒充源码位置。
- [x] 分词、记录交付、FTS、候选枚举及源码输出接入既有资源预算和取消机制；不通过静默裁剪伪装完整结果。

### 生命周期与版本

- [x] 新增 GRA1 注释及关联交付契约，接入 staging、引用校验、owner 替换和封存。
- [x] 全量导入后构建 FTS；增量替换同步清理旧记录，封存时验证 FTS 与正文一致。
- [x] 注释独改按源码 hash 更新当前 owner；保持 Java 契约指纹忽略注释，不扩大无关消费者重算。
- [x] 代码独改也重新计算注释归属、位置及附近语句。
- [x] 零注释、删除、改名、语言禁用、world 删除均显式清理旧事实。
- [x] Python 沿用当前变更后全量重算策略。
- [x] 分词及关联规则变化使旧 profile 不可复用；首期要求全量重建。
- [x] 注释与代码在同一 candidate 中发布；取消、失败或 CAS 冲突保留旧 head。
- [x] 历史查询使用固定 revision 的捕获源码；续页不读取当前工作区。
- [x] 注释表随 facts.db 回收，源码继续遵守现有 blob 引用保护。
- [x] 同步升级受影响的图模型、行契约、物理/逻辑 schema、Java 编译器模型和相关版本门禁；C ABI 入口不变则保留 ABI 版本。
- [x] 旧图库按现有规则要求 `index --full`，成功建立新 revision 后切 head。
- [x] DDL 修改生成输入及生成器后重新生成，不直接修改生成的紧凑 DDL；同步导入、打包和覆盖映射。

## 4. 测试与验收

| 测试组 | 必测场景 | 通过标准 |
|---|---|---|
| Java fixture | Javadoc、方法内、行尾、文件头、嵌套作用域、Unicode 转义、字符串伪注释 | 提取范围正确，关联无跨作用域误挂 |
| Rust / Python fixture | 内外文档、嵌套块注释、doc 属性、docstring、普通字符串 | 符合语言规则，不把普通字符串误判为注释 |
| 上下文配置 | 默认 5/5、非对称值、0、100、非法值、文件首尾、多行注释、跨方法窗口 | 返回精确行范围；CLI 覆盖及配置实际生效 |
| 检索 | 中文、混合标识符、标点、重复词、同方法多注释、孤立注释 | 预期召回、稳定去重、证据完整 |
| 位置映射 | 中文、emoji、CRLF、去除注释标记 | 字节范围、UTF-16 行列、高亮一致 |
| 增量 | 注释独改、代码独改、插入空行、删除全部注释、方法删除、文件改名 | 同输入 full/delta 规范化内容一致，无旧词残留 |
| 隔离 | 同文件不同 world、同名方法、模块/scope 过滤 | 无跨上下文串联 |
| 历史与分页 | 查询后改工作区、发布新 revision、修改配置、续页 | 原查询结果及上下文不漂移 |
| 故障与预算 | worker 崩溃、取消、断流、磁盘失败、超大注释、查询截断 | 不发布半成品，旧 head 保留，限制明确披露 |
| 存储 | FK、FTS 完整性、删除清理、旧格式拒绝 | 校验通过，重建要求明确 |
| 配置与规则失效 | 只修改展示配置、升级分词规则、语言禁用、world 删除 | 展示变更不重建语义图；规则升级不复用旧索引；停用后无旧事实 |

执行顺序：

1. [x] `just test-java`，Java 分析及模型 fixture 优先。
2. [x] Rust/Python 注释 fixture、共享存储与查询回归。
3. [x] `just test-rust`、`just test-python`，验证 CLI/RPC/pipeline/config 完整链路。
4. [x] `just test-java-native`、`just test-java-release`、Python 分发及混合语言回归；分发检查遵守原可执行路径恢复和禁止混装约束。
5. [x] 使用本仓库固定的自建 fixture 进行功能验收；建立明确的注释命中及方法定位预期集。
6. [x] 性能仅做一轮：固定输入、空应用状态、预算及产物，记录耗时、进程树峰值内存、图库大小、注释与关联数量、固定查询集结果；保留原失败回执，不区分 Native 冷热。

所有测试记录区分计划、实际执行和验收结论；fixture 或开发构建通过不写成正式放行。不得把历史性能数字作为本功能当前版本的测量结果。

## 5. 交付阶段与完成条件

| 阶段 | 总计划任务 | 前置依赖 | 交付 |
|---|---|---|---|
| P0 | C0 | A0 | 冻结模型、配置和查询契约，建立 Java 预期 fixture |
| P1 | C1 | A2、A3；通过 A3 依赖 C0 | Java 提取 → GRA1 → 存储 → 分词 → 查询闭环 |
| P2 | C2 | C1 | 增量、历史、分页、故障及预算闭环 |
| P3 | C3 | C2 | Rust/Python 标准注释接入 |
| P4 | C4 | C3 | Native、分发、混合语言及自建 fixture 综合验收 |
| P5 | 贯穿 C0—C4 | 随各阶段交付，C4 最终核对 | 同步现行配置、索引、存储、查询、契约和验证文档 |

依赖编号及完成门槛以 [总计划任务表](architecture-and-comments-roadmap.md#2-任务依赖与交付顺序) 为准。P5 不再作为全部代码完成后的独立末尾阶段；本节完成条件保持不变。

完成条件：

- [x] 三种语言均能通过注释关键词找到周边代码和可关联声明。
- [x] 默认上下各 5 行及 TOML/CLI 覆盖均有端到端测试。
- [x] 注释生命周期与代码 revision 一致，全量/增量等价验证通过。
- [x] 查询结果明确区分确定归属与位置推断。
- [x] 所有要求的回归留存真实结果，未完成范围明确列出。

## 6. 实施依据

- [架构](../docs/architecture.md)、[契约](../docs/contracts.md)、[当前实现与验证边界](../docs/current-state.md)。
- [索引生命周期](../docs/modules/indexing.md)、[存储](../docs/modules/storage.md)、[查询](../docs/modules/query.md)、[配置](../docs/configuration.md)。
- [jieba-rs](https://github.com/messense/jieba-rs)、[SQLite FTS5](https://www.sqlite.org/fts5.html)。

开发开始时重新核对代码版本和当前门禁；本文保留完整要求；完成状态以总计划和对应回执为准。

## 7. C0 实施契约（2026-11-01 冻结，C1 已公开接入）

以下为三语言公共链路的实现约束。

| 对象 | 确定形状 |
|---|---|
| CommentFact | 公开 comment ID、source_file/source_hash、module/scope/world、language/provider/producer、kind、UTF-8 原始半开范围、UTF-16 行列、body、body_map、提取规则版本 |
| CommentKind | line / block / doc / doc_attribute / docstring；语言决定对应语法，普通字符串不进入该集合 |
| BodySegment | body_start/body_end/source_start/source_end；全部为 UTF-8 半开区间。正文每段都能回到捕获源码；转义或去标记导致长度不同时，不按线性字节偏移猜测高亮 |
| CommentLink | comment ID、四种关系之一、可选公开 declaration ID、目标源码范围、basis（language_rule / heuristic）、规则版本；目标无公开实体时只存范围 |
| CommentFilter | module/scope/world/path 过滤；不得跨 source instance 连接 |
| CommentQuery | 普通关键词、过滤、可选 context_before/context_after；应用层归一化成实际值，再保存到冻结查询 |
| CommentMatch | 注释身份/位置/正文、命中正文与源码范围、关联证据、捕获源码窗口、可选可导航声明；不暴露内部主键 |
| CommentReader | 固定 revision 的有界候选枚举、批量注释关联读取、冻结输入可用性投影；使用现有 SourceContext/ResourceBudget，不另建查询账本 |

- comment ID 由 producer、语言、module/scope/world、路径、源码 hash、原始范围和种类确定；不含任务 ID、内部主键或时间。
- GRA1 追加 COMMENT/COMMENT_LINK 表族；两类行均须显式 Row.world，不使用空 world 推断注释来源，禁止占用 reserved 6。
- 声明文档和最近 callable 分开记录。方法前的 Javadoc 可以 documents 指向该方法，但不把它当作位于方法体内的注释。
- lambda/初始化块等无公开节点的独立执行作用域阻断外层 callable 归属；保留最近作用域范围，不伪造节点。
- 前置/行尾语句只在相同语句块按既定相邻规则关联；声明不是普通语句，方法前普通块注释不变成 documents。
- search-code 的 comments 入口只投影 documents/enclosing_callable 中存在的公开声明；同一声明保留全部不同注释命中证据。范围关联不单独制造声明命中。
- 空白或仅标点且分词后无词的注释查询返回明确参数错误；重复查询词去重，关键词 AND；不开放 FTS 操作符。
- 展示窗口包含完整注释及配置行数，必须读取 revision 的 blob；普通 source 的当前文件校验行为保持。

### C1 版本切换清单

| 边界 | 冻结基线 → 本轮目标 | 理由 |
|---|---|---|
| graph model / GraphSemantics | 4/26 → 5/27 | 新事实族和能力 |
| worker | 22 → 23 | 新行族及能力校验，拒绝混装 |
| Java analysis request/result | 11 → 12 | 注释输出及 producer 身份同步 |
| 图库物理 / 逻辑 schema | 11/34 → 12/35 | 新表、投影、FTS、校验和增量替换 |
| javac detached model | v50 → v51 | 注释和作用域范围交付 |
| daemon / C ABI / compiler batch / catalog | 4/4/2/2 保持 | 追加能力及字段；既有传输入口、批次封套和 catalog 布局不变 |

版本只在 C1 生产/消费端同时就绪后切换；C0/A1—A3 不先改现行常量。daemon 通过新增 comments-v1 能力拒绝旧服务；新增字段使用未占用字段号。C3 只升级实际语言 producer/提取规则，不再次改变公共 schema。

Java 首组预期输入位于 lang-java-adaptor/fixtures/comment-indexing/Comments.java，expected.json 记录文件头、声明文档、相邻语句、空行阻断、lambda 与字符串伪注释；C1 的 JavacCommentModelIT 已直接读取它验证实际提取/语言关联，公开查询预期已验证；词法专项另覆盖 Unicode 转义、CRLF 与 emoji。内部 javac 模型已升至 v51，匹配开发 Native SDK 已安装，正式分发属 C4。jieba 依赖、内置词典和策略摘要已固定到共享内核，最终分发验证仍待 C4，不接受运行时替换。

C4 验收及预算边界见总计划和 target/todo-implementation/c4/receipt.json。初始默认节点预算失败没有转为通过；最终固定 fixture 使用显式预算，只有一轮正式采样。Native 为匹配源码的 build-only SDK，本机 fixture 通过不等于完整迁移、默认容量或跨平台放行。
