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
| 完整pass重写 | 未完成，现有分析pass仍待进一步接入类型化模型 |
| 产品数据清理 | 待定位实际catalog；不得删除验收参考库 |
| 完整验收 | 新同版本native/daemon、iopengw、两套80上下文、14域和发行均待完成 |

## 证据与边界

- 原c5任务38615.869秒后NATIVE_HEAP_EXHAUSTED，app/test@TEST Lombok owner阶段，1GiB native堆；未发布、无异常signal。已越过原core/service commit。
- 文件级新路径重放已保存模型：message-protocol-component约6.63秒；app/test的502MiB模型约9.82秒，512MiB JVM堆。单上下文重放不等于完整产品验收或性能达标。
- Lombok16/Builder14/SuperBuilder8基础回归和版本切换后归档回归通过；参考测试失败保留，修复后新回执才有效。
- JavaUnitModelProbe检查歧义、非法句柄、整体回滚、写入、幂等和租约；旧raw80不能认证AST。

## 移植出处

| 参考类 | 采用逻辑 |
|---|---|
| JavaModel/JavaUnit | 文件边界、上下文引用、找到/歧义/缺失及成员查询；底层使用本项目归档 |
| LombokJavaModelExtension | 待处理文件、文件Overlay、依赖查询、重复处理保护 |
| JavaModelExtensions | 候选验证和失败原子性 |
| JavaModelExtractor | 文件结果封存释放；完整pass迁移待完成 |

参考源码固定commit，直接复制文件须登记SHA和原版权声明。已证明参考缺陷不复制，保留raw差异及独立证明。
