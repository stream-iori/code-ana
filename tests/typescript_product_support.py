"""Shared actual native TypeScript fixture; no test cases."""
import json
from prepared_product_support import PreparedHarness


class TypeScriptHarness(PreparedHarness):
    copy_fixture = False
    required_backends = frozenset({'typescript'})

    def setUp(self):
        super().setUp()
        self.script = self.project/'nested'
        self.script.mkdir()
        self.a = self.script/'a.ts'
        self.b = self.script/'b.js'
        self.a.write_bytes('\ufeff/** 库存文档 中文😀 */\r\nexport function greet(value: number) { return value + 1; }\r\n'.encode())
        self.b.write_text('/** 库存调用说明 */\nimport {greet} from "./a";\nexport function exercise() { greet(1); greet(2); }\nexercise();')
        (self.script/'tsconfig.json').write_text(json.dumps({
            'compilerOptions': {'noLib':True,'allowJs':True,'checkJs':True,
                                'module':'ESNext','moduleResolution':'bundler'},
            'include':['*.ts','*.js']}))
        self.config = '''schema_version=1
kind="project"
[resources.index]
worker_memory_bytes=3221225472
[languages.javascript]
enabled=true
[languages.typescript]
enabled=true
[inputs]
[[inputs.entries]]
id="script"
language="typescript"
root="nested"
manifest="tsconfig.json"
[[modules]]
id="app"
language="typescript"
root="nested"
'''
        self.write_config()

    def index(self, *args, **kwargs):
        return self.cli('index', *args, timeout=180, **kwargs)


    def use_ordinary_inputs(self):
        unit=self.catalog()['units'][0]
        self.config='schema_version=1\nkind="project"\n'+(self.project/unit['config']['path']).read_text()
        self.write_config()
        (self.project/'.code-ana/inputs.json').unlink()
        return unit
