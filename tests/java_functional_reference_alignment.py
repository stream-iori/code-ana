#!/usr/bin/env python3
"""Independent frozen Anatomist proofs; targeted success does not prove real-project parity."""
import argparse
import collections
import hashlib
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import time

import java_model_tests as models
import java_lombok_alignment as lombok
import java_lombok_builder_alignment as builders

ROOT = pathlib.Path(__file__).resolve().parents[1]
REFERENCE_SHA = '457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e'
REFERENCE_COMMIT = 'dff2e3954096853352ec91fcbfc6b995fbd4f390'
CONTROL = 'config.stopBubbling=true\nlombok.addLombokGeneratedAnnotation=false\n'
from java_collector_alignment import SOURCE as COLLECTOR_SOURCE, TARGET_SOURCE as COLLECTOR_TARGET_SOURCE, IDENTITY_SOURCE, CONCURRENT_IDENTITY_SOURCE, LAMBDA_MAP_SOURCE, ENTRY_CHAIN_SOURCE, STABLE_LAMBDA_CONSTRUCTOR_SOURCE, UNRELATED_GETTER_CHAIN_SOURCE, CAST_REFERENCE_CHAIN_SOURCE, MAP_CAST_CHAIN_SOURCE, INSTANCEOF_CAST_CHAIN_SOURCE, ARITHMETIC_CONCAT_SOURCE, ARITHMETIC_MISSING_SOURCE, NULL_BRANCH_SOURCE, JOINING_SOURCE
from java_builder_chain_alignment import SOURCE as BUILDER_CHAIN_SOURCE, GETTER_SOURCE as BUILDER_GETTER_SOURCE, ordinary_chain
from java_diamond_alignment import SOURCE as DIAMOND_SOURCE
from java_lambda_input_alignment import SOURCE as LAMBDA_INPUT_SOURCE
from java_contextual_invocation_alignment import NESTED, CONSTRUCTOR, GENERIC, METHOD_INPUT
from java_functional_output_alignment import VOID_OVERLOAD_SOURCE, TRY_OUTPUT_SOURCE

DEEP_CONCRETE_SOURCE = ('@lombok.Getter class Deep<T> { ' + 'java.util.List<' * 80 + 'T' + '>' * 80
                        + ' value;} class Use {int read(Deep<String> d){return d.getValue().size();}}')

