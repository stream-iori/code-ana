#!/usr/bin/env python3
"""Actual daemon/native Spring XML graph against the independently frozen index."""
import collections
import json
import pathlib
import shutil
import sqlite3
import time

import java_alignment as alignment
import java_e2e as product
from java_functional_reference_alignment import compare,digest
from java_inventory_canonical import Canonical
from java_xml_reference_alignment import index_reference
from java_xml_graph_inventory_diff import (encoded, product_graph, reference_graph, select_facts,
    product_bindings, reference_bindings, product_wires, reference_wires)


def runtime_artifacts():
    bundle=product.BIN.resolve().parent/'lib/java'
    paths=[product.BIN/name for name in ('codeana-daemon','codeana-cli','codeana-java-worker')]
    paths+=[bundle/name for name in ('compiler-bridge.jar','maven-input-worker.jar','native/libcodeana_java.dylib','native/manifest.json')]
    return {str(path.resolve()):digest(path) for path in paths}

SOURCE='''package p;
class Api {}
class Use {
 Use(Api api){} public void start(){} public void stop(){}
 public void setOther(Api api){} public void setItems(java.util.List items){}
 public static Use create(){return new Use(new Api());}
 public Use instance(){return this;}
}
'''
XML='''<beans xmlns="http://www.springframework.org/schema/beans">
 <bean id="api" class="p.Api"/>
 <bean id="use" class="p.Use" init-method="start" destroy-method="stop">
  <constructor-arg ref="api"/><property name="other" ref="api"/>
  <property name="items"><list><ref bean="api"/><idref bean="api"/><value>原字节😀</value></list></property>
 </bean>
 <bean id="made" class="p.Use" factory-method="create"/>
 <bean id="instance" factory-bean="made" factory-method="instance"/>
 <bean id="inherited" class="p.Use" parent="use"><constructor-arg ref="api"/></bean>
</beans>'''


def main():
    base=alignment.ROOT/'target/java-alignment/evidence'/('xml-graph-reference-'+time.strftime('%Y%m%d-%H%M%S'))
    base.mkdir(parents=True,exist_ok=False)
    artifacts=runtime_artifacts();inputs=alignment.input_files()
    case=product.JavaProduct('test_core');case.setUp();proofs=[];started=time.monotonic()
    try:
        config=case.project/'.code-ana/config.toml'
        config.write_text(config.read_text().replace('path = "src"','path = "app"').replace(
            '[languages.java.extensions.spring]\nenabled = false','[languages.java.extensions.spring]\nenabled = true\nxml = true'))
        shutil.rmtree(case.project/'src')
        source=case.project/'app/Api.java';source.parent.mkdir();source.write_text(SOURCE)
        resource=case.project/'app/src/main/resources/beans.xml';resource.parent.mkdir(parents=True)
        previous=None
        for name,text in [('baseline',XML),('line-growth',XML.replace('\n','\r\n').replace(' <bean id="use"','\r\n\r\n <bean id="use"')),
                          ('reference-change',XML.replace('<ref bean="api"/>','<ref bean="absent"/>'))]:
            directory=base/name;directory.mkdir();resource.write_text(text)
            _,terminal=case.cli('index',*(('--full',) if previous is None else ()),timeout=180)
            facts=case.facts(terminal['revision'])
            case.assertTrue(any(f['relation']=='java.bean' for f in facts),'XML fixture was not captured')
            if previous is not None:
                _,full=case.cli('index','--full',timeout=180)
                case.assertEqual(facts,case.facts(full['revision']))
            previous=facts
            product_db=directory/'facts.db';shutil.copy2(case.home/'revisions'/terminal['revision']/'facts.db',product_db)
            reference=directory/'reference';reference.mkdir();reference_db=index_reference(reference,SOURCE,text)
            case.assertEqual(source.read_bytes(),(reference/'app/Api.java').read_bytes())
            case.assertEqual(resource.read_bytes(),(reference/'app/src/main/resources/beans.xml').read_bytes())
            before=dict(product=digest(product_db),reference=digest(reference_db))
            with sqlite3.connect(product_db.resolve().as_uri()+'?mode=ro',uri=True) as db,sqlite3.connect(reference_db.resolve().as_uri()+'?mode=ro',uri=True) as ref:
                db.row_factory=ref.row_factory=sqlite3.Row
                selected=select_facts(db);normalize=Canonical(db,ref,case.project)
                left,gaps=product_graph(selected,normalize);right,counts,unknown=reference_graph(ref,normalize)
                projections={relation:compare(left[relation],right[relation]) for relation in left}
                rows=lambda values:collections.Counter((encoded(row),) for row in values)
                projections['BINDS_TO']=compare(rows(product_bindings(list(selected.values()),normalize)),rows(reference_bindings(ref,normalize)))
                rows=lambda values,side:collections.Counter((r['file'],normalize(r['source'],source=r['file'],origin=side),normalize(r['target'],source=r['file'],origin=side),r['external'],r['line']) for r in values)
                projections['WIRES']=compare(rows(product_wires(list(selected.values()),include_file=True),'product'),rows(reference_wires(ref),'reference'))
            case.assertEqual(before,dict(product=digest(product_db),reference=digest(reference_db)))
            proof=dict(name=name,projections=projections,schema_gaps=gaps,unknown_reference_edges=unknown,
                raw_reference_counts=dict(counts),database_sha256=before,
                original_source_sha256=digest(source),original_xml_sha256=digest(resource))
            proof['equal']=not gaps and not unknown and all(row['equal'] for row in projections.values())
            (directory/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            proofs.append(proof);print(name,{key:{k:row[k] for k in ('product','reference','equal')} for key,row in projections.items()},flush=True)
        case.assertFalse(list(case.project.rglob('*.class')))
    finally:case.doCleanups()
    current=alignment.input_files();after=runtime_artifacts()
    report=dict(native_product_verified=True,proofs=proofs,inputs=inputs,artifacts=artifacts,
        changed_inputs=[p for p in inputs.keys()|current.keys() if inputs.get(p)!=current.get(p)],
        changed_artifacts=[p for p in artifacts.keys()|after.keys() if artifacts.get(p)!=after.get(p)],
        elapsed_seconds=round(time.monotonic()-started,3),full_semantic_diff_accepted=False)
    report['all_ok']=len(proofs)==3 and all(p['equal'] for p in proofs) and not report['changed_inputs'] and not report['changed_artifacts']
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
