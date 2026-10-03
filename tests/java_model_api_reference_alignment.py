#!/usr/bin/env python3
"""Actual SDK8 detached navigation versus frozen Anatomist JavaModel/JavaSyntax."""
import argparse
import json
import os
import pathlib
import subprocess
import time

import java_alignment as alignment
import java_functional_reference_alignment as functional
import java_model_tests as models

CASES={
 'generic-nested':{'Use.java':'// 原字节😀\r\npackage p; class Outer<T> {T field; Outer() {} static class Inner {int x; Inner(){} void run(int value) {int local=1;} } }'},
 'method-formals':{'Use.java':'''package p; class Outer<T extends Number & Comparable<T>> {
 Outer() {} <R extends CharSequence> R choose(R first, R... others) {return first;}
 static class Inner<U> {Inner(){} U value;}
 }'''},
 'control-syntax':{'Use.java':'''package p; class Flow {Flow(){} int choose(Object value,int[] values) {
 label: for(int n: values) {if(n<0)continue label;try {synchronized(this) {
 while(n>0){n--;if(n==1)break;}}}catch(RuntimeException error){throw error;}finally {value=null;}}
 do {value=this;} while(false);
 switch(values.length){case 1:return 0;default:break;} return -1;
 }}'''},
 'duplicate-reference-span':{'One.java':'package p; class Dup {}','Two.java':'package p; class Dup {}'},
 'shared-ast':{'Use.java':'''package p; class Shared {Shared(){} java.util.List<String> first,second;
 void run(){java.util.List<String> a,b; new java.util.ArrayList<String>() {{add("原字节😀");}};}}'''},
}


