#!/usr/bin/env python3
"""Enforce ownership and language dependency boundaries without a JDK or a parser SDK."""
import argparse
import json
import pathlib
import re
import tomllib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]


def scrub(source):
    # Java/Rust policy checks examine code, not examples in comments/string literals.
    return re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/', ' ', source)


def production(source):
    return re.split(r'#\[cfg\(test\)\]\s*mod\s+\w+\s*\{', source, maxsplit=1)[0]


def dependency_path(graph, start, forbidden):
    pending = [(start, [start])]
    seen = set()
    while pending:
        name, path = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        if name != start and name in forbidden:
            return path
        pending.extend((child, path+[child]) for child in sorted(graph.get(name, ()), reverse=True))
    return None


def dependency_graph(root):
    workspace = tomllib.loads((root/'Cargo.toml').read_text())['workspace']
    manifests = set()
    for pattern in workspace['members']:
        for member in root.glob(pattern):
            manifests.add(member/'Cargo.toml')
    manifests |= {root/'lang-python-adaptor/Cargo.toml', root/'lang-python-adaptor/worker/Cargo.toml'}
    data = {p: tomllib.loads(p.read_text()) for p in sorted(manifests)}
    graph = {}
    def sections(value):
        for key, item in value.items():
            if key in ('dependencies', 'dev-dependencies', 'build-dependencies'):
                yield item
            elif key == 'target':
                for target in item.values():
                    yield from sections(target)
    for path, value in data.items():
        if 'package' not in value:
            continue
        dependencies = set()
        for section in sections(value):
            for name, settings in section.items():
                if isinstance(settings, dict) and settings.get('workspace'):
                    settings = workspace.get('dependencies', {}).get(name, {})
                actual = settings.get('package', name) if isinstance(settings, dict) else name
                dependencies.add(actual)
        graph[value['package']['name']] = dependencies
    return graph, len(manifests)


def rust_violations(path, source):
    code = scrub(production(source))
    failures = []
    filename = pathlib.Path(path).name
    if filename != 'lib.rs' and re.search(r'\bimpl\s+Engine\s*\{', code):
        failures.append('service implementation cannot depend on Engine')
    if filename in ('indexing.rs', 'index_execution.rs', 'rust_inputs.rs'):
        if re.search(r'\b(?:Engine|Store|TaskRepository)\b|\.\s*jobs\b', code):
            failures.append('index receives task context and index ports, not the global runtime/store')
    if filename in ('history.rs', 'reports.rs', 'worker_service.rs', 'query_service.rs'):
        if re.search(r'\bEngine\b|\.\s*jobs\b', code):
            failures.append('service cannot access the Engine or global jobs')
    if re.search(r'Target\s*=\s*Engine\b', code):
        failures.append('Deref to Engine bypasses ownership')
    if filename == 'lib.rs':
        match = re.search(r'pub\s+struct\s+Engine\s*\{([^}]+)\}', code)
        if match and re.search(r'\b(?:Store|Mutex|BTreeMap|HashMap|Semaphore|AtomicBool|TaskRepository|ResourceBudget)\b', match[1]):
            failures.append('Engine must assemble services, not own runtime state')
    if filename == 'ports.rs':
        match = re.search(r'trait\s+IndexBaselineReader\b[^\{]*\{([^}]+)\}', code)
        if match and 'rust_' in match[1]:
            failures.append('Rust baseline is a separate capability')
    language = {'java.rs':'java', 'rust_inputs.rs':'rust', 'python_inputs.rs':'python'}.get(filename)
    if language:
        if language != 'java' and re.search(r'\bWorker\s*::\s*start\s*\(', code):
            failures.append(f'{language} input unit cannot start the Java worker')
        for other in {'java', 'rust', 'python'}-{language}:
            if re.search(r'\b'+other+r'_inputs\s*::|\bprepare_'+other+r'_(?:unit|inputs)\s*\(|\bWorker\s*::\s*start_'+other+r'\s*\(', code):
                failures.append(f'{language} input unit cannot orchestrate {other}')
    return failures


