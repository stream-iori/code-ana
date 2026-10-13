#!/usr/bin/env python3
"""JDK-free product checks for the typed Anatomist graph contract."""
import contextlib,json,sqlite3,unittest
import rust_e2e
from java_graph_storage import GraphStorage

class GraphProduct(rust_e2e.Product):
    canonical=GraphStorage.canonical
    def test_typed_graph_delta_history_and_pagination(self):
        first=self.cli('index')[1]['revision'];before=self.canonical(first)
        with contextlib.closing(sqlite3.connect(self.home/'revisions'/first/'facts.db')) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM call_sites').fetchone()[0],2)
            self.assertEqual(db.execute('SELECT count(*) FROM call_site_targets').fetchone()[0],2)
        self.assertTrue(self.cli('structure','app')[0]);self.assertTrue(self.cli('annotations','app')[0])
        self.cli('snapshot','before','--name','analysis')
        self.state('edited');delta=self.cli('index')[1]['revision'];changed=self.canonical(delta)
        full=self.cli('index','--full')[1]['revision'];self.assertEqual(changed,self.canonical(full));self.assertEqual(before,self.canonical(first))
        self.cli('snapshot','after','--name','analysis');self.assertTrue(self.cli('diff','--base','before','--target','after')[0])
        self.assertTrue(self.cli('calls','main')[0])
    test_worker_errors_cancel_and_idempotency=rust_e2e.Product.test_worker_errors_cancel_and_idempotency
    test_historical_text_search_is_independent_of_active_workspace=rust_e2e.Product.test_historical_text_search_is_independent_of_active_workspace
    def test_old_schema_requires_full_rebuild(self):
        first=self.cli('index')[1]['revision'];path=self.home/'revisions'/first/'facts.db'
        with sqlite3.connect(path) as db:db.execute('PRAGMA user_version=5')
        self.assertIn('GRAPH_SCHEMA_REBUILD_REQUIRED',self.cli('overview','--revision',first,code=1)[1]['message'])
        self.assertIn('GRAPH_SCHEMA_REBUILD_REQUIRED',self.cli('index',code=1)[1]['message'])
        second=self.cli('index','--full')[1]['revision'];self.assertNotEqual(first,second);self.canonical(second)

def load_tests(loader,tests,pattern):
    return unittest.TestSuite(GraphProduct(name) for name in ('test_typed_graph_delta_history_and_pagination','test_worker_errors_cancel_and_idempotency','test_historical_text_search_is_independent_of_active_workspace','test_old_schema_requires_full_rebuild'))
if __name__=='__main__':unittest.main()
