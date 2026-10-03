#!/usr/bin/env python3
import copy
import json
import pathlib
import struct
import tempfile
import unittest
from java_model_api_inventory_diff import archive_inventory, invalid_sources, projections, validate_inputs
from java_real_acceptance import digest
from java_real_compiler_diagnostics import compiler_context


class ModelInventoryGuards(unittest.TestCase):
    def test_bad_utf8_keeps_bytes_and_requires_exact_failure_without_semantics(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary);source=root/'Broken.java';source.write_bytes(b'class Broken {\xff}')
            request=dict(sources=[dict(path='Broken.java',input=str(source),hash=digest(source),publish=True)])
            invalid=invalid_sources(request);self.assertEqual(source.read_bytes(),b'class Broken {\xff}')
            diagnostic=dict(code='SOURCE_ENCODING_INVALID',source='Broken.java',sha256=digest(source),severity='ERROR',
                phase='input_decode',encoding='UTF-8',start=-1,end=-1)
            row=dict(kind='row',section='diagnostics',value=diagnostic)
            path=root/'model.bin'
            def write(rows):
                with path.open('wb') as out:
                    out.write(b'CAF1')
                    for value in rows:
                        body=json.dumps(value).encode();out.write(struct.pack('>I',len(body)));out.write(body)
            write([row]);self.assertEqual(len(archive_inventory(path,set(),invalid)),1)
            for rows,reason in [([], 'MISSING_OR_DUPLICATE'),([row,row], 'MISSING_OR_DUPLICATE'),
                ([dict(row,value=dict(diagnostic,sha256='changed'))], 'DIAGNOSTIC_INVALID'),
                ([row,dict(kind='row',section='syntax',value=dict(source='Broken.java'))], 'HAS_SEMANTICS')]:
                write(rows)
                with self.subTest(reason=reason),self.assertRaisesRegex(ValueError,reason):archive_inventory(path,set(),invalid)

    def test_original_scope_source_order_and_byte_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            for name in ('main/Api.java','test/Use.java'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('class A {}')
            manifest=dict(scopes=['MAIN','TEST'],modules=[dict(id='app',root='.',resolved_classpath=True,
                dependencies=dict(MAIN=[],TEST=[]),classpath=dict(MAIN=[],TEST=[]),
                source_roots=[dict(path='main',scope='MAIN'),dict(path='test',scope='TEST')])])
            request=compiler_context(root,manifest,'app','TEST');validate_inputs(root,manifest,request)
            altered=copy.deepcopy(request);altered['sources'][0]['publish']=True
            with self.assertRaisesRegex(ValueError,'SOURCE_ORDER_OR_SCOPE'):validate_inputs(root,manifest,altered)
            altered=copy.deepcopy(request);altered['sources'].reverse()
            with self.assertRaisesRegex(ValueError,'SOURCE_ORDER_OR_SCOPE'):validate_inputs(root,manifest,altered)
            alias=root/'changed.java';alias.write_text('class Changed {}')
            altered=copy.deepcopy(request);altered['sources'][1]['input']=str(alias)
            with self.assertRaisesRegex(ValueError,'ORIGINAL_SOURCE_DRIFT'):validate_inputs(root,manifest,altered)

    def test_duplicates_and_every_navigation_projection_remain_visible(self):
        row=dict(source='Use.java',kind='METHOD',name='run',start=0,end=9,parent=None,members=[],syntax='METHOD',
                 slice=dict(utf16_length=9,sha256='original'))
        data=dict(rows=[row,row],syntax=[dict(kind='METHOD',parent=None,enclosing=None)],
                  sources={'Use.java':dict(positions_sha256='original',position_count=10)},
                  types=[dict(name='p.Use',status='found',candidates=[dict(kind='CLASS',name='Use')])])
        original=projections(data)
        self.assertEqual(sum(original['declarations'].values()),2)
        for domain,key in (('declarations','rows'),('syntax','syntax'),('sources','sources'),('type_lookup','types')):
            altered=copy.deepcopy(data)
            if key=='sources':altered[key]['Use.java']['positions_sha256']='changed'
            else:altered[key]=[]
            actual=projections(altered)
            self.assertNotEqual(original[domain],actual[domain])
            self.assertEqual(set(actual),{'declarations','syntax','sources','type_lookup'})


if __name__=='__main__':unittest.main(verbosity=2)
