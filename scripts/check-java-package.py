#!/usr/bin/env python3
"""Reject stale shaded reactor classes before JVM/Native artifact generation."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

MODULES=('analysis-core','framework-lombok','framework-spring','framework-sofa','java-bridge')

def verify(project,backend):
    module={'native':'native-bridge','jvm':'jvm-runner'}[backend]
    artifact=project/module/'target'/(module+'-0.1.0.jar')
    checked=0
    with ZipFile(artifact) as jar:
        counts={}
        for name in jar.namelist():counts[name]=counts.get(name,0)+1
        for source in (*MODULES,module):
            directory=project/source/'target/classes'
            classes=list(directory.rglob('*.class'))
            if not classes:raise ValueError('JAVA_PACKAGE_REACTOR_CLASSES_MISSING: '+source)
            for path in classes:
                name=path.relative_to(directory).as_posix()
                if counts.get(name,0)!=1:raise ValueError('JAVA_PACKAGE_CLASS_MISSING_OR_DUPLICATE: '+name)
                if jar.read(name)!=path.read_bytes():raise ValueError('JAVA_PACKAGE_STALE_CLASS: '+name)
                checked+=1
        for filename, major in [('javac-worker.jar', 52), ('javac-worker17.jar', 61)]:
            name = 'META-INF/anatomist/' + filename
            compiled = project/'analysis-core/target/classes'/name
            if counts.get(name, 0) != 1 or jar.read(name) != compiled.read_bytes():
                raise ValueError('JAVA_PACKAGE_COMPILER_MISSING_OR_STALE: ' + name)
            with ZipFile(compiled) as bridge:
                classes = [entry for entry in bridge.namelist() if entry.endswith('.class')]
                if not classes:
                    raise ValueError('JAVA_PACKAGE_COMPILER_EMPTY: ' + name)
                for entry in classes:
                    header = bridge.read(entry)[:8]
                    if header[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(header[6:8], 'big') != major:
                        raise ValueError('JAVA_PACKAGE_COMPILER_VERSION: ' + entry)
    return dict(status='passed',backend=backend,classes=checked,artifact=str(artifact),
                sha256=hashlib.sha256(artifact.read_bytes()).hexdigest())

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('backend',choices=['native','jvm'])
    parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1]/'lang-java-adaptor')
    args=parser.parse_args()
    print(json.dumps(verify(args.project,args.backend)))
