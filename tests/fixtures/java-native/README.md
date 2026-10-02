# Java 8 fixtures

全部为本项目独立编写，不依赖 Anatomist fixture/输出。

| 输入 | 独立预期 |
|---|---|
| `base/src/p/App.java` | 两次 greet 调用加一次方法引用，均指向 Service.greet；main 包含构造、普通调用、泛型调用和方法引用 |
| 同文件中的 🧪 | 补充平面字符之后的调用范围仍正确映射 UTF-8 |
| Python 测试生成的 missing/ambiguous 源码 | 保留诊断和 unresolved 调用，不出现错误的 confirmed target |
| 模块与 scope fixture | app MAIN 可见 base MAIN，TEST 独立发布；显式 generated 源码有对应输入摘要 |
| 框架 fixture | Lombok 仅声明模型；Spring/SOFA 均不宣称运行时绑定，未知 Bean 名字 partial |
| 故障 fixture | 实际 javac 子进程启动后取消/杀 worker；旧 head 不变、进程清理；RSS 超限失败 |

`java_native_probe.py` 显式编译处理器陷阱，只用于验证索引的 `-proc:none`。`java_maven_export.py` 显式调用 Maven，只验证外部准备工具。产品测试不运行 fixture 的 Maven 编译或处理器。
