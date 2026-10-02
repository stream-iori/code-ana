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
class ConcreteGraphCalls(GraphCalls):
 def source(self,depth=80,actual='String'):
  return '@lombok.Getter class Deep<T> { '+'java.util.List<'*depth+'T'+'>'*depth+' value;} class Use {int read(Deep<'+actual+'> d){return d.getValue().size();}}'
 def test_concrete_leaf_and_original_generic_evidence(self):
  from java_type_views import type_attributes
  result=self.analyze([self.graph(self.source())])
  self.assertCountEqual(self.targets(result),[('Deep#getValue()','possible'),('java.util.List#size()','possible')])
  site=next(f for f in result['facts'] if f['kind']=='call_site' and f.get('name','').endswith('.getValue'))
  value=type_attributes(site)['recovered_return_type_model']
  for _ in range(80):
   self.assertEqual(value['element'],'java.util.List');value=value['arguments'][0]
  self.assertEqual(value['element'],'java.lang.String');self.assertTrue(value['complete'])
  declaration=next(f for f in result['facts'] if f['kind']=='declaration' and f['signature']=='Deep#getValue()')
  value=type_attributes(declaration)['return_type_model']
  for _ in range(80):value=value['arguments'][0]
  self.assertEqual(value['kind'],'TYPEVAR');self.assertEqual(value['name'],'T')
 def test_candidate_isolation_and_missing_receiver(self):
  from java_type_views import type_attributes
  source=self.source().replace('return d.getValue().size();','int a=d.getValue().size(); return n.getValue().size();').replace('Deep<String> d)','Deep<String> d,Deep<Number> n)')
  result=self.analyze([self.graph(source)]);self.assertEqual(len(self.targets(result)),4)
  values=[]
  for site in result['facts']:
   if site['kind']=='call_site' and site.get('name','').endswith('.getValue'):
    value=type_attributes(site)['recovered_return_type_model']
    for _ in range(80):value=value['arguments'][0]
    values.append(value['element'])
  self.assertCountEqual(values,['java.lang.String','java.lang.Number'])
  bad=self.source().replace('d.getValue().size()','d.absent().size()')
  self.assertFalse(self.targets(self.analyze([self.graph(bad)])))
if __name__=='__main__':unittest.main(verbosity=2)
