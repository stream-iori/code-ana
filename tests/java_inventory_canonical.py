"""Independent aliases, admitted only by unique equal original-source anonymous/local-class spans."""
import collections,functools,re,hashlib,json

def compiler_lines(text):
 """Java line terminators are CR, LF and CRLF, not Unicode LS/PS/NEL."""
 return re.findall(r'[^\r\n]*(?:\r\n|\r|\n|$)',text)

def verify_source_inputs(product,project):
 expected={}
 for row in product.execute("select source,body from facts where kind in ('provenance','external_symbol','coverage')"):
  fact=json.loads(row['body']);attributes=fact.get('attributes',{})
  origin=attributes.get('source_origin',{})
  path=origin.get('path') if fact['kind']=='external_symbol' else fact['source']
  checksum=origin.get('hash') if fact['kind']=='external_symbol' else attributes.get('input_hash') if fact['kind']=='coverage' else attributes.get('source_hash')
  if not path or not path.endswith('.java') or not checksum:continue
  if path in expected and expected[path]!=checksum:raise ValueError('AMBIGUOUS_CAPTURE_HASH: '+path)
  expected[path]=checksum
 if not expected:raise ValueError('CAPTURED_JAVA_PROVENANCE_REQUIRED')
 for path,checksum in sorted(expected.items()):
  source=project/path
  if not source.resolve().is_relative_to(project.resolve()) or hashlib.sha256(source.read_bytes()).hexdigest()!=checksum:
   raise ValueError('CAPTURED_JAVA_SOURCE_DRIFT: '+path)
 return dict(count=len(expected),sha256=hashlib.sha256(json.dumps(expected,sort_keys=True,separators=(',',':')).encode()).hexdigest(),verified='captured_source_sha256')

class Canonical:
 def __init__(self,product,reference,project):
  self.aliases={};self.receipt=dict(matched_anonymous_classes=0,matched_local_classes=0,matched_source_classes=0,unmatched_product=[],unmatched_reference=[])
  @functools.lru_cache(maxsize=128)
  def lines(path):return compiler_lines((project/path).read_bytes().decode('utf-8'))
  def offset(path,line,column,last=False):
   text=lines(path);return sum(len(row.encode('utf-8')) for row in text[:line-1])+len(text[line-1].encode('utf-16-le')[:(column-1+last)*2].decode('utf-16-le').encode('utf-8'))
  wanted=collections.defaultdict(list)
  for row in reference.execute("select symbol_id,source_file,begin_line,begin_column,end_line,end_column from nodes where kind='ANONYMOUS_CLASS' or (kind='CLASS' and symbol_id like '%$local@%')"):
   key=(row['source_file'],offset(row['source_file'],row['begin_line'],row['begin_column']),offset(row['source_file'],row['end_line'],row['end_column'],True))
   wanted[key].append(row['symbol_id'])
  found=set()
  rows=list(product.execute("select source,json_extract(body,'$.attributes.nesting_kind') as nesting_kind,json_extract(body,'$.attributes.element_kind') as element_kind,json_extract(body,'$.attributes.upstream_id') as upstream_id,json_extract(body,'$.attributes.scope') as scope,json_extract(body,'$.signature') as signature,json_extract(body,'$.span.start') as start,json_extract(body,'$.span.end') as end from facts where kind='declaration' and (json_extract(body,'$.attributes.nesting_kind') in ('ANONYMOUS','LOCAL') or json_extract(body,'$.attributes.element_kind')='ANONYMOUS_CLASS' or (json_extract(body,'$.attributes.element_kind')='CLASS' and json_extract(body,'$.attributes.upstream_id') like '%$local@%'))"))
  counts=collections.Counter((row['source'],row['start'],row['end']) for row in rows)
  for row in rows:
   key=(row['source'],row['start'],row['end'])
   if row['start'] is None or row['end'] is None:
    self.receipt['unmatched_product'].append([*key,row['signature']]);continue
   matches=[(key,v) for v in wanted.get(key,[])]
   if not matches and row['upstream_id']:
    # The reference includes the new-expression prefix; the class body ends at
    # the same captured byte. Require original identity plus containment, never name alone.
    original=row['upstream_id'].replace('$','.')
    matches=[(candidate,v) for candidate,values in wanted.items()
             if candidate[0]==row['source'] and candidate[1]<=row['start'] and candidate[2]==row['end']
             for v in values if v.replace('$','.')==original]
   if counts[key]!=1 or len(matches)!=1:self.receipt['unmatched_product'].append([*key,row['signature']]);continue
   matched,value=matches[0]
   self.aliases[(row['source'],row['signature'])]=value;found.add(matched)
   self.receipt['matched_local_classes' if row['nesting_kind']=='LOCAL' or '$local@' in (row['upstream_id'] or '') else 'matched_anonymous_classes']+=1
  for key,values in wanted.items():
   if key not in found:self.receipt['unmatched_reference'].append([*key,*values])
  self.receipt['matched_source_classes']=len(self.aliases)
  by_signature=collections.defaultdict(set)
  for (_,signature),value in self.aliases.items():by_signature[signature].add(value)
  self.unique={signature:next(iter(values)) for signature,values in by_signature.items() if len(values)==1}
  names=sorted(by_signature,key=len,reverse=True)
  self.pattern=re.compile(r'(?<![\w.$])(?:'+'|'.join(re.escape(name) for name in names)+r')(?![\w])') if names else None
 def __call__(self,signature,kind=None,source='',origin='reference'):
  value=signature or ''
  if origin=='product' and '$' in value and self.pattern is not None:
   value=self.pattern.sub(lambda match:self.aliases.get((source,match[0]),self.unique.get(match[0],match[0])),value)
  value=value.replace('$','.').replace('...','[]')
  if '#' in value and '(' in value:
   owner,member=value.rsplit('#',1)
   if kind=='CONSTRUCTOR' or (not kind and member.split('(',1)[0]==owner.rsplit('.',1)[-1]):value=owner+'#<init>'+member[member.index('('):]
  return value
