#!/usr/bin/env python3
"""All Spring construction and config-node metadata from the actual frozen parser."""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_model_tests import JDK25
from java_xml_alignment import Xml

XML = '''<beans xmlns="http://www.springframework.org/schema/beans">
 <!-->原字节😀<--><alias name="apiAlias" alias="secondAlias"/>
 <alias name="api" alias="apiAlias"/>
 <bean id="api" name="first another" class="Api" primary="${primary}" autowire-candidate="false"/>
 <bean id="holder" class="Holder" init-method="start" destroy-method="stop">
  <constructor-arg index="0" type="Api" ref="api"/>
  <constructor-arg index="invalid" value="原字节😀"/>
  <constructor-arg><null/></constructor-arg>
  <property name="items"><list>
   <ref bean="api"/><idref local="api"/>
   <ref parent="parentOnly"/>
   <value><![CDATA[ 中文 😀 ]]></value><value value=""/>
   <bean class="Api"><property name="other" ref="api"/></bean>
  </list></property>
  <property name="values"><map>
   <entry key-ref="api" value-ref="api"/>
   <entry key="x" value="y"/>
   <entry key="z"><null/></entry>
  </map></property>
  <property name="other" ref="api" value="both"><null/></property>
 </bean>
 <bean id="template" abstract="true" class="Api"/>
 <bean parent="template"><property name="other" ref="api"/></bean>
 <bean id="static" class="Maker" factory-method="create"/>
 <bean id="instance" factory-bean="static" factory-method="instance"/>
</beans>'''


def projection(facts):
    result = []
    for fact in facts:
        if fact['relation'] != 'java.bean' or fact['attributes'].get('kind') != 'xml_bean':
            continue
        model = fact['attributes']['xml_configuration_model']
        if model['version'] != 1:
            raise ValueError('XML_CONFIGURATION_MODEL_VERSION')
        nodes = []
        for node in model['nodes']:
            if not isinstance(node['synthetic'], bool) or not node['source_element_span']:
                raise ValueError('XML_CONFIGURATION_SOURCE_EVIDENCE_MISSING')
            position = node['position']
            nodes.append(dict(metadata=node['metadata'], children=node['children'],
                position={key:position[key] for key in ('line','column','end_line','end_column')}))
        result.append(dict(version=1, construction=model['construction'], roots=model['roots'], nodes=nodes))
    return sorted(result,key=lambda row:row['construction']['ordinal'])


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--native',action='store_true');args=parser.parse_args()
    root=alignment.ROOT
    base=root/'target/java-alignment/evidence'/('xml-model-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    base.mkdir(parents=True)
    jar=reference.REFERENCE_JAR
    if reference.digest(jar)!=reference.REFERENCE_SHA:raise ValueError('XML_MODEL_REFERENCE_ARTIFACT_DRIFT')
    classes=base/'classes';classes.mkdir()
    source=root/'tests/java-migration/XmlConfigurationWitness.java'
    classpath=os.pathsep.join([str(jar),str(classes)])
    subprocess.run([str(JDK25/'bin/javac'),'-cp',classpath,'-d',str(classes),str(source)],check=True,timeout=30)
    rust_target=base/'rust-build'
    subprocess.run(['cargo','build','--target-dir',str(rust_target),'-p','codeana-model','--example','validate_java_graph_facts','--locked'],
                   cwd=root,check=True,capture_output=True,timeout=240)
    validator=base/'validate-java-xml-facts'
    shutil.copy2(rust_target/'debug/examples/validate_java_graph_facts',validator)
    inputs,artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    artifacts[str(validator)]=reference.digest(validator)
    case=Xml();case.setUp()
    case.rust_validator=validator
    if args.native:case.analyze=reference.native_analyze.__get__(case,Xml)
    baseline=None;proofs=[];started=time.monotonic()
    try:
        for name,text in [('baseline',XML),('body',XML.replace('中文','正文').replace('\n','\r\n')),
                          ('declaration',XML.replace('key="x"','key="changed"'))]:
            directory=base/name;directory.mkdir()
            captured=directory/'beans.xml';captured.write_text(text,encoding='utf8')
            context=case.captured({'beans.xml':text})
            result=case.analyze([context],cache=True);facts=result['facts']
            from java_graph_fact_alignment import GraphFacts
            GraphFacts.validate_rust(case,[context],result)
            payload=json.loads((case.root/'rust-graph-facts.json').read_text())
            corrupted=next(f['attributes']['xml_configuration_model'] for f in payload['facts']
                           if f['relation']=='java.bean' and f['attributes']['xml_configuration_model']['roots'])
            corrupted['roots'].append(corrupted['roots'][0])
            rejected=directory/'invalid-model.json';rejected.write_text(json.dumps(payload))
            failure=subprocess.run([str(validator),str(rejected)],capture_output=True,text=True,timeout=30)
            case.assertNotEqual(failure.returncode,0)
            case.assertIn('INVALID_JAVA_XML_CONFIGURATION',failure.stderr)
            actual=projection(facts)
            witness_input=directory/'reference-inputs.json'
            witness_input.write_text(json.dumps([dict(file='beans.xml',path=str(captured))]))
            process=subprocess.run([str(JDK25/'bin/java'),'-cp',classpath,
                'XmlConfigurationWitness',str(witness_input)],capture_output=True,text=True,check=True,timeout=30)
            expected=sorted(json.loads(process.stdout)[0]['models'],key=lambda row:row['construction']['ordinal'])
            from java_xml_reference_alignment import index_reference
            from java_xml_alignment import SOURCE
            from java_xml_model_inventory_diff import reference_models
            from java_declaration_inventory_diff import connection
            graph=directory/'graph';graph.mkdir()
            database=index_reference(graph,SOURCE,text)
            with connection(database) as db:
                published=sorted((row['model'] for row in reference_models(db)),
                                 key=lambda row:row['construction']['ordinal'])
            case.assertEqual(expected,published)
            proof=dict(name=name,product=actual,reference=expected,equal=actual==expected,
                       captured_xml_sha256=reference.digest(captured),reference_database_sha256=reference.digest(database),
                       reference_parser_and_published_graph_equal=expected==published)
            if baseline is not None:
                case.assertEqual(case.merged(baseline,case.analyze([context],baseline,cache=True)),sorted(facts,key=lambda f:f['id']))
            baseline=facts;proofs.append(proof)
            (directory/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            print(name,len(actual),len(expected),proof['equal'],flush=True)
    finally:case.doCleanups()
    now,current=alignment.input_files(),reference.probe_artifacts(args.native)
    current[str(validator)]=reference.digest(validator)
    report=dict(native=args.native,proofs=proofs,inputs=inputs,artifacts=artifacts,
        changed_inputs=sorted(k for k in inputs.keys()|now.keys() if inputs.get(k)!=now.get(k)),
        changed_artifacts=sorted(k for k in artifacts.keys()|current.keys() if artifacts.get(k)!=current.get(k)),
        reference_commit=reference.REFERENCE_COMMIT,reference_sha256=reference.REFERENCE_SHA,
        elapsed_seconds=round(time.monotonic()-started,3),full_semantic_diff_accepted=False)
    report['all_ok']=len(proofs)==3 and all(p['equal'] for p in proofs) and not report['changed_inputs'] and not report['changed_artifacts']
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
