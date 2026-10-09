#!/usr/bin/env python3
"""Independent Spring bean constraints and constructor/non-container parameter parity.

Aliases are a bean's names, not an Anatomist protocol requirement. This projection
does not certify the complete annotation, XML, framework or real-project graph.
"""
import argparse
import json
import os
import sqlite3
import subprocess
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_framework_alignment import BASE, DI, SOFA_API, Frameworks
from java_model_tests import JDK8, JDK25
from java_type_views import type_attributes


SOURCE = '''interface Api {}
@org.springframework.stereotype.Component @javax.annotation.Priority(10)
@org.springframework.context.annotation.Profile("prod") interface Marker {}
@org.springframework.context.annotation.Configuration
@org.springframework.context.annotation.Profile({ "prod", /* 原字节😀 */ "cloud" }) class Config {
 @org.springframework.beans.factory.annotation.Qualifier("constructor-level")
 Config(Api ordinary,@com.alipay.sofa.runtime.api.annotation.SofaReference Api sofa,
        @org.springframework.beans.factory.annotation.Value("fixed") String fixed){}
 @javax.annotation.Resource void setURL(Api arbitrary) {}
 @org.springframework.beans.factory.annotation.Autowired Api[] many;
 @javax.annotation.Resource(type=Object.class,lookup="") Api limited;
 @org.springframework.context.annotation.Bean(name={"first","alias"})
 @org.springframework.context.annotation.Lazy(false)
 Api api(@com.alipay.sofa.runtime.api.annotation.SofaReference Api sofa, Api ordinary) { return null; }
}
'''
DEFINITIONS = BASE | DI | SOFA_API | {
    'org/springframework/context/annotation/Configuration.java': 'package org.springframework.context.annotation; public @interface Configuration { String value() default ""; }',
    'org/springframework/context/annotation/Profile.java': 'package org.springframework.context.annotation; public @interface Profile { String[] value(); }',
    'org/springframework/context/annotation/Lazy.java': 'package org.springframework.context.annotation; public @interface Lazy { boolean value() default true; }',
    'javax/annotation/Priority.java': 'package javax.annotation; public @interface Priority { int value(); }',
}


def product_projection(facts):
    by_id = {f['id']: f for f in facts}
    beans = {}
    injections = []
    for f in facts:
        if f['relation'] == 'java.bean':
            a = f['attributes']
            owner = by_id[f['parent']]
            aliases = a['aliases']
            key = (owner['signature'], tuple(aliases), f['owner']['world'])
            value = dict(type=a['type'], priority=a.get('priority_selection_unknown', False),
                         constraints=a.get('configuration_constraints', []))
            group = beans.setdefault(key, dict(value=value, names=[]))
            if group['value'] != value or f['name'] in group['names']:
                raise ValueError('BEAN_ALIAS_EVIDENCE_INCONSISTENT')
            group['names'].append(f['name'])
        elif f['relation'] == 'java.spring.injection':
            a = type_attributes(f)
            site = by_id[f['parent']]
            owner = by_id[site['parent']] if site['attributes']['element_kind'] == 'PARAMETER' else site
            identity = owner['signature']
            if a['role'] == 'setter':
                identity = identity.split('#')[0] + '#setter:' + a['injection_point']
            injections.append(dict(owner=identity, point=a['injection_point'],
                type=a['injected_type']['display'].replace(', ', ','),
                implicit=a['implicit_single_constructor'], qualifier=a.get('qualifier'),
                shape=a['shape'], collection=a.get('collection_type'), element=a.get('element_type'),
                cardinality=a.get('cardinality'), required=str(a['required']).lower() if 'required' in a else None,
                resource_default=a.get('resource_default_name'), resource_name=a.get('resource_name'),
                resource_written=dict(a.get('resource_written_attributes', {})), unsupported=a.get('matching_unsupported', False)))
    result = []
    for (owner, aliases, _world), group in beans.items():
        if set(group['names']) != set(aliases):
            raise ValueError('BEAN_ALIAS_NAMES_INCOMPLETE')
        result.append(dict(owner=owner, names=list(aliases), **group['value']))
    return dict(beans=sorted(result, key=lambda x: x['owner']), injections=sorted(injections, key=lambda x: (x['owner'],x['point'])))


