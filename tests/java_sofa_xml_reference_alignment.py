#!/usr/bin/env python3
"""Independent complete SOFA XML registration metadata, including MsgBroker children."""
import argparse
import json
import os
import sqlite3
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_sofa_xml_inventory_diff import product_projection, reference_projection, select_facts
from java_xml_reference_alignment import index_reference
from java_xml_alignment import Xml

SOURCE = 'package p; interface Api {} class Service implements Api {} class Listener {}'
XML = '''<beans xmlns:sofa="http://schema.alipay.com/sofa/schema/service">
 <!-->原字节😀<-->
 <bean id="implementation" class="p.Service"/><bean id="listener" class="p.Listener"/>
 <sofa:service interface="p.Api" ref="implementation" unique-id="one"><sofa:binding.tr/></sofa:service>
 <sofa:reference id="remote" interface="p.Api"><sofa:binding.tri/><sofa:binding.rest/></sofa:reference>
 <sofa:reference id="duplicate" interface="p.Api"/><sofa:reference id="duplicate" interface="p.Api"/>
 <sofa:reference id="dynamic" interface="${type}"/><sofa:reference id="unknown" interface="p.Api"><sofa:binding.custom/></sofa:reference>
 <sofa:publisher id="publisher" group="pub"><sofa:channels><sofa:channel value="first"><sofa:event value="a"/></sofa:channel></sofa:channels></sofa:publisher>
 <sofa:consumer id="consumer" group="consume"><sofa:binding.msg_broker/><sofa:listener ref="listener"/><sofa:channel value="second"><sofa:event value="b"/></sofa:channel></sofa:consumer>
</beans>'''


def main():
    parser = argparse.ArgumentParser();parser.add_argument('--native',action='store_true');args=parser.parse_args()
    base=alignment.ROOT/'target/java-alignment/evidence'/('sofa-xml-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    base.mkdir(parents=True)
    inputs,artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    case=Xml();case.setUp()
    if args.native:case.analyze=reference.native_analyze.__get__(case)
    started=time.monotonic();proofs=[]
    try:
        old=None
        for name,source,xml in [('baseline',SOURCE,XML),('body',SOURCE.replace('class Service implements Api {}','class Service implements Api { void body(){int n=1;} }'),XML),
                                ('configuration',SOURCE,XML.replace('unique-id="one"','unique-id="two"').replace('value="b"','value="changed"'))]:
            folder=base/name;folder.mkdir()
            context=case.captured({'app/src/main/resources/beans.xml':xml},source=source);context['sofa']=True
            facts=case.analyze([context],cache=True)['facts']
            if old is not None:case.assertEqual(case.merged(old,case.analyze([context],old,cache=True)),sorted(facts,key=lambda f:f['id']))
            old=facts
            database=index_reference(folder,source,xml)
            with sqlite3.connect(database) as db:
                db.row_factory=sqlite3.Row;right=reference_projection(db)
            left=product_projection(facts)
            with sqlite3.connect(':memory:') as db:
                db.row_factory=sqlite3.Row;db.execute('CREATE TABLE facts(relation,body)')
                db.executemany('INSERT INTO facts VALUES(?,?)',((f['relation'],json.dumps(f)) for f in facts))
                case.assertEqual(product_projection(select_facts(db)),left)
            case.assertEqual(right.total(),8)
            (folder/'product.json').write_text(json.dumps(facts,ensure_ascii=False)+'\n')
            proof=dict(name=name,**reference.compare(left,right),product_sha256=reference.digest(folder/'product.json'),reference_sha256=reference.digest(database))
            (folder/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n');proofs.append(proof)
            print(name,proof['product'],proof['reference'],proof['equal'],flush=True)
    finally:case.doCleanups()
    current,now=alignment.input_files(),reference.probe_artifacts(args.native)
    drift=sorted(k for k in inputs.keys()|current.keys() if inputs.get(k)!=current.get(k))
    changed=sorted(k for k in artifacts.keys()|now.keys() if artifacts.get(k)!=now.get(k))
    report=dict(kind='frozen_sofa_msgbroker_xml_registration_attributes',native=args.native,proofs=proofs,
                inputs=inputs,artifacts=artifacts,changed_inputs=drift,changed_artifacts=changed,
                reference_commit=reference.REFERENCE_COMMIT,full_semantic_diff_accepted=False,
                elapsed_seconds=round(time.monotonic()-started,3))
    report['all_ok']=len(proofs)==3 and all(p['equal'] for p in proofs) and not drift and not changed
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
