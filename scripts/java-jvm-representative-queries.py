#!/usr/bin/env python3
"""Check preselected public graph queries against the independent frozen DB.

This gate covers representative navigation; it never claims the whole Q1–Q10
matrix or native equivalence. Every CLI page is retained, including failures.
"""
import argparse
import collections
import functools
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from acceptance_support import sha256
from java_inventory_canonical import Canonical, compiler_lines, verify_source_inputs
from java_real_acceptance import atomic_json
from java_declaration_inventory_diff import connection

spec = importlib.util.spec_from_file_location('public_pages', ROOT / 'scripts/java-jvm-project-queries.py')
pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pages)


class Expectations:
    def __init__(self, product, reference, project):
        self.product, self.reference, self.project = product, reference, project
        self.canonical = Canonical(product, reference, project)
        self.nodes = {row['id']: dict(row) for row in reference.execute('SELECT * FROM nodes')}

    @functools.lru_cache(maxsize=512)
    def lines(self, source):
        return compiler_lines((self.project / source).read_bytes().decode('utf-8'))

    def offset(self, source, line, column, last=False):
        if not line or not column:
            return None
        lines = self.lines(source)
        return sum(len(text.encode()) for text in lines[:line-1]) + len(
            lines[line-1].encode('utf-16-le')[:(column-1+last)*2].decode('utf-16-le').encode())

    def span(self, row):
        source = row['source_file']
        return (source, self.offset(source, row['begin_line'], row['begin_column']),
                self.offset(source, row['end_line'], row['end_column'], True))

    def reference_symbol(self, identity, external=''):
        node = self.nodes.get(identity, {})
        return self.canonical(node.get('symbol_id') or external or identity,
                              kind=node.get('kind'), source=node.get('source_file', ''))

    @functools.lru_cache(maxsize=16384)
    def fact(self, identity):
        row = self.product.execute('SELECT body FROM facts WHERE id=?', [identity]).fetchone()
        if row is None:
            raise ValueError('PUBLIC_QUERY_TARGET_NOT_PUBLISHED: ' + identity)
        return json.loads(row['body'])

    def product_symbol(self, identity):
        fact = self.fact(identity)
        return self.canonical(fact['signature'], kind=fact['attributes'].get('element_kind'),
                              source=fact['source'], origin='product')

    @staticmethod
    def product_span(fact):
        span = fact.get('span') or {}
        return fact['source'], span.get('start'), span.get('end')

    def calls(self, identity, incoming):
        where = 't.target_id=?' if incoming else 'o.caller_id=?'
        sql = '''SELECT t.*,o.caller_id,s.site_pk FROM call_site_targets t
                 JOIN call_sites s ON s.site_pk=t.call_site_pk
                 JOIN call_site_owners o ON o.owner_pk=s.owner_pk WHERE ''' + where
        sites = collections.defaultdict(set)
        for row in self.reference.execute(sql, [identity]):
            if row['resolution_status'] != 'exact':
                continue
            target = self.reference_symbol(row['target_id'], row['external_target_symbol'] or row['external_target_fqn'])
            sites[(self.reference_symbol(row['caller_id']), target)].add(row['site_pk'])
        return collections.Counter({key: len(value) for key, value in sites.items()})

    def call_sites(self, identity, incoming):
        sql = '''SELECT DISTINCT s.*,o.source_file FROM call_sites s
                 JOIN call_site_owners o ON o.owner_pk=s.owner_pk '''
        if incoming:
            sql += 'JOIN call_site_targets t ON t.call_site_pk=s.site_pk WHERE t.target_id=?'
        else:
            sql += 'WHERE o.caller_id=?'
        return collections.Counter(self.span(row) for row in self.reference.execute(sql, [identity]))

    def annotations(self, identity):
        return collections.Counter((row['annotation_fqn'], row['raw_name'], row['target_kind'],
            row['target_path'] or '', json.dumps(json.loads(row['attributes'] or '{}'), sort_keys=True))
            for row in self.reference.execute('SELECT * FROM annotations WHERE node_id=?', [identity]))

    def meta_annotations(self, identity):
        # Independent frozen AnnotationQueryService expandedSql(), reduced to
        # columns asserted here. Its SQL recursion is separate from Rust BFS.
        sql = '''WITH RECURSIVE expanded(annotation_id,name,raw_name,attributes,target_kind,target_path,depth,via,seen) AS (
            SELECT id,COALESCE(annotation_fqn,raw_name),raw_name,attributes,target_kind,target_path,0,
                   COALESCE(annotation_fqn,raw_name),'>'||COALESCE(annotation_fqn,raw_name)||'>'
            FROM annotations WHERE node_id=? UNION ALL
            SELECT x.annotation_id,m.meta_annotation_fqn,m.raw_name,NULL,x.target_kind,x.target_path,
                   x.depth+1,x.via||'>'||m.meta_annotation_fqn,x.seen||m.meta_annotation_fqn||'>'
            FROM expanded x JOIN (SELECT annotation_fqn,meta_annotation_fqn,min(raw_name) AS raw_name
                 FROM annotation_meta_relations GROUP BY annotation_fqn,meta_annotation_fqn) m
                 ON m.annotation_fqn=x.name WHERE x.depth<16 AND m.meta_annotation_fqn IS NOT NULL
                 AND instr(x.seen,'>'||m.meta_annotation_fqn||'>')=0)
            SELECT * FROM expanded'''
        return collections.Counter((row['name'],row['raw_name'],row['target_kind'],row['target_path'] or '',
            json.dumps(json.loads(row['attributes']) if row['attributes'] is not None else None,sort_keys=True),
            row['via'],row['depth'],row['depth']==0) for row in self.reference.execute(sql,[identity]))

    def relations(self, identity, incoming, names):
        sql = 'SELECT * FROM edges WHERE ' + ('target_id=?' if incoming else 'source_id=?')
        result = collections.Counter()
        for row in self.reference.execute(sql, [identity]):
            if row['relation'] not in names:
                continue
            relation = {'INHERITS':'extends', 'IMPLEMENTS':'implements', 'HANDLES':'handles_possible',
                        'WIRES':'wires_possible', 'XML_REFERS_TO':'config', 'BINDS_TO':'config_member'}.get(
                            row['relation'], row['relation'].lower())
            if row['relation'] == 'XML_REFERS_TO' and row['is_external']:
                relation = 'unresolved_config'
            target = self.reference_symbol(row['target_id'], row['external_target_symbol'] or row['external_target_fqn'])
            result[(relation, self.reference_symbol(row['source_id']), target, *self.span(row))] += 1
        return result

    def observed_relations(self, rows):
        return collections.Counter((row['relation'], self.product_symbol(row['parent']),
            self.product_symbol(row['target']), *self.product_span(row)) for row in rows)

    def configuration_tree(self, identity):
        seen, frontier = set(), [(identity,0)]
        while frontier:
            current, depth = frontier.pop()
            if current in seen:continue
            seen.add(current)
            children = [row['target_id'] for row in self.reference.execute(
                "SELECT target_id FROM edges WHERE source_id=? AND relation IN ('CONFIGURES','XML_CONTAINS') AND is_external=0",[current])]
            if depth>=16 and children:raise ValueError('INDEPENDENT_CONFIGURATION_DEPTH_EXCEEDS_CASE')
            frontier.extend((child,depth+1) for child in children)
        return collections.Counter(self.reference_symbol(current) for current in seen)


