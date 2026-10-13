# Anatomist 紧凑存储实施

当前版本：storage schema 7 / 上游逻辑 schema 33，默认 G1、GRA1/protobuf 行。架构和维护规则见 [存储模块](../modules/storage.md)、[公共契约](../contracts.md) 与 [当前实现](../current-state.md)。参考固定 4a903272c22083585107e982f2899ed03a280aa2；旧协议方案与测试已删除，以下仅保留当前改造的测量及验证边界。

## 紧凑存储实施与唯一一轮验证

采用上游 schema 33 的元注解定义/来源/归属拆分，以及 1,024 项/1 MiB 上下文直接父注解缓存。进一步把调用点的整数归属设计扩展到节点引用、所有文件路径和完整外部目标身份。`graph_*` 是紧凑物理表；同名逻辑视图还原稳定字符串、原始 metadata 和 NULL。该设计属于 code-ana 的 storage schema 7，不宣称与 Anatomist 的物理数据库格式兼容。

节点 UPSERT 保留整数主键；全局路径 NULL 与空字符串分开，保留不同于源节点的边路径。外部字典身份包括 FQN/symbol/language/provider 的完整可空元组。metadata 仅共享至少 256 UTF-8 字节且重复两次的原文本，摘要碰撞逐字核对，不解析或重排 JSON。共享归属删除、状态刷新和孤立字典回收在私有候选事务内完成。源/目标/文件查询计划使用整数索引；FTS 外部内容直接引用物理节点主键。

| 唯一一轮冻结 iopengw（MAIN＋TEST，G1） | 既有正常流程 | 紧凑存储 |
|---|---:|---:|
| 图库字节 | 606,650,368 | **233,488,384（233.49 MB，-61.51%）** |
| 索引命令秒 | 234.879 | **214.278（-8.77%）** |
| 进程树峰值 RSS | 2.856 GiB | **2.699 GiB** |
| 本轮目录逻辑峰值 | 2.69 GiB | **2.35 GiB** |
| MAIN / TEST 节点 | 38,839 / 16,561 | 相同 |
| 元注解逻辑记录 | 56,957 | 相同；341 个定义、3,403 个归属来源 |
| 完整语义摘要 | 12 表 | 与原图库全部相等 |

全局路径 4,317 条、外部目标 7,182 条、共享 metadata 2,688 条。SQLite integrity、外键及 FTS 校验通过，最终无空闲页；既有 854 个 partial 文件保留。旧 schema 6 查询拒绝并提示 `index --full`，显式全量可替换旧格式 head；原文件不迁移。Java protobuf 直写、GRA1 行列、ABI 4、worker 18 和 request 9 保持接口，adapter 改为 `anatomist-4a903272-codeana-graph2` 以拒绝混装。

首次测量准备被采集器拒绝（缺少独立应用状态标记），尚未启动索引，原回执保留。补齐后只运行一次完整索引。索引发布及 12 表摘要成功，随后 `search-code` 发现逻辑视图沿用旧 `nodes.rowid`；原测量失败回执保留。修复只改 FTS 的整数主键到稳定 ID 查询，归档原测量二进制与查询源码，在同一已发布图库回放六项查询通过，图库 SHA 不变，未重跑索引。214.278 秒属于修复前的实际索引流程，不冒充修复后再次测量。

验证包括 Java 636 项组件、Rust 全部门禁（31 项存储，其中新增 5 项紧凑布局）、G1 生命周期 8/8、Native 产品 6/6（含公开 FTS、旧格式拒绝/全量重建）、真实 SIGSEGV、release 打包及图/错误 JDK/自动 daemon 三组验收。原执行路径保留，release 字节临时安装后恢复原 dev 二进制。正式 raw model/AST 迁移验收仍独立，`native_accepted` 和 `performance_accepted` 不据单轮和本轮 fixture 自动改为 true。

原测量：`target/compact-storage-upgrade-01/receipt.json`；同库查询复验：`target/compact-query-recheck-01/receipt.json`；最终汇总：`target/compact-storage-upgrade-01/implementation-receipt.json`。生成 schema 的维护门禁为 `python3 scripts/generate-compact-schema.py --check`，已接入 `just test-rust`。
