"""Read-only native/reference inventory diff; never claims complete type/framework parity."""
import argparse, collections, functools, hashlib, importlib.util, json, pathlib, re, sqlite3

p = argparse.ArgumentParser()
p.add_argument('--product', type=pathlib.Path, required=True)
p.add_argument('--reference', type=pathlib.Path, required=True)
p.add_argument('--project', type=pathlib.Path, required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
p.add_argument('--module', default='')
p.add_argument('--product-kind', choices=('published_native','jvm','isolated_bridge'), default='published_native')
p.add_argument('--scope', choices=('MAIN','TEST','MAIN,TEST'), default='MAIN,TEST')
a = p.parse_args()
scopes=set(a.scope.split(','))
if scopes != {'MAIN','TEST'} and not a.module:raise ValueError('scoped inventory requires a module identity')
worlds=None if scopes=={'MAIN','TEST'} else {'world_'+hashlib.sha256(json.dumps([a.module,scope],separators=(',',':')).encode()).hexdigest() for scope in scopes}
def chosen(fact):return worlds is None or fact['owner']['world'] in worlds

core = {'CLASS', 'INTERFACE', 'ENUM', 'ANNOTATION_TYPE', 'FIELD', 'ENUM_CONSTANT', 'METHOD', 'CONSTRUCTOR'}

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def canon(signature, kind=None):
    signature = (signature or '').replace('$', '.').replace('...', '[]')
    if '#' in signature and '(' in signature:
        owner, member = signature.split('#', 1)
        method = member.split('(', 1)[0]
        if kind == 'CONSTRUCTOR' or method == owner.rsplit('.', 1)[-1]:
            signature = owner + '#<init>' + member[member.index('('):]
    return signature

@functools.lru_cache(maxsize=128)
def lines(path):
    text=(a.project / path).read_bytes().decode('utf-8')
    return re.findall(r'[^\r\n]*(?:\r\n|\r|\n|$)',text)

def offset(path, line, column, inclusive=False):
    content = lines(path)
    if not line or not column:
        return None
    prefix = sum(len(s.encode('utf-8')) for s in content[:line-1])
    row = content[line-1].encode('utf-16-le')
    units = column - 1 + int(inclusive)
    return prefix + len(row[:units * 2].decode('utf-16-le').encode('utf-8'))

def connection(path):
    db = sqlite3.connect('file:' + str(path.resolve()) + '?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    return db

def compare(left, right):
    def rows(counter):
        return [dict(key=list(k), count=v) for k, v in sorted(counter.items(), key=lambda kv: str(kv[0]))[:10000]]
    missing, extra = right-left, left-right
    return dict(product=sum(left.values()), reference=sum(right.values()),
                missing_count=sum(missing.values()), extra_count=sum(extra.values()),
                missing_sample=rows(missing), extra_sample=rows(extra), accepted=left == right)

before = dict(product=sha(a.product), reference=sha(a.reference))
with connection(a.product) as db, connection(a.reference) as ref:
    if a.product_kind=='published_native':
        revision=db.execute("SELECT value FROM identity WHERE key='revision'").fetchone()
        if not revision:raise ValueError('published native revision identity missing')
    spec=importlib.util.spec_from_file_location('canonical',pathlib.Path(__file__).with_name('java_inventory_canonical.py'));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    captured_inputs=module.verify_source_inputs(db,a.project)
    canonical=module.Canonical(db,ref,a.project)
    product_decls, reference_decls = collections.Counter(), collections.Counter()
    product_signatures, product_nodes = {}, {}
    for row in db.execute("SELECT id,body FROM facts WHERE kind IN ('declaration','external_symbol')"):
        fact = json.loads(row['body']); kind = fact['attributes'].get('element_kind')
        if fact['attributes'].get('nesting_kind')=='ANONYMOUS':kind='CLASS'
        product_signatures[row['id']] = canonical(fact['signature'], kind, fact['source'] or fact['attributes'].get('source_origin',{}).get('path',''), 'product')
        product_nodes[row['id']] = dict(kind=kind,parent=fact['parent'],span=fact.get('span'),source=fact['source'])
        if a.module and not fact['source'].startswith(a.module+'/'):
            continue
        if fact['kind'] == 'declaration' and kind in core and chosen(fact):
            product_decls[(fact['source'], kind, canonical(fact['signature'], kind, fact['source'] or fact['attributes'].get('source_origin',{}).get('path',''), 'product'))] += 1
    nodes = {r['id']: dict(r) for r in ref.execute('SELECT id,symbol_id,kind,scope,source_file,begin_line,begin_column,end_line,end_column FROM nodes')}
    ref_parents = {}
    for row in ref.execute("SELECT source_id,target_id FROM edges WHERE relation='CONTAINS'"):
        if row['target_id'] in ref_parents and ref_parents[row['target_id']] != row['source_id']:
            raise ValueError('ambiguous reference lexical parent: '+row['target_id'])
        ref_parents[row['target_id']] = row['source_id']
    @functools.lru_cache(maxsize=None)
    def product_owner(identity):
        node=product_nodes.get(identity,{})
        if node.get('kind') in {'LAMBDA','METHOD_REF','INITIALIZER'}:
            span=node.get('span') or {}
            return (node['kind'],node['source'],span.get('start'),span.get('end'),product_owner(node['parent']))
        return product_signatures.get(identity,'<missing-owner>')
    @functools.lru_cache(maxsize=None)
    def reference_owner(identity):
        node=nodes.get(identity,{})
        if node.get('kind') in {'LAMBDA','METHOD_REF','SOURCE_REGION'}:
            kind='INITIALIZER' if node['kind']=='SOURCE_REGION' else node['kind']
            return (kind,node['source_file'],offset(node['source_file'],node['begin_line'],node['begin_column']),offset(node['source_file'],node['end_line'],node['end_column'],True),reference_owner(ref_parents.get(identity,'')))
        return canonical(node.get('symbol_id','<missing-owner>'),node.get('kind'))
    for node in nodes.values():
        if a.module and not node['source_file'].startswith(a.module+'/'):
            continue
        kind = {'ANNOTATION':'ANNOTATION_TYPE','ANONYMOUS_CLASS':'CLASS'}.get(node['kind'],node['kind'])
        if kind in core and node["scope"] in scopes:
            reference_decls[(node['source_file'], kind, canonical(node['symbol_id'], kind))] += 1
    product_sites, reference_sites = collections.Counter(), collections.Counter()
    product_targets, reference_targets = collections.Counter(), collections.Counter()
    product_owners, reference_owners = collections.Counter(), collections.Counter()
    for row in db.execute("SELECT body FROM facts WHERE kind='call_site'"):
        f = json.loads(row['body']); span = f.get('span') or {}; key = (f['source'], span.get('start'), span.get('end'))
        if a.module and not f['source'].startswith(a.module+'/'):
            continue
        if not chosen(f):continue
        product_sites[key] += 1
        product_owners[key + (product_owner(f['parent']),)] += 1
    for row in db.execute("SELECT c.source,c.body AS site,json_extract(t.body,'$.status') AS status,t.target FROM facts t JOIN facts c ON c.id=t.parent WHERE t.kind='call_target'"):
        site = json.loads(row['site']); span = site.get('span') or {}
        if a.module and not row['source'].startswith(a.module+'/'):
            continue
        if not chosen(site):continue
        key = (row['source'], span.get('start'), span.get('end'))
        product_targets[key + (product_signatures.get(row['target'], '<missing-target>'), row['status'])] += 1
    for row in ref.execute('SELECT s.*,o.source_file,o.caller_id FROM call_sites s JOIN call_site_owners o ON s.owner_pk=o.owner_pk'):
        if a.module and not row['source_file'].startswith(a.module+'/'):
            continue
        key = (row['source_file'], offset(row['source_file'],row['begin_line'],row['begin_column']), offset(row['source_file'],row['end_line'],row['end_column'],True))
        if nodes[row['caller_id']]['scope'] not in scopes:continue
        reference_sites[key] += 1
        reference_owners[key + (reference_owner(row['caller_id']),)] += 1
    for row in ref.execute('SELECT t.*,s.begin_line,s.begin_column,s.end_line,s.end_column,o.source_file,o.caller_id FROM call_site_targets t JOIN call_sites s ON t.call_site_pk=s.site_pk JOIN call_site_owners o ON s.owner_pk=o.owner_pk'):
        if a.module and not row['source_file'].startswith(a.module+'/'):
            continue
        key = (row['source_file'], offset(row['source_file'],row['begin_line'],row['begin_column']), offset(row['source_file'],row['end_line'],row['end_column'],True))
        if nodes[row['caller_id']]['scope'] not in scopes:continue
        node = nodes.get(row['target_id'], {})
        signature = node.get('symbol_id') or row['external_target_symbol'] or row['external_target_fqn'] or '<missing-target>'
        status = {'exact':'resolved', 'heuristic':'possible'}.get(row['resolution_status'],row['resolution_status'])
        reference_targets[key + (canonical(signature,node.get('kind')), status)] += 1
    report = dict(kind=a.product_kind+'_core_call_inventory', native_verified=a.product_kind=='published_native', reference_commit='dff2e3954096853352ec91fcbfc6b995fbd4f390',
                  project_commit='267c7ced4993aa88613f8f17a8099943fec98159', scope=a.scope.split(','), identity_aliases=canonical.receipt,
                  product_sha256=before['product'], reference_sha256=before['reference'], project_source_inputs=captured_inputs,
                  canonicalization=['binary nesting $ to source dot','varargs to array','constructor final member name to <init>', 'anonymous class names unified only by uniquely equal original-source spans', 'anonymous enum bodies use the reference graph ANONYMOUS_CLASS category; original compiler kind remains unchanged','UTF-16 inclusive reference coordinates to UTF-8 half-open bytes','callback/initializer identity by kind, source span and recursively verified lexical parent'],
                  declarations=compare(product_decls,reference_decls), sites=compare(product_sites,reference_sites),
                  targets=compare(product_targets,reference_targets), owners=compare(product_owners,reference_owners),
                  type_evidence_equivalence_accepted=False, framework_equivalence_accepted=False,
                  full_semantic_diff_accepted=False, capacity_accepted=False)
if before != dict(product=sha(a.product), reference=sha(a.reference)):
    raise ValueError('COMPARISON_DATABASE_DRIFT')
report['changed_databases'] = []
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k in ('kind','declarations','sites','targets','owners')},ensure_ascii=False))

raise SystemExit(0 if all(report[k]["accepted"] for k in ("declarations","sites","targets","owners")) else 1)
