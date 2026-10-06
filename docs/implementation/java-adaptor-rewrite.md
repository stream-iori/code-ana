# Java 适配层重写

参考 Anatomist dev/replace-parser@dff2e3954096853352ec91fcbfc6b995fbd4f390。Java保持文件级独立模型、上下文查询、文件级扩展事务；Rust保持捕获、调度、缓存、存储、发布、查询。

| 决定 | 内容 |
|---|---|
| 执行 | SDKMAN Java25/GraalVM native → Rust C ABI worker → SDKMAN Java8 javac；无JNI、处理器或业务编译 |
| 范围 | AST静态声明与调用证据，配置off/model；不扩展compile、lombok.config、Gradle、Linux、目标JDK17/25或独立容量验收 |
| 兼容 | 用户允许破坏性修改，删除旧产品索引和Java派生缓存；保留冻结输入、参考库和失败回执 |
| 新版本 | compiler9/model8/worker12/request6，ABI2/Fact IR1不变，混装拒绝 |

## 实施进度

| 部分 | 状态 |
|---|---|
| JavaContextModel | 文件身份、声明位置句柄、成员索引、显式歧义；不保留全部解码声明 |
| JavaUnitModelExtension | Lombok按文件执行/校验/封存，最后统一提交；失败不留下部分修改 |
| UnitDeclarationRows | 合并原始行与封存overlay，可追踪写入、稳定snapshot、任务租约及缓存回收 |
| 原Lombok路径 | 已删除产品全上下文candidate/original容器；规则持有当前文件，父类通过共享查询读取 |
| 模型导航 | 原声明目录改为位置索引，避免保留所有解码对象 |
| 源码位置 | 按文件惰性读取，4文件缓存；坏编码保持原字节和明确诊断 |
| AST验收入口 | 新增--lombok-ast，两侧均开启；参考保留真实跨文件上下文 |
| JavaSemanticModel | 文件内声明/语法/绑定视图；上下文声明、类型和成员目录只保存位置；跨文件依赖按需读取 |
| 调用/反射pass | 调用恢复按文件新建状态；反射只保留当前文件调用；覆盖关系不物化全部方法 |
| 声明/绑定pass | FactExtractor分离声明与绑定投影，按文件执行；调用点与反射关联限定同一文件 |
| 类型/注解pass | 写明类型的节点目录随文件切换；meta annotation与补充ABI使用位置查询；不保留全部解码声明 |
| JavaUnitAnalyzer | 冻结上下文查询、文件内body、文件输出事务；Lombok/Spring/MVC/Handlers/SOFA注解均已接入，失败文件隔离、拒绝外文件事实；完整回归待最终收口 |
| 规则实现 | 保留本项目已移植的推断与证据规则，文件边界改造不能替代真实语义差分；wire Map仍留在规则输入边界 |
| 产品数据清理 | 默认~/.code-ana不存在，无默认产品catalog可删；新验收采用隔离空home，保留参考库 |
| 完整验收 | 007d79d native模型17项、发行4门禁通过；完整回归、iopengw发布、两套80上下文、14域未完成；尚未替换dist |

## 证据与边界

- 原c5任务38615.869秒后NATIVE_HEAP_EXHAUSTED，app/test@TEST Lombok owner阶段，1GiB native堆；未发布、无异常signal。已越过原core/service commit。
- 文件级新路径重放已保存模型：message-protocol-component约6.63秒；app/test的502MiB模型约9.82秒，512MiB JVM堆。单上下文重放不等于完整产品验收或性能达标。
- Lombok16/Builder14/SuperBuilder8基础回归和版本切换后归档回归通过；参考测试失败保留，修复后新回执才有效。
- JavaUnitModelProbe检查歧义、非法句柄、整体回滚、写入、幂等和租约；旧raw80不能认证AST。
- 文件模型新增64MiB惰性声明目录检查：1200个每次读取附带128KiB payload的声明，跨文件查找、body归属、路由写入与关闭后拒绝均通过；不认证项目容量。
- 48b0ba6 native模型17项对照通过。该提交未包含后续semantic pass改造，不认证最新代码；旧65fe23d真实raw已通过6个上下文、AST已通过5个，均主动停止以等待最终同版本验证。
- 65fe23d及007d79d首次产品启动收到SIGKILL9，代码签名校验有效，系统日志未证明具体杀进程来源。用户已处理，007d79d重试进入compiler_model/事实提取；完整发布尚未完成。

## 移植出处

| 参考类 | 采用逻辑 |
|---|---|
| JavaModel/JavaUnit | 文件边界、上下文引用、找到/歧义/缺失及成员查询；底层使用本项目归档 |
| LombokJavaModelExtension | 待处理文件、文件Overlay、依赖查询、重复处理保护 |
| JavaModelExtensions | 候选验证和失败原子性 |
| JavaModelExtractor/JavaCallFacts | 文件body状态、跨文件声明查询、按文件扩展隔离；事实采用本项目Rust契约 |

参考源码固定commit，直接复制文件须登记SHA和原版权声明。已证明参考缺陷不复制，保留raw差异及独立证明。

## 007d79d 同版本收口

| 项目 | 已执行结果 | 边界 |
|---|---|---|
| JVM模型API / native模型API | 各17项通过，独立native回执无输入/产物漂移 | fixture对照 |
| 文件扩展失败隔离/归属 | 旧树及图模式专项均通过；失败文件回滚、健康文件继续、冷delta相等 | 初次测试路径误写已纠正 |
| JVM高级绑定 | 227项测试成功；581次请求、546次Rust校验 | 汇总输入指纹因测试路径修正漂移，不能作最终总门禁 |
| 发行功能 | 4门禁通过；包内自动启动/索引/calls/source，MAIN/TEST/XML和错误JDK检查；无包/源码/运行时漂移 | 包保留在候选验收目录，最终dist交付仍待真实语义收口 |
| 完整验收 | 另一个007d79d固定副本运行完整回归；本副本执行完整发布及raw/AST各80上下文 | 未闭合52项，未认证容量 |

产品重试与发行回执位于`target/java-alignment/rewrite-007d79d/target/java-alignment/evidence/`；旧SIGKILL回执保留，不能覆盖成成功。

## 内置框架文件事务补齐（2026-10-07）

| 项目 | 结果 |
|---|---|
| 已查证差异 | Anatomist JavaModelExtractor按文件调用全部JavaModelAnalyzer；此前本项目五个内置Java分析回调仍按上下文提交，文件接口仅fixture使用 |
| 改造 | 五个回调采用JavaUnitAnalyzer；当前文件声明/绑定参与规则执行，共享跨文件声明位置与注解元数据查询；XML/DI项目分析保持项目边界 |
| 输出/失败 | 文件级候选校验后提交；失败仅撤销该文件候选、健康文件继续；strict与取消仍终止任务；原有按source生成的事实ID保持 |
| 身份 | registry v14-unit-framework；Lombok13、Spring8、MVC/Handlers/SOFA注解3；compiler/model/wire/ABI不变 |
| 已验证 | 框架14项、扩展20项JVM回归通过；64MiB探针验证20万旧事实不解码、多文件事实与原投影相等、跨文件目标排名及5个文件回调清单 |
| 可观察性 | 调用恢复增加override与当前源码进度；不改变事实或预算 |
| 待验证 | 以上变更晚于007d79d固定验收；不能把旧native/发行/iopengw结果登记为新版成功，需另建同版本固定运行目录 |

007d79d启动重试未再次被杀，已越过Lombok提交；common@MAIN调用恢复耗时较长。系统取样显示计算与归档读取，尚无证据判定死循环或最终成功。
