#!/usr/bin/env python3
"""Real JAR lookup under a descriptor limit below the classpath size."""
import os
import pathlib
import resource
import shutil
import subprocess
import tempfile
import zipfile
import struct
import java_model_tests as models

with tempfile.TemporaryDirectory(prefix='codeana-binary-test-') as temporary:
    root = pathlib.Path(temporary)
    source = root/'source'; source.mkdir()
    files = []
    for i in range(256):
        path = source/f'p{i}/Api.java'; path.parent.mkdir()
        path.write_text(f'package p{i}; public class Api {{ public static final int MARK={i}; public static final double FRACTION=0.125; public static final float TINY=1.25e-20f; public static final double NAN=0.0/0.0; public static final float INFINITY=1.0f/0.0f; }}')
        files.append(str(path))
    classes = root/'classes'; classes.mkdir()
    subprocess.run([str(models.JDK8/'bin/javac'),'-proc:none','-d',str(classes),*files],check=True,capture_output=True,timeout=60)
    duplicates = []
    for i in (0,1):
        path = source/'dup/Api.java'; path.parent.mkdir(exist_ok=True)
        path.write_text(f'package dup; public class Api {{ public static final int MARK={i}; public static final double FRACTION=0.125; public static final float TINY=1.25e-20f; public static final double NAN=0.0/0.0; public static final float INFINITY=1.0f/0.0f; }}')
        subprocess.run([str(models.JDK8/'bin/javac'),'-proc:none','-d',str(classes),str(path)],check=True,capture_output=True,timeout=30)
        duplicates.append((classes/'dup/Api.class').read_bytes())
    for i in range(256):
        with zipfile.ZipFile(root/f'{i}.jar','w') as jar:
            jar.write(classes/f'p{i}/Api.class',f'p{i}/Api.class')
            if i in (0,1): jar.writestr('dup/Api.class',duplicates[i])
    # Old writers emitted redundant ZIP64 fields even for ordinary entry sizes.
    with zipfile.ZipFile(root/'tolerant.jar','w') as jar:
        for name,data in [('dup/Api.class',duplicates[0]),('p0/Api.class',(classes/'p0/Api.class').read_bytes())]:
            info=zipfile.ZipInfo(name);info.extra=struct.pack('<HHQQ',1,16,len(data),len(data))
            jar.writestr(info,data)
    corrupt=bytearray((root/'tolerant.jar').read_bytes())
    name_bytes,extra_bytes=struct.unpack_from('<HH',corrupt,26)
    corrupt[30+name_bytes+extra_bytes]^=1
    (root/'tolerant-crc.jar').write_bytes(corrupt)
    with zipfile.ZipFile(root/'tolerant-bomb.jar','w',compression=zipfile.ZIP_DEFLATED) as jar:
        info=zipfile.ZipInfo('inflated.bin');info.compress_type=zipfile.ZIP_DEFLATED
        info.extra=struct.pack('<HHQQ',1,16,0,0)
        with jar.open(info,'w') as output:
            for _ in range(257):output.write(b'x'*(1024*1024))
    shutil.rmtree(classes)
    (root/'invalid.jar').write_bytes(b'invalid')
    with zipfile.ZipFile(root/'budget.jar','w') as jar:
        for i in range(1025): jar.writestr('x'*8192+str(i)+'/Unused.class',b'')
        jar.writestr('dup/Api.class',duplicates[1])
    dependencies=(models.ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
    classpath=os.pathsep.join([str(models.ROOT/'lang-adaptor/target'/p) for p in ('test-classes','classes')]+[dependencies])
    def limit_descriptors():
        _, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
        resource.setrlimit(resource.RLIMIT_NOFILE,(min(96,hard),hard))
    subprocess.run([str(models.JDK25/'bin/java'),'-cp',classpath,'dev.codeana.analysis.BinaryIndexProbe',str(root),str(models.JDK8)],
                   check=True,timeout=90,preexec_fn=limit_descriptors)
