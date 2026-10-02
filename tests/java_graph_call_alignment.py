#!/usr/bin/env python3
"""Deep graph receiver evidence and full/delta invalidation, without changing target confidence."""
from pathlib import Path
import sys,unittest
import java_collector_alignment as collector
class GraphCalls(unittest.TestCase):
 setUp=collector.Collectors.setUp
 context=collector.Collectors.context
 analyze=collector.Collectors.analyze
 fixture=collector.Collectors.fixture
 merged=collector.Collectors.merged
 def source(self,depth=80):return '@lombok.Getter class Deep { '+'java.util.List<'*depth+'String'+'>'*depth+' value;} class Use {int read(Deep d){return d.getValue().size();}}'
 def graph(self,source):return self.fixture(source)|dict(type_graph=True)
 def targets(self,result):
  by={f['id']:f for f in result['facts']}
  return [(by[f['target']]['signature'],f['status']) for f in result['facts'] if f['kind']=='call_target']
 def test_deep_graph_getter_and_following_receiver_full_delta(self):
  base=self.analyze([self.graph(self.source())],cache=True)
  self.assertCountEqual(self.targets(base),[('Deep#getValue()','possible'),('java.util.List#size()','possible')])
  self.assertFalse([f for f in base['facts'] if f['kind']=='call_site' and f['attributes'].get('recovery_failure')])
  changed=self.graph(self.source(82));delta=self.analyze([changed],base['facts'],cache=True);full=self.analyze([changed],cache=True)
  self.assertEqual(self.merged(base['facts'],delta),sorted(full['facts'],key=lambda f:f['id']))
  self.assertCountEqual(self.targets(full),self.targets(base))
  removed=self.graph(self.source(82).replace('@lombok.Getter ',''));delta=self.analyze([removed],full['facts'],cache=True);cold=self.analyze([removed],cache=True)
  self.assertEqual(self.merged(full['facts'],delta),sorted(cold['facts'],key=lambda f:f['id']))
  self.assertFalse(self.targets(cold))
if __name__=='__main__':unittest.main(verbosity=2)
