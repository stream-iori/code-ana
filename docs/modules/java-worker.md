# Java 适配器与 worker

**复用 Java 的解析和框架能力，移除对 Anatomist CLI、查询、SQLite 发布链路的依赖。**

状态：用户已确认 `lang-adaptor/`、Java 25 + GraalVM、Rust worker 通过 C ABI / FFI 调用 Java 原生库，禁止 JNI（D13）。内存闭环和框架顺序继承提案；ABI 细节与内存组织需 P0 验证，关联 D04/D05/D06。

## 0. 工程与原生边界

| 项目 | 已确定方向 / 实施要求 |
|---|---|
| Java 工程根 | `lang-adaptor/`，独立 Maven 项目；Java 解析、绑定、框架和桥接代码均归此处 |
| 语言与工具链 | Java 25、GraalVM for JDK 25 Native Image；锁定发行版、补丁版本和构建插件，不跟随 latest |
| 构建产物 | Native Image `--shared` 生成的共享库，连同生成 C header、ABI 版本和构建 manifest 分发 |
| 调用端 | `crates/java-worker/` 中的独立 Rust host；daemon 只管理该进程 |
| 互操作 | Java 用 `@CEntryPoint` 导出 C ABI，Rust 通过 FFI 调用；不使用 JNI/JNI Invocation API |
| 与 FFM 的区别 | 本方案是 Rust 调用 Java Native Image 的 C API；不要求 Java 用 `java.lang.foreign` 调用 Rust |

配置入口为 `languages.java` 及 Java module 覆盖，见 [多语言配置](../configuration.md)。adapter 的 Java 25 构建版本固定；`analysis.source_version`、解析 JDK/classpath 是被分析项目的环境。Lombok 直接配置在 `languages.java.lombok`；Spring/SOFA 配置仍归 `languages.java.extensions.*`。Java 模块可通过 `modules.lombok` 覆盖语言级 Lombok 默认值。worker 消费 daemon 冻结的 JavaAnalysisConfig，不在 FFI 会话中重新加载 TOML。

```text
lang-adaptor/ (Java 25)
    -> GraalVM Native Image shared build
    -> libcodeana_java + generated header + ABI manifest
                                  ^
                                  | C ABI / Rust FFI
daemon -- worker IPC --> Rust worker host（独立进程）
```

