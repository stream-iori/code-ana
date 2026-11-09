# 单一当前格式

只保留当前读写契约，不迁移、转换或专门回放本产品旧格式。当前数据损坏或版本错误直接报错，不自动删除用户数据。

| 边界 | 唯一路径 | 删除内容 |
|---|---|---|
| 页 | 投影 3、原请求与当前摘要 | legacy_options、注释专用旧格式开关、旧页默认值 |
| 记录 | 带 record_type 的 Record | 无标签 Display 推断、双解码器 |
| 任务 | Progress / Record / Result | Output、持久 protobuf Terminal、旧正文回放 |
| 报告 | schema 3 的全局摘要 | schema 1/2、Full/Basic 兼容枚举 |
| 资源 | workers 列表 | worker 首项别名与缺列表回退 |
| 配置 | Java/Rust 直接序列化 | LanguageWire 及 legacy 字段 |
| Python | 两种导出入口均输出 schema 3 | schema 1/2 读取 |
| Maven | schema 4、显式 dependency_mode 和回执轮次 | 历史缺字段补值 |
| Java key | provider/module/scope/symbol | v22 三段 key 读取 |
| 准备命令 | codeana-cli java-inputs export | Python 包装脚本和独立分发别名 |

版本为 daemon 7、公开投影 3、报告 3；Python 输入 3、Maven 输入 4。worker/graph/ABI/图库契约保持。保留当前校验、reserved 字段号、Java 8/17、外部 XML/JAR 格式及现行 Maven source-only 策略。

实现验收见 docs/current-state.md；任务见 todo/remove-legacy.md。
