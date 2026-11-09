# CLI 完整重构目标与方案

状态：实施依据，CLI 协议 7 已接入；2026-11-09。本文统一 CLI 输入、help、输出编码、目标复用、分页、错误、后台与导出的约定。样例定义交付契约；真实输出及验收范围以 docs/current-state.md 和本轮产物为准。执行任务只维护在 [CLI 重构待办](../todo/cli-implementation-plan.md) 和 [优先重构](../todo/refactor.md)；输出投影细节及验收矩阵见 [输出设计](cli-output-design.md)。

## 1. 重构目标与范围

agent 是唯一用户。交互成本按完成同一任务的 help、输入、输出、补查、续页和重试总量衡量，同时保证目标准确、事实不丢、结果边界可判断。

| 目标 | 可验收结果 |
|---|---|
| 输入少而明确 | 每个命令只接受有效参数；默认值和前置条件准确；无关项明确拒绝 |
| help 能直接指导使用 | 根 help 提供完整能力导航；命令 help 给出合法调用、结果单位、边界和必要样例 |
| 输出没有同义重复 | 一份规范声明、一次必要身份；文件/主体在共同 header 共享 |
| 输出可接续输入 | 声明/现场/快照/任务使用已有公开身份或可重建的规范 selector，不依赖内部主键 |
| 简洁且可验证 | 必要事实和异常始终保留；完整回执有正向结束标记，不能仅凭没有警告判完整 |
| 原文与历史可信 | 保留原字符、关系次数、来源与冻结上下文，不偷换当前/历史 |
| 状态不混淆 | 语义、计算、交付、执行、门禁、提交、报告分别保有真实事实 |

覆盖现有 30 个一级入口、35 条展开业务路径、help/version、pipeline、后台、重放和导出。15 个产品场景与六格保持 [既有定义](cli-application-scenarios.md)。不在本轮新增语言分析能力、修改目标业务源码或自动编译目标项目。六格和 P/C/B/O 编号不进入普通 CLI 回执。

## 2. 统一输入契约

### 2.1 命令、参数和默认值

| 规则 | 最终约定 |
|---|---|
| 二进制和业务入口 | 保留 codeana-cli 与现有 35 条业务路径；只修正参数语义和回执 |
| 参数定义 | 领域拥有真实规则，公开命令契约驱动 help/解析/预检；不因复用 QueryArgs 就暴露全部参数 |
| 无关参数 | 明确拒绝，不静默忽略；参数错误在可本地确定时不提交任务 |
| 作用域 | 项目查询/索引作用于选定项目；daemon 作用于当前 home 的用户级服务与全部任务；task 按任务身份；recover 按当前 home 的存储残留。命令 help 必须说明，不能用无效 --project 假装缩小全局操作范围 |
| 默认值 | 保留现有查询方向、范围、深度、记录/字节/执行预算的业务默认；help 显示有意义的默认值，不显示空字段 |
| 显式与省略 | 解析保留用户是否显式指定，先恢复 cursor/上下文，再应用默认值；不能把省略值变成新的筛选条件 |
| 项目与历史 | 普通请求沿用现行项目/配置解析；显式历史通过 --revision，含 snapshot:`<name>`；不自动回退历史或扩展 scope/world |
| 语言与输入 | 保持显式启用语言的配置要求；FILES/工作区文本能力不强制语义配置。缺输入时交付有依据的准备/BuildAdvice 条件，不自动编译目标项目 |
| 输出格式 | 数据/操作命令 --format text\|json，默认 text；重定向和非 TTY 不自动切 JSON。help/version 固定 text |
| 补充信息 | --detail 只展开命令已定义的诊断/来源补充字段，不转储 SQL/任意 metadata；关键限制无需 detail 也必须出现 |
| 导出 | --export `<new-path>` 保存本次回执；禁止覆盖；不自动翻完后续页 |
| 后台 | 仅实际经 SubmitTask 且声明支持的入口接受 --background；本地 prepare 完成后才返回受理任务身份 |
| 请求幂等 | 保留同 request-id/同规范化请求复用原任务、不同请求拒绝的规则；明确新交付字段的归一化，不因格式、断流或导出失败自动再执行一次写任务 |

