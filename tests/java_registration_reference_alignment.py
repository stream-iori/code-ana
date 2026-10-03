#!/usr/bin/env python3
"""Strict source MVC, lifecycle/event and SOFA registration graph projection."""
import argparse
import collections
import json
import os
import sqlite3
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_declaration_reference_alignment import index_reference
from java_framework_alignment import Frameworks, BASE, DI, MVC, SOFA_API
from java_type_views import type_attributes


SOURCE = '''package p; import java.util.List;
interface Api {}
@com.alipay.sofa.runtime.api.annotation.SofaService(uniqueId="one") class Service implements Api {}
@org.springframework.web.bind.annotation.GetMapping("/composed") @interface Short {}
@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/base") class Use {
 @com.alipay.sofa.runtime.api.annotation.SofaReference Api api;
 @javax.annotation.PostConstruct void init(){}
 @org.springframework.context.event.EventListener(classes=String.class,condition="event.valid")
 void event(List< /* 原字节😀 */ String > values){}
 @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT)
 void transactional(String value){}
 @org.springframework.web.bind.annotation.GetMapping(path="/raw",produces="application/json",params="active")
 void read(@org.springframework.web.bind.annotation.RequestParam("names") List< /* 中文😀 */ String > names,String... rest){}
 @Short void shortRoute(){}
}
'''
DEFINITIONS = BASE | DI | MVC | SOFA_API | {
    'javax/annotation/PostConstruct.java':'package javax.annotation; public @interface PostConstruct {}',
    'org/springframework/context/event/EventListener.java':'package org.springframework.context.event; public @interface EventListener { Class<?>[] classes() default {}; String condition() default ""; }',
    'org/springframework/transaction/event/TransactionPhase.java':'package org.springframework.transaction.event; public enum TransactionPhase { BEFORE_COMMIT,AFTER_COMMIT }',
    'org/springframework/transaction/event/TransactionalEventListener.java':'package org.springframework.transaction.event; public @interface TransactionalEventListener { TransactionPhase phase() default TransactionPhase.AFTER_COMMIT; boolean fallbackExecution() default false; }',
}


def evidence(a, product):
    return dict(direct=a.get('direct_annotation' if product else 'annotationFqn'),
                root=a.get('root_annotation' if product else 'frameworkAnnotationRoot'),
                path=list(a.get('meta_path' if product else 'metaPath', [])))


def product_projection(facts):
    by = {f['id']:f for f in facts}
    handles = collections.defaultdict(list)
    for f in facts:
        if f['relation']=='handles_possible':
            handles[f['parent']].append(reference.canonical(by[f['target']]['signature']))
    result = collections.Counter()
    for f in facts:
        if f['relation'] not in ('java.spring.mvc','java.spring.handler','java.sofa'):
            continue
        owner = by[f['parent']]
        a = type_attributes(f)
        kind = f['relation']
        common = dict(owner=reference.canonical(owner['signature']),handles=sorted(handles[f['id']]))
        if kind=='java.spring.mvc':
            parameters=[]
            for p in a['parameters']:
                row=dict(name=p['name'],type=p['type'])
                if 'binding' in p:
                    row.update(binding=p['binding'],path=list(p['binding_evidence']['meta_path']),value=p['attributes'].get('value'))
                parameters.append(row)
            common.update(path=a['path'],http=a['http_method'],parameters=parameters,evidence=evidence(a,True),
                          consumes=list(a['consumes']),produces=list(a['produces']),params=list(a['params']))
        elif kind=='java.spring.handler':
            common.update(role=a['role'],mechanism=a['mechanism'],attributes=dict(a['written_attributes']),
                parameters=list(a['source_parameter_types']),condition=a['condition_status'],evidence=evidence(a,True))
            if 'transaction_phase' in a:
                common.update(phase=a['written_attributes'].get('phase',a['transaction_phase']['name']),delivery=a['delivery_status'])
        else:
            common.update(role=a['role'],mechanism=a['mechanism'],contract=a['contract'],unique=a['unique_id'],
                attributes=dict(a['written_attributes']),members=list(a['api_members']),supported=a['contract_supported'],
                transport=a['transport'],baseline=a['contract_baseline'])
        result[(f['source'],kind,json.dumps(common,ensure_ascii=False,sort_keys=True))]+=1
    return result


