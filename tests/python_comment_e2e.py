#!/usr/bin/env python3
"""Real frozen Pyrefly/Ruff comments through the public graph and query boundary."""
import unittest
import copy
import json
from product_support import ProductHarness, ROOT, FIX
import python_language_e2e as language

class PythonComments(ProductHarness):
    setUp = language.PythonLanguage.setUp
    export = language.PythonLanguage.export
    db = language.PythonLanguage.db
    add_rust = language.PythonLanguage.add_rust
    add_java = language.PythonLanguage.add_java

    def prepare(self):
        source=self.project/'comments.py'
        source.write_bytes(((FIX/'python/comment-indexing/comments.py') if (FIX/'python/comment-indexing/comments.py').exists() else (ROOT/'fixtures/python/comment-indexing/comments.py')).read_bytes())
        self.export()
        return source,self.cli('index','--full',timeout=120)[1]['revision']

    def canonical_comments(self,revision):
        with self.db(revision) as db:
            return {table:sorted(db.execute('SELECT * FROM '+table).fetchall(),key=repr) for table in ('comments','comment_links')}

    def test_docstrings_scopes_projection_and_escape_highlights(self):
        source,revision=self.prepare()
        matches,terminal=self.cli('search-comments','扣减库存','--strict')
        self.assertTrue(terminal['evidence']['semantic'])
        self.assertEqual(len(matches),3)
        declarations,_=self.cli('search-code','扣减库存','--in','comments')
        self.assertEqual(len(declarations),1)
        self.assertEqual(declarations[0]['name'],'reserve')
        self.assertEqual(len(declarations[0]['comment_hits']),3)
        for word in ('字符串伪注释','普通晚字符串'):
            self.assertFalse(self.cli('search-comments',word)[0])
        self.assertFalse(self.cli('search-code','闭包隔离','--in','comments')[0])
        self.assertFalse(self.cli('search-code','类内隔离','--in','comments')[0])
        inner,_=self.cli('search-code','内层方法隔离','--in','comments')
        self.assertEqual([r['name'] for r in inner],['nested'])
        trailing,_=self.cli('search-code','方法尾注释','--in','comments')
        self.assertEqual([r['name'] for r in trailing],['reserve'])
        self.assertFalse(self.cli('search-code','文件末尾库存','--in','comments')[0])
        escaped,_=self.cli('search-comments','转义库存')
        self.assertEqual(len(escaped),1)
        original=source.read_bytes()
        highlighted=[original[h['source']['byte_start']:h['source']['byte_end']].decode() for h in escaped[0]['hits']]
        self.assertIn('\\u5e93',highlighted)
        self.assertIn('\\u5b58',highlighted)
        self.assertTrue(self.canonical_comments(revision)['comments'])

    def test_full_recompute_cleanup_history_and_frozen_page(self):
        source,first=self.prepare()
        before=self.canonical_comments(first)
        rows,terminal=self.cli('search-comments','库存','--max-records','1')
        self.assertTrue(terminal['cursor'])
        cursor=terminal['cursor']
        source.write_text(source.read_text().replace('扣减库存','冻结余额'))
        self.export()
        second=self.cli('index',timeout=120)[1]['revision']
        self.assertTrue(self.cli('search-comments','冻结余额')[0])
        frozen,_=self.cli('search-comments','库存','--cursor',cursor,'--max-records','100')
        self.assertTrue(any('扣减库存' in r['comment']['body'] for r in frozen))
        full=self.cli('index','--full',timeout=120)[1]['revision']
        self.assertEqual(self.canonical_comments(second),self.canonical_comments(full))
        source.write_text('VALUE=1\n')
        self.export()
        self.cli('index',timeout=120)
        self.assertFalse(self.cli('search-comments','冻结余额')[0])
        self.assertEqual(before,self.canonical_comments(first))
        self.assertTrue(self.cli('search-comments','扣减库存','--revision',first)[0])

    def test_display_defaults_reuse_the_same_semantic_comments(self):
        _,first=self.prepare()
        before=self.canonical_comments(first)
        profile=json.loads((self.home/'revisions'/first/'revision.json').read_text())['profile']
        shown=self.cli('search-comments','扣减库存')[0]
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[query.comments]\ncontext_before=0\ncontext_after=0\n')
        rows,current=self.cli('index',timeout=120)
        self.assertEqual(rows[0]['mode'],'delta')
        self.assertEqual(json.loads((self.home/'revisions'/current['revision']/'revision.json').read_text())['profile'],profile)
        self.assertEqual(before,self.canonical_comments(current['revision']))
        after=self.cli('search-comments','扣减库存')[0]
        self.assertLess(len(after[0]['source']['text']),len(shown[0]['source']['text']))

    def test_shared_source_worlds_are_isolated_and_removed_explicitly(self):
        self.prepare()
        manifest=self.project/'.code-ana/python-inputs.json'
        inputs=json.loads(manifest.read_text())
        original=copy.deepcopy(inputs)
        alternate=copy.deepcopy(inputs['contexts'][0])
        alternate['id']='alternate-comments'
        alternate['scope']='TEST'
        config=self.project/'.code-ana/config.toml'
        config.write_text(config.read_text()+'\n[scan]\nscopes=["MAIN","TEST"]\n')
        inputs['contexts'].append(alternate)
        manifest.write_text(json.dumps(inputs))
        first=self.cli('index','--full',timeout=120)[1]['revision']
        rows,_=self.cli('search-comments','扣减库存')
        worlds={r['comment']['world'] for r in rows}
        self.assertEqual(len(worlds),2)
        self.assertEqual(len(rows),6)
        for world in worlds:
            selected,_=self.cli('search-comments','扣减库存','--world',world)
            self.assertEqual(len(selected),3)
            declarations,_=self.cli('search-code','扣减库存','--in','comments','--world',world)
            self.assertEqual(len(declarations),1)
            self.assertEqual(declarations[0]['owner']['world'],world)
        manifest.write_text(json.dumps(original))
        self.cli('index',timeout=120)
        self.assertEqual(len(self.cli('search-comments','扣减库存')[0]),3)
        self.assertEqual(len(self.cli('search-comments','扣减库存','--revision',first)[0]),6)

if __name__=='__main__':
    unittest.main()