--background 与 --export 互斥，避免把受理回执误当作最终结果文件；需要导出任务结果时显式使用 task watch --export，并保留此次观察区间及正文完整性。cursor 续页与 --background 互斥。task watch 可以按其命令契约使用格式与导出参数，不自动补齐已经过期的事件。

--revision、公开任务/快照 ID 等在其业务用途明确时仍可输入。普通查询不输出 revision/producer/profile/manifest/hash；明确历史、比较、歧义或诊断需要的身份按需展示。

### 2.2 修正一参多义

| 命令 | 当前入口语义 | 目标输入 |
|---|---|---|
| diff | --name 选择比较模式 | diff --base before --target after --section files\|declarations\|relations\|impact |
| snapshot | --name 选择 files/analysis | snapshot `<name>` --kind files\|analysis [--target WORKTREE\|`<git-ref>`]；默认 files |
| report | 默认 JSON，另支持 Markdown | report `<id>` 默认 text；--format json 用于测试/结构化导出；Markdown 保留为 report --format markdown --export `<path>` 的显式报告导出 |
| pipeline | 默认读取 JSON 阶段文件 | pipeline `<text-spec>`，每行一个阶段，见第 7 节；产品入口不保留隐式 JSON 文件探测 |

--name 不再作为 diff/snapshot 的模式参数；旧参数明确报参数错误，不静默转义或保留第二套常驻别名。上述目标参数已在协议 7 实现。

### 2.3 help 与错误输入

- 根 help 按查找/阅读、关系/判断、索引/历史、诊断/运行等用途组织全部入口，包含 index、diff、维护与 help。
- 命令 help 只列该命令的有效参数，分为主体/筛选、上下文、交付、执行等必要组；同时说明结果单位、方向、支持范围、副作用和一组可执行形状的例子。
- 所有有效参数都可在相应命令 help 找到，压缩的是无关项和重复解释，不隐藏真实能力。
- help 不启动 daemon、worker 或目标构建；语言静态支持与本机就绪分别表达。

例如调用关系的目标 help：

~~~text
calls <selector> [options]
Static caller/callee relations with call-site locations.

--direction in|out   default: out
--depth N           default: 1

Context: --revision, --module, --scope, --world
Delivery: --max-records, --max-output-bytes, --format, --detail, --export
Execution: --strict, --background, --deadline-seconds, --request-id
Resume: --cursor; selector and filters must be omitted
~~~

此为 help 结构示例；S1 必须逐命令核对全部实际有效项，而不是复制这份参数表给所有命令。calls 不出现 --full、--execute、snapshot/diff 的模式参数。

## 3. 目标引用与输入输出闭环

| 输出对象 | 可复用方式 |
|---|---|
| 程序实体 | 现有规范签名或公开 ID；共享 owner 时可按已定义规则重建完整 selector |
| 调用/引用现场 | 必要时返回现有公开现场 ID；位置仍保留，不靠位置猜唯一身份 |
| 重名/多 world 实体 | 按 module/scope/world 分组并显示必要范围；仍不唯一时显示公开 ID |
| 历史内容 | 显示本次固定来源；ANALYSIS 需要稳定复查时给实际 revision。FILES 显示快照来源/捕获信息，续页绑定冻结捕获 |
| 快照 | 返回已有可操作身份及类型；同名可替换，名称本身不承诺永久指向同一内容 |
| 任务 | 受理后返回真实 task ID；未受理不能伪造任务引用 |

规范 selector 不能为了短而丢参数类型或 world 信息。Java 仅在上下文充分时省 owner 前缀，例如 owner 为 p.Service、成员为 greet(java.lang.String)，可重建 p.Service#greet(java.lang.String)。其他语言遵循各自已有公开编码，不套 Java 语法。

