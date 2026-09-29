#!/usr/bin/env python3
"""Regenerate explicit, reviewed semantic fixtures; this is not a Java analyzer."""
import hashlib,json,pathlib
ROOT=pathlib.Path(__file__).resolve().parent
compact=lambda x:json.dumps(x,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()
sha=lambda b:hashlib.sha256(b).hexdigest()
world='world_'+sha(compact(['fixture','MAIN']))
CONFIG='''schema_version = 1
kind = "project"
[discovery]
mode = "explicit"
[scan]
scopes = ["MAIN"]
[languages.java]
enabled = true
mode = "semantic"
[languages.java.discovery]
build_system = "plain"
[languages.java.analysis]
source_version = "17"
toolchain = "mock-jdk17"
classpath = "none"
vm_classpath = true
[[modules]]
id = "fixture"
language = "java"
root = "."
[[modules.source_roots]]
path = "src"
scope = "MAIN"
'''
A='''package fixture;
// 😀 UTF-8 offset fixture
class App {
  void main() { greet(); greet(); }
  String greet() { return "hello"; }
}
'''
B=A.replace('greet(); greet();','greet();').replace('"hello"','"你好"')
I='''package fixture;
interface Greeting { String greet(); }
'''
IMPL='''package fixture;
class Service implements Greeting { public String greet() { return "service"; } }
'''
def fact(id,kind,source='src/App.java',**fields):
    return dict(id=id,kind=kind,owner=dict(world=world,producer='mock-fixture-v1',kind='source',id=source),source=source,origin='mock-fixture-v1',evidence_complete=True,**fields)
def facts(main):
    b=main.encode();g=b.index(b'void main');start=b.index(b'greet();');second=b.find(b'greet();',start+1)
    fs=[fact('app','declaration',name='App',span=dict(start=b.index(b'class App'),end=len(b)-1)),
        fact('main','declaration',name='main',parent='app',signature='main()',span=dict(start=g,end=b.index(b'\n',g))),
        fact('greet','declaration',name='greet',parent='app',signature='greet()',span=dict(start=b.index(b'String greet'),end=b.index(b'\n',b.index(b'String greet')))),
        fact('site1','call_site',parent='main',status='resolved',span=dict(start=start,end=start+7)),
        fact('target1','call_target',parent='site1',target='greet',status='resolved',declaration_resolved=True),
        fact('ref1','reference',parent='main',target='greet',span=dict(start=start,end=start+5)),
        fact('greeting','declaration','src/Greeting.java',name='Greeting'),
        fact('greeting_method','declaration','src/Greeting.java',name='greet',parent='greeting',signature='greet()'),
        fact('service','declaration','src/Service.java',name='Service'),
        fact('service_method','declaration','src/Service.java',name='greet',parent='service',signature='greet()'),
        fact('implements','relation','src/Service.java',parent='service',target='greeting',relation='implements'),
        fact('overrides','relation','src/Service.java',parent='service_method',target='greeting_method',relation='overrides'),
        fact('annotation','facet',parent='app',relation='java.annotation',attributes={'schema_version':1,'name':'FixtureOnly','origin':'mock'}),
        fact('generated','declaration',name='generatedGetter',parent='app',status='generated',attributes={'generated':True,'source_available':False}),
    ]
    if second>=0:fs += [fact('site2','call_site',parent='main',status='resolved',span=dict(start=second,end=second+7)),fact('target2','call_target',parent='site2',target='greet',status='resolved',declaration_resolved=True)]
    for path in ['src/App.java','src/Greeting.java','src/Service.java']:fs.append(fact('coverage_'+path,'coverage',path,status='complete'))
    return fs
states=[]
for name,text,behavior in [('base',A,''),('edited',B,''),('replan',B+'// replan\n','replan'),('partial',A+'// incomplete semantics\n',''),('advice',A+'// missing generated input\n',''),('crash',A+'// crash\n','crash'),('hang',A+'// hang\n','hang'),('wrong-sequence',A+'// sequence\n','wrong-sequence'),('bad-checksum',A+'// checksum\n','bad-checksum'),('missing-end',A+'// missing end\n','missing-end'),('oversize',A+'// frame limit\n','oversize')]:
    path=ROOT/'java'/name
    files={'.code-ana/config.toml':CONFIG,'src/App.java':text,'src/Greeting.java':I,'src/Service.java':IMPL}
    for p,data in files.items():
        target=path/p;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data.encode())
    fs=facts(text)
    if name=='partial':
        for f in fs:
            if f['kind']=='coverage' and f['source']=='src/App.java':f['status']='partial';f['evidence_complete']=False
    state=dict(name=name,content_digest=sha(compact({p:sha(data.encode())for p,data in files.items()})),context=dict(language='java',modules=['fixture'],source_version='17',classpath=[],producer='mock-fixture-v1',capabilities=['declarations','calls','relations','coverage','java-facets']),facts=fs,behavior=behavior,delay_ms=0)
    if name=='advice':state['advice']=dict(missing=['target/generated-sources/Example.java'],cwd='.',argv=[],purpose='prepare generated sources outside code-ana',expected=['target/generated-sources/Example.java'],impact='Java semantic coverage is incomplete',retry='start a new index task after preparing the input')
    states.append(state)
(ROOT/'mock.json').write_text(json.dumps(dict(schema_version=1,states=states),indent=2,ensure_ascii=False)+'\n')
