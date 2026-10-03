"""Read-only type occurrence comparison, including generated/callback owners and status.
This projection cannot certify the whole Java model or framework graph.
"""
import argparse,collections,functools,hashlib,importlib.util,json,pathlib,re,sqlite3
p=argparse.ArgumentParser()
for key in ('product','reference','project','output'):p.add_argument('--'+key,type=pathlib.Path,required=True)
p.add_argument('--module',default='')
p.add_argument('--scope',choices=('MAIN','TEST','MAIN,TEST'),default='MAIN,TEST')
a=p.parse_args()
scopes=set(a.scope.split(','))
if scopes != {'MAIN','TEST'} and not a.module:raise ValueError('scoped inventory requires a module identity')
worlds=None if scopes=={'MAIN','TEST'} else {'world_'+hashlib.sha256(json.dumps([a.module,scope],separators=(',',':')).encode()).hexdigest() for scope in scopes}
def connect(path):
 d=sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True);d.row_factory=sqlite3.Row;return d
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for c in iter(lambda:f.read(65536),b''):h.update(c)
 return h.hexdigest()
@functools.lru_cache(maxsize=128)
def lines(path):return re.findall(r'[^\r\n]*(?:\r\n|\r|\n|$)',(a.project/path).read_bytes().decode('utf8'))
def offset(path,line,col,end=False):
 if not line or not col:return None
 rows=lines(path);return sum(len(s.encode('utf8')) for s in rows[:line-1])+len(rows[line-1].encode('utf-16-le')[:2*(col-1+end)].decode('utf-16-le').encode('utf8'))
def compare(left,right):
 def sample(values):return [dict(key=k,count=v) for k,v in sorted(values.items(),key=lambda x:str(x[0]))[:100]]
 return dict(product=left.total(),reference=right.total(),missing=(right-left).total(),extra=(left-right).total(),equal=left==right,missing_sample=sample(right-left),extra_sample=sample(left-right))
before=dict(product=digest(a.product),reference=digest(a.reference))
with connect(a.product) as db,connect(a.reference) as ref:
 assert db.execute("select value from identity where key='revision'").fetchone(),'published revision required'
 spec=importlib.util.spec_from_file_location('canonical',pathlib.Path(__file__).with_name('java_inventory_canonical.py'));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
 captured_inputs=mod.verify_source_inputs(db,a.project)
 canonical=mod.Canonical(db,ref,a.project)
 product={r['id']:json.loads(r['body']) for r in db.execute("select id,body from facts where kind in ('declaration','external_symbol')")}
 nodes={r['id']:dict(r) for r in ref.execute('select * from nodes')}
 parents={}
 for r in ref.execute("select source_id,target_id from edges where relation='CONTAINS'"):
  assert r['target_id'] not in parents or parents[r['target_id']]==r['source_id'],'ambiguous lexical parent'
  parents[r['target_id']]=r['source_id']
 @functools.lru_cache(maxsize=None)
 def po(identity):
  f=product.get(identity,{})
  kind=f.get('attributes',{}).get('element_kind')
  if kind in ('LAMBDA','METHOD_REF','INITIALIZER'):
   span=f.get('span') or {};return(kind,f['source'],span.get('start'),span.get('end'),po(f['parent']))
  return canonical(f.get('signature','<missing-owner>'),kind,f.get('source',''),'product')
 @functools.lru_cache(maxsize=None)
 def ro(identity):
  r=nodes.get(identity,{})
  if r.get('kind') in ('LAMBDA','METHOD_REF','SOURCE_REGION'):
   return('INITIALIZER' if r['kind']=='SOURCE_REGION' else r['kind'],r['source_file'],offset(r['source_file'],r['begin_line'],r['begin_column']),offset(r['source_file'],r['end_line'],r['end_column'],True),ro(parents.get(identity,'')))
  return canonical(r.get('symbol_id','<missing-owner>'),r.get('kind'))
 rels={'type_reference':'REFERENCES','reflection_member':'REFERENCES','extends':'INHERITS','implements':'IMPLEMENTS','overrides':'OVERRIDES'}
 left={r:collections.Counter() for r in rels};right={r:collections.Counter() for r in rels};excluded=collections.Counter()
 for row in db.execute("select body from facts where kind in ('reference','relation')"):
  f=json.loads(row['body']);r='reflection_member' if f['relation']=='reflection_lookup' else f['relation'];path=f['source']
  if r not in rels or (a.module and not path.startswith(a.module+'/')):continue
  if worlds is not None and f['owner']['world'] not in worlds:continue
  target=product.get(f['target']);assert target,'missing target '+f['target']
  span=f.get('span') or {}
  left[r][(path,po(f['parent']),canonical(target['signature'],target['attributes'].get('element_kind'),path,'product'),span.get('start'),span.get('end'),f['attributes'].get('type_context'),f['status'])]+=1
 for row in ref.execute("select * from edges where relation in ('REFERENCES','INHERITS','IMPLEMENTS','OVERRIDES')"):
  path=row['source_file']
  if a.module and not path.startswith(a.module+'/'):continue
  owner=nodes.get(row['source_id']);assert owner,'missing owner'
  if owner['scope'] not in scopes:continue
  target=nodes.get(row['target_id'],{});r={'REFERENCES':'type_reference','INHERITS':'extends','IMPLEMENTS':'implements','OVERRIDES':'overrides'}[row['relation']]
  if row['relation']=='REFERENCES' and (target.get('kind') in ('METHOD','CONSTRUCTOR','FIELD','ENUM_CONSTANT') or '#' in (target.get('symbol_id') or row['external_target_symbol'] or '')):r='reflection_member'
  metadata=json.loads(owner['metadata'] or '{}');generated=metadata.get('generator')=='lombok'
  status='possible' if generated or row['confidence']=='INFERRED' else 'complete'
  right[r][(path,ro(row['source_id']),canonical(target.get('symbol_id') or row['external_target_symbol'] or row['external_target_fqn'],target.get('kind')),offset(path,row['begin_line'],row['begin_column']),offset(path,row['end_line'],row['end_column'],True),row['context'],status)]+=1
 result=dict(kind='published_native_type_occurrence_projection',identity_aliases=canonical.receipt,product_sha256=before['product'],reference_sha256=before['reference'],project_source_inputs=captured_inputs,module=a.module,scope=sorted(scopes),projections={k:compare(left[k],right[k]) for k in rels},excluded=dict(excluded),full_semantic_diff_accepted=False)
if before!=dict(product=digest(a.product),reference=digest(a.reference)):raise ValueError('COMPARISON_DATABASE_DRIFT')
result['changed_databases']=[]
a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print({k:{field:value for field,value in v.items() if not field.endswith('sample')} for k,v in result['projections'].items()})

raise SystemExit(0 if all(v["equal"] for v in result["projections"].values()) else 1)
