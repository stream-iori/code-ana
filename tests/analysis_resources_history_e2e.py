#!/usr/bin/env python3
"""Stored report compatibility through actual CLI JSON and Markdown readers."""
import copy
import json
import sqlite3
import unittest
import python_language_e2e as python

class ResourceHistory(python.PythonLanguage):
    def test_old_reports_and_unknown_watermarks_render_without_invented_usage(self):
        _, terminal=self.cli('index','--full',timeout=180)
        report_id=terminal['report']
        with sqlite3.connect(self.home/'catalog.db') as db:
            original=db.execute("SELECT body FROM objects WHERE kind='report' AND id=?",(report_id,)).fetchone()[0]
        fresh=json.loads(original)
        def save(value):
            with sqlite3.connect(self.home/'catalog.db') as db:
                db.execute("UPDATE objects SET body=? WHERE kind='report' AND id=?",(json.dumps(value),report_id))
        try:
            old=copy.deepcopy(fresh)
            old.pop('analysis_resources');old.pop('schema_version');old.pop('global',None)
            save(old)
            value=self.cli('report',report_id)[0][0]
            self.assertNotIn('analysis_resources',value)
            self.assertEqual(value['schema_version'],1)
            self.assertNotIn('Analysis resources:',self.cli('report',report_id,'--format','markdown',raw=True))
            for unknown in (False,True):
                old=copy.deepcopy(fresh)
                coordinator=old['analysis_resources']['coordinator']
                ids=[]
                for slot in coordinator['languages']:
                    slot.pop('workers')
                    worker=slot['worker']
                    worker.pop('phase',None)
                    ids.append(worker['grant']['id'])
                    if unknown:worker['state']='unknown'
                if unknown:coordinator['complete']=False
                save(old)
                value=self.cli('report',report_id)[0][0]['analysis_resources']
                self.assertEqual(value['usage'],old['analysis_resources']['usage'])
                markdown=self.cli('report',report_id,'--format','markdown',raw=True)
                for account in ids:self.assertEqual(markdown.count(account),1)
                if unknown:
                    self.assertIn('| unknown |',markdown)
                    self.assertIn('unknown consumption is not zero',markdown)
        finally:
            with sqlite3.connect(self.home/'catalog.db') as db:
                db.execute("UPDATE objects SET body=? WHERE kind='report' AND id=?",(original,report_id))
        self.assertEqual(self.cli('overview')[1]['revision'],terminal['revision'])

def load_tests(loader,tests,pattern):
    return unittest.TestSuite([ResourceHistory('test_old_reports_and_unknown_watermarks_render_without_invented_usage')])

if __name__=='__main__':unittest.main(verbosity=2)
