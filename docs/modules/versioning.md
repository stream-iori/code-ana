# 版本、源码、diff 与 GC

> 对齐 2026-10-13。现行版本与支持范围见 [当前实现](../current-state.md)；以下验收表是需保持的行为约束，未完成能力单独注明。

FILES 快照现在允许无语言配置，并保存本次 EffectiveConfig/捕获策略；`search-text` 可独立搜索工作区 `text_only`，不改变已存历史。Git 输出在读取时按字节上限截断。files/declarations/relations/impact diff 已接入，冻结结果与双端引用保护保持；不做重命名推断，完整语义对应仍受 producer 证据限制。

**历史由冻结输入和不可变结果决定，续页不能重新比较当前工作区。**

当前 files/analysis、冻结 diff、源码 blob 与引用保护已实现；D03/D10/D11 的未完成能力仍按实际支持范围披露。

## 1. 对象关系与端口

```text
named head ----> SnapshotId
                    |
                    +---- FILES -----> manifest -----> source blobs
                    |
                    +---- ANALYSIS --> RevisionId ---> facts.db
                                              |
                                           manifest

ComparisonResultId ----> base snapshot + target snapshot + rows + evidence
                            ^                   ^
                       引用保护端点与源码，直到结果过期且无读者
```

| 端口 | 输入 | 输出 |
|---|---|---|
| capture_files | ref/WORKTREE、捕获策略 | FILES snapshot，不需要 Maven 或语义图 |
| prepare_analysis | 已捕获输入、profile/请求要求 | ANALYSIS snapshot，委托 indexing 构建 |
| open_snapshot | exact ID 或已解析 selector | 固定身份、能力与只读句柄 |
| read_source | 声明/site/file、范围、预算 | 内容 hash 校验后的源码页 |
| compare / continue_comparison | 两端、能力/过滤/预算；结果 ID+cursor | 固定比较结果及同一份证据 |
| collect / recover | 保留策略、执行/预览 | 受保护对象、可删集合、恢复与存储结果 |

## 2. 捕获与源码

| 场景 | 约束 |
|---|---|
| Git ref | 先解析并冻结 commit；后续 branch 移动不改变本次端点 |
| WORKTREE | 捕获当次字节与清单；未提交内容不会凭 Git 自动恢复 |
| linked worktree | 历史按 repo/project 共享；活动工作区身份独立 |
| files-only | 只捕获既定政策包含的文件；不触发构建或解析 |
| MAIN / TEST | 捕获范围与查询选择分开；未捕获 TEST 不能解释为无测试变更 |
| 自动缓存选择 | 校验请求、profile、捕获策略和所需依赖身份 |
| 精确历史 ID | 兼容格式不依赖当前 classpath、工作区或 branch 存在；旧 graph 格式明确拒绝 |
| 准备历史语义快照但缺构建产物 | 返回指定 commit/profile 的外部准备建议；不能自动编译该提交，也不能借用无法证明匹配的当前工作区产物 |
| 历史 source | 只读捕获 blob，校验 hash；不回退当前文件 |
| 普通活动索引 source | 用户已确认校验当前文件/hash；不匹配报错，不以冻结源码替代当前源码返回 |

source 行为已由 D15 确认：工作区变更需重新索引或显式选择历史 revision；历史读取使用冻结 blob。两种入口均验证内容 hash。

源码行页保留空行，完整声明需连续读至结束。生成声明优先返回真实输入来源；生成文本明确标记，无法获得内容时返回限制。

## 3. diff 能力

| 能力 | 依赖 | 结果与边界 |
|---|---|---|
| files | 两端冻结 manifest/blob | 文件文本变化；捕获政策不同须披露 |
| declarations | 两端声明事实 | 声明对应及变化；重命名推断需附依据和不确定性 |
| relations | 两端关系/调用点 | 规范化关系多重集比较，不按 rowid 或含位置 hash 直接判定 |
| impact | relations + 双端调用遍历/派发依据 | 静态可能影响；按每端深度/实体/状态预算披露 |