GraalVM 支持将 Java 编译为可由原生代码调用的共享库；C API 管理 isolate 与线程绑定。API 类型限于原生可表达类型，不能直接返回 Java 对象。[共享库构建与 CEntryPoint](https://www.graalvm.org/jdk25/reference-manual/native-image/guides/build-native-shared-library/)、[Native Image C API](https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/C-API/)。这里的 `isolate` 是同进程执行上下文，崩溃隔离依靠外层 worker 进程。

### FFI 接口草案

函数名称和布局待 P0 冻结；不在本文宣称已有可用 SDK。

| 接口族 | 作用 | 生命周期规则 |
|---|---|---|
| abi_version / capabilities | 装载后检查 ABI、Fact IR、producer 和平台 | 不兼容时不开始分析 |
| session_create / input_chunk / seal_input | 建立会话，分块导入控制输入/基线，再冻结输入 | 输入指针只在本次调用内有效；需保留的数据先复制到 adapter 所属内存 |
| analyze_start | Java 内部启动分析并返回 opaque task handle | 控制调用及时返回，分析在线程中运行，供控制通道继续取消/轮询 |
| poll_event / read_result_chunk | 读取进度、状态、冻结结果 | 只有分析冻结后可取结果块；单块有上限 |
| cancel | 设置协作取消状态 | 明确允许与分析并发；不能依赖长分析函数返回后才执行取消 |
| buffer_release / task_close / session_close | 释放返回缓冲和不再使用的 handle | 谁分配谁释放，所有终止路径都有回收 |
| publish_ack | 告知最终提交 revision | 未确认的会话事实不得复用成新基线 |

| 边界 | 规则 |
|---|---|
| 数据表示 | 固定宽度数值、显式长度的字节缓冲、opaque handle、状态码；不跨边界传 Java 引用或 Rust 所有权对象 |
| 数据编码 | 建议输入/事实块复用版本化 protobuf 消息；基线与结果分块，禁止为整张图反复创建完整序列化副本 |
| 返回缓冲 | Java 桥接层拥有原生缓冲，host 消费/复制后调用释放入口；不得调用 Rust allocator 释放 |
| 线程 | 每个进入 C entry point 的 OS 线程先 attach 到对应 isolate；thread handle 不跨线程复用 |
| 调度 | FFI 控制调用在专用受限线程执行；定义 poll/cancel 并发与 handle 使用规则 |
| 异常 | 可恢复 Java 异常在桥接层转为状态码与诊断；Rust panic 不跨 FFI；致命 native 错误按 worker 退出处理 |
| 退出 | 停止分析，回收 buffer/handle，退出其他附着线程，再销毁 isolate；卡死则 supervisor 终止整个 worker |

请求、结果和错误的内存所有权应进入 ABI 契约测试。共享库、header、Rust binding 与架构/OS 成套匹配，不能只比较 Java 版本字符串。

## 1. 输入与输出

| 输入 | 输出 |
|---|---|
| 捕获源码/资源、classpath、JDK、模块/源码集、Java 版本 | Java 声明、外部符号、调用点、引用、类型/方法关系 |
| Lombok / Spring / SOFA 配置与 producer 版本 | 类型化 facet、Bean/配置关系、生成来源 |
| BaselineFacts、替换范围、固定 revision/profile | 新事实、最终替换范围、依赖、诊断、coverage、完成清单 |
| 分析/传输预算、取消信号 | 分阶段状态或明确失败；不直接发布 revision |

## 2. 内部分析顺序

```text
捕获项目 + BuildContext + 可选 BaselineFacts
                     |
                发现/核对文件与资源
                     |
       JavaParser / SymbolSolver / ASM
                     |
       Lombok AST model -> 声明契约 -> core extractors
                     |
       JavaUnitAnalyzer（Spring / SOFA）
                     |
       内存 Symbol / Type / Bean / Dependency indexes
                     |
       Java 绑定与增量依赖传播，直到稳定
                     |
       DefaultProjectFactView + 共享 XML inventory
                     |
       Spring resource analysis -> SOFA resource analysis
                     |
       校验来源/归属 -> 冻结紧凑事实 -> 完整交付
```

项目级资源分析必须看到完整逻辑视图：全量事实或“基线减替换范围加新事实”。框架生成结果若又影响 Java 绑定，应在该闭环中显式重算到稳定；不能依赖 dump 后由 Rust 补 Java 求解。

## 3. Anatomist 代码复用映射

| 现有位置 | 建议处理 |
|---|---|
| `core/` 的扫描、classpath、parser、身份辅助 | 保留语言相关部分；输入必须接受捕获路径/BuildContext |
| `extract/`、`CallOverloadResolver` | 复用提取与绑定；输出改接内存 fact sink |
| `framework/` SPI、`DefaultProjectFactView` | 保留扩展顺序；fact view 从集合建立，记录复制成本 |
| `framework/lombok/` | 保留签名模型与 modeled/partial/unmodeled 披露 |
| Spring / SOFA 独立模块 | 保留构建期装配和依赖；不引入运行时 JAR 插件加载 |
| `incremental/` | 复用语言契约指纹与失效算法；拆出数据库访问，由基线输入替代 |
| `StagedGraphStore` 的身份、绑定、校验逻辑 | 分类审计：Java 语义移入 worker；通用引用/协议校验移入 Rust；SQL 按新存储端口另行设计 |
| `application/`、CLI、QueryService、JDBC 发布 | 不作为新 worker 的产品链路依赖 |
| 新的 native bridge | 放入 `lang-adaptor/native-bridge/`，只负责 C entry points、生命周期与编码，不包含 daemon/查询逻辑 |

不能直接把整个 `anatomist-core` 打包成最终 worker，它仍混有查询、存储和配置职责。先做依赖清单再移动代码，避免为了去 JDBC 丢失暂存层原有的绑定行为。

## 4. 内存与生命周期

| 对象 | 生命周期 | 约束 |
|---|---|---|
| AST / parser 结构 | 当前解析批次或求解所需窗口 | 确认 SymbolSolver 不再引用后释放；不能只清顶层列表就宣称释放 |
| 符号/类型/Bean 摘要 | 本轮完整分析 | 紧凑存储，避免为每个 view 再复制一份大集合 |
| 基线事实 | 冷启动增量开始到冻结 | 计入 worker 峰值，不能只测本轮新文件 |
| 最终事实 | 冻结至交付结束 | 分块编码、有界缓冲；首期不写 Java 溢出文件 |
| 会话缓存 | 已提交且匹配的 revision/profile | 收到 PublishAck 才可作为复用基线；仍受空闲与内存预算 |

```text
峰值预算至少包含：
worker(AST + 基线 + 索引 + 最终事实 + 编码缓冲)
  + 同一 worker 进程中的 Rust host / FFI 原生缓冲
  + daemon(接收缓冲 + staging + 查询/缓存)
  + 相关子进程与数据库开销
```

超预算时终止本次构建并保留旧版。分块 dump 只降低传输缓冲，不能解决完整分析工作集过大。P0 必须在 Java 25/GraalVM 的实际共享库与 Rust host 上覆盖冷启动增量、反射/资源可达性和依赖兼容；仅 JVM 小夹具通过不足以接受 native 路线。RSS 按整个 worker 进程统计，不能把 Java 和 Rust 子堆算成两个进程后重复求和。

## 5. 故障与验收

| 故障 / 用例 | 预期 |
|---|---|
| 单文件解析失败 | 当前 owner 不保留伪装成最新的旧事实；coverage 披露，门禁独立判断 |
| Spring/SOFA 扩展失败 | 丢弃该 producer 本轮部分结果；依赖它的分析降级或失败，其他事实保持可解释 |
| 缺 classpath / JDK 类型 | 外部缺口明确披露，不能输出“确认无目标” |
| 同 owner 两次相同调用 | 两个 call site，`calls` 可投影为一个方法关系 |
| 删除 XML / producer 停用 | 全量与增量均不残留原关系 |
| 冷启动/热会话/全量 | 同输入 profile 的规范化事实和证据一致 |
| native 崩溃 / OOM / 传输中断 | 无完成清单就不发布，daemon 保留旧 revision |
| 错误 ABI / 平台 / header 与库不匹配 | 分析前拒绝装载/握手，不进行不兼容调用 |
| 多轮分析、取消、异常、释放 | buffer/handle 无泄漏、重复释放或越线程使用；任务退出可回收 isolate |
| Java 异常 / worker fatal error | 可恢复异常转诊断；致命错误只影响该 worker，daemon 继续服务 |

代码复用与框架设计参考见 [framework-plugins](../../../anatomist/docs/framework-plugins.md)。