不能任意选择第一个候选：需要唯一起终点/现场的能力必须校验唯一性，允许集合的能力则保留多候选及范围。

### 示例 A：结构输出直接用于读取源码

输入：

~~~sh
codeana-cli structure 'p.Service'
~~~

目标输出：

~~~text
src/p/Service.java
  owner p.Service
    method greet(java.lang.String) -> java.lang.String [public] @6:5–9:6
      parameter-names: name
    method submit(p.Order) -> void [public] @12:5–16:6
      parameter-names: order
end
~~~

后续输入：

~~~sh
codeana-cli source 'p.Service#greet(java.lang.String)'
~~~

如果同一 selector 跨 world 不唯一，输出必须给出可用范围或公开 ID；不强迫 agent 额外读取 metadata 才能确认目标。容器 header 与命中记录须在契约中区分，不能将分组标题计为额外结果。

## 4. 统一 text 回执与状态边界

### 4.1 正文、尾部和默认基线

~~~text
必要共享上下文
  业务正文
必要的例外/实际业务状态
可续页时的结果范围与 cursor
end
~~~

| 部分 | 规则 |
|---|---|
| end | 固定末行，表示当前页/回执已完整交付；不代表后台任务完成、索引已发布或所有事实是运行事实。缺失即不能判回执完整 |
| 正常基线 | 只有编码器已核实适用的语义证据充分、计算完成、当前答案全部交付，才允许不打印这三个维度的例外。收到 end 后才能采用该基线 |
| semantic | partial/unknown 等非充分状态必须显式展示，含必要原因和归属；最终 source 成功不能洗掉上游缺口 |
| computation | limited/取消等非完整计算状态必须显式展示，保留预算/深度等原因 |
| delivery | 有续页打印 page 与实际 cursor；业务正文未完整交付打印 partial/failed，不以拿到任务终态替代正文完整 |
| 业务效果 | index、snapshot、cancel、daemon、export 等主体必须明确受理/实际结果，不能靠 end 或退出码推断 |
| 异常数据 | 原因、次数、省略量和未知项按已定义投影表达；不能为了字节预算删掉关键异常 |

这是已定义基线加例外的压缩编码。内部语义、计算、交付、执行、门禁和提交事实仍独立保存；end 只为回执闭合提供正向凭据。编码器必须验证例外完整呈现，不能通过省略一个实际异常来取得“简洁”。

普通查询不输出协议横幅、默认 Next、重复参数回显或文档分类编号。协议/投影版本由内部请求与冻结结果管理，必要兼容错误才对外说明。

header 中的路径/名称等标量含空白、控制字符、分隔符或保留字时，使用双引号及明确的引号、反斜杠、换行和 Unicode 转义；原文块不做此转义。展示引用与 shell 参数引用分别处理，输出的数据不作为 shell 脚本执行。

### 4.2 源码和正文封装

source、注释和文本窗口使用原文数据块，与状态分开。围栏长度大于正文中最长的同类围栏串，至少三个反引号。正文可以包含看似 end/error 的文本，均按数据处理。

精确封装：位置 header＋围栏行＋原始正文＋一个固定 framing 换行＋闭围栏。闭围栏前的这个固定换行不属于原文；原文自身末尾换行仍保留，因此无末尾换行、CRLF 和空行均可还原。不逐行附行号，不跨非连续区间拼接。

目标输出示例，假定原文以右花括号结束、末尾无换行：

~~~~text
src/p/Service.java @6:5–9:5
```java
public String greet(String name) {
    validate(name);
    return "Hello " + name;
}
```
end
~~~~

长原文允许按真实连续 UTF-8 字符边界分块，各块位置正确；部分交付必须标记，不能把半段源码宣称完整。

### 4.3 stdout、stderr、退出码