文本、声明、关系、影响各自有 completeness、limits、reasons 与否定判断。环境变化可以限制关系比较，不能无条件抹掉仍成立的文件文本证据。`--section` 和 `--view` 只改变展示，不偷偷改变分析范围。

```text
resolve base/target -> 准备所需快照 -> 取得双方读者引用
          -> 文本/声明/关系/影响计算
          -> 冻结 rows + evidence + options + endpoints
          -> 登记 ComparisonResultId
          -> 首屏 / next(result ID, cursor)

工作区变化 -------- 不影响已有结果 --------> 续页
结果过期 -------------------------------> 明确失败，不重算
```

impact 用双方捕获图分别遍历；展示 scope 不截断中间路径。可能派发边带依据，不能把这些候选写成关系变更。预算、默认值和选择规则在本项目的 fixture 中冻结，不要求与其他产品相同。

## 4. GC 与恢复

| 引用根 | 保护内容 |
|---|---|
| 活动入口、有效命名入口 | revision / snapshot / manifest / blobs |
| pinned snapshot | 固定历史全体必要对象 |
| 活跃 reader | 正在读取的图、源码、比较结果 |
| build attempt | 固定基线、捕获输入、候选对象 |
| 未过期 comparison | 双端 snapshot、结果行、证据和源码 |

```text
取得维护协调权 -> 处理确定归属的中断记录
               -> 遍历保护根 -> 计算不可达对象
               -> 预览 / 执行前重新确认引用 -> 删除 -> 统计
```

| 情况 | 行为 |
|---|---|
| 容量不足但对象均受保护 | 报告预算未满足，不强删固定/活动对象 |
| 中断构建工作目录 | 校验归属再清理；无归属目录保留并报告 |
| 孤儿分析子进程 | 只恢复本产品启动的 worker；不接管或终止 Agent/人类运行的目标项目编译进程 |
| 发布后清理失败 | pending 状态；保持成功发布，后续恢复 |
| 只读快照请求 | 不准备新索引、不触发自动 GC；命令参数由本项目定义 |
| GC preview | 只报告计划与恢复需求，不执行删除或杀进程 |

图 GC 不删除人工标注正文。重建或历史删除造成标注绑定不可用，交 operations 记录状态。

## 5. 验收

重点场景：构建后改工作区再读历史；diff 首屏后切分支再续页；files 模式无 Maven；TEST 未捕获；不同 profile 对比；读者与 GC 竞争；发布前后崩溃；孤儿工作目录和子进程恢复；容量不足但所有对象被固定。

设计参考：[Anatomist 版本管理](../../../anatomist/docs/git-snapshots.md) 与 [文本搜索](../../../anatomist/docs/text-search.md)。不读取或迁移其历史库。

## javac 环境与历史

历史语义 profile 保存 javac 后端、独立 Java 模型/编译器桥版本、目标 JDK 内容身份、有序依赖和 Lombok 模型配置。读取已发布历史不需要当前 JDK；创建或重建历史分析才验证工具链与封存输入。签名缓存命中不替代历史输入证据，也不保证缓存永久存在。

不同绑定来源或证据完整性的变化必须进入语义 diff；不能为缩小差异而忽略诊断/未解析调用。线程数、编译器堆和回收策略是执行记录；若改变语义恢复预算，则进入 profile 与可比性判断。

## 图格式升级边界

历史文件保留与读者兼容是两件事。当前仅打开物理 schema 10 的图；schema 9 及更旧查询/delta 返回 GRAPH_SCHEMA_REBUILD_REQUIRED。index --full 创建新图并在成功后切换 head，不原地升级旧 revision。已经打开的同格式 revision 仍受 reader 引用保护；FILES/源码捕获与人工标注生命周期独立。
