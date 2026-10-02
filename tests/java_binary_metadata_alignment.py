#!/usr/bin/env python3
"""Independent declaration metadata comparison against the frozen Anatomist ASM reader."""
import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import zipfile
import java_model_tests as models

REFERENCE = 'dff2e3954096853352ec91fcbfc6b995fbd4f390'
REFERENCE_FILES = (
    'anatomist-core/src/main/java/com/anatomist/core/java/JavaBinaryType.java',
    'anatomist-core/src/main/java/com/anatomist/core/asmsolver/JavaBinaryReader.java',
)

def annotation(value):
    descriptor = value['descriptor']
    return dict(descriptor=descriptor, type=descriptor[1:-1].replace('/', '.'),
                runtime_visible=value['visible'], explicit={k: primitive(v) for k, v in value['values'].items()})

def primitive(value):
    if value is None:
        return None
    kind = value['record']
    if kind == 'Scalar':
        scalar = value['value']
        name = value['scalar_type'].lower()
        if name in ('string', 'boolean', 'integer'):
            return scalar
        return dict(kind='primitive', type=name, value=scalar)
    if kind == 'ClassLiteral':
        descriptor = value['descriptor']
        dimensions = len(descriptor)-len(descriptor.lstrip('['))
        base = descriptor[dimensions:]
        name = ({'I': 'int', 'J': 'long', 'B': 'byte', 'S': 'short', 'C': 'char',
                 'Z': 'boolean', 'F': 'float', 'D': 'double', 'V': 'void'}.get(base)
                or base[1:-1].replace('/', '.')) + '[]'*dimensions
        return dict(kind='class', name=name, descriptor=descriptor, complete=True)
    if kind == 'EnumValue':
        return dict(kind='enum', type=value['descriptor'][1:-1].replace('/', '.'), name=value['name'])
    if kind == 'NestedAnnotation':
        return dict(kind='annotation', value=annotation(value['annotation']))
    if kind == 'ArrayValue':
        return dict(kind='array', values=[primitive(v) for v in value['values']])
    raise AssertionError(kind)

def type_annotations(values):
    return [dict(reference=v['target'], path=v['path'], annotation=annotation(v['annotation'])) for v in values]

def metadata(actual, expected):
    assert actual['annotation_metadata'] == [annotation(v) for v in expected['annotations']]
    assert actual['type_annotations'] == type_annotations(expected['typeAnnotations'])