def reference_projection(database, annotation_only=False):
    with sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        nodes = {r['id']: dict(r) for r in db.execute('SELECT * FROM nodes')}
        selected_beans = {n['id'] for n in nodes.values() if n['kind'] == 'BEAN'
                          and (not annotation_only or json.loads(n['metadata']).get('source') == 'annotation')}
        owners = {}
        for edge in db.execute("SELECT * FROM edges WHERE relation='DEFINED_BY'"):
            if edge['source_id'] not in selected_beans:
                continue
            target = nodes.get(edge['target_id'])
            owner = (target['symbol_id'] if target else
                     edge['external_target_symbol'] or edge['external_target_fqn'])
            if not owner:
                raise ValueError('REFERENCE_BEAN_OWNER_MISSING: '+edge['source_id'])
            if edge['source_id'] in owners and owners[edge['source_id']] != owner:
                raise ValueError('REFERENCE_BEAN_OWNER_AMBIGUOUS: '+edge['source_id'])
            owners[edge['source_id']] = owner
        beans, injections = [], []
        for n in nodes.values():
            if n['kind'] != 'BEAN':
                continue
            a = json.loads(n['metadata'])
            if annotation_only and a.get('source') != 'annotation':
                continue
            beans.append(dict(owner=owners[n['id']], names=a.get('aliases', [n['label']]),
                              type=a.get('className', a.get('returnType')),
                              priority=a.get('prioritySelectionUnknown', False),
                              constraints=a.get('configurationConstraints', [])))
        for e in db.execute("SELECT source_id,metadata FROM edges WHERE relation='INJECTS'"):
            a = json.loads(e['metadata'])
            parts = a['injectionSite'].split(':')
            role, name = parts[0], parts[-1]
            if role == 'field':
                owner = nodes[e['source_id']]['symbol_id'] + '#' + name
            elif role == 'setter':
                owner = nodes[e['source_id']]['symbol_id'] + '#setter:' + name
            else:
                type_name = nodes[e['source_id']]['symbol_id']
                prefix = type_name+'.'
                if not parts[1].startswith(prefix):
                    raise ValueError('INJECTION_CALLABLE_OWNER_MISMATCH: '+parts[1])
                owner = type_name+'#'+parts[1][len(prefix):]
                if role == 'constructor':
                    prefix, args = owner.split('(', 1)
                    owner = prefix.split('#')[0] + '#<init>(' + args
            injections.append(dict(owner=owner, point=a['injectionPointName'],
                type=a['injectedType'].replace(', ', ','),
                implicit=a.get('implicitSingleConstructor', False), qualifier=a.get('qualifier'),
                shape=a['injectionShape'], collection=a.get('collectionType'), element=a.get('elementType'),
                cardinality=a.get('cardinality'), required=a.get('required'),
                resource_default=a.get('resourceDefaultName'), resource_name=a.get('resourceName'),
                resource_written=a.get('resourceAttributes', {}), unsupported=a.get('matchingUnsupported', False)))
        return dict(beans=sorted(beans, key=lambda x: x['owner']), injections=sorted(injections, key=lambda x: (x['owner'],x['point'])))


