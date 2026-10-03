#!/usr/bin/env python3
"""Strict XML callable candidates; a separate mode reproduces a frozen reference bug.

This projection covers member bindings, not the entire XML tree or framework graph.
The defect receipt intentionally records raw_fact_equivalence=false.
"""
import argparse
import collections
import json
import os
import sqlite3
import subprocess
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_model_tests import JDK8, JDK25
from java_xml_alignment import Xml

SOURCE = '''package p;
class Use {
 public Use(){} public void start(){} public void stop(){}
 public void setName(String name){}
 public static Use create(){return new Use();}
}
'''
XML = '''<beans xmlns="http://www.springframework.org/schema/beans">
 <bean id="use" class="p.Use" init-method="start" destroy-method="stop">
  <property name="name" value="原字节😀"/>
 </bean>
 <bean id="made" class="p.Use" factory-method="create"/>
</beans>'''
ROLES = {'init-method':'init', 'destroy-method':'destroy', 'factory-method':'factory-method',
         'constructor':'constructor', 'property-setter':'property-member',
         'setter':'property-member', 'factory':'factory-method'}


def product_projection(facts):
    by = {f['id']:f for f in facts}
    rows = []
    for f in facts:
        if f['relation'] != 'java.spring.xml' or 'member' not in f['attributes']:
            continue
        a = f['attributes']
        candidates = []
        for link in facts:
            if link['parent'] == f['id'] and link['relation'] == 'config_member':
                target = by[link['target']]
                candidates.append((reference.canonical(target['signature'],target['attributes']['element_kind']),
                                   target['attributes']['element_kind']))
        owner = by.get(f['parent'])
        while owner and owner['relation'] != 'java.bean':
            owner = by.get(owner['parent'])
        if owner is None:
            raise ValueError('XML_CALLABLE_BEAN_OWNER_MISSING')
        if a['candidate_count'] != len(candidates):
            raise ValueError('XML_CANDIDATE_COUNT_MISMATCH')
        rows.append(dict(bean=owner['name'],role=a['kind'],arity=a['arity'],
                         hints=a['parameter_type_hints'],candidates=sorted(candidates)))
    return sorted(rows,key=lambda r:(r['bean'],r['role']))


def reference_projection(database):
    with sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True) as db:
        db.row_factory=sqlite3.Row
        nodes={r['id']:dict(r) for r in db.execute('SELECT * FROM nodes')}
        parents={r['target_id']:r['source_id'] for r in db.execute(
            "SELECT source_id,target_id FROM edges WHERE relation IN ('CONFIGURES','XML_CONTAINS')")}
        rows=[]
        sources={r['source_id']:json.loads(r['metadata']) for r in db.execute(
            "SELECT source_id,metadata FROM edges WHERE relation='BINDS_TO'")}
        for identity,a in sources.items():
            n=nodes[identity];symbol=a['symbolRef']
            owner=nodes[parents[n['id']]]
            while owner['kind']!='BEAN':owner=nodes[parents[owner['id']]]
            candidates=[]
            for edge in db.execute("SELECT * FROM edges WHERE source_id=? AND relation='BINDS_TO'",(n['id'],)):
                if edge['target_id']:
                    target=nodes[edge['target_id']]
                    candidates.append((reference.canonical(target['symbol_id'],target['kind']),target['kind']))
                elif a['candidateCount']:
                    raise ValueError('REFERENCE_BOUND_CANDIDATE_MISSING')
            if len(candidates)!=a['candidateCount']:raise ValueError('REFERENCE_CANDIDATE_COUNT_MISMATCH')
            rows.append(dict(bean=owner['label'],role=ROLES.get(a['role'],a['role']),
                             arity=symbol['arity'],hints=symbol['parameterTypeHints'],candidates=sorted(candidates)))
        return sorted(rows,key=lambda r:(r['bean'],r['role']))


def index_reference(base, source, xml):
    java=base/'app/Api.java';java.parent.mkdir();java.write_text(source)
    resources=base/'app/src/main/resources';resources.mkdir(parents=True)
    (resources/'beans.xml').write_text(xml)
    config=base/'.anatomist/config.toml';config.parent.mkdir()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\n'
                      'javac_analysis_threads=1\njavac_worker_heap_mb=128\nspring_xml=true\n')
    jar=alignment.ROOT.parent/'anatomist/target/anatomist.jar'
    if reference.digest(jar)!=reference.REFERENCE_SHA:raise ValueError('FROZEN_REFERENCE_JAR_CHANGED')
    database=base/'index.db'
    command=[str(JDK25/'bin/java'),'-Xmx512m','-jar',str(jar),'index',str(base),
             '--source-root','app@MAIN=app','--jdk-home',str(JDK8),'--java-version','8',
             '--lombok','off','--vm-classpath','true','--output',str(database),'--full','--no-classpath']
    with (base/'reference.log').open('wb') as log:
        result=subprocess.run(command,stdout=log,stderr=log,timeout=180,
                              env=dict(os.environ,ANATOMIST_HOME=str(base/'reference-state')))
    if result.returncode:raise RuntimeError((base/'reference.log').read_text()[-4000:])
    return database


