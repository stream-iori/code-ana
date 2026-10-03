#!/usr/bin/env python3
"""Maven diagnostic contexts must preserve physical roots and mediated scope."""
import json
import pathlib
import tempfile
import unittest
from java_real_compiler_diagnostics import compiler_context


class DiagnosticInputs(unittest.TestCase):
    def test_module_roots_and_test_dependencies_do_not_expand_dependency_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            for path in ('app/main/App.java','app/test/AppTest.java','lib/main/Api.java','lib/test/Bad.java','unused/main/Other.java'):
                source=root/path;source.parent.mkdir(parents=True,exist_ok=True);source.write_text('class Example {}')
            modules=[dict(id=name,root=name,source_roots=[dict(path='main',scope='MAIN'),dict(path='test',scope='TEST')],
                resolved_classpath=True,dependencies=dict(MAIN=[],TEST=['lib'] if name=='app' else []),
                classpath=dict(MAIN=[],TEST=[])) for name in ('app','lib','unused')]
            context=compiler_context(root,dict(modules=modules,scopes=['MAIN','TEST']),'app','TEST')
            self.assertEqual({s['path'] for s in context['sources']},{'app/main/App.java','app/test/AppTest.java','lib/main/Api.java'})
            self.assertEqual([s['path'] for s in context['sources'] if s['publish']],['app/test/AppTest.java'])
            modules[0]['source_roots'].append(dict(path='../../outside',scope='MAIN'))
            with self.assertRaisesRegex(ValueError,'OUTSIDE_FROZEN_PROJECT'):
                compiler_context(root,dict(modules=modules,scopes=['TEST']),'app','TEST')


if __name__=='__main__':unittest.main(verbosity=2)
