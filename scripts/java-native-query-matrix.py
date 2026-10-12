#!/usr/bin/env python3
"""Additional frozen-project hierarchy, dispatch, aggregation and text proofs.

Seeds and oracle outputs are fixed before starting the product daemon. Original
query APIs run from the independently built JAR, not the migrated source/bundle.
"""
import argparse
import collections
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tests'))
from acceptance_support import sha256
from java_fresh_reference import reference as checked_oracle
from java_declaration_inventory_diff import connection
from java_inventory_canonical import verify_source_inputs
from java_real_acceptance import atomic_json

spec=importlib.util.spec_from_file_location('representatives',ROOT/'scripts/java-jvm-representative-queries.py')
navigation=importlib.util.module_from_spec(spec);spec.loader.exec_module(navigation)


def choose(nodes, edges, kinds, minimum=2, minimum_depth=2):
    children=collections.defaultdict(list)
    for row in edges: children[row['target_id']].append(row['source_id'])
    for identity in sorted(nodes):
        node=nodes[identity]
        if node['kind'] not in kinds or not node.get('source_file'): continue
        queue=collections.deque([(identity,0)]);seen={identity};concrete=[]
        while queue:
            current,depth=queue.popleft()
            if depth>=8:continue
            for child in sorted(children[current]):
                if child in seen:continue
                seen.add(child);queue.append((child,depth+1));value=nodes.get(child,{})
                if (value.get('kind') in ('CLASS','ENUM','RECORD','ANONYMOUS_CLASS','METHOD')
                        and 'abstract' not in json.loads(value.get('modifiers') or '[]')):
                    concrete.append((child,depth+1))
        if len(concrete)>=minimum and any(depth>=minimum_depth for _,depth in concrete):
            return {key:node.get(key) for key in ['id','symbol_id','kind','source_file','module','scope','modifiers']}
    raise ValueError('INDEPENDENT_TRANSITIVE_SEED_NOT_FOUND')