def main():
    p=argparse.ArgumentParser();p.add_argument('--native',action='store_true')
    p.add_argument('--reference-defect',action='store_true');args=p.parse_args()
    base=alignment.ROOT/'target/java-alignment/evidence'/('xml-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    base.mkdir(parents=True)
    inputs,artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    case=Xml();case.setUp()
    if args.native:case.analyze=reference.native_analyze.__get__(case)
    groups=[('baseline',SOURCE,XML),('body',SOURCE.replace('start(){}','start(){int n=1;}'),XML),
            ('xml-change',SOURCE,XML.replace('init-method="start"','init-method="stop"')),
            ('unresolved-ambiguous',SOURCE.replace('public Use(){}',
                'public Use(){} public void setChoice(String value){} public void setChoice(Integer value){}'),
                '<beans><bean id="use" class="p.Use" init-method="absent">'
                '<property name="choice"><null/></property><property name="absent" value="x"/>'
                '</bean><bean id="unknown" class="missing.External"/></beans>')]
    if args.reference_defect:
        groups=[('constructor-lambda',SOURCE.replace('Use(){}','Use(){Runnable r=()->{};}'),
                 '<beans><bean id="use" class="p.Use"/></beans>')]
    proofs=[];started=time.monotonic()
    try:
        old=None
        for name,source,xml in groups:
            folder=base/name;folder.mkdir()
            context=case.captured({'app/src/main/resources/beans.xml':xml},source=source)
            facts=case.analyze([context],cache=True)['facts']
            if old is not None:
                case.assertEqual(case.merged(old,case.analyze([context],old,cache=True)),sorted(facts,key=lambda f:f['id']))
            old=facts
            database=index_reference(folder,source,xml)
            left,right=product_projection(facts),reference_projection(database)
            case.assertTrue(left);case.assertTrue(right)
            proof=dict(name=name,product=left,reference=right,equal=left==right)
            from java_xml_binding_inventory_diff import product_projection as requests, reference_projection as bindings
            from java_declaration_inventory_diff import connection
            with connection(database) as db:
                request_left,request_right=requests(facts),bindings(db)
            proof['request_metadata']=dict(product=request_left,reference=request_right,equal=request_left==request_right)
            if args.reference_defect:
                case.assertEqual(left[0]['candidates'],[('p.Use#<init>()','CONSTRUCTOR')])
                case.assertEqual(collections.Counter(kind for _,kind in right[0]['candidates']),
                                 {'CONSTRUCTOR':1,'LAMBDA':1})
                case.assertFalse(proof['equal'])
                proof['reference_defect_reproduced']=True
                case.assertFalse(proof['request_metadata']['equal'])
            else:
                case.assertEqual(request_left,request_right)
                if name=='unresolved-ambiguous':
                    case.assertEqual(collections.Counter(row['resolution'] for row in request_left),
                                     {'exact':1,'unresolved':3,'ambiguous':1})
            (folder/'product.json').write_text(json.dumps(facts,ensure_ascii=False)+'\n')
            proof.update(product_sha256=reference.digest(folder/'product.json'),reference_sha256=reference.digest(database))
            (folder/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            proofs.append(proof);print(name,proof['equal'],flush=True)
    finally:case.doCleanups()
    current,now=alignment.input_files(),reference.probe_artifacts(args.native)
    drift=sorted(k for k in inputs.keys()|current.keys() if inputs.get(k)!=current.get(k))
    changed=sorted(k for k in artifacts.keys()|now.keys() if artifacts.get(k)!=now.get(k))
    report=dict(kind='frozen_xml_constructor_reference_defect' if args.reference_defect else 'frozen_xml_callable_candidates',
                native=args.native,proofs=proofs,inputs=inputs,artifacts=artifacts,
                changed_inputs=drift,changed_artifacts=changed,reference_commit=reference.REFERENCE_COMMIT,
                elapsed_seconds=round(time.monotonic()-started,3),full_semantic_diff_accepted=False,
                raw_fact_equivalence=all(proof['equal'] for proof in proofs))
    report['all_ok']=len(proofs)==len(groups) and not drift and not changed and all(
        proof.get('reference_defect_reproduced') if args.reference_defect else proof['equal'] for proof in proofs)
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
