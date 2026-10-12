#!/usr/bin/env python3
"""Independent frozen-jar comparison for the migrated core and captured-input boundary."""
import argparse,hashlib,json,os,pathlib,shutil,subprocess,tempfile,time
from java_fresh_reference import reference
ROOT=pathlib.Path(__file__).resolve().parents[1]
REFERENCE,REFERENCE_PROOF=reference()
REFERENCE_SHA=REFERENCE_PROOF['jar_sha256']
TARGET=ROOT/'lang-java-adapotr/java-bridge/target'
JDK=pathlib.Path(os.environ.get('CODEANA_JAVA8_HOME',pathlib.Path.home()/'.sdkman/candidates/java/8.0.492-zulu'))
HOST=pathlib.Path.home()/'.sdkman/candidates/java/25.3.4+1.r25-graal'
CASES={
 'calls':{'Use.java':'package p; interface Api {String get();} class Use implements Api { public String get(){return String.valueOf(1);} void call(){Api x=this; x.get(); java.util.function.Supplier<String> f=this::get; f.get();} }'},
 'types':{'Use.java':'package p; @interface Tag {Class<?> value();} @Tag(String.class) class Use<T extends Number & Comparable<T>> {java.util.List<? extends T[]> value; <R extends CharSequence> R choose(R x,R... y){return x;} class Inner {} }'},
 'control':{'Use.java':'class Use {int field; int run(int[] xs){label:for(int x:xs){try {if(x<0)continue label; synchronized(this){field+=x;}}catch(RuntimeException e){throw e;}} return field;} }'},
 'lombok':{'lombok/Getter.java':'package lombok; public @interface Getter {}','Use.java':'@lombok.Getter class Use {private boolean ready; boolean run(){return isReady();}}'},
}
CASES['spring-sofa']={
 'org/springframework/stereotype/Service.java':'package org.springframework.stereotype; public @interface Service {String value() default "";}',
 'org/springframework/beans/factory/annotation/Autowired.java':'package org.springframework.beans.factory.annotation; public @interface Autowired {boolean required() default true;}',
 'org/springframework/web/bind/annotation/GetMapping.java':'package org.springframework.web.bind.annotation; public @interface GetMapping {String[] value() default {};}',
 'com/alipay/sofa/runtime/api/annotation/SofaService.java':'package com.alipay.sofa.runtime.api.annotation; public @interface SofaService {Class<?> interfaceType();}',
 'com/alipay/sofa/runtime/api/annotation/SofaReference.java':'package com.alipay.sofa.runtime.api.annotation; public @interface SofaReference {}',
 'p/Use.java':'package p; interface Api {} @org.springframework.stereotype.Service @com.alipay.sofa.runtime.api.annotation.SofaService(interfaceType=Api.class) class Use implements Api {@org.springframework.beans.factory.annotation.Autowired Api api; @com.alipay.sofa.runtime.api.annotation.SofaReference Api remote; @org.springframework.web.bind.annotation.GetMapping("/hello") String hello(){return "hi";}}',
}
CASES['xml']={
 'p/Use.java':'package p; class Service {public void start(){}} class Use {public void setService(Service s){}}',
 'beans.xml':'<beans xmlns:sofa="http://schema.alipay.com/sofa/schema/service"><bean id="service" class="p.Service" init-method="start"/><bean id="use" class="p.Use"><property name="service" ref="service"/></bean><sofa:service interface="p.Service" ref="service"/></beans>',
}
CASES['annotations-meta']={
 'org/springframework/stereotype/Component.java':'package org.springframework.stereotype; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Component {String value() default "";}',
 'p/Use.java':'package p; @org.springframework.stereotype.Component @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @interface ServiceMeta {String value() default "service";} @ServiceMeta class Use {}',
}
CASES['unresolved']={'Use.java':'class Use {void run(){missing(1); Unknown.run();}}'}
CASES['capture-literals']={'Use.java':'''import java.lang.annotation.*;
@Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.SOURCE) @interface Tag {String value();}
class Use {java.util.List<@Tag("capture#123") ? extends Number> values;
Number first(){return values.get(0);} }'''}
CASES['xml-cross-file']={
 'p/Api.java':'package p; public class Api {}',
 'p/Base.java':'package p; public class Base {public void setApi(Api api){}}',
 'p/Use.java':'package p; public class Use extends Base {public static Api create(){return new Api();}}',
 'api.xml':'<beans><bean id="api" class="p.Api"/></beans>',
 'use.xml':'<beans><bean id="use" class="p.Use"><property name="api" ref="api"/></bean><bean id="factory" class="p.Use" factory-method="create"/></beans>',
}
CASES['constructor-statements']={'Use.java':'class B { B(int n){} } class Use extends B { Use(){this(1);} Use(int n){super(n);} }'}
CASES['failed-inference']={'Inference.java':'''import java.util.*; import java.util.stream.*; class Inference<T> {
    int healthy(){return 1;} List<T> broken(Map<Integer,T> m){return m.entrySet().stream()
    .sorted(Comparator.comparing(o -> o.getValue())).map(Map.Entry::getValue).collect(Collectors.toList());}
}'''}
CASES['field-mutations']={'Use.java':'class Use {int field; int run(){field++; ++field; field--; return field;} }'}
CASES['lombok-modeled-overrides']={
 'lombok/Getter.java':'package lombok; public @interface Getter {}',
 'Use.java':'interface Contract<T> {T getValue();} @lombok.Getter class Bean implements Contract<String> {String value;} @lombok.Getter class Parent<T> {T value;} class Child extends Parent<String> {public String getValue(){return "yes";}}',
}
CASES['lombok-source-recovery']={
 'lombok/Getter.java':'package lombok; public @interface Getter {}',
 'Use.java':'class Holder {@lombok.Getter Api api;} class Api {public void choose(long value){} public void choose(Integer value){} public <T extends Number & Comparable<T>> T echo(T value){return value;}} class Use {void run(Holder holder){holder.getApi().choose(1); holder.getApi().<Integer>echo(1);}}',
}
CASES['enum-array-reference']={'Use.java':'enum E { FIRST(1), SECOND(2); E(int n){} } class Use {java.util.function.IntFunction<String[]> array=String[]::new; java.util.function.Supplier<Object> object=Object::new; Object value=new Object();}'}
CASES['conditional-bean-occurrences']={
 'Use.java':'class Use {void setSize(int value){} void setOther(Use value){}}',
 'beans.xml':'<beans>#if ($app == "first")\n<bean id="a" class="Use"><property name="size" value="32"/></bean>\n#end\n#if ($app == "second")\n<bean id="a" class="Use"><property name="size" value="512"/></bean>\n#end\n<bean id="holder" class="Use"><property name="other" ref="a"/></bean></beans>',
}
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--evidence',type=pathlib.Path,
        default=ROOT/'target/implementation-evidence'/('raw-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid())))
    evidence=parser.parse_args().evidence.resolve()
    evidence.mkdir(parents=True,exist_ok=False)
    REFERENCE,proof=reference()
    REFERENCE_SHA=proof['jar_sha256']
    output=evidence/'receipt.json'
    receipt=dict(reference_commit=proof['commit'],reference_sha256=REFERENCE_SHA,categories=14,cases=[],full_semantic_diff_accepted=False)
    with tempfile.TemporaryDirectory(prefix='codeana-migration-reference-') as tmp:
        work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir()
        bundle=pathlib.Path(os.environ.get('CODEANA_TEST_JAVA_BUNDLE',ROOT/'lang-java-adapotr/target/jvm'))
        manifest=json.loads((bundle/'manifest.json').read_text())
        frozen=work/'migrated-runner.jar'
        shutil.copy2(bundle/'runner.jar',frozen)
        runner_sha=hashlib.sha256(frozen.read_bytes()).hexdigest()
        assert runner_sha==manifest['files']['runner.jar'],'MIGRATED_BUNDLE_DRIFT'
        receipt['migrated_runner_sha256']=runner_sha
        receipt['migrated_manifest']=manifest
        migrated=str(frozen)
        subprocess.run([str(HOST/'bin/javac'),'-cp',migrated,'-d',str(classes),str(ROOT/'tests/java-migration/ParityWitness.java')],check=True)
        for case,sources in CASES.items():
            root=work/case;root.mkdir()
            for name,text in sources.items():
                file=root/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)
            results={}
            for label,classpath,mode in [('reference',str(REFERENCE),'reference'),('migrated',migrated,'captured')]:
                started=time.monotonic()
                result=subprocess.run([str(HOST/'bin/java'),'-cp',str(classes)+os.pathsep+classpath,'ParityWitness',str(root),str(JDK),str(work/(case+'-'+label)), 'ast' if case.startswith('lombok') else 'off',mode,str(evidence/case/label)],capture_output=True,text=True,timeout=120)
                assert result.returncode==0,result.stderr
                results[label]=json.loads(result.stdout)
                if case=='capture-literals':
                    rows=json.loads((evidence/case/label/'raw.types.json').read_text())
                    assert any('capture#123' in row for row in rows),'ANNOTATION_LITERAL_CHANGED_BY_WITNESS'
            difference=[domain for domain in results['reference'] if results['reference'][domain]!=results['migrated'][domain]]
            receipt['cases'].append(dict(name=case,differences=difference,**results))
            output.write_text(json.dumps(receipt,indent=2)+'\n')
            assert not difference,(case,difference,str(output))
    receipt['empty_categories']=[k for k in receipt['cases'][0]['reference'] if all(c['reference'][k]['rows']==0 for c in receipt['cases'])]
    output.write_text(json.dumps(receipt,indent=2)+'\n')
    assert 'facts.annotationMetaRelations' not in receipt['empty_categories'], 'META_ANNOTATION_COVERAGE_MISSING'
    assert 'facts.unresolvedCallSites' not in receipt['empty_categories'], 'UNRESOLVED_CALL_COVERAGE_MISSING'
    print(json.dumps(dict(cases=len(CASES),categories=14,differences=0,empty_categories=receipt['empty_categories'],receipt=str(output))))
if __name__=='__main__':main()