def java_violations(path, source):
    code = scrub(source)
    failures = []
    if '/analysis-core/src/main/java/' in '/'+str(path):
        if re.search(r'\b(?:com\.sun\.(?:tools\.javac|source)|jdk\.internal|javax\.lang\.model|javax\.tools)\.', code):
            failures.append('compiler types belong only in the Java 8 worker source set')
        if re.search(r'\bcom\.anatomist\.framework\.lombok\.', code):
            failures.append('core cannot depend on a framework implementation')
    if '/src/main/java/' in '/'+str(path) and re.search(r'\bcom\.github\.javaparser\.', code):
        failures.append('Java backend is javac only')
    if '/src/main/java/' in '/'+str(path) and re.search(r'\.overlays\s*\(\s*\)', code):
        failures.append('production overlays must receive the existing analysis account')
    if pathlib.Path(path).name == 'JavaModelExtension.java':
        required = r'\bJavaAnalysisContext\s+augment\s*\(\s*[\w.]+\s+\w+\s*,\s*[\w.]+\s+\w+\s*,\s*(?:\w+\.)*AnalysisContext\s+\w+\s*\)\s*;'
        if not re.search(required, code):
            failures.append('model extension must implement the budget callback; no default legacy adapter')
    if pathlib.Path(path).name == 'JavaModelExtensions.java':
        if not re.search(r'this\.resources\s*=\s*Objects\.requireNonNull\(\s*resources\b', code):
            failures.append('production extension account must be non-null')
    return failures


def maven_dependencies(path):
    pom = ET.parse(path).getroot()
    ns = {'p':'http://maven.apache.org/POM/4.0.0'}
    return {item.findtext('p:artifactId', namespaces=ns) for item in pom.findall('./p:dependencies/p:dependency', ns)}


def check(root):
    failures = []
    graph, manifest_count = dependency_graph(root)
    constraints = {
        'codeana-query': {'codeana-storage', 'codeana-application', 'rusqlite', 'sqlx', 'diesel'},
        'codeana-java-worker': {'codeana-storage', 'codeana-rust-adaptor', 'codeana-python-adaptor', 'jni', 'jni-sys'},
        'codeana-model': {'codeana-storage', 'codeana-application', 'codeana-query', 'codeana-contracts'},
        'codeana-rust-worker': {'codeana-java-worker', 'codeana-python-adaptor'},
        'codeana-python-worker': {'codeana-java-worker', 'codeana-rust-adaptor'},
    }
    for missing in sorted(constraints.keys()-graph.keys()):
        failures.append('Missing boundary package: '+missing)
    for start, forbidden in constraints.items():
        path = dependency_path(graph, start, forbidden)
        if path:
            failures.append('Cargo dependency boundary: '+' -> '.join(path))
    for path in sorted((root/'crates/application/src').glob('*.rs')):
        failures.extend(f'{path.relative_to(root)}: {message}' for message in rust_violations(path, path.read_text()))
    java_files = list((root/'lang-java-adaptor').glob('*/src/main/java/**/*.java'))
    for path in sorted(java_files):
        failures.extend(f'{path.relative_to(root)}: {message}' for message in java_violations(path.relative_to(root), path.read_text()))
    java = root/'lang-java-adaptor'
    if (java/'analysis-core/src/main/java/com/anatomist/framework/lombok').exists() or (java/'analysis-core/src/main/resources/lombok').exists():
        failures.append('Lombok implementation/resources must be outside core')
    if not (java/'framework-lombok/src/main/resources/lombok/annotation-copy-candidates.txt').is_file():
        failures.append('Lombok resource is part of its module')
    for pom in java.glob('*/pom.xml'):
        if any('javaparser' in name.lower() or 'symbolsolver' in name.lower() for name in maven_dependencies(pom)):
            failures.append(f'Java backend is javac only: {pom.relative_to(root)}')
    pom = ET.parse(java/'analysis-core/pom.xml').getroot()
    workers=[node for node in pom.iter() if node.tag.split('}')[-1]=='javac' and node.get('srcdir','').endswith('/src/worker/java')]
    if len(workers)!=1 or workers[0].get('source')!='8' or workers[0].get('target')!='8' or '${java8.home}/bin/javac' not in workers[0].get('executable',''):
        failures.append('Compiler worker must be separately compiled by Java 8')
    dependencies = {name:maven_dependencies(java/name/'pom.xml') for name in ['analysis-core','framework-lombok','framework-spring','framework-sofa','java-bridge']}
    for name in ['framework-lombok','framework-spring','framework-sofa']:
        if 'analysis-core' not in dependencies[name] or 'java-bridge' in dependencies[name]:
            failures.append(f'Maven dependency direction: {name} -> core')
    if 'framework-spring' not in dependencies['framework-sofa']:
        failures.append('SOFA keeps its real Spring dependency')
    if not {'framework-lombok','framework-spring','framework-sofa'} <= dependencies['java-bridge']:
        failures.append('Java bridge must compose the three framework modules')
    if dependencies['analysis-core'] & {'framework-lombok','framework-spring','framework-sofa','java-bridge'}:
        failures.append('core cannot depend on framework/assembly modules')
    return dict(status='failed' if failures else 'passed', manifests=manifest_count, java_files=len(java_files), violations=failures)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=pathlib.Path, default=ROOT)
    parser.add_argument('--evidence', type=pathlib.Path)
    args = parser.parse_args()
    result = check(args.root.resolve())
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['status']=='passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