SOURCES = {
    'functional-deep-concrete': DEEP_CONCRETE_SOURCE,
    'functional-deep-concrete-missing': DEEP_CONCRETE_SOURCE.replace('getValue()', 'absent()'),
    'functional-deep-concrete-two': DEEP_CONCRETE_SOURCE.replace('Deep<String> d)', 'Deep<String> d,Deep<Number> n)').replace('return d.getValue().size();', 'int a=d.getValue().size();return n.getValue().size();'),
    'functional-unrelated-getter-chain': UNRELATED_GETTER_CHAIN_SOURCE,
    'functional-cast-reference-chain': CAST_REFERENCE_CHAIN_SOURCE,
    'functional-map-cast-chain': MAP_CAST_CHAIN_SOURCE,
    'functional-instanceof-cast-chain': INSTANCEOF_CAST_CHAIN_SOURCE,
    'functional-arithmetic-concat': ARITHMETIC_CONCAT_SOURCE,
    'functional-arithmetic-concat-missing': ARITHMETIC_MISSING_SOURCE,
    'functional-entry-chain': ENTRY_CHAIN_SOURCE,
    'functional-stable-lambda-constructor': STABLE_LAMBDA_CONSTRUCTOR_SOURCE,
    'functional-lambda-map': LAMBDA_MAP_SOURCE,
    'functional-identity-concurrent': CONCURRENT_IDENTITY_SOURCE,
    'functional-builder-getter-chain': BUILDER_GETTER_SOURCE,
    'functional-builder-chain': BUILDER_CHAIN_SOURCE,
    'functional-null-branch-inference': NULL_BRANCH_SOURCE,
    'functional-fixed-wildcard-collector': JOINING_SOURCE,
    'functional-void-supplier-overload': VOID_OVERLOAD_SOURCE,
    'functional-try-catch-output': TRY_OUTPUT_SOURCE,
    'functional-ordinary-ten-chain': ordinary_chain(10),
    'functional-ordinary-eighty-chain': ordinary_chain(80),
    'functional-identity-factory': IDENTITY_SOURCE,
    'functional-collector-target': COLLECTOR_TARGET_SOURCE,
    'functional-same-return-method': METHOD_INPUT,
    'functional-nested-reentry': NESTED,
    'functional-stable-constructor': CONSTRUCTOR,
    'functional-poly-factory': GENERIC,
    'functional-collector-capture': COLLECTOR_SOURCE,
    'functional-diamond': DIAMOND_SOURCE,
    'functional-lambda-input': LAMBDA_INPUT_SOURCE,
    'functional-output': 'class Holder {@lombok.Getter Api api;}\nclass Api {void run(){} String text(){return "";} }\nclass Other {}\ninterface SourceSupplier<T> {T get();}\nclass Factory {\n static <T> T create(java.util.function.Supplier<T> s){return null;}\n static <T> T source(SourceSupplier<T> s){return null;}\n static <T,R> R convert(java.util.function.Function<T,R> fn){return null;}\n static <T extends Number> T bounded(java.util.function.Supplier<T> s){return null;}\n}\nclass Use {void read(Holder h, boolean flag){\n Factory.create(() -> h.getApi()).run();\n Factory.create(h::getApi).run();\n Factory.source(() -> h.getApi()).run();\n Factory.create(() -> {if(flag) return h.getApi(); else return h.getApi();}).run();\n Factory.convert(Api::text).length();\n Factory.create(() -> {if(flag) return h.getApi(); else return new Other();}).run();\n Factory.bounded(() -> h.getApi()).intValue();\n}}\n',
    'functional-unbound': 'class Holder {@lombok.Getter Factory factory;}\nclass Api {@lombok.Getter String value;}\nclass Factory {<T,R> R convert(java.util.function.Function<T,R> fn){return null;}}\nclass Use {int read(Holder h){return h.getFactory().convert(Api::getValue).length();}}\n',
    'functional-generated-overload': '@lombok.Getter class Envelope {java.util.Map<String,Object> extraContent;\n <T> T getExtraContent(String key){return null;}}\nclass Item {@lombok.Getter String name;} class Use {void read(Envelope e,java.util.Optional<Item> value){\n e.getExtraContent().get("name");value.map(item -> item.getName()).orElse("empty").length();\n e.getExtraContent(1,2);value.<Number>map(item -> item.getName());}}',
    'functional-static-import': 'import static p.Mode.valueOf;\n// 中文位置\nclass Holder {@lombok.Getter String name;} class Use {int read(Holder h){switch(valueOf(h.getName())) {\n case ONE:return 1;case TWO:return 2;default:return 0;}}}',
    'functional-generic-reference': 'class Holder {@lombok.Getter Factory factory;} class Factory {<T,R> R convert(T input,java.util.function.Function<T,R> fn){return null;}}\nclass Api {void run(){}} class Box<T> {@lombok.Getter T value;} class Child extends Box<Api> {}\nclass Use {void read(Holder h,Box<Api> box,Child child,java.util.Map.Entry<String,Api> entry){\n h.getFactory().convert(box,Box::getValue).run();h.getFactory().convert(child,Child::getValue).run();\n h.getFactory().convert(entry,java.util.Map.Entry::getValue).run();\n h.getFactory().<Box<Api>,String>convert(box,Box::getValue);\n h.getFactory().convert(new Object(),Box::getValue);}}',
    'functional-receiver-boundary': '@lombok.Getter class Envelope {java.util.Map<String,Object> extraContent; <T> T getExtraContent(String key){return null;}}\nclass Item {@lombok.Getter String name;} class Use {void read(Envelope e,java.util.Optional<Item> value){e.getExtraContent().get("name");value.map(item -> item.getName()).orElse("empty").length();e.getExtraContent(1,2);value.<Number>map(item -> item.getName());}}\nclass Holder {static Api current(){return null;} static void read(){current().getValue();current().absent();}} class Api {@lombok.Getter String value;}',
    'functional-static-reference': 'class Context {@lombok.Getter String name; @lombok.Getter java.util.Map<String,String> headers;}\nclass Use {static String read(Context c){return java.util.Optional.ofNullable(c).map(Context::getName).orElse(null);}\nstatic java.util.Map<String,String> headers(Context c){return java.util.Optional.ofNullable(c).map(Context::getHeaders).orElse(null);}\nstatic void invalid(Context c){java.util.Optional.ofNullable(c).<Number>map(Context::getName);Context.getName();}}',
    'functional-constructor-reference': 'class Context {@lombok.Getter String name; @lombok.Getter java.util.Map<String,String> headers;}\nclass Use {static String read(Context c){return java.util.Optional.ofNullable(c).map(Context::getName).orElse(null);}\nstatic java.util.Map<String,String> headers(Context c){return java.util.Optional.ofNullable(c).map(Context::getHeaders).orElseGet(java.util.HashMap::new);}\nstatic void invalid(Context c){java.util.Optional.ofNullable(c).<Number>map(Context::getName);Context.getName();}}\n@lombok.NoArgsConstructor @lombok.AllArgsConstructor class Bean {String value;} class Store {static ThreadLocal<Bean> local=ThreadLocal.withInitial(Bean::new);}\nclass Bounded<K extends Number,V> extends java.util.HashMap<K,V>{public Bounded(){}} class Invalid {static java.util.Map<String,String> bad(Context c){return java.util.Optional.ofNullable(c).map(Context::getHeaders).orElseGet(Bounded::new);} static java.util.Map<String,String> incompatible(Context c){return java.util.Optional.ofNullable(c).map(Context::getHeaders).orElseGet(java.util.ArrayList::new);}}',
    'source-parameter-owner': '// 中文：验证 UTF-16 与 UTF-8 位置\nclass Leaf {} class Box<T> {}\nclass Holder {\n Holder(Leaf one, Leaf[] two){}\n Box<Leaf> signature(Leaf first, Leaf second, Box<Box<Leaf>> nested, Leaf[][] matrix){return null;}\n}',
    'expressions': '@lombok.Builder class Response {String name; String region; boolean success; @lombok.Builder.Default int readTimeout=30;}\nclass Proxy {@lombok.Getter String name; @lombok.Getter int cost;\n @lombok.Getter boolean fail; @lombok.Getter Integer boxed;\n @lombok.Getter String[] names; @lombok.Getter Other other;}\nclass Other {} class Use {void read(Proxy proxy,boolean flag){\n Response.builder().name(proxy != null ? proxy.getName() : null).region("r").build();\n Response.builder().name(proxy == null ? null : proxy.getName()).build();\n Response.builder().name(flag ? proxy.getName() : "default").build();\n Response.builder().name(proxy.getName() + ":" + proxy.getCost()).build();\n Response.builder().success(proxy.getCost() > 0 && !proxy.isFail()).build();\n Response.builder().success(proxy.getBoxed() <= proxy.getCost() || proxy.isFail()).build();\n Response.builder().success(proxy.getName() != null).build();\n Response.builder().name(flag ? proxy.getName() : proxy.getOther()).build();\n Response.builder().name(proxy.getCost() ? proxy.getName() : null).build();\n Response.builder().name(flag ? proxy.getNames() : proxy.getNames()).build();\n Response.builder().name(proxy.getCost() + proxy.getCost()).build();\n Response.builder().name(proxy.getName() && proxy.isFail()).build();\n}}\n',
}