| 情形 | stdout | stderr / 退出码 |
|---|---|---|
| 正常业务回执 | 正文、必要状态、end | 默认无进度噪音；0 |
| 已形成业务失败回执 | 保留实际效果与错误，完整收尾 | 不重复业务错误；1，或 strict 3 / timeout 124 / cancelled 130 |
| 语法/可本地确定的参数错误 | 空，不创建任务 | 一次错误码＋原因＋必要短用法；2 |
| 未形成完整回执的异常/断流 | 可能已有部分正文，但没有 end | 一次错误身份；非零。不得事后追加 end 冒充正文齐全 |
| 显式 task watch | 有序业务事件、业务结果和交付边界 | 允许必要阶段变化，不反复输出相同状态/任务 ID |

示例：calls --direction both 的目标错误仅在 stderr 出现一次：

~~~text
INVALID_DIRECTION: calls accepts in/out; received both
~~~

strict 的交付拒绝与原分析质量/发布门禁分别保存。已发布后交付超限可以返回 3，但不能改写原门禁结论或抹掉 published。

## 5. 查询输出与判断依据

各查询族的 header/content/不可丢失项以 [输出投影矩阵](cli-output-design.md) 为准。共同规则：

- 声明只保留一种规范表达；需要原始声明时读取 source，不拼造语言原句。
- 文件、owner、方向等在最小共同 header 共享；renderer 不自行重排、聚合或改变事实单位。
- 方法关系可以去重，调用/引用现场次数、环、meta 路径及证明差异不可丢。
- 派发绑定与静态可能目标分开；分析假设和算法影响结论时保留。
- 索引的 full/delta/reuse 模式默认归 detail/报告；普通回执首先回答实际生效和重要缺口。

### 示例 B：同一目标的多次调用

~~~sh
codeana-cli calls 'p.Service#submit(p.Order)'
~~~

~~~text
p.Service#submit(p.Order)
  src/p/Service.java
    calls
      p.Order#validate()          @14:9
      p.Repository#save(p.Order)  @15:9,18:9
end
~~~

位置属于调用者文件中的调用现场，不是被调用方法的声明位置。一个 save 关系保留两次发生。

### 示例 C：没有命中但语义有缺口

~~~text
semantic: partial
  inputs_unavailable: app-b/MAIN
end
~~~

该回执已完整送达，但不支持“项目不存在相关实现”的判断。若计算也受限，另行打印 computation: limited 及原因，不用一个 incomplete 混写所有维度。

此例假设命令仍能在部分范围求解。完全不具备所需语义能力或语言不支持时，应返回明确错误，不能以空结果替代。

## 6. 冻结分页与最终格式预算

### 6.1 续页最小输入

~~~sh
codeana-cli search-code Order --max-records 2
codeana-cli search-code --cursor '<实际游标>'
~~~

游标绑定原项目、操作、规范化筛选、scope/world/历史来源、排序、投影版本与格式；续页从冻结结果恢复，不重新计算。home 仍由连接入口确定，不跨 home 搜索。

| 续页输入 | 目标行为 |
|---|---|
| selector、筛选、来源、strict、pipeline spec | 不再重复；与 cursor 同时提供时拒绝，不能悄悄覆盖 |
| --project | 可省略，使用游标绑定项目；显式提供时必须匹配 |
| --format、--detail | 可省略，继承；显式提供必须等于原请求 |
| --max-records、--max-output-bytes | 可调整，只改变本页交付大小 |
| --deadline-seconds、--export | 可按本次交付需要指定，不改变被冻结答案 |
| 过期、错操作、旧格式、不匹配游标 | 明确错误，不自动重算或换用当前 head |

### 示例 D：分页

~~~text
src/p/Order.java
  class p.Order @3:1
src/p/OrderRepository.java
  interface p.OrderRepository @3:1
results: 1–2 / 3
delivery: page
cursor: <实际游标>
end
~~~

每页都有足以独立解释的最小 header。结果总数仅在已知时显示，不为展示总数额外全量扫描；受限计算的冻结总数不能冒充项目全量。

### 6.2 预算与排序