def main():
    parser=argparse.ArgumentParser()
    for name in ['publication-receipt','query-plan','reference-database','reference-receipt','project','query-binaries','evidence']:
        parser.add_argument('--'+name,type=pathlib.Path,required=True)
    args=parser.parse_args(); args.evidence.mkdir(parents=True,exist_ok=False)
    publication=json.loads(args.publication_receipt.read_text()); plan=json.loads(args.query_plan.read_text())
    original=json.loads(args.reference_receipt.read_text())
    if publication.get('backend')!='native' or publication.get('publication_accepted') is not True:raise ValueError('ACCEPTED_NATIVE_PUBLICATION_REQUIRED')
    if original.get('state')!='finished' or original.get('changed_inputs')!=[] or original.get('execution',{}).get('status')!='passed':raise ValueError('INDEPENDENT_REFERENCE_REQUIRED')
    if sha256(args.reference_database)!=plan['reference_database_sha256'] or sha256(args.reference_receipt)!=plan['reference_receipt_sha256']:raise ValueError('INDEPENDENT_REFERENCE_DRIFT')
    oracle,oracle_proof=checked_oracle()
    product=connection(pathlib.Path(publication['published_facts'])); reference=connection(args.reference_database)
    expected=navigation.Expectations(product,reference,args.project)
    runtime_root=choose(expected.nodes,list(reference.execute("SELECT source_id,target_id FROM edges WHERE relation IN ('INHERITS','IMPLEMENTS') AND is_external=0 AND target_id IS NOT NULL")),{'INTERFACE'})
    callable_root=choose(expected.nodes,list(reference.execute("SELECT source_id,target_id FROM edges WHERE relation='OVERRIDES' AND is_external=0 AND target_id IS NOT NULL")) ,{'METHOD'},minimum=1,minimum_depth=1)
    dispatch=reference.execute('''SELECT s.*,o.source_file FROM call_sites s
        JOIN call_site_owners o ON o.owner_pk=s.owner_pk JOIN call_site_targets t ON t.call_site_pk=s.site_pk
        JOIN nodes n ON n.id=t.target_id WHERE t.resolution_status='exact' AND n.kind='METHOD'
        AND n.id LIKE ? ORDER BY s.site_pk LIMIT 1''',[runtime_root['id']+'#%']).fetchone()
    if dispatch is None:raise ValueError('INDEPENDENT_VIRTUAL_SITE_NOT_FOUND')
    dispatch=dict(dispatch); dispatch['id']='callsite:sha256:'+dispatch['stable_hash'].hex();dispatch.pop('stable_hash')
    dispatch_span=expected.span(dispatch)
    matching=[]
    for row in product.execute("SELECT body FROM facts WHERE kind='call_site' AND source=?",[dispatch_span[0]]):
        fact=json.loads(row['body'])
        if expected.product_span(fact)==dispatch_span:matching.append(fact)
    if len(matching)!=1:raise ValueError('CAPTURED_CALL_SITE_SPAN_UNIQUE_REQUIRED')
    site=matching[0]
    selection=dict(runtime_root=runtime_root,callable_root=callable_root,dispatch=dispatch,
        captured_site_id=site['id'],depth=8,instantiability=['yes','all','unknown'],world='workspace-open')
    atomic_json(args.evidence/'selection.json',selection)
    witness=ROOT/'tests/java-migration/QueryWitness.java';classes=args.evidence/'oracle-classes';classes.mkdir()
    java=pathlib.Path.home()/'.sdkman/candidates/java/25.3.4+1.r25-graal/bin/java';javac=java.with_name('javac')
    with (args.evidence/'compile.stdout').open('wb') as out,(args.evidence/'compile.stderr').open('wb') as err:
        subprocess.run([str(javac),'-cp',str(oracle),'-d',str(classes),str(witness)],stdout=out,stderr=err,check=True,timeout=30)
    def observe(name,operation,identity,depth,option,world='workspace-open'):
        argv=[str(java),'-Xmx2g','--enable-native-access=ALL-UNNAMED','-cp',str(classes)+':'+str(oracle),'QueryWitness',
            str(args.reference_database),operation,identity,str(depth),option,world]
        with (args.evidence/(name+'.oracle.stdout')).open('wb') as out,(args.evidence/(name+'.oracle.stderr')).open('wb') as err:
            result=subprocess.run(argv,stdout=out,stderr=err,timeout=120)
        if result.returncode:raise ValueError('ORIGINAL_QUERY_FAILED: '+name)
        value=json.loads((args.evidence/(name+'.oracle.stdout')).read_text());atomic_json(args.evidence/(name+'.oracle.json'),dict(command=argv,result=value))
        return value
    oracle_queries={}
    for depth in [1,8]:
        for filter in ['yes','all','unknown']:
            name=f'runtime-{depth}-{filter}';oracle_queries[name]=observe(name,'runtime',runtime_root['id'],depth,filter)
    oracle_queries['callable']=observe('callable','callable',callable_root['id'],8,'incoming')
    for algorithm in ['exact','cha']:
        oracle_queries['dispatch-'+algorithm]=observe('dispatch-'+algorithm,'dispatch',dispatch['id'],8,algorithm)
    # Independent raw-row counts, without the product's GROUP BY implementation.
    modules=collections.Counter(); units={(r['path'],r['world']):r['module'] for r in product.execute('SELECT * FROM source_units WHERE auxiliary=0')}
    for row in product.execute('SELECT source,world FROM facts'):
        if (row['source'],row['world']) in units:modules[units[row['source'],row['world']]]+=1
    packages=collections.Counter(r[0] for r in product.execute("SELECT p.package FROM declaration_projection p JOIN facts f USING(id) WHERE f.kind='declaration'"))
    folded=collections.Counter()
    for package,count in packages.items():folded['.'.join(package.split('.')[:2])]+=count
    package_by_id = {row['id']:row['package'] for row in product.execute('SELECT id,package FROM declaration_projection')}
    dependencies = collections.Counter()
    for row in product.execute("SELECT parent,target,relation FROM facts WHERE kind IN ('reference','relation') AND relation IN ('reads','writes')"):
        source_package=package_by_id.get(row['parent']);target_package=package_by_id.get(row['target'])
        if source_package is not None and target_package is not None and source_package!=target_package:
            dependencies[(source_package,target_package,row['relation'])]+=1
    dependency_package = min({source for source,_,_ in dependencies},key=lambda package:(
        -sum(count for (source,_,_),count in dependencies.items() if source==package),package))
    dependency_counts = {}
    for direction in ['out','in','both']:
        dependency_counts[direction] = collections.Counter({key:count for key,count in dependencies.items()
            if ((direction in ('out','both') and key[0]==dependency_package)
                or (direction in ('in','both') and key[1]==dependency_package))})
    family=next(row for row in plan['representative_selections'] if row['family']=='high_fan_out')['locator']
    source_path=family['source_file'];captured=(args.project/source_path).read_bytes();lines=captured.decode().splitlines();digest=sha256(args.project/source_path)
    pattern='MolpayApiStandardConfig'
    def wanted_text(ignore=False,regex=False):
        expression=re.compile(re.escape(pattern)+('.*' if regex else ''),re.IGNORECASE if ignore else 0)
        return collections.Counter((source_path,n+1,line,digest,
            json.dumps([dict(line=i+1,text=lines[i]) for i in range(max(0,n-1),n)],sort_keys=True),
            json.dumps([dict(line=i+1,text=lines[i]) for i in range(n+1,min(len(lines),n+2))],sort_keys=True))
            for n,line in enumerate(lines) if expression.search(line))
    runtime=args.query_binaries;home=args.publication_receipt.parent/'home'
    command=[str(runtime/'codeana-cli'),'--home',str(home),'--project',str(args.project)]
    inputs={str(path.resolve()):sha256(path) for path in [args.publication_receipt,args.query_plan,args.reference_database,args.reference_receipt,
        pathlib.Path(publication['published_facts']),pathlib.Path(__file__),ROOT/'scripts/java-jvm-representative-queries.py',ROOT/'scripts/java-jvm-project-queries.py',
        ROOT/'tests/java_inventory_canonical.py',witness,oracle,java,javac,runtime/'codeana-cli',runtime/'codeana-daemon',args.evidence/'selection.json',*classes.glob('*.class')]}
    atomic_json(args.evidence/'dependency-selection.json',dict(package=dependency_package,relations=['reads','writes'],directions=['in','out','both']))
    inputs[str((args.evidence/'dependency-selection.json').resolve())]=sha256(args.evidence/'dependency-selection.json')
    report=dict(backend='native', status='running',kind='frozen_project_extended_public_queries',revision=publication['published_revision'],
        cases=[],inputs=inputs,source_proof=verify_source_inputs(product,args.project),oracle_proof=oracle_proof,
        complete_query_matrix=False,native_accepted=False,real_incremental_history_accepted=False)
    receipt=args.evidence/'receipt.json';atomic_json(receipt,report);daemon=None
    def check(name,argv,wanted,normalize):
        folder=args.evidence/name;folder.mkdir();row=dict(id=name,status='failed')
        try:
            rows,proof=navigation.pages.query(command+argv+['--revision',report['revision'],'--max-records','3','--max-output-bytes','8388608'],folder,'query')
            if any(page['terminal']['revision']!=report['revision'] for page in proof):raise ValueError('QUERY_REVISION_DRIFT')
            observed=normalize(rows);row.update(records=len(rows),pages=len(proof),expected=sum(wanted.values()),
                missing=list((wanted-observed).items()),extra=list((observed-wanted).items()))
            atomic_json(folder/'results.json',rows);atomic_json(folder/'pages.json',proof)
            if observed!=wanted:raise ValueError('INDEPENDENT_QUERY_MISMATCH')
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
            for depth in [1,8]:
                for filter in ['yes','all','unknown']:
                    name=f'runtime-{depth}-{filter}';wanted=collections.Counter((expected.reference_symbol(row['id']),row['instantiability'],row['reason']) for row in oracle_queries[name]['rows'])
                    check(name,['runtime-implementations',runtime_root['symbol_id'],'--depth',str(depth),'--instantiability',filter],wanted,
                        lambda rows:collections.Counter((expected.product_symbol(row['id']),row['runtime_implementation']['instantiability'],row['runtime_implementation']['reason']) for row in rows))
            wanted=collections.Counter((expected.reference_symbol(row['subject']),expected.reference_symbol(row['object'])) for row in oracle_queries['callable']['rows'])
            check('callable',['callable-relations',callable_root['symbol_id'],'--direction','in','--transitive','--depth','8'],wanted,
                lambda rows:collections.Counter((expected.product_symbol(row['parent']),expected.product_symbol(row['target'])) for row in rows))
            for algorithm in ['exact','cha']:
                name='dispatch-'+algorithm;wanted=collections.Counter((expected.reference_symbol(row['target']),row['candidate'],row['executable'],row['algorithm']) for row in oracle_queries[name]['rows'])
                check(name,['dispatch',site['id'],'--algorithm',algorithm,'--depth','8'],wanted,
                    lambda rows:collections.Counter((expected.product_symbol(row['target']),row['candidate'],row['executable'],row['algorithm']) for row in rows))
            for section,wanted,extra in [('modules',modules,[]),('packages',packages,[]),('packages-folded',folded,['--package-depth','2'])]:
                key='module' if section=='modules' else 'package'
                check('overview-'+section,['overview','--section',section.split('-')[0],*extra],wanted,
                    lambda rows:collections.Counter({row[key]:row['count'] for row in rows}))
            for direction in ['out','in','both']:
                check('dependencies-'+direction,['overview','--section','dependencies','--package',dependency_package,
                    '--relation','reads,writes','--direction',direction],dependency_counts[direction],
                    lambda rows:collections.Counter({(row['package'],row['target_package'],row['relation']):row['count'] for row in rows}))
            for ignore,regex in [(False,False),(True,False),(False,True)]:
                name='text-'+('folded' if ignore else 'regex' if regex else 'literal')
                check(name,['search-text',pattern+('.*' if regex else ''),'--path',source_path,'--context','1',
                    *(['--ignore-case'] if ignore else []),*(['--regex'] if regex else [])],wanted_text(ignore,regex),
                    lambda rows:collections.Counter((row['path'],row['line'],row['text'],row['hash'],
                        json.dumps(row['context_before'],sort_keys=True),json.dumps(row['context_after'],sort_keys=True)) for row in rows))
            specification=args.evidence/'exact-pipeline.json';specification.write_text(json.dumps([
                {'operation':'resolve','selector':site['id']},{'operation':'dispatch','algorithm':'exact','depth':8}]))
            wanted=collections.Counter(expected.reference_symbol(row['target']) for row in oracle_queries['dispatch-exact']['rows'])
            check('pipeline-exact',['pipeline',str(specification)],wanted,lambda rows:collections.Counter(expected.product_symbol(row['target']) for row in rows))
        report['changed_inputs']=[path for path,digest in inputs.items() if sha256(path)!=digest]
        report['expected_cases']=19
        report['status']='passed' if len(report['cases'])==19 and all(row['status']=='passed' for row in report['cases']) and not report['changed_inputs'] else 'failed'
    except BaseException as error:report.update(status='failed',failure=str(error))
    finally:
        if daemon is not None and daemon.poll() is None:
            daemon.terminate()
            try:daemon.wait(timeout=15)
            except subprocess.TimeoutExpired:daemon.kill();daemon.wait(timeout=5)
        product.close();reference.close();atomic_json(receipt,report)
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