def normalize(value):
    result=dict(value)
    order=lambda row:json.dumps(row,sort_keys=True,ensure_ascii=False)
    # Archive ordinals/protocol are private. Keep every occurrence and all
    # metadata; only catalogue iteration order differs between implementations.
    result['rows']=sorted([dict(row,members=sorted(row['members'],key=order)) for row in value['rows']],key=order)
    result['syntax']=sorted(value['syntax'],key=order)
    return result


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--native-executable',type=pathlib.Path)
    parser.add_argument('--native',action='store_true')
    parser.add_argument('--inventory',action='store_true',help='Compare every source position and slice by lossless digest')
    args=parser.parse_args();root=alignment.ROOT
    evidence=root/'target/java-alignment/evidence'/('model-api-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    evidence.mkdir(parents=True);frozen=alignment.input_files()
    jar=root.parent/'anatomist/target/anatomist.jar'
    if not jar.exists():jar=root/'target/java-alignment/anatomist/target/anatomist.jar'
    if functional.digest(jar)!=functional.REFERENCE_SHA:raise ValueError('MODEL_API_FROZEN_REFERENCE_DRIFT')
    classes=evidence/'frozen-classes';classes.mkdir()
    reference_cp=os.pathsep.join([str(classes),str(root/'lang-adaptor/target/classes'),
        str(root/'lang-adaptor/target/test-classes'),str(jar)])
    subprocess.run([str(models.JDK25/'bin/javac'),'-cp',reference_cp,'-d',str(classes),
        str(root/'lang-adaptor/src/frozen-test/java/dev/codeana/analysis/DetachedModelApiReferenceProbe.java'),
        str(root/'lang-adaptor/src/frozen-test/java/com/anatomist/core/javac/FrozenExactModelApiContext.java')],check=True,timeout=60)
    product_cp=os.pathsep.join([str(root/'lang-adaptor/target/classes'),str(root/'lang-adaptor/target/test-classes'),
        (root/'lang-adaptor/target/native-classpath.txt').read_text().strip()])
    if args.native and args.native_executable:parser.error('使用 --native 或 --native-executable，不能同时指定')
    if args.native:
        args.native_executable=evidence/'model-api-native'
        with (evidence/'native-build.log').open('wb') as log:
            subprocess.run([str(models.JDK25/'bin/native-image'),'--no-fallback','-R:MaxHeapSize=256m','-cp',product_cp,
                'dev.codeana.analysis.DetachedModelApiProbe','-o',str(args.native_executable)],
                stdout=log,stderr=subprocess.STDOUT,check=True,timeout=600)
    artifacts={str(root/'lang-adaptor/target/compiler-bridge.jar'):functional.digest(root/'lang-adaptor/target/compiler-bridge.jar'),
        str(jar):functional.digest(jar)}
    if args.native_executable:artifacts[str(args.native_executable.resolve())]=functional.digest(args.native_executable)
    receipt=dict(kind='actual_detached_model_navigation_reference',cases=[],reference_sha256=functional.REFERENCE_SHA,
        native_executable=bool(args.native_executable),full_semantic_diff_accepted=False,all_ok=False)
    try:
        for name,files in CASES.items():
            case=models.Models();case.setUp();base=evidence/name;base.mkdir();case.root=base/'inputs';case.root.mkdir()
            try:
                context=case.context('app',files)
                request=dict(version=5,facts=False,java_home=str(models.JDK8),bridge=str(root/'lang-adaptor/target/compiler-bridge.jar'),
                    scratch=str(case.root),contexts=[context],model_type_queries=['p.Outer','p.Outer.Inner','p.Outer$Inner','p.Flow','p.Dup','p.Missing'])
                if args.inventory:request.update(model_api_inventory=True,model_api_exact_context=True)
                path=base/'request.json';path.write_text(json.dumps(request));values={}
                for side,cp,probe in [('product',product_cp,'dev.codeana.analysis.DetachedModelApiProbe'),
                                     ('reference',reference_cp,'dev.codeana.analysis.DetachedModelApiReferenceProbe')]:
                    command=([str(args.native_executable.resolve()),str(path)] if side=='product' and args.native_executable else
                        [str(models.JDK25/'bin/java'),'-cp',cp,probe,str(path)])
                    process=subprocess.run(command,capture_output=True,text=True,timeout=180)
                    (base/(side+'.log')).write_text(process.stderr)
                    if process.returncode:raise RuntimeError(side+' '+process.stderr[-4000:])
                    (base/(side+'.json')).write_text(process.stdout);values[side]=normalize(json.loads(process.stdout))
                left,right=values['product'],values['reference'];equal=left==right
                proof=dict(name=name,product_declarations=len(left['rows']),reference_declarations=len(right['rows']),
                    product_syntax=len(left['syntax']),reference_syntax=len(right['syntax']),raw_equal=equal)
                if name=='duplicate-reference-span':
                    # Both models disclose ambiguity. The reference loses the
                    # second actual class span; never replace correct product evidence.
                    status=lambda d:next(x['status'] for x in d['types'] if x['name']=='p.Dup')
                    product_classes=[r for r in left['rows'] if r['kind']=='CLASS']
                    reference_classes=[r for r in right['rows'] if r['kind']=='CLASS']
                    proof['reference_defect_reproduced']=(not equal and status(left)==status(right)=='ambiguous'
                        and len(product_classes)==len(reference_classes)==2 and all(r['start']>=0 for r in product_classes)
                        and sum(r['start']<0 for r in reference_classes)==1 and left['syntax']==right['syntax'] and left['sources']==right['sources'])
                    if not proof['reference_defect_reproduced']:raise AssertionError('MODEL_API_DUPLICATE_DEFECT_CHANGED')
                elif not equal:raise AssertionError('MODEL_API_REFERENCE_DIFFERENCE: '+name)
                (base/'diff.json').write_text(json.dumps(dict(proof=proof,product=left,reference=right),ensure_ascii=False,indent=2)+'\n')
                receipt['cases'].append(proof)
            finally:case.doCleanups()
        guards=[str(models.JDK25/'bin/java'),'-Xmx512m','-cp',product_cp,
            'dev.codeana.analysis.extension.DetachedModelApiGuards',str(evidence/'guards')]
        with (evidence/'guards.log').open('wb') as log:subprocess.run(guards,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=120)
        receipt['all_ok']=True
    except Exception as error:
        receipt.update(failure=str(error),failure_type=type(error).__name__)
    finally:
        actual=alignment.input_files();receipt['changed_inputs']=[p for p in frozen.keys()|actual.keys() if frozen.get(p)!=actual.get(p)]
        receipt['changed_artifacts']=[p for p,sha in artifacts.items() if functional.digest(pathlib.Path(p))!=sha]
        receipt['all_ok']=receipt['all_ok'] and not receipt['changed_inputs'] and not receipt['changed_artifacts']
        (evidence/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
        print(evidence,flush=True)
    return 0 if receipt['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