预算覆盖本页 header、正文、原文 framing、必要状态、cursor 和 end。公开投影后按最终 text 或 JSON 的实际编码大小计费，分页器与编码器共用大小规则，消除预先按完整 wire JSON 卡住 text 的路径。

先确定业务排序/分组，再冻结和分页；renderer 只共享连续相同上下文，不在页内临时全局重排。路径顺序、事件序号、关系次数按业务契约保持。

只输出完整业务单元。原文和已定义的现场集合可按连续范围/明确集合区间分块；其余原子记录、签名和必要证明超限时明确拒绝，不截半条冒充完整。状态/游标预留依据实际所需长度，不假定固定 4096 足够；没有空间容纳必要回执时给出明确预算错误。

## 7. pipeline 文本输入

产品入口为 pipeline `<text-spec>`。每个非空行是一个查询阶段；阶段使用相应命令的参数规则，--from 声明上一阶段的类型化端点，映射现有 Stage.endpoint。

例如 stages.txt：

~~~text
search-code greet
source --from entity
~~~

~~~sh
codeana-cli pipeline stages.txt
~~~

阶段文件是受限查询规格，不是 shell 脚本；不执行变量、命令替换或任意管道。执行前完成阶段类型和能力校验；仅支持核心查询，search/overview 类只可首阶段，禁止嵌套 pipeline、diff、索引和控制操作。输出最终结果及必要上游缺口，不逐阶段倾倒全量中间结果。测试可直接构造内部类型化 Stage，JSON 不作为默认产品输入。

## 8. 索引、后台、服务、历史与导出

### 示例 E：索引已生效但报告失败

~~~sh
codeana-cli index --prepare
~~~

~~~text
index: published
report: failed
error: REPORT_WRITE_FAILED: permission denied
end
~~~

退出码 1，stderr 不再复制错误。已发布事实保留。正常索引无需默认输出 revision、mode、manifest；必要范围、语义缺口和失败必须展示。detail/报告可以展开相关身份和模式。

--prepare 是本地输入准备阶段，不与 daemon 索引构成单事务；准备失败时明确 index 未启动，准备成功不等于索引已生效。

### 示例 F：后台受理、取消与观察

~~~sh
codeana-cli index --background
~~~

~~~text
task: <实际任务ID>
state: accepted
end
~~~

~~~sh
codeana-cli task cancel '<实际任务ID>'
~~~

~~~text
task: <实际任务ID>
cancel: requested
state: running
end
~~~

需要确认效果时 agent 自行选用 task status/watch。若实际已提交，则状态必须保留 index: published；取消不能冒充回滚。task watch 支持 --after-seq，事件序号与查询 cursor 不通用；事件过期和断流明确处理。重连读取终态不证明正文已交付。

watch 在头部共享 task 身份，各事件标明递增序号和类型，尾部给出可续接的已交付前缀 last-seq，以及流实际观察位置 observed-seq；预算省略正文时不能拿 observed-seq 跳过未交付事件；相同阶段不重复刷屏。新格式从类型化事件编码，不能直接拼接旧 OutputChunk 中的完整回执及 end。导出 watch 时必须标明观察区间，不能将 after-seq 之后的结果称为原任务全量正文。

### 8.1 服务控制

cancel_all 触发 drain，maintenance 在 active=0 后请求退出；CLI 在管理 RPC 之后等待原 PID/启动身份退出，restart 再确认新 generation：

| 命令 | 目标效果与回执 |
|---|---|
| daemon status | 仅返回实际服务状态和必要活动任务信息 |
| daemon drain | 停止准入、让既有任务完成，返回 draining；不能写成已退出 |
| daemon stop | 排空、请求取消、等待已开始的提交和进程退出；确认后返回 stopped |
| daemon restart | 排空并等待原服务退出，再启动/连接并核对新实例，不能只发请求就报成功 |

stop/restart 使用 --deadline-seconds，管理等待默认沿用当前 restart 的 10 秒窗口，并在 help 明示；不改变分析任务的现有 deadline 默认。超时返回仍在 draining/未确认退出的事实及 124，不盲杀其他进程、不虚构 stopped。