def reference_projection(database):
    result = collections.Counter()
    with sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True) as db:
        db.row_factory=sqlite3.Row
        nodes={n['id']:dict(n) for n in db.execute('SELECT * FROM nodes')}
        handles=collections.defaultdict(list)
        for e in db.execute("SELECT source_id,target_id FROM edges WHERE relation='HANDLES'"):
            handles[e['source_id']].append(reference.canonical(nodes[e['target_id']]['symbol_id']))
        for n in nodes.values():
            a=json.loads(n['metadata'] or '{}')
            if n['kind']=='ROUTE':
                parameters=[]
                for p in a['parameters']:
                    row=dict(name=p['name'],type=p['type'])
                    if 'binding' in p:row.update(binding=p['binding'],path=p['bindingMetaPath'],value=p.get('value'))
                    parameters.append(row)
                common=dict(owner=reference.canonical(a['handler']),path=a['path'],http=a['httpMethod'],
                    parameters=parameters,evidence=evidence(a,False),consumes=a.get('consumes',[]),
                    produces=a.get('produces',[]),params=a.get('params',[]))
                kind='java.spring.mvc'
            elif n['kind']=='FRAMEWORK_REGISTRATION' and a.get('mechanism','').startswith('spring.annotation.'):
                common=dict(owner=reference.canonical(n['label']),role=a['role'],mechanism=a['mechanism'],
                    attributes=a['attributes'],parameters=a['parameterTypes'],condition=a['condition_status'],evidence=evidence(a,False))
                if 'transactionPhase' in a:common.update(phase=a['transactionPhase'],delivery=a['delivery_status'])
                kind='java.spring.handler'
            elif n['kind']=='FRAMEWORK_REGISTRATION' and a.get('mechanism','').startswith('sofa.annotation.'):
                common=dict(owner=reference.canonical(a['injectionSite']),role=a['role'],mechanism=a['mechanism'],
                    contract=a['contract'],unique=a['uniqueId'],attributes=a['attributes'],members=sorted(a['apiMembers']),
                    supported=a['contractSupported'],transport=a['transport'],baseline=a['contractBaseline'])
                kind='java.sofa'
            else:continue
            common['handles']=sorted(handles[n['id']])
            result[(n['source_file'],kind,json.dumps(common,ensure_ascii=False,sort_keys=True))]+=1
    return result


def main():
    p=argparse.ArgumentParser();p.add_argument('--native',action='store_true');args=p.parse_args()
    base=alignment.ROOT/'target/java-alignment/evidence'/('registration-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    base.mkdir(parents=True)
    inputs,artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    case=Frameworks();case.setUp()
    if args.native:case.analyze=reference.native_analyze.__get__(case)
    proofs=[];started=time.monotonic()
    try:
        baseline=None
        for name,source in [('baseline',SOURCE),('body',SOURCE.replace('void init(){}','void init(){int body=1;}')),
                            ('declaration',SOURCE.replace('"/raw"','"/changed"').replace('uniqueId="one"','uniqueId="two"'))]:
            folder=base/name;folder.mkdir()
            files=DEFINITIONS|{'Use.java':source}
            context=case.context('app',files,spring=True,sofa=True)
            full=case.analyze([context],cache=True)['facts']
            if baseline is not None:case.assertEqual(case.merged(baseline,case.analyze([context],baseline,cache=True)),sorted(full,key=lambda f:f['id']))
            baseline=full
            database=index_reference(folder,files)
            left,right=product_projection(full),reference_projection(database)
            case.assertEqual(right.total(),7)
            (folder/'product.json').write_text(json.dumps(full,ensure_ascii=False)+'\n')
            proof=dict(name=name,**reference.compare(left,right),product_sha256=reference.digest(folder/'product.json'),reference_sha256=reference.digest(database))
            (folder/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            proofs.append(proof);print(name,proof['product'],proof['reference'],proof['equal'],flush=True)
    finally:case.doCleanups()
    current,now=alignment.input_files(),reference.probe_artifacts(args.native)
    changed_inputs=sorted(k for k in inputs.keys()|current.keys() if inputs.get(k)!=current.get(k))
    changed_artifacts=sorted(k for k in artifacts.keys()|now.keys() if artifacts.get(k)!=now.get(k))
    report=dict(kind='frozen_registration_attribute_and_handles_projection',native=args.native,proofs=proofs,
        inputs=inputs,artifacts=artifacts,changed_inputs=changed_inputs,changed_artifacts=changed_artifacts,
        reference_commit=reference.REFERENCE_COMMIT,reference_sha256=reference.REFERENCE_SHA,
        full_semantic_diff_accepted=False,elapsed_seconds=round(time.monotonic()-started,3))
    report['all_ok']=len(proofs)==3 and all(p['equal'] for p in proofs) and not changed_inputs and not changed_artifacts
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
