#!/usr/bin/env python3
"""XML graph audits consume emitted candidates, never invent them from names."""
import copy
import json
import unittest
from java_xml_graph_inventory_diff import product_graph,encoded


class XmlGraphEvidence(unittest.TestCase):
    def fixture(self):
        position=dict(line=1,column=20,end_line=1,end_column=20)
        model=dict(construction=dict(name='use',ordinal=0),roots=[0],nodes=[dict(
            metadata=dict(xmlKind='ref',ordinal=0,bean='api'),position=position,
            source_element_span=dict(start=1,end=20),children=[])])
        def bean(identity,name,ordinal):
            configuration=copy.deepcopy(model) if name=='use' else dict(construction=dict(name=name,ordinal=ordinal),roots=[],nodes=[])
            return dict(id=identity,source='beans.xml',kind='facet',relation='java.bean',name=name,parent='',target='',
                attributes=dict(kind='xml_bean',position=position,aliases=[],xml_configuration_model=configuration))
        site=dict(id='site',source='beans.xml',span=dict(start=1,end=20),parent='use',target='',kind='facet',relation='java.spring.xml',
            attributes=dict(kind='bean_ref',bean='api',candidate_count=0))
        return dict(use=bean('use','use',0),api=bean('api','api',1),site=site)

    def test_same_name_does_not_create_a_product_candidate(self):
        facts=self.fixture();normalize=lambda value,*args:value
        edges,gaps=product_graph(facts,normalize)
        self.assertFalse(gaps)
        row=next(iter(edges['XML_REFERS_TO']))
        self.assertEqual(json.loads(row[1]),['external_bean','api'])
        self.assertTrue(row[-1])
        facts['site']['attributes']['candidate_count']=1
        with self.assertRaisesRegex(ValueError,'REFERENCE_COUNT_MISMATCH'):product_graph(facts,normalize)
        facts['link']=dict(id='link',source='beans.xml',relation='config',parent='site',target='api',kind='reference',attributes={})
        edges,gaps=product_graph(facts,normalize)
        row=next(iter(edges['XML_REFERS_TO']))
        self.assertEqual(json.loads(row[1])[:3],['bean','beans.xml','api'])
        self.assertFalse(row[-1])

    def test_parent_container_uncertainty_and_duplicate_roots_remain_visible(self):
        facts=self.fixture();facts['site']['attributes']=dict(kind='parent_context_ref',bean='api',selection='unknown_parent_container')
        edges,gaps=product_graph(facts,lambda value,*args:value)
        self.assertTrue(next(iter(edges['XML_REFERS_TO']))[-1])
        facts['use']['attributes']['xml_configuration_model']['roots'].append(0)
        with self.assertRaisesRegex(ValueError,'CONFIGURATION_GRAPH_INVALID'):
            product_graph(facts,lambda value,*args:value)


if __name__=='__main__':unittest.main(verbosity=2)