### 示例 G：快照与比较

~~~sh
codeana-cli snapshot before --kind analysis
codeana-cli diff --base before --target after --section declarations
~~~

~~~text
diff: declarations
base: before (analysis <固定 revision>)
target: after (analysis <固定 revision>)
world: <实际 world>
  declaration p.Service#submit(p.Order) -> void [public] @12:5–16:6 count:0→1
    parent: <所属类型的公开 ID>
    parameter-names: order
end
~~~

快照同名可替换，比较执行时固定双方实际端点；analysis 使用实际 revision，files 使用捕获 manifest 身份，跨页和 watch 保持原值。analysis 快照如生成并发布索引，其实际生效结果必须说明。files 快照不暗示具有语义图。diff files 不伪造文本 patch。impact 仅从变更关系涉及的声明/外部目标做反向调用传播，明确 relation_change_roots_only 和 static_possible_impact；不是源码、数据流或实际运行影响。--depth 仅用于 impact，未实现的 --world/--module/--scope 筛选明确拒绝。

### 8.2 导出与 JSON

- --export 保存本次响应，包括限制与 cursor；不自动取后续页。输出到新文件，先校验目标，再采用原子、不覆盖的完成方式处理竞争；失败不留下冒充完整结果的目标文件。
- 指定 export 时 stdout 只给导出回执和必要原业务状态，不再复制整份正文。写出失败时保留已经发生的索引/引用效果。
- --format json 使用同一公开业务投影，输出一份标准 JSON 文档，含记录、独立状态与交付信息；不带 record 前缀、text 围栏或 end。watch 的 events 可增量编码进同一文档，结束时闭合并给出交付结果，不要求装载全部事件。
- JSON 仅用于显式测试和结构化导出；JSON 文档不完整同样不能判交付完成。JSON 模式的 stdout 导出回执也为结构化文档，不混入 text。
- report 的 Markdown 只在显式 --format markdown --export 时生成文件，stdout 给 text 导出回执。
- java-inputs export --output 指向输入准备领域的产物，不等于普通响应的 --export；分别按其产物更新规则和回复不覆盖规则处理，help 必须说清。

例子：

~~~sh
codeana-cli search-code Order --export order-results.txt
~~~

~~~text
exported: order-results.txt
end
~~~

要结构化文件时显式使用 --format json --export order-results.json。保存成功不证明原查询语义或枚举完整，原响应的限制仍必须出现在文件和必要的导出回执中。

## 9. 全命令覆盖

下面列出全部 35 条业务路径的目标主要接口/结果，完整参数白名单由 S1 逐命令核对领域规则并固化；没有列在某命令契约中的参数不得被静默接受。上下文、交付与执行组选项仅在实际适用时开放。