def reference_index(base, source):
    for name, contents in (DEFINITIONS | {'Use.java': source}).items():
        p = base / 'app' / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(contents)
    config = base / '.anatomist/config.toml'
    config.parent.mkdir()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\n'
                      'javac_analysis_threads=1\njavac_worker_heap_mb=128\nspring_xml=false\n')
    from java_anatomist_reference import REFERENCE
    jar = REFERENCE
    if reference.digest(jar) != reference.REFERENCE_SHA:
        raise ValueError('FROZEN_REFERENCE_JAR_CHANGED')
    command = [str(JDK25/'bin/java'), '-Xmx512m', '-jar', str(jar), 'index', str(base),
               '--source-root', 'app@MAIN=app', '--jdk-home', str(JDK8), '--java-version', '8',
               '--lombok', 'off', '--vm-classpath', 'true', '--output', str(base/'index.db'),
               '--full', '--no-classpath']
    with (base/'reference.log').open('wb') as log:
        result = subprocess.run(command, stdout=log, stderr=log, timeout=120,
                                env=dict(os.environ, ANATOMIST_HOME=str(base/'reference-state')))
    if result.returncode:
        raise RuntimeError((base/'reference.log').read_text()[-4000:])
    return reference_projection(base/'index.db')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--native', action='store_true')
    args = p.parse_args()
    evidence = alignment.ROOT/'target/java-alignment/evidence'/('framework-reference-'
                +time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    evidence.mkdir()
    frozen = alignment.input_files()
    artifacts = reference.probe_artifacts(args.native)
    case = Frameworks()
    case.setUp()
    if args.native:
        case.analyze = reference.native_analyze.__get__(case)
    proofs = []
    try:
        baseline = None
        for name, source in [('constraints', SOURCE), ('body', SOURCE.replace('return null;', 'return (Api)null;')),
                             ('qualified', 'package p; '+SOURCE),
                             ('removed', SOURCE.replace('@javax.annotation.Priority(10)', '')
                              .replace('@org.springframework.context.annotation.Profile("prod")', '')
                              .replace('@org.springframework.context.annotation.Lazy(false)', '')
                              .replace('@com.alipay.sofa.runtime.api.annotation.SofaReference ', ''))]:
            base = evidence/name
            base.mkdir()
            context = case.context('app', DEFINITIONS | {'Use.java': source}, spring=True, sofa=True)
            full = case.analyze([context], cache=True)['facts']
            if baseline is not None:
                delta = case.analyze([context], baseline, cache=True)
                case.assertEqual(case.merged(baseline, delta), sorted(full, key=lambda f: f['id']))
            baseline = full
            left, right = product_projection(full), reference_index(base, source)
            from java_framework_inventory_diff import select_facts
            with sqlite3.connect(':memory:') as inventory:
                inventory.row_factory = sqlite3.Row
                inventory.execute('CREATE TABLE facts(id,relation,body)')
                inventory.executemany('INSERT INTO facts VALUES(?,?,?)',
                    ((f['id'], f['relation'], json.dumps(f)) for f in full))
                case.assertEqual(product_projection(select_facts(inventory)), left)
            case.assertEqual(len(left['beans']), 3)
            case.assertEqual(len(left['injections']), 7 if name == 'removed' else 5)
            (base/'product.json').write_text(json.dumps(full, ensure_ascii=False)+'\n')
            proof = dict(name=name, product=left, reference=right, equal=left == right,
                         product_sha256=reference.digest(base/'product.json'),
                         reference_sha256=reference.digest(base/'index.db'))
            (base/'comparison.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2)+'\n')
            proofs.append(proof)
            print(name, 'equal' if proof['equal'] else 'different', flush=True)
    finally:
        case.doCleanups()
    current, now = alignment.input_files(), reference.probe_artifacts(args.native)
    drift = sorted(k for k in frozen.keys() | current.keys() if frozen.get(k) != current.get(k))
    changed = sorted(k for k in artifacts.keys() | now.keys() if artifacts.get(k) != now.get(k))
    report = dict(kind='frozen_spring_bean_constraints_and_parameter_projection', native=args.native,
                  proofs=proofs, inputs=frozen, artifacts=artifacts, changed_inputs=drift,
                  changed_artifacts=changed, full_semantic_diff_accepted=False)
    report['all_ok'] = all(p['equal'] for p in proofs) and not drift and not changed
    (evidence/'receipt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(evidence/'receipt.json', flush=True)
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
