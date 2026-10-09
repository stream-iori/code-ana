import json
import sqlite3
import unittest
from java_xml_binding_inventory_diff import product_projection, select_facts, reference_projection

class CallableInventory(unittest.TestCase):
    def test_setter_is_read_from_binding_evidence_without_legacy_member_facet(self):
        def fact(identity,relation,parent='',target='',attrs=None):
            return dict(id=identity,kind='facet',relation=relation,parent=parent,target=target,attributes=attrs or {},source='beans.xml',name='holder',signature='p.Holder')
        bean=fact('bean','java.bean',attrs={'xml_configuration_model':{'construction':{'name':'holder'}}})
        prop=fact('property','java.spring.xml','bean',attrs={'kind':'property','candidate_count':0,'position':{'line':2,'column':8,'end_line':2,'end_column':8}})
        target=fact('setter','',attrs={'element_kind':'METHOD'});target['signature']='p.Holder#setValue(java.lang.String)'
        link=fact('binding','config_member','property','setter',{'role':'setter','candidateCount':1,'resolutionStatus':'exact','symbolRef':{'owner':'p.Holder','memberName':'setValue','arity':1,'parameterTypeHints':['java.lang.String']}})
        with sqlite3.connect(':memory:') as db:
            db.row_factory=sqlite3.Row;db.execute('CREATE TABLE facts(id TEXT PRIMARY KEY,relation TEXT,body TEXT,parent TEXT)')
            for f in [bean,prop,target,link]:db.execute('INSERT INTO facts VALUES(?,?,?,?)',(f['id'],f['relation'],json.dumps(f),f['parent']))
            result=product_projection(select_facts(db))
        self.assertEqual(len(result),1);row=result[0]
        self.assertEqual((row['role'],row['member'],row['candidate_count']),('setter','setValue',1))
        self.assertEqual(row['candidates'],[('p.Holder#setValue(java.lang.String)','METHOD')])

    def test_frozen_callable_uses_its_original_discovery_position(self):
        with sqlite3.connect(':memory:') as db:
            db.row_factory=sqlite3.Row
            db.execute('CREATE TABLE nodes(id TEXT,kind TEXT,symbol_id TEXT,label TEXT,source_file TEXT,metadata TEXT,producer_id TEXT,begin_line INTEGER,begin_column INTEGER,end_line INTEGER,end_column INTEGER)')
            db.execute('CREATE TABLE edges(source_id TEXT,target_id TEXT,producer_id TEXT,relation TEXT,metadata TEXT)')
            for row in [('bean','BEAN','bean','holder','beans.xml','{}','spring-xml',20,50,30,12),('call','XML_CALLABLE_REF','call','constructor','beans.xml','{}','spring-xml',20,1,20,1),('ctor','CONSTRUCTOR','p.Holder#Holder()','Holder','A.java','{}','java-core',1,1,1,10)]:db.execute('INSERT INTO nodes VALUES(?,?,?,?,?,?,?,?,?,?,?)',row)
            metadata=dict(symbolRef=dict(runtime='jvm',languageHint='java',symbolKind='constructor',owner='p.Holder',memberName='<init>',arity=0,parameterTypeHints=[],staticRequirement=None),candidateCount=1,resolutionStatus='exact',role='constructor',mechanism='spring.xml.constructor')
            db.execute('INSERT INTO edges VALUES(?,?,?,?,?)',('bean','call','spring-xml','CONFIGURES','{}'))
            db.execute('INSERT INTO edges VALUES(?,?,?,?,?)',('call','ctor','spring-xml','BINDS_TO',json.dumps(metadata)))
            row=reference_projection(db)[0]
        self.assertEqual(row['position'],dict(line=20,column=1,end_line=20,end_column=1))

if __name__=='__main__':unittest.main()