def digest(path):
    value = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            value.update(chunk)
    return value.hexdigest()


def probe_artifacts(native):
    bridge = pathlib.Path(os.environ.get('CODEANA_TEST_BRIDGE', ROOT / 'lang-adaptor/target/compiler-bridge.jar'))
    files = [bridge]
    if native:
        files += [ROOT / 'target/release/codeana-java-worker', ROOT / 'lang-adaptor/target/native/libcodeana_java.dylib',
                  ROOT / 'lang-adaptor/target/native/manifest.json']
    else:
        classpath = ROOT / 'lang-adaptor/target/native-classpath.txt'
        files += [classpath]
        files += [pathlib.Path(p) for p in classpath.read_text().strip().split(os.pathsep) if p]
        directories = [ROOT / 'lang-adaptor/target/classes', ROOT / 'lang-adaptor/target/test-classes']
        directories += [pathlib.Path(p) for p in os.environ.get('CODEANA_TEST_CLASSES', '').split(os.pathsep) if p]
        for directory in directories:
            files += sorted(directory.rglob('*.class'))
    return {str(path.resolve()): digest(path) for path in files}


def native_analyze(self, contexts, baseline=None, parallelism=1, cache=False, compact=False):
    model_probe = getattr(self, 'probe', '') == 'dev.codeana.analysis.DependencyViewProbe'
    request = dict(version=6, facts=not model_probe, full=baseline is None, baseline=baseline or [], java_home=str(models.JDK8),
                   bridge=os.environ.get('CODEANA_TEST_BRIDGE', str(ROOT / 'lang-adaptor/target/compiler-bridge.jar')),
                   scratch=str(self.root), contexts=contexts, parallelism=parallelism)
    if cache:
        request.update(signature_cache=True, cache_root=str(self.root/'signature-cache'), profile='fixture-profile')
    if compact:
        request = models.compact_request(request)
    if hasattr(self, 'mutate_request'):
        self.mutate_request(request)
    path = self.root / 'native-request.json'
    path.write_text(json.dumps(request))
    result = subprocess.run([str(ROOT / 'target/release/codeana-java-worker'),
        str(ROOT / 'lang-adaptor/target/native/libcodeana_java.dylib'), '--probe', str(path)],
        capture_output=True, text=True, timeout=140)
    self.assertEqual(result.returncode, 0, result.stderr)
    self.assertFalse(list(self.root.rglob('*.class')))
    self.assertFalse(list(self.root.glob('compiler-*')))
    output = json.loads(result.stdout)
    if model_probe:
        self.assertIsNone(baseline)
        self.assertEqual(len(output['models']), 1)
        model = output['models'][0]
        return dict(syntax=len(model['syntax']), observations=len(model['observations']),
                    bytes=len(result.stdout.encode('utf8')), byte_representation='native_model_json')
    return output