| 命令路径 | 目标主要接口与结果 |
|---|---|
| java-inputs export | --output 与各原生准备选项；产物/降级及失败回执 |
| hello | [name]；实际连通性响应 |
| daemon status | 实际状态与活动任务信息 |
| daemon drain | 排空受理与当前状态 |
| daemon stop | 等待并确认原服务退出，超时不报 stopped |
| daemon restart | 等待原服务退出并确认新服务 |
| task status | `<id>`；当前/终态、独立效果与交付范围 |
| task cancel | `<id>`；取消请求与实际已知状态 |
| task watch | `<id>` [--after-seq N]；有序事件和完整交付边界 |
| index | [--prepare] [--full]；质量、门禁、实际发布与失败独立 |
| doctor | 配置/输入/工具链的就绪与未知；不自动构建 |
| resolve | `<selector>`；精确身份或候选、规范声明与必要属性 |
| search-code | `<terms>` [--in names\|comments\|names,comments]；实体和匹配依据 |
| search-comments | `<terms>`；注释、命中与真实声明链接 |
| structure | `<selector>`；包含结构与必要位置 |
| calls | `<selector>` [--direction in\|out]；方法关系与现场集合 |
| call-sites | `<selector>`；每次调用与目标/缺口 |
| references | `<selector>`；引用/读写/类型/配置关系与 occurrence |
| source | `<selector>`；当前或明确历史来源的连续原文 |
| annotations | `<selector>` [--meta]；直接注解或不同 meta 路径 |
| type-relations | `<selector>` [--transitive]；类型关系、方向、环与依据 |
| callable-relations | `<selector>` [--transitive]；方法层级与依据 |
| runtime-implementations | `<selector>` [--instantiability yes\|unknown\|all]；候选与层级依据 |
| dispatch | `<call-site>` [--algorithm auto\|exact\|cha]；绑定、候选与分析假设 |
| trace | `<from>` --target `<to>`；一条有序路径及逐跳依据 |
| overview | [--section project\|modules\|packages\|dependencies]；明确口径的聚合 |
| search-text | `<pattern>` [--regex] [--ignore-case] [--path] [--context N]；窗口与命中 |
| pipeline | `<text-spec>`；类型化阶段组合，只输出最终结果与上游缺口 |
| snapshot | `<name>` [--kind files\|analysis] [--target WORKTREE\|`<git-ref>`]；内容与引用实际结果 |
| snapshot-list | 已有快照目录和可操作身份 |
| snapshot-remove | `<name>`；实际解除引用，不宣称内容已回收 |
| diff | --base `<snapshot>` --target `<snapshot>` [--section files\|declarations\|relations\|impact]；固定端点差异 |
| report | `<id>` [--base `<id>`]；原任务解释或可比指标差异 |
| gc | [--execute]；默认预览，实际保护和回收效果 |
| recover | [--execute]；默认预览，活动任务约束与存储残留处置 |

help/version、显式导出、cursor 续页与 task watch 重放另按对应章节覆盖。命令名保留不等于旧的无关参数继续有效。

## 10. DDD 与实现职责

~~~text
命令专属参数 / 文本 pipeline
             ↓
输入标准化与能力契约
             ↓
业务请求 + 交付选项 + 执行选项
             ↓
应用用例路由 → 构建 / 查询 / 历史 / 维护 / 就绪 / 报告
             ↓
类型化业务结果与独立状态
             ↓
公开投影 → 冻结/分页与大小核算 → text / JSON 编码
             ↓
CLI stdout / stderr / 导出文件
~~~

| 位置 | 调整职责 |
|---|---|
| cli | 命令专属 args、文本规格、默认值显式性、help、通道与导出/服务等待；不新增语言绑定规则 |
| model/query | 保留业务类型和领域校验；typed endpoint、语义/计算证据；不读取展示 JSON 决定业务 |
| application 交互模块 | 公开能力、结果投影、编码器和回执封装；CLI 本地准备与 daemon 任务复用相同规则 |
| application 用例层 | 通用操作路由移出 QueryService；各服务保有所属规则，提交/控制/报告分别协调 |
| history/storage | 保存冻结规范记录、原请求、排序/投影/格式版本及保护引用；恢复和续页不换上下文 |
| contracts/daemon | 交付格式与显式输入信息进入版本化接入契约，不混进语言 worker 请求 |

先在现有 crate 内收紧模块与端口，不因六格拆六个服务。发布点的 head/revision/task commit 事务、引用保护、取消/超时不能撤销实际提交等约束保持。

## 11. 版本与旧产物处理

- 新 CLI/daemon 交付契约增加格式与恢复原请求所需信息，daemon 协议已由 5 升为 6；混装在握手拒绝。
- worker 24、graph model 6、analysis 13、C ABI 4 与图库物理/逻辑 schema 13/36 不因展示重构改变；若实现触及其实际契约，另行列明变更和必要验证。
- 旧 text-v1 不作为新默认输出模式长期保留；测试与工具改为显式 JSON 或新版 text。help 明确参数变化。
- 旧 cursor 的投影/排序版本无法安全复用时明确拒绝，不自动重算；不为显示升级要求重建已有兼容图库。
- 任务只读取当前内部结果与类型化事件；删除旧正文和 protobuf 持久终态读取路径。
- 显式存储 projection/version，不将版本、内部主键或 producer 批量打印到正常回执。

