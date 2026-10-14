#!/usr/bin/env python3
"""Explicit Maven preparation fixture; product indexing never invokes Maven."""
import hashlib, json, pathlib, subprocess, tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='codeana-maven-fixture-') as tmp:
    root=pathlib.Path(tmp)
    pom=root/'pom.xml'
    pom.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>dev.codeana.fixture</groupId><artifactId>export-fixture</artifactId><version>1</version><properties><maven.compiler.source>1.8</maven.compiler.source><maven.compiler.target>1.8</maven.compiler.target></properties><profiles><profile><id>fixture</id><properties><fixture.marker>true</fixture.marker></properties></profile></profiles></project>''')
    output=root/'.code-ana/java-inputs.json'
    subprocess.run(['python3',str(ROOT/'scripts/export-java-inputs.py'),'--extension',str(ROOT/'lang-java-adapotr/target/maven-input-worker.jar'),'--project',str(root),'--output',str(output)],check=True,timeout=180)
    value=json.loads(output.read_text())
    assert value['schema_version']==4
    assert value['controls']['pom.xml']==hashlib.sha256(pom.read_bytes()).hexdigest()
    assert len(value['effective_model_sha256'])==64
    assert len(value['active_profiles_sha256'])==64
    assert value['selected_profiles']==[]
    assert value['external_controls']
    module=value['modules'][0]
    assert module['root']=='.' and module['source_version']=='8'
    assert module['source_roots']==[dict(path='src/main/java',scope='MAIN',generated=False,generated_from={}),dict(path='src/test/java',scope='TEST',generated=False,generated_from={})]
    assert module['classpath']==dict(MAIN=[],TEST=[])
    explicit=root/'.code-ana/explicit-inputs.json'
    subprocess.run(['python3',str(ROOT/'scripts/export-java-inputs.py'),'--extension',str(ROOT/'lang-java-adapotr/target/maven-input-worker.jar'),'--project',str(root),'--output',str(explicit),'--profile','fixture'],check=True,timeout=180)
    selected=json.loads(explicit.read_text())
    assert selected['selected_profiles']==['fixture']
    assert selected['active_profiles_sha256']!=value['active_profiles_sha256']
    assert not list(root.rglob('*.class'))
    print('explicit real Maven effective model and ordered classpath export: passed')
