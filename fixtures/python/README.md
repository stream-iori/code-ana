# Python fixture 项目

全部使用仓库内源码，测试只静态分析；不安装或运行目标项目。

| 项目 | 固定预期 |
|---|---|
| inference | 无注解工厂、高阶返回、union 多目标、Any 与已知目标并存 |
| semantic-core | alias、直接继承、super、classmethod/staticmethod、嵌套同名函数 |
| imports | 循环 import；缺失目标必须可见 |
| mini-service | dataclass、Protocol、工厂返回具体 repository 的方法 |
| dynamic | Any/getattr/dynamic import/装饰器替换，不能按名称制造确定边 |
| identity-source | UTF-8、非 BMP 字符、不同作用域同名声明 |
| metadata | 可选 pyproject、setuptools 布局、TEST 路径、extra 和动态元数据执行陷阱 |
| diagnostics | 辅助模块缺依赖、循环 import、无关文件完整性及 world 隔离 |

`lang-python-adaptor/tests/` 直接验证固定内核、冻结私有目录及真实 storage。
`tests/python_language_e2e.py` 验证 daemon / CLI、严格模式保留 head、输出超限、Rust/Python 单次发布和保守复用。

`tests/python_inputs_test.py` 验证可选静态元数据导出；核心 fixture 还覆盖赋值目标不记作读取引用、异常别名声明、未知影响保留上下文 partial，以及诊断进入真实图库。

`just test-python` 先运行 Java fixture，再运行 Python fixture 与 CLI。CLI、取消/崩溃恢复、九个探针、Native/JVM 多语言 fixture、隔离分发与冻结 diorama-sdd 范围验证已通过；边界和历史失败见 `docs/current-state.md`。

`preparation` 覆盖两个同名 utils 模块的环境隔离、common 显式依赖、共享环境实际 module 归属和禁止执行目标代码。`tests/prepared_inputs_e2e.py` 同时验证多独立 Cargo/Maven 入口、语义降级/恢复、全局报告及旧 head 保留。