## 12. 实施顺序与放行条件

| 阶段 | 交付 | 主要位置 | 验收门槛 |
|---|---|---|---|
| S0 基线与接口清单 | 35 路径的现状/目标、逐命令有效参数、输出单位、版本与测试清单 | cli、场景/契约文档、测试工具 | 命令不漏；现状与目标分开；记录原失败而非提前宣称通过 |
| S1 输入与 help | 专属 args、标准化、真实默认值、目标参数名、文本 pipeline、交付接入契约 | cli、model/query、contracts/daemon | help 可构造有效请求；无关参数拒绝；能力/就绪不混写；新版本握手通过 |
| S2 规范公开投影 | 查询/操作类型化公开结果、目标引用、范围与必要扩展 | model、query、application、storage read mapping | 无 display/metadata 多份表达；目标可复用；关系/现场/证明与未知不丢 |
| S3 编码与回执 | text/JSON、原文 framing、end、基线/例外、通道、原子导出 | application 交互模块、cli | 无重复错误；无末尾换行/围栏/多字节可还原；错误与实际效果一致 |
| S4 冻结分页与预算 | cursor 恢复原请求、共享大小核算、分块、稳定排序、必要尾部预留 | history/pages、分页器、编码器 | 无需重复长请求；跨页无漏重；text 不先受 wire JSON 限制；旧页明确拒绝 |
| S5 路由与生命周期 | 明确用例路由、窄端口、prepare/后台、watch/重连、stop/restart 确认 | TaskExecution/QueryService/TaskService、daemon/cli | 35 路径保持；已提交与交付失败并存可解释；停服超时不伪报成功 |
| S6 全场景回归 | Java fixture 优先，随后对应 Rust/Python、架构门禁、JSON 断言与 text golden | 自建 fixture、tests、scripts | 15 场景/六格/31 能力及正常/异常/历史/预算/取消全部受测；旧消费者处理明确 |
| S7 任务效果与交付 | 固定 agent 任务比较 token/调用/重试/误判，更新现行 docs/覆盖/打包 | 产品任务集、docs、分发脚本 | 无新增关键误判；正常目标导航不多一次补查；证明成本改善；必要包验证完成 |

S0→S1→S2→S3→S4 是核心依赖链；S5 在类型化结果稳定后承接路由/运行，最终统一进入 S6/S7。R1 对应 S1，R2 对应 S2–S4，R3 对应 S5；任务状态仅在 todo 更新，不在设计中维护完成勾选。

验收采用本仓库自建 fixture，遵守 Java 优先与正式 Native/分发要求。正常查询、明确历史、歧义、空结果、语义缺口、预算耗尽、断流、重放、取消提交边界及导出失败均需覆盖。全任务成本必须包含 help/补查/重试；本次不执行性能测量，不用旧回执签新重构放行。

## 13. 当前实现依据

[命令能力契约](../crates/application/src/input_contract.rs)、[CLI 参数](../crates/cli/src/command_contract.rs)、[pipeline 文本输入](../crates/cli/src/pipeline.rs)、[query 预检](../crates/query/src/lib.rs)、[Stage](../crates/model/src/lib.rs)、[公开 wire 编码](../crates/model/src/records.rs)、[SQL attributes](../crates/storage/src/graph_query.rs)、[当前输出预算](../crates/application/src/output.rs)、[冻结页](../crates/application/src/history.rs)、[任务取消/排空/退出](../crates/application/src/task_runner.rs)、[实际提交](../crates/storage/src/staged.rs)、[架构](../docs/architecture.md)、[公共契约](../docs/contracts.md)。实现状态与回归记录见 [当前实现](../docs/current-state.md)。旧实现问题作为重构基线，不再描述为当前行为。