def canonical(value, kind=''):
    value = (value or '').replace('$', '.').replace('...', '[]')
    if '#' in value and '(' in value:
        owner, member = value.rsplit('#', 1)
        if kind == 'CONSTRUCTOR' or member.split('(', 1)[0] == owner.rsplit('.', 1)[-1]:
            value = owner + '#<init>' + member[member.index('('):]
    return value


def compare(left, right):
    rows = lambda values: [dict(key=list(key), count=count) for key, count in sorted(values.items(), key=str)]
    return dict(product=sum(left.values()), reference=sum(right.values()), missing=rows(right-left),
                extra=rows(left-right), equal=left == right)


def proof(name, source, evidence, native):
    base = evidence / name
    base.mkdir()
    from java_static_import_alignment import StaticImports
    case = StaticImports() if name == 'functional-static-import' else builders.Builders() if name == 'expressions' else lombok.Lombok() if name.startswith('functional') else models.Models()
    case.setUp()
    try:
        if native:
            case.analyze = native_analyze.__get__(case)
        if name == 'expressions':
            context = case.fixture(source)
        elif name == 'functional-static-import':
            context = case.fixture(source)
            shutil.copyfile(context['classpath'][0], base / 'enum.jar')
        elif name.startswith('functional'):
            context = case.controls(case.context('app', case.files(source), lombok='model'), {'app/lombok.config': CONTROL})
        else:
            context = case.context('app', {'App.java': source})
        if name.startswith('functional-deep-concrete'):
            context['type_graph'] = True
        facts = case.analyze([context])['facts']
        for unit in context['sources']:
            target = base / unit['path']
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(unit['input'], target)
    finally:
        case.doCleanups()
    modeled = name != 'source-parameter-owner'
    if modeled:
        (base / 'app/lombok.config').write_text(CONTROL)
    config = base / '.anatomist/config.toml'
    config.parent.mkdir()
    config.write_text('[index]\nparser="javac"\njavac_execution="toolchain"\njavac_analysis_threads=1\njavac_worker_heap_mb=128\nspring_xml=false\n')
    jar = ROOT.parent / 'anatomist/target/anatomist.jar'
    command = [str(models.JDK25 / 'bin/java'), '-Xmx512m', '-jar', str(jar), 'index', str(base),
               '--source-root', 'app@MAIN=app', '--jdk-home', str(models.JDK8), '--java-version', '8',
               '--lombok', 'ast' if modeled else 'off', '--vm-classpath', 'true', '--output', str(base / 'index.db'),
               '--full', '--no-classpath']
    if name == 'functional-static-import':
        command.remove('--no-classpath')
        command += ['--classpath', str(base / 'enum.jar')]
    with (base / 'reference.log').open('wb') as log:
        result = subprocess.run(command, stdout=log, stderr=log, timeout=120,
                                env=dict(os.environ, ANATOMIST_HOME=str(base / 'reference-state')))
    if result.returncode:
        raise RuntimeError((base / 'reference.log').read_text()[-5000:])
    (base / 'product.json').write_text(json.dumps(facts, ensure_ascii=False) + '\n')
    by_id = {fact['id']: fact for fact in facts}
    left, right = collections.Counter(), collections.Counter()
    line_cache = {}
    def offset(path, line, column, last=False):
        if line is None or column is None:
            return None
        if path not in line_cache:
            line_cache[path] = (base / path).read_bytes().decode('utf8').splitlines(keepends=True)
        lines = line_cache[path]
        return sum(len(row.encode('utf8')) for row in lines[:line-1]) + len(
            lines[line-1].encode('utf-16-le')[:2*(column-1+last)].decode('utf-16-le').encode('utf8'))
    with sqlite3.connect((base / 'index.db').as_uri() + '?mode=ro', uri=True) as db:
        if modeled:
            for fact in facts:
                if fact['kind'] == 'call_target':
                    site = by_id[fact['parent']]
                    span = site['span'] or {}
                    target = by_id[fact['target']]
                    left[(site['source'], span.get('start'), span.get('end'),
                          canonical(target['signature'], target['attributes'].get('element_kind')), fact['status'])] += 1
            for row in db.execute('SELECT o.source_file,s.begin_line,s.begin_column,s.end_line,s.end_column,'
                    'coalesce(n.symbol_id,t.external_target_symbol,t.external_target_fqn),n.kind,t.resolution_status '
                    'FROM call_site_targets t JOIN call_sites s ON t.call_site_pk=s.site_pk '
                    'JOIN call_site_owners o ON s.owner_pk=o.owner_pk LEFT JOIN nodes n ON n.id=t.target_id'):
                path, line, col, end_line, end_col, target, kind, status = row
                right[(path, offset(path, line, col), offset(path, end_line, end_col, True),
                       canonical(target, kind), {'exact':'resolved', 'heuristic':'possible'}.get(status, status))] += 1
        else:
            owners = {canonical(fact['signature']) for fact in facts if fact['kind'] == 'declaration'
                      and fact['attributes']['element_kind'] in ('METHOD', 'CONSTRUCTOR') and fact['signature'].startswith('Holder#')}
            for fact in facts:
                if fact['relation'] == 'type_reference' and canonical(by_id[fact['parent']]['signature']) in owners:
                    span = fact['span'] or {}
                    left[(canonical(by_id[fact['parent']]['signature']), canonical(by_id[fact['target']]['signature']),
                          fact['source'], span.get('start'), span.get('end'))] += 1
            for row in db.execute("SELECT s.symbol_id,coalesce(t.symbol_id,e.external_target_fqn),e.source_file,"
                    "e.begin_line,e.begin_column,e.end_line,e.end_column FROM edges e JOIN nodes s ON s.id=e.source_id "
                    "LEFT JOIN nodes t ON t.id=e.target_id WHERE e.relation='REFERENCES'"):
                owner, target, path, line, col, end_line, end_col = row
                if canonical(owner) in owners:
                    right[(canonical(owner), canonical(target), path, offset(path, line, col), offset(path, end_line, end_col, True))] += 1
    reads_proof = None
    if name == 'functional-static-import':
        product_reads, reference_reads = collections.Counter(), collections.Counter()
        for fact in facts:
            if fact['relation'] != 'reads':
                continue
            span = fact['span'] or {}
            product_reads[(fact['source'], span.get('start'), span.get('end'),
                           canonical(by_id[fact['parent']]['signature']),
                           canonical(by_id[fact['target']]['signature']))] += 1
        with sqlite3.connect((base / 'index.db').as_uri() + '?mode=ro', uri=True) as db:
            for row in db.execute("SELECT e.source_file,e.begin_line,e.begin_column,e.end_line,e.end_column,"
                    "s.symbol_id,coalesce(t.symbol_id,e.external_target_symbol,e.external_target_fqn) "
                    "FROM edges e JOIN nodes s ON s.id=e.source_id LEFT JOIN nodes t ON t.id=e.target_id "
                    "WHERE e.relation='READS'"):
                path, line, col, end_line, end_col, owner, target = row
                reference_reads[(path, offset(path, line, col), offset(path, end_line, end_col, True),
                                 canonical(owner), canonical(target))] += 1
        reads_proof = compare(product_reads, reference_reads)
    annotation_proof = None
    if name == 'expressions':
        product_annotations, reference_annotations = collections.Counter(), collections.Counter()
        for fact in facts:
            if fact['relation'] != 'java.annotation':
                continue
            if canonical(fact['attributes'].get('resolved_type')) != 'lombok.Builder.Default':
                continue
            span = fact['span'] or {}
            product_annotations[(canonical(by_id[fact['parent']]['signature']), fact['source'], span.get('start'), span.get('end'))] += 1
        with sqlite3.connect((base / 'index.db').as_uri() + '?mode=ro', uri=True) as db:
            for row in db.execute('SELECT n.symbol_id,a.source_file,a.begin_line,a.begin_column,a.end_line,a.end_column '
                    'FROM annotations a JOIN nodes n ON n.id=a.node_id '
                    'WHERE replace(a.annotation_fqn, ?, ?) = ?', ('$', '.', 'lombok.Builder.Default')):
                owner, path, line, col, end_line, end_col = row
                reference_annotations[(canonical(owner), path, offset(path, line, col), offset(path, end_line, end_col, True))] += 1
        annotation_proof = compare(product_annotations, reference_annotations)
    report = dict(kind=name, source_sha256=hashlib.sha256(source.encode()).hexdigest(), reference_commit=REFERENCE_COMMIT,
                  reference_jar_sha256=REFERENCE_SHA, reference_db_sha256=digest(base / 'index.db'), command=command,
                  native_verified=native, real_main_accepted=False, full_semantic_diff_accepted=False, **compare(left, right))
    minimum = {'functional-deep-concrete':2, 'functional-deep-concrete-missing':0, 'functional-deep-concrete-two':4, 'functional-unrelated-getter-chain':14, 'functional-cast-reference-chain':12, 'functional-map-cast-chain':8, 'functional-instanceof-cast-chain':8, 'functional-arithmetic-concat':3, 'functional-arithmetic-concat-missing':3, 'functional-entry-chain':9, 'functional-stable-lambda-constructor':10, 'functional-lambda-map':4, 'functional-identity-concurrent':5, 'functional-builder-getter-chain':30, 'functional-builder-chain':42, 'functional-null-branch-inference':11, 'functional-fixed-wildcard-collector':5, 'functional-void-supplier-overload':4, 'functional-try-catch-output':4, 'functional-ordinary-ten-chain':11, 'functional-ordinary-eighty-chain':32, 'functional-identity-factory':5, 'functional-collector-target':6, 'functional-same-return-method':2, 'functional-nested-reentry':15, 'functional-stable-constructor':4, 'functional-poly-factory':3, 'functional-collector-capture':14, 'functional-diamond':7, 'functional-lambda-input':23, 'functional-output':19, 'functional-unbound':4, 'source-parameter-owner':10, 'expressions':15, 'functional-generated-overload':6, 'functional-static-import':2, 'functional-generic-reference':12, 'functional-receiver-boundary':9, 'functional-static-reference':9, 'functional-constructor-reference':20}[name]
    report['equal'] = report['equal'] and report['reference'] >= minimum
    if reads_proof is not None:
        report['static_import_reads'] = reads_proof
        report['equal'] = report['equal'] and reads_proof['equal'] and reads_proof['reference'] == 5
    if annotation_proof is not None:
        report['builder_default_annotations'] = annotation_proof
        report['equal'] = report['equal'] and annotation_proof['equal'] and annotation_proof['reference'] == 1
    (base / 'receipt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(name + ': ' + str(report['product']) + '/' + str(report['reference']) + ' equal=' + str(report['equal']), flush=True)
    return dict(name=name, path=str(base / 'receipt.json'), equal=report['equal'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=pathlib.Path)
    parser.add_argument('--native', action='store_true')
    args = parser.parse_args()
    evidence = (args.evidence or ROOT / 'target/java-alignment/evidence' /
                ('functional-reference-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))).resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    if digest(ROOT.parent / 'anatomist/target/anatomist.jar') != REFERENCE_SHA:
        raise ValueError('FROZEN_REFERENCE_JAR_CHANGED')
    frozen = probe_artifacts(args.native)
    proofs = [proof(name, source, evidence, args.native) for name, source in SOURCES.items()]
    current = probe_artifacts(args.native)
    changed = sorted(path for path in frozen.keys() | current.keys() if frozen.get(path) != current.get(path))
    report = dict(kind='frozen_functional_source_reference_fixtures', proofs=proofs, artifacts=frozen,
                  changed_artifacts=changed, all_ok=all(row['equal'] for row in proofs) and not changed,
                  full_semantic_diff_accepted=False)
    (evidence / 'receipt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Evidence: ' + str(evidence), flush=True)
    return 0 if report['all_ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