def run():
    dependencies = (models.ROOT/'lang-adaptor/target/native-classpath.txt').read_text().strip()
    classpath = os.pathsep.join([str(models.ROOT/'lang-adaptor/target'/p)
                               for p in ('test-classes', 'classes')]+[dependencies])
    with tempfile.TemporaryDirectory(prefix='codeana-binary-metadata-') as temporary:
        root = pathlib.Path(temporary)
        source = root/'source/p'; source.mkdir(parents=True)
        sources = {
            'Nested.java': 'package p; public @interface Nested { String value() default "嵌套😀"; }',
            'Choice.java': 'package p; public enum Choice { ONE, TWO }',
            'Mark.java': '''package p; import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE,ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER})
                public @interface Mark { String text() default "默认😀"; Class<?> type() default int[][].class;
                Choice choice() default Choice.ONE; Nested nested() default @Nested("inside");
                Nested[] nesteds() default {@Nested("a"),@Nested("b")}; int[] numbers() default {1,2};
                long wide() default 9007199254740993L; char character() default '\\u4e2d';
                float fraction() default 1.25f; boolean enabled() default true; byte tiny() default 7; }''',
            'TypeUse.java': '''package p; import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE_USE,ElementType.TYPE_PARAMETER})
                public @interface TypeUse { String value() default "type"; }''',
            'Outer.java': 'package p; public class Outer { public static class Inner { public int member; } }',
            'Api.java': '''package p; @Mark(text="实际😀",type=String[].class,choice=Choice.TWO,nested=@Nested("nested"),
                nesteds={@Nested("x"),@Nested("y")},numbers={3,4},wide=9007199254740993L,character='中',fraction=2.5f,tiny=9)
                public class Api<@TypeUse("formal") T> extends @TypeUse("parent") Object {
                @Mark(text="field") public java.util.List<@TypeUse("argument") String> field;
                @Mark(text="method") public @TypeUse("return") String call(@Mark(text="param") @TypeUse("parameter") String input) throws @TypeUse("throws") Exception {return input;}
                ''' + ''.join(f'public int field{i};' for i in range(2100)) + '}',
        }
        for name, text in sources.items():
            (source/name).write_text(text, encoding='utf-8')
        classes = root/'classes'; classes.mkdir()
        subprocess.run([str(models.JDK8/'bin/javac'), '-proc:none', '-parameters', '-encoding', 'UTF-8',
                        '-d', str(classes), *map(str, source.glob('*.java'))], check=True, timeout=30)
        jar = root/'fixture.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            for path in classes.rglob('*.class'):
                archive.write(path, path.relative_to(classes).as_posix())
        forged = root/'forged.jar'
        with zipfile.ZipFile(forged, 'w') as archive:
            archive.writestr('p/Forged.class', (classes/'p/Nested.class').read_bytes())
        actual_path = root/'actual.json'
        subprocess.run([str(models.JDK25/'bin/java'), '-Xmx128m', '-cp', classpath,
                        'dev.codeana.analysis.BinaryMetadataProbe', str(jar), str(models.JDK8),
                        str(forged), str(actual_path)], check=True, timeout=60)
        frozen = root/'frozen'; frozen.mkdir()
        hashes = {}
        paths = []
        for relative in REFERENCE_FILES:
            content = subprocess.check_output(['git', '-C', str(models.ROOT.parent/'anatomist'),
                                               'show', f'{REFERENCE}:{relative}'])
            hashes[relative] = hashlib.sha256(content).hexdigest()
            path = frozen/pathlib.Path(relative).name; path.write_bytes(content); paths.append(str(path))
        probe = frozen/'FrozenBinaryReaderProbe.java'
        probe.write_text('''import dev.codeana.common.Json;
            import com.anatomist.core.asmsolver.JavaBinaryReader;
            import java.nio.file.*; import java.util.*;
            public class FrozenBinaryReaderProbe {
              static Object detach(Object value) throws Exception {
                if(value==null) return null;
                if(value instanceof Character c) return (int)c.charValue();
                if(value instanceof String || value instanceof Number || value instanceof Boolean) return value;
                if(value instanceof Map<?,?> m) {Map<String,Object> out=new LinkedHashMap<>();
                  for(var e:m.entrySet()) out.put(e.getKey().toString(),detach(e.getValue()));return out;}
                if(value instanceof Iterable<?> a) {List<Object> out=new ArrayList<>();for(Object v:a)out.add(detach(v));return out;}
                Map<String,Object> out=new LinkedHashMap<>();out.put("record",value.getClass().getSimpleName());
                for(var field:value.getClass().getRecordComponents()) {
                  Object item=field.getAccessor().invoke(value);out.put(field.getName(),detach(item));
                  if(value.getClass().getSimpleName().equals("Scalar"))out.put("scalar_type",item.getClass().getSimpleName());}
                return out;
              }
              public static void main(String[] args) throws Exception {
                Map<String,Object> out=new LinkedHashMap<>();try(var jar=new java.util.zip.ZipFile(args[0])) {
                  for(String name:List.of("p.Api","p.Mark","p.Nested","p.TypeUse","p.Outer$Inner")) {
                    byte[] bytes;try(var in=jar.getInputStream(jar.getEntry(name.replace('.','/')+".class"))){bytes=in.readAllBytes();}
                    out.put(name,detach(JavaBinaryReader.read(bytes)));}}
                Files.writeString(Path.of(args[1]),Json.write(out));
              }
            }''', encoding='utf-8')
        subprocess.run([str(models.JDK25/'bin/javac'), '-cp', classpath, '-d', str(frozen),
                        *paths, str(probe)], check=True, timeout=30)
        expected_path = root/'expected.json'
        subprocess.run([str(models.JDK25/'bin/java'), '-Xmx128m', '-cp', str(frozen)+os.pathsep+classpath,
                        'FrozenBinaryReaderProbe', str(jar), str(expected_path)], check=True, timeout=30)
        actual, expected = json.loads(actual_path.read_bytes()), json.loads(expected_path.read_bytes())
        members = 0
        for name, model in actual.items():
            reference = expected[name]
            metadata(model['declaration'], reference)
            assert model['declaration']['members_complete'] and model['declaration']['members_omitted'] == 0
            fields = [v for v in model['members'].values() if v['kind']=='FIELD']
            methods = [v for v in model['members'].values() if v['kind']=='METHOD']
            assert len(fields)==len(reference['fields']) and len(methods)==len(reference['methods'])
            for value in reference['fields']:
                member = model['members'][name+'#'+value['name']]
                metadata(member, value)
                assert member['descriptor'] == value['descriptor'] and member['access']==value['access']
            for value in reference['methods']:
                member = next(v for key,v in model['members'].items() if key.startswith(name+'#'+value['name']+'(')
                              and v['descriptor']==value['descriptor'])
                metadata(member, value)
                assert member['parameters']==[{k:v for k,v in p.items() if k!='record'} for p in value['parameters']]
                assert member['parameter_annotations']==[dict(parameter=p['parameter'],annotation=annotation(p['annotation']))
                                                          for p in value['parameterAnnotations']]
                assert member.get('visible_parameter_count',-1)==value['visibleParameterCount']
                assert member.get('invisible_parameter_count',-1)==value['invisibleParameterCount']
                assert member.get('annotation_default')==primitive(value['annotationDefault'])
            members += len(fields)+len(methods)
        receipt = dict(kind='frozen_binary_reader_fixture', reference_commit=REFERENCE,
                       reference_sources=hashes, classes=len(actual), members=members,
                       metadata_diff_count=0, cache_weight_bytes=8192, fixture_accepted=True,
                       native_accepted=False, iopengw_accepted=False)
        output = models.ROOT/'target/java-alignment/binary-metadata-reference-receipt.json'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
        print(f'frozen binary reader: {len(actual)} classes / {members} members, zero metadata differences')

if __name__ == '__main__':
    run()
