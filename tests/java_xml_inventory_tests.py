"""Selection preserves direct copied XML evidence and bridge reference evidence."""
import json
import sqlite3
import unittest
from java_xml_wiring_inventory_diff import select_facts as wiring
from java_xml_graph_inventory_diff import select_facts as graph

class XmlInventorySelection(unittest.TestCase):
    def test_copied_wires_uses_direct_evidence_and_captured_coverage(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.row_factory = sqlite3.Row
        db.execute('CREATE TABLE facts(id TEXT,body TEXT,relation TEXT,source TEXT,kind TEXT)')
        for identity, kind, relation, parent, target, attrs in [
            ('bean','facet','java.bean','','',{}),
            ('type','declaration','','','',{}),
            ('coverage','coverage','','','',{}),
            ('edge','relation','wires_possible','bean','type',
             {'via':'xml_configuration','upstream_relation':'WIRES','upstream_producer':'spring-xml'}),
        ]:
            fact = dict(id=identity,kind=kind,relation=relation,parent=parent,target=target,
                        attributes=attrs,source='beans.xml')
            db.execute('INSERT INTO facts VALUES(?,?,?,?,?)',
                       (identity,json.dumps(fact),relation,'beans.xml',kind))
        self.assertEqual({r['id'] for r in wiring(db)}, {'bean','type','coverage','edge'})
        self.assertEqual(set(graph(db)), {'bean','type','coverage','edge'})
        db.execute("UPDATE facts SET body=json_set(body,'$.attributes.upstream_relation','') WHERE id='edge'")
        with self.assertRaises(KeyError):
            wiring(db)

if __name__ == '__main__':
    unittest.main()
