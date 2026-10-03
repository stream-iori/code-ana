#!/usr/bin/env python3
"""Independent written annotation data and parameter ownership with full/delta equality."""
import argparse
import json
import os
import time

import java_alignment as alignment
import java_functional_reference_alignment as reference
from java_annotation_inventory_diff import fixture_projection
from java_declaration_reference_alignment import index_reference
from java_model_tests import Models


SOURCE = '''package p; import java.lang.annotation.*;
@Target({ElementType.TYPE,ElementType.METHOD,ElementType.FIELD,ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME) @interface Note {
 String value() default "";char ch() default 'a';float fraction() default 1F;
 boolean enabled() default false;int count() default 0;Class<?> type() default Object.class;
}
class Constants {static final int COUNT=10;}
@Note(value="中文😀",ch='x',fraction=0.1F,enabled=true,count=Constants.COUNT,type=String.class)
class Use {
 @Note("field") String field;
 @Note("method") void read(@Note("parameter") String parameter){}
}
'''


def main():
    p=argparse.ArgumentParser();p.add_argument('--native',action='store_true');args=p.parse_args()
    base=alignment.ROOT/'target/java-alignment/evidence'/('annotation-reference-'+time.strftime('%Y%m%d-%H%M%S')+'-'+str(os.getpid()))
    base.mkdir(parents=True)
    inputs,artifacts=alignment.input_files(),reference.probe_artifacts(args.native)
    case=Models();case.setUp()
    if args.native:case.analyze=reference.native_analyze.__get__(case)
    proofs=[];started=time.monotonic()
    try:
        baseline=None
        for name,source in [('baseline',SOURCE),('body',SOURCE.replace('parameter){}','parameter){int body=1;}')),
                            ('annotation',SOURCE.replace('"parameter"','"changed"').replace('fraction=0.1F','fraction=0.25F'))]:
            folder=base/name;folder.mkdir()
            files={'Use.java':source};context=case.context('app',files)
            full=case.analyze([context],cache=True)['facts']
            if baseline is not None:case.assertEqual(case.merged(baseline,case.analyze([context],baseline,cache=True)),sorted(full,key=lambda f:f['id']))
            baseline=full
            database=index_reference(folder,files)
            proof=fixture_projection(full,database,folder)
            case.assertEqual(proof['reference'],6)
            (folder/'product.json').write_text(json.dumps(full,ensure_ascii=False)+'\n')
            proof.update(name=name,product_sha256=reference.digest(folder/'product.json'),reference_sha256=reference.digest(database))
            (folder/'comparison.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            proofs.append(proof);print(name,proof['product'],proof['reference'],proof['equal'],flush=True)
    finally:case.doCleanups()
    current,now=alignment.input_files(),reference.probe_artifacts(args.native)
    changed_inputs=sorted(k for k in inputs.keys()|current.keys() if inputs.get(k)!=current.get(k))
    changed_artifacts=sorted(k for k in artifacts.keys()|now.keys() if artifacts.get(k)!=now.get(k))
    report=dict(kind='frozen_source_annotation_attribute_projection',native=args.native,proofs=proofs,
        inputs=inputs,artifacts=artifacts,changed_inputs=changed_inputs,changed_artifacts=changed_artifacts,
        reference_commit=reference.REFERENCE_COMMIT,reference_sha256=reference.REFERENCE_SHA,
        annotation_meta_graph_accepted=False,full_semantic_diff_accepted=False,elapsed_seconds=round(time.monotonic()-started,3))
    report['all_ok']=len(proofs)==3 and all(p['equal'] for p in proofs) and not changed_inputs and not changed_artifacts
    (base/'receipt.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(base/'receipt.json',flush=True)
    return 0 if report['all_ok'] else 1


if __name__=='__main__':raise SystemExit(main())