def main():
    parser = argparse.ArgumentParser()
    for name in ('publication-receipt', 'query-plan', 'reference-database', 'reference-receipt', 'project', 'evidence'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    parser.add_argument('--query-binaries', type=pathlib.Path)
    args = parser.parse_args()
    publication = json.loads(args.publication_receipt.read_text())
    plan = json.loads(args.query_plan.read_text())
    original = json.loads(args.reference_receipt.read_text())
    if publication.get('backend') != 'jvm' or publication.get('publication_accepted') is not True:
        raise ValueError('ACCEPTED_JVM_PUBLICATION_REQUIRED')
    if original.get('state') != 'finished' or original.get('execution', {}).get('status') != 'passed' or original.get('changed_inputs') != []:
        raise ValueError('INDEPENDENT_REFERENCE_NOT_ACCEPTED')
    if plan['unconfirmed_contexts'] or plan['inputs_manifest_sha256'] != publication['inputs_manifest_sha256']:
        raise ValueError('INDEPENDENT_QUERY_SELECTION_REQUIRED')
    if sha256(args.reference_database) != plan['reference_database_sha256'] or sha256(args.reference_receipt) != plan['reference_receipt_sha256']:
        raise ValueError('INDEPENDENT_REFERENCE_DRIFT')
    args.evidence.mkdir(parents=True, exist_ok=False)
    runtime = args.query_binaries or pathlib.Path(publication['runtime']['binary_directory'])
    database = pathlib.Path(publication['published_facts'])
    inputs = {str(path.resolve()):sha256(path) for path in [args.publication_receipt,
        args.query_plan,args.reference_database,args.reference_receipt,database,pathlib.Path(__file__),
        ROOT/'tests/java_inventory_canonical.py',ROOT/'scripts/java-jvm-project-queries.py',
        runtime/'codeana-cli',runtime/'codeana-daemon']}
    product, reference = connection(database), connection(args.reference_database)
    expected = Expectations(product, reference, args.project)
    report = dict(status='running', kind='independent_representative_public_queries', cases=[],
        revision=publication['published_revision'], inputs=inputs, publication_runtime=publication['runtime'],
        query_binaries=str(runtime), identity_aliases=expected.canonical.receipt,
        source_proof=verify_source_inputs(product,args.project), complete_query_matrix=False,native_accepted=False)
    receipt = args.evidence/'receipt.json'
    home = args.publication_receipt.parent/'home'
    command = [str(runtime/'codeana-cli'),'--home',str(home),'--project',str(args.project)]
    daemon = None
    def check(name, argv, wanted, normalize):
        directory=args.evidence/name;directory.mkdir()
        row=dict(id=name,status='failed')
        try:
            records, proof = pages.query(command+argv+['--revision',report['revision'],
                '--max-records','7','--max-output-bytes','8388608'],directory,'query')
            if any(page['terminal'].get('revision')!=report['revision'] for page in proof):
                raise ValueError('PUBLIC_QUERY_REVISION_DRIFT')
            observed=normalize(records)
            atomic_json(directory/'results.json',records);atomic_json(directory/'pages.json',proof)
            row.update(records=len(records),pages=len(proof),expected=sum(wanted.values()),
                observed=sum(observed.values()),missing=list((wanted-observed).items()),extra=list((observed-wanted).items()))
            if observed!=wanted:raise ValueError('INDEPENDENT_PUBLIC_QUERY_MISMATCH')
            row['status']='passed'
        except Exception as error:row['failure']=str(error)
        report['cases'].append(row);atomic_json(receipt,report);print(name+': '+row['status'],flush=True)
    try:
        with (args.evidence/'daemon.log').open('wb') as log:
            daemon=subprocess.Popen([str(runtime/'codeana-daemon'),'--home',str(home)],stdout=log,stderr=log)
            deadline=time.monotonic()+15
            while subprocess.run(command+['daemon','status'],capture_output=True,timeout=3).returncode:
                if daemon.poll() is not None or time.monotonic()>deadline:raise ValueError('QUERY_DAEMON_START_FAILED')
                time.sleep(.05)
            for selected in plan['representative_selections']:
                family=selected['family'];anchor=selected['locator'];identity=anchor['id']
                scope=anchor['scope']
                selector=anchor['symbol_id'];options=['--module',anchor['module'],'--scope',scope]
                expected_symbol=expected.reference_symbol(identity)
                check(family+'-resolve',['resolve',selector,'--unique',*options],collections.Counter([expected_symbol]),
                    lambda rows:collections.Counter(expected.product_symbol(row['id']) for row in rows))
                if family in ('high_fan_out','high_fan_in'):
                    incoming=family=='high_fan_in';direction='in' if incoming else 'out'
                    check(family+'-calls',['calls',selector,'--direction',direction,'--depth','1',*options],
                        expected.calls(identity,incoming),lambda rows:collections.Counter({
                            (expected.product_symbol(row['from']),expected.product_symbol(row['to'])):len(row['sites']) for row in rows}))
                    check(family+'-sites',['call-sites',selector,'--direction',direction,*options],
                        expected.call_sites(identity,incoming),lambda rows:collections.Counter(expected.product_span(row) for row in rows))
                if family in ('annotation','spring_bean'):
                    check(family+'-annotations',['annotations',selector,*options],expected.annotations(identity),
                        lambda rows:collections.Counter((row['attributes']['annotation'],row['attributes']['raw_name'],
                            row['attributes']['target_kind'],row['attributes']['target_path'] or '',
                            json.dumps(row['attributes']['values'],sort_keys=True)) for row in rows))
                    check(family+'-meta',['annotations',selector,'--meta',*options],expected.meta_annotations(identity),
                        lambda rows:collections.Counter((row['attributes']['annotation'],row['attributes'].get('raw_name'),
                            row['attributes'].get('target_kind'),row['attributes'].get('target_path') or '',
                            json.dumps(row['attributes']['values'],sort_keys=True),row['attributes']['via'],
                            row['attributes']['meta_depth'],row['attributes']['direct']) for row in rows))
                if family=='type':
                    check(family+'-hierarchy',['type-relations',selector,'--direction','out',*options],
                        expected.relations(identity,False,{'INHERITS','IMPLEMENTS'}),expected.observed_relations)
                if family in ('field_references','xml_bean'):
                    for incoming in [False,True]:
                        direction='in' if incoming else 'out';group='reads,writes' if family=='field_references' else 'config'
                        names={'READS','WRITES'} if family=='field_references' else {'DEFINED_BY','XML_REFERS_TO','PARENT_BEAN','BINDS_TO','INJECTS','WIRES','HANDLES'}
                        check(family+'-'+direction,['references',selector,'--direction',direction,'--relation',group,*options],
                            expected.relations(identity,incoming,names),expected.observed_relations)
                if family=='xml_bean':
                    check(family+'-structure',['structure',selector,'--depth','16',*options],
                        expected.configuration_tree(identity),lambda rows:collections.Counter(
                            expected.product_symbol(row['id']) for row in rows))
        report['changed_inputs']=[path for path,digest in inputs.items() if sha256(path)!=digest]
        report['expected_cases']=21
        report['status']='passed' if len(report['cases'])==report['expected_cases'] and all(row['status']=='passed' for row in report['cases']) and not report['changed_inputs'] else 'failed'
    except BaseException as error:report.update(status='failed',failure=str(error))
    finally:
        if daemon is not None and daemon.poll() is None:
            daemon.terminate()
            try:daemon.wait(timeout=15)
            except subprocess.TimeoutExpired:daemon.kill();daemon.wait(timeout=5)
        product.close();reference.close();atomic_json(receipt,report)
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':
    raise SystemExit(main())
